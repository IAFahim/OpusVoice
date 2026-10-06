using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Threading;
using OpusVoice.Qr.Services;
using OpusVoice.Qr.Services.Voice;

namespace OpusVoice.Qr.Views;

/// <summary>
/// The Voice tab: Opus-encodes the test tone or the microphone (48 kHz mono, 20 ms frames,
/// selectable bitrate), frames it as RTP (payload type 111) and sends it over UDP. Stats are
/// refreshed by a UI timer from the engine snapshot; everything audio degrades gracefully.
/// </summary>
public partial class VoiceView : UserControl
{
    private static readonly int[] BitrateChoices = VoiceEngine.SupportedBitrates;

    private readonly DispatcherTimer _statsTimer;
    private VoiceEngine? _engine;

    public VoiceView()
    {
        InitializeComponent();
        _statsTimer = new DispatcherTimer(
            TimeSpan.FromMilliseconds(250),
            DispatcherPriority.Default,
            (_, _) => UpdateStats());
    }

    /// <summary>Fills the target fields from a scanned udp:// payload.</summary>
    public void SetTarget(string host, int port)
    {
        HostBox.Text = host;
        PortBox.Text = port.ToString();
        SetStatus($"Voice target set to {host}:{port} — ready to stream.", Palette.Accent);
    }

    private void OnStartClicked(object? sender, RoutedEventArgs e)
    {
        string host = HostBox.Text?.Trim() ?? string.Empty;
        if (host.Length == 0)
        {
            SetStatus("Enter a target host (or scan a udp:// QR on the Scan tab).", Palette.Warning);
            return;
        }

        if (!int.TryParse(PortBox.Text?.Trim(), out int port) || port is < 1 or > 65535)
        {
            SetStatus("The port must be a number between 1 and 65535.", Palette.Warning);
            return;
        }

        int bitrate = BitrateChoices[Math.Clamp(BitrateBox.SelectedIndex, 0, BitrateChoices.Length - 1)];
        var source = MicRadio.IsChecked == true ? VoiceSourceKind.Microphone : VoiceSourceKind.TestTone;
        bool loopback = LoopbackCheck.IsChecked == true;

        _engine?.Dispose();
        _engine = new VoiceEngine();
        _engine.StatusChanged += message => Dispatcher.UIThread.Post(() => SetStatus(message, Palette.TextSecondary));
        _engine.Failed += message => Dispatcher.UIThread.Post(() =>
        {
            SetStatus(message, Palette.Error);
            OnEngineStopped();
        });
        _engine.Stopped += () => Dispatcher.UIThread.Post(OnEngineStopped);

        _engine.Start(new VoiceEngineOptions(host, port, bitrate, source, loopback));
        if (_engine.IsRunning)
        {
            StartButton.IsEnabled = false;
            StopButton.IsEnabled = true;
            _statsTimer.Start();
        }
        else
        {
            OnEngineStopped();
        }
    }

    private void OnStopClicked(object? sender, RoutedEventArgs e) => _engine?.Stop();

    private void OnEngineStopped()
    {
        _statsTimer.Stop();
        StartButton.IsEnabled = true;
        StopButton.IsEnabled = false;
        UpdateStats();
    }

    private void UpdateStats()
    {
        VoiceEngine? engine = _engine;
        if (engine is null)
        {
            StatsText.Text = "idle";
            return;
        }

        VoiceStats stats = engine.Snapshot();
        StatsText.Text = string.Join('\n', new[]
        {
            $"target    {stats.Target}",
            $"bitrate   {stats.Bitrate / 1000} kbps",
            $"elapsed   {stats.Elapsed:hh\\:mm\\:ss}",
            $"packets   {stats.PacketsSent}",
            $"rtp bytes {FormatBytes(stats.BytesSent)}",
            $"opus data {FormatBytes(stats.OpusBytes)}",
            $"loopback  {(stats.LoopbackActive ? "on" : "off")}",
            $"errors    {stats.Errors}{(stats.LastError is null ? string.Empty : $" ({stats.LastError})")}",
            stats.PacketsSent == 0 && !engine.IsRunning ? "note      nothing was sent" : string.Empty,
        }.Where(line => line.Length > 0));
    }

    private void SetStatus(string message, IBrush brush)
    {
        VoiceStatus.Text = message;
        VoiceStatus.Foreground = brush;
    }

    private static string FormatBytes(long bytes) => bytes switch
    {
        < 1024 => $"{bytes} B",
        < 1024 * 1024 => $"{bytes / 1024.0:F1} KB",
        _ => $"{bytes / (1024 * 1024.0):F1} MB",
    };

    /// <summary>Stops the engine; called when the window closes.</summary>
    public void Shutdown()
    {
        _statsTimer.Stop();
        _engine?.Dispose();
        _engine = null;
    }
}
