package pinhole

import java.net.InetAddress
import java.net.InetSocketAddress

/** Kind of candidate address a connection string can carry. */
enum class CandidateKind(val wire: Int) {
    Direct(1),
    Reflexive(2),
    Relay(3),
    IrohRelay(4);

    companion object {
        fun fromWire(value: Int): CandidateKind? = entries.firstOrNull { it.wire == value }
    }
}

/** NAT classification hint embedded by the publisher; Symmetric means a direct punch is hopeless. */
enum class NatHint(val wire: Int) {
    Unknown(0),
    Cone(1),
    Symmetric(2);

    companion object {
        fun fromWire(value: Int): NatHint? = entries.firstOrNull { it.wire == value }
    }
}

/** One dialable address. The minimal dialer punches Direct and Reflexive candidates. */
class PinholeCandidate(
    val kind: CandidateKind,
    val address: InetSocketAddress,
)

/**
 * "pinhole1:<base64url>" — a peer's stable id, NAT hint, candidate addresses, and (v2/v3)
 * its pinned X25519 static key. Wire-compatible with Pinhole.Net's ConnectionString.
 */
class ConnectionString(
    val peerId: ULong,
    val natHint: NatHint,
    val candidates: List<PinholeCandidate>,
    val staticKey: ByteArray?,
    val endpointKey: ByteArray?,
) {
    companion object {
        const val SCHEME = "pinhole1"
        const val MAX_CANDIDATES = 32
        const val MAX_LENGTH = 8192
        const val KEY_LENGTH = 32

        /** Lenient like Pinhole.Net's TryParseCode: surrounding whitespace and an omitted
         *  "pinhole1:" prefix are tolerated; anything malformed throws [IllegalArgumentException]. */
        fun parse(text: String): ConnectionString {
            require(text.length <= MAX_LENGTH) { "connection string exceeds $MAX_LENGTH characters" }
            var t = text.trim()
            if (':' !in t) t = "$SCHEME:$t"
            val colon = t.indexOf(':')
            require(colon > 0 && t.substring(0, colon) == SCHEME) { "expected a \"$SCHEME:\" connection string" }

            val payload = decodeBase64Url(t.substring(colon + 1))
            require(payload.size >= 13) { "payload too short" }
            val version = payload[0].toInt() and 0xFF
            require(version == 1 || version == 2 || version == 3) { "unsupported connection string version" }
            val flags = payload[1].toInt() and 0xFF
            val expectedFlags = when (version) {
                2 -> 1
                3 -> 3
                else -> 0
            }
            require(flags == expectedFlags) { "unknown connection string flags" }

            val reader = Reader(payload, 2)
            val peerId = reader.u64Le()
            val natHint = NatHint.fromWire(reader.byte())
                ?: throw IllegalArgumentException("unknown NAT hint")
            val count = reader.byte()
            require(count <= MAX_CANDIDATES) { "connection string carries more than $MAX_CANDIDATES candidates" }

            val candidates = ArrayList<PinholeCandidate>(count)
            repeat(count) {
                val address = reader.endpoint()
                val kind = CandidateKind.fromWire(reader.byte())
                    ?: throw IllegalArgumentException("unknown candidate kind")
                when (kind) {
                    CandidateKind.Relay -> {
                        reader.endpoint() // relay server
                        reader.shortText() // username
                        reader.shortText() // credential
                    }
                    CandidateKind.IrohRelay -> {
                        reader.shortText() // relay URL
                        reader.bytes(KEY_LENGTH) // relay public key
                    }
                    else -> {}
                }
                candidates.add(PinholeCandidate(kind, address))
            }

            val staticKey: ByteArray?
            val endpointKey: ByteArray?
            when (version) {
                2 -> {
                    require(reader.remaining == KEY_LENGTH) { "v2 string must end with a 32-byte static key" }
                    staticKey = reader.bytes(KEY_LENGTH)
                    endpointKey = null
                }
                3 -> {
                    require(reader.remaining == 2 * KEY_LENGTH) { "v3 string must end with static and endpoint keys" }
                    staticKey = reader.bytes(KEY_LENGTH)
                    endpointKey = reader.bytes(KEY_LENGTH)
                }
                else -> {
                    require(reader.atEnd) { "trailing bytes in connection string" }
                    staticKey = null
                    endpointKey = null
                }
            }

            return ConnectionString(peerId, natHint, candidates, staticKey, endpointKey)
        }

        /** Strict base64url charset, padding tolerated; anything else is malformed. */
        private fun decodeBase64Url(text: String): ByteArray {
            for (c in text) {
                val ok = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '='
                if (!ok) throw IllegalArgumentException("payload is not valid base64url")
            }
            val pad = (4 - text.length % 4) % 4
            val base64 = text.replace('-', '+').replace('_', '/') + "=".repeat(pad)
            return java.util.Base64.getMimeDecoder().decode(base64)
        }
    }

    private class Reader(private val data: ByteArray, var pos: Int) {
        fun byte(): Int {
            if (pos >= data.size) throw IllegalArgumentException("payload is truncated")
            return data[pos++].toInt() and 0xFF
        }

        fun u64Le(): ULong {
            if (pos + 8 > data.size) throw IllegalArgumentException("payload is truncated")
            var value = 0uL
            for (i in 7 downTo 0) {
                value = (value shl 8) or (data[pos + i].toULong() and 0xFFuL)
            }
            pos += 8
            return value
        }

        fun endpoint(): InetSocketAddress {
            val family = byte()
            require(family == 4 || family == 16) { "unknown address family" }
            require(pos + family + 2 <= data.size) { "payload is truncated" }
            val address = InetAddress.getByAddress(data.copyOfRange(pos, pos + family))
            pos += family
            val port = (byte() shl 8) or byte()
            return InetSocketAddress(address, port)
        }

        fun shortText(): String {
            val length = byte()
            require(length <= 64 && pos + length <= data.size) { "payload is truncated" }
            val text = String(data, pos, length, Charsets.US_ASCII)
            pos += length
            return text
        }

        fun bytes(count: Int): ByteArray {
            require(pos + count <= data.size) { "payload is truncated" }
            val out = data.copyOfRange(pos, pos + count)
            pos += count
            return out
        }

        val atEnd: Boolean get() = pos == data.size
        val remaining: Int get() = data.size - pos
    }
}
