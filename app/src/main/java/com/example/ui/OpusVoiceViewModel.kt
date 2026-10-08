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
import com.example.qr.QrPayload
import com.example.webrtc.JitterBuffer
import com.example.webrtc.JitterBufferStats
import com.example.webrtc.NetworkTelemetry
import com.example.webrtc.RtpPacket
import com.example.webrtc.TransportState
import com.example.webrtc.UdpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import pinhole.PinholeDialer
import com.example.network.pinholeRouterMapping

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

    // Pinhole transport (NAT traversal + E2E encryption via a connection string)
    val usePinhole: Boolean = false,
    val pinholeTicket: String = "",
    val isPinholeConnected: Boolean = false,

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
        },
        onBoundPort = { requested, bound ->
            if (bound != requested) {
                _uiState.update {
                    it.copy(
                        localPort = bound,
                        userNotice = "Port $requested was busy — receiving on $bound instead"
                    )
                }
            } else {
                _uiState.update { it.copy(localPort = bound) }
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
    @Volatile private var pinholeDialer: PinholeDialer? = null
    private var pinholeConnectJob: Job? = null

    // One-shot diagnostics for silent-failure paths the user would otherwise never see.
    private var gatedIncomingNotified = false
    private var pinholeOversizeNotified = false
    // AEC state saved while loopback mutes it, restored when loopback ends.
    private var aecBeforeLoopback: Boolean? = null

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
                // Pinhole maintains its NAT mapping on its own network worker.
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

    /** Starts the playout player, surfacing an AudioTrack failure instead of dropping it —
     *  a speaker that never started is indistinguishable from the playback bug otherwise. */
    private fun startPlayerOrNotice() {
        if (!audioPlayer.start()) {
            _uiState.update {
                it.copy(userNotice = "Speaker unavailable — AudioTrack failed to start; playback is off")
            }
        }
    }

    private fun handleOutgoingRtpPacket(packet: RtpPacket) {
        if (_uiState.value.isLoopbackMode) {
            // Self-test: feed packet directly through jitter buffer
            jitterBuffer.push(packet)
        }
        val dialer = pinholeDialer
        if (dialer != null && dialer.isConnected) {
            // Same RTP bytes, tunneled through the encrypted Pinhole session. The session
            // caps a datagram at 1200 bytes — a 1287-byte worst-case Opus frame + RTP
            // header would be rejected, so drop it here and say so instead of losing TX
            // silently inside the dialer.
            val bytes = packet.toByteArray()
            if (bytes.size > 1200) {
                if (!pinholeOversizeNotified) {
                    pinholeOversizeNotified = true
                    _uiState.update {
                        it.copy(userNotice = "Frame too large for Pinhole (${bytes.size} B > 1200) — lower the bitrate")
                    }
                }
                return
            }
            try {
                dialer.send(bytes)
            } catch (_: Exception) {
            }
            return
        }
        // Send to network target via UDP
        udpTransport.sendPacket(packet)
    }

    private fun handleIncomingRtpPacket(packet: RtpPacket) {
        if (_uiState.value.isListening && !_uiState.value.isLoopbackMode) {
            jitterBuffer.push(packet)
        } else if (!gatedIncomingNotified) {
            // Audio is arriving but the playout gate drops it: tell the user once,
            // otherwise "receiving but hearing nothing" looks like a playback bug.
            gatedIncomingNotified = true
            val loopback = _uiState.value.isLoopbackMode
            _uiState.update {
                it.copy(userNotice = if (loopback) {
                    "Incoming audio is muted while Loopback is on (loopback feeds your own mic instead)"
                } else {
                    "Incoming audio is arriving — enable Listening to hear it"
                })
            }
        }
    }

    fun startStreaming() {
        val state = _uiState.value

        if (state.usePinhole) {
            startPinholeStreaming(state.pinholeTicket)
            return
        }

        val host = state.targetHost
        val port = state.targetPort

        // Fresh one-shot diagnostics for this session.
        gatedIncomingNotified = false
        pinholeOversizeNotified = false

        // 1. Start UDP Transport
        udpTransport.start(host, port, state.localPort)

        // 2. Fresh jitter buffer for every session — restarts mint new sequence numbers,
        //    and stale playout state would classify every loopback/incoming packet as
        //    "late" and drop it (received counts climb, speaker stays silent).
        jitterBuffer.reset()
        if (state.isListening) {
            startPlayerOrNotice()
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

    private fun startPinholeStreaming(ticket: String) {
        if (pinholeConnectJob?.isActive == true || pinholeDialer?.isConnected == true) return
        if (ticket.isBlank()) {
            _uiState.update {
                it.copy(isStreaming = false, userNotice = "Paste a Pinhole ticket or iroh endpoint ID/ticket first")
            }
            return
        }
        _uiState.update {
            it.copy(transportState = TransportState.CONNECTING, transportErrorMessage = null,
                userNotice = "Resolving peer and connecting…")
        }
        pinholeConnectJob = viewModelScope.launch(Dispatchers.IO) {
            var dialer: PinholeDialer? = null
            try {
                val connecting = PinholeDialer(ticket.trim(), portMapping = pinholeRouterMapping(getApplication()))
                connecting.debug = true
                dialer = connecting
                connecting.onReceived = { bytes ->
                    if (pinholeDialer === connecting) {
                        RtpPacket.parse(bytes)?.let { handleIncomingRtpPacket(it) }
                    }
                }
                connecting.onConnected = {
                    if (pinholeDialer === connecting) {
                        _uiState.update {
                            if (pinholeDialer !== connecting || !connecting.isConnected) it
                            else it.copy(isPinholeConnected = true, transportState = TransportState.CONNECTED,
                                userNotice = "Pinhole session established (encrypted)")
                        }
                    }
                }
                connecting.onClosed = { reason ->
                    viewModelScope.launch(Dispatchers.Main) {
                        if (pinholeDialer === connecting) {
                            pinholeDialer = null
                            audioRecorder.stopRecording()
                            audioPlayer.stop()
                            _uiState.update {
                                it.copy(isStreaming = false, isPinholeConnected = false,
                                    transportState = if (reason == "closed") TransportState.DISCONNECTED else TransportState.ERROR,
                                    transportErrorMessage = if (reason == "closed") null else reason)
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) { pinholeDialer = connecting }
                connecting.connect()
                withContext(Dispatchers.Main) {
                    if (pinholeDialer !== connecting) return@withContext
                    // Fresh jitter buffer per session (restarts mint new sequence numbers).
                    jitterBuffer.reset()
                    if (_uiState.value.isListening) {
                        startPlayerOrNotice()
                    }
                    val started = audioRecorder.startRecording()
                    if (!started) {
                        audioPlayer.stop()
                        connecting.close()
                        pinholeDialer = null
                    }
                    _uiState.update {
                        it.copy(isStreaming = started, isPinholeConnected = started,
                            transportState = if (started) TransportState.CONNECTED else TransportState.ERROR,
                            userNotice = if (started) "Streaming via Pinhole" else "AudioRecord failed to start. Check mic permissions.")
                    }
                }
            } catch (e: Exception) {
                dialer?.close()
                if (e is CancellationException) throw e
                if (pinholeDialer === dialer) {
                    pinholeDialer = null
                    _uiState.update {
                        it.copy(isStreaming = false, isPinholeConnected = false, transportState = TransportState.ERROR,
                            transportErrorMessage = "Pinhole: " + e.message)
                    }
                }
            }
        }
    }

    fun stopStreaming() {
        pinholeConnectJob?.cancel()
        pinholeConnectJob = null
        audioRecorder.stopRecording()
        audioPlayer.stop()
        pinholeDialer?.close()
        pinholeDialer = null
        udpTransport.stop()
        jitterBuffer.reset()

        val wasLoopback = _uiState.value.isLoopbackMode
        _uiState.update {
            it.copy(
                isStreaming = false,
                isTransmitting = false,
                // Loopback is a self-test, not a sticky preference: leaving it armed
                // after stop means the next Start immediately feeds back through the
                // speaker. Disarm it so a fresh session is always a normal stream.
                isLoopbackMode = false,
                isPinholeConnected = false,
                transportState = TransportState.DISCONNECTED,
                transportErrorMessage = null,
                inputDbLevel = -80f,
                outputDbLevel = -80f,
                userNotice = if (wasLoopback) "Stream stopped — loopback disarmed" else "Stream stopped"
            )
        }

        // After isStreaming flips false, or the profile swap restarts the mic
        // and leaves it recording while the app is idle.
        restoreNetworkVoiceProfile()
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
            startPlayerOrNotice()
        } else {
            audioPlayer.stop()
        }
        _uiState.update { it.copy(isListening = newListening) }
    }

    fun toggleLoopback() {
        val newLoopback = !_uiState.value.isLoopbackMode
        if (newLoopback) {
            startPlayerOrNotice()
            applyLoopbackMonitorProfile()
        } else {
            restoreNetworkVoiceProfile()
        }
        _uiState.update {
            it.copy(
                isLoopbackMode = newLoopback,
                userNotice = when {
                    !newLoopback -> "Loopback disabled"
                    !_uiState.value.isStreaming -> "Loopback armed — start streaming to hear yourself"
                    else -> "Loopback active (monitor mic, AEC parked — wear headphones to avoid feedback howl)"
                }
            )
        }
    }

    /**
     * Loopback needs an honest monitor path: park the hardware AEC (it cancels exactly the
     * played-back voice you want to hear) and record from the plain MIC source — the
     * VOICE_COMMUNICATION chain on many OEMs carries source-level echo suppression that
     * mutes the mic outright while the app is playing the audio back. Applies immediately
     * by restarting a live recorder.
     */
    private fun applyLoopbackMonitorProfile() {
        if (_uiState.value.aecEnabled) {
            aecBeforeLoopback = true
            dspManager.setAec(false)
            _uiState.update { it.copy(aecEnabled = false) }
        }
        audioRecorder.audioSource = android.media.MediaRecorder.AudioSource.MIC
        restartRecorderIfStreaming()
    }

    private fun restoreNetworkVoiceProfile() {
        val saved = aecBeforeLoopback
        if (saved != null) {
            aecBeforeLoopback = null
            dspManager.setAec(saved)
            _uiState.update { it.copy(aecEnabled = saved) }
        }
        audioRecorder.audioSource = android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
        restartRecorderIfStreaming()
    }

    private fun restartRecorderIfStreaming() {
        if (!_uiState.value.isStreaming) return
        audioRecorder.stopRecording()
        if (!audioRecorder.startRecording()) {
            stopStreaming()
            _uiState.update { it.copy(userNotice = "Microphone could not restart for the new profile.") }
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
        // Re-wire the pipeline to a fresh codec FIRST, release the old one LAST: the
        // recorder and player loops decode/encode on IO threads and must never observe
        // a released codec — a release-before-swap window crashes every subsequent
        // MediaCodec call and the playout goes permanently silent.
        val old = opusCodec
        opusCodec = OpusCodec(bitrate = bitrate)
        audioRecorder.codec = opusCodec
        audioPlayer.codec = opusCodec
        old.release()
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

    fun setUsePinhole(enabled: Boolean) {
        if (enabled != _uiState.value.usePinhole) stopStreaming()
        _uiState.update { it.copy(usePinhole = enabled) }
    }

    fun setPinholeTicket(ticket: String) {
        _uiState.update { it.copy(pinholeTicket = ticket) }
    }

    /** Applies a scanned QR code: fills the fields the payload addresses, nothing else. */
    fun handleQrPayload(payload: QrPayload) {
        _uiState.update {
            when (payload) {
                is QrPayload.PinholeTicket -> it.copy(
                    usePinhole = true,
                    pinholeTicket = payload.ticket,
                    userNotice = "Pinhole ticket scanned — press Start to connect"
                )

                is QrPayload.IrohEndpoint -> it.copy(
                    usePinhole = true,
                    pinholeTicket = payload.ticket,
                    userNotice = "Iroh endpoint selected; its session key will be verified on connect"
                )

                is QrPayload.UdpEndpoint -> it.copy(
                    usePinhole = false,
                    targetHost = payload.host,
                    targetPort = payload.port,
                    userNotice = "UDP target scanned: ${payload.host}:${payload.port}"
                )

                is QrPayload.Unknown -> it.copy(
                    userNotice = "QR code not recognized: showing it in the connection field"
                )
            }
        }
        if (payload is QrPayload.Unknown) {
            setPinholeTicket(payload.text.take(200))
        }
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
        pinholeConnectJob?.cancel()
        audioRecorder.stopRecording()
        audioPlayer.stop()
        udpTransport.stop()
        pinholeDialer?.close()
        dspManager.release()
        opusCodec.release()
    }
}
