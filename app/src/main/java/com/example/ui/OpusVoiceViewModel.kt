package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioConfig
import com.example.audio.AudioDspManager
import com.example.audio.AudioPlayer
import com.example.audio.AudioRecorder
import com.example.audio.OpusCodec
import com.example.audio.TransmissionMode
import com.example.webrtc.JitterBuffer
import com.example.webrtc.JitterBufferStats
import com.example.webrtc.NetworkTelemetry
import com.example.webrtc.RtpPacket
import com.example.webrtc.TransportState
import com.example.webrtc.UdpTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ConnectionPreset(
    val title: String,
    val host: String,
    val port: Int,
    val description: String
)

data class OpusVoiceUiState(
    // Theme
    val isDarkMode: Boolean = true,

    // Streaming & Audio States
    val isStreaming: Boolean = false,
    val isListening: Boolean = true,
    val isMuted: Boolean = false,
    val isLoopbackMode: Boolean = false,
    val transmissionMode: TransmissionMode = TransmissionMode.VOICE_ACTIVITY,
    val isPttPressed: Boolean = false,

    // Realtime Audio Levels
    val inputDbLevel: Float = -80f,
    val inputPeakLevel: Float = 0f,
    val isTransmitting: Boolean = false,
    val outputDbLevel: Float = -80f,
    val isOutputPlaying: Boolean = false,

    // DSP Pre-Processing Settings
    val aecEnabled: Boolean = true,
    val nsEnabled: Boolean = true,
    val agcEnabled: Boolean = true,
    val vadThresholdDb: Float = -45f,
    val micGain: Float = 1.0f,
    val isAecSupported: Boolean = false,
    val isNsSupported: Boolean = false,
    val isAgcSupported: Boolean = false,

    // Codec & WebRTC
    val bitrate: Int = AudioConfig.DEFAULT_BITRATE,
    val rtpPayloadType: Int = AudioConfig.DEFAULT_RTP_PAYLOAD_TYPE_WEBRTC,
    val isHardwareOpusEncoder: Boolean = false,

    // Jitter Buffer & Playout
    val adaptiveJitterEnabled: Boolean = true,
    val manualBufferDepthMs: Int = 60,
    val jitterBufferStats: JitterBufferStats = JitterBufferStats(0, 40, 0.0, 0, 0, 0, 0.0),

    // Network & Target
    val targetHost: String = "127.0.0.1",
    val targetPort: Int = AudioConfig.DEFAULT_TARGET_PORT,
    val localPort: Int = AudioConfig.DEFAULT_LOCAL_RECEIVE_PORT,
    val transportState: TransportState = TransportState.DISCONNECTED,
    val transportErrorMessage: String? = null,
    val networkTelemetry: NetworkTelemetry = NetworkTelemetry(),

    // User Message / Snackbar
    val userNotice: String? = null
)

class OpusVoiceViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(OpusVoiceUiState())
    val uiState: StateFlow<OpusVoiceUiState> = _uiState.asStateFlow()

    // Core Audio & Network Pipeline
    private val dspManager = AudioDspManager()
    private val jitterBuffer = JitterBuffer()
    private var opusCodec: OpusCodec = OpusCodec(bitrate = AudioConfig.DEFAULT_BITRATE)

    private val udpTransport = UdpTransport(
        onPacketReceived = { packet ->
            handleIncomingRtpPacket(packet)
        },
        onStateChanged = { state, errorMsg ->
            _uiState.update {
                it.copy(
                    transportState = state,
                    transportErrorMessage = errorMsg
                )
            }
        }
    )

    private val audioRecorder = AudioRecorder(
        dspManager = dspManager,
        codec = opusCodec,
        onRtpPacketReady = { packet ->
            handleOutgoingRtpPacket(packet)
        },
        onAudioLevelUpdate = { db, peak, isTransmitting ->
            _uiState.update {
                it.copy(
                    inputDbLevel = db,
                    inputPeakLevel = peak,
                    isTransmitting = isTransmitting
                )
            }
        }
    )

    private val audioPlayer = AudioPlayer(
        jitterBuffer = jitterBuffer,
        codec = opusCodec,
        onPlayoutLevelUpdate = { db, isPlaying ->
            _uiState.update {
                it.copy(
                    outputDbLevel = db,
                    isOutputPlaying = isPlaying
                )
            }
        }
    )

    private var telemetryJob: Job? = null

    val presets = listOf(
        ConnectionPreset("Local Loopback", "127.0.0.1", 5004, "Test mic, Opus encoding & Jitter Buffer locally"),
        ConnectionPreset("LAN Peer", "192.168.1.100", 5004, "Send to local network device or PC"),
        ConnectionPreset("Discord Voice Bridge", "127.0.0.1", 50000, "Discord bot / WebRTC Gateway relay"),
        ConnectionPreset("VoIP Server", "10.0.0.1", 5004, "Custom remote IP:Port streaming")
    )

    init {
        // Query hardware DSP capabilities
        _uiState.update {
            it.copy(
                isAecSupported = dspManager.isAecSupported,
                isNsSupported = dspManager.isNsSupported,
                isAgcSupported = dspManager.isAgcSupported,
                isHardwareOpusEncoder = opusCodec.isUsingHardwareOpusEncoder
            )
        }

        startTelemetryPolling()
    }

    private fun startTelemetryPolling() {
        telemetryJob = viewModelScope.launch {
            while (isActive) {
                delay(120)
                val netTelemetry = udpTransport.getTelemetry()
                val jbStats = jitterBuffer.getSnapshot()
                _uiState.update {
                    it.copy(
                        networkTelemetry = netTelemetry,
                        jitterBufferStats = jbStats
                    )
                }
            }
        }
    }

    private fun handleOutgoingRtpPacket(packet: RtpPacket) {
        if (_uiState.value.isLoopbackMode) {
            // Self-test: feed packet directly through jitter buffer
            jitterBuffer.push(packet)
        }
        // Send to network target via UDP
        udpTransport.sendPacket(packet)
    }

    private fun handleIncomingRtpPacket(packet: RtpPacket) {
        if (_uiState.value.isListening && !_uiState.value.isLoopbackMode) {
            jitterBuffer.push(packet)
        }
    }

    fun startStreaming() {
        val state = _uiState.value
        val host = state.targetHost
        val port = state.targetPort

        // 1. Start UDP Transport
        udpTransport.start(host, port, state.localPort)

        // 2. Start Jitter Buffer and Player if listening is enabled
        if (state.isListening) {
            jitterBuffer.reset()
            audioPlayer.start()
        }

        // 3. Start Audio Recorder
        val started = audioRecorder.startRecording()
        if (started) {
            _uiState.update {
                it.copy(
                    isStreaming = true,
                    userNotice = "Streaming to $host:$port"
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    isStreaming = false,
                    userNotice = "AudioRecord failed to start. Check mic permissions."
                )
            }
        }
    }

    fun stopStreaming() {
        audioRecorder.stopRecording()
        audioPlayer.stop()
        udpTransport.stop()
        jitterBuffer.reset()

        _uiState.update {
            it.copy(
                isStreaming = false,
                isTransmitting = false,
                inputDbLevel = -80f,
                outputDbLevel = -80f,
                userNotice = "Stream stopped"
            )
        }
    }

    fun toggleStreaming() {
        if (_uiState.value.isStreaming) {
            stopStreaming()
        } else {
            startStreaming()
        }
    }

    fun setPttPressed(pressed: Boolean) {
        audioRecorder.isPttPressed = pressed
        _uiState.update { it.copy(isPttPressed = pressed) }
    }

    fun setTransmissionMode(mode: TransmissionMode) {
        audioRecorder.transmissionMode = mode
        _uiState.update { it.copy(transmissionMode = mode) }
    }

    fun toggleMute() {
        val newMuted = !_uiState.value.isMuted
        audioRecorder.isMuted = newMuted
        _uiState.update { it.copy(isMuted = newMuted) }
    }

    fun toggleListen() {
        val newListening = !_uiState.value.isListening
        if (newListening) {
            audioPlayer.start()
        } else {
            audioPlayer.stop()
        }
        _uiState.update { it.copy(isListening = newListening) }
    }

    fun toggleLoopback() {
        val newLoopback = !_uiState.value.isLoopbackMode
        if (newLoopback) {
            audioPlayer.start()
        }
        _uiState.update {
            it.copy(
                isLoopbackMode = newLoopback,
                userNotice = if (newLoopback) "Loopback active (hearing self via Opus & Jitter Buffer)" else "Loopback disabled"
            )
        }
    }

    fun toggleDarkMode() {
        _uiState.update { it.copy(isDarkMode = !it.isDarkMode) }
    }

    fun setAec(enabled: Boolean) {
        dspManager.setAec(enabled)
        _uiState.update { it.copy(aecEnabled = enabled) }
    }

    fun setNs(enabled: Boolean) {
        dspManager.setNs(enabled)
        _uiState.update { it.copy(nsEnabled = enabled) }
    }

    fun setAgc(enabled: Boolean) {
        dspManager.setAgc(enabled)
        _uiState.update { it.copy(agcEnabled = enabled) }
    }

    fun setVadThreshold(thresholdDb: Float) {
        dspManager.vadThresholdDb = thresholdDb
        _uiState.update { it.copy(vadThresholdDb = thresholdDb) }
    }

    fun setMicGain(gain: Float) {
        dspManager.micGain = gain
        _uiState.update { it.copy(micGain = gain) }
    }

    fun setBitrate(bitrate: Int) {
        _uiState.update { it.copy(bitrate = bitrate) }
        // Recreate codec with new bitrate and re-wire the pipeline; recorder
        // and player hold their own codec reference and must be updated too.
        opusCodec.release()
        opusCodec = OpusCodec(bitrate = bitrate)
        audioRecorder.codec = opusCodec
        audioPlayer.codec = opusCodec
        _uiState.update { it.copy(isHardwareOpusEncoder = opusCodec.isUsingHardwareOpusEncoder) }
    }

    fun setRtpPayloadType(pt: Int) {
        audioRecorder.rtpPayloadType = pt
        _uiState.update { it.copy(rtpPayloadType = pt) }
    }

    fun setAdaptiveJitter(enabled: Boolean) {
        jitterBuffer.adaptiveEnabled = enabled
        _uiState.update { it.copy(adaptiveJitterEnabled = enabled) }
    }

    fun setManualBufferDepthMs(depthMs: Int) {
        jitterBuffer.manualBufferDepthMs = depthMs
        _uiState.update { it.copy(manualBufferDepthMs = depthMs) }
    }

    fun setTargetHost(host: String) {
        _uiState.update { it.copy(targetHost = host) }
    }

    fun setTargetPort(port: Int) {
        _uiState.update { it.copy(targetPort = port) }
    }

    fun applyPreset(preset: ConnectionPreset) {
        _uiState.update {
            it.copy(
                targetHost = preset.host,
                targetPort = preset.port,
                userNotice = "Loaded preset: ${preset.title}"
            )
        }
        if (_uiState.value.isStreaming) {
            udpTransport.targetHost = preset.host
            udpTransport.targetPort = preset.port
        }
    }

    fun clearNotice() {
        _uiState.update { it.copy(userNotice = null) }
    }

    override fun onCleared() {
        super.onCleared()
        telemetryJob?.cancel()
        audioRecorder.stopRecording()
        audioPlayer.stop()
        udpTransport.stop()
        dspManager.release()
        opusCodec.release()
    }
}
