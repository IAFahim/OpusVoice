using System.Diagnostics;
using NAudio.Wave;
using OpusVoice.Qr.Audio;

namespace OpusVoice.Qr.Services.Voice;

/// <summary>A source of 16-bit mono 48 kHz PCM frames of exactly 960 samples (20 ms).</summary>
internal interface IAudioSource : IDisposable
{
    /// <summary>Raised on the source's own thread for every captured frame.</summary>
    event Action<short[]>? FrameAvailable;

    /// <summary>Raised on the source's own thread when capture cannot continue.</summary>
    event Action<string>? Failed;

    void Start();
}

internal static class AudioSourceFactory
{
    /// <summary>Best-effort microphone capture: NAudio's WaveInEvent on Windows, arecord elsewhere.</summary>
    public static IAudioSource CreateMicrophone()
        => OperatingSystem.IsWindows() ? new WindowsWaveInSource() : new ArecordSource();
}

/// <summary>
/// The built-in 440 Hz test tone: pure C#, no audio hardware needed, so the streaming pipeline
/// can be proven even on headless machines. A Stopwatch-paced thread emits 20 ms frames.
/// </summary>
internal sealed class TestToneSource : IAudioSource
{
    public const double Frequency = 440.0;

    private CancellationTokenSource? _cts;
    private Thread? _thread;

    public event Action<short[]>? FrameAvailable;
    public event Action<string>? Failed;

    public void Start()
    {
        if (_thread is not null) return;
        _cts = new CancellationTokenSource();
        _thread = new Thread(() => Run(_cts.Token)) { IsBackground = true, Name = "opusvoice-tone" };
        _thread.Start();
    }

    private void Run(CancellationToken ct)
    {
        try
        {
            var frame = new short[OpusStreamCodec.FrameSamples];
            double phase = 0;
            var clock = Stopwatch.StartNew();
            long frequency = Stopwatch.Frequency;
            // 20 ms in Stopwatch ticks (Frequency-based, NOT TimeSpan.Ticks — on Linux
            // Stopwatch runs at 1 GHz, so TimeSpan ticks would pace frames 100× too fast).
            long frameTicks = (long)Math.Round(frequency * 0.020);
            long nextDeadline = clock.ElapsedTicks + frameTicks;

            while (!ct.IsCancellationRequested)
            {
                TestTone.FillSineFrame(frame, ref phase, Frequency);
                FrameAvailable?.Invoke(frame);

                nextDeadline += frameTicks;
                long remaining = nextDeadline - clock.ElapsedTicks;
                if (remaining < -10 * frameTicks)
                {
                    nextDeadline = clock.ElapsedTicks + frameTicks; // fell far behind: resync
                }
                else
                {
                    int sleepMs = (int)(remaining * 1000 / frequency) - 1;
                    if (sleepMs > 0) ct.WaitHandle.WaitOne(sleepMs);
                    while (!ct.IsCancellationRequested && clock.ElapsedTicks < nextDeadline)
                    {
                        Thread.SpinWait(16);
                    }
                }
            }
        }
        catch (Exception ex)
        {
            Fail($"test tone generator failed ({ex.Message.Split('\n')[0].Trim()})");
        }
    }

    private void Fail(string message) => Failed?.Invoke(message);

    public void Dispose()
    {
        try
        {
            _cts?.Cancel();
            _thread?.Join(TimeSpan.FromMilliseconds(500));
        }
        catch (Exception)
        {
            // teardown hiccups on a background thread are not fatal
        }
        finally
        {
            _cts?.Dispose();
        }
    }
}

/// <summary>
/// Linux microphone capture via <c>arecord -f S16_LE -r 48000 -c 1</c>: reads 1920-byte frames
/// from stdout. Missing binaries, missing devices and permission problems all degrade to
/// <see cref="Failed"/> — never a crash.
/// </summary>
internal sealed class ArecordSource : IAudioSource
{
    private Process? _process;

    public event Action<short[]>? FrameAvailable;
    public event Action<string>? Failed;

    public void Start()
    {
        try
        {
            var info = new ProcessStartInfo("arecord")
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
            };
            foreach (string arg in new[] { "-q", "-f", "S16_LE", "-r", "48000", "-c", "1" })
            {
                info.ArgumentList.Add(arg);
            }

            _process = Process.Start(info) ?? throw new InvalidOperationException("arecord did not start");
            // Drain stderr continuously: a redirected-but-unread pipe fills (~64 KiB of
            // overrun warnings) and then blocks arecord itself, stalling the pipeline.
            _process.ErrorDataReceived += (_, _) => { };
            _process.BeginErrorReadLine();
            var reader = new Thread(ReadStream) { IsBackground = true, Name = "opusvoice-arecord" };
            reader.Start();
        }
        catch (Exception ex)
        {
            Fail($"Could not start arecord ({ex.Message.Split('\n')[0].Trim()}) — is alsa-utils installed?");
        }
    }

    private void ReadStream()
    {
        try
        {
            var raw = new byte[OpusStreamCodec.FrameSamples * 2];
            Stream stdout = _process!.StandardOutput.BaseStream;
            while (true)
            {
                int read = 0;
                while (read < raw.Length)
                {
                    int chunk = stdout.Read(raw, read, raw.Length - read);
                    if (chunk <= 0)
                    {
                        Fail("The microphone stream ended — falling back to the test tone is the next step.");
                        return;
                    }

                    read += chunk;
                }

                var frame = new short[raw.Length / 2];
                Buffer.BlockCopy(raw, 0, frame, 0, raw.Length); // little-endian host assumed
                FrameAvailable?.Invoke(frame);
            }
        }
        catch (Exception ex)
        {
            Fail($"Microphone capture failed ({ex.Message.Split('\n')[0].Trim()}).");
        }
    }

    private void Fail(string message) => Failed?.Invoke(message);

    public void Dispose()
    {
        try
        {
            if (_process is { HasExited: false })
            {
                _process.Kill(entireProcessTree: true);
            }
        }
        catch (Exception)
        {
            // the process may already be gone
        }

        try
        {
            _process?.Dispose();
        }
        catch (Exception)
        {
            // not fatal
        }
    }
}

/// <summary>Windows microphone capture via NAudio's WaveInEvent.</summary>
[System.Runtime.Versioning.SupportedOSPlatform("windows")]
internal sealed class WindowsWaveInSource : IAudioSource
{
    private readonly Queue<byte> _pending = new();
    private WaveInEvent? _waveIn;

    public event Action<short[]>? FrameAvailable;
    public event Action<string>? Failed;

    public void Start()
    {
        if (!OperatingSystem.IsWindows())
        {
            Fail("NAudio capture is Windows-only.");
            return;
        }

        try
        {
            _waveIn = new WaveInEvent
            {
                WaveFormat = new WaveFormat(OpusStreamCodec.SampleRate, 16, OpusStreamCodec.Channels),
                BufferMilliseconds = 60,
                NumberOfBuffers = 3,
            };
            _waveIn.DataAvailable += OnDataAvailable;
            _waveIn.StartRecording();
        }
        catch (Exception ex)
        {
            Fail($"Could not open the microphone ({ex.Message.Split('\n')[0].Trim()}).");
        }
    }

    private void OnDataAvailable(object? sender, WaveInEventArgs e)
    {
        try
        {
            lock (_pending)
            {
                for (int i = 0; i < e.BytesRecorded; i++)
                {
                    _pending.Enqueue(e.Buffer[i]);
                }
            }

            while (true)
            {
                byte[] raw;
                lock (_pending)
                {
                    if (_pending.Count < OpusStreamCodec.FrameSamples * 2) break;
                    raw = new byte[OpusStreamCodec.FrameSamples * 2];
                    for (int i = 0; i < raw.Length; i++)
                    {
                        raw[i] = _pending.Dequeue();
                    }
                }

                var frame = new short[OpusStreamCodec.FrameSamples];
                Buffer.BlockCopy(raw, 0, frame, 0, raw.Length);
                FrameAvailable?.Invoke(frame);
            }
        }
        catch (Exception ex)
        {
            Fail($"Microphone capture failed ({ex.Message.Split('\n')[0].Trim()}).");
        }
    }

    private void Fail(string message) => Failed?.Invoke(message);

    public void Dispose()
    {
        try
        {
            if (_waveIn is not null)
            {
                _waveIn.DataAvailable -= OnDataAvailable;
                _waveIn.StopRecording();
                _waveIn.Dispose();
            }
        }
        catch (Exception)
        {
            // not fatal
        }
    }
}
