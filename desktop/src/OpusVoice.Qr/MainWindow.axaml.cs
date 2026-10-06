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
    }

    private void OnUdpTargetChosen(string host, int port)
    {
        VoiceView.SetTarget(host, port);
        Tabs.SelectedItem = VoiceTab;
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
    }
}
