package pinhole

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Best-effort mapping of the session's UDP port. Android supplies OS routes through
 * gateways; JVM callers can replace them explicitly. No guessed gateway addresses. */
data class PortMappingOptions(
    val enabled: Boolean = true,
    val gateways: () -> List<InetSocketAddress> = { RouteGateways.linux() },
    val leaseSeconds: Long = 7200,
    val ssdpTarget: InetSocketAddress = InetSocketAddress("239.255.255.250", 1900),
) {
    init { require(leaseSeconds in 1..0xffff_ffffL); require(!ssdpTarget.isUnresolved) }
}

internal interface RouterLease {
    val external: InetSocketAddress
    val lifetimeSeconds: Long
    fun renew(): Boolean
    fun release()
}

/** One cancellable control operation at a time, separate from the session socket. */
internal class MappingIo : Closeable {
    private val stopped = AtomicBoolean(false)
    private val active = AtomicReference<Closeable?>()
    val isClosed: Boolean get() = stopped.get()

    fun <T> use(resource: Closeable, operation: () -> T): T {
        if (stopped.get()) { resource.close(); throw IOException("mapping discovery stopped") }
        check(active.compareAndSet(null, resource))
        if (stopped.get()) { active.compareAndSet(resource, null); resource.close(); throw IOException("mapping discovery stopped") }
        try { return operation() }
        finally { active.compareAndSet(resource, null); resource.close() }
    }

    override fun close() { stopped.set(true); active.getAndSet(null)?.close() }
}

/** Background discovery, lease renewal, and withdrawal; never joins the audio dial. */
internal class RouterPortMapper(
    private val internalPort: Int,
    private val options: PortMappingOptions,
    private val publish: (InetSocketAddress?) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val revision = java.util.concurrent.atomic.AtomicLong()
    private val io = MappingIo()
    private val publicationGate = Any()
    private var publicationVersion = 0L
    private var expiry: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile var current: InetSocketAddress? = null
        private set
    private val worker = thread(isDaemon = true, name = "pinhole-port-mapping") { run() }

    fun refresh() { revision.incrementAndGet() }

    private fun update(endpoint: InetSocketAddress?) {
        synchronized(publicationGate) {
            publicationVersion++; expiry?.cancel(false); expiry = null
            if (closed.get()) return
            if (current != endpoint) { current = endpoint; publish(endpoint) }
        }
    }

    private fun publishLease(lease: RouterLease) {
        synchronized(publicationGate) {
            update(lease.external)
            if (closed.get()) return
            val version = publicationVersion
            // A slow renewal cannot keep an expired mapping advertised.
            expiry = expiryExecutor.schedule({
                synchronized(publicationGate) {
                    if (!closed.get() && publicationVersion == version) update(null)
                }
            }, lease.lifetimeSeconds, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun run() {
        if (!options.enabled) return
        var lease: RouterLease? = null
        var network: List<String>? = null
        var seenRevision = -1L
        var next = 0L
        var passes = 0
        var everMapped = false
        try {
            while (!closed.get()) {
                val gateways = try { options.gateways().filter { !it.isUnresolved && it.port > 0 && !it.address.isAnyLocalAddress && !it.address.isMulticastAddress }
                    .distinctBy { "${it.address.hostAddress}:${it.port}" }.take(4) }
                    catch (_: Exception) { emptyList() }
                val generation = revision.get()
                val networkKey = gateways.map { "${it.address.hostAddress}:${it.port}" }
                if (network != networkKey || seenRevision != generation) {
                    val retired = lease
                    lease = null; update(null); network = networkKey; seenRevision = generation
                    retired?.let { try { it.release() } catch (_: Exception) { } }
                    next = 0; passes = 0; everMapped = false
                }
                val now = System.nanoTime()
                if (now >= next) {
                    if (lease != null) {
                        val live = lease
                        val renewed = try { live.renew() } catch (_: Exception) { false }
                        if (!renewed) {
                            lease = null; update(null)
                            try { live.release() } catch (_: Exception) { }
                            next = System.nanoTime() + 60_000_000_000L
                        } else {
                            publishLease(live)
                            next = System.nanoTime() + live.lifetimeSeconds * 500_000_000L
                        }
                    } else {
                        passes++
                        lease = try {
                            RouterProtocols.pcp(io, gateways, internalPort, options.leaseSeconds)
                                ?: RouterProtocols.natPmp(io, gateways, internalPort, options.leaseSeconds)
                                ?: UpnpPortMapping.discover(io, options.ssdpTarget, internalPort, options.leaseSeconds)
                        } catch (_: Exception) { null }
                        val mapped = lease
                        if (mapped != null && !closed.get() && revision.get() == seenRevision) {
                            everMapped = true; publishLease(mapped)
                            next = System.nanoTime() + mapped.lifetimeSeconds * 500_000_000L
                        } else next = if (everMapped || passes < 3) System.nanoTime() +
                            (if (everMapped) 60_000_000_000L else 4_000_000_000L) else Long.MAX_VALUE
                    }
                }
                Thread.sleep(250)
            }
        } catch (_: InterruptedException) { }
        finally {
            synchronized(publicationGate) { current = null; expiry?.cancel(false); expiry = null }
            lease?.let { try { it.release() } catch (_: Exception) { } }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(publicationGate) { current = null; expiry?.cancel(false); expiry = null }
        io.close(); worker.interrupt()
    }

    internal fun awaitStopped(timeoutMs: Long = 3000): Boolean { worker.join(timeoutMs); return !worker.isAlive }

    companion object {
        private val expiryExecutor = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "pinhole-mapping-expiry").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

internal object RouterProtocols {
    data class Grant(val external: InetSocketAddress, val lifetime: Long)

    fun pcpRequest(nonce: ByteArray, client: InetAddress, port: Int, lifetime: Long, suggested: InetSocketAddress? = null): ByteArray {
        require(nonce.size == 12 && port in 1..65535 && lifetime in 0..0xffff_ffffL)
        return ByteArray(60).also {
            it[0] = 2; it[1] = 1; put32(it, 4, lifetime)
            mapped(client).copyInto(it, 8); nonce.copyInto(it, 24)
            it[36] = 17; put16(it, 40, port); put16(it, 42, suggested?.port ?: 0)
            mapped(suggested?.address ?: InetAddress.getByAddress(ByteArray(if (client is Inet4Address) 4 else 16))).copyInto(it, 44)
        }
    }

    fun pcpResponse(bytes: ByteArray, request: ByteArray): Grant? {
        if (bytes.size !in 60..1100 || bytes.size % 4 != 0 || bytes[0].toInt() != 2 || u8(bytes[1]) != 129 || bytes[3].toInt() != 0
            || !bytes.copyOfRange(24, 36).contentEquals(request.copyOfRange(24, 36)) || bytes[36] != request[36]
            || u16(bytes, 40) != u16(request, 40)) return null
        val lifetime = u32(bytes, 4)
        val port = u16(bytes, 42)
        val ip = InetAddress.getByAddress(bytes.copyOfRange(44, 60))
        if (lifetime == 0L && u32(request, 4) != 0L || port == 0 || ip.isAnyLocalAddress || ip.isMulticastAddress) return null
        return Grant(InetSocketAddress(ip, port), lifetime)
    }

    fun pcp(io: MappingIo, gateways: List<InetSocketAddress>, port: Int, lifetime: Long): RouterLease? {
        for (gateway in gateways) {
            if (io.isClosed) return null
            try {
                DatagramSocket().use socketBlock@{ socket ->
                    socket.connect(gateway)
                    val client = socket.localAddress
                    val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
                    val request = pcpRequest(nonce, client, port, lifetime)
                    var adopted = false
                    val grant = try {
                        io.use(socket) { exchange(socket, request) { pcpResponse(it, request) } }?.also { adopted = true }
                    } finally {
                        // The router may have granted a request whose reply was lost or
                        // cancelled. Its nonce can retire that unobserved lease too.
                        if (!adopted) try { releaseUdp(gateway, client, pcpRequest(nonce, client, port, 0)) } catch (_: IOException) { }
                    } ?: return@socketBlock
                    return PcpLease(io, gateway, client, nonce, port, grant)
                }
            } catch (_: IOException) { }
        }
        return null
    }

    private class PcpLease(val io: MappingIo, val gateway: InetSocketAddress, val client: InetAddress,
        val nonce: ByteArray, val port: Int, var grant: Grant) : RouterLease {
        override val external get() = grant.external
        override val lifetimeSeconds get() = grant.lifetime
        override fun renew(): Boolean {
            val request = pcpRequest(nonce, client, port, lifetimeSeconds, external)
            val updated = connected(io, gateway, client) { exchange(it, request) { response -> pcpResponse(response, request) } } ?: return false
            grant = updated; return true
        }
        override fun release() = releaseUdp(gateway, client, pcpRequest(nonce, client, port, 0, external))
    }

    fun natPmpRequest(port: Int, lifetime: Long, suggestedPort: Int = 0) = ByteArray(12).also {
        require(port in 1..65535 && suggestedPort in 0..65535 && lifetime in 0..0xffff_ffffL)
        it[1] = 1; put16(it, 4, port); put16(it, 6, suggestedPort); put32(it, 8, lifetime)
    }

    fun natPmpResponse(bytes: ByteArray, request: ByteArray, ip: InetAddress): Grant? {
        if (bytes.size != 16 || bytes[0].toInt() != 0 || u8(bytes[1]) != 129 || u16(bytes, 2) != 0
            || u16(bytes, 8) != u16(request, 4) || u16(bytes, 10) == 0) return null
        val lifetime = u32(bytes, 12)
        if (lifetime == 0L && u32(request, 8) != 0L) return null
        return Grant(InetSocketAddress(ip, u16(bytes, 10)), lifetime)
    }

    fun natPmp(io: MappingIo, gateways: List<InetSocketAddress>, port: Int, lifetime: Long): RouterLease? {
        for (gateway in gateways.filter { it.address is Inet4Address }) {
            if (io.isClosed) return null
            try {
                DatagramSocket().use socketBlock@{ socket ->
                    socket.connect(gateway)
                    val client = socket.localAddress
                    var attempted = false
                    var adopted = false
                    val grant = try {
                        io.use(socket) {
                            val ip = pmpAddress(socket) ?: return@use null
                            val request = natPmpRequest(port, lifetime)
                            attempted = true
                            exchange(socket, request) { natPmpResponse(it, request, ip) }?.also { adopted = true }
                        }
                    } finally {
                        if (attempted && !adopted) try { releaseUdp(gateway, client, natPmpRequest(port, 0)) } catch (_: IOException) { }
                    } ?: return@socketBlock
                    return PmpLease(io, gateway, client, port, grant)
                }
            } catch (_: IOException) { }
        }
        return null
    }

    private fun pmpAddress(socket: DatagramSocket): InetAddress? = exchange(socket, byteArrayOf(0, 0)) {
        if (it.size != 12 || it[0].toInt() != 0 || u8(it[1]) != 128 || u16(it, 2) != 0) null
        else InetAddress.getByAddress(it.copyOfRange(8, 12)).takeUnless { ip -> ip.isAnyLocalAddress || ip.isMulticastAddress }
    }

    private class PmpLease(val io: MappingIo, val gateway: InetSocketAddress, val client: InetAddress,
        val port: Int, var grant: Grant) : RouterLease {
        override val external get() = grant.external
        override val lifetimeSeconds get() = grant.lifetime
        override fun renew(): Boolean {
            val updated = connected(io, gateway, client) { socket ->
                val ip = pmpAddress(socket) ?: return@connected null
                val request = natPmpRequest(port, lifetimeSeconds, external.port)
                exchange(socket, request) { natPmpResponse(it, request, ip) }
            } ?: return false
            grant = updated; return true
        }
        override fun release() = releaseUdp(gateway, client, natPmpRequest(port, 0, external.port))
    }

    private fun <T> connected(io: MappingIo, gateway: InetSocketAddress, client: InetAddress, call: (DatagramSocket) -> T): T =
        DatagramSocket(InetSocketAddress(client, 0)).let { socket ->
            io.use(socket) { socket.connect(gateway); call(socket) }
        }

    private fun releaseUdp(gateway: InetSocketAddress, client: InetAddress, request: ByteArray) {
        DatagramSocket(InetSocketAddress(client, 0)).use { socket ->
            socket.connect(gateway); socket.send(DatagramPacket(request, request.size))
        }
    }

    private fun <T> exchange(socket: DatagramSocket, request: ByteArray, parse: (ByteArray) -> T?): T? {
        val buffer = ByteArray(1101)
        for (timeout in intArrayOf(250, 500, 1000)) {
            socket.send(DatagramPacket(request, request.size))
            val end = System.nanoTime() + timeout * 1_000_000L
            while (System.nanoTime() < end) {
                socket.soTimeout = maxOf(1, ((end - System.nanoTime()) / 1_000_000).toInt())
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    parse(buffer.copyOf(packet.length))?.let { return it }
                    val pcpError = request[0].toInt() == 2 && packet.length >= 24 && buffer[0].toInt() == 2 &&
                        u8(buffer[1]) == 129 && buffer[3].toInt() != 0
                    val pmpError = request[0].toInt() == 0 && packet.length >= 8 && buffer[0].toInt() == 0 &&
                        u8(buffer[1]) == u8(request[1]) + 128 && u16(buffer, 2) != 0
                    if (pcpError || pmpError) return null
                } catch (_: SocketTimeoutException) { break }
            }
        }
        return null
    }

    private fun mapped(ip: InetAddress): ByteArray = if (ip is Inet4Address) ByteArray(16).also {
        it[10] = 0xff.toByte(); it[11] = 0xff.toByte(); ip.address.copyInto(it, 12)
    } else ip.address
    fun u8(b: Byte) = b.toInt() and 255
    fun u16(b: ByteArray, at: Int) = (u8(b[at]) shl 8) or u8(b[at + 1])
    fun u32(b: ByteArray, at: Int): Long = (0..3).fold(0L) { n, i -> (n shl 8) or u8(b[at + i]).toLong() }
    fun put16(b: ByteArray, at: Int, n: Int) { b[at] = (n ushr 8).toByte(); b[at + 1] = n.toByte() }
    fun put32(b: ByteArray, at: Int, n: Long) { for (i in 0..3) b[at + i] = (n ushr (24 - i * 8)).toByte() }
}

internal object RouteGateways {
    // Linux JVM fallback only. Android uses ConnectivityManager instead of restricted /proc.
    fun linux(): List<InetSocketAddress> = try {
        File("/proc/net/route").useLines { lines -> lines.drop(1).mapNotNull { line ->
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 8 || fields[1] != "00000000" || ((fields[3].toIntOrNull(16) ?: 0) and 3) != 3) null
            else fields[2].toLongOrNull(16)?.takeIf { it != 0L }?.let { value ->
                InetSocketAddress(InetAddress.getByAddress(ByteArray(4) { i -> (value ushr (i * 8)).toByte() }), 5351)
            }
        }.distinct().take(4).toList() }
    } catch (_: Exception) { emptyList() }
}
