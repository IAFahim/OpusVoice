using System.Diagnostics;
using System.Net.Sockets;
using OpusVoice.Qr.Audio;
using OpusVoice.Qr.Core.Rtp;

namespace OpusVoice.Qr.Services.Voice;

internal enum VoiceSourceKind
{
    TestTone,
    Microphone,
}

internal sealed record VoiceEngineOptions(string Host, int Port, int Bitrate, VoiceSourceKind Source, bool LoopbackMonitor);

internal sealed record VoiceStats(
    string Target,
    int Bitrate,
    long PacketsSent,
    long BytesSent,
    long OpusBytes,
    TimeSpan Elapsed,
    bool LoopbackActive,
    long Errors,
    string? LastError);

/// <summary>
/// The voice pipeline: PCM frames in (test tone or microphone), Opus encode (48 kHz mono,
/// 20 ms frames, selectable bitrate), RFC 3550 RTP framing, UDP send. With the loopback monitor
/// enabled it also decodes its own frames and plays them back when audio output exists.
/// </summary>
internal sealed class VoiceEngine : IDisposable
{
    public static readonly int[] SupportedBitrates = { 16000, 32000, 64000, 96000, 128000 };

    private readonly OpusStreamCodec _codec = new();
    private readonly Stopwatch _clock = new();
    private readonly object _gate = new();

    private IAudioSource? _source;
    private IAudioSink? _monitorSink;
    private UdpClient? _udp;
    private RtpPacketizer? _packetizer;
    private string _target = string.Empty;
    private long _packetsSent;
    private long _bytesSent;
    private long _opusBytes;
    private long _errors;
    private string? _lastError;
    private bool _loopbackWanted;
    private bool _loopbackActive;
    private int _running; // Interlocked gate: a engine instance streams exactly one session

    /// <summary>Raised on a capture thread with human-readable progress messages.</summary>
    public event Action<string>? StatusChanged;

    /// <summary>Raised on a capture thread when streaming cannot continue; the engine has stopped itself.</summary>
    public event Action<string>? Failed;

    /// <summary>Raised on a capture thread whenever the engine stops (user stop, source failure, error).</summary>
    public event Action? Stopped;

    public bool IsRunning => Volatile.Read(ref _running) == 1;

    public void Start(VoiceEngineOptions options)
    {
        if (Interlocked.CompareExchange(ref _running, 1, 0) != 0) return;
        try
        {
            _codec.Bitrate = options.Bitrate;
            _packetizer = new RtpPacketizer(RtpPacketizer.RandomSsrc());
            _udp = new UdpClient();
            _udp.Connect(options.Host, options.Port); // resolves once; sends afterwards are connectionless-fast
            _target = $"{options.Host}:{options.Port}";
            _loopbackWanted = options.LoopbackMonitor;

            _source = options.Source switch
            {
                VoiceSourceKind.TestTone => new TestToneSource(),
                VoiceSourceKind.Microphone => AudioSourceFactory.CreateMicrophone(),
                _ => new TestToneSource(),
            };
            _source.FrameAvailable += OnFrame;
            _source.Failed += reason => Fail($"Source stopped: {reason}");

            if (_loopbackWanted)
            {
                _monitorSink = AudioSinkFactory.Create();
                if (_monitorSink is null)
                {
                    StatusChanged?.Invoke($"Streaming to {_target} — audio output is unavailable on this platform, loopback disabled.");
                }
            }

            _clock.Restart();
            StatusChanged?.Invoke($"Streaming Opus ({options.Bitrate / 1000} kbps, 20 ms frames, RTP payload type 111) to {_target}.");
            _source.Start();
        }
        catch (Exception ex)
        {
            Fail($"Could not start streaming ({ex.Message.Split('\n')[0].Trim()}).");
        }
    }

    private void OnFrame(short[] pcm)
    {
        if (Volatile.Read(ref _running) != 1) return;
        UdpClient? udp;
        RtpPacketizer? packetizer;
        lock (_gate)
        {
            udp = _udp;
            packetizer = _packetizer;
        }

        if (udp is null || packetizer is null) return;
        try
        {
            var encoded = new byte[OpusStreamCodec.MaxFrameBytes];
            int length = _codec.EncodeFrame(pcm, encoded);
            if (length <= 0) return;

            byte[] packet = packetizer.NextPacket(encoded.AsSpan(0, length));
            udp.Send(packet, packet.Length);

            Interlocked.Increment(ref _packetsSent);
            Interlocked.Add(ref _bytesSent, packet.Length);
            Interlocked.Add(ref _opusBytes, length);

            IAudioSink? sink = _monitorSink;
            if (sink is not null)
            {
                var decoded = new short[pcm.Length];
                int samples = _codec.DecodeFrame(encoded, length, decoded);
                if (samples > 0 && !sink.TryWrite(decoded, samples))
                {
                    _monitorSink = null;
                    sink.Dispose();
                    Volatile.Write(ref _loopbackActive, false);
                    StatusChanged?.Invoke("Audio output is unavailable — loopback monitor disabled, streaming continues.");
                }
                else if (samples > 0)
                {
                    Volatile.Write(ref _loopbackActive, true);
                }
            }
        }
        catch (Exception ex)
        {
            Interlocked.Increment(ref _errors);
            Volatile.Write(ref _lastError, ex.Message.Split('\n')[0].Trim());
        }
    }

    public VoiceStats Snapshot() => new(
        Target: _target,
        Bitrate: _codec.Bitrate,
        PacketsSent: Volatile.Read(ref _packetsSent),
        BytesSent: Volatile.Read(ref _bytesSent),
        OpusBytes: Volatile.Read(ref _opusBytes),
        Elapsed: _clock.Elapsed,
        LoopbackActive: Volatile.Read(ref _loopbackWanted) && Volatile.Read(ref _loopbackActive),
        Errors: Volatile.Read(ref _errors),
        LastError: Volatile.Read(ref _lastError));

    public void Stop()
    {
        if (Interlocked.CompareExchange(ref _running, 0, 1) != 1) return;
        TearDown();
        StatusChanged?.Invoke("Stopped.");
        Stopped?.Invoke();
    }

    private void Fail(string reason)
    {
        // Only the owner of the 1→0 transition reports a failure: a source hitting EOF
        // after Stop() killed it (arecord's stdout closes) is a clean stop, not an error.
        if (Interlocked.CompareExchange(ref _running, 0, 1) != 1) return;
        TearDown();
        Failed?.Invoke(reason);
        Stopped?.Invoke();
    }

    private void TearDown()
    {
        lock (_gate)
        {
            TryDispose(_source);
            _source = null;
            TryDispose(_udp);
            _udp = null;
            TryDispose(_monitorSink);
            _monitorSink = null;
        }
    }

    private static void TryDispose(IDisposable? disposable)
    {
        try
        {
            disposable?.Dispose();
        }
        catch (Exception)
        {
            // teardown must not throw on capture threads
        }
    }

    public void Dispose() => Stop();
}
