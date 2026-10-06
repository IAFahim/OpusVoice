namespace OpusVoice.Qr.Core.Rtp;

/// <summary>
/// Frames Opus packets into RTP like the Android sender: sequence number +1 per packet,
/// timestamp +960 per packet (20 ms at 48 kHz), random 32-bit SSRC.
/// </summary>
public sealed class RtpPacketizer
{
    public const int SamplesPerFrame = 960; // 20 ms at 48 kHz

    private readonly uint _ssrc;
    private ushort _sequence;
    private uint _timestamp;

    public RtpPacketizer(uint ssrc, ushort? initialSequence = null, uint initialTimestamp = 0)
    {
        _ssrc = ssrc;
        _sequence = initialSequence ?? (ushort)Random.Shared.Next(ushort.MaxValue + 1);
        _timestamp = initialTimestamp;
    }

    /// <summary>A random 32-bit synchronization source, per RFC 3550.</summary>
    public static uint RandomSsrc() => (uint)Random.Shared.NextInt64(uint.MaxValue + 1L);

    public uint Ssrc => _ssrc;

    public ushort SequenceNumber => _sequence;

    public uint Timestamp => _timestamp;

    public byte[] NextPacket(ReadOnlySpan<byte> opusFrame)
    {
        byte[] packet = RtpPacket.Serialize(_sequence, _timestamp, _ssrc, opusFrame);
        _sequence++;
        _timestamp += SamplesPerFrame;
        return packet;
    }
}
