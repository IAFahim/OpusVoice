using Avalonia;
using Avalonia.Controls;
using Avalonia.Interactivity;

namespace OpusVoice.Qr;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
#if DEBUG
        this.AttachDevTools();
#endif
        Closing += OnClosing;
        ScanView.UdpTargetChosen += OnUdpTargetChosen;
        ScanView.PinholeTargetChosen += OnPinholeTargetChosen;
        VoiceView.QrRequested += OnQrRequested;
    }

    private void OnUdpTargetChosen(string host, int port)
    {
        VoiceView.SetTarget(host, port);
        Tabs.SelectedItem = VoiceTab;
    }

    private void OnPinholeTargetChosen(string ticket)
    {
        VoiceView.SetPinholeTarget(ticket);
        Tabs.SelectedItem = VoiceTab;
    }

    private void OnQrRequested(string text)
    {
        GenerateView.SetInput(text);
        Tabs.SelectedItem = GenerateTab;
    }

    private void OnTabSelectionChanged(object? sender, SelectionChangedEventArgs e)
    {
        if (ReferenceEquals(Tabs.SelectedItem, HistoryTab))
        {
            HistoryView.Refresh();
        }
    }

    private void OnClosing(object? sender, WindowClosingEventArgs e)
    {
        VoiceView.Shutdown();
        ScanView.Shutdown();
        DeviceView.Shutdown();
    }
}
