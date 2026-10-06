namespace OpusVoice.Qr.Core;

/// <summary>Kind of candidate address a connection string can carry (wire values mirror Pinhole.Net).</summary>
public enum CandidateKind
{
    Direct = 1,
    Reflexive = 2,
    Relay = 3,
    IrohRelay = 4,
}

/// <summary>NAT classification hint embedded by the publisher; Symmetric means a direct punch is hopeless.</summary>
public enum NatHint
{
    Unknown = 0,
    Cone = 1,
    Symmetric = 2,
}
