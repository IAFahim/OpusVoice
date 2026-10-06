package pinhole

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import org.bouncycastle.math.ec.rfc8032.Ed25519

/** Native iroh endpoint identity and IP/relay paths. User data belongs to signed
 * discovery; native endpoint tickets do not carry a Pinhole session key. */
class IrohAddress(
    endpointId: String,
    directAddresses: List<InetSocketAddress> = emptyList(),
    relayUrls: List<URI> = emptyList(),
    val userData: String? = null,
) {
    val directAddresses = directAddresses.toList()
    val relayUrls = relayUrls.toList()
    internal val key = IrohEncoding.key(endpointId)
    val endpointId = IrohEncoding.hex(key)

    init {
        require(directAddresses.size + relayUrls.size <= 64) { "too many iroh addresses" }
        directAddresses.forEach { require(!it.isUnresolved && it.port in 1..65535) }
        relayUrls.forEach { validateIrohUrl(it) }
        require(userData == null || userData.toByteArray(Charsets.UTF_8).size <= 245)
    }

    override fun toString(): String {
        val out = ByteArrayOutputStream()
        out.write(0)
        out.write(key)
        IrohEncoding.varint(out, (relayUrls.size + directAddresses.size).toULong())
        relayUrls.sortedBy { it.toASCIIString() }.forEach {
            out.write(0)
            val url = it.toASCIIString().toByteArray(Charsets.UTF_8)
            IrohEncoding.varint(out, url.size.toULong())
            out.write(url)
        }
        directAddresses.sortedWith(compareBy<InetSocketAddress>(
            { it.address.address.size }, { IrohEncoding.hex(it.address.address) }, { it.port },
        )).forEach {
            out.write(1)
            val ip = it.address.address
            out.write(if (ip.size == 4) 0 else 1)
            out.write(ip)
            IrohEncoding.varint(out, it.port.toULong())
        }
        return "endpoint" + IrohEncoding.base32(out.toByteArray())
    }

    companion object {
        fun parse(text: String): IrohAddress {
            val value = text.trim()
            require(value.length <= 32768) { "iroh ticket is too long" }
            if (!value.startsWith("endpoint")) return IrohAddress(value)
            val input = IrohEncoding.Reader(IrohEncoding.decode32(value.substring(8)))
            require(input.byte() == 0) { "unsupported iroh ticket version" }
            val id = IrohEncoding.hex(input.bytes(32))
            val count = input.varint()
            require(count <= 64uL) { "too many iroh ticket addresses" }
            val direct = mutableListOf<InetSocketAddress>()
            val relays = mutableListOf<URI>()
            repeat(count.toInt()) {
                when (input.byte()) {
                    0 -> {
                        val size = input.varint()
                        require(size in 1uL..2048uL) { "invalid relay URL length" }
                        val url = URI(String(input.bytes(size.toInt()), Charsets.UTF_8))
                        relays.add(validateIrohUrl(url))
                    }
                    1 -> {
                        val family = input.byte()
                        require(family in 0..1) { "invalid iroh IP family" }
                        val ip = InetAddress.getByAddress(input.bytes(if (family == 0) 4 else 16))
                        val port = input.varint()
                        require(port in 1uL..65535uL) { "invalid iroh UDP port" }
                        direct.add(InetSocketAddress(ip, port.toInt()))
                    }
                    else -> throw IllegalArgumentException("unsupported iroh custom transport")
                }
            }
            require(input.atEnd) { "trailing bytes in iroh ticket" }
            return IrohAddress(id, direct.distinct(), relays.distinct())
        }

        fun tryParse(text: String): IrohAddress? = try { parse(text) } catch (_: Exception) { null }
    }
}

internal fun validateIrohUrl(url: URI): URI {
    val host = url.host?.removeSurrounding("[", "]") ?: ""
    val loopback = host == "localhost" || host == "::1" ||
        (host.startsWith("127.") && host.split('.').size == 4 &&
            host.split('.').all { it.toIntOrNull() in 0..255 })
    require(url.isAbsolute && (url.scheme == "https" || (url.scheme == "http" && loopback)) &&
        host.isNotEmpty() && url.userInfo == null && url.query == null && url.fragment == null &&
        url.toASCIIString().length <= 2048) { "iroh URL must use HTTPS (HTTP only on loopback)" }
    return url
}

internal object IrohEncoding {
    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"
    private const val ZBASE32 = "ybndrfg8ejkmcpqxot1uwisza345h769"

    fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it.toInt() and 255) }

    fun unhex(text: String): ByteArray {
        require(text.length % 2 == 0) { "invalid hex length" }
        return ByteArray(text.length / 2) { i ->
            val hi = text[i * 2].digitToIntOrNull(16) ?: error("invalid hex")
            val lo = text[i * 2 + 1].digitToIntOrNull(16) ?: error("invalid hex")
            ((hi shl 4) or lo).toByte()
        }
    }

    fun key(text: String): ByteArray {
        require(text.length == 64 || text.length == 52) { "iroh ID must be a 32-byte key" }
        val key = if (text.length == 64) unhex(text) else decode32(text)
        require(key.size == 32 && Ed25519.validatePublicKeyFull(key, 0)) { "invalid iroh endpoint key" }
        return key
    }

    fun base32(data: ByteArray, zbase: Boolean = false): String {
        val alphabet = if (zbase) ZBASE32 else BASE32
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        data.forEach {
            buffer = (buffer shl 8) or (it.toInt() and 255)
            bits += 8
            while (bits >= 5) { bits -= 5; out.append(alphabet[(buffer ushr bits) and 31]) }
        }
        if (bits > 0) out.append(alphabet[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    fun decode32(text: String): ByteArray {
        require(text.length <= 32768)
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        text.forEach {
            val value = BASE32.indexOf(it.lowercaseChar())
            require(value >= 0) { "invalid base32" }
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) { bits -= 8; out.write((buffer ushr bits) and 255) }
        }
        require(bits < 5 && buffer and ((1 shl bits) - 1) == 0) { "noncanonical base32" }
        return out.toByteArray()
    }

    fun varint(out: ByteArrayOutputStream, number: ULong) {
        var value = number
        while (value >= 128uL) { out.write(value.toInt() or 128); value = value shr 7 }
        out.write(value.toInt())
    }

    class Reader(private val data: ByteArray, var offset: Int = 0) {
        fun byte(): Int { require(offset < data.size) { "truncated packet" }; return data[offset++].toInt() and 255 }
        fun bytes(count: Int): ByteArray {
            require(count >= 0 && count <= data.size - offset) { "truncated packet" }
            return data.copyOfRange(offset, offset + count).also { offset += count }
        }
        fun varint(): ULong {
            var value = 0uL
            for (shift in 0..63 step 7) {
                val next = byte()
                require(shift != 63 || next <= 1) { "varint overflow" }
                value = value or ((next and 127).toULong() shl shift)
                if (next < 128) {
                    require(shift == 0 || next != 0) { "noncanonical varint" }
                    return value
                }
            }
            throw IllegalArgumentException("varint overflow")
        }
        val atEnd: Boolean get() = offset == data.size
    }
}
