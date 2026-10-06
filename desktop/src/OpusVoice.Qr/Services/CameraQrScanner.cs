using System.Runtime.InteropServices;
using OpenCvSharp;
using ZXing;
using ZXing.Common;
using ZXing.QrCode;

namespace OpusVoice.Qr.Services;

/// <summary>A camera frame ready for display: tight BGRA pixels (used by the UI thread's WriteableBitmap).</summary>
internal readonly record struct CameraFrame(byte[] Bgra, int Width, int Height, int Stride);

/// <summary>
/// Continuously grabs webcam frames and decodes QR codes from them. The first successful decode
/// wins and stops the loop (deliver-once semantics, mirroring the Android scanner's AtomicBoolean
/// gate). Camera failure — including a missing OpenCV runtime on a headless machine — is reported
/// through <see cref="Failed"/> instead of crashing.
/// </summary>
internal sealed class CameraQrScanner : IDisposable
{
    private readonly CancellationTokenSource _cts = new();
    private readonly QRCodeReader _qrReader = new();
    private readonly Dictionary<DecodeHintType, object> _hints = new() { { DecodeHintType.TRY_HARDER, true } };
    private Task? _loop;
    private int _delivered; // 0 = nothing delivered yet; flipped once by the first decode

    /// <summary>Raised on the capture thread for every grabbed frame.</summary>
    public event Action<CameraFrame>? FrameReady;

    /// <summary>Raised exactly once, on the capture thread, with the QR code's text.</summary>
    public event Action<string>? Decoded;

    /// <summary>Raised on the capture thread when the camera cannot be opened or the stream dies.</summary>
    public event Action<string>? Failed;

    public void Start(int cameraIndex = 0) => _loop ??= Task.Run(() => Run(cameraIndex, _cts.Token));

    private void Run(int cameraIndex, CancellationToken ct)
    {
        VideoCapture? capture = null;
        try
        {
            capture = VideoCapture.FromCamera(cameraIndex, VideoCaptureAPIs.ANY);
            if (capture is null || !capture.IsOpened())
            {
                Fail("No camera is available on this machine — paste a payload below instead.");
                return;
            }

            using var frame = new Mat();
            int emptyFrames = 0;
            while (!ct.IsCancellationRequested)
            {
                bool grabbed;
                try
                {
                    grabbed = capture.Read(frame);
                }
                catch (Exception)
                {
                    grabbed = false;
                }

                if (!grabbed || frame.Empty())
                {
                    if (++emptyFrames > 30)
                    {
                        Fail("The camera stream ended — paste a payload below instead.");
                        return;
                    }

                    ct.WaitHandle.WaitOne(20);
                    continue;
                }

                emptyFrames = 0;
                PublishFrame(frame);

                string? text = TryDecodeFrame(frame);
                if (text is not null)
                {
                    // first decode wins: deliver once, then stop scanning
                    if (Interlocked.CompareExchange(ref _delivered, 1, 0) == 0)
                    {
                        Decoded?.Invoke(text);
                    }

                    return;
                }

                ct.WaitHandle.WaitOne(30); // keep the loop near ~20-30 fps
            }
        }
        catch (Exception ex) when (ex is DllNotFoundException or TypeInitializationException or BadImageFormatException)
        {
            Fail("The OpenCV native runtime could not be loaded — paste a payload below instead.");
        }
        catch (Exception ex)
        {
            Fail($"Camera failed ({Friendly(ex)}) — paste a payload below instead.");
        }
        finally
        {
            TryDispose(capture);
        }
    }

    private void PublishFrame(Mat bgr)
    {
        using var display = new Mat();
        Cv2.CvtColor(bgr, display, ColorConversionCodes.BGR2BGRA);
        int width = display.Width;
        int height = display.Height;
        int stride = width * 4;
        var bytes = new byte[stride * height];

        bool tight = display.IsContinuous() && display.Step() == stride;
        if (tight)
        {
            Marshal.Copy(display.Data, bytes, 0, bytes.Length);
        }
        else
        {
            using Mat clone = display.Clone(); // ensures tightly packed rows
            for (int y = 0; y < height; y++)
            {
                Marshal.Copy(clone.Data + (int)(clone.Step() * y), bytes, y * stride, stride);
            }
        }

        FrameReady?.Invoke(new CameraFrame(bytes, width, height, stride));
    }

    /// <summary>Decodes one frame; restricted to QR codes (QRCodeReader), grayscale luminance source.</summary>
    private string? TryDecodeFrame(Mat bgr)
    {
        try
        {
            using var gray = new Mat();
            Cv2.CvtColor(bgr, gray, ColorConversionCodes.BGR2GRAY);
            int width = gray.Width;
            int height = gray.Height;
            var luminance = new byte[width * height];

            bool tight = gray.IsContinuous() && gray.Step() == width;
            if (tight)
            {
                Marshal.Copy(gray.Data, luminance, 0, luminance.Length);
            }
            else
            {
                using Mat clone = gray.Clone();
                for (int y = 0; y < height; y++)
                {
                    Marshal.Copy(clone.Data + (int)(clone.Step() * y), luminance, y * width, width);
                }
            }

            var source = new RGBLuminanceSource(luminance, width, height, RGBLuminanceSource.BitmapFormat.Gray8);
            var binarizer = new HybridBinarizer(source);
            var bitmap = new BinaryBitmap(binarizer);
            try
            {
                return _qrReader.decode(bitmap, _hints)?.Text;
            }
            catch (ReaderException)
            {
                return null; // simply no QR code in this frame
            }
        }
        catch (Exception)
        {
            return null; // decoding must never take the app down
        }
    }

    private void Fail(string message) => Failed?.Invoke(message);

    private static string Friendly(Exception ex) => ex.Message.Split('\n')[0].Trim();

    private static void TryDispose(IDisposable? disposable)
    {
        try
        {
            disposable?.Dispose();
        }
        catch (Exception)
        {
            // native cleanup failures are not fatal
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        try
        {
            _loop?.Wait(TimeSpan.FromSeconds(2));
        }
        catch (AggregateException)
        {
            // the loop is fault-tolerant; ignore late faults during teardown
        }

        _cts.Dispose();
    }
}
