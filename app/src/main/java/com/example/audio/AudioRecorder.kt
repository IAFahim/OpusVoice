package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.webrtc.RtpPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Random

enum class TransmissionMode {
    VOICE_ACTIVITY,
    PUSH_TO_TALK,
    CONTINUOUS
}

/**
 * High-performance VoIP Audio Recorder capturing 48kHz Mono 16-bit PCM frames (20ms)
 * using the industry-standard VOICE_COMMUNICATION audio source for hardware echo cancellation.
 */
class AudioRecorder(
    private val dspManager: AudioDspManager,
    // Mutable so the ViewModel can swap in a reconfigured codec (e.g. after a
    // bitrate change); the capture loop reads it fresh on every frame.
    var codec: OpusCodec,
    private val onRtpPacketReady: (RtpPacket) -> Unit,
    private val onAudioLevelUpdate: (db: Float, peak: Float, isTransmitting: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "AudioRecorder"
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Streaming state
    var isRecording: Boolean = false
        private set
    var isMuted: Boolean = false
    var transmissionMode: TransmissionMode = TransmissionMode.VOICE_ACTIVITY
    var isPttPressed: Boolean = false

    // WebRTC RTP session state
    private var sequenceNumber: Int = 0
    private var rtpTimestamp: Long = 0
    private val ssrc: Long = Random().nextInt().toLong() and 0xFFFFFFFFL
    var rtpPayloadType: Int = AudioConfig.DEFAULT_RTP_PAYLOAD_TYPE_WEBRTC

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording) return true

        val minBufferSize = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (AudioConfig.FRAME_BYTES * 4).coerceAtLeast(minBufferSize)

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                AudioConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                record.release()
                return false
            }

            // Bind hardware AEC, NS, AGC to the audio session ID
            dspManager.attachToAudioSession(record.audioSessionId)

            record.startRecording()
            audioRecord = record
            isRecording = true
            sequenceNumber = (0..65535).random()
            rtpTimestamp = 0

            recordingJob = scope.launch {
                runCaptureLoop(record)
            }
            Log.i(TAG, "VoIP Audio recording started successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord", e)
            return false
        }
    }

    private fun runCaptureLoop(record: AudioRecord) {
        val sampleBuffer = ShortArray(AudioConfig.SAMPLES_PER_FRAME)

        while (scope.isActive && isRecording) {
            var readSamples = 0
            while (readSamples < AudioConfig.SAMPLES_PER_FRAME && scope.isActive && isRecording) {
                val read = record.read(
                    sampleBuffer,
                    readSamples,
                    AudioConfig.SAMPLES_PER_FRAME - readSamples
                )
                if (read > 0) {
                    readSamples += read
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord read error: $read")
                    break
                }
            }

            if (readSamples != AudioConfig.SAMPLES_PER_FRAME) continue

            // 1. Process DSP effects (AEC, NS, software AGC, RMS/dB, VAD)
            val dspResult = dspManager.processPcmFrame(sampleBuffer, readSamples)

            // 2. Determine if audio should be transmitted
            val shouldTransmit = !isMuted && when (transmissionMode) {
                TransmissionMode.CONTINUOUS -> true
                TransmissionMode.VOICE_ACTIVITY -> dspResult.isVoiceActive
                TransmissionMode.PUSH_TO_TALK -> isPttPressed
            }

            // Report visual telemetry
            onAudioLevelUpdate(dspResult.dbLevel, dspResult.peakLevel, shouldTransmit)

            if (shouldTransmit) {
                // 3. Compress / Encode to Opus
                val encodedBytes = codec.encode(sampleBuffer, readSamples)

                if (encodedBytes.isNotEmpty()) {
                    // 4. Wrap into RFC 3550 RTP Packet
                    val packet = RtpPacket(
                        version = 2,
                        padding = false,
                        hasExtension = false,
                        marker = (sequenceNumber == 0 || dspResult.isVoiceActive),
                        payloadType = rtpPayloadType,
                        sequenceNumber = sequenceNumber,
                        timestamp = rtpTimestamp,
                        ssrc = ssrc,
                        payload = encodedBytes
                    )

                    onRtpPacketReady(packet)

                    // Increment RTP counters
                    sequenceNumber = (sequenceNumber + 1) and 0xFFFF
                    rtpTimestamp = (rtpTimestamp + AudioConfig.SAMPLES_PER_FRAME) and 0xFFFFFFFFL
                }
            }
        }
    }

    fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }

        dspManager.release()
        onAudioLevelUpdate(-80f, 0f, false)
        Log.i(TAG, "VoIP Audio recording stopped")
    }
}
