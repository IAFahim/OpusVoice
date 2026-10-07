using System.Diagnostics;
using OpusVoice.Qr.Audio;
using OpusVoice.Qr.Core.Rtp;
using OpusVoice.Qr.Core.Voice;

namespace OpusVoice.Qr.Services.Voice;

internal enum VoiceSourceKind
{
    TestTone,
    Microphone,
}

internal sealed record VoiceEngineOptions(
    string Host,
    int Port,
    int Bitrate,
    VoiceSourceKind Source,
    bool LoopbackMonitor,
    IVoiceTransport? Transport = null);

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
/// 20 ms frames, selectable bitrate), RFC 3550 RTP framing, datagram send over the chosen
/// transport — plain UDP, or an encrypted NAT-traversing Pinhole session supplied by the
/// caller (whose dialing is too slow for this synchronous Start and belongs to the UI).
/// With the loopback monitor enabled it also decodes its own frames and plays them back
/// when audio output exists.
/// </summary>
internal sealed class VoiceEngine : IDisposable
{
    public static readonly int[] SupportedBitrates = { 16000, 32000, 64000, 96000, 128000 };

    private readonly OpusStreamCodec _codec = new();
    private readonly Stopwatch _clock = new();
    private readonly object _gate = new();

    private IAudioSource? _source;
    private IAudioSink? _monitorSink;
    private IVoiceTransport? _transport;
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

    /// <summary>The transport this session sends through, for stats polling (e.g. Pinhole path/RTT).</summary>
    public IVoiceTransport? Transport
    {
        get { lock (_gate) { return _transport; } }
    }

    public void Start(VoiceEngineOptions options)
    {
        if (Interlocked.CompareExchange(ref _running, 1, 0) != 0) return;
        try
        {
            _codec.Bitrate = options.Bitrate;
            _packetizer = new RtpPacketizer(RtpPacketizer.RandomSsrc());
            if (options.Transport is { } external)
            {
                // A pre-connected transport (Pinhole): it reports its own state; forward it.
                _transport = external;
                _target = external.Target;
                external.StatusChanged += OnTransportStatus;
            }
            else
            {
                _transport = new UdpVoiceTransport(options.Host, options.Port);
                _target = $"{options.Host}:{options.Port}";
            }
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

    private void OnTransportStatus(string message) => StatusChanged?.Invoke(message);

    private void OnFrame(short[] pcm)
    {
        if (Volatile.Read(ref _running) != 1) return;
        IVoiceTransport? transport;
        RtpPacketizer? packetizer;
        lock (_gate)
        {
            transport = _transport;
            packetizer = _packetizer;
        }

        if (transport is null || packetizer is null) return;
        try
        {
            var encoded = new byte[OpusStreamCodec.MaxFrameBytes];
            int length = _codec.EncodeFrame(pcm, encoded);
            if (length <= 0) return;

            byte[] packet = packetizer.NextPacket(encoded.AsSpan(0, length));
            transport.Send(packet);

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
            if (_transport is { } transport)
            {
                transport.StatusChanged -= OnTransportStatus;
                TryDispose(transport);
            }
            _transport = null;
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
