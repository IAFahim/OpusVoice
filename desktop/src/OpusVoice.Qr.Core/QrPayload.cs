namespace OpusVoice.Qr.Core;

/// <summary>
/// Turns raw QR text into the first matching typed payload. Every QR-enabled feature grows a
/// branch here — the scanner UI stays format-agnostic and hands over whatever the code contained
/// (a line-by-line port of the Android app's <c>com.example.qr.QrPayload</c> router).
///
/// Recognized today:
///  - "pinhole1:…" (or the bare base64 body) — a Pinhole connection string to dial
///  - "udp://host:port" — a plain RTP target for the app's UDP mode
/// </summary>
public abstract record QrPayload
{
    private QrPayload()
    {
    }

    /// <summary>A Pinhole connection string vouching for a peer; <see cref="PinholeTicket.Parsed"/> proves it is well-formed.</summary>
    public sealed record PinholeTicket(string Ticket, ConnectionString Parsed) : QrPayload;

    /// <summary>A plain UDP destination: fill the host and port fields.</summary>
    public sealed record UdpEndpoint(string Host, int Port) : QrPayload;

    /// <summary>Nothing recognized; the scanner shows the raw text so nothing is silently lost.</summary>
    public sealed record Unknown(string Text) : QrPayload;

    public static QrPayload Parse(string raw)
    {
        string text = raw.Trim();
        ConnectionString? ticket = ConnectionString.TryParse(text);
        if (ticket is not null) return new PinholeTicket(text, ticket);
        UdpEndpoint? udp = ParseUdpEndpoint(text);
        if (udp is not null) return udp;
        return new Unknown(text);
    }

    private static UdpEndpoint? ParseUdpEndpoint(string text)
    {
        if (!text.StartsWith("udp://", StringComparison.OrdinalIgnoreCase)) return null;
        string body = text["udp://".Length..];
        int lastColon = body.LastIndexOf(':');
        if (lastColon < 0) return null;
        string portPart = body[(lastColon + 1)..];
        if (!TryParseIntStrict(portPart, out int port)) return null;
        if (port is < 1 or > 65535) return null;
        string hostPart = body[..lastColon];
        if (hostPart.Length == 0) return null;
        bool bracketed = hostPart.StartsWith('[') && hostPart.EndsWith(']');
        string host = bracketed ? hostPart[1..^1] : hostPart;
        if (host.Length == 0) return null;
        if (!bracketed && host.Contains(':')) return null; // unbracketed IPv6 is ambiguous: refuse
        return new UdpEndpoint(host, port);
    }

    /// <summary>Kotlin's <c>toIntOrNull()</c>: optional sign then ASCII digits only, no whitespace, null on overflow.</summary>
    private static bool TryParseIntStrict(string s, out int value)
    {
        value = 0;
        if (s.Length == 0) return false;
        int i = 0;
        bool negative = false;
        if (s[0] is '+' or '-')
        {
            negative = s[0] == '-';
            i = 1;
        }

        if (i >= s.Length) return false;
        long accumulator = 0;
        for (; i < s.Length; i++)
        {
            if (s[i] is < '0' or > '9') return false;
            accumulator = checked(accumulator * 10) + (s[i] - '0');
            if (accumulator > int.MaxValue + 1L) return false;
        }

        long signed = negative ? -accumulator : accumulator;
        if (signed is < int.MinValue or > int.MaxValue) return false;
        value = (int)signed;
        return true;
    }
}
