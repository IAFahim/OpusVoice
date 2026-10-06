package com.example.qr

import pinhole.ConnectionString

/**
 * Turns raw QR text into the first matching typed payload. Every QR-enabled feature
 * grows a branch here — the scanner UI stays format-agnostic and hands over whatever
 * the code contained.
 *
 * Recognized today:
 *  - `pinhole1:…` (or the bare base64 body) — a Pinhole connection string to dial
 *  - `udp://host:port` — a plain RTP target for the app's UDP mode
 */
sealed interface QrPayload {

    /** A Pinhole connection string vouching for a peer; [parsed] proves it is well-formed. */
    data class PinholeTicket(val ticket: String, val parsed: ConnectionString) : QrPayload

    /** A plain UDP destination: fill the host and port fields. */
    data class UdpEndpoint(val host: String, val port: Int) : QrPayload

    /** Nothing recognized; the scanner shows the raw text so nothing is silently lost. */
    data class Unknown(val text: String) : QrPayload

    companion object {
        fun parse(raw: String): QrPayload {
            val text = raw.trim()
            ConnectionString.tryParse(text)?.let { return PinholeTicket(ticket = text, parsed = it) }
            parseUdpEndpoint(text)?.let { return it }
            return Unknown(text)
        }

        private fun parseUdpEndpoint(text: String): UdpEndpoint? {
            if (!text.startsWith("udp://", ignoreCase = true)) return null
            val body = text.substring("udp://".length)
            val port = body.substringAfterLast(':', "").toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            val hostPart = body.substringBeforeLast(':')
            if (hostPart.isEmpty()) return null
            val bracketed = hostPart.startsWith("[") && hostPart.endsWith("]")
            val host = if (bracketed) hostPart.removeSurrounding("[", "]") else hostPart
            if (host.isEmpty()) return null
            if (!bracketed && ':' in host) return null // unbracketed IPv6 is ambiguous: refuse
            return UdpEndpoint(host, port)
        }
    }
}
