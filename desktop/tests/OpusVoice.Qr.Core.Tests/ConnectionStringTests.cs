using System.Net;
using System.Net.Sockets;

namespace OpusVoice.Qr.Core.Tests;

/// <summary>Deep coverage of the <c>pinhole1:</c> grammar, mirroring pinhole/ConnectionString.kt rule for rule.</summary>
public class ConnectionStringTests
{
    private const string ReferenceTicket =
        "pinhole1:AwPvzauJZ0UjAQECBMCoASrIIgEEywBxB8giAgECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5-gIGCg4Q";

    [Fact]
    public void ReferenceTicketParsesCompletely()
    {
        ConnectionString parsed = ConnectionString.Parse(ReferenceTicket);
        Assert.Equal(3, parsed.Version);
        Assert.Equal(0x0123456789ABCDEFul, parsed.PeerId);
        Assert.Equal(NatHint.Cone, parsed.NatHint);
        Assert.Equal(2, parsed.Candidates.Count);

        Assert.Equal(CandidateKind.Direct, parsed.Candidates[0].Kind);
        Assert.Equal(new IPEndPoint(IPAddress.Parse("192.168.1.42"), 51234), parsed.Candidates[0].Address);
        Assert.Equal(CandidateKind.Reflexive, parsed.Candidates[1].Kind);
        Assert.Equal(new IPEndPoint(IPAddress.Parse("203.0.113.7"), 51234), parsed.Candidates[1].Address);

        Assert.NotNull(parsed.StaticKey);
        Assert.Equal(32, parsed.StaticKey.Length);
        Assert.Equal(0x01, parsed.StaticKey[0]);
        Assert.Equal(0x20, parsed.StaticKey[^1]);
        Assert.NotNull(parsed.EndpointKey);
        Assert.Equal(32, parsed.EndpointKey.Length);
        Assert.Equal(0x65, parsed.EndpointKey[0]);
        Assert.Equal(0x84, parsed.EndpointKey[^1]);
    }

    [Fact]
    public void ReferenceTicketToleratesPaddingWhitespaceAndMissingPrefix()
    {
        ConnectionString expected = ConnectionString.Parse(ReferenceTicket);
        string body = ReferenceTicket["pinhole1:".Length..];

        Assert.Equal(expected, ConnectionString.Parse($"\n  {ReferenceTicket} \t"));
        Assert.Equal(expected, ConnectionString.Parse(body));
        Assert.Equal(expected, ConnectionString.Parse($"pinhole1:{body}=")); // one '=' completes the 123-char body to a quantum
    }

    [Fact]
    public void TryParseReturnsNullInsteadOfThrowing()
    {
        Assert.Null(ConnectionString.TryParse("pinhole1:not-a-real-ticket"));
        Assert.Null(ConnectionString.TryParse(string.Empty));
        Assert.Null(ConnectionString.TryParse("hello world"));
        Assert.NotNull(ConnectionString.TryParse(ReferenceTicket));
    }

    [Theory]
    [InlineData("pinhole2:AAAA", "expected a \"pinhole1:\" connection string")]
    [InlineData("x:y", "expected a \"pinhole1:\" connection string")]
    [InlineData(":AwPv", "expected a \"pinhole1:\" connection string")]
    [InlineData("pinhole1:AAAA", "payload too short")]
    [InlineData("pinhole1:", "payload too short")]
    [InlineData("pinhole1:a-b_c$", "payload is not valid base64url")]
    [InlineData("pinhole1:AAAAAA+E", "payload is not valid base64url")] // '+' is standard base64, not base64url
    public void MalformedStringsAreRejectedWithTheKotlinMessages(string text, string expectedMessage)
    {
        ArgumentException exception = Assert.Throws<ArgumentException>(() => ConnectionString.Parse(text));
        Assert.Equal(expectedMessage, exception.Message);
        Assert.Null(ConnectionString.TryParse(text));
    }

    [Fact]
    public void LengthLimitIsEnforcedBeforeAnythingElse()
    {
        string oversized = "pinhole1:" + new string('A', 8200);
        ArgumentException exception = Assert.Throws<ArgumentException>(() => ConnectionString.Parse(oversized));
        Assert.Contains("exceeds 8192 characters", exception.Message);
    }

    [Fact]
    public void UnsupportedVersionIsRejected()
    {
        string version4 = new Wire().Direct("10.0.0.1", 6000).Ticket(version: 4, flags: 0);
        ArgumentException exception = Assert.Throws<ArgumentException>(() => ConnectionString.Parse(version4));
        Assert.Equal("unsupported connection string version", exception.Message);
    }

    [Fact]
    public void FlagsMustMatchTheVersion()
    {
        Assert.NotNull(ConnectionString.Parse(new Wire().Direct("10.0.0.1", 6000).Ticket(1, 0)));
        AssertThrows("unknown connection string flags", new Wire().Direct("10.0.0.1", 6000).Ticket(1, 1));
        AssertThrows("unknown connection string flags", new Wire().Direct("10.0.0.1", 6000).Ticket(1, 3));
        AssertThrows("unknown connection string flags", new Wire().Direct("10.0.0.1", 6000).Trailing(32, 0x11).Ticket(2, 0));
        Assert.NotNull(ConnectionString.Parse(new Wire().Direct("10.0.0.1", 6000).Trailing(32, 0x11).Ticket(2, 1)));
        AssertThrows("unknown connection string flags", new Wire().Direct("10.0.0.1", 6000).Trailing(64, 0x11).Ticket(3, 1));
        Assert.Throws<ArgumentException>(() => ConnectionString.Parse(new Wire().Direct("10.0.0.1", 6000).Trailing(64, 0x11).Ticket(3, 0)));
        Assert.NotNull(ConnectionString.Parse(new Wire().Direct("10.0.0.1", 6000).Trailing(64, 0x11).Ticket(3, 3)));
    }

    [Fact]
    public void UnknownNatHintIsRejected()
    {
        string ticket = new Wire { NatHintWire = 3 }.Direct("10.0.0.1", 6000).Ticket(1, 0);
        ArgumentException exception = Assert.Throws<ArgumentException>(() => ConnectionString.Parse(ticket));
        Assert.Equal("unknown NAT hint", exception.Message);
    }

    [Fact]
    public void UnknownCandidateKindIsRejected()
    {
        string ticket = new Wire().Endpoint("10.0.0.1", 6000).Kind(5).Ticket(1, 0);
        ArgumentException exception = Assert.Throws<ArgumentException>(() => ConnectionString.Parse(ticket));
        Assert.Equal("unknown candidate kind", exception.Message);
    }

    [Fact]
    public void UnknownAddressFamilyIsRejected()
    {
        string ticket = new Wire().RawAfterHint(new byte[] { 1, 6, 10, 0, 0, 1, 0x17, 0x70 }).Ticket(1, 0);
        ArgumentException exception = Assert.Throws<ArgumentException>(() => ConnectionString.Parse(ticket));
        Assert.Equal("unknown address family", exception.Message);
    }

    [Fact]
    public void CandidateCountIsCappedAt32()
    {
        var many = new Wire();
        for (int i = 0; i < 32; i++) many.Direct("10.0.0.1", 6000);
        ConnectionString parsed = ConnectionString.Parse(many.Ticket(1, 0));
        Assert.Equal(32, parsed.Candidates.Count);

        string tooMany = many.Direct("10.0.0.1", 6000).Ticket(1, 0);
        ArgumentException exception = Assert.Throws<ArgumentException>(() => ConnectionString.Parse(tooMany));
        Assert.Contains("more than 32 candidates", exception.Message);
    }

    [Fact]
    public void DeclaredCountAboveActualCandidatesIsTruncated()
    {
        string ticket = new Wire().Direct("10.0.0.1", 6000).DeclaredCount(2).Ticket(1, 0);
        ArgumentException exception = Assert.Throws<ArgumentException>(() => ConnectionString.Parse(ticket));
        Assert.Equal("payload is truncated", exception.Message);
    }

    [Fact]
    public void V1WithNoCandidatesIsTooShort()
    {
        // header is 12 bytes; the grammar demands at least 13, so a v1 string needs at least one candidate
        string headerOnly = new Wire().Ticket(1, 0);
        ArgumentException exception = Assert.Throws<ArgumentException>(() => ConnectionString.Parse(headerOnly));
        Assert.Equal("payload too short", exception.Message);
    }

    [Fact]
    public void V1MustEndExactlyAfterTheLastCandidate()
    {
        ConnectionString parsed = ConnectionString.Parse(new Wire().Direct("10.0.0.1", 6000).Ticket(1, 0));
        Assert.Equal(1, parsed.Version);
        Assert.Null(parsed.StaticKey);
        Assert.Null(parsed.EndpointKey);

        string trailing = new Wire().Direct("10.0.0.1", 6000).Trailing(1, 0xAB).Ticket(1, 0);
        AssertThrows("trailing bytes in connection string", trailing);
    }

    [Fact]
    public void V2MustEndWithExactlyA32ByteStaticKey()
    {
        ConnectionString parsed = ConnectionString.Parse(new Wire().Direct("10.0.0.1", 6000).Trailing(32, 0x42).Ticket(2, 1));
        Assert.Equal(2, parsed.Version);
        Assert.NotNull(parsed.StaticKey);
        Assert.All(parsed.StaticKey, b => Assert.Equal(0x42, b));
        Assert.Null(parsed.EndpointKey);

        AssertThrows("v2 string must end with a 32-byte static key", new Wire().Direct("10.0.0.1", 6000).Trailing(31, 0x42).Ticket(2, 1));
        AssertThrows("v2 string must end with a 32-byte static key", new Wire().Direct("10.0.0.1", 6000).Trailing(33, 0x42).Ticket(2, 1));
    }

    [Fact]
    public void V3MustEndWithExactlyStaticAndEndpointKeys()
    {
        ConnectionString parsed = ConnectionString.Parse(new Wire().Direct("10.0.0.1", 6000).Trailing(64, 0x77).Ticket(3, 3));
        Assert.Equal(3, parsed.Version);
        Assert.NotNull(parsed.StaticKey);
        Assert.NotNull(parsed.EndpointKey);
        Assert.Equal(32, parsed.StaticKey.Length);
        Assert.Equal(32, parsed.EndpointKey.Length);
        Assert.All(parsed.StaticKey, b => Assert.Equal(0x77, b));

        AssertThrows("v3 string must end with static and endpoint keys", new Wire().Direct("10.0.0.1", 6000).Trailing(32, 0x77).Ticket(3, 3));
        AssertThrows("v3 string must end with static and endpoint keys", new Wire().Direct("10.0.0.1", 6000).Trailing(96, 0x77).Ticket(3, 3));
    }

    [Fact]
    public void RelayCandidateCarriesServerUsernameAndCredential()
    {
        string ticket = new Wire()
            .Relay("10.0.0.5", 3478, "10.1.2.3", 3479, "user", "pass")
            .Ticket(1, 0);
        ConnectionString parsed = ConnectionString.Parse(ticket);
        var relay = Assert.Single(parsed.Candidates);
        Assert.Equal(CandidateKind.Relay, relay.Kind);
        Assert.Equal(new IPEndPoint(IPAddress.Parse("10.0.0.5"), 3478), relay.Address);

        string truncated = new Wire()
            .RelayTruncated("10.0.0.5", 3478, "10.1.2.3", 3479, "user")
            .Ticket(1, 0);
        AssertThrows("payload is truncated", truncated);
    }

    [Fact]
    public void IrohRelayCandidateCarriesUrlAndPublicKey()
    {
        ConnectionString parsed = ConnectionString.Parse(
            new Wire().IrohRelay("10.0.0.9", 3340, "https://relay.example.org", 0x5A).Ticket(1, 0));
        var relay = Assert.Single(parsed.Candidates);
        Assert.Equal(CandidateKind.IrohRelay, relay.Kind);
        Assert.Equal(new IPEndPoint(IPAddress.Parse("10.0.0.9"), 3340), relay.Address);

        AssertThrows("payload is truncated", new Wire().IrohRelay("10.0.0.9", 3340, "https://relay.example.org", 0x5A, keyBytes: 31).Ticket(1, 0));
    }

    [Fact]
    public void IPv6CandidatesUseFamily16AndBigEndianPorts()
    {
        string ticket = new Wire().Direct("2001:db8::1", 443).Ticket(1, 0);
        ConnectionString parsed = ConnectionString.Parse(ticket);
        var candidate = Assert.Single(parsed.Candidates);
        Assert.Equal(CandidateKind.Direct, candidate.Kind);
        Assert.Equal(AddressFamily.InterNetworkV6, candidate.Address.AddressFamily);
        Assert.Equal(IPAddress.Parse("2001:db8::1"), candidate.Address.Address);
        Assert.Equal(443, candidate.Address.Port);
    }

    [Fact]
    public void TruncatedEndpointIsRejected()
    {
        // version 1, flags 0, peerId, hint 1, count 1, then family 4 with only three address bytes
        byte[] payload = { 1, 0, 0xEF, 0xCD, 0xAB, 0x89, 0x67, 0x45, 0x23, 0x01, 1, 1, 4, 10, 0, 0 };
        ArgumentException exception = Assert.Throws<ArgumentException>(
            () => ConnectionString.Parse("pinhole1:" + ToBase64Url(payload)));
        Assert.Equal("payload is truncated", exception.Message);
    }

    [Fact]
    public void RelayTextFieldsAcceptUpTo64Bytes()
    {
        Assert.NotNull(ConnectionString.TryParse(
            new Wire().Relay("10.0.0.5", 3478, "10.1.2.3", 3479, new string('u', 64), new string('p', 64)).Ticket(1, 0)));
        Assert.Null(ConnectionString.TryParse(
            new Wire().Relay("10.0.0.5", 3478, "10.1.2.3", 3479, new string('u', 65), "p").Ticket(1, 0)));
    }

    private static void AssertThrows(string expectedMessage, string ticket)
    {
        ArgumentException exception = Assert.Throws<ArgumentException>(() => ConnectionString.Parse(ticket));
        Assert.Equal(expectedMessage, exception.Message);
    }

    private static string ToBase64Url(byte[] payload) =>
        Convert.ToBase64String(payload).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    /// <summary>Builds pinhole payloads byte by byte, mirroring the wire grammar.</summary>
    private sealed class Wire
    {
        private readonly List<byte> _body = new();
        private int _count;
        private int _declaredCount = -1;

        public int NatHintWire { get; init; } = 1;

        /// <summary>Overrides the candidate count byte (e.g. to declare more candidates than encoded).</summary>
        public Wire DeclaredCount(int declaredCount)
        {
            _declaredCount = declaredCount;
            return this;
        }

        public Wire Endpoint(string ip, int port)
        {
            byte[] address = IPAddress.Parse(ip).GetAddressBytes();
            _body.Add((byte)address.Length); // family: 4 for IPv4, 16 for IPv6
            _body.AddRange(address);
            _body.Add((byte)(port >> 8));
            _body.Add((byte)(port & 0xFF));
            return this;
        }

        public Wire Kind(int kind)
        {
            _body.Add((byte)kind);
            _count++; // the kind byte terminates a candidate
            return this;
        }

        public Wire Direct(string ip, int port)
        {
            Endpoint(ip, port);
            Kind(1);
            return this;
        }

        public Wire Relay(string ip, int port, string serverIp, int serverPort, string username, string credential)
        {
            RelayTruncated(ip, port, serverIp, serverPort, username);
            ShortText(credential);
            return this;
        }

        public Wire RelayTruncated(string ip, int port, string serverIp, int serverPort, string username)
        {
            Endpoint(ip, port);
            Kind(3);
            Endpoint(serverIp, serverPort);
            ShortText(username);
            return this;
        }

        public Wire IrohRelay(string ip, int port, string url, int fill, int keyBytes = 32)
        {
            Endpoint(ip, port);
            Kind(4);
            ShortText(url);
            for (int i = 0; i < keyBytes; i++) _body.Add((byte)fill);
            return this;
        }

        public Wire Trailing(int byteCount, int fill)
        {
            for (int i = 0; i < byteCount; i++) _body.Add((byte)fill);
            return this;
        }

        /// <summary>Appends raw bytes verbatim after the NAT-hint byte; the first byte plays the count.</summary>
        public Wire RawAfterHint(byte[] bytes)
        {
            _body.AddRange(bytes);
            _declaredCount = -2; // the count byte lives in the raw body
            return this;
        }

        private void ShortText(string text)
        {
            _body.Add((byte)text.Length);
            foreach (char c in text) _body.Add((byte)c);
        }

        public string Ticket(int version, int flags) => "pinhole1:" + ToBase64Url(Build(version, flags));

        private byte[] Build(int version, int flags)
        {
            var payload = new List<byte>
            {
                (byte)version,
                (byte)flags,
                0xEF, 0xCD, 0xAB, 0x89, 0x67, 0x45, 0x23, 0x01, // peerId 0x0123456789ABCDEF little-endian
                (byte)NatHintWire,
            };
            if (_declaredCount == -2)
            {
                // count already inside the raw body
            }
            else if (_declaredCount >= 0)
            {
                payload.Add((byte)_declaredCount);
            }
            else
            {
                payload.Add((byte)_count);
            }

            payload.AddRange(_body);
            return payload.ToArray();
        }
    }
}
