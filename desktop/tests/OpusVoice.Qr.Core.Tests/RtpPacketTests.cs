using OpusVoice.Qr.Core.Rtp;

namespace OpusVoice.Qr.Core.Tests;

/// <summary>
/// The RTP byte layout the Android sender produces — a separate C# sink parses these packets
/// and strips the 12-byte header, so the framing is pinned down byte for byte.
/// </summary>
public class RtpPacketTests
{
    [Fact]
    public void SerializeLaysOutTheHeaderExactly()
    {
        byte[] packet = RtpPacket.Serialize(
            sequenceNumber: 0x1234,
            timestamp: 0xAABBCCDD,
            ssrc: 0x11223344,
            payload: new byte[] { 0xDE, 0xAD });

        Assert.Equal(
            new byte[] { 0x80, 111, 0x12, 0x34, 0xAA, 0xBB, 0xCC, 0xDD, 0x11, 0x22, 0x33, 0x44, 0xDE, 0xAD },
            packet);
        Assert.Equal(14, packet.Length);
    }

    [Fact]
    public void SerializeWithEmptyPayloadIsHeaderOnly()
    {
        byte[] packet = RtpPacket.Serialize(1, 2, 3, Array.Empty<byte>());
        Assert.Equal(12, packet.Length);
        Assert.Equal(0x80, packet[0]);
        Assert.Equal(111, packet[1]);
    }

    [Fact]
    public void SerializeUsesBigEndianThroughout()
    {
        byte[] packet = RtpPacket.Serialize(0x0102, 0x03040506, 0x0708090A, new byte[] { 0xFF });
        Assert.Equal(0x01, packet[2]);  // sequence high byte first
        Assert.Equal(0x02, packet[3]);
        Assert.Equal(0x03, packet[4]);  // timestamp high byte first
        Assert.Equal(0x04, packet[5]);
        Assert.Equal(0x05, packet[6]);
        Assert.Equal(0x06, packet[7]);
        Assert.Equal(0x07, packet[8]);  // SSRC high byte first
        Assert.Equal(0x08, packet[9]);
        Assert.Equal(0x09, packet[10]);
        Assert.Equal(0x0A, packet[11]);
        Assert.Equal(0xFF, packet[12]); // payload starts at byte 12
    }

    [Fact]
    public void ParseRecoversEveryFieldAndStripsTheHeader()
    {
        byte[] opus = new byte[] { 0x01, 0x02, 0x03 };
        byte[] packet = RtpPacket.Serialize(0xBEEF, 48000, 0xDEADBEEF, opus);

        ParsedRtpPacket parsed = RtpPacket.Parse(packet);
        Assert.Equal(2, parsed.Version);
        Assert.Equal(111, parsed.PayloadType);
        Assert.Equal(0xBEEF, parsed.SequenceNumber);
        Assert.Equal(48000u, parsed.Timestamp);
        Assert.Equal(0xDEADBEEFu, parsed.Ssrc);
        Assert.Equal(opus, parsed.Payload);
        Assert.Equal(3, parsed.Payload.Length);
    }

    [Fact]
    public void ParseRejectsBrokenPackets()
    {
        Assert.Throws<ArgumentException>(() => RtpPacket.Parse(new byte[] { 0x80, 111, 0, 1, 0, 0, 0, 2, 0, 0, 0, 3 }.AsSpan(0, 11).ToArray()));
        Assert.Throws<ArgumentException>(() => RtpPacket.Parse(new byte[] { 0x40, 111, 0, 1, 0, 0, 0, 2, 0, 0, 0, 3 })); // version 1
        Assert.Throws<ArgumentException>(() => RtpPacket.Parse(new byte[] { 0xA0, 111, 0, 1, 0, 0, 0, 2, 0, 0, 0, 3 })); // padding bit set
        Assert.Throws<ArgumentException>(() => RtpPacket.Parse(new byte[] { 0x91, 111, 0, 1, 0, 0, 0, 2, 0, 0, 0, 3 })); // one CSRC
    }

    [Fact]
    public void PacketizerIncrementsSequenceByOneAndTimestampBy960()
    {
        var packetizer = new RtpPacketizer(ssrc: 0xCAFEF00D, initialSequence: 100, initialTimestamp: 5000);

        byte[] first = packetizer.NextPacket(new byte[] { 0x10 });
        byte[] second = packetizer.NextPacket(new byte[] { 0x20, 0x30 });
        byte[] third = packetizer.NextPacket(Array.Empty<byte>());

        ushort Seq(byte[] p) => (ushort)((p[2] << 8) | p[3]);
        uint Ts(byte[] p) => ((uint)p[4] << 24) | ((uint)p[5] << 16) | ((uint)p[6] << 8) | p[7];
        uint Ssrc(byte[] p) => ((uint)p[8] << 24) | ((uint)p[9] << 16) | ((uint)p[10] << 8) | p[11];

        Assert.Equal((ushort)100, Seq(first));
        Assert.Equal((ushort)101, Seq(second));
        Assert.Equal((ushort)102, Seq(third));
        Assert.Equal(5000u, Ts(first));
        Assert.Equal(5960u, Ts(second));   // +960 samples = 20 ms at 48 kHz
        Assert.Equal(6920u, Ts(third));
        Assert.All(new[] { first, second, third }, p => Assert.Equal(0xCAFEF00Du, Ssrc(p)));
        Assert.All(new[] { first, second, third }, p => Assert.Equal(0x80, p[0]));
        Assert.All(new[] { first, second, third }, p => Assert.Equal(111, p[1]));
        Assert.Equal(12, third.Length); // header + zero payload
    }

    [Fact]
    public void PacketizerSequenceWrapsAt65535()
    {
        var packetizer = new RtpPacketizer(1, initialSequence: 65535, initialTimestamp: uint.MaxValue - 959);
        byte[] first = packetizer.NextPacket(new byte[] { 1 });
        byte[] second = packetizer.NextPacket(new byte[] { 1 });

        Assert.Equal(0xFFFF, ((first[2] << 8) | first[3]) & 0xFFFF);
        Assert.Equal(0x0000, (second[2] << 8) | second[3]);
        uint secondTs = ((uint)second[4] << 24) | ((uint)second[5] << 16) | ((uint)second[6] << 8) | second[7];
        Assert.Equal(0u, secondTs); // uint.MaxValue - 959 + 960 wraps to 0
    }

    [Fact]
    public void RandomSsrcVariesWithin32Bits()
    {
        var seen = new HashSet<uint>();
        for (int i = 0; i < 64; i++)
        {
            uint ssrc = RtpPacketizer.RandomSsrc();
            seen.Add(ssrc);
        }

        Assert.True(seen.Count > 1, "a random SSRC must not be constant");
    }
}
