package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.ConnectionPreset
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.DiscordBlurple
import com.example.ui.theme.NeonEmerald
import com.example.webrtc.TransportState

@Composable
fun NetworkConfigCard(
    targetHost: String,
    targetPort: Int,
    localPort: Int,
    transportState: TransportState,
    errorMessage: String?,
    isLoopbackMode: Boolean,
    isMuted: Boolean,
    isListening: Boolean,
    usePinhole: Boolean,
    pinholeTicket: String,
    presets: List<ConnectionPreset>,
    onHostChange: (String) -> Unit,
    onPortChange: (Int) -> Unit,
    onUsePinholeToggle: (Boolean) -> Unit,
    onPinholeTicketChange: (String) -> Unit,
    onScanQr: () -> Unit,
    onPresetSelect: (ConnectionPreset) -> Unit,
    onLoopbackToggle: () -> Unit,
    onMuteToggle: () -> Unit,
    onListenToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (statusLabel, statusColor) = when (transportState) {
        TransportState.CONNECTED -> (if (usePinhole) "Active (Pinhole)" else "Active (UDP)") to NeonEmerald
        TransportState.CONNECTING -> "Connecting..." to AmberWarning
        TransportState.DISCONNECTED -> "Idle" to MaterialTheme.colorScheme.onSurfaceVariant
        TransportState.ERROR -> "Error" to CrimsonError
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("network_config_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            // Header with Connection Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Router,
                        contentDescription = "Network Target",
                        tint = DiscordBlurple,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "RTP Destination (IP : Port)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Transport status chip
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(statusColor.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = statusColor
                        )
                    }
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = CrimsonError
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Transport mode: plain UDP to IP:Port, or a Pinhole connection string
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !usePinhole,
                    onClick = { onUsePinholeToggle(false) },
                    label = { Text("UDP IP : Port", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DiscordBlurple,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                FilterChip(
                    selected = usePinhole,
                    onClick = { onUsePinholeToggle(true) },
                    label = {
                        Text(
                            text = "Pinhole / iroh",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("pinhole_chip")
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = NeonEmerald,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }

            if (usePinhole) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = pinholeTicket,
                    onValueChange = onPinholeTicketChange,
                    label = { Text("Pinhole ticket or iroh endpoint ID/ticket") },
                    singleLine = false,
                    maxLines = 3,
                    trailingIcon = {
                        IconButton(
                            onClick = onScanQr,
                            modifier = Modifier.testTag("pinhole_scan_qr_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "Scan connection QR code",
                                tint = NeonEmerald,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pinhole_ticket_input"),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonEmerald,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
                Text(
                    text = "Paste or scan the receiver's Pinhole ticket or published iroh endpoint",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!usePinhole) {
                Spacer(modifier = Modifier.height(14.dp))

                // IP & Port Inputs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                OutlinedTextField(
                    value = targetHost,
                    onValueChange = onHostChange,
                    label = { Text("Target IP / Host") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(
                            onClick = onScanQr,
                            modifier = Modifier.testTag("udp_scan_qr_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = "Scan endpoint QR code",
                                tint = DiscordBlurple,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    modifier = Modifier
                        .weight(1.8f)
                        .testTag("target_ip_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DiscordBlurple,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )

                OutlinedTextField(
                    value = targetPort.toString(),
                    onValueChange = { str ->
                        str.toIntOrNull()?.let { onPortChange(it) }
                    },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("target_port_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DiscordBlurple,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Presets
            Text(
                text = "QUICK PRESETS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.take(3).forEach { preset ->
                    val isSelected = targetHost == preset.host && targetPort == preset.port
                    FilterChip(
                        selected = isSelected,
                        onClick = { onPresetSelect(preset) },
                        label = {
                            Text(
                                text = preset.title,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = DiscordBlurple,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            }

            Spacer(modifier = Modifier.height(14.dp))

            // Self-Test Loopback Option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Repeat,
                        contentDescription = "Loopback Mode",
                        tint = if (isLoopbackMode) NeonEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Local Loopback Audio Test",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Play back captured audio through Opus & Jitter Buffer",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = isLoopbackMode,
                    onCheckedChange = { onLoopbackToggle() },
                    modifier = Modifier.testTag("loopback_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = NeonEmerald
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Mic Mute & Speaker Listen Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onMuteToggle,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("mute_mic_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isMuted) CrimsonError.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                        contentColor = if (isMuted) CrimsonError else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Mute Microphone",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = if (isMuted) "Unmute Mic" else "Mute Mic")
                }

                FilledTonalButton(
                    onClick = onListenToggle,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("listen_speaker_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isListening) DiscordBlurple.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                        contentColor = if (isListening) DiscordBlurple else MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = "Listen to Receiver",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = if (isListening) "Listening" else "Muted Audio")
                }
            }
        }
    }
}
