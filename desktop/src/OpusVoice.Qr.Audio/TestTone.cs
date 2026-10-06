namespace OpusVoice.Qr.Audio;

/// <summary>Generates the built-in 440 Hz test tone: 16-bit mono PCM at 48 kHz, 20 ms frames.</summary>
public static class TestTone
{
    public const int SampleRate = 48000;
    public const int FrameSamples = 960; // 20 ms at 48 kHz
    public const double DefaultFrequency = 440.0;
    public const double DefaultAmplitude = 0.25;

    /// <summary>
    /// Fills the frame with a continuous-phase sine tone; <paramref name="phase"/> (radians)
    /// carries across calls so concatenated frames are a single glitch-free tone.
    /// </summary>
    public static void FillSineFrame(short[] frame, ref double phase, double frequencyHz = DefaultFrequency, double amplitude = DefaultAmplitude)
    {
        ArgumentNullException.ThrowIfNull(frame);
        const double twoPi = 2.0 * Math.PI;
        double step = twoPi * frequencyHz / SampleRate;
        for (int i = 0; i < frame.Length; i++)
        {
            frame[i] = (short)Math.Round(Math.Sin(phase) * amplitude * short.MaxValue);
            phase += step;
            if (phase >= twoPi) phase -= twoPi;
        }
    }
}
