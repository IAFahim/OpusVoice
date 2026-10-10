package pinhole

import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.bouncycastle.crypto.digests.Blake3Digest
import org.bouncycastle.crypto.params.Blake3Parameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

sealed interface IrohPath {
    data class Direct(val address: InetSocketAddress) : IrohPath {
        override fun equals(other: Any?): Boolean = other is Direct && sameScopedEndpoint(address, other.address)
        override fun hashCode(): Int = scopedEndpointHash(address)
    }
    data class DirectTcp(val address: InetSocketAddress, val connectionId: Long) : IrohPath {
        override fun equals(other: Any?): Boolean = other is DirectTcp && connectionId == other.connectionId &&
            sameScopedEndpoint(address, other.address)
        override fun hashCode(): Int = 31 * scopedEndpointHash(address) + connectionId.hashCode()
    }
    data class Relay(val url: URI, val endpointId: String) : IrohPath
}

// InetSocketAddress equality does not preserve the receiving interface's IPv6
// scope. Equal link-local bytes on two LAN interfaces are different routes.
private fun sameScopedEndpoint(a: InetSocketAddress, b: InetSocketAddress): Boolean =
    a == b && (a.address as? Inet6Address)?.scopeId == (b.address as? Inet6Address)?.scopeId
private fun scopedEndpointHash(address: InetSocketAddress): Int =
    31 * address.hashCode() + ((address.address as? Inet6Address)?.scopeId ?: 0)

data class IrohDatagram(val path: IrohPath, val payload: ByteArray)

internal val irohHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
    .callTimeout(5, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()

internal class IrohIdentity(seed: ByteArray = Crypto.randomPrivateKey()) {
    private val secret = Ed25519PrivateKeyParameters(seed, 0)
    val key: ByteArray = secret.generatePublicKey().encoded
    val endpointId = IrohEncoding.hex(key)
    val peerId: ULong = readULongLe(MessageDigest.getInstance("SHA-256").digest(key), 0)

    fun authenticate(challenge: ByteArray): ByteArray {
        require(challenge.size == 16)
        val hash = Blake3Digest()
        hash.init(Blake3Parameters.context("iroh-relay handshake v1 challenge signature".toByteArray(Charsets.UTF_8)))
        hash.update(challenge, 0, challenge.size)
        val message = ByteArray(32)
        hash.doFinal(message, 0)
        val signer = Ed25519Signer()
        signer.init(true, secret)
        signer.update(message, 0, message.size)
        val frame = ByteArray(98)
        frame[0] = 1
        key.copyInto(frame, 1)
        frame[33] = 64
        signer.generateSignature().copyInto(frame, 34)
        return frame
    }
}

/** Native iroh discovery/relay packet layer, without application framing. The
 * packet protocol above this socket authenticates peers and validates paths. */
class IrohTransport(secretKeySeed: ByteArray? = null) : Closeable {
    internal val identity = IrohIdentity(secretKeySeed ?: Crypto.randomPrivateKey())
    val endpointId: String get() = identity.endpointId
    val localPort: Int get() = socket.localPort
    private val socket = DatagramSocket()
    private val closed = AtomicBoolean(false)
    private val received = LinkedBlockingDeque<IrohDatagram>(256)
    private val end = IrohDatagram(IrohPath.Direct(InetSocketAddress(0)), ByteArray(0))
    private val relays = mutableMapOf<URI, IrohRelay>()
    private class StunProbe(val server: InetSocketAddress, val request: ByteArray) {
        val ready = CountDownLatch(1)
        @Volatile var mapped: InetSocketAddress? = null
    }
    private val stunPending = ConcurrentHashMap<String, StunProbe>()
    private val scheduler = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "iroh-relay-reconnect").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val tcp = TcpTransport(scheduler, ::enqueue)
    private val receiver = thread(isDaemon = true, name = "iroh-udp-recv") {
        val buffer = ByteArray(65535)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!closed.get()) {
            try {
                packet.length = buffer.size // DatagramSocket otherwise truncates to the previous packet's length.
                socket.receive(packet)
                val source = packet.socketAddress as InetSocketAddress
                val payload = buffer.copyOf(packet.length)
                val probe = Stun.transaction(payload)?.let { stunPending[it] }
                if (probe != null && source == probe.server) {
                    Stun.mappedAddress(payload, probe.request)?.let { probe.mapped = it; probe.ready.countDown() }
                } else enqueue(IrohDatagram(IrohPath.Direct(source), payload))
            } catch (_: IOException) { if (closed.get()) break }
        }
    }

    fun prepare(address: IrohAddress): List<IrohPath> {
        check(!closed.get()) { "iroh transport is closed" }
        address.relayUrls.forEach { relay(it) }
        return address.directAddresses.map { IrohPath.Direct(it) } +
            address.relayUrls.map { IrohPath.Relay(it, address.endpointId) }
    }

    fun sendTo(path: IrohPath, payload: ByteArray): Boolean {
        check(!closed.get()) { "iroh transport is closed" }
        return when (path) {
            is IrohPath.Direct -> {
                require(payload.size <= 65507)
                socket.send(DatagramPacket(payload, payload.size, path.address))
                true
            }
            is IrohPath.Relay -> {
                require(payload.size in 1..65502)
                relay(path.url).send(IrohEncoding.key(path.endpointId), payload)
            }
            is IrohPath.DirectTcp -> tcp.send(path, payload)
        }
    }

    internal fun dialTcp(address: InetSocketAddress): IrohPath.DirectTcp? = tcp.dial(address)
    internal fun tcpReady(path: IrohPath.DirectTcp): Boolean = tcp.isReady(path)
    internal fun tcpAuthenticated(path: IrohPath.DirectTcp): Boolean = tcp.isAuthenticated(path)
    internal fun beginTcpProof(path: IrohPath.DirectTcp): Long? = tcp.beginProof(path)
    internal fun confirmTcpProof(path: IrohPath.DirectTcp, nonce: Long): Boolean = tcp.confirmProof(path, nonce)
    internal fun observeTcpChallenge(path: IrohPath.DirectTcp, nonce: Long) = tcp.observeChallenge(path, nonce)
    internal fun compareTcpProofs(a: IrohPath.DirectTcp, b: IrohPath.DirectTcp): Int = tcp.compareProofs(a, b)
    internal fun closeTcp(path: IrohPath.DirectTcp) = tcp.close(path)
    internal fun tcpPaths(): List<IrohPath.DirectTcp> = tcp.paths()

    fun receive(timeoutMs: Long = 1000): IrohDatagram? {
        require(timeoutMs in 0..60_000)
        if (closed.get()) return null
        val packet = received.poll(timeoutMs, TimeUnit.MILLISECONDS)
        if (packet === end) { received.offer(end); return null }
        return if (closed.get()) null else packet
    }

    /** Probes from the session socket, without stealing datagrams from the receive loop. */
    internal fun probeStun(server: InetSocketAddress, timeoutMs: Long = 3000): InetSocketAddress? {
        require(!server.isUnresolved && timeoutMs in 1..10_000)
        if (closed.get()) return null
        val request = Stun.request()
        val key = Stun.transaction(request)!!
        val probe = StunProbe(server, request)
        stunPending[key] = probe
        try {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            var waitMs = 500L
            while (!closed.get()) {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) break
                socket.send(DatagramPacket(request, request.size, server))
                if (probe.ready.await(minOf(waitMs, remaining), TimeUnit.MILLISECONDS)) break
                waitMs = minOf(waitMs * 2, 2000)
            }
            return probe.mapped
        } catch (_: IOException) { return null }
        finally { stunPending.remove(key, probe) }
    }

    private fun enqueue(packet: IrohDatagram) {
        if (closed.get()) return
        if (!received.offer(packet)) { received.poll(); received.offer(packet) }
    }

    private fun relay(url: URI): IrohRelay = synchronized(relays) {
        check(!closed.get()) { "iroh transport is closed" }
        relays.getOrPut(validateIrohUrl(url)) {
            if (relays.size >= 64) throw IOException("iroh relay connection limit reached")
            IrohRelay(url, identity, scheduler) { source, bytes ->
                if (org.bouncycastle.math.ec.rfc8032.Ed25519.validatePublicKeyFull(source, 0))
                    enqueue(IrohDatagram(IrohPath.Relay(url, IrohEncoding.hex(source)), bytes))
            }.also { it.start() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket.close()
        tcp.close()
        stunPending.values.forEach { it.ready.countDown() }
        synchronized(relays) { relays.values.forEach { it.close() }; relays.clear() }
        scheduler.shutdownNow()
        receiver.interrupt()
        received.clear()
        received.offer(end)
    }
}

private class IrohRelay(
    private val url: URI,
    private val identity: IrohIdentity,
    private val scheduler: ScheduledThreadPoolExecutor,
    private val received: (ByteArray, ByteArray) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var generation = 0
    private var failures = 0
    @Volatile private var socket: WebSocket? = null
    @Volatile private var alive = false

    fun start() {
        synchronized(lock) {
            if (closed.get()) return
            val current = ++generation
            var phase = 0
            val wsUrl = url.resolve("/relay").toASCIIString()
                .replaceFirst(if (url.scheme == "https") "https:" else "http:", if (url.scheme == "https") "wss:" else "ws:")
            val request = Request.Builder().url(wsUrl)
                .header("Sec-WebSocket-Protocol", "iroh-relay-v2, iroh-relay-v1").build()
            socket = irohHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (current != generation || closed.get()) { webSocket.cancel(); return }
                    if (response.header("Sec-WebSocket-Protocol") !in listOf("iroh-relay-v1", "iroh-relay-v2"))
                        retry(current, webSocket)
                    scheduler.schedule({
                        if (current == generation && !alive && !closed.get()) retry(current, webSocket)
                    }, 10, TimeUnit.SECONDS)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (current != generation || closed.get()) return
                    try {
                        val frame = bytes.toByteArray()
                        require(frame.isNotEmpty() && frame.size <= 1024 * 1024)
                        if (phase == 0) {
                            require(frame.size == 17 && frame[0].toInt() == 0) { "invalid relay challenge" }
                            check(webSocket.send(identity.authenticate(frame.copyOfRange(1, 17)).toByteString()))
                            phase = 1
                        } else if (phase == 1) {
                            require(frame.size == 1 && frame[0].toInt() == 2) { "relay rejected identity" }
                            failures = 0
                            alive = true
                            phase = 2
                        } else when (frame[0].toInt() and 255) {
                            9 -> {
                                require(frame.size == 9)
                                frame[0] = 10
                                check(webSocket.send(frame.toByteString()))
                            }
                            6, 7 -> {
                                val batched = frame[0].toInt() == 7
                                val header = if (batched) 36 else 34
                                require(frame.size >= header)
                                val source = frame.copyOfRange(1, 33)
                                val size = if (batched) ((frame[34].toInt() and 255) shl 8) or (frame[35].toInt() and 255) else frame.size - header
                                require(!batched || size > 0)
                                if (size == 0) received(source, ByteArray(0))
                                else {
                                    var offset = header
                                    while (offset < frame.size) {
                                        val end = minOf(frame.size, offset + size)
                                        received(source, frame.copyOfRange(offset, end))
                                        offset = end
                                    }
                                }
                            }
                            12 -> retry(current, webSocket)
                            13 -> if (frame.size == 2 && frame[1].toInt() == 1) retry(current, webSocket)
                        }
                    } catch (_: Exception) { retry(current, webSocket) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) { retry(current, webSocket) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { retry(current, webSocket) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { retry(current, webSocket) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { retry(current, webSocket) }
            })
        }
    }

    private fun retry(current: Int, webSocket: WebSocket) {
        synchronized(lock) {
            if (current != generation || closed.get()) return
            generation++
            alive = false
            webSocket.cancel()
            val delay = minOf(1000L shl minOf(failures++, 5), 30_000L)
            val jitter = 0.9 + java.security.SecureRandom().nextDouble() * 0.2
            scheduler.schedule({ start() }, (delay * jitter).toLong(), TimeUnit.MILLISECONDS)
        }
    }

    fun send(key: ByteArray, payload: ByteArray): Boolean {
        if (!alive || closed.get()) return false
        val ws = socket ?: return false
        if (ws.queueSize() > 1024 * 1024) return false
        val frame = ByteArray(34 + payload.size)
        frame[0] = 4
        key.copyInto(frame, 1)
        payload.copyInto(frame, 34)
        return ws.send(frame.toByteString())
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        alive = false
        socket?.cancel()
    }
}
