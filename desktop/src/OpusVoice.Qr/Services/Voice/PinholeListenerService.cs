using System.Diagnostics;
using OpusVoice.Qr.Audio;
using OpusVoice.Qr.Core.Rtp;
using OpusVoice.Qr.Pinhole;
using OpusVoice.Qr.Services.Voice;

namespace OpusVoice.Qr.Services.Voice;

/// <summary>Snapshot for the listener panel: what to show while waiting for and serving a peer.</summary>
internal sealed record PinholeListenStats(
    string ConnectionString,
    string? IrohTicket,
    bool RelayLive,
    string? Peer,
    string? Path,
    long PacketsReceived,
    long BytesReceived,
    TimeSpan Elapsed,
    bool Playing,
    string? LastError);

/// <summary>
/// The Voice tab's receiving half: binds a Pinhole listener, publishes its connection string
/// for the phone to scan or paste, and plays accepted peers' RTP/Opus streams through the
/// machine's default audio output — the desktop counterpart of the app's loopback monitor,
/// pointed at the network instead of the self.
/// </summary>
internal sealed class PinholeListenerService : IDisposable
{
    private readonly OpusStreamCodec _codec = new();
    private readonly Stopwatch _clock = new();
    private readonly object _gate = new();

    private PinholeVoiceListener? _listener;
    private PinholePeerSession? _peer;
    private IAudioSink? _sink;
    private CancellationTokenSource? _acceptCts;
    private long _packets;
    private long _bytes;
    private string? _peerLabel;
    private string? _lastError; // written via Volatile, read under _gate
    private int _running;
    private short[] _decodeBuffer = new short[OpusStreamCodec.FrameSamples * 4];

    /// <summary>Raised on background threads with human-readable progress messages.</summary>
    public event Action<string>? StatusChanged;

    public bool IsRunning => Volatile.Read(ref _running) == 1;

    /// <summary>Binds the listener. Returns false (with a status message) when binding fails.</summary>
    public async Task<bool> StartAsync(bool publishIroh)
    {
        if (Interlocked.CompareExchange(ref _running, 1, 0) != 0) return true;
        try
        {
            _listener = await PinholeVoiceListener.StartAsync(publishIroh).ConfigureAwait(false);
            _clock.Restart();
            _acceptCts = new CancellationTokenSource();
            _ = Task.Run(() => AcceptLoopAsync(_acceptCts.Token));
            StatusChanged?.Invoke($"Listening — connection string ready (relay live: {_listener.HasRelay}).");
            return true;
        }
        catch (Exception ex)
        {
            Interlocked.Exchange(ref _running, 0);
            StatusChanged?.Invoke($"Could not bind the Pinhole listener ({ex.Message.Split('\n')[0].Trim()}).");
            return false;
        }
    }

    private async Task AcceptLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            PinholeVoiceListener? listener;
            lock (_gate) { listener = _listener; }
            if (listener is null) return;

            PinholePeerSession peer;
            try
            {
                peer = await listener.AcceptAsync(ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (Exception ex)
            {
                Volatile.Write(ref _lastError, ex.Message.Split('\n')[0].Trim());
                StatusChanged?.Invoke($"Accept failed ({ex.Message.Split('\n')[0].Trim()}).");
                return;
            }

            lock (_gate)
            {
                TryDispose(_peer);
                TryDispose(_sink);
                _peer = peer;
                _peerLabel = peer.Path;
                _sink = AudioSinkFactory.Create();
                if (_sink is null)
                {
                    StatusChanged?.Invoke("Peer connected — audio output is unavailable, counting packets only.");
                }
                else
                {
                    StatusChanged?.Invoke($"Peer connected ({peer.Path}) — playing incoming audio.");
                }
            }
            peer.StatusChanged += message => StatusChanged?.Invoke(message);

            try
            {
                await foreach (ReadOnlyMemory<byte> datagram in peer.ReadAllAsync(ct).ConfigureAwait(false))
                {
                    OnDatagram(datagram.Span);
                }
            }
            catch (OperationCanceledException)
            {
                return;
            }
            // A peer that goes away loops us back to AcceptAsync for the next one.
        }
    }

    private void OnDatagram(ReadOnlySpan<byte> datagram)
    {
        if (datagram.Length < RtpPacket.HeaderLength) return;
        byte[] copy = datagram.ToArray();
        ParsedRtpPacket packet;
        try
        {
            packet = RtpPacket.Parse(copy);
        }
        catch (Exception)
        {
            return; // not RTP: ignore rather than feed garbage to the decoder
        }

        Interlocked.Increment(ref _packets);
        Interlocked.Add(ref _bytes, copy.Length);

        IAudioSink? sink;
        lock (_gate) { sink = _sink; }
        if (sink is null) return;

        int samples = _codec.DecodeFrame(packet.Payload, packet.Payload.Length, _decodeBuffer);
        if (samples > 0)
        {
            var pcm = new short[samples];
            Array.Copy(_decodeBuffer, pcm, samples);
            if (!sink.TryWrite(pcm, samples))
            {
                lock (_gate)
                {
                    TryDispose(_sink);
                    _sink = null;
                }
                StatusChanged?.Invoke("Audio output failed — continuing to count packets.");
            }
        }
    }

    public PinholeListenStats? Snapshot()
    {
        lock (_gate)
        {
            if (_listener is null) return null;
            return new PinholeListenStats(
                _listener.ConnectionString,
                _listener.IrohTicket,
                _listener.HasRelay,
                _peerLabel,
                _peer?.Path,
                Volatile.Read(ref _packets),
                Volatile.Read(ref _bytes),
                _clock.Elapsed,
                _sink is not null,
                _lastError);
        }
    }

    public void Stop()
    {
        if (Interlocked.Exchange(ref _running, 0) != 1) return;
        _acceptCts?.Cancel();
        _acceptCts?.Dispose();
        _acceptCts = null;
        lock (_gate)
        {
            TryDispose(_peer);
            _peer = null;
            TryDispose(_sink);
            _sink = null;
            if (_listener is not null)
            {
                _listener.DisposeAsync().AsTask().GetAwaiter().GetResult();
                _listener = null;
            }
        }
        StatusChanged?.Invoke("Listener stopped.");
    }

    private static void TryDispose(IDisposable? disposable)
    {
        try
        {
            disposable?.Dispose();
        }
        catch (Exception)
        {
            // teardown must not throw on background threads
        }
    }

    public void Dispose() => Stop();
}
