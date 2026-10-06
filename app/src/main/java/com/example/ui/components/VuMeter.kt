package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.TransmissionMode
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.DiscordBlurple
import com.example.ui.theme.NeonEmerald
import java.util.Locale

@Composable
fun VuMeter(
    inputDb: Float,
    thresholdDb: Float,
    isTransmitting: Boolean,
    isMuted: Boolean,
    transmissionMode: TransmissionMode,
    isPttPressed: Boolean,
    outputDb: Float,
    isOutputPlaying: Boolean,
    onPttPressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // Normalize input dB: -80 dB..0 dB mapped to 0f..1f
    val normalizedInput = ((inputDb + 80f) / 80f).coerceIn(0f, 1f)
    val normalizedThreshold = ((thresholdDb + 80f) / 80f).coerceIn(0f, 1f)
    val normalizedOutput = ((outputDb + 80f) / 80f).coerceIn(0f, 1f)

    val animatedInputFraction by animateFloatAsState(
        targetValue = if (isMuted) 0f else normalizedInput,
        animationSpec = tween(durationMillis = 60),
        label = "input_fraction"
    )

    val animatedOutputFraction by animateFloatAsState(
        targetValue = if (isOutputPlaying) normalizedOutput else 0f,
        animationSpec = tween(durationMillis = 60),
        label = "output_fraction"
    )

    val activeColor by animateColorAsState(
        targetValue = when {
            isMuted -> CrimsonError
            isTransmitting -> NeonEmerald
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        },
        label = "active_color"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("vu_meter_card"),
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
            // Header: Status & dB Readout
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(activeColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            isMuted -> "MUTED"
                            isTransmitting -> "TRANSMITTING (OPUS)"
                            transmissionMode == TransmissionMode.PUSH_TO_TALK -> "READY (HOLD PTT)"
                            else -> "VOICE GATE CLOSED"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = activeColor
                    )
                }

                Text(
                    text = String.format(Locale.US, "%.1f dBFS", inputDb),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Main Input VU Level Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                // Gradient Fill Bar
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val fillWidth = size.width * animatedInputFraction
                    if (fillWidth > 0f) {
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    NeonEmerald,
                                    AmberWarning,
                                    CrimsonError
                                )
                            ),
                            size = Size(fillWidth, size.height),
                            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                        )
                    }

                    // Draw Threshold Vertical Marker Line
                    val thresholdX = size.width * normalizedThreshold
                    drawLine(
                        color = Color.White.copy(alpha = 0.9f),
                        start = Offset(thresholdX, 0f),
                        end = Offset(thresholdX, size.height),
                        strokeWidth = 3.dp.toPx()
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Threshold Sub-label
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "-80 dB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Text(
                    text = String.format(Locale.US, "Gate: %.0f dB", thresholdDb),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "0 dB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            // Output Speaker Monitor Bar
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Speaker Playout Monitor",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Receiver Playout",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val fillWidth = size.width * animatedOutputFraction
                        if (fillWidth > 0f) {
                            drawRoundRect(
                                color = DiscordBlurple,
                                size = Size(fillWidth, size.height),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = String.format(Locale.US, "%.0f dB", outputDb),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Push-to-Talk Interactive Trigger if PTT mode selected
            if (transmissionMode == TransmissionMode.PUSH_TO_TALK) {
                Spacer(modifier = Modifier.height(16.dp))
                val pttBackground = if (isPttPressed) NeonEmerald else DiscordBlurple
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .testTag("push_to_talk_button")
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    onPttPressChange(true)
                                    tryAwaitRelease()
                                    onPttPressChange(false)
                                }
                            )
                        },
                    color = pttBackground,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isPttPressed) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = "Push To Talk",
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isPttPressed) "TRANSMITTING (HOLDING)" else "PRESS & HOLD TO SPEAK",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
