using System.Net;
using System.Text;

namespace OpusVoice.Qr.Core;

/// <summary>
/// "pinhole1:&lt;base64url&gt;" — a peer's stable id, NAT hint, candidate addresses, and (v2/v3)
/// its pinned X25519 static key. Wire-compatible with Pinhole.Net's ConnectionString; this is a
/// line-by-line port of the Android app's <c>pinhole.ConnectionString</c> parser.
/// </summary>
public sealed class ConnectionString : IEquatable<ConnectionString>
{
    public const string Scheme = "pinhole1";
    public const int MaxCandidates = 32;
    public const int MaxLength = 8192;
    public const int KeyLength = 32;

    public ConnectionString(int version, ulong peerId, NatHint natHint, IReadOnlyList<PinholeCandidate> candidates, byte[]? staticKey, byte[]? endpointKey)
    {
        Version = version;
        PeerId = peerId;
        NatHint = natHint;
        Candidates = candidates;
        StaticKey = staticKey;
        EndpointKey = endpointKey;
    }

    /// <summary>Payload version the string was encoded with: 1, 2 or 3.</summary>
    public int Version { get; }

    public ulong PeerId { get; }

    public NatHint NatHint { get; }

    public IReadOnlyList<PinholeCandidate> Candidates { get; }

    /// <summary>32-byte pinned static key; present in v2 and v3 strings, null in v1.</summary>
    public byte[]? StaticKey { get; }

    /// <summary>32-byte endpoint key; present only in v3 strings.</summary>
    public byte[]? EndpointKey { get; }

    /// <summary>
    /// Lenient like Pinhole.Net's TryParseCode: surrounding whitespace and an omitted
    /// "pinhole1:" prefix are tolerated; anything malformed throws <see cref="ArgumentException"/>.
    /// </summary>
    public static ConnectionString Parse(string text)
    {
        if (text.Length > MaxLength) throw new ArgumentException($"connection string exceeds {MaxLength} characters");
        var t = TrimKotlin(text); // Kotlin's String.trim() — not .NET's Unicode-wider Trim()
        if (!t.Contains(':')) t = $"{Scheme}:{t}";
        int colon = t.IndexOf(':');
        if (colon <= 0 || t[..colon] != Scheme) throw new ArgumentException($"expected a \"{Scheme}:\" connection string");

        byte[] payload = DecodeBase64Url(t[(colon + 1)..]);
        if (payload.Length < 13) throw new ArgumentException("payload too short");
        int version = payload[0] & 0xFF;
        if (version is not (1 or 2 or 3)) throw new ArgumentException("unsupported connection string version");
        int flags = payload[1] & 0xFF;
        int expectedFlags = version switch
        {
            2 => 1,
            3 => 3,
            _ => 0,
        };
        if (flags != expectedFlags) throw new ArgumentException("unknown connection string flags");

        var reader = new Reader(payload, 2);
        ulong peerId = reader.U64Le();
        NatHint natHint = reader.Byte() switch
        {
            0 => NatHint.Unknown,
            1 => NatHint.Cone,
            2 => NatHint.Symmetric,
            _ => throw new ArgumentException("unknown NAT hint"),
        };
        int count = reader.Byte();
        if (count > MaxCandidates) throw new ArgumentException($"connection string carries more than {MaxCandidates} candidates");

        var candidates = new List<PinholeCandidate>(count);
        for (int i = 0; i < count; i++)
        {
            IPEndPoint address = reader.Endpoint();
            CandidateKind kind = reader.Byte() switch
            {
                1 => CandidateKind.Direct,
                2 => CandidateKind.Reflexive,
                3 => CandidateKind.Relay,
                4 => CandidateKind.IrohRelay,
                _ => throw new ArgumentException("unknown candidate kind"),
            };
            switch (kind)
            {
                case CandidateKind.Relay:
                    reader.Endpoint();  // relay server
                    reader.ShortText(); // username
                    reader.ShortText(); // credential
                    break;
                case CandidateKind.IrohRelay:
                {
                    string relayUrl = reader.ShortText();      // relay URL
                    reader.Bytes(KeyLength);                   // relay public key
                    ValidateIrohRelayUrl(relayUrl);
                    break;
                }
            }

            candidates.Add(new PinholeCandidate(kind, address));
        }

        byte[]? staticKey;
        byte[]? endpointKey;
        switch (version)
        {
            case 2:
                if (reader.Remaining != KeyLength) throw new ArgumentException("v2 string must end with a 32-byte static key");
                staticKey = reader.Bytes(KeyLength);
                endpointKey = null;
                break;
            case 3:
                if (reader.Remaining != 2 * KeyLength) throw new ArgumentException("v3 string must end with static and endpoint keys");
                staticKey = reader.Bytes(KeyLength);
                endpointKey = reader.Bytes(KeyLength);
                break;
            default:
                if (!reader.AtEnd) throw new ArgumentException("trailing bytes in connection string");
                staticKey = null;
                endpointKey = null;
                break;
        }

        return new ConnectionString(version, peerId, natHint, candidates, staticKey, endpointKey);
    }

    /// <summary><see cref="Parse"/> for untrusted input (QR codes, share intents): null instead of an exception.</summary>
    public static ConnectionString? TryParse(string text)
    {
        try
        {
            return Parse(text);
        }
        catch (ArgumentException)
        {
            return null;
        }
    }

    /// <summary>
    /// Mirrors the app's validateIrohUrl: an absolute https relay URL (http only on loopback),
    /// with a host, no userinfo/query/fragment, at most 2048 characters. The app additionally
    /// validates the 32-byte key as an Ed25519 point; here the key is length-checked only —
    /// a deliberate, display-only leniency.
    /// </summary>
    private static void ValidateIrohRelayUrl(string text)
    {
        if (text.Length > 2048 || !Uri.TryCreate(text, UriKind.Absolute, out Uri? uri))
            throw new ArgumentException("invalid iroh relay URL");

        bool schemeOk = uri.Scheme == Uri.UriSchemeHttps
            || (uri.Scheme == Uri.UriSchemeHttp && uri.Host is "localhost" or "127.0.0.1" or "::1");
        if (!schemeOk || uri.Host.Length == 0
            || uri.UserInfo.Length > 0 || uri.Query.Length > 0 || uri.Fragment.Length > 0)
        {
            throw new ArgumentException("invalid iroh relay URL");
        }
    }

    /// <summary>Kotlin's Char.isWhitespace() for practical input: ASCII controls and space
    /// (incl. U+001C–U+001F), but not the Unicode non-breaking spaces .NET's Trim() also strips.</summary>
    internal static bool KotlinWhitespace(char c) => c <= ' ';

    /// <summary><see cref="string.Trim()"/> with Kotlin's whitespace semantics (see <see cref="KotlinWhitespace"/>).</summary>
    internal static string TrimKotlin(string text)
    {
        int start = 0, end = text.Length;
        while (start < end && KotlinWhitespace(text[start])) start++;
        while (end > start && KotlinWhitespace(text[end - 1])) end--;
        return text[start..end];
    }

    /// <summary>Strict base64url charset, padding tolerated; anything else is malformed.</summary>
    private static byte[] DecodeBase64Url(string text)
    {
        foreach (char c in text)
        {
            bool ok = c is (>= 'A' and <= 'Z') or (>= 'a' and <= 'z') or (>= '0' and <= '9') or '-' or '_' or '=';
            if (!ok) throw new ArgumentException("payload is not valid base64url");
        }

        int pad = (4 - text.Length % 4) % 4;
        var base64 = new StringBuilder(text.Length + pad).Append(text).Replace('-', '+').Replace('_', '/').Append('=', pad);
        try
        {
            return Convert.FromBase64String(base64.ToString());
        }
        catch (FormatException)
        {
            throw new ArgumentException("payload is not valid base64url");
        }
    }

    public bool Equals(ConnectionString? other) =>
        other is not null
        && Version == other.Version
        && PeerId == other.PeerId
        && NatHint == other.NatHint
        && Candidates.Count == other.Candidates.Count
        && Candidates.Zip(other.Candidates, (a, b) => a == b).All(equal => equal)
        && KeyEquals(StaticKey, other.StaticKey)
        && KeyEquals(EndpointKey, other.EndpointKey);

    public override bool Equals(object? obj) => Equals(obj as ConnectionString);

    public override int GetHashCode() => HashCode.Combine(Version, PeerId, NatHint, Candidates.Count, StaticKey?.Length ?? -1, EndpointKey?.Length ?? -1);

    private static bool KeyEquals(byte[]? left, byte[]? right) =>
        (left is null, right is null) switch
        {
            (true, true) => true,
            (false, false) => left.AsSpan().SequenceEqual(right),
            _ => false,
        };

    private sealed class Reader(byte[] data, int pos)
    {
        private readonly byte[] _data = data;
        private int _pos = pos;

        public int Byte()
        {
            if (_pos >= _data.Length) throw new ArgumentException("payload is truncated");
            return _data[_pos++] & 0xFF;
        }

        public ulong U64Le()
        {
            if (_pos + 8 > _data.Length) throw new ArgumentException("payload is truncated");
            ulong value = 0;
            for (int i = 7; i >= 0; i--)
            {
                value = (value << 8) | _data[_pos + i];
            }

            _pos += 8;
            return value;
        }

        public IPEndPoint Endpoint()
        {
            int family = Byte();
            if (family != 4 && family != 16) throw new ArgumentException("unknown address family");
            if (_pos + family + 2 > _data.Length) throw new ArgumentException("payload is truncated");
            var address = new IPAddress(_data.AsSpan(_pos, family));
            _pos += family;
            int port = (Byte() << 8) | Byte();
            return new IPEndPoint(address, port);
        }

        public string ShortText()
        {
            int length = Byte();
            if (length > 64 || _pos + length > _data.Length) throw new ArgumentException("payload is truncated");
            string text = Encoding.ASCII.GetString(_data, _pos, length);
            _pos += length;
            return text;
        }

        public byte[] Bytes(int count)
        {
            if (_pos + count > _data.Length) throw new ArgumentException("payload is truncated");
            byte[] result = _data[_pos..(_pos + count)];
            _pos += count;
            return result;
        }

        public bool AtEnd => _pos == _data.Length;

        public int Remaining => _data.Length - _pos;
    }
}
