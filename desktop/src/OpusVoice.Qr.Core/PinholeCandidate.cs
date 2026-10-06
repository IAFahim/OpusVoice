using System.Net;

namespace OpusVoice.Qr.Core;

/// <summary>One dialable address. The minimal dialer punches Direct and Reflexive candidates.</summary>
public sealed record PinholeCandidate(CandidateKind Kind, IPEndPoint Address);
