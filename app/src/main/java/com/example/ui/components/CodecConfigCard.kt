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
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.AudioConfig
import com.example.ui.theme.DiscordBlurple
import com.example.ui.theme.NeonEmerald

@Composable
fun CodecConfigCard(
    bitrate: Int,
    isHardwareOpusEncoder: Boolean,
    rtpPayloadType: Int,
    adaptiveJitterEnabled: Boolean,
    manualBufferDepthMs: Int,
    onBitrateChange: (Int) -> Unit,
    onPayloadTypeChange: (Int) -> Unit,
    onAdaptiveJitterChange: (Boolean) -> Unit,
    onManualBufferDepthChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("codec_config_card"),
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Compress,
                        contentDescription = "Opus Codec & WebRTC Protocol",
                        tint = DiscordBlurple,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Opus Codec & WebRTC Protocol",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isHardwareOpusEncoder) NeonEmerald.copy(alpha = 0.15f)
                            else DiscordBlurple.copy(alpha = 0.15f)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (isHardwareOpusEncoder) "MediaCodec Opus" else "Adaptive Opus VoIP",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = if (isHardwareOpusEncoder) NeonEmerald else DiscordBlurple
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Specs bar: 48 kHz | 20 ms | Mono
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                SpecPill("Sample Rate", "48,000 Hz")
                SpecPill("Frame Size", "20 ms (960)")
                SpecPill("Channels", "1 (VoIP Mono)")
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Bitrate Selector
            Text(
                text = "OPUS BITRATE",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AudioConfig.BITRATE_OPTIONS.forEach { bps ->
                    val kbps = bps / 1000
                    val isSelected = bitrate == bps
                    FilterChip(
                        selected = isSelected,
                        onClick = { onBitrateChange(bps) },
                        label = {
                            Text(
                                text = "${kbps}k",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = DiscordBlurple,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // RTP Payload Type
            Text(
                text = "RTP PAYLOAD TYPE (RFC 3550 / RFC 7587)",
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
                FilterChip(
                    selected = rtpPayloadType == AudioConfig.DEFAULT_RTP_PAYLOAD_TYPE_WEBRTC,
                    onClick = { onPayloadTypeChange(AudioConfig.DEFAULT_RTP_PAYLOAD_TYPE_WEBRTC) },
                    label = { Text("PT 111 (WebRTC Dynamic Opus)") },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DiscordBlurple,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                FilterChip(
                    selected = rtpPayloadType == AudioConfig.DEFAULT_RTP_PAYLOAD_TYPE_DISCORD,
                    onClick = { onPayloadTypeChange(AudioConfig.DEFAULT_RTP_PAYLOAD_TYPE_DISCORD) },
                    label = { Text("PT 120 (Discord Opus)") },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DiscordBlurple,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Adaptive Jitter Buffer Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Adaptive Jitter Buffer (RFC 3550)",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Auto-tunes playout delay based on live packet arrival jitter",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = adaptiveJitterEnabled,
                    onCheckedChange = onAdaptiveJitterChange,
                    modifier = Modifier.testTag("adaptive_jitter_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = DiscordBlurple
                    )
                )
            }

            if (!adaptiveJitterEnabled) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Manual Buffer Playout Delay",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "$manualBufferDepthMs ms",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = DiscordBlurple
                    )
                }
                Slider(
                    value = manualBufferDepthMs.toFloat(),
                    onValueChange = { onManualBufferDepthChange(it.toInt()) },
                    valueRange = 20f..200f,
                    steps = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_buffer_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = DiscordBlurple,
                        activeTrackColor = DiscordBlurple
                    )
                )
            }
        }
    }
}

@Composable
private fun SpecPill(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
