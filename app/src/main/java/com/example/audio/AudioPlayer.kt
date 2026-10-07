package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.example.webrtc.JitterBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Low-latency VoIP Audio Player for rendering incoming RTP Opus packets.
 * Feeds from the Adaptive Jitter Buffer and decodes frames to AudioTrack.
 */
class AudioPlayer(
    private val jitterBuffer: JitterBuffer,
    // Mutable so the ViewModel can swap in a reconfigured codec (e.g. after a
    // bitrate change); the playout loop reads it fresh on every packet. Volatile:
    // the swap happens on the main thread, the read on the IO playout thread.
    @Volatile var codec: OpusCodec,
    private val onPlayoutLevelUpdate: (db: Float, isPlaying: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private var playoutJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    var isPlaying: Boolean = false
        private set
    var isMuted: Boolean = false
    var volume: Float = 1.0f

    // PLC (Packet Loss Concealment) cache
    private var lastDecodedFrame = ShortArray(AudioConfig.SAMPLES_PER_FRAME)

    fun start(): Boolean {
        if (isPlaying) return true

        val minBufferSize = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (AudioConfig.FRAME_BYTES * 4).coerceAtLeast(minBufferSize)

        try {
            // USAGE_MEDIA (not VOICE_COMMUNICATION): this track is a monitor — the user's
            // own voice played back to them. Voice-comm routing on phones with mode NORMAL
            // sends output to the earpiece on the in-call volume slider, which reads as
            // total silence; media routing plays on the speaker at media volume, matching
            // the desktop loopback monitor and web console.
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(AudioConfig.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize")
                track.release()
                return false
            }

            track.play()
            audioTrack = track
            isPlaying = true

            playoutJob = scope.launch {
                runPlayoutLoop(track)
            }
            Log.i(TAG, "VoIP AudioPlayer started successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioTrack", e)
            return false
        }
    }

    private suspend fun runPlayoutLoop(track: AudioTrack) {
        var emptyDecodes = 0
        var decodedFrames = 0
        while (scope.isActive && isPlaying) {
            when (val result = jitterBuffer.pollNextPlayout()) {
                is JitterBuffer.PlayoutResult.PacketReady -> {
                    val pcm = codec.decode(result.packet.payload)
                    if (pcm.isEmpty()) {
                        // MediaCodec warm-up drops a frame or two; a permanent stream of
                        // empty decodes means the decoder never produces output at all.
                        emptyDecodes++
                        if (emptyDecodes % 50 == 1) {
                            val payload = result.packet.payload
                            Log.w(TAG, "decode returned empty x$emptyDecodes (frame ${payload.size} B, " +
                                "head=${payload.take(4).joinToString(",") { (it.toInt() and 0xFF).toString(16) }})")
                        }
                    } else {
                        decodedFrames++
                        if (decodedFrames == 1) {
                            Log.i(TAG, "first frame decoded: ${pcm.size} samples")
                        }
                    }
                    if (!renderPcm(track, pcm)) break
                }

                is JitterBuffer.PlayoutResult.PacketWithLoss -> {
                    // Conceal lost packets: generate attenuated decay of last frame (PLC)
                    val plcSamples = ShortArray(AudioConfig.SAMPLES_PER_FRAME)
                    for (i in plcSamples.indices) {
                        plcSamples[i] = (lastDecodedFrame[i] * 0.4f).toInt().toShort()
                    }
                    if (!renderPcm(track, plcSamples)) break

                    // Render current arrived packet
                    val pcm = codec.decode(result.packet.payload)
                    if (!renderPcm(track, pcm)) break
                }

                is JitterBuffer.PlayoutResult.Buffering,
                is JitterBuffer.PlayoutResult.WaitingForRetransmit -> {
                    // Small sleep while waiting for network jitter buffer
                    Thread.sleep(5)
                }

                is JitterBuffer.PlayoutResult.Empty -> {
                    // Idle silence
                    onPlayoutLevelUpdate(-80f, false)
                    Thread.sleep(10)
                }
            }
        }
    }

    /**
     * Writes one frame to the track. Returns false when the track died under us —
     * typically stop() releasing it mid-write from another thread — so the playout
     * loop exits instead of throwing into the coroutine scope.
     */
    private fun renderPcm(track: AudioTrack, pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return true

        // Update PLC cache
        if (pcm.size == lastDecodedFrame.size) {
            System.arraycopy(pcm, 0, lastDecodedFrame, 0, pcm.size)
        }

        if (isMuted) {
            onPlayoutLevelUpdate(-80f, false)
            return true
        }

        // Apply volume & measure output dB
        var sumSquares = 0.0
        val renderBuffer = ShortArray(pcm.size)
        val vol = volume
        for (i in pcm.indices) {
            val sample = (pcm[i] * vol).toInt().coerceIn(-32768, 32767).toShort()
            renderBuffer[i] = sample
            sumSquares += (sample.toDouble() * sample.toDouble())
        }

        val rms = sqrt(sumSquares / pcm.size)
        val normalizedRms = (rms / 32767.0).coerceIn(0.00001, 1.0)
        val db = (20.0 * log10(normalizedRms)).toFloat().coerceIn(-80f, 0f)

        try {
            track.write(renderBuffer, 0, renderBuffer.size)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioTrack write after release — stopping playout loop")
            return false
        }
        onPlayoutLevelUpdate(db, true)
        return true
    }

    fun stop() {
        isPlaying = false
        playoutJob?.cancel()
        playoutJob = null

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        }
        onPlayoutLevelUpdate(-80f, false)
        Log.i(TAG, "VoIP AudioPlayer stopped")
    }
}
