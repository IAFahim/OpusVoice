using OpusVoice.Qr.Audio;
using OpusVoice.Qr.Core;

namespace OpusVoice.Qr.Core.Tests;

/// <summary>Headless proof of the voice pipeline: the test tone, the Concentus Opus round trip, and the detail formatter.</summary>
public class VoicePipelineTests
{
    [Fact]
    public void ToneFrameIsASineAtRoughlyTheRequestedAmplitude()
    {
        var frame = new short[TestTone.FrameSamples];
        double phase = 0;
        TestTone.FillSineFrame(frame, ref phase);

        int peak = frame.Max(sample => Math.Abs(sample));
        Assert.InRange(peak, 0.2 * short.MaxValue, 0.28 * short.MaxValue); // amplitude 0.25

        int signChanges = Enumerable.Range(0, frame.Length - 1).Count(i => frame[i] >= 0 != frame[i + 1] >= 0);
        // 960 samples at 48 kHz hold 8.8 periods of 440 Hz -> ~17-18 zero crossings
        Assert.InRange(signChanges, 15, 20);

        Assert.InRange(Math.Abs(frame.Select(sample => (double)sample).Average()), 0, 400); // no significant DC offset
    }

    [Fact]
    public void TonePhaseContinuesAcrossFrames()
    {
        var first = new short[TestTone.FrameSamples];
        var second = new short[TestTone.FrameSamples];
        double phase = 0;
        TestTone.FillSineFrame(first, ref phase);
        TestTone.FillSineFrame(second, ref phase);

        // two consecutive frames must differ (a fresh 0-phase frame would repeat identically)
        Assert.False(first.AsSpan().SequenceEqual(second.AsSpan()));
    }

    [Fact]
    public void OpusRoundTripsTheToneWithEnergyIntact()
    {
        var codec = new OpusStreamCodec { Bitrate = 32000 };
        var pcm = new short[OpusStreamCodec.FrameSamples];
        var decoded = new short[OpusStreamCodec.FrameSamples];
        var data = new byte[OpusStreamCodec.MaxFrameBytes];
        double phase = 0;
        TestTone.FillSineFrame(pcm, ref phase);

        int encodedLength = 0;
        int decodedSamples = 0;
        for (int i = 0; i < 6; i++) // a few frames for the encoder to settle
        {
            encodedLength = codec.EncodeFrame(pcm, data);
            decodedSamples = codec.DecodeFrame(data, encodedLength, decoded);
        }

        Assert.InRange(encodedLength, 1, OpusStreamCodec.MaxFrameBytes);
        Assert.Equal(OpusStreamCodec.FrameSamples, decodedSamples);
        Assert.True(decoded.Max(sample => Math.Abs(sample)) > 2000, "decoded audio should carry the tone's energy");
    }

    [Fact]
    public void OpusBitrateChangesTheFrameSize()
    {
        var pcm = new short[OpusStreamCodec.FrameSamples];
        var data = new byte[OpusStreamCodec.MaxFrameBytes];
        var rng = new Random(1234);
        for (int i = 0; i < pcm.Length; i++)
        {
            pcm[i] = (short)(Math.Sin(i * 0.03) * 9000 + rng.Next(-4000, 4000)); // tone plus noise needs bits
        }

        var narrowband = new OpusStreamCodec { Bitrate = 16000 };
        var wideband = new OpusStreamCodec { Bitrate = 128000 };
        int narrow = 0, wide = 0;
        for (int i = 0; i < 4; i++)
        {
            narrow = narrowband.EncodeFrame(pcm, data);
            wide = wideband.EncodeFrame(pcm, data);
        }

        Assert.InRange(narrow, 20, 80);
        Assert.True(wide > narrow * 4, $"128 kbps frames ({wide} B) should dwarf 16 kbps frames ({narrow} B)");
    }

    [Fact]
    public void FormatterShowsPeerIdNatHintAndKeys()
    {
        QrPayload payload = QrPayload.Parse(
            "pinhole1:AwPvzauJZ0UjAQECBMCoASrIIgEEywBxB8giAgECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5-gIGCg4Q");
        string described = string.Join('\n', PayloadFormatter.Describe(payload));

        Assert.Contains("Pinhole connection string v3", described);
        Assert.Contains("0x0123456789ABCDEF", described);
        Assert.Contains("cone", described);
        Assert.Contains("192.168.1.42:51234  ·  direct", described);
        Assert.Contains("203.0.113.7:51234  ·  reflexive", described);
        Assert.Contains("Static key     present (32 bytes)", described);
        Assert.Contains("Endpoint key   present (32 bytes)", described);
    }
}
