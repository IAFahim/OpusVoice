using System.Collections.ObjectModel;
using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Interactivity;
using Avalonia.Media;
using OpusVoice.Qr.Core;
using OpusVoice.Qr.Services;

namespace OpusVoice.Qr.Views;

/// <summary>One row of the history list, pre-routed for display.</summary>
public sealed class HistoryRow
{
    public HistoryRow(HistoryEntry entry)
    {
        Raw = entry.RawText;
        When = entry.ScannedAtUtc.ToLocalTime().ToString("yyyy-MM-dd HH:mm:ss");
        (KindLabel, KindBrush) = QrPayload.Parse(entry.RawText) switch
        {
            QrPayload.PinholeTicket => ("pinhole", Palette.Accent),
            QrPayload.UdpEndpoint => ("udp", Palette.AccentAlt),
            _ => ("text", Palette.TextTertiary),
        };
        Preview = entry.RawText.Length > 96 ? $"{entry.RawText[..96]}…" : entry.RawText;
        LengthLabel = $"{entry.RawText.Length} chars";
    }

    public string Raw { get; }

    public string When { get; }

    public string KindLabel { get; }

    public IBrush KindBrush { get; }

    public string Preview { get; }

    public string LengthLabel { get; }
}

/// <summary>
/// The History tab: persisted JSON, newest first; click an entry to re-route it, with a copy
/// button and a clear-all.
/// </summary>
public partial class HistoryView : UserControl
{
    private readonly ObservableCollection<HistoryRow> _rows = new();
    private HistoryRow? _selected;

    public HistoryView()
    {
        InitializeComponent();
        HistoryList.ItemsSource = _rows;
        Refresh();
    }

    /// <summary>Reloads from disk; called every time the tab is selected and at startup.</summary>
    public void Refresh()
    {
        HistoryRow? selected = _selected;
        _rows.Clear();
        foreach (HistoryEntry entry in HistoryStore.Instance.Load())
        {
            _rows.Add(new HistoryRow(entry));
        }

        CountText.Text = _rows.Count == 0
            ? "no scans yet"
            : $"{_rows.Count} scan{(_rows.Count == 1 ? string.Empty : "s")}";
        if (selected is not null)
        {
            HistoryRow? same = _rows.FirstOrDefault(row => row.Raw == selected.Raw);
            HistoryList.SelectedItem = same;
            _selected = same;
        }
    }

    private void OnSelectionChanged(object? sender, SelectionChangedEventArgs e)
    {
        _selected = HistoryList.SelectedItem as HistoryRow;
        if (_selected is null)
        {
            DetailText.Text = "Select a scan to see its routed details again.";
            CopyEntryButton.IsEnabled = false;
            return;
        }

        DetailText.Text = string.Join('\n', PayloadFormatter.Describe(QrPayload.Parse(_selected.Raw)));
        CopyEntryButton.IsEnabled = true;
    }

    private void OnClearClicked(object? sender, RoutedEventArgs e)
    {
        HistoryStore.Instance.Clear();
        _selected = null;
        HistoryList.SelectedItem = null;
        Refresh();
    }

    private async void OnCopyClicked(object? sender, RoutedEventArgs e)
    {
        if (_selected is null) return;
        IClipboard? clipboard = TopLevel.GetTopLevel(this)?.Clipboard;
        if (clipboard is null) return;
        await clipboard.SetTextAsync(_selected.Raw);
    }
}
