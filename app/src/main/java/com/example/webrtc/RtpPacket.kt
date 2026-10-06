package com.example.webrtc

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RFC 3550 Real-Time Transport Protocol (RTP) packet implementation.
 * Used by WebRTC, Discord, and VoIP systems for transporting Opus audio payloads over UDP.
 *
 * RTP Header (12 bytes):
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|X|  CC   |M|     PT      |       Sequence Number         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           Timestamp                           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           Synchronization Source (SSRC) identifier            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
data class RtpPacket(
    val version: Int = 2,
    val padding: Boolean = false,
    val hasExtension: Boolean = false,
    val csrcCount: Int = 0,
    val marker: Boolean = false,
    val payloadType: Int,
    val sequenceNumber: Int, // 0..65535
    val timestamp: Long, // 32-bit unsigned
    val ssrc: Long, // 32-bit unsigned
    val payload: ByteArray,
    val arrivalTimestampMs: Long = System.currentTimeMillis()
) {
    fun toByteArray(): ByteArray {
        val totalLength = 12 + payload.size
        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN)

        // Byte 0: V=2, P=0, X=0, CC=0 -> 0x80
        var b0 = (version and 0x03) shl 6
        if (padding) b0 = b0 or 0x20
        if (hasExtension) b0 = b0 or 0x10
        b0 = b0 or (csrcCount and 0x0F)
        buffer.put(b0.toByte())

        // Byte 1: Marker (1 bit) + Payload Type (7 bits)
        var b1 = payloadType and 0x7F
        if (marker) b1 = b1 or 0x80
        buffer.put(b1.toByte())

        // Bytes 2-3: Sequence Number (16 bits)
        buffer.putShort((sequenceNumber and 0xFFFF).toShort())

        // Bytes 4-7: Timestamp (32 bits)
        buffer.putInt((timestamp and 0xFFFFFFFFL).toInt())

        // Bytes 8-11: SSRC (32 bits)
        buffer.putInt((ssrc and 0xFFFFFFFFL).toInt())

        // Payload
        buffer.put(payload)

        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RtpPacket
        if (sequenceNumber != other.sequenceNumber) return false
        if (timestamp != other.timestamp) return false
        if (ssrc != other.ssrc) return false
        if (payloadType != other.payloadType) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = sequenceNumber
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + ssrc.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val HEADER_SIZE = 12

        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size): RtpPacket? {
            if (length < HEADER_SIZE) return null

            val buffer = ByteBuffer.wrap(data, offset, length).order(ByteOrder.BIG_ENDIAN)

            val b0 = buffer.get().toInt() and 0xFF
            val version = (b0 ushr 6) and 0x03
            val padding = ((b0 ushr 5) and 0x01) == 1
            val hasExtension = ((b0 ushr 4) and 0x01) == 1
            val csrcCount = b0 and 0x0F

            val b1 = buffer.get().toInt() and 0xFF
            val marker = ((b1 ushr 7) and 0x01) == 1
            val payloadType = b1 and 0x7F

            val sequenceNumber = buffer.short.toInt() and 0xFFFF
            val timestamp = buffer.int.toLong() and 0xFFFFFFFFL
            val ssrc = buffer.int.toLong() and 0xFFFFFFFFL

            var headerOffset = HEADER_SIZE + (csrcCount * 4)
            if (length < headerOffset) return null

            // Skip extensions if present
            if (hasExtension) {
                if (length < headerOffset + 4) return null
                buffer.position(headerOffset)
                val extProfile = buffer.short.toInt() and 0xFFFF
                val extLengthWords = buffer.short.toInt() and 0xFFFF
                headerOffset += 4 + (extLengthWords * 4)
                if (length < headerOffset) return null
            }

            var payloadLength = length - headerOffset
            if (padding && payloadLength > 0) {
                val padBytes = data[offset + length - 1].toInt() and 0xFF
                if (padBytes <= payloadLength) {
                    payloadLength -= padBytes
                }
            }

            if (payloadLength < 0) return null

            val payload = ByteArray(payloadLength)
            System.arraycopy(data, offset + headerOffset, payload, 0, payloadLength)

            return RtpPacket(
                version = version,
                padding = padding,
                hasExtension = hasExtension,
                csrcCount = csrcCount,
                marker = marker,
                payloadType = payloadType,
                sequenceNumber = sequenceNumber,
                timestamp = timestamp,
                ssrc = ssrc,
                payload = payload
            )
        }
    }
}
