package pinhole

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress

/** A receiver discovered on the local link. Discovery is unsigned; its encrypted
 * handshake proves key possession. A trusted ticket/QR establishes device identity. */
data class LanReceiver(val peerId: ULong, val addresses: List<InetSocketAddress>, val ticket: String) {
    val id: String get() = peerId.toString(16).padStart(16, '0')

    companion object {
        const val SERVICE_TYPE = "_pinhole._udp."
        private val hexId = Regex("[0-9a-fA-F]{16}")
        private val hexKey = Regex("[0-9a-fA-F]{64}")

        fun isService(name: String, type: String): Boolean = hexId.matches(name) &&
            type.lowercase().trimEnd('.').removeSuffix(".local") == SERVICE_TYPE.trimEnd('.')

        /** Validate a resolved DNS-SD record without resolving attacker-supplied names.
         * Preserve already scoped IPv6 addresses and use the actual receiving scope
         * for unscoped link-locals when the platform supplied one. */
        fun fromService(name: String, type: String, port: Int, addresses: List<InetAddress>,
                        attributes: Map<String, ByteArray>, scope: Int = 0): LanReceiver? {
            if (!isService(name, type) || port !in 1..65535) return null
            fun text(key: String) = attributes[key]?.takeIf { it.size <= 64 }?.toString(Charsets.US_ASCII)
            if (!name.equals(text("id"), ignoreCase = true)) return null
            val key = text("key")?.takeIf { hexKey.matches(it) }?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
                ?: return null // the audio dialer requires a pinned encryption key
            if (key.all { it == 0.toByte() }) return null
            val hint = when (val value = text("hint")) {
                null -> NatHint.Unknown
                else -> NatHint.entries.firstOrNull { it.wire.toString() == value } ?: return null
            }
            val endpoints = addresses.asSequence().filter {
                !it.isAnyLocalAddress && !it.isMulticastAddress &&
                    !(it is Inet4Address && (it.address[0].toInt() and 255) >= 224)
            }.map { ip ->
                val scoped = if (ip is Inet6Address && ip.isLinkLocalAddress && ip.scopeId == 0 && scope > 0)
                    Inet6Address.getByAddress(null, ip.address, scope) else ip
                InetSocketAddress(scoped, port)
            }.distinctBy { "${it.address.hostAddress}:${it.port}" }.take(8).toList()
            if (endpoints.isEmpty()) return null
            val peerId = name.toULongOrNull(16)?.takeIf { it != 0uL } ?: return null
            val ticket = ConnectionString(peerId, hint, endpoints.map { PinholeCandidate(CandidateKind.Direct, it) }, key, null).toString()
            return LanReceiver(peerId, endpoints, ticket)
        }
    }
}

/** Bounded discovery cache. An update cannot substitute another key for an already
 * discovered service; lost services and a new browsing lifecycle clear that pin. */
class LanReceiverCatalog {
    private val services = linkedMapOf<String, LanReceiver>()

    @Synchronized fun update(service: String, receiver: LanReceiver?) {
        if (receiver == null) { services.remove(service); return }
        val old = services[service]
        if (old != null && !sameKey(old, receiver)) return
        if (old != null || services.size < 32) services[service] = receiver
    }

    @Synchronized fun remove(service: String) { services.remove(service) }
    @Synchronized fun clear() { services.clear() }

    @Synchronized fun snapshot(): List<LanReceiver> = services.values.groupBy { it.peerId }.map { (_, records) ->
        val first = records.first()
        val addresses = records.filter { sameKey(first, it) }.flatMap { it.addresses }
            .distinctBy { "${it.address.hostAddress}:${it.port}" }.take(8)
        val cs = ConnectionString.parse(first.ticket)
        first.copy(addresses = addresses, ticket = ConnectionString(cs.peerId, cs.natHint,
            addresses.map { PinholeCandidate(CandidateKind.Direct, it) }, cs.staticKey, cs.endpointKey).toString())
    }.sortedBy { it.id }

    private fun sameKey(left: LanReceiver, right: LanReceiver): Boolean =
        left.peerId == right.peerId && java.security.MessageDigest.isEqual(
            ConnectionString.parse(left.ticket).staticKey, ConnectionString.parse(right.ticket).staticKey)
}
