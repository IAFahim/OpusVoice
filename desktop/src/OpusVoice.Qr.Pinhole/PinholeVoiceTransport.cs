using System.Net;
using OpusVoice.Qr.Core.Voice;
using Pinhole;

namespace OpusVoice.Qr.Pinhole;

/// <summary>The outcome of dialing a Pinhole connection string: either a connected transport
/// or an actionable failure, mirroring <see cref="PinholeConnectResult"/> for the UI layer.</summary>
public sealed record PinholeDialOutcome(
    PinholeVoiceTransport? Transport,
    PinholeConnectFailure? Failure,
    string? ErrorMessage)
{
    public static PinholeDialOutcome Connected(PinholeVoiceTransport transport) => new(transport, null, null);
    public static PinholeDialOutcome Failed(PinholeConnectFailure failure, string message) => new(null, failure, message);
}

/// <summary>Everything the Voice tab shows about a live Pinhole session, as plain bindable values.</summary>
public sealed record PinholeVoiceStats(
    string PeerId,
    string State,          // punching / direct / relay / dead / closed, already humanized
    string Path,           // "direct 203.0.113.7:52114" / "relay use1-iroh…" / "none"
    string? RelayUrl,
    TimeSpan? LastRtt,
    TimeSpan? AverageRtt,
    long DroppedDatagrams,
    long FramesRejected);

/// <summary>
/// Dials a Pinhole connection string (as printed or QR-encoded by OpusVoice.Receiver, the
/// Android app's listener mode, or any Pinhole.Net node) and carries the Voice tab's RTP
/// packets through the encrypted session. The chain runs inside Pinhole — direct punch
/// first, iroh relay as the standing fallback — and this class surfaces the resulting path
/// state and round-trip times so the connection can be checked while it is being used.
/// </summary>
public sealed class PinholeVoiceTransport : IVoiceTransport
{
    private readonly PinholeNode _node;
    private readonly PinholeConnection _connection;
    private readonly ulong _peerId;
    private volatile bool _disposed;

    /// <summary>Raised (on a Pinhole background thread) with connection progress messages.</summary>
    public event Action<string>? StatusChanged;

    private PinholeVoiceTransport(PinholeNode node, PinholeConnection connection, ulong peerId)
    {
        _node = node;
        _connection = connection;
        _peerId = peerId;
        _connection.StateChanged += OnStateChanged;
    }

    /// <summary>The dialed peer's ID, as 16 hex characters.</summary>
    public string PeerId => $"0x{_peerId:X16}";

    public string Target => $"pinhole {PeerId} ({DescribePath()})";

    /// <summary>
    /// Binds a node and dials <paramref name="connectionString"/> (whitespace tolerated, the
    /// <c>pinhole1:</c> prefix optional). Returns a connected transport or a typed failure;
    /// never throws for expected dial outcomes. Unexpected infrastructure errors propagate.
    /// </summary>
    public static async Task<PinholeDialOutcome> TryConnectAsync(string? connectionString, CancellationToken ct = default)
    {
        PinholeNode? node = null;
        try
        {
            node = await PinholeNode.BindAsync(new PinholeOptions
            {
                // The dialer only sends; a peer echoing back must not OOM an unattended buffer.
                ReceiveBufferCapacity = 64 * 1024,
                // Keep NAT mappings alive through mute gaps without help from the caller.
                KeepaliveInterval = TimeSpan.FromSeconds(10),
            }, ct).ConfigureAwait(false);

            PinholeConnectResult result = await node.TryConnectAsync(connectionString, ct).ConfigureAwait(false);
            if (!result.IsSuccess)
            {
                await node.DisposeAsync().ConfigureAwait(false);
                return PinholeDialOutcome.Failed(result.Failure, result.ErrorMessage ?? result.Failure.ToString());
            }

            return PinholeDialOutcome.Connected(new PinholeVoiceTransport(node, result.Connection, ParsePeerId(connectionString)));
        }
        catch
        {
            if (node is not null) await node.DisposeAsync().ConfigureAwait(false);
            throw;
        }
    }

    private static ulong ParsePeerId(string? connectionString)
    {
        // The dialed string's own peer ID, parsed without Pinhole's help for the label only;
        // failure is cosmetic and falls back to the connection's runtime knowledge.
        try
        {
            string text = (connectionString ?? string.Empty).Trim();
            if (!text.Contains(':')) text = "pinhole1:" + text;
            return global::Pinhole.ConnectionString.TryParse(text, out var cs) ? cs.PeerId : 0;
        }
        catch
        {
            return 0;
        }
    }

    public void Send(ReadOnlySpan<byte> packet)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        _connection.Send(packet);
    }

    /// <summary>Sends one probe that schedules <see cref="PinholeVoiceStats.LastRtt"/>;
    /// never throws. The UI polls it on its stats timer to show live round-trip time.</summary>
    public void Ping() => _connection.Ping();

    /// <summary>A live snapshot for the UI: path kind, relay URL, RTTs, drop counters.</summary>
    public PinholeVoiceStats Snapshot() => new(
        PeerId,
        DescribeState(_connection.State),
        DescribePath(),
        _connection.Path.RelayUrl?.ToString(),
        _connection.LastRtt,
        _connection.AverageRtt,
        _connection.DroppedDatagrams,
        _connection.FramesRejected);

    private void OnStateChanged(PinholeConnectionState state) =>
        StatusChanged?.Invoke($"pinhole: {DescribeState(state)}");

    private static string DescribeState(PinholeConnectionState state) => state switch
    {
        PinholeConnectionState.Punching => "punching NAT…",
        PinholeConnectionState.Open => "direct path open",
        PinholeConnectionState.Degraded => "relay fallback (usable, higher latency)",
        PinholeConnectionState.Dead => "no usable path",
        PinholeConnectionState.Closed => "closed",
        _ => state.ToString().ToLowerInvariant(),
    };

    private string DescribePath()
    {
        PinholePath path = _connection.Path;
        return path.Kind switch
        {
            PathKind.Direct => $"direct {path.Remote}",
            PathKind.Relay => path.RelayUrl is null ? "relay" : $"relay {path.RelayUrl.Host}",
            _ => "no path",
        };
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _connection.StateChanged -= OnStateChanged;
        try
        {
            _connection.Dispose();
        }
        catch (Exception)
        {
            // teardown must not throw on capture threads
        }
        try
        {
            _node.Dispose();
        }
        catch (Exception)
        {
        }
        StatusChanged?.Invoke("pinhole: closed");
    }
}
