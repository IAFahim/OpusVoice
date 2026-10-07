using System.Runtime.InteropServices;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
using OpusVoice.Qr.Core;
using OpusVoice.Qr.Services;

namespace OpusVoice.Qr.Views;

/// <summary>
/// The Scan tab: live camera decoding with deliver-once semantics (first QR wins, scanning then
/// stops) plus a paste path routed through the same payload parser. Every successful scan lands
/// in the history store.
/// </summary>
public partial class ScanView : UserControl
{
    private CameraQrScanner? _scanner;
    private WriteableBitmap? _preview;
    private string? _scannedText;

    /// <summary>Raised when the user wants a scanned udp:// payload to become the Voice tab target.</summary>
    public event Action<string, int>? UdpTargetChosen;

    /// <summary>Raised when the user wants a scanned pinhole ticket to become the Voice tab target.</summary>
    public event Action<string>? PinholeTargetChosen;

    public ScanView()
    {
        InitializeComponent();
    }

    private void OnStartClicked(object? sender, RoutedEventArgs e)
    {
        if (_scanner is not null) return;
        StartButton.IsEnabled = false;
        StopButton.IsEnabled = true;
        SetCameraStatus("Opening camera…", Palette.TextSecondary);

        _scanner = new CameraQrScanner();
        _scanner.FrameReady += OnFrameReady;
        _scanner.Decoded += OnDecoded;
        _scanner.Failed += OnCameraFailed;
        _scanner.Start();
    }

    private void OnStopClicked(object? sender, RoutedEventArgs e)
    {
        StopScanner("Scanning stopped — the camera preview is off.");
    }

    private void OnParseClicked(object? sender, RoutedEventArgs e)
    {
        string? raw = PasteBox.Text;
        if (string.IsNullOrWhiteSpace(raw))
        {
            SetScanStatus("Paste some text first.", Palette.Warning);
            return;
        }

        ShowResult(raw);
        SetScanStatus("Pasted text routed.", Palette.TextSecondary);
    }

    private void OnCopyClicked(object? sender, RoutedEventArgs e)
    {
        if (_scannedText is not null)
        {
            _ = CopyToClipboardAsync(_scannedText);
        }
    }

    private void OnUseAsVoiceTargetClicked(object? sender, RoutedEventArgs e)
    {
        switch (QrPayload.Parse(_scannedText ?? string.Empty))
        {
            case QrPayload.UdpEndpoint udp:
                UdpTargetChosen?.Invoke(udp.Host, udp.Port);
                break;
            case QrPayload.PinholeTicket:
                PinholeTargetChosen?.Invoke((_scannedText ?? string.Empty).Trim());
                break;
        }
    }

    private void OnDecoded(string text)
    {
        Dispatcher.UIThread.Post(() =>
        {
            StopScanner("QR code detected — first decode wins, scanning stopped.");
            ShowResult(text);
            SetScanStatus("Decoded from camera.", Palette.TextSecondary);
        });
    }

    private void OnCameraFailed(string reason)
    {
        Dispatcher.UIThread.Post(() =>
        {
            StopScanner(reason);
            SetScanStatus(reason, Palette.Warning);
        });
    }

    private void OnFrameReady(CameraFrame frame)
    {
        Dispatcher.UIThread.Post(() => RenderFrame(frame));
    }

    private void RenderFrame(CameraFrame frame)
    {
        if (_preview is null || _preview.PixelSize.Width != frame.Width || _preview.PixelSize.Height != frame.Height)
        {
            _preview?.Dispose();
            _preview = new WriteableBitmap(new PixelSize(frame.Width, frame.Height), new Vector(96, 96));
            CameraImage.Source = _preview;
            CameraOverlay.IsVisible = false; // frames are flowing: hide the idle overlay
        }

        using (var fb = _preview.Lock())
        {
            for (int y = 0; y < frame.Height; y++)
            {
                Marshal.Copy(frame.Bgra, y * frame.Stride, IntPtr.Add(fb.Address, y * fb.RowBytes), frame.Width * 4);
            }
        }
    }

    private void StopScanner(string status)
    {
        CameraQrScanner? scanner = _scanner;
        _scanner = null;
        if (scanner is not null)
        {
            scanner.FrameReady -= OnFrameReady;
            scanner.Decoded -= OnDecoded;
            scanner.Failed -= OnCameraFailed;
            scanner.Dispose();
        }

        StartButton.IsEnabled = true;
        StopButton.IsEnabled = false;
        SetCameraStatus(status, Palette.TextSecondary);
    }

    private void ShowResult(string raw)
    {
        string text = raw.Trim();
        QrPayload payload = QrPayload.Parse(text);
        _scannedText = text;

        SetBadge(payload);
        DetailText.Text = string.Join('\n', PayloadFormatter.Describe(payload));
        CopyResultButton.IsEnabled = true;
        UseAsVoiceTargetButton.IsVisible = payload is QrPayload.UdpEndpoint or QrPayload.PinholeTicket;

        HistoryStore.Instance.Append(text);
    }

    private void SetBadge(QrPayload payload)
    {
        KindBadge.IsVisible = true;
        (string label, IBrush brush) = payload switch
        {
            QrPayload.PinholeTicket => ("pinhole ticket", Palette.Accent),
            QrPayload.UdpEndpoint => ("udp endpoint", Palette.AccentAlt),
            _ => ("unknown text", Palette.TextTertiary),
        };
        KindBadgeText.Text = label;
        KindBadge.Background = brush;
    }

    private void SetCameraStatus(string message, IBrush brush)
    {
        CameraStatus.Text = message;
        CameraStatus.Foreground = brush;
        CameraOverlay.IsVisible = true;
    }

    private void SetScanStatus(string message, IBrush brush)
    {
        ScanStatus.Text = message;
        ScanStatus.Foreground = brush;
    }

    private async Task CopyToClipboardAsync(string text)
    {
        IClipboard? clipboard = TopLevel.GetTopLevel(this)?.Clipboard;
        if (clipboard is null)
        {
            SetScanStatus("Clipboard is unavailable here.", Palette.Warning);
            return;
        }

        await clipboard.SetTextAsync(text);
        SetScanStatus("Copied to clipboard.", Palette.TextSecondary);
    }

    /// <summary>Stops scanning and releases the preview; called when the window closes.</summary>
    public void Shutdown()
    {
        StopScanner("Closed.");
        _preview?.Dispose();
        _preview = null;
    }
}
