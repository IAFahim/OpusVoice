using QRCoder;

namespace OpusVoice.Qr.Services;

/// <summary>Renders arbitrary text as a QR code PNG, ECC level L (matching the C# receiver's terminal QRs).</summary>
internal static class QrRenderer
{
    public static byte[] RenderPng(string text)
    {
        using QRCodeData data = new QRCodeGenerator().CreateQrCode(text, QRCodeGenerator.ECCLevel.L);
        return new PngByteQRCode(data).GetGraphic(pixelsPerModule: 10);
    }
}
