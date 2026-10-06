// Echo peer for the Kotlin dialer's interop test: binds a Pinhole node, prints its
// ticket, accepts connections in a loop, and echoes every datagram back.
//   dotnet run --project interop/EchoPeer [-p:PinholeRoot=/path/to/Pinhole.Net]
using Pinhole;

await using var node = await PinholeNode.BindAsync(new PinholeOptions { ReceiveBufferCapacity = 64 * 1024 });
Console.WriteLine("TICKET=" + node.ConnectionString);
Console.WriteLine("waiting for the Kotlin dialer…");

while (true)
{
    await using var conn = await node.AcceptAsync();
    Console.WriteLine($"connected ({conn.Path.Kind} path)");
    conn.Received += d => conn.Send(d);
    conn.StateChanged += s => { if (s == PinholeConnectionState.Closed) Console.WriteLine("disconnected"); };
}
