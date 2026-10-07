using System.Diagnostics;
using NAudio.Wave;
using OpusVoice.Qr.Audio;

namespace OpusVoice.Qr.Services.Voice;

/// <summary>A 16-bit mono 48 kHz playback sink for the loopback monitor.</summary>
internal interface IAudioSink : IDisposable
{
    /// <summary>Writes samples; returns false when playback is unavailable and should be disabled.</summary>
    bool TryWrite(short[] samples, int count);
}

internal static class AudioSinkFactory
{
    /// <summary>Best-effort playback: WaveOut on Windows, aplay on Linux; null when hopeless.</summary>
    public static IAudioSink? Create()
    {
        if (OperatingSystem.IsWindows()) return new WaveOutSink();
        if (OperatingSystem.IsLinux()) return new AplaySink();
        return null;
    }
}

/// <summary>Linux playback via <c>aplay</c>; fails soft when no audio device exists (headless machines).</summary>
internal sealed class AplaySink : IAudioSink
{
    private Process? _process;

    public bool TryWrite(short[] samples, int count)
    {
        try
        {
            if (_process is null)
            {
                var info = new ProcessStartInfo("aplay")
                {
                    RedirectStandardInput = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    UseShellExecute = false,
                };
                foreach (string arg in new[] { "-q", "-f", "S16_LE", "-r", "48000", "-c", "1" })
                {
                    info.ArgumentList.Add(arg);
                }

                _process = Process.Start(info);
                if (_process is null) return false;
                // Drain both pipes so aplay's chatter can never block it (same reasoning as arecord).
                _process.OutputDataReceived += (_, _) => { };
                _process.ErrorDataReceived += (_, _) => { };
                _process.BeginOutputReadLine();
                _process.BeginErrorReadLine();
            }

            var bytes = new byte[count * 2];
            Buffer.BlockCopy(samples, 0, bytes, 0, bytes.Length);
            _process.StandardInput.BaseStream.Write(bytes, 0, bytes.Length);
            _process.StandardInput.BaseStream.Flush();
            return true;
        }
        catch (Exception)
        {
            DisposeProcess();
            return false;
        }
    }

    private void DisposeProcess()
    {
        try
        {
            if (_process is { HasExited: false })
            {
                _process.Kill(entireProcessTree: true);
            }

            _process?.Dispose();
        }
        catch (Exception)
        {
            // already gone
        }

        _process = null;
    }

    public void Dispose() => DisposeProcess();
}

/// <summary>Windows playback via NAudio's WaveOutEvent with a buffered provider.</summary>
[System.Runtime.Versioning.SupportedOSPlatform("windows")]
internal sealed class WaveOutSink : IAudioSink
{
    private WaveOutEvent? _waveOut;
    private BufferedWaveProvider? _provider;

    public bool TryWrite(short[] samples, int count)
    {
        if (!OperatingSystem.IsWindows()) return false;
        try
        {
            if (_waveOut is null || _provider is null)
            {
                _provider = new BufferedWaveProvider(new WaveFormat(OpusStreamCodec.SampleRate, 16, OpusStreamCodec.Channels))
                {
                    DiscardOnBufferOverflow = true,
                };
                _waveOut = new WaveOutEvent();
                _waveOut.Init(_provider);
                _waveOut.Play();
            }

            var bytes = new byte[count * 2];
            Buffer.BlockCopy(samples, 0, bytes, 0, bytes.Length);
            _provider.AddSamples(bytes, 0, bytes.Length);
            return true;
        }
        catch (Exception)
        {
            Dispose();
            return false;
        }
    }

    public void Dispose()
    {
        try
        {
            _waveOut?.Stop();
            _waveOut?.Dispose();
        }
        catch (Exception)
        {
            // already gone
        }

        _waveOut = null;
        _provider = null;
    }
}
