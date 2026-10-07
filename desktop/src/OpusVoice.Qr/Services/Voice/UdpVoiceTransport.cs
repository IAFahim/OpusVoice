using System.Net.Sockets;
using OpusVoice.Qr.Core.Voice;

namespace OpusVoice.Qr.Services.Voice;

/// <summary>
/// The plain-UDP <see cref="IVoiceTransport"/>: one connected socket, resolved once at
/// construction, connectionless-fast sends afterwards. UDP has no connection state to
/// report, so <see cref="IVoiceTransport.StatusChanged"/> is never raised.
/// </summary>
internal sealed class UdpVoiceTransport : IVoiceTransport
{
    private readonly UdpClient _udp;

    public UdpVoiceTransport(string host, int port)
    {
        _udp = new UdpClient();
        _udp.Connect(host, port); // resolves once; sends afterwards are connectionless-fast
        Target = $"{host}:{port}";
    }

    public string Target { get; }

    public void Send(ReadOnlySpan<byte> packet) => _udp.Send(packet);

    event Action<string>? IVoiceTransport.StatusChanged
    {
        add { }
        remove { }
    }

    public void Dispose()
    {
        try
        {
            _udp.Dispose();
        }
        catch (Exception)
        {
            // teardown must not throw on capture threads
        }
    }
}
