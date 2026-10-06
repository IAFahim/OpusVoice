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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.TransmissionMode
import com.example.ui.theme.DiscordBlurple
import com.example.ui.theme.NeonEmerald
import java.util.Locale

@Composable
fun AudioDspCard(
    aecEnabled: Boolean,
    nsEnabled: Boolean,
    agcEnabled: Boolean,
    isAecSupported: Boolean,
    isNsSupported: Boolean,
    isAgcSupported: Boolean,
    vadThresholdDb: Float,
    micGain: Float,
    transmissionMode: TransmissionMode,
    onAecChange: (Boolean) -> Unit,
    onNsChange: (Boolean) -> Unit,
    onAgcChange: (Boolean) -> Unit,
    onVadThresholdChange: (Float) -> Unit,
    onMicGainChange: (Float) -> Unit,
    onTransmissionModeChange: (TransmissionMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("audio_dsp_card"),
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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Equalizer,
                    contentDescription = "Audio Processing DSP",
                    tint = DiscordBlurple,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "VoIP Processing & Clarity DSP",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Transmission Mode Selector
            Text(
                text = "TRANSMISSION MODE",
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
                TransmissionChip(
                    label = "Voice Activity (VAD)",
                    selected = transmissionMode == TransmissionMode.VOICE_ACTIVITY,
                    onClick = { onTransmissionModeChange(TransmissionMode.VOICE_ACTIVITY) },
                    modifier = Modifier.weight(1f)
                )
                TransmissionChip(
                    label = "Push-to-Talk",
                    selected = transmissionMode == TransmissionMode.PUSH_TO_TALK,
                    onClick = { onTransmissionModeChange(TransmissionMode.PUSH_TO_TALK) },
                    modifier = Modifier.weight(1f)
                )
                TransmissionChip(
                    label = "Continuous",
                    selected = transmissionMode == TransmissionMode.CONTINUOUS,
                    onClick = { onTransmissionModeChange(TransmissionMode.CONTINUOUS) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 1. Acoustic Echo Cancellation (AEC)
            DspToggleRow(
                title = "Acoustic Echo Cancellation (AEC)",
                subtitle = "Removes speaker playback loopback from microphone",
                enabled = aecEnabled,
                isHardware = isAecSupported,
                onCheckedChange = onAecChange,
                testTag = "aec_switch"
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Noise Suppression (NS)
            DspToggleRow(
                title = "Noise Suppression (NS)",
                subtitle = "Filters background fan, typing, and ambient room noise",
                enabled = nsEnabled,
                isHardware = isNsSupported,
                onCheckedChange = onNsChange,
                testTag = "ns_switch"
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Automatic Gain Control (AGC)
            DspToggleRow(
                title = "Auto Gain Control (AGC)",
                subtitle = "Dynamically normalizes volume for whispering & shouting",
                enabled = agcEnabled,
                isHardware = isAgcSupported,
                onCheckedChange = onAgcChange,
                testTag = "agc_switch"
            )

            Spacer(modifier = Modifier.height(18.dp))

            // VAD Sensitivity Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "VAD Sensitivity Threshold",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = String.format(Locale.US, "%.0f dBFS", vadThresholdDb),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = DiscordBlurple
                )
            }
            Slider(
                value = vadThresholdDb,
                onValueChange = onVadThresholdChange,
                valueRange = -70f..-15f,
                steps = 11,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vad_threshold_slider"),
                colors = SliderDefaults.colors(
                    thumbColor = DiscordBlurple,
                    activeTrackColor = DiscordBlurple
                )
            )

            // Mic Boost Gain Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Microphone Boost",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = String.format(Locale.US, "%.1fx", micGain),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = NeonEmerald
                )
            }
            Slider(
                value = micGain,
                onValueChange = onMicGainChange,
                valueRange = 0.5f..2.5f,
                steps = 8,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("mic_gain_slider"),
                colors = SliderDefaults.colors(
                    thumbColor = NeonEmerald,
                    activeTrackColor = NeonEmerald
                )
            )
        }
    }
}

@Composable
private fun TransmissionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                ),
                maxLines = 1
            )
        },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = DiscordBlurple,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

@Composable
private fun DspToggleRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    isHardware: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isHardware) NeonEmerald.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surface
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isHardware) "Hardware DSP" else "Software DSP",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        color = if (isHardware) NeonEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Switch(
            checked = enabled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = DiscordBlurple
            )
        )
    }
}
