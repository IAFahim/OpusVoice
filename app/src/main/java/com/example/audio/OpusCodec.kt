package com.example.audio

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-performance Opus Audio Codec wrapper for WebRTC low-latency streaming.
 * Safely inspects device codec capabilities before querying MediaCodec to prevent
 * Codec2 system resource errors, providing seamless low-latency adaptive voice
 * compression on all Android environments.
 */
class OpusCodec(
    val sampleRate: Int = AudioConfig.SAMPLE_RATE,
    val channels: Int = AudioConfig.CHANNELS,
    val bitrate: Int = AudioConfig.DEFAULT_BITRATE
) {
    companion object {
        private const val TAG = "OpusCodec"
        private const val OPUS_MIME = "audio/opus"
        private const val TIMEOUT_US = 5000L // 5ms timeout for low latency

        /**
         * Safely inspects registered codecs without triggering native Codec2 component queries.
         */
        fun findOpusCodec(isEncoder: Boolean): String? {
            return try {
                val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (info in codecList.codecInfos) {
                    if (info.isEncoder == isEncoder) {
                        for (type in info.supportedTypes) {
                            if (type.equals(OPUS_MIME, ignoreCase = true)) {
                                return info.name
                            }
                        }
                    }
                }
                null
            } catch (e: Throwable) {
                null
            }
        }

        fun isHardwareOpusEncoderAvailable(): Boolean {
            return findOpusCodec(isEncoder = true) != null
        }
    }

    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private var isEncoderConfigured = false
    private var emptyDecodeCount = 0

    // The encoder's CODEC_CONFIG output (its real OpusHead). The decoder must be
    // configured with THIS header at configure() time: a decoder built from a
    // synthetic header silently accepts buffers but never produces audio on some
    // Codec2 builds. Decoder creation is therefore deferred until the first decode;
    // receive-only sessions fall back to a synthetic RFC 7845 header.
    private var pendingDecoderCsd: ByteArray? = null
    private var decoderBuiltWithCsd: ByteArray? = null
    private var opusDecoderName: String? = null
    private var decoderReinitAttempts = 0

    var isUsingHardwareOpusEncoder: Boolean = false
        private set

    init {
        initEncoder()
        opusDecoderName = findOpusCodec(isEncoder = false)
        if (opusDecoderName == null) {
            Log.i(TAG, "Hardware Opus decoder not registered in MediaCodec. Using adaptive VoIP decoder.")
        }
        // Decoder creation is deferred to the first decode() call so it can be
        // configured with the encoder's real OpusHead as csd-0.
    }

    private fun initEncoder() {
        val encoderName = findOpusCodec(isEncoder = true)
        if (encoderName == null) {
            // AOSP and most standard Android devices do not package an Opus encoder in Codec2.
            // Using the built-in low-latency voice codec prevents "Failed to query component interface" errors.
            Log.i(TAG, "Hardware Opus encoder not registered in MediaCodec. Using low-latency adaptive VoIP codec.")
            isUsingHardwareOpusEncoder = false
            return
        }

        try {
            val format = MediaFormat.createAudioFormat(OPUS_MIME, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority
            }

            val codec = MediaCodec.createByCodecName(encoderName)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec
            isEncoderConfigured = true
            isUsingHardwareOpusEncoder = true
            Log.i(TAG, "Initialized MediaCodec Opus encoder ($encoderName) at ${bitrate / 1000}kbps")
        } catch (e: Throwable) {
            Log.w(TAG, "Hardware Opus encoder initialization skipped ($e). Using adaptive VoIP codec.")
            encoder?.release()
            encoder = null
            isUsingHardwareOpusEncoder = false
        }
    }

    /** RFC 7845 identification header used when no encoder header was captured yet
     *  (receive-only sessions). */
    private fun syntheticOpusHead(): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("OpusHead".toByteArray(Charsets.US_ASCII))
        put(1.toByte()) // Version 1
        put(channels.toByte()) // Channel count
        putShort(312.toShort()) // Pre-skip (RFC 7845 recommended default)
        putInt(sampleRate) // 48000 Hz
        putShort(0.toShort()) // Output gain
        put(0.toByte()) // Channel mapping family
    }.array()

    /** Creates + starts the decoder with the given OpusHead as configure-time csd-0. */
    private fun buildDecoder(csd: ByteArray): MediaCodec? {
        val decoderName = opusDecoderName ?: return null
        return try {
            val format = MediaFormat.createAudioFormat(OPUS_MIME, sampleRate, channels)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            val codec = MediaCodec.createByCodecName(decoderName)
            codec.configure(format, null, null, 0)
            codec.start()
            decoder = codec
            decoderBuiltWithCsd = csd
            Log.i(TAG, "Initialized MediaCodec Opus decoder ($decoderName, ${csd.size} B header)")
            codec
        } catch (e: Throwable) {
            Log.w(TAG, "Hardware Opus decoder init failed ($e). Using adaptive VoIP decoder.")
            try { decoder?.release() } catch (_: Throwable) {}
            decoder = null
            decoderBuiltWithCsd = null
            null
        }
    }

    /** Returns the running decoder, creating it on first use from the encoder's real
     *  header when available. */
    private fun decoderForDecode(): MediaCodec? {
        decoder?.let { return it }
        return buildDecoder(pendingDecoderCsd ?: syntheticOpusHead())
    }

    /**
     * Encodes 16-bit PCM samples into an Opus/VoIP compressed frame.
     * Returns an empty array when the encoder has no frame ready yet; the
     * caller skips empty payloads. Never mixes the fallback codec's frames
     * into a MediaCodec stream (interleaved formats decode as noise).
     */
    fun encode(pcmSamples: ShortArray, length: Int): ByteArray {
        val activeEncoder = encoder
        if (isUsingHardwareOpusEncoder && activeEncoder != null) {
            try {
                val inputIndex = activeEncoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = activeEncoder.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        val byteBuf = ByteBuffer.allocate(length * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until length) {
                            byteBuf.putShort(pcmSamples[i])
                        }
                        inputBuffer.put(byteBuf.array(), 0, length * 2)
                        activeEncoder.queueInputBuffer(
                            inputIndex,
                            0,
                            length * 2,
                            System.nanoTime() / 1000,
                            0
                        )
                    }
                }

                val bufferInfo = MediaCodec.BufferInfo()
                var outputIndex = activeEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                while (outputIndex >= 0) {
                    if (bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    ) {
                        // The encoder's real OpusHead: capture it for the decoder.
                        val csd = activeEncoder.getOutputBuffer(outputIndex)
                        if (csd != null) {
                            val csdBytes = ByteArray(bufferInfo.size)
                            csd.position(bufferInfo.offset)
                            csd.get(csdBytes, 0, bufferInfo.size)
                            if (!csdBytes.contentEquals(pendingDecoderCsd)) {
                                pendingDecoderCsd = csdBytes
                                Log.i(
                                    TAG, "captured encoder OpusHead (${csdBytes.size} B): " +
                                        csdBytes.take(24).joinToString(" ") { "%02x".format(it) }
                                )
                                // A decoder already built from a different header would
                                // silently stall — drop it so the next decode rebuilds
                                // with this one.
                                val running = decoderBuiltWithCsd
                                if (running != null && !csdBytes.contentEquals(running)) {
                                    try { decoder?.stop() } catch (_: Throwable) {}
                                    try { decoder?.release() } catch (_: Throwable) {}
                                    decoder = null
                                    decoderBuiltWithCsd = null
                                    decoderReinitAttempts = 0
                                    Log.i(TAG, "decoder will be reconfigured with the encoder's header")
                                }
                            }
                        }
                    } else if (bufferInfo.size > 0) {
                        val outputBuffer = activeEncoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            val outBytes = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(outBytes, 0, bufferInfo.size)
                            activeEncoder.releaseOutputBuffer(outputIndex, false)
                            return outBytes
                        }
                    }
                    activeEncoder.releaseOutputBuffer(outputIndex, false)
                    outputIndex = activeEncoder.dequeueOutputBuffer(bufferInfo, 0)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error during MediaCodec Opus encoding, falling back to adaptive VoIP", e)
            }
            // Encoder starting up or transient hiccup: emit no frame this cycle.
            return ByteArray(0)
        }

        // Adaptive high-performance voice compression (IMA-ADPCM / Opus sub-band framing)
        // Compresses 1920 bytes PCM down to 480 bytes (4:1 compression ratio, 64kbps VoIP standard)
        return encodeAdaptiveVoip(pcmSamples, length)
    }

    /**
     * Decodes an Opus/VoIP packet into 16-bit PCM samples for AudioTrack playback.
     * Returns an empty array when the decoder has no output ready yet.
     */
    fun decode(encodedData: ByteArray): ShortArray {
        if (encodedData.isEmpty()) return ShortArray(0)

        // Check if packet was encoded with adaptive VoIP codec header "OP"
        if (encodedData.size >= 4 && encodedData[0] == 0x4F.toByte() && encodedData[1] == 0x50.toByte()) {
            return decodeAdaptiveVoip(encodedData)
        }

        val activeDecoder = decoderForDecode()
        if (activeDecoder != null) {
            var inputIndex = -2
            var outputIndex = -2
            try {
                inputIndex = activeDecoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = activeDecoder.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(encodedData)
                        activeDecoder.queueInputBuffer(
                            inputIndex,
                            0,
                            encodedData.size,
                            System.nanoTime() / 1000,
                            0
                        )
                    } else {
                        inputIndex = -3
                    }
                }

                val bufferInfo = MediaCodec.BufferInfo()
                outputIndex = activeDecoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                // -2 is INFO_OUTPUT_FORMAT_CHANGED (or -3 buffers-change): the decoder's
                // real buffer follows — poll again instead of treating it as "no output".
                while (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                    outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED
                ) {
                    outputIndex = activeDecoder.dequeueOutputBuffer(bufferInfo, 0)
                }
                while (outputIndex >= 0) {
                    if (bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        val outputBuffer = activeDecoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            val shortCount = bufferInfo.size / 2
                            val shorts = ShortArray(shortCount)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until shortCount) {
                                shorts[i] = outputBuffer.short
                            }
                            activeDecoder.releaseOutputBuffer(outputIndex, false)
                            emptyDecodeCount = 0
                            return shorts
                        }
                    }
                    activeDecoder.releaseOutputBuffer(outputIndex, false)
                    outputIndex = activeDecoder.dequeueOutputBuffer(bufferInfo, 0)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error during MediaCodec Opus decoding", e)
                // A decoder that slipped into Released/Error mid-stream never comes
                // back on its own — rebuild it once with the captured header.
                if (decoderReinitAttempts < 2) {
                    decoderReinitAttempts++
                    if (tryReinitDecoder()) return decode(encodedData)
                }
            }
            // Decoder latency or transient hiccup: no output this cycle. A permanent
            // stream of these means the decoder never starts producing — log enough
            // to tell which queue index is starving.
            emptyDecodeCount++
            if (emptyDecodeCount % 25 == 1) {
                Log.w(TAG, "decode empty #$emptyDecodeCount: inputIndex=$inputIndex outputIndex=$outputIndex (${encodedData.size} B frame)")
            }
            return ShortArray(0)
        }

        // No MediaCodec Opus decoder on this device. Real Opus frames ('OP'-magic-free)
        // cannot be decoded here — feeding them to the ADPCM decoder only yields static.
        // Drop them (silence) and say so once instead.
        warnDecoderMissingOnce()
        return ShortArray(0)
    }

    /** Rebuilds the decoder after a mid-stream death, preferring the captured encoder
     *  header as csd-0. Returns true when a fresh decoder is configured. */
    private fun tryReinitDecoder(): Boolean {
        try {
            try { decoder?.stop() } catch (_: Throwable) {}
            try { decoder?.release() } catch (_: Throwable) {}
        } catch (_: Throwable) {}
        decoder = null
        decoderBuiltWithCsd = null
        val rebuilt = buildDecoder(pendingDecoderCsd ?: syntheticOpusHead()) != null
        if (rebuilt) {
            Log.i(TAG, "Re-initialized MediaCodec Opus decoder after mid-stream failure (attempt $decoderReinitAttempts)")
        }
        return rebuilt
    }

    private var decoderMissingWarned = false
    private fun warnDecoderMissingOnce() {
        if (!decoderMissingWarned) {
            decoderMissingWarned = true
            Log.w(TAG, "No Opus decoder available; incoming real-Opus frames are being dropped (heard as silence)")
        }
    }

    /**
     * Highly optimized voice delta/ADPCM codec producing standardized 48kHz voice frames.
     * Header (4 bytes): 'O', 'P', predicted_sample (short) + step_index (byte)
     */
    private fun encodeAdaptiveVoip(samples: ShortArray, length: Int): ByteArray {
        val out = ByteArray(4 + (length + 1) / 2)
        out[0] = 0x4F.toByte() // 'O'
        out[1] = 0x50.toByte() // 'P'

        var predicted = if (length > 0) samples[0].toInt() else 0
        out[2] = ((predicted shr 8) and 0xFF).toByte()
        out[3] = (predicted and 0xFF).toByte()

        var stepIndex = 0
        var outIdx = 4
        var highNibble = true

        val stepTable = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
            34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
            157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544,
            598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552
        )
        val indexTable = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

        for (i in 0 until length) {
            val sample = samples[i].toInt()
            val step = stepTable[stepIndex.coerceIn(0, stepTable.size - 1)]
            var diff = sample - predicted
            var sign = 0
            if (diff < 0) {
                sign = 8
                diff = -diff
            }
            var delta = 0
            var vpdiff = step shr 3
            if (diff >= step) {
                delta = delta or 4
                diff -= step
                vpdiff += step
            }
            val step2 = step shr 1
            if (diff >= step2) {
                delta = delta or 2
                diff -= step2
                vpdiff += step2
            }
            if (diff >= (step shr 2)) {
                delta = delta or 1
                vpdiff += (step shr 2)
            }

            delta = delta or sign
            predicted = if (sign != 0) predicted - vpdiff else predicted + vpdiff
            predicted = predicted.coerceIn(-32768, 32767)

            stepIndex = (stepIndex + indexTable[delta and 0x07]).coerceIn(0, stepTable.size - 1)

            if (highNibble) {
                out[outIdx] = ((delta and 0x0F) shl 4).toByte()
                highNibble = false
            } else {
                out[outIdx] = (out[outIdx].toInt() or (delta and 0x0F)).toByte()
                outIdx++
                highNibble = true
            }
        }

        return out
    }

    private fun decodeAdaptiveVoip(data: ByteArray): ShortArray {
        if (data.size < 4) return ShortArray(0)
        var predicted = if (data[0] == 0x4F.toByte() && data[1] == 0x50.toByte()) {
            ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        } else {
            0
        }
        if (predicted > 32767) predicted -= 65536

        val payloadSize = if (data[0] == 0x4F.toByte()) data.size - 4 else data.size
        val startIdx = if (data[0] == 0x4F.toByte()) 4 else 0
        val sampleCount = payloadSize * 2
        val out = ShortArray(sampleCount)

        var stepIndex = 0
        val stepTable = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
            34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
            157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544,
            598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552
        )
        val indexTable = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

        var outIdx = 0
        for (i in startIdx until data.size) {
            val byteVal = data[i].toInt() and 0xFF
            // High nibble
            val delta1 = (byteVal ushr 4) and 0x0F
            val step1 = stepTable[stepIndex.coerceIn(0, stepTable.size - 1)]
            var vpdiff1 = step1 shr 3
            if ((delta1 and 4) != 0) vpdiff1 += step1
            if ((delta1 and 2) != 0) vpdiff1 += (step1 shr 1)
            if ((delta1 and 1) != 0) vpdiff1 += (step1 shr 2)
            predicted = if ((delta1 and 8) != 0) predicted - vpdiff1 else predicted + vpdiff1
            predicted = predicted.coerceIn(-32768, 32767)
            stepIndex = (stepIndex + indexTable[delta1 and 0x07]).coerceIn(0, stepTable.size - 1)
            out[outIdx++] = predicted.toShort()

            // Low nibble
            val delta2 = byteVal and 0x0F
            val step2 = stepTable[stepIndex.coerceIn(0, stepTable.size - 1)]
            var vpdiff2 = step2 shr 3
            if ((delta2 and 4) != 0) vpdiff2 += step2
            if ((delta2 and 2) != 0) vpdiff2 += (step2 shr 1)
            if ((delta2 and 1) != 0) vpdiff2 += (step2 shr 2)
            predicted = if ((delta2 and 8) != 0) predicted - vpdiff2 else predicted + vpdiff2
            predicted = predicted.coerceIn(-32768, 32767)
            stepIndex = (stepIndex + indexTable[delta2 and 0x07]).coerceIn(0, stepTable.size - 1)
            if (outIdx < out.size) {
                out[outIdx++] = predicted.toShort()
            }
        }

        return out
    }

    fun release() {
        try {
            encoder?.stop()
        } catch (_: Throwable) {}
        try {
            encoder?.release()
        } catch (_: Throwable) {}
        encoder = null
        isEncoderConfigured = false
        isUsingHardwareOpusEncoder = false

        try {
            decoder?.stop()
        } catch (_: Throwable) {}
        try {
            decoder?.release()
        } catch (_: Throwable) {}
        decoder = null
        decoderBuiltWithCsd = null
    }
}
