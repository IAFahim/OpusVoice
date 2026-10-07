using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Interactivity;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using OpusVoice.Qr.Core;
using OpusVoice.Qr.Services;

namespace OpusVoice.Qr.Views;

/// <summary>
/// The Generate tab: multi-line text in, live QR preview out (ECC level L), with structured
/// details whenever the input parses as a pinhole ticket or a udp:// endpoint.
/// </summary>
public partial class GenerateView : UserControl
{
    private readonly DispatcherTimer _debounce;
    private Bitmap? _qrBitmap;
    private byte[]? _png;

    public GenerateView()
    {
        InitializeComponent();
        _debounce = new DispatcherTimer(DispatcherPriority.Background)
        {
            Interval = TimeSpan.FromMilliseconds(180),
        };
        _debounce.Tick += OnDebounceTick;
        InputBox.TextChanged += OnInputChanged;
        Regenerate();
    }

    /// <summary>Fills the input from elsewhere in the app (e.g. the Voice tab's "Show QR"
    /// for its listener connection string) and regenerates immediately.</summary>
    public void SetInput(string text)
    {
        InputBox.Text = text;
    }

    private void OnDebounceTick(object? sender, EventArgs e)
    {
        _debounce.Stop();
        Regenerate();
    }

    private void OnInputChanged(object? sender, EventArgs e)
    {
        _debounce.Stop();
        _debounce.Start();
    }

    private void Regenerate()
    {
        string text = InputBox.Text ?? string.Empty;
        GenerateStatus.Text = text.Length == 0
            ? "Type something to render a QR code."
            : $"{text.Length} characters encoded at ECC level L.";

        if (text.Length == 0)
        {
            ClearQr();
        }
        else
        {
            try
            {
                _png = QrRenderer.RenderPng(text);
                var old = _qrBitmap;
                using (var stream = new MemoryStream(_png))
                {
                    _qrBitmap = new Bitmap(stream);
                }

                QrImage.Source = _qrBitmap;
                old?.Dispose();
            }
            catch (Exception)
            {
                _png = null;
                ClearQr();
                GenerateStatus.Text = "Input too long for a single QR code (about 2953 bytes at ECC L).";
                GenerateStatus.Foreground = Palette.Warning;
            }
        }

        QrPayload payload = QrPayload.Parse(text);
        DetailText.Text = string.Join('\n', PayloadFormatter.Describe(payload));
        SetBadge(payload);
    }

    private void ClearQr()
    {
        QrImage.Source = null;
        _qrBitmap?.Dispose();
        _qrBitmap = null;
        _png = null;
        GenerateStatus.Foreground = Palette.TextTertiary;
    }

    private void SetBadge(QrPayload payload)
    {
        (string label, IBrush brush) = payload switch
        {
            QrPayload.PinholeTicket => ("pinhole ticket", Palette.Accent),
            QrPayload.UdpEndpoint => ("udp endpoint", Palette.AccentAlt),
            _ => ("free text", Palette.TextTertiary),
        };
        KindBadge.IsVisible = true;
        KindBadgeText.Text = label;
        KindBadge.Background = brush;
    }

    private async void OnCopyClicked(object? sender, RoutedEventArgs e)
    {
        string text = InputBox.Text ?? string.Empty;
        if (text.Length == 0) return;
        IClipboard? clipboard = TopLevel.GetTopLevel(this)?.Clipboard;
        if (clipboard is null)
        {
            GenerateStatus.Text = "Clipboard is unavailable here.";
            GenerateStatus.Foreground = Palette.Warning;
            return;
        }

        await clipboard.SetTextAsync(text);
        GenerateStatus.Text = "Text copied to clipboard.";
        GenerateStatus.Foreground = Palette.TextSecondary;
    }

    private async void OnSaveClicked(object? sender, RoutedEventArgs e)
    {
        if (_png is null)
        {
            GenerateStatus.Text = "Nothing to save yet — enter some text first.";
            GenerateStatus.Foreground = Palette.Warning;
            return;
        }

        IStorageProvider storage = TopLevel.GetTopLevel(this)?.StorageProvider
            ?? throw new InvalidOperationException("no storage provider");
        IStorageFile? file = await storage.SaveFilePickerAsync(new FilePickerSaveOptions
        {
            Title = "Save QR code",
            SuggestedFileName = "qr.png",
            DefaultExtension = "png",
            FileTypeChoices = new List<FilePickerFileType> { new("PNG image") { Patterns = new[] { "*.png" } } },
        });
        if (file is null) return;

        await using (var stream = await file.OpenWriteAsync())
        {
            await stream.WriteAsync(_png);
        }

        GenerateStatus.Text = $"Saved {file.Name}.";
        GenerateStatus.Foreground = Palette.TextSecondary;
    }
}
