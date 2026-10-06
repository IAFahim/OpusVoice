package com.example.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Manages device audio DSP pre-processing:
 * - Acoustic Echo Cancellation (AEC)
 * - Noise Suppression (NS)
 * - Automatic Gain Control (AGC)
 *
 * Provides real-time audio statistics (RMS, dBFS, peak) and software fallbacks
 * (voice gate / activity detection with hangover timer, soft-knee peak compressor)
 * so voice clarity is guaranteed regardless of hardware capabilities.
 */
class AudioDspManager {

    companion object {
        private const val TAG = "AudioDspManager"
        const val MIN_DB = -80f
        const val MAX_DB = 0f
    }

    // Hardware Audio Effects
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var autoGainControl: AutomaticGainControl? = null

    // Effect states
    var aecEnabled: Boolean = true
        private set
    var nsEnabled: Boolean = true
        private set
    var agcEnabled: Boolean = true
        private set

    // Hardware availability flags
    val isAecSupported: Boolean = try { AcousticEchoCanceler.isAvailable() } catch (_: Throwable) { false }
    val isNsSupported: Boolean = try { NoiseSuppressor.isAvailable() } catch (_: Throwable) { false }
    val isAgcSupported: Boolean = try { AutomaticGainControl.isAvailable() } catch (_: Throwable) { false }

    // VAD & Voice gating state
    var vadThresholdDb: Float = -45f // Configurable sensitivity threshold
    var micGain: Float = 1.0f // 0.5x to 3.0x software boost
    private var hangoverFramesRemaining: Int = 0
    private val hangoverFramesMax = 10 // ~200ms at 20ms/frame

    /**
     * Binds hardware audio effects to the AudioRecord session.
     */
    fun attachToAudioSession(audioSessionId: Int) {
        release()

        // 1. Acoustic Echo Canceler
        if (isAecSupported) {
            try {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = aecEnabled
                }
                Log.i(TAG, "AcousticEchoCanceler attached (enabled: $aecEnabled)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AcousticEchoCanceler", e)
            }
        }

        // 2. Noise Suppressor
        if (isNsSupported) {
            try {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = nsEnabled
                }
                Log.i(TAG, "NoiseSuppressor attached (enabled: $nsEnabled)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize NoiseSuppressor", e)
            }
        }

        // 3. Automatic Gain Control
        if (isAgcSupported) {
            try {
                autoGainControl = AutomaticGainControl.create(audioSessionId)?.apply {
                    enabled = agcEnabled
                }
                Log.i(TAG, "AutomaticGainControl attached (enabled: $agcEnabled)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize AutomaticGainControl", e)
            }
        }
    }

    fun setAec(enabled: Boolean) {
        aecEnabled = enabled
        echoCanceler?.enabled = enabled
    }

    fun setNs(enabled: Boolean) {
        nsEnabled = enabled
        noiseSuppressor?.enabled = enabled
    }

    fun setAgc(enabled: Boolean) {
        agcEnabled = enabled
        autoGainControl?.enabled = enabled
    }

    /**
     * Processes 16-bit PCM samples in-place.
     * Applies software AGC, soft limiter, computes signal energy (RMS, dBFS),
     * and evaluates Voice Activity Detection (VAD).
     *
     * @return DspProcessResult containing measured dB, voice activity status, and peak level.
     */
    fun processPcmFrame(samples: ShortArray, length: Int): DspProcessResult {
        if (length <= 0) {
            return DspProcessResult(
                dbLevel = MIN_DB,
                isVoiceActive = false,
                peakLevel = 0f
            )
        }

        var sumSquares = 0.0
        var maxSample = 0

        // 1. Apply Gain & Measure energy
        val gain = micGain
        for (i in 0 until length) {
            var sample = samples[i].toInt()

            // Software Gain
            if (gain != 1.0f) {
                sample = (sample * gain).toInt().coerceIn(-32768, 32767)
            }

            // Software AGC soft-knee limiter if hardware AGC not supported or soft enhancement is active
            if (agcEnabled && !isAgcSupported) {
                // Gentle compression near peaks
                if (abs(sample) > 28000) {
                    val excess = abs(sample) - 28000
                    val compressedExcess = (excess * 0.4).toInt()
                    sample = if (sample > 0) 28000 + compressedExcess else -28000 - compressedExcess
                }
            }

            samples[i] = sample.toShort()
            val absVal = abs(sample)
            if (absVal > maxSample) {
                maxSample = absVal
            }
            sumSquares += (sample.toDouble() * sample.toDouble())
        }

        val rms = sqrt(sumSquares / length)
        val normalizedRms = (rms / 32767.0).coerceIn(0.00001, 1.0)
        val db = (20.0 * log10(normalizedRms)).toFloat().coerceIn(MIN_DB, MAX_DB)
        val peak = (maxSample / 32767.0f).coerceIn(0f, 1f)

        // 2. Voice Activity Detection (VAD) with Hangover Hold
        val rawActive = db >= vadThresholdDb
        val isVoiceActive: Boolean

        if (rawActive) {
            hangoverFramesRemaining = hangoverFramesMax
            isVoiceActive = true
        } else if (hangoverFramesRemaining > 0) {
            hangoverFramesRemaining--
            isVoiceActive = true
        } else {
            isVoiceActive = false
        }

        // 3. Software Noise Gate attenuation when gate is closed
        if (nsEnabled && !rawActive && hangoverFramesRemaining == 0) {
            // Smoothly zero out background noise when inactive
            for (i in 0 until length) {
                samples[i] = 0
            }
        }

        return DspProcessResult(
            dbLevel = db,
            isVoiceActive = isVoiceActive,
            peakLevel = peak
        )
    }

    fun release() {
        try {
            echoCanceler?.release()
            echoCanceler = null
            noiseSuppressor?.release()
            noiseSuppressor = null
            autoGainControl?.release()
            autoGainControl = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio effects", e)
        }
    }
}

data class DspProcessResult(
    val dbLevel: Float,
    val isVoiceActive: Boolean,
    val peakLevel: Float
)
