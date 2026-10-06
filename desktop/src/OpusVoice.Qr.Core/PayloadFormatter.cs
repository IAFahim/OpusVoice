namespace OpusVoice.Qr.Core;

/// <summary>Renders a routed <see cref="QrPayload"/> as the structured detail lines shown next to QR codes.</summary>
public static class PayloadFormatter
{
    public static IReadOnlyList<string> Describe(QrPayload payload) => payload switch
    {
        QrPayload.PinholeTicket ticket => DescribeTicket(ticket),
        QrPayload.UdpEndpoint udp => DescribeUdp(udp),
        QrPayload.Unknown unknown => DescribeUnknown(unknown),
        _ => Array.Empty<string>(),
    };

    private static List<string> DescribeTicket(QrPayload.PinholeTicket ticket)
    {
        ConnectionString parsed = ticket.Parsed;
        var lines = new List<string>
        {
            $"Pinhole connection string v{parsed.Version}",
            $"Peer ID    0x{parsed.PeerId:X16}",
            $"NAT hint   {DescribeNatHint(parsed.NatHint)}",
            $"Candidates ({parsed.Candidates.Count})",
        };
        foreach (PinholeCandidate candidate in parsed.Candidates)
        {
            lines.Add($"  {candidate.Address}  ·  {DescribeKind(candidate.Kind)}");
        }

        lines.Add(parsed.StaticKey is null
            ? "Static key     none"
            : $"Static key     present ({parsed.StaticKey.Length} bytes)");
        lines.Add(parsed.EndpointKey is null
            ? "Endpoint key   none"
            : $"Endpoint key   present ({parsed.EndpointKey.Length} bytes)");
        lines.Add($"Ticket         {ticket.Ticket.Length} characters");
        return lines;
    }

    private static List<string> DescribeUdp(QrPayload.UdpEndpoint udp) => new()
    {
        "UDP endpoint",
        $"Host   {udp.Host}",
        $"Port   {udp.Port}",
        $"Target udp://{udp.Host}:{udp.Port}",
        "Ready to use as a voice target.",
    };

    private static List<string> DescribeUnknown(QrPayload.Unknown unknown) => new()
    {
        $"Unrecognized text ({unknown.Text.Length} characters)",
        unknown.Text,
    };

    public static string DescribeKind(CandidateKind kind) => kind switch
    {
        CandidateKind.Direct => "direct",
        CandidateKind.Reflexive => "reflexive",
        CandidateKind.Relay => "relay",
        CandidateKind.IrohRelay => "iroh-relay",
        _ => kind.ToString(),
    };

    public static string DescribeNatHint(NatHint hint) => hint switch
    {
        NatHint.Unknown => "unknown",
        NatHint.Cone => "cone",
        NatHint.Symmetric => "symmetric",
        _ => hint.ToString(),
    };
}
