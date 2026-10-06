package pinhole

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Native HTTP pkarr discovery, authenticated against the ID from the ticket/QR.
 * A directory response never supplies its own trusted verification key. */
class IrohDiscovery(
    private val client: OkHttpClient,
    serviceUrl: URI = URI("https://dns.iroh.link/pkarr"),
) : Closeable {
    private val service = validateIrohUrl(serviceUrl)
    private val active = AtomicReference<Call?>()
    private val sequences = mutableMapOf<String, ULong>()
    @Volatile private var closed = false

    fun resolve(endpointId: String): IrohAddress {
        check(!closed) { "discovery is closed" }
        val key = IrohEncoding.key(endpointId)
        val url = service.toASCIIString().trimEnd('/') + "/" + IrohEncoding.base32(key, zbase = true)
        val call = client.newCall(Request.Builder().url(url).build())
        active.set(call)
        if (closed) call.cancel()
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("iroh discovery returned HTTP " + response.code)
                val body = response.body ?: throw IOException("empty iroh discovery response")
                val bytes = body.byteStream().use { input ->
                    val buffer = ByteArray(1073)
                    var used = 0
                    while (used < buffer.size) {
                        val count = input.read(buffer, used, buffer.size - used)
                        if (count < 0) break
                        used += count
                    }
                    buffer.copyOf(used)
                }
                val timestamp = timestamp(bytes)
                val record = parsePayload(key, bytes)
                synchronized(sequences) {
                    val previous = sequences[record.endpointId]
                    require(previous == null || timestamp >= previous) { "iroh discovery rollback" }
                    if (sequences.size < 1024 || previous != null) sequences[record.endpointId] = timestamp
                }
                return record
            }
        } finally { active.compareAndSet(call, null) }
    }

    override fun close() { closed = true; active.getAndSet(null)?.cancel() }

    companion object {
        internal fun timestamp(payload: ByteArray): ULong {
            require(payload.size in 84..1072) { "invalid pkarr payload size" }
            var value = 0uL
            for (i in 64..71) value = (value shl 8) or (payload[i].toULong() and 255uL)
            return value
        }

        internal fun parsePayload(key: ByteArray, payload: ByteArray): IrohAddress {
            require(key.size == 32)
            val sequence = timestamp(payload)
            val dns = payload.copyOfRange(72, payload.size)
            val prefix = ("3:seqi" + sequence + "e1:v" + dns.size + ":").toByteArray(Charsets.US_ASCII)
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(key, 0))
            verifier.update(prefix, 0, prefix.size)
            verifier.update(dns, 0, dns.size)
            require(verifier.verifySignature(payload.copyOf(64))) { "iroh discovery signature does not match the endpoint ID" }
            val values = txt(dns, "_iroh." + IrohEncoding.base32(key, zbase = true))
            val direct = mutableListOf<InetSocketAddress>()
            val relays = mutableListOf<URI>()
            var userData: String? = null
            values.forEach {
                when {
                    it.startsWith("relay=") -> relays.add(validateIrohUrl(URI(it.substring(6))))
                    it.startsWith("addr=") -> ipAddress(it.substring(5))?.let(direct::add)
                    it.startsWith("user-data=") -> {
                        require(userData == null) { "duplicate signed user data" }
                        userData = it.substring(10)
                        require(userData!!.toByteArray(Charsets.UTF_8).size <= 245)
                    }
                }
                require(direct.size + relays.size <= 64) { "too many discovered addresses" }
            }
            return IrohAddress(IrohEncoding.hex(key), direct.distinct(), relays.distinct(), userData)
        }

        private fun ipAddress(text: String): InetSocketAddress? {
            val colon = text.lastIndexOf(':')
            if (colon < 1) return null
            val host = text.substring(0, colon).removeSurrounding("[", "]")
            val port = text.substring(colon + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            val ipv4 = host.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } }
            if (!ipv4 && ':' !in host) return null // Never resolve a DNS hostname from addr=.
            return try { InetSocketAddress(InetAddress.getByName(host), port) } catch (_: Exception) { null }
        }

        private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()

        private fun txt(dns: ByteArray, owner: String): List<String> {
            fun u16(at: Int): Int { require(at >= 0 && at + 2 <= dns.size); return ((dns[at].toInt() and 255) shl 8) or (dns[at + 1].toInt() and 255) }
            val questions = u16(4)
            val answers = u16(6)
            val records = answers + u16(8) + u16(10)
            require(questions <= 64 && records <= 128)
            val reader = IrohEncoding.Reader(dns, 12)
            repeat(questions) { name(dns, reader); reader.bytes(4) }
            val values = mutableListOf<String>()
            repeat(records) { index ->
                val recordOwner = name(dns, reader)
                val type = u16(reader.offset)
                val klass = u16(reader.offset + 2) and 0x7fff
                val size = u16(reader.offset + 8)
                reader.bytes(10)
                val data = reader.bytes(size)
                if (index < answers && type == 16 && klass == 1 && recordOwner.equals(owner, ignoreCase = true)) {
                    val chunks = IrohEncoding.Reader(data)
                    val out = java.io.ByteArrayOutputStream()
                    while (!chunks.atEnd) out.write(chunks.bytes(chunks.byte()))
                    values.add(utf8(out.toByteArray()))
                }
            }
            require(reader.atEnd) { "trailing DNS bytes" }
            return values
        }

        private fun name(dns: ByteArray, input: IrohEncoding.Reader): String {
            val labels = mutableListOf<String>()
            val cursor = IrohEncoding.Reader(dns, input.offset)
            var end = -1
            repeat(128) {
                val length = cursor.byte()
                if (length == 0) {
                    input.offset = if (end < 0) cursor.offset else end
                    return labels.joinToString(".")
                }
                if (length and 0xc0 == 0xc0) {
                    val target = ((length and 63) shl 8) or cursor.byte()
                    require(target < cursor.offset - 2) { "invalid DNS compression pointer" }
                    if (end < 0) end = cursor.offset
                    cursor.offset = target
                } else {
                    require(length <= 63)
                    labels.add(utf8(cursor.bytes(length)))
                    require(labels.sumOf { it.length + 1 } <= 255)
                }
            }
            throw IllegalArgumentException("DNS compression loop")
        }
    }
}
