package pinhole

import java.io.Closeable
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Encrypted Pinhole session over direct UDP/TCP or native iroh relay transport.
 * Accepts pinhole1 connection strings, native endpoint tickets, and endpoint IDs.
 * Native IDs/tickets require signed discovery advertising a Pinhole static-key binding.
 */
class PinholeDialer(
    private val ticket: String,
    private val connectTimeoutMs: Long = 15_000,
    discoveryUrl: URI = URI("https://dns.iroh.link/pkarr"),
    secretKeySeed: ByteArray? = null,
    private val relayOnly: Boolean = false,
    private val stunServers: List<InetSocketAddress>? = null,
    private val portMapping: PortMappingOptions = PortMappingOptions(),
    private val enableTcp: Boolean = true,
    private val tcpOnly: Boolean = false,
    private val publishDirectAddresses: Boolean = true,
) : Closeable {
    var onConnected: ((InetSocketAddress) -> Unit)? = null
    var onClosed: ((String) -> Unit)? = null
    var onReceived: ((ByteArray) -> Unit)? = null
    var onPathChanged: ((IrohPath) -> Unit)? = null
    var debug: Boolean = false
    var lastRttMs: Long = -1
        private set

    private val transport = IrohTransport(secretKeySeed)
    private val discovery = IrohDiscovery(irohHttpClient, discoveryUrl)
    private val myPeerId = transport.identity.peerId
    private val myToken = Crypto.randomToken()
    private val ephPrivate = Crypto.randomPrivateKey()
    private val staticPrivate = Crypto.randomPrivateKey()
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val settled = CountDownLatch(1)
    private lateinit var cs: ConnectionString
    @Volatile private var paths: List<IrohPath> = emptyList()
    @Volatile private var established = false
    @Volatile private var handshakeReady = false
    @Volatile private var peerPath: IrohPath? = null
    @Volatile private var failure: String? = null
    @Volatile private var lastSendNanos = 0L
    @Volatile private var lastReceiveNanos = 0L
    private var lastProbeNanos = 0L
    private var lastResolveNanos = 0L
    private var lastDirectProbeNanos = 0L
    private var lastDirectReceiveNanos = 0L
    private var lastPathReceiveNanos = 0L
    private var connectStartedNanos = 0L
    private var lastTcpAttemptNanos = 0L
    private var tcpCandidateCursor = 0
    private val pendingPings = linkedMapOf<Pair<IrohPath, ULong>, Long>()
    private var nativeDiscovery = false
    private var remoteToken = 0
    private var remoteTokenAuthenticated = false
    private var recvThread: Thread? = null
    private var punchThread: Thread? = null
    private var candidateThread: Thread? = null
    private var portMapper: RouterPortMapper? = null
    @Volatile private var mappedEndpoint: InetSocketAddress? = null
    val portMappedEndpoint: InetSocketAddress? get() = mappedEndpoint
    private val candidateWorkers = Executors.newFixedThreadPool(3) { task ->
        Thread(task, "pinhole-stun").apply { isDaemon = true }
    }
    @Volatile private var localCandidates: List<PinholeCandidate> = emptyList()
    private var lastAnnouncement: ByteArray? = null
    private var lastAnnounceNanos = 0L
    private var announcementAttempts = 0
    private var sendSealer: FrameSealer? = null
    private var recvSealer: FrameSealer? = null
    private var acceptedPack: ByteArray? = null
    private var hsckFrame: ByteArray? = null
    private var answerPunc: ByteArray? = null

    private val puncFrame = prefix(FRAME_PUNC, myToken, PREFIX + 64).also {
        Crypto.publicKey(ephPrivate).copyInto(it, PREFIX)
        Crypto.publicKey(staticPrivate).copyInto(it, PREFIX + 32)
    }

    val isConnected: Boolean get() = established && !closed.get()
    val connectedPath: IrohPath? get() = peerPath

    /** Blocks on a worker thread until an authenticated reply confirms the peer's session.
     * For relay connections the return value describes the relay server; connectedPath
     * carries the actual identity-addressed path. */
    fun connect(): InetSocketAddress {
        check(started.compareAndSet(false, true)) { "connect may only be called once" }
        check(!closed.get()) { "dialer is closed" }
        require(connectTimeoutMs in 1..120_000)
        require(!tcpOnly || enableTcp && !relayOnly) { "TCP-only requires TCP enabled and relay-only disabled" }
        connectStartedNanos = System.nanoTime()
        val deadline = connectStartedNanos + connectTimeoutMs * 1_000_000
        try {
            val native = IrohAddress.tryParse(ticket)
            cs = if (native == null) ConnectionString.parse(ticket) else {
                nativeDiscovery = true
                val address = native
                val record = discovery.resolve(address.endpointId)
                fromDiscovery(address, record)
            }
            require(cs.staticKey != null) { "a pinned Pinhole static key is required" }
            paths = pathsFor(cs)
            require(paths.isNotEmpty()) { "no supported IP or iroh relay candidates" }
            check(!closed.get()) { "dialer was closed while resolving" }
            lastReceiveNanos = System.nanoTime()
            recvThread = thread(isDaemon = true, name = "pinhole-recv") { receiveLoop() }
            if (!relayOnly && publishDirectAddresses && portMapping.enabled) {
                portMapper = RouterPortMapper(transport.localPort, portMapping) { endpoint ->
                    if (!closed.get()) mappedEndpoint = endpoint
                }
            }
            if (!relayOnly && publishDirectAddresses) candidateThread = thread(isDaemon = true, name = "pinhole-candidates") { discoverCandidates() }
            punchThread = thread(isDaemon = true, name = "pinhole-maintenance") {
                try {
                    while (!closed.get()) {
                        raceTcp(System.nanoTime())
                        if (!established) {
                            if (System.nanoTime() >= deadline) {
                                finish("connect timeout after " + connectTimeoutMs + "ms")
                                break
                            }
                            if (handshakeReady) {
                                peerPath?.let { sendHandshakeAck(it) }
                            } else paths.filter { !tcpOnly || it !is IrohPath.Direct }.forEach { sendRaw(it, puncFrame) }
                        } else maintain()
                        Thread.sleep(200)
                    }
                } catch (_: InterruptedException) {
                    if (!closed.get()) finish("connection interrupted")
                } catch (e: Exception) { finish("connection failed: " + e.message) }
            }
            val remaining = maxOf(0L, deadline - System.nanoTime())
            if (!settled.await(remaining, TimeUnit.NANOSECONDS)) finish("connect timeout after " + connectTimeoutMs + "ms")
            check(isConnected) { failure ?: "pinhole connection failed" }
            return displayAddress(peerPath ?: error("connected without a path"))
        } catch (e: Exception) {
            finish(e.message ?: "connection failed")
            throw e
        }
    }

    fun send(payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset) {
        check(isConnected) { "pinhole not connected" }
        require(offset >= 0 && length >= 0 && offset <= payload.size - length)
        require(length in 1..1200) { "Pinhole session datagrams must contain 1..1200 bytes" }
        sendSealed(FRAME_DATA, payload.copyOfRange(offset, offset + length), peerPath ?: error("missing path"))
    }

    override fun close() {
        if (isConnected) try { sendSealed(FRAME_BYE, ByteArray(0), peerPath ?: return) } catch (_: Exception) {}
        finish("closed")
    }

    private fun finish(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        failure = reason
        established = false
        portMapper?.close()
        mappedEndpoint = null
        discovery.close()
        transport.close()
        candidateWorkers.shutdownNow()
        candidateThread?.interrupt()
        punchThread?.interrupt()
        recvThread?.interrupt()
        settled.countDown()
        onClosed?.invoke(reason)
    }

    private fun receiveLoop() {
        try {
            while (!closed.get()) {
                val packet = transport.receive(1000) ?: continue
                handleFrame(packet.payload, packet.path)
            }
        } catch (_: InterruptedException) {
            if (!closed.get()) finish("receive interrupted")
        } catch (e: Exception) { finish("receive failed: " + e.message) }
    }

    private fun handleFrame(frame: ByteArray, from: IrohPath) {
        if (relayOnly && from !is IrohPath.Relay || tcpOnly && from !is IrohPath.DirectTcp) return
        if (frame.size < PREFIX || readULongLe(frame, 1) != cs.peerId) return
        if (from is IrohPath.Relay) {
            val endpoint = cs.endpointKey ?: cs.candidates.firstOrNull { it.kind == CandidateKind.IrohRelay }?.relayKey ?: return
            if (from.endpointId != IrohEncoding.hex(endpoint)) return
        }
        when (frame[0].toInt() and 255) {
            FRAME_PACK -> handlePack(frame, from)
            FRAME_PUNC -> handlePunc(frame, from)
            else -> {
                if (!handshakeReady || remoteTokenAuthenticated && readIntLe(frame, 9) != remoteToken) return
                val plaintext = recvSealer?.open(frame, frame.size) ?: return
                val pingSentAt = if (frame[0].toInt() and 255 == FRAME_PONG && plaintext.size >= 8)
                    synchronized(pendingPings) { pendingPings.remove(from to readULongLe(plaintext, 0)) } else null
                // PACK confirms the keys, while sealed AAD authenticates the token.
                // Correct an edited first PACK only after the genuine frame opens.
                remoteToken = readIntLe(frame, 9)
                remoteTokenAuthenticated = true
                answerPunc?.let { writeIntLe(it, 9, remoteToken) }
                val type = frame[0].toInt() and 255
                if (from is IrohPath.DirectTcp && !transport.tcpAuthenticated(from)) {
                    when (type) {
                        FRAME_PING -> if (plaintext.size >= 8) {
                            transport.observeTcpChallenge(from, readULongLe(plaintext, 0).toLong())
                            sendTcpProof(from)
                            sendSealed(FRAME_PONG, plaintext.copyOf(8), from)
                        }
                        FRAME_PONG -> {
                            if (plaintext.size < 8 || !transport.confirmTcpProof(from, readULongLe(plaintext, 0).toLong())) return
                        }
                    }
                    if (!transport.tcpAuthenticated(from)) return
                }
                val now = System.nanoTime()
                lastReceiveNanos = now
                if (from is IrohPath.Direct) lastDirectReceiveNanos = now
                // Prefer a validated direct path while it is alive. A late relay reply
                // must not immediately switch a healthy direct session back to relay.
                val healthyUdp = peerPath is IrohPath.Direct && now - lastDirectReceiveNanos <= 5_000_000_000L
                val current = peerPath
                if (current == from) lastPathReceiveNanos = now
                when (from) {
                    is IrohPath.Direct -> adoptPath(from)
                    is IrohPath.DirectTcp -> if (!healthyUdp) {
                        if (current is IrohPath.DirectTcp && current != from && transport.tcpReady(current) &&
                            now - lastPathReceiveNanos <= 5_000_000_000L &&
                            transport.compareTcpProofs(from, current) >= 0) transport.closeTcp(from)
                        else adoptPath(from)
                    }
                    is IrohPath.Relay -> if (!healthyUdp &&
                        (current !is IrohPath.DirectTcp || !transport.tcpReady(current) || now - lastPathReceiveNanos > 5_000_000_000L) &&
                        (current !is IrohPath.Relay || current == from || now - lastPathReceiveNanos > 5_000_000_000L)) adoptPath(from)
                }
                if (!established && type != FRAME_BYE) {
                    established = true
                    settled.countDown()
                    onConnected?.invoke(displayAddress(from))
                }
                when (type) {
                    FRAME_DATA -> onReceived?.invoke(plaintext)
                    FRAME_PING -> if (plaintext.size >= 8) sendSealed(FRAME_PONG, plaintext.copyOf(8), from)
                    FRAME_PONG -> if (pingSentAt != null) {
                        lastRttMs = maxOf(0, (System.nanoTime() - pingSentAt) / 1_000_000)
                    }
                    FRAME_ANNOUNCE -> try {
                        val fresh = ConnectionString.readAnnouncement(plaintext)
                        val announced = ConnectionString(cs.peerId, cs.natHint, fresh, cs.staticKey, cs.endpointKey)
                        paths = (paths.filterIsInstance<IrohPath.Relay>() + pathsFor(announced)).distinct()
                        if (debug) System.err.println("pinhole: authenticated peer paths: " + paths)
                    } catch (e: IllegalArgumentException) {
                        if (debug) System.err.println("pinhole: rejected peer path hint: " + e.message)
                    }
                    FRAME_BYE -> finish("peer closed the session")
                }
            }
        }
    }

    private fun handlePack(frame: ByteArray, from: IrohPath) {
        if (frame.size != 97 || readIntLe(frame, 9) != myToken) return
        if (handshakeReady) {
            if (acceptedPack?.contentEquals(frame) == true) sendHandshakeAck(from)
            return
        }
        val pinned = cs.staticKey ?: return
        val peerEph = frame.copyOfRange(17, 49)
        val peerStatic = frame.copyOfRange(49, 81)
        if (!MessageDigest.isEqual(peerStatic, pinned)) return
        val keys = try {
            SessionKeys.derive(myPeerId, ephPrivate, staticPrivate, cs.peerId, peerEph, peerStatic)
        } catch (_: IllegalArgumentException) { return }
        val iAmLo = myPeerId < cs.peerId
        val expected = if (iAmLo) keys.hiConfirm else keys.loConfirm
        if (!MessageDigest.isEqual(frame.copyOfRange(81, 97), expected)) return

        remoteToken = readIntLe(frame, 13)
        sendSealer = FrameSealer(keys, iAmLo, sending = true)
        recvSealer = FrameSealer(keys, iAmLo, sending = false)
        hsckFrame = prefix(FRAME_HSCK, myToken, PREFIX + 16).also {
            (if (iAmLo) keys.loConfirm else keys.hiConfirm).copyInto(it, PREFIX)
        }
        answerPunc = prefix(FRAME_PACK, remoteToken, 97).also {
            for (i in 0 until 4) it[PREFIX + i] = (myToken ushr (i * 8)).toByte()
            puncFrame.copyInto(it, PREFIX + 4, PREFIX, PREFIX + 64)
            (if (iAmLo) keys.loConfirm else keys.hiConfirm).copyInto(it, 81)
        }
        acceptedPack = frame.copyOf()
        peerPath = from
        handshakeReady = true
        sendHandshakeAck(from)
    }

    /** Reply to the established peer's reverse punches. Promotion still requires an
     * authenticated sealed reply, so a replayed plaintext PUNC cannot select a path. */
    private fun handlePunc(frame: ByteArray, from: IrohPath) {
        val accepted = acceptedPack ?: return
        if (!handshakeReady || frame.size != PREFIX + 64 || readIntLe(frame, 9) != remoteToken ||
            !MessageDigest.isEqual(frame.copyOfRange(PREFIX, PREFIX + 64), accepted.copyOfRange(17, 81))) return
        answerPunc?.let { sendRaw(from, it) }
        if (from is IrohPath.DirectTcp) sendTcpProof(from)
        else try { sendSealed(FRAME_PING, uLongLe(System.nanoTime().toULong()), from) } catch (_: IOException) {}
    }

    private fun sendHandshakeAck(path: IrohPath) {
        hsckFrame?.let { sendRaw(path, it) }
        if (path is IrohPath.DirectTcp) sendTcpProof(path)
        else try { sendSealed(FRAME_PING, uLongLe(System.nanoTime().toULong()), path) } catch (_: IOException) {}
    }

    private fun sendTcpProof(path: IrohPath.DirectTcp) {
        if (!handshakeReady) return
        val challenge = transport.beginTcpProof(path) ?: return
        try { sendSealed(FRAME_PING, uLongLe(challenge.toULong()), path) }
        catch (_: IOException) { transport.closeTcp(path) }
    }

    private fun adoptPath(path: IrohPath) {
        lastPathReceiveNanos = System.nanoTime()
        if (peerPath == path) return
        peerPath = path
        onPathChanged?.invoke(path)
    }

    /** Bounded fallback on advertised endpoints only; never replace healthy UDP. */
    private fun raceTcp(now: Long) {
        if (!enableTcp || relayOnly || !tcpOnly && now - connectStartedNanos < 1_200_000_000L) return
        if (established && peerPath is IrohPath.Direct && now - lastDirectReceiveNanos <= 5_000_000_000L) {
            transport.tcpPaths().forEach { transport.closeTcp(it) }
            return
        }
        val targets = paths.filterIsInstance<IrohPath.Direct>().distinct().take(8).map { it.address }
        if (targets.isNotEmpty() && (lastTcpAttemptNanos == 0L || now - lastTcpAttemptNanos >= 5_000_000_000L)) {
            lastTcpAttemptNanos = now
            repeat(minOf(4, targets.size)) {
                transport.dialTcp(targets[tcpCandidateCursor % targets.size])
                tcpCandidateCursor++
            }
        }
        transport.tcpPaths().forEach {
            if (transport.tcpReady(it) && !transport.tcpAuthenticated(it)) {
                sendRaw(it, puncFrame)
                sendTcpProof(it)
            }
        }
    }

    private fun sendRaw(path: IrohPath, frame: ByteArray): Boolean = try {
        transport.sendTo(path, frame)
    } catch (_: IOException) { false }

    private fun sendSealed(type: Int, body: ByteArray, path: IrohPath) {
        val sealer = sendSealer ?: throw IllegalStateException("handshake not ready")
        val frame = prefix(type, myToken, PREFIX + 24 + body.size)
        sealer.seal(frame, PREFIX, frame.copyOf(PREFIX), body)
        val pingKey = if (type == FRAME_PING && body.size >= 8) path to readULongLe(body, 0) else null
        if (pingKey != null) synchronized(pendingPings) {
            while (pendingPings.size >= 8) pendingPings.remove(pendingPings.keys.first())
            pendingPings[pingKey] = System.nanoTime()
        }
        if (!transport.sendTo(path, frame)) {
            if (pingKey != null) synchronized(pendingPings) { pendingPings.remove(pingKey) }
            throw IOException("transport is reconnecting or its queue is full")
        }
        lastSendNanos = System.nanoTime()
    }

    /** The worker calls this automatically; callers may also request an idle heartbeat. */
    fun keepaliveIfIdle(idleMs: Long = 5_000) {
        if (!isConnected || System.nanoTime() - lastSendNanos < idleMs * 1_000_000) return
        try { sendSealed(FRAME_PING, uLongLe(System.nanoTime().toULong()), peerPath ?: return) } catch (_: IOException) {}
    }

    private fun maintain() {
        val now = System.nanoTime()
        if (!relayOnly) announceCandidates(now)
        if (!relayOnly && !tcpOnly && peerPath !is IrohPath.Direct && now - lastDirectProbeNanos >= 1_000_000_000L) {
            lastDirectProbeNanos = now
            if (debug) System.err.println("pinhole: probing direct candidates: " + paths.filterIsInstance<IrohPath.Direct>())
            paths.filterIsInstance<IrohPath.Direct>().forEach {
                try { sendSealed(FRAME_PING, uLongLe(now.toULong()), it) } catch (_: IOException) {}
            }
        }
        keepaliveIfIdle()
        if (now - lastReceiveNanos <= 5_000_000_000L) return
        if (now - lastReceiveNanos > 60_000_000_000L) { finish("connection lost"); return }
        if (now - lastProbeNanos >= 1_000_000_000L) {
            lastProbeNanos = now
            paths.forEach {
                try { sendSealed(FRAME_PING, uLongLe(now.toULong()), it) } catch (_: IOException) {}
            }
        }
        if (nativeDiscovery && now - lastResolveNanos >= 15_000_000_000L) {
            lastResolveNanos = now
            try {
                val id = IrohEncoding.hex(cs.endpointKey ?: return)
                val fresh = fromDiscovery(IrohAddress(id), discovery.resolve(id))
                if (MessageDigest.isEqual(fresh.staticKey, cs.staticKey)) paths = pathsFor(fresh)
            } catch (_: Exception) {} // Keep racing known paths while discovery is unavailable.
        }
    }

    private fun pathsFor(peer: ConnectionString): List<IrohPath> = peer.candidates.flatMap {
        when (it.kind) {
            CandidateKind.Direct, CandidateKind.Reflexive -> if (relayOnly) emptyList() else directPaths(it.address)
            CandidateKind.IrohRelay -> {
                val key = it.relayKey ?: return@flatMap emptyList()
                require(derivePeerId(key) == peer.peerId) { "iroh relay identity does not match the Pinhole peer ID" }
                require(peer.endpointKey == null || MessageDigest.isEqual(key, peer.endpointKey))
                if (tcpOnly) emptyList() else listOf(IrohPath.Relay(it.relayUrl ?: return@flatMap emptyList(), IrohEncoding.hex(key)))
            }
            CandidateKind.Relay -> emptyList() // TURN uses a different transport.
        }
    }

    /** Try each local LAN scope; link-local addresses have no meaning on the mobile network. */
    private fun directPaths(address: InetSocketAddress): List<IrohPath.Direct> {
        val addr = address.address ?: return emptyList()
        if (addr !is Inet6Address || !addr.isLinkLocalAddress || addr.scopeId != 0) return listOf(IrohPath.Direct(address))
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { n -> n.isUp && !n.isLoopback && interfaceRank(n.name) <= 1 &&
                    n.interfaceAddresses.any { it.address.isLinkLocalAddress && it.address is Inet6Address } }
                .sortedBy { interfaceRank(it.name) }.take(4)
                .mapNotNull { nif -> try {
                    IrohPath.Direct(InetSocketAddress(Inet6Address.getByAddress(null, addr.address, nif), address.port))
                } catch (_: Exception) { null } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun hostCandidates(): List<PinholeCandidate> = try {
        val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { interfaceRank(it.name) }
        val addresses = interfaces.flatMap { n -> n.interfaceAddresses.map { n to it.address } }
        val routable = addresses.map { it.second }.filter { !it.isAnyLocalAddress && !it.isMulticastAddress && !it.isLinkLocalAddress }
        val linkLocal = addresses.filter { interfaceRank(it.first.name) <= 1 && it.second is Inet6Address && it.second.isLinkLocalAddress }
            .map { it.second }.take(2)
        (routable + linkLocal).distinct().take(8).map {
            PinholeCandidate(CandidateKind.Direct, InetSocketAddress(it, transport.localPort))
        }
    } catch (_: Exception) { emptyList() }

    /** Refresh after interface changes and once a minute for silent carrier NAT rebinding. */
    private fun discoverCandidates() {
        var previousHosts: ByteArray? = null
        var nextProbe = 0L
        try {
            while (!closed.get()) {
                val hosts = hostCandidates()
                val hostBody = ConnectionString.writeAnnouncement(hosts)
                val changed = previousHosts?.contentEquals(hostBody) != true
                if (changed || System.nanoTime() >= nextProbe) {
                    if (changed) {
                        localCandidates = hosts // stale mappings from the old network must not linger
                        if (previousHosts != null) {
                            mappedEndpoint = null
                            portMapper?.refresh()
                        }
                    }
                    previousHosts = hostBody
                    val probes: List<Callable<List<InetSocketAddress>>> = if (stunServers != null) {
                        stunServers.take(8).map { server -> Callable { listOfNotNull(transport.probeStun(server)) } }
                    } else listOf("stun.l.google.com" to 19302, "stun.cloudflare.com" to 3478, "global.stun.twilio.com" to 3478).map { (name, port) ->
                        Callable {
                            InetAddress.getAllByName(name).take(2).mapNotNull { ip -> transport.probeStun(InetSocketAddress(ip, port)) }
                        }
                    }
                    val observed = candidateWorkers.invokeAll(probes, 5, TimeUnit.SECONDS).flatMap { result ->
                        try { result.get() } catch (_: Exception) { emptyList() }
                    }.distinct()
                    if (closed.get()) break
                    localCandidates = hosts + observed.map { PinholeCandidate(CandidateKind.Reflexive, it) }
                    if (debug) System.err.println("pinhole: local UDP candidates: " + localCandidates.map { "${it.kind} ${it.address}" })
                    nextProbe = System.nanoTime() + 60_000_000_000L
                }
                Thread.sleep(5000)
            }
        } catch (_: InterruptedException) { } // shutdown cancels DNS/probes and the refresh loop
    }

    private fun announceCandidates(now: Long) {
        val relays = paths.filterIsInstance<IrohPath.Relay>().map { it.url }.distinct().map {
            PinholeCandidate(CandidateKind.IrohRelay, InetSocketAddress(InetAddress.getByAddress(ByteArray(4)), 0),
                it, transport.identity.key)
        }
        val mapping = mappedEndpoint?.let { listOf(PinholeCandidate(CandidateKind.Reflexive, it)) } ?: emptyList()
        val body = ConnectionString.writeAnnouncement((localCandidates + mapping + relays).distinct().take(ConnectionString.MAX_CANDIDATES))
        if (lastAnnouncement?.contentEquals(body) != true) {
            lastAnnouncement = body
            announcementAttempts = 0
            lastAnnounceNanos = 0
        }
        // Repeat the new snapshot to survive datagram loss; refresh occasionally while relayed.
        val interval = if (announcementAttempts < 3) 1_000_000_000L else 30_000_000_000L
        if (now - lastAnnounceNanos < interval) return
        try {
            sendSealed(FRAME_ANNOUNCE, body, peerPath ?: return)
            lastAnnounceNanos = now
            announcementAttempts++
        } catch (_: IOException) { }
    }

    private fun interfaceRank(name: String): Int = when {
        name.startsWith("wlan") || name.startsWith("wifi") -> 0
        name.startsWith("eth") || name.startsWith("usb") -> 1
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") ||
            name.startsWith("ppp") || name.startsWith("dummy") || name.startsWith("ap") -> 9
        else -> 5
    }

    private fun fromDiscovery(ticket: IrohAddress, record: IrohAddress): ConnectionString {
        require(ticket.endpointId == record.endpointId)
        val metadata = record.userData ?: throw IllegalArgumentException("endpoint has no signed Pinhole session key")
        require(metadata.startsWith(PROTOCOL_PREFIX) && metadata.length == PROTOCOL_PREFIX.length + 64) {
            "endpoint does not advertise the Pinhole session protocol"
        }
        val pin = IrohEncoding.unhex(metadata.substring(PROTOCOL_PREFIX.length))
        val placeholder = InetSocketAddress(InetAddress.getByAddress(ByteArray(4)), 0)
        val relays = (ticket.relayUrls + record.relayUrls).distinct().map {
            PinholeCandidate(CandidateKind.IrohRelay, placeholder, it, record.key)
        }
        val direct = (ticket.directAddresses + record.directAddresses).distinct().map {
            PinholeCandidate(CandidateKind.Direct, it)
        }
        return ConnectionString(derivePeerId(record.key), NatHint.Unknown,
            (relays + direct).take(ConnectionString.MAX_CANDIDATES), pin, record.key)
    }

    private fun prefix(type: Int, token: Int, size: Int): ByteArray = ByteArray(size).also {
        it[0] = type.toByte()
        uLongLe(myPeerId).copyInto(it, 1)
        writeIntLe(it, 9, token)
    }

    private fun displayAddress(path: IrohPath): InetSocketAddress = when (path) {
        is IrohPath.Direct -> path.address
        is IrohPath.DirectTcp -> path.address
        is IrohPath.Relay -> InetSocketAddress.createUnresolved(path.url.host,
            if (path.url.port > 0) path.url.port else if (path.url.scheme == "https") 443 else 80)
    }

    private fun derivePeerId(key: ByteArray) = readULongLe(MessageDigest.getInstance("SHA-256").digest(key), 0)

    private companion object {
        const val PROTOCOL_PREFIX = "pinhole-v1:"
        const val PREFIX = 13
        const val FRAME_PUNC = 0x50
        const val FRAME_PACK = 0x51
        const val FRAME_DATA = 0x52
        const val FRAME_PING = 0x53
        const val FRAME_PONG = 0x54
        const val FRAME_ANNOUNCE = 0x55
        const val FRAME_BYE = 0x56
        const val FRAME_HSCK = 0x57
    }
}
