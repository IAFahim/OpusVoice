using Avalonia.Media;

namespace OpusVoice.Qr.Services;

/// <summary>The Android app's dark palette as code-side brushes (XAML-side keys live in App.axaml).</summary>
internal static class Palette
{
    public static readonly IBrush Background = Brush("#0F111A");
    public static readonly IBrush Surface = Brush("#161926");
    public static readonly IBrush SurfaceVariant = Brush("#202538");
    public static readonly IBrush Highlight = Brush("#2C324B");
    public static readonly IBrush Accent = Brush("#10B981");      // scan / ticket
    public static readonly IBrush AccentAlt = Brush("#5865F2");   // udp / voice
    public static readonly IBrush TextPrimary = Brush("#F1F5F9");
    public static readonly IBrush TextSecondary = Brush("#94A3B8");
    public static readonly IBrush TextTertiary = Brush("#64748B");
    public static readonly IBrush Error = Brush("#EF4444");
    public static readonly IBrush Warning = Brush("#F59E0B");

    private static IBrush Brush(string hex) => new SolidColorBrush(Color.Parse(hex));
}
