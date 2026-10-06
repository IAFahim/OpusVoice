using Concentus;
using Concentus.Enums;

namespace OpusVoice.Qr.Audio;

/// <summary>
/// Thin wrapper over the pure-C# Concentus Opus codec: 48 kHz mono audio, 20 ms frames
/// (960 samples), matching the OpusVoice Android sender. Fully managed, so the voice
/// pipeline works on headless machines with no native Opus library installed.
/// </summary>
public sealed class OpusStreamCodec
{
    public const int SampleRate = 48000;
    public const int Channels = 1;
    public const int FrameSamples = 960;   // 20 ms at 48 kHz
    public const int MaxFrameBytes = 1275; // RFC 6716 maximum Opus packet size

    private readonly IOpusEncoder _encoder = OpusCodecFactory.CreateEncoder(SampleRate, Channels, OpusApplication.OPUS_APPLICATION_AUDIO);
    private readonly IOpusDecoder _decoder = OpusCodecFactory.CreateDecoder(SampleRate, Channels);

    /// <summary>Encoding bitrate in bits per second (16 kbps … 128 kbps are the app's choices).</summary>
    public int Bitrate
    {
        get => _encoder.Bitrate;
        set => _encoder.Bitrate = value;
    }

    /// <summary>Encodes one 960-sample frame into <paramref name="destination"/>; returns the encoded byte count.</summary>
    public int EncodeFrame(short[] pcm, byte[] destination)
    {
        ArgumentNullException.ThrowIfNull(pcm);
        ArgumentNullException.ThrowIfNull(destination);
        return _encoder.Encode(pcm.AsSpan(), pcm.Length, destination, destination.Length);
    }

    /// <summary>Decodes an encoded frame back into PCM; returns the decoded sample count.</summary>
    public int DecodeFrame(byte[] data, int length, short[] pcm)
    {
        ArgumentNullException.ThrowIfNull(data);
        ArgumentNullException.ThrowIfNull(pcm);
        return _decoder.Decode(data.AsSpan(0, length), pcm, pcm.Length, decode_fec: false);
    }
}
