using OpusVoice.Qr.Core.Voice;
using Pinhole;

namespace OpusVoice.Qr.Pinhole;

/// <summary>
/// The accepting half of a Pinhole voice session: binds a node, publishes its connection
/// string (and optionally its signed native iroh endpoint ticket) for the phone to scan or
/// paste, then hands accepted peers' datagrams to the UI one at a time. This is the desktop
/// counterpart of OpusVoice.Receiver's pinhole mode, minus the disk capture.
/// </summary>
public sealed class PinholeVoiceListener : IAsyncDisposable
{
    private readonly PinholeNode _node;
    private volatile bool _disposed;

    /// <summary>Raised (on a background thread) with listener progress messages.</summary>
    public event Action<string>? StatusChanged;

    private PinholeVoiceListener(PinholeNode node) => _node = node;

    /// <summary>The string a peer dials: hand it to the phone by QR (the Generate tab can
    /// render it) or paste. Includes relay candidates, so it works from any network.</summary>
    public string ConnectionString => _node.ConnectionString;

    /// <summary>The native iroh endpoint ticket. Dialing it requires the signed
    /// discovery record, which this listener publishes by default.</summary>
    public string? IrohTicket => _node.IrohAddress?.ToString();

    /// <summary>The native iroh endpoint ID (stable across restarts only when the node's
    /// identity seed is persisted). Published through signed discovery by default.</summary>
    public string? IrohEndpointId => _node.IrohAddress?.EndpointId;

    /// <summary>Whether an iroh or TURN relay is currently live on this node. A peer that
    /// cannot punch directly will still reach this listener while this is true.</summary>
    public bool HasRelay => _node.HasRelay;

    /// <summary>
    /// Binds the listener with LAN discovery. <paramref name="publishIroh"/> publishes the signed
    /// endpoint-ID → Pinhole-key discovery record so the Android app can dial by native iroh
    /// ticket with peer authentication preserved. Publication includes direct candidates
    /// and is enabled by default; callers can explicitly disable it.
    /// </summary>
    public static async Task<PinholeVoiceListener> StartAsync(bool publishIroh = true, CancellationToken ct = default)
    {
        PinholeNode node = await PinholeNode.BindAsync(new PinholeOptions
        {
            ReceiveBufferCapacity = 64 * 1024, // the library's maximum
            PublishIrohAddress = publishIroh,
            PublishDirectIrohAddresses = true,
            EnableLanDiscovery = true,
            // Link-local candidates provide an additional direct path on the LAN.
            AdvertiseLinkLocal = true,
        }, ct).ConfigureAwait(false);
        return new PinholeVoiceListener(node);
    }

    /// <summary>Waits for one peer to dial and returns its connection. Call again for the
    /// next peer; each accepted connection is the caller's to dispose.</summary>
    public async Task<PinholePeerSession> AcceptAsync(CancellationToken ct = default)
    {
        StatusChanged?.Invoke("listening: waiting for a peer to dial…");
        PinholeConnection connection = await _node.AcceptAsync(ct).ConfigureAwait(false);
        StatusChanged?.Invoke("peer connected.");
        return new PinholePeerSession(connection);
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed) return;
        _disposed = true;
        try
        {
            await _node.DisposeAsync().ConfigureAwait(false);
        }
        catch (Exception)
        {
            // teardown must not throw
        }
    }
}

/// <summary>One accepted peer: a live datagram feed plus the same path/RTT reporting the
/// dialing side sees, so a phone-initiated session can be checked while it streams.</summary>
public sealed class PinholePeerSession : IAsyncDisposable, IDisposable
{
    private readonly PinholeConnection _connection;
    private volatile bool _disposed;

    internal PinholePeerSession(PinholeConnection connection)
    {
        _connection = connection;
        _connection.StateChanged += OnStateChanged;
    }

    private void OnStateChanged(PinholeConnectionState state) =>
        StatusChanged?.Invoke($"peer session: {state switch
        {
            PinholeConnectionState.Punching => "punching NAT…",
            PinholeConnectionState.Open => "direct path open",
            PinholeConnectionState.Degraded => "relay fallback (usable, higher latency)",
            PinholeConnectionState.Dead => "no usable path",
            PinholeConnectionState.Closed => "closed",
            _ => state.ToString().ToLowerInvariant(),
        }}");

    /// <summary>Raised (on a background thread) when the connection's state changes.</summary>
    public event Action<string>? StatusChanged;

    /// <summary>The connected path: "direct <endpoint>" or "relay <host>".</summary>
    public string Path => _connection.Path.Kind switch
    {
        PathKind.Direct => $"direct {_connection.Path.Remote}",
        PathKind.Relay => _connection.Path.RelayUrl is null ? "relay" : $"relay {_connection.Path.RelayUrl.Host}",
        _ => "no path",
    };

    /// <summary>Round-trip time of the most recent answered probe, if the peer answers pings.</summary>
    public TimeSpan? LastRtt => _connection.LastRtt;

    /// <summary>Sends one probe that schedules <see cref="LastRtt"/>; never throws.</summary>
    public void Ping() => _connection.Ping();

    /// <summary>Yields each datagram the peer sends, in order, until the peer disconnects or
    /// the enumeration is cancelled. Buffering absorbs bursts; drops are visible via the
    /// connection's dropped-datagram counter surfaced on the next yield.</summary>
    public IAsyncEnumerable<ReadOnlyMemory<byte>> ReadAllAsync(CancellationToken ct = default) =>
        _connection.ReadAllAsync(ct);

    public async ValueTask DisposeAsync()
    {
        if (_disposed) return;
        _disposed = true;
        _connection.StateChanged -= OnStateChanged;
        try
        {
            await _connection.DisposeAsync().ConfigureAwait(false);
        }
        catch (Exception)
        {
            // teardown must not throw
        }
    }

    /// <summary>Sync facade over <see cref="DisposeAsync"/>: the underlying dispose completes
    /// synchronously, and callers on UI/capture threads want the plain pattern.</summary>
    public void Dispose() => DisposeAsync().AsTask().GetAwaiter().GetResult();
}
