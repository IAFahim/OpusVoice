package com.example.audio

/**
 * Industry-standard configuration constants for WebRTC Opus audio streaming.
 * Discord and WebRTC VoIP protocols standardize on 48,000 Hz fullband audio
 * with 20ms frame packets (960 samples per frame).
 */
object AudioConfig {
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 1 // Mono for VoIP mic transmission
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = 2

    // 20ms at 48kHz = 960 samples
    const val FRAME_DURATION_MS = 20
    const val SAMPLES_PER_FRAME = (SAMPLE_RATE * FRAME_DURATION_MS) / 1000 // 960
    const val FRAME_BYTES = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE // 1920 bytes

    // WebRTC Opus RTP specifications (RFC 7587)
    const val DEFAULT_RTP_PAYLOAD_TYPE_WEBRTC = 111 // Standard dynamic WebRTC Opus
    const val DEFAULT_RTP_PAYLOAD_TYPE_DISCORD = 120 // Discord Opus payload type
    const val RTP_HEADER_SIZE = 12

    // Default network ports
    const val DEFAULT_TARGET_PORT = 5004 // Standard RTP port
    const val DEFAULT_LOCAL_RECEIVE_PORT = 5004

    // Bitrate options
    val BITRATE_OPTIONS = listOf(16000, 32000, 64000, 96000, 128000)
    const val DEFAULT_BITRATE = 64000 // 64 kbps (standard high quality voice)
}
