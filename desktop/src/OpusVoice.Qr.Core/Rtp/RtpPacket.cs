namespace OpusVoice.Qr.Core.Rtp;

/// <summary>An RTP packet as recovered by a receiver: the 12-byte header fields plus the Opus payload.</summary>
public readonly record struct ParsedRtpPacket(int Version, bool Marker, int PayloadType, ushort SequenceNumber, uint Timestamp, uint Ssrc, byte[] Payload);

/// <summary>
/// RTP framing exactly like the OpusVoice Android app (RFC 3550): a fixed 12-byte header —
/// version 2 (first byte 0x80, no padding, no extension, no CSRCs), Opus payload type 111,
/// big-endian sequence number, big-endian timestamp, big-endian 32-bit SSRC — followed by the
/// raw Opus frame bytes. A receiving sink parses the header and strips these 12 bytes.
/// </summary>
public static class RtpPacket
{
    public const int HeaderLength = 12;
    public const byte VersionTwoFirstByte = 0x80;
    public const int OpusPayloadType = 111;

    public static byte[] Serialize(ushort sequenceNumber, uint timestamp, uint ssrc, ReadOnlySpan<byte> payload)
    {
        byte[] packet = new byte[HeaderLength + payload.Length];
        packet[0] = VersionTwoFirstByte;
        packet[1] = (byte)OpusPayloadType;
        packet[2] = (byte)(sequenceNumber >> 8);
        packet[3] = (byte)sequenceNumber;
        packet[4] = (byte)(timestamp >> 24);
        packet[5] = (byte)(timestamp >> 16);
        packet[6] = (byte)(timestamp >> 8);
        packet[7] = (byte)timestamp;
        packet[8] = (byte)(ssrc >> 24);
        packet[9] = (byte)(ssrc >> 16);
        packet[10] = (byte)(ssrc >> 8);
        packet[11] = (byte)ssrc;
        payload.CopyTo(packet.AsSpan(HeaderLength));
        return packet;
    }

    /// <summary>Parses and validates a packet from the wire, stripping the 12-byte header from the payload.</summary>
    public static ParsedRtpPacket Parse(ReadOnlySpan<byte> packet)
    {
        if (packet.Length < HeaderLength) throw new ArgumentException($"RTP packet is shorter than the {HeaderLength}-byte header");
        byte first = packet[0];
        if ((first >> 6) != 2) throw new ArgumentException("unsupported RTP version");
        if ((first & 0x3F) != 0) throw new ArgumentException("padding, header extensions and CSRCs are not supported");
        byte second = packet[1];
        int payloadType = second & 0x7F;
        var payload = new byte[packet.Length - HeaderLength];
        packet[HeaderLength..].CopyTo(payload);
        return new ParsedRtpPacket(
            Version: 2,
            Marker: (second & 0x80) != 0,
            PayloadType: payloadType,
            SequenceNumber: (ushort)((packet[2] << 8) | packet[3]),
            Timestamp: ((uint)packet[4] << 24) | ((uint)packet[5] << 16) | ((uint)packet[6] << 8) | packet[7],
            Ssrc: ((uint)packet[8] << 24) | ((uint)packet[9] << 16) | ((uint)packet[10] << 8) | packet[11],
            Payload: payload);
    }
}
