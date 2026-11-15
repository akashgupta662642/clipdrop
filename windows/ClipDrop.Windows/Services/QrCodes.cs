using System.IO;
using System.Windows.Media.Imaging;
using QRCoder;

namespace ClipDrop.Windows.Services;

public static class QrCodes
{
    /// <summary>Renders <paramref name="text"/> as a QR code image (error correction level M).</summary>
    public static BitmapImage Render(string text, int pixelsPerModule = 8)
    {
        using var generator = new QRCodeGenerator();
        using QRCodeData data = generator.CreateQrCode(text, QRCodeGenerator.ECCLevel.M);
        byte[] png = new PngByteQRCode(data).GetGraphic(pixelsPerModule);

        var image = new BitmapImage();
        using var stream = new MemoryStream(png);
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = stream;
        image.EndInit();
        image.Freeze();
        return image;
    }
}
