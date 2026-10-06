using OpusVoice.Qr.Core;

namespace OpusVoice.Qr.Core.Tests;

/// <summary>
/// The six parity tests from the Android app's <c>QrPayloadTest</c>, mirrored one for one,
/// plus a few extra UDP-router cases that pin down the Kotlin <c>toIntOrNull</c> semantics.
/// </summary>
public class QrPayloadTests
{
    private const string Ticket =
        "pinhole1:AwPvzauJZ0UjAQECBMCoASrIIgEEywBxB8giAgECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5-gIGCg4Q";

    [Fact]
    public void PinholeTicketIsRecognizedAndParsed()
    {
        QrPayload payload = QrPayload.Parse(Ticket);
        var ticket = Assert.IsType<QrPayload.PinholeTicket>(payload);
        Assert.Equal(Ticket, ticket.Ticket);
        Assert.Equal(0x0123456789ABCDEFul, ticket.Parsed.PeerId);
        Assert.Equal(NatHint.Cone, ticket.Parsed.NatHint);
    }

    [Fact]
    public void SurroundingWhitespaceAndPrefixlessBodiesStillDial()
    {
        Assert.IsType<QrPayload.PinholeTicket>(QrPayload.Parse($"  {Ticket}  "));
        string body = Ticket["pinhole1:".Length..];
        Assert.IsType<QrPayload.PinholeTicket>(QrPayload.Parse(body));
    }

    [Fact]
    public void CorruptedTicketIsNotAPinholePayload()
    {
        Assert.IsType<QrPayload.Unknown>(QrPayload.Parse("pinhole1:not-a-real-ticket"));
    }

    [Fact]
    public void UdpEndpointsParse()
    {
        Assert.Equal(
            new QrPayload.UdpEndpoint("192.168.1.42", 5004),
            QrPayload.Parse("udp://192.168.1.42:5004"));
        Assert.Equal(
            new QrPayload.UdpEndpoint("fe80::1", 5004),
            QrPayload.Parse("udp://[fe80::1]:5004"));
        Assert.Equal(
            new QrPayload.UdpEndpoint("stream.lan", 65535),
            QrPayload.Parse("UDP://stream.lan:65535"));
    }

    [Fact]
    public void MalformedUdpUrisFallThroughToUnknown()
    {
        Assert.IsType<QrPayload.Unknown>(QrPayload.Parse("udp://host:notaport"));
        Assert.IsType<QrPayload.Unknown>(QrPayload.Parse("udp://:5004"));
        Assert.IsType<QrPayload.Unknown>(QrPayload.Parse("udp://host:0"));
        Assert.IsType<QrPayload.Unknown>(QrPayload.Parse("udp://host:70000"));
    }

    [Fact]
    public void ArbitraryTextStaysUnknown()
    {
        Assert.IsType<QrPayload.Unknown>(QrPayload.Parse("https://example.com/call"));
        QrPayload payload = QrPayload.Parse("hello world");
        var unknown = Assert.IsType<QrPayload.Unknown>(payload);
        Assert.Equal("hello world", unknown.Text);
    }

    [Fact]
    public void UdpWithoutAPortStaysUnknown()
    {
        Assert.IsType<QrPayload.Unknown>(QrPayload.Parse("udp://host"));
        Assert.IsType<QrPayload.Unknown>(QrPayload.Parse("udp://host:"));
        Assert.IsType<QrPayload.Unknown>(QrPayload.Parse("udp://"));
    }

    [Fact]
    public void UdpWithUnbracketedMultiColonHostStaysUnknown()
    {
        // the port is "9" (after the last colon) but the host "host:5004" is an unbracketed IPv6: refused
        Assert.IsType<QrPayload.Unknown>(QrPayload.Parse("udp://host:5004:9"));
        Assert.IsType<QrPayload.Unknown>(QrPayload.Parse("udp://fe80::1:5004"));
    }

    [Fact]
    public void UdpPortParsesLikeKotlinToIntOrNull()
    {
        // Kotlin's toIntOrNull accepts a leading sign; so does the mirror.
        Assert.Equal(new QrPayload.UdpEndpoint("host", 5004), QrPayload.Parse("udp://host:+5004"));
        // "-1" parses as an integer but fails the 1..65535 range check.
        Assert.IsType<QrPayload.Unknown>(QrPayload.Parse("udp://host:-1"));
        // whitespace-padded numbers do not parse, and surrounding text is trimmed first.
        Assert.Equal(new QrPayload.UdpEndpoint("host", 5004), QrPayload.Parse("  udp://host:5004  "));
        Assert.IsType<QrPayload.Unknown>(QrPayload.Parse("udp://host: 5004"));
    }

    [Fact]
    public void UdpWithBracketedIpv6UnwrapsTheHost()
    {
        Assert.Equal(new QrPayload.UdpEndpoint("::1", 5004), QrPayload.Parse("udp://[::1]:5004"));
        Assert.Equal(new QrPayload.UdpEndpoint("user@stream.lan", 5004), QrPayload.Parse("udp://user@stream.lan:5004"));
    }
}
