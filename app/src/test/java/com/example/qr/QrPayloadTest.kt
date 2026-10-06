package com.example.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pinhole.NatHint

class QrPayloadTest {

    private val ticket =
        "pinhole1:AwPvzauJZ0UjAQECBMCoASrIIgEEywBxB8giAgECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gZWZnaGlqa2xtbm9wcXJzdHV2d3h5ent8fX5-gIGCg4Q"

    @Test
    fun pinholeTicketIsRecognizedAndParsed() {
        val payload = QrPayload.parse(ticket)
        assertTrue(payload is QrPayload.PinholeTicket)
        payload as QrPayload.PinholeTicket
        assertEquals(ticket, payload.ticket)
        assertEquals(0x0123456789ABCDEFUL, payload.parsed.peerId)
        assertEquals(NatHint.Cone, payload.parsed.natHint)
    }

    @Test
    fun surroundingWhitespaceAndPrefixlessBodiesStillDial() {
        assertTrue(QrPayload.parse("  $ticket  ") is QrPayload.PinholeTicket)
        val body = ticket.removePrefix("pinhole1:")
        assertTrue(QrPayload.parse(body) is QrPayload.PinholeTicket)
    }

    @Test
    fun corruptedTicketIsNotAPinholePayload() {
        assertTrue(QrPayload.parse("pinhole1:not-a-real-ticket") is QrPayload.Unknown)
    }

    @Test
    fun nativeIrohTicketsAndIdsAreRecognized() {
        val id = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"
        val ticket = "endpointaab2cb576phbbpq5odorrz2lycmwpzgwgcn2kdk7dxoimzaskuy3qayaejuhi5dqom5c6l3bobztcljrfzzgk3dbpexg4mbonfzg62bonruw42zpaeah6aaaagaaqaibaaaaaaaaaaaaaaaaaaaaaaaaah776ay"
        val payload = QrPayload.parse("  " + ticket + "  ") as QrPayload.IrohEndpoint
        assertEquals(id, payload.parsed.endpointId)
        assertEquals(ticket, payload.ticket)
        assertTrue(QrPayload.parse(id) is QrPayload.IrohEndpoint)
        assertTrue(QrPayload.parse("endpointinvalid") is QrPayload.Unknown)
        assertTrue(QrPayload.parse("00".repeat(32)) is QrPayload.Unknown)
    }

    @Test
    fun udpEndpointsParse() {
        assertEquals(
            QrPayload.UdpEndpoint("192.168.1.42", 5004),
            QrPayload.parse("udp://192.168.1.42:5004")
        )
        assertEquals(
            QrPayload.UdpEndpoint("fe80::1", 5004),
            QrPayload.parse("udp://[fe80::1]:5004")
        )
        assertEquals(
            QrPayload.UdpEndpoint("stream.lan", 65535),
            QrPayload.parse("UDP://stream.lan:65535")
        )
    }

    @Test
    fun malformedUdpUrisFallThroughToUnknown() {
        assertTrue(QrPayload.parse("udp://host:notaport") is QrPayload.Unknown)
        assertTrue(QrPayload.parse("udp://:5004") is QrPayload.Unknown)
        assertTrue(QrPayload.parse("udp://host:0") is QrPayload.Unknown)
        assertTrue(QrPayload.parse("udp://host:70000") is QrPayload.Unknown)
    }

    @Test
    fun arbitraryTextStaysUnknown() {
        assertTrue(QrPayload.parse("https://example.com/call") is QrPayload.Unknown)
        val payload = QrPayload.parse("hello world")
        assertEquals("hello world", (payload as QrPayload.Unknown).text)
    }
}
