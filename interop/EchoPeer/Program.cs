// Local C# reference peer for Kotlin discovery/direct/relay interoperability.
using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Diagnostics;
using System.Buffers.Binary;
using System.Reflection;
using Pinhole;

string? Value(string name) => args.SkipWhile(a => a != name).Skip(1).FirstOrDefault();
bool relayOnly = args.Contains("--relay-only");
bool native = args.Contains("--iroh");
bool dropHandshake = args.Contains("--drop-handshake");
bool ipv6 = args.Contains("--ipv6");
using var mappingGateway = args.Contains("--fake-pcp") ? new MappingGateway() : null;
using var referenceRelay = Value("--relay-bin") is { } binary ? await ReferenceRelay.StartAsync(binary) : null;
Uri? relayUrl = referenceRelay?.Url ?? (Value("--relay-url") is { } relay ? new Uri(relay) : null);
if (relayOnly && relayUrl is null) throw new ArgumentException("--relay-only requires --relay-url");
using var directory = native ? new PkarrDirectory(args.Contains("--tamper")) : null;
await using var node = await PinholeNode.BindAsync(new PinholeOptions
{
    Bind = new IPEndPoint(ipv6 ? IPAddress.IPv6Loopback : IPAddress.Loopback, 0), StunServers = [],
    IrohRelayUrls = relayUrl is null ? [] : [relayUrl],
    EnableLanDiscovery = false, // test peers stay on the local fixture network
    EnableNetworkWatch = false, EnablePortMapping = false, EnablePmtud = false,
    ReceiveBufferCapacity = 256, PublishIrohAddress = native,
    PublishDirectIrohAddresses = !relayOnly,
    IrohDiscoveryUrl = directory?.Url ?? new Uri("https://dns.iroh.link/pkarr"),
});
using var proxy = dropHandshake ? new HandshakeLossProxy(new IPEndPoint(IPAddress.Loopback, node.LocalPort)) : null;
var code = ConnectionString.Parse(node.ConnectionString);
if (ipv6) code = new ConnectionString(code.PeerId,
    [new PinholeCandidate(CandidateKind.Direct, new IPEndPoint(IPAddress.IPv6Loopback, node.LocalPort))],
    code.NatHint, code.StaticKey, code.EndpointKey);
if (relayOnly) code = new ConnectionString(code.PeerId,
    code.Candidates.Where(c => c.Kind == CandidateKind.IrohRelay).ToArray(), code.NatHint, code.StaticKey, code.EndpointKey);
if (proxy is not null) code = new ConnectionString(code.PeerId,
    [new PinholeCandidate(CandidateKind.Direct, proxy.Address)], code.NatHint, code.StaticKey, code.EndpointKey);
string ticket = code.ToString();
if (native)
{
    var address = node.IrohAddress;
    if (relayOnly) address = new IrohAddress(address.EndpointId, relayUrls: address.RelayUrls);
    ticket = args.Contains("--id") ? address.EndpointId : address.ToString();
    Console.WriteLine("DISCOVERY_URL=" + directory!.Url);
}
if (mappingGateway is not null) Console.WriteLine("MAPPING_GATEWAY=" + mappingGateway.Address);
Console.WriteLine("TICKET=" + ticket);
Console.WriteLine("EXPECTED_PATH=" + (relayOnly ? "relay" : "direct"));
var connections = new List<PinholeConnection>();
while (true)
{
    var conn = await node.AcceptAsync();
    connections.Add(conn);
    Console.WriteLine("CONNECTED path=" + conn.Path.Kind + " encrypted=" + conn.IsEncrypted);
    _ = EchoAsync(conn, node, mappingGateway);
}
static async Task EchoAsync(PinholeConnection conn, PinholeNode node, MappingGateway? mapping)
{
    try
    {
        while (await conn.ReceiveAsync() is { } payload)
        {
            if (mapping is not null && payload.Span.SequenceEqual("mapping-status"u8))
            {
                // Observe the core's real authenticated ANNOUNCE handler through its
                // existing diagnostic accessor; reflection is confined to this test peer.
                object engine = typeof(PinholeNode).GetProperty("Engine", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(node)!;
                var candidates = (PinholeCandidate[])engine.GetType().GetMethod("PeerCandidatesSnapshot", BindingFlags.Instance | BindingFlags.NonPublic)!
                    .Invoke(engine, [conn.PeerId])!;
                bool observed = mapping.InternalPort == conn.Path.Remote?.Port &&
                    candidates.Any(c => c.Kind == CandidateKind.Reflexive && c.Address.Equals(mapping.External));
                conn.Send(observed ? "mapping-confirmed"u8 : "mapping-pending"u8);
                if (observed) Console.WriteLine("MAPPING_VALIDATED audioPort=" + mapping.InternalPort);
            }
            else conn.Send(payload.Span);
        }
    }
    catch (Exception ex) { Console.Error.WriteLine("echo ended: " + ex.Message); }
}

// An independent RFC 6887 gateway fixture; it never changes the host router or firewall.
sealed class MappingGateway : IDisposable
{
    private readonly UdpClient _control = new(new IPEndPoint(IPAddress.Loopback, 0));
    private readonly UdpClient _mapped = new(new IPEndPoint(IPAddress.Loopback, 0));
    private readonly CancellationTokenSource _stop = new();
    public IPEndPoint Address => (IPEndPoint)_control.Client.LocalEndPoint!;
    public IPEndPoint External => (IPEndPoint)_mapped.Client.LocalEndPoint!;
    public int InternalPort;

    public MappingGateway() => _ = RunAsync();
    private async Task RunAsync()
    {
        try
        {
            while (!_stop.IsCancellationRequested)
            {
                UdpReceiveResult packet = await _control.ReceiveAsync(_stop.Token);
                byte[] request = packet.Buffer;
                if (request.Length != 60 || request[0] != 2 || request[1] != 1 || request[36] != 17
                    || !request.AsSpan(8, 16).SequenceEqual(packet.RemoteEndPoint.Address.MapToIPv6().GetAddressBytes())) continue;
                Volatile.Write(ref InternalPort, BinaryPrimitives.ReadUInt16BigEndian(request.AsSpan(40)));
                byte[] reply = new byte[60];
                reply[0] = 2; reply[1] = 129;
                BinaryPrimitives.WriteUInt32BigEndian(reply.AsSpan(4), BinaryPrimitives.ReadUInt32BigEndian(request.AsSpan(4)) == 0 ? 0u : 2u);
                request.AsSpan(24, 12).CopyTo(reply.AsSpan(24)); reply[36] = 17;
                request.AsSpan(40, 2).CopyTo(reply.AsSpan(40));
                BinaryPrimitives.WriteUInt16BigEndian(reply.AsSpan(42), (ushort)External.Port);
                IPAddress.Loopback.MapToIPv6().GetAddressBytes().CopyTo(reply, 44);
                await _control.SendAsync(reply, packet.RemoteEndPoint, _stop.Token);
            }
        }
        catch (Exception ex) when (ex is OperationCanceledException or SocketException or ObjectDisposedException) { }
    }
    public void Dispose() { _stop.Cancel(); _control.Dispose(); _mapped.Dispose(); }
}

// Deliberately untrusted, ephemeral local discovery service. Both endpoints
// verify its returned signatures; --tamper exercises malicious directory data.
sealed class PkarrDirectory : IDisposable
{
    private readonly HttpListener _listener = new();
    private readonly ConcurrentDictionary<string, byte[]> _records = new();
    public Uri Url { get; }
    public PkarrDirectory(bool tamper)
    {
        using var reservation = new TcpListener(IPAddress.Loopback, 0);
        reservation.Start();
        int port = ((IPEndPoint)reservation.LocalEndpoint).Port;
        reservation.Stop();
        Url = new Uri("http://127.0.0.1:" + port + "/pkarr");
        _listener.Prefixes.Add("http://127.0.0.1:" + port + "/");
        _listener.Start();
        _ = RunAsync(tamper);
    }
    private async Task RunAsync(bool tamper)
    {
        try
        {
            while (_listener.IsListening)
            {
                var context = await _listener.GetContextAsync();
                string key = context.Request.Url!.AbsolutePath;
                if (context.Request.HttpMethod == "PUT")
                {
                    byte[] bytes = new byte[1073];
                    int used = 0;
                    while (used < bytes.Length)
                    {
                        int read = await context.Request.InputStream.ReadAsync(bytes.AsMemory(used));
                        if (read == 0) break;
                        used += read;
                    }
                    if (used is < 84 or > 1072) context.Response.StatusCode = 400;
                    else _records[key] = bytes[..used];
                }
                else if (context.Request.HttpMethod == "GET" && _records.TryGetValue(key, out byte[]? value))
                {
                    byte[] bytes = value.ToArray();
                    if (tamper) bytes[^1] ^= 1;
                    await context.Response.OutputStream.WriteAsync(bytes);
                }
                else context.Response.StatusCode = 404;
                context.Response.Close();
            }
        }
        catch (Exception ex) when (ex is HttpListenerException or ObjectDisposedException) { }
    }
    public void Dispose() => _listener.Close();
}

// One UDP proxy drops the first PACK and HSCK, proving recovery of either lost
// handshake flight without disabling the Pinhole key pin or replay protection.
sealed class HandshakeLossProxy : IDisposable
{
    private readonly UdpClient _udp = new(new IPEndPoint(IPAddress.Loopback, 0));
    private readonly CancellationTokenSource _stop = new();
    public IPEndPoint Address => (IPEndPoint)_udp.Client.LocalEndPoint!;
    public HandshakeLossProxy(IPEndPoint server) => _ = RunAsync(server);
    private async Task RunAsync(IPEndPoint server)
    {
        IPEndPoint? client = null;
        bool droppedPack = false, droppedHsck = false;
        try
        {
            while (!_stop.IsCancellationRequested)
            {
                var packet = await _udp.ReceiveAsync(_stop.Token);
                bool response = packet.RemoteEndPoint.Equals(server);
                if (packet.Buffer.Length > 0 && packet.Buffer[0] == 0x51 && !droppedPack)
                { droppedPack = true; Console.WriteLine("DROPPED=PACK"); continue; }
                if (packet.Buffer.Length > 0 && packet.Buffer[0] == 0x57 && !droppedHsck)
                { droppedHsck = true; Console.WriteLine("DROPPED=HSCK"); continue; }
                if (!response) client = packet.RemoteEndPoint;
                var target = response ? client : server;
                if (target is not null) await _udp.SendAsync(packet.Buffer, target, _stop.Token);
            }
        }
        catch (Exception ex) when (ex is OperationCanceledException or ObjectDisposedException) { }
    }
    public void Dispose() { _stop.Cancel(); _udp.Dispose(); }
}

sealed class ReferenceRelay : IDisposable
{
    private readonly Process _process;
    private readonly string _directory;
    public Uri Url { get; }
    private ReferenceRelay(Process process, string directory, Uri url)
    { _process = process; _directory = directory; Url = url; }
    public static async Task<ReferenceRelay> StartAsync(string binary)
    {
        using var reservation = new TcpListener(IPAddress.Loopback, 0);
        reservation.Start();
        int port = ((IPEndPoint)reservation.LocalEndpoint).Port;
        reservation.Stop();
        string directory = Path.Combine(Path.GetTempPath(), "opusvoice-relay-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        string config = Path.Combine(directory, "relay.toml");
        await File.WriteAllTextAsync(config, "http_bind_addr = \"127.0.0.1:" + port + "\"\nenable_stun = false\nenable_quic = false\nenable_metrics = false\n");
        var start = new ProcessStartInfo(binary) { RedirectStandardOutput = true, RedirectStandardError = true };
        start.ArgumentList.Add("--dev");
        start.ArgumentList.Add("--config-path");
        start.ArgumentList.Add(config);
        Process process = Process.Start(start) ?? throw new IOException("could not start iroh-relay");
        Task<string> output = process.StandardOutput.ReadToEndAsync();
        Task<string> error = process.StandardError.ReadToEndAsync();
        var handle = new ReferenceRelay(process, directory, new Uri("http://127.0.0.1:" + port + "/"));
        try
        {
            using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(20));
            while (!deadline.IsCancellationRequested)
            {
                if (process.HasExited) throw new IOException("reference iroh relay exited before startup: " + await output + await error);
                try
                {
                    using var client = new TcpClient();
                    await client.ConnectAsync(IPAddress.Loopback, port, deadline.Token);
                    return handle;
                }
                catch (SocketException) { await Task.Delay(50, deadline.Token); }
            }
            throw new TimeoutException("reference relay startup timed out");
        }
        catch { handle.Dispose(); throw; }
    }
    public void Dispose()
    {
        if (!_process.HasExited) _process.Kill(entireProcessTree: true);
        _process.Dispose();
        try { Directory.Delete(_directory, recursive: true); } catch (IOException) { }
    }
}
