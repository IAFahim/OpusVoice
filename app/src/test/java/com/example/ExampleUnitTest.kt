package com.example

import com.example.audio.AudioConfig
import com.example.audio.AudioDspManager
import com.example.webrtc.JitterBuffer
import com.example.webrtc.RtpPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testRtpPacketSerializationAndParsing() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val packet = RtpPacket(
            version = 2,
            padding = false,
            hasExtension = false,
            csrcCount = 0,
            marker = true,
            payloadType = AudioConfig.DEFAULT_RTP_PAYLOAD_TYPE_WEBRTC,
            sequenceNumber = 1234,
            timestamp = 96000L,
            ssrc = 0xDEADBEEFL,
            payload = payload
        )

        val serialized = packet.toByteArray()
        assertEquals(12 + payload.size, serialized.size)

        val parsed = RtpPacket.parse(serialized)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.version)
        assertTrue(parsed.marker)
        assertEquals(AudioConfig.DEFAULT_RTP_PAYLOAD_TYPE_WEBRTC, parsed.payloadType)
        assertEquals(1234, parsed.sequenceNumber)
        assertEquals(96000L, parsed.timestamp)
        assertEquals(0xDEADBEEFL, parsed.ssrc)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun testJitterBufferOrderingAndStats() {
        val jb = JitterBuffer(adaptiveEnabled = true)
        jb.reset()

        val p1 = RtpPacket(
            payloadType = 111,
            sequenceNumber = 100,
            timestamp = 1000L,
            ssrc = 1L,
            payload = byteArrayOf(1, 2)
        )
        val p2 = RtpPacket(
            payloadType = 111,
            sequenceNumber = 101,
            timestamp = 1960L,
            ssrc = 1L,
            payload = byteArrayOf(3, 4)
        )

        jb.push(p1)
        jb.push(p2)

        val stats = jb.getSnapshot()
        assertEquals(2L, stats.totalReceived)
        assertEquals(0L, stats.totalLost)
    }
}
