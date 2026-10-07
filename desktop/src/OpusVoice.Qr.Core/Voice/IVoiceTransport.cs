namespace OpusVoice.Qr.Core.Voice;

/// <summary>
/// A one-way datagram sink for RTP packets: the Voice tab's transport seam. UDP is the
/// plain implementation; the Pinhole library adds an encrypted NAT-traversing one that
/// also reports live connection state (path kind, relay usage, round-trip time).
/// </summary>
public interface IVoiceTransport : IDisposable
{
    /// <summary>Human-readable target, e.g. "192.168.1.4:5004" or "pinhole 0x1a2b… (direct)".</summary>
    string Target { get; }

    /// <summary>Sends one RTP packet. Called from the capture thread; implementations must not
    /// buffer or retry. A throw means this packet was lost — the engine counts it and moves on.</summary>
    void Send(ReadOnlySpan<byte> packet);

    /// <summary>Raised (possibly on a background thread) with human-readable transport progress:
    /// connection established, path upgrades, relay fallbacks, shutdown.</summary>
    event Action<string>? StatusChanged;
}
