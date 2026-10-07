using System.Diagnostics;
using System.Text;

namespace OpusVoice.Qr.Services.Usb;

/// <summary>One row of <c>adb devices -l</c>: a phone (or emulator) visible over USB.</summary>
public sealed record AdbDevice(string Serial, string State, string Model, string Device, string Product);

/// <summary>
/// Thin wrapper around the Android platform-tools <c>adb</c> binary — the USB control path for
/// a phone plugged into this machine. Locates adb from ANDROID_HOME, the default Linux SDK
/// path, or PATH; every operation degrades to a clear message when adb is absent rather than
/// throwing, mirroring how the camera and audio paths degrade on headless machines.
/// </summary>
public sealed class AdbService : IDisposable
{
    /// <summary>The OpusVoice app's application id and launcher activity (namespace com.example).</summary>
    public const string AppId = "com.aistudio.opusvoice.wrtxqp";
    public const string LaunchActivity = "com.example.MainActivity";

    /// <summary>Logcat tag filter for everything the app and its pinhole module log:
    /// the five app audio/network tags plus System.err, where the Kotlin pinhole module's
    /// debug lines (authenticated paths, rejected hints, probed candidates) land.</summary>
    public const string DefaultLogFilter = "AudioPlayer:* AudioRecorder:* AudioDspManager:* OpusCodec:* UdpTransport:* System.err:* *:S";

    private readonly string? _adb;
    private Process? _logcat;
    private readonly object _logGate = new();

    /// <summary>Raised on a background thread for every received logcat line.</summary>
    public event Action<string>? LogLine;

    /// <summary>Raised on a background thread when the logcat stream terminates.</summary>
    public event Action<string>? LogStopped;

    public AdbService()
    {
        _adb = LocateAdb();
    }

    public bool IsAvailable => _adb is not null;

    /// <summary>Resolves the adb binary: $ANDROID_HOME/platform-tools/adb, the default SDK
    /// locations, then PATH. Null when nothing usable exists.</summary>
    public static string? LocateAdb()
    {
        string? home = Environment.GetEnvironmentVariable("ANDROID_HOME");
        string?[] candidates =
        {
            home is null ? null : Path.Combine(home, "platform-tools", "adb"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Android", "Sdk", "platform-tools", "adb"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Android", "Sdk", "platform-tools", "adb"),
        };
        foreach (string? candidate in candidates)
        {
            if (candidate is null) continue;
            string path = OperatingSystem.IsWindows() && !candidate.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ? candidate + ".exe" : candidate;
            if (File.Exists(path)) return path;
        }
        return ExecutableOnPath("adb");
    }

    private static string? ExecutableOnPath(string name)
    {
        string? pathVariable = Environment.GetEnvironmentVariable("PATH");
        if (string.IsNullOrEmpty(pathVariable)) return null;
        foreach (string dir in pathVariable.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            try
            {
                string candidate = Path.Combine(dir.Trim(), OperatingSystem.IsWindows() ? name + ".exe" : name);
                if (File.Exists(candidate)) return candidate;
            }
            catch (Exception)
            {
                // a malformed PATH entry is not fatal
            }
        }
        return null;
    }

    public IReadOnlyList<AdbDevice> ListDevices()
    {
        if (_adb is null) return Array.Empty<AdbDevice>();
        string output = Run(_adb, "devices -l", TimeSpan.FromSeconds(10));
        var devices = new List<AdbDevice>();
        foreach (string raw in output.Split('\n', StringSplitOptions.RemoveEmptyEntries))
        {
            string line = raw.Trim();
            if (line.Length == 0 || line.StartsWith("List of devices") || line.StartsWith('*')) continue;
            // "<serial> device product:… model:… device:… transport_id:…" — tokens separated by spaces
            string[] parts = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length < 2) continue;
            string serial = parts[0];
            string state = parts[1];
            string model = Token(parts, "model:");
            string device = Token(parts, "device:");
            string product = Token(parts, "product:");
            devices.Add(new AdbDevice(serial, state, model, device, product));
        }
        return devices;
    }

    private static string Token(string[] parts, string prefix)
    {
        foreach (string part in parts)
        {
            if (part.StartsWith(prefix, StringComparison.Ordinal))
            {
                return part[prefix.Length..];
            }
        }
        return string.Empty;
    }

    /// <summary>Installs (or reinstalls) an APK: <c>adb install -r</c>. Returns adb's verdict,
    /// e.g. "Success" or a failure line.</summary>
    public string InstallApk(string serial, string apkPath)
    {
        if (_adb is null) return "adb not found — install Android platform-tools and set ANDROID_HOME.";
        if (!File.Exists(apkPath)) return $"no such file: {apkPath}";
        return Run(_adb, $"-s {serial} install -r \"{apkPath}\"", TimeSpan.FromMinutes(3)).Trim();
    }

    /// <summary>Launches the OpusVoice app on the device.</summary>
    public string LaunchApp(string serial)
    {
        if (_adb is null) return "adb not found";
        return Run(_adb, $"-s {serial} shell am start -n {AppId}/{LaunchActivity}", TimeSpan.FromSeconds(30)).Trim();
    }

    /// <summary>Force-stops the OpusVoice app on the device.</summary>
    public string ForceStopApp(string serial)
    {
        if (_adb is null) return "adb not found";
        Run(_adb, $"-s {serial} shell am force-stop {AppId}", TimeSpan.FromSeconds(30));
        return "stopped";
    }

    /// <summary>Clears the device log buffer so a fresh capture starts clean.</summary>
    public void ClearLogcat(string serial)
    {
        if (_adb is null) return;
        Run(_adb, $"-s {serial} logcat -c", TimeSpan.FromSeconds(10));
    }

    /// <summary>Starts a streaming logcat read for the app's tags. Any previous stream is stopped first.</summary>
    public void StartLogcat(string serial, string? filter = null)
    {
        if (_adb is null)
        {
            LogStopped?.Invoke("adb not found");
            return;
        }
        StopLogcat();
        lock (_logGate)
        {
            var info = new ProcessStartInfo
            {
                FileName = _adb,
                Arguments = $"-s {serial} logcat -v time {(filter ?? DefaultLogFilter)}",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            Process process = new() { StartInfo = info, EnableRaisingEvents = true };
            process.OutputDataReceived += (_, e) => { if (e.Data is not null) LogLine?.Invoke(e.Data); };
            process.ErrorDataReceived += (_, e) => { if (e.Data is not null) LogLine?.Invoke(e.Data); };
            process.Exited += (_, _) => LogStopped?.Invoke($"logcat ended (exit {process.ExitCode})");
            process.Start();
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            _logcat = process;
        }
    }

    public void StopLogcat()
    {
        lock (_logGate)
        {
            if (_logcat is null) return;
            try
            {
                if (!_logcat.HasExited)
                {
                    _logcat.Kill(entireProcessTree: true);
                }
            }
            catch (Exception)
            {
                // the stream may already be gone
            }
            _logcat.Dispose();
            _logcat = null;
        }
    }

    private static string Run(string adb, string arguments, TimeSpan timeout)
    {
        var info = new ProcessStartInfo
        {
            FileName = adb,
            Arguments = arguments,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
        };
        using var process = Process.Start(info);
        if (process is null) return "could not start adb";
        StringBuilder output = new();
        process.OutputDataReceived += (_, e) => { if (e.Data is not null) output.AppendLine(e.Data); };
        process.ErrorDataReceived += (_, e) => { if (e.Data is not null) output.AppendLine(e.Data); };
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();
        if (!process.WaitForExit((int)timeout.TotalMilliseconds))
        {
            try { process.Kill(entireProcessTree: true); } catch (Exception) { }
            return "adb timed out";
        }
        return output.ToString();
    }

    public void Dispose() => StopLogcat();
}
