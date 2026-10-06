package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.qr.QrScannerScreen
import com.example.ui.components.AudioDspCard
import com.example.ui.components.CodecConfigCard
import com.example.ui.components.NetworkConfigCard
import com.example.ui.components.TelemetryCard
import com.example.ui.components.VuMeter
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.DiscordBlurple
import com.example.ui.theme.NeonEmerald

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpusVoiceScreen(
    viewModel: OpusVoiceViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (isGranted) {
            viewModel.startStreaming()
        }
    }

    var showInfoDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.userNotice) {
        uiState.userNotice?.let { notice ->
            snackbarHostState.showSnackbar(notice)
            viewModel.clearNotice()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(DiscordBlurple),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "OpusVoice Logo",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "OpusVoice",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "WebRTC Opus Low-Latency Streamer",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    // Dark Mode Toggle Button
                    IconButton(
                        onClick = { viewModel.toggleDarkMode() },
                        modifier = Modifier.testTag("dark_mode_toggle")
                    ) {
                        Icon(
                            imageVector = if (uiState.isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle Dark Mode",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Info Dialog Button
                    IconButton(
                        onClick = { showInfoDialog = true },
                        modifier = Modifier.testTag("info_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Protocol Information",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permission Banner (if not granted)
            if (!hasMicPermission) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Microphone Permission Required",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Required for low-latency voice capture and Opus encoding.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Grant")
                            }
                        }
                    }
                }
            }

            // Hero Main Stream Action Card
            item {
                HeroStreamCard(
                    isStreaming = uiState.isStreaming,
                    isTransmitting = uiState.isTransmitting,
                    targetHost = uiState.targetHost,
                    targetPort = uiState.targetPort,
                    onToggleStream = {
                        if (!hasMicPermission) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            viewModel.toggleStreaming()
                        }
                    }
                )
            }

            // VU Meter & Push-to-Talk Interactive Trigger
            item {
                VuMeter(
                    inputDb = uiState.inputDbLevel,
                    thresholdDb = uiState.vadThresholdDb,
                    isTransmitting = uiState.isTransmitting,
                    isMuted = uiState.isMuted,
                    transmissionMode = uiState.transmissionMode,
                    isPttPressed = uiState.isPttPressed,
                    outputDb = uiState.outputDbLevel,
                    isOutputPlaying = uiState.isOutputPlaying,
                    onPttPressChange = { pressed -> viewModel.setPttPressed(pressed) }
                )
            }

            // Real-Time Network Jitter Buffer Telemetry Card
            item {
                TelemetryCard(
                    jitterStats = uiState.jitterBufferStats,
                    networkTelemetry = uiState.networkTelemetry,
                    isStreaming = uiState.isStreaming
                )
            }

            // Network IP:Port Destination Card
            item {
                NetworkConfigCard(
                    targetHost = uiState.targetHost,
                    targetPort = uiState.targetPort,
                    localPort = uiState.localPort,
                    transportState = uiState.transportState,
                    errorMessage = uiState.transportErrorMessage,
                    isLoopbackMode = uiState.isLoopbackMode,
                    isMuted = uiState.isMuted,
                    isListening = uiState.isListening,
                    usePinhole = uiState.usePinhole,
                    pinholeTicket = uiState.pinholeTicket,
                    presets = viewModel.presets,
                    onHostChange = { viewModel.setTargetHost(it) },
                    onPortChange = { viewModel.setTargetPort(it) },
                    onUsePinholeToggle = { viewModel.setUsePinhole(it) },
                    onPinholeTicketChange = { viewModel.setPinholeTicket(it) },
                    onScanQr = { showQrScanner = true },
                    onPresetSelect = { viewModel.applyPreset(it) },
                    onLoopbackToggle = { viewModel.toggleLoopback() },
                    onMuteToggle = { viewModel.toggleMute() },
                    onListenToggle = { viewModel.toggleListen() }
                )
            }

            // Audio DSP Processing Controls Card (AEC, NS, AGC)
            item {
                AudioDspCard(
                    aecEnabled = uiState.aecEnabled,
                    nsEnabled = uiState.nsEnabled,
                    agcEnabled = uiState.agcEnabled,
                    isAecSupported = uiState.isAecSupported,
                    isNsSupported = uiState.isNsSupported,
                    isAgcSupported = uiState.isAgcSupported,
                    vadThresholdDb = uiState.vadThresholdDb,
                    micGain = uiState.micGain,
                    transmissionMode = uiState.transmissionMode,
                    onAecChange = { viewModel.setAec(it) },
                    onNsChange = { viewModel.setNs(it) },
                    onAgcChange = { viewModel.setAgc(it) },
                    onVadThresholdChange = { viewModel.setVadThreshold(it) },
                    onMicGainChange = { viewModel.setMicGain(it) },
                    onTransmissionModeChange = { viewModel.setTransmissionMode(it) }
                )
            }

            // Opus Codec & WebRTC Protocol Tuning Card
            item {
                CodecConfigCard(
                    bitrate = uiState.bitrate,
                    isHardwareOpusEncoder = uiState.isHardwareOpusEncoder,
                    rtpPayloadType = uiState.rtpPayloadType,
                    adaptiveJitterEnabled = uiState.adaptiveJitterEnabled,
                    manualBufferDepthMs = uiState.manualBufferDepthMs,
                    onBitrateChange = { viewModel.setBitrate(it) },
                    onPayloadTypeChange = { viewModel.setRtpPayloadType(it) },
                    onAdaptiveJitterChange = { viewModel.setAdaptiveJitter(it) },
                    onManualBufferDepthChange = { viewModel.setManualBufferDepthMs(it) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showQrScanner) {
        QrScannerScreen(
            hint = "Point the camera at a connection-string QR code",
            onPayload = { payload ->
                showQrScanner = false
                viewModel.handleQrPayload(payload)
            },
            onClose = { showQrScanner = false }
        )
    }

    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = {
                Text(
                    text = "About OpusVoice WebRTC",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "• WebRTC RTP Protocol (RFC 3550 & RFC 7587):\nAudio frames are formatted with standard 12-byte RTP headers containing sequence numbers, 48kHz timestamps, and SSRC identifiers.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Opus VoIP Compression:\nCaptures 20ms audio frames at 48,000 Hz fullband VoIP format with low latency.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Adaptive Jitter Buffer:\nImplements the RFC 3550 inter-arrival jitter equation D(i, j) = (R_j - R_i) - (S_j - S_i) to adapt playout delay and reorder out-of-sequence packets.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "• Audio DSP Clarity:\nAcoustic Echo Cancellation (AEC), Noise Suppression (NS), and Automatic Gain Control (AGC) ensure crystal-clear voice communication.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Got it")
                }
            }
        )
    }
}

@Composable
private fun HeroStreamCard(
    isStreaming: Boolean,
    isTransmitting: Boolean,
    targetHost: String,
    targetPort: Int,
    onToggleStream: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hero_stream_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isStreaming) {
                if (isTransmitting) NeonEmerald.copy(alpha = 0.12f) else DiscordBlurple.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isStreaming) "ACTIVE VOIP SESSION" else "READY TO CONNECT",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = if (isStreaming) NeonEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Target: $targetHost:$targetPort",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (isStreaming) NeonEmerald else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = onToggleStream,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("toggle_stream_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isStreaming) CrimsonError else DiscordBlurple
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isStreaming) "Stop Streaming" else "Start Streaming",
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isStreaming) "DISCONNECT / STOP STREAM" else "START STREAMING TO $targetHost",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    )
                }
            }
        }
    }
}
