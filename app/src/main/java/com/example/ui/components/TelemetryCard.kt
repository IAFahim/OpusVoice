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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.DiscordBlurple
import com.example.ui.theme.NeonEmerald
import com.example.webrtc.JitterBufferStats
import com.example.webrtc.NetworkTelemetry
import java.util.Locale

@Composable
fun TelemetryCard(
    jitterStats: JitterBufferStats,
    networkTelemetry: NetworkTelemetry,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    val jitterMs = jitterStats.jitterMs
    val lossRate = jitterStats.lossRatePercent

    val (jitterLabel, jitterColor) = when {
        jitterMs < 15.0 -> "Optimal" to NeonEmerald
        jitterMs < 40.0 -> "Good" to NeonEmerald
        jitterMs < 80.0 -> "Moderate" to AmberWarning
        else -> "High Jitter" to CrimsonError
    }

    val lossColor = when {
        lossRate < 1.0 -> NeonEmerald
        lossRate < 5.0 -> AmberWarning
        else -> CrimsonError
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("telemetry_card"),
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
            // Card Title & Jitter Quality Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CellTower,
                        contentDescription = "Network Jitter Telemetry",
                        tint = DiscordBlurple,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "WebRTC Network & Jitter Buffer",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Jitter status pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(jitterColor.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(jitterColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = jitterLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = jitterColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4 Grid Telemetry Tiles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryTile(
                    title = "JITTER",
                    value = String.format(Locale.US, "%.1f ms", jitterMs),
                    icon = Icons.Default.Timer,
                    valueColor = jitterColor,
                    modifier = Modifier.weight(1f)
                )

                TelemetryTile(
                    title = "PACKET LOSS",
                    value = String.format(Locale.US, "%.1f%%", lossRate),
                    icon = Icons.Default.SwapVert,
                    valueColor = lossColor,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryTile(
                    title = "BUFFER DELAY",
                    value = "${jitterStats.bufferDepthMs} ms",
                    icon = Icons.Default.Speed,
                    valueColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                TelemetryTile(
                    title = "BITRATE (TX/RX)",
                    value = String.format(
                        Locale.US,
                        "%.0f / %.0f kbps",
                        networkTelemetry.sendBitrateKbps,
                        networkTelemetry.receiveBitrateKbps
                    ),
                    icon = Icons.Default.SwapVert,
                    valueColor = DiscordBlurple,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Packet Counter Footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Packets Sent: ${networkTelemetry.packetsSent}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Received: ${networkTelemetry.packetsReceived} (Lost: ${jitterStats.totalLost})",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TelemetryTile(
    title: String,
    value: String,
    icon: ImageVector,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                ),
                color = valueColor
            )
        }
    }
}
