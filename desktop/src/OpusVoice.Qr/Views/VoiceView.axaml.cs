using Avalonia.Controls;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Threading;
using OpusVoice.Qr.Pinhole;
using OpusVoice.Qr.Services;
using OpusVoice.Qr.Services.Voice;

namespace OpusVoice.Qr.Views;

/// <summary>
/// The Voice tab: Opus-encodes the test tone or the microphone (48 kHz mono, 20 ms frames,
/// selectable bitrate), frames it as RTP (payload type 111) and sends it over the chosen
/// transport — plain UDP, or an encrypted Pinhole session dialed from a ticket (direct path
/// first, iroh relay fallback; the live path and RTT show in the stats). The listener panel
/// is the receiving half: it binds a Pinhole node, shows its connection string for the phone
/// to scan, and plays an accepted peer's stream. Stats are refreshed by a UI timer; every
/// audio path degrades gracefully.
/// </summary>
public partial class VoiceView : UserControl
{
    private static readonly int[] BitrateChoices = VoiceEngine.SupportedBitrates;
    private static readonly TimeSpan PingInterval = TimeSpan.FromSeconds(2);

    private readonly DispatcherTimer _statsTimer;
    private VoiceEngine? _engine;
    private PinholeVoiceTransport? _pinhole;
    private PinholeListenerService? _listener;
    private DateTimeOffset _lastPing = DateTimeOffset.MinValue;

    /// <summary>Raised when the user wants a string rendered as a QR on the Generate tab.</summary>
    public event Action<string>? QrRequested;

    public VoiceView()
    {
        InitializeComponent();
        _statsTimer = new DispatcherTimer(
            TimeSpan.FromMilliseconds(250),
            DispatcherPriority.Default,
            (_, _) => UpdateStats());
    }

    private bool PinholeMode => PinholeRadio.IsChecked == true;

    /// <summary>Fills the target fields from a scanned udp:// payload.</summary>
    public void SetTarget(string host, int port)
    {
        UdpRadio.IsChecked = true;
        UpdateTransportMode();
        HostBox.Text = host;
        PortBox.Text = port.ToString();
        SetStatus($"Voice target set to {host}:{port} — ready to stream.", Palette.Accent);
    }

    /// <summary>Fills the ticket box from a scanned pinhole payload.</summary>
    public void SetPinholeTarget(string ticket)
    {
        PinholeRadio.IsChecked = true;
        UpdateTransportMode();
        TicketBox.Text = ticket;
        SetStatus("Pinhole ticket loaded — start streaming to dial it (direct path, relay fallback).", Palette.Accent);
    }

    private void OnTransportModeClicked(object? sender, RoutedEventArgs e) => UpdateTransportMode();

    private void UpdateTransportMode()
    {
        bool pinhole = PinholeMode;
        UdpTargetLabel.IsVisible = !pinhole;
        UdpTargetPanel.IsVisible = !pinhole;
        PinholeTargetLabel.IsVisible = pinhole;
        TicketBox.IsVisible = pinhole;
        ListenPanel.IsVisible = pinhole;
    }

    private async void OnStartClicked(object? sender, RoutedEventArgs e)
    {
        int bitrate = BitrateChoices[Math.Clamp(BitrateBox.SelectedIndex, 0, BitrateChoices.Length - 1)];
        var source = MicRadio.IsChecked == true ? VoiceSourceKind.Microphone : VoiceSourceKind.TestTone;
        bool loopback = LoopbackCheck.IsChecked == true;
        string udpHost = HostBox.Text?.Trim() ?? string.Empty;
        int.TryParse(PortBox.Text?.Trim(), out int udpPort);

        PinholeVoiceTransport? transport = null;
        if (PinholeMode)
        {
            string ticket = TicketBox.Text?.Trim() ?? string.Empty;
            if (ticket.Length == 0)
            {
                SetStatus("Paste a pinhole connection string (or scan one on the Scan tab).", Palette.Warning);
                return;
            }

            StartButton.IsEnabled = false;
            SetStatus("Dialing the Pinhole ticket — punching, with relay fallback…", Palette.TextSecondary);
            PinholeDialOutcome dial = await PinholeVoiceTransport.TryConnectAsync(ticket);
            if (dial.Transport is null)
            {
                SetStatus($"Pinhole dial failed ({dial.Failure}): {dial.ErrorMessage}", Palette.Error);
                StartButton.IsEnabled = true;
                return;
            }
            transport = dial.Transport;
        }
        else
        {
            if (udpHost.Length == 0)
            {
                SetStatus("Enter a target host (or scan a udp:// QR on the Scan tab).", Palette.Warning);
                return;
            }

            if (udpPort is < 1 or > 65535)
            {
                SetStatus("The port must be a number between 1 and 65535.", Palette.Warning);
                return;
            }
        }

        _engine?.Dispose();
        _engine = new VoiceEngine();
        _pinhole = transport;
        _engine.StatusChanged += message => Dispatcher.UIThread.Post(() => SetStatus(message, Palette.TextSecondary));
        _engine.Failed += message => Dispatcher.UIThread.Post(() =>
        {
            SetStatus(message, Palette.Error);
            OnEngineStopped();
        });
        _engine.Stopped += () => Dispatcher.UIThread.Post(OnEngineStopped);

        // For UDP the engine builds its own transport from these fields; for Pinhole they
        // are ignored because the pre-connected transport is passed instead.
        _engine.Start(new VoiceEngineOptions(udpHost, udpPort, bitrate, source, loopback, transport));
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
        _pinhole = null;
        StartButton.IsEnabled = true;
        StopButton.IsEnabled = false;
        UpdateStats();
    }

    private async void OnListenStartClicked(object? sender, RoutedEventArgs e)
    {
        _listener?.Dispose();
        _listener = new PinholeListenerService();
        _listener.StatusChanged += message => Dispatcher.UIThread.Post(() => SetStatus(message, Palette.TextSecondary));
        ListenStartButton.IsEnabled = false;
        bool ok = await _listener.StartAsync(publishIroh: false);
        if (!ok)
        {
            ListenStartButton.IsEnabled = true;
            return;
        }
        ListenStopButton.IsEnabled = true;
        ListenCopyButton.IsEnabled = true;
        ListenQrButton.IsEnabled = true;
        if (_listener.Snapshot() is { } snapshot)
        {
            ListenTicketBox.Text = snapshot.ConnectionString;
            SetStatus("Listening — let the phone scan this string (Show QR) or paste it there.", Palette.Accent);
        }
        _statsTimer.Start();
    }

    private void OnListenStopClicked(object? sender, RoutedEventArgs e)
    {
        _listener?.Dispose();
        _listener = null;
        ListenStopButton.IsEnabled = false;
        ListenCopyButton.IsEnabled = false;
        ListenQrButton.IsEnabled = false;
        ListenStartButton.IsEnabled = true;
        ListenTicketBox.Text = string.Empty;
        MaybeStopTimer();
    }

    private async void OnListenCopyClicked(object? sender, RoutedEventArgs e)
    {
        if (TopLevel.GetTopLevel(this)?.Clipboard is { } clipboard && ListenTicketBox.Text is { Length: > 0 } text)
        {
            await clipboard.SetTextAsync(text);
            SetStatus("Connection string copied — paste or scan it into the phone app.", Palette.Accent);
        }
    }

    private void OnListenQrClicked(object? sender, RoutedEventArgs e)
    {
        if (ListenTicketBox.Text is { Length: > 0 } text)
        {
            QrRequested?.Invoke(text);
        }
    }

    private void MaybeStopTimer()
    {
        if (_engine is null && _listener is null)
        {
            _statsTimer.Stop();
        }
    }

    private void UpdateStats()
    {
        VoiceEngine? engine = _engine;
        PinholeListenerService? listener = _listener;
        if (engine is null && listener is null)
        {
            StatsText.Text = "idle";
            return;
        }

        var lines = new List<string>();
        if (engine is not null)
        {
            VoiceStats stats = engine.Snapshot();
            lines.Add("--- sending ---");
            lines.Add($"target    {stats.Target}");
            lines.Add($"bitrate   {stats.Bitrate / 1000} kbps");
            lines.Add($"elapsed   {stats.Elapsed:hh\\:mm\\:ss}");
            lines.Add($"packets   {stats.PacketsSent}");
            lines.Add($"rtp bytes {FormatBytes(stats.BytesSent)}");
            lines.Add($"opus data {FormatBytes(stats.OpusBytes)}");
            lines.Add($"loopback  {(stats.LoopbackActive ? "on" : "off")}");

            if (_pinhole is { } pinhole)
            {
                if (DateTimeOffset.UtcNow - _lastPing > PingInterval)
                {
                    _lastPing = DateTimeOffset.UtcNow;
                    pinhole.Ping();
                }
                PinholeVoiceStats p = pinhole.Snapshot();
                lines.Add($"path      {p.Path}");
                lines.Add($"rtt       {(p.LastRtt is { } rtt ? $"{rtt.TotalMilliseconds:F0} ms" : p.AverageRtt is { } avg ? $"~{avg.TotalMilliseconds:F0} ms" : "—")}");
                lines.Add($"dropped   {p.DroppedDatagrams} datagrams");
            }

            lines.Add($"errors    {stats.Errors}{(stats.LastError is null ? string.Empty : $" ({stats.LastError})")}");
            if (stats.PacketsSent == 0 && !engine.IsRunning)
            {
                lines.Add("note      nothing was sent");
            }
        }

        if (listener?.Snapshot() is { } listen)
        {
            lines.Add("--- listening ---");
            lines.Add($"relay     {(listen.RelayLive ? "live" : "none — direct only")}");
            lines.Add($"peer      {listen.Peer ?? "waiting for a peer to dial…"}");
            if (listen.PacketsReceived > 0)
            {
                lines.Add($"received  {listen.PacketsReceived} packets ({FormatBytes(listen.BytesReceived)})");
                lines.Add($"playing   {(listen.Playing ? "yes" : "no (no audio output)")}");
            }
        }

        StatsText.Text = string.Join('\n', lines);
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

    /// <summary>Stops everything; called when the window closes.</summary>
    public void Shutdown()
    {
        _statsTimer.Stop();
        _engine?.Dispose();
        _engine = null;
        _pinhole = null;
        _listener?.Dispose();
        _listener = null;
    }
}
