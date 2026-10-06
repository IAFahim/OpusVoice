using System.Collections.Concurrent;
using System.Diagnostics;
using System.Threading.Channels;

string root = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../../../../.."));
string configuration = new DirectoryInfo(AppContext.BaseDirectory).Parent!.Name;
string echoDll = Path.Combine(root, "interop", "EchoPeer", "bin", configuration, "net10.0", "EchoPeer.dll");
string? relayBin = Environment.GetEnvironmentVariable("IROH_RELAY_BIN")
    ?? (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator)
        .Select(dir => Path.Combine(dir, "iroh-relay")).FirstOrDefault(File.Exists);
if (relayBin is null) throw new IOException("Install the reference iroh-relay server or set IROH_RELAY_BIN.");
string temporary = Path.Combine(Path.GetTempPath(), "opusvoice-interop-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(temporary);
var peers = new List<(Process Process, string Name, ConcurrentQueue<string> Log)>();
var cases = new List<string>();
var configurations = new (string Name, string[] Args, string Path, string Result)[]
{
    ("pinhole-direct", [], "direct", "accept"),
    ("pinhole-lost-PACK-and-HSCK", ["--drop-handshake"], "direct", "accept"),
    ("iroh-ticket-direct", ["--iroh"], "direct", "accept"),
    ("iroh-id-direct", ["--iroh", "--id"], "direct", "accept"),
    ("pinhole-relay-only", ["--relay-only", "--relay-bin", relayBin], "relay", "accept"),
    ("iroh-id-relay-only", ["--iroh", "--id", "--relay-only", "--relay-bin", relayBin], "relay", "accept"),
    ("iroh-ticket-relay-only", ["--iroh", "--relay-only", "--relay-bin", relayBin], "relay", "accept"),
    ("iroh-relay-to-direct-upgrade", ["--iroh", "--id", "--relay-only", "--relay-bin", relayBin], "upgrade", "accept"),
    ("tampered-iroh-discovery", ["--iroh", "--id", "--tamper"], "direct", "reject"),
};
try
{
    foreach (var config in configurations)
    {
        var start = new ProcessStartInfo("dotnet") { RedirectStandardOutput = true, RedirectStandardError = true };
        start.ArgumentList.Add(echoDll);
        foreach (string argument in config.Args) start.ArgumentList.Add(argument);
        var process = Process.Start(start) ?? throw new IOException("failed to start echo peer");
        var log = new ConcurrentQueue<string>();
        var lines = Channel.CreateUnbounded<string>();
        process.OutputDataReceived += (_, e) => { if (e.Data is { } line) { log.Enqueue(line); lines.Writer.TryWrite(line); } };
        process.ErrorDataReceived += (_, e) => { if (e.Data is { } line) log.Enqueue(line); };
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        process.EnableRaisingEvents = true;
        process.Exited += (_, _) => lines.Writer.TryComplete(new IOException("echo peer exited before readiness"));
        peers.Add((process, config.Name, log));
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        string discovery = "https://dns.iroh.link/pkarr";
        while (true)
        {
            string line = await lines.Reader.ReadAsync(deadline.Token);
            if (line.StartsWith("DISCOVERY_URL=")) discovery = line["DISCOVERY_URL=".Length..];
            if (!line.StartsWith("TICKET=")) continue;
            cases.Add(string.Join('|', config.Name, line["TICKET=".Length..], discovery, config.Path, config.Result));
            Console.WriteLine("ready: " + config.Name);
            break;
        }
    }
    string file = Path.Combine(temporary, "cases.txt");
    await File.WriteAllLinesAsync(file, cases);
    var gradleStart = new ProcessStartInfo(Path.Combine(root, "gradlew")) { WorkingDirectory = root };
    foreach (string arg in new[] { ":pinhole:test", "--tests", "*Interop*", "--rerun", "--console=plain" })
        gradleStart.ArgumentList.Add(arg);
    gradleStart.Environment["PINHOLE_INTEROP_CASES"] = file;
    gradleStart.Environment["PINHOLE_TICKET"] = "";
    using var gradle = Process.Start(gradleStart) ?? throw new IOException("failed to start Gradle");
    await gradle.WaitForExitAsync();
    if (gradle.ExitCode != 0) return gradle.ExitCode;
    foreach (var peer in peers.Where(p => p.Name != "tampered-iroh-discovery"))
    {
        if (!peer.Log.Any(line => line.StartsWith("CONNECTED ") && line.EndsWith("encrypted=True")))
            throw new IOException(peer.Name + " did not establish an encrypted C# session");
        if (peer.Name.Contains("lost-") && (!peer.Log.Contains("DROPPED=PACK") || !peer.Log.Contains("DROPPED=HSCK")))
            throw new IOException("handshake loss fixture did not drop both flights");
    }
    Console.WriteLine("PASS: all nine C# ↔ Kotlin connection cases.");
    return 0;
}
finally
{
    foreach (var peer in peers)
    {
        Console.WriteLine(peer.Name + ": " + string.Join("; ", peer.Log));
        if (!peer.Process.HasExited) peer.Process.Kill(entireProcessTree: true);
        peer.Process.Dispose();
    }
    Directory.Delete(temporary, recursive: true);
}
