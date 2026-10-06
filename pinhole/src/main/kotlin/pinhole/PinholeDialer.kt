package pinhole

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Minimal Pinhole dialer, wire-compatible with Pinhole.Net: punches the connection
 * string's candidates, completes the v2/v3 handshake (triple-DH X25519 with the peer's
 * static key pinned to the string), then exchanges AES-GCM sealed datagrams.
 *
 * Supports Direct and Reflexive candidates; TURN and iroh relay candidates are not
 * implemented (strings that carry only relay candidates are refused).
 */
class PinholeDialer(
    ticket: String,
    private val connectTimeoutMs: Long = 15_000,
) : Closeable {

    /** Fires on the receive thread once the handshake completes; carries the answered path. */
    var onConnected: ((InetSocketAddress) -> Unit)? = null

    /** Fires on the receive thread when the session ends (peer Bye, failure, or close). */
    var onClosed: ((String) -> Unit)? = null

    /** Fires on the receive thread for every decrypted datagram. */
    var onReceived: ((ByteArray) -> Unit)? = null

    var lastRttMs: Long = -1
        private set

    private val cs = ConnectionString.parse(ticket)
    private val myPeerId = Crypto.randomPeerId()
    private val myToken = Crypto.randomToken()
    private val ephPrivate = Crypto.randomPrivateKey()
    private val staticPrivate = Crypto.randomPrivateKey()

    private val socket = DatagramSocket()
    private val closed = AtomicBoolean(false)
    private var recvThread: Thread? = null
    private var punchThread: Thread? = null

    @Volatile private var established = false
    @Volatile private var remoteToken = 0
    @Volatile private var peerAddress: InetSocketAddress? = null
    @Volatile private var hsckSent = false
    @Volatile private var lastSendNanos = 0L
    private var sendSealer: FrameSealer? = null
    private var recvSealer: FrameSealer? = null

    private val puncFrame: ByteArray = ByteArray(PREFIX + 32 + 32).also { frame ->
        frame[0] = FRAME_PUNC.toByte()
        val peerId = uLongLe(myPeerId)
        System.arraycopy(peerId, 0, frame, 1, 8)
        writeIntLe(frame, 9, myToken)
        System.arraycopy(Crypto.publicKey(ephPrivate), 0, frame, PREFIX, 32)
        System.arraycopy(Crypto.publicKey(staticPrivate), 0, frame, PREFIX + 32, 32)
    }

    val isConnected: Boolean get() = established

    /**
     * Blocks until the handshake completes or the connect timeout elapses. Returns the
     * answered peer address. Call from a worker thread (Dispatchers.IO).
     */
    fun connect(): InetSocketAddress {
        require(cs.staticKey != null) {
            "connection string carries no static key (v1); this dialer requires a v2/v3 string"
        }
        val punchable = cs.candidates.filter { it.kind == CandidateKind.Direct || it.kind == CandidateKind.Reflexive }
        require(punchable.isNotEmpty()) {
            "no punchable candidates (only relay); relay dialing is not implemented"
        }

        recvThread = thread(isDaemon = true, name = "pinhole-recv") { receiveLoop() }
        val deadline = System.nanoTime() + connectTimeoutMs * 1_000_000
        punchThread = thread(isDaemon = true, name = "pinhole-punch") {
            while (!established && !closed.get() && System.nanoTime() < deadline) {
                for (candidate in punchable) {
                    if (established || closed.get()) break
                    try {
                        socket.send(DatagramPacket(puncFrame, puncFrame.size, candidate.address))
                    } catch (e: Exception) {
                        // one unroutable candidate must not kill the punch
                        System.err.println("pinhole: punch to ${'$'}{candidate.address} failed: ${'$'}e")
                    }
                }
                Thread.sleep(PUNCH_INTERVAL_MS)
            }
            if (!established && !closed.get()) {
                System.err.println("pinhole: connect timed out after ${'$'}connectTimeoutMs ms (punched ${'$'}{punchable.size} candidates)")
                fail("connect timeout after ${connectTimeoutMs}ms")
            }
        }

        punchThread?.join(connectTimeoutMs + 5_000)
        check(established) { "pinhole connection failed" }
        return peerAddress ?: error("connected without a peer address")
    }

    /** Sends one datagram through the established, encrypted session. */
    fun send(payload: ByteArray, offset: Int = 0, length: Int = payload.size - offset) {
        val sealer = sendSealer ?: throw IllegalStateException("pinhole not connected")
        val address = peerAddress ?: throw IllegalStateException("pinhole not connected")
        val frame = ByteArray(PREFIX + 8 + length + 16)
        frame[0] = FRAME_DATA.toByte()
        System.arraycopy(uLongLe(myPeerId), 0, frame, 1, 8)
        writeIntLe(frame, 9, myToken)
        val sealed = sealer.seal(frame, PREFIX, frame.copyOf(PREFIX), payload, offset, length)
        socket.send(DatagramPacket(frame, 0, PREFIX + sealed, address))
        lastSendNanos = System.nanoTime()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (established) {
            try {
                sendBye()
            } catch (_: Exception) {
            }
        }
        established = false
        socket.close()
        onClosed?.invoke("closed")
    }

    // ------------------------------------------------------------------ receive path

    private fun receiveLoop() {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!closed.get()) {
            try {
                socket.receive(packet)
            } catch (_: Exception) {
                return // socket closed
            }
            if (packet.length == 0) continue
            handleFrame(buffer, packet.length, packet.socketAddress as? InetSocketAddress ?: continue)
        }
    }

    private fun handleFrame(frame: ByteArray, length: Int, from: InetSocketAddress) {
        when (frame[0].toInt() and 0xFF) {
            FRAME_PACK -> handlePack(frame, length, from)

            FRAME_PUNC -> {} // we are the dialer; simultaneous-open is out of scope

            else -> {
                if (!established) return
                // Post-handshake frames must carry the token we learned from the PACK.
                if (length < PREFIX + 4 || readIntLe(frame, 9) != remoteToken) return
                val plaintext = recvSealer?.open(frame, length) ?: return
                when (frame[0].toInt() and 0xFF) {
                    FRAME_DATA -> onReceived?.invoke(plaintext)
                    FRAME_PING -> sendPong(plaintext, from)
                    FRAME_PONG -> if (plaintext.size >= 8) {
                        lastRttMs = (System.nanoTime() - readTickNanos(plaintext)) / 1_000_000
                    }
                    FRAME_ANNOUNCE -> {} // path hints: not needed by a dialer
                    FRAME_BYE -> fail("peer closed the session")
                }
            }
        }
    }

    private fun handlePack(frame: ByteArray, length: Int, from: InetSocketAddress) {
        // body = [echo of our token][responder token][ephemeral][static][confirm]
        if (length != 9 + 4 + 4 + 32 + 32 + 16) return
        if (readIntLe(frame, 9) != myToken) return // not an echo of our handshake

        val pinned = cs.staticKey ?: return
        val peerEph = frame.copyOfRange(17, 17 + 32)
        val peerStatic = frame.copyOfRange(49, 49 + 32)
        val peerConfirm = frame.copyOfRange(81, 81 + 16)

        // The answering key must be the key the string vouches for: a machine in the
        // middle, or a stale string — either way the session is dead on arrival.
        if (!peerStatic.contentEquals(pinned)) {
            fail("peer static key does not match its connection string (possible man in the middle)")
            return
        }

        val keys = try {
            SessionKeys.derive(myPeerId, ephPrivate, staticPrivate, cs.peerId, peerEph, peerStatic)
        } catch (_: IllegalArgumentException) {
            return // low-order point probe: ignore, an honest retry still works
        }

        val expected = if (myPeerId < cs.peerId) keys.hiConfirm else keys.loConfirm
        if (!peerConfirm.contentEquals(expected)) {
            fail("handshake confirmation failed: the answering peer does not hold the advertised key")
            return
        }

        remoteToken = readIntLe(frame, 13)
        peerAddress = from
        synchronized(this) {
            if (sendSealer == null) {
                val iAmLo = myPeerId < cs.peerId
                sendSealer = FrameSealer(keys, iAmLo, sending = true)
                recvSealer = FrameSealer(keys, iAmLo, sending = false)
            }
        }

        // Third flight: our confirm back (plaintext frame, token-authenticated).
        if (!hsckSent) {
            hsckSent = true
            val hsck = ByteArray(PREFIX + 16)
            hsck[0] = FRAME_HSCK.toByte()
            System.arraycopy(uLongLe(myPeerId), 0, hsck, 1, 8)
            writeIntLe(hsck, 9, myToken)
            val myConfirm = if (myPeerId < cs.peerId) keys.loConfirm else keys.hiConfirm
            System.arraycopy(myConfirm, 0, hsck, PREFIX, 16)
            socket.send(DatagramPacket(hsck, hsck.size, from))
        }

        established = true
        onConnected?.invoke(from)
    }

    // ------------------------------------------------------------------ maintenance frames

    private fun sendPong(pingTimestamp: ByteArray, to: InetSocketAddress) {
        val body = ByteArray(8)
        System.arraycopy(pingTimestamp, 0, body, 0, minOf(8, pingTimestamp.size))
        sendSealed(FRAME_PONG, body, to)
    }

    private fun sendBye() {
        sendSealed(FRAME_BYE, ByteArray(0), peerAddress ?: return)
    }

    /** Sends our own keepalive ping when nothing else has flown for a while. */
    fun keepaliveIfIdle(idleMs: Long = 5_000) {
        if (!established) return
        if (System.nanoTime() - lastSendNanos < idleMs * 1_000_000) return
        sendSealed(FRAME_PING, tickNanos(), peerAddress ?: return)
    }

    private fun sendSealed(type: Int, body: ByteArray, to: InetSocketAddress) {
        val sealer = sendSealer ?: return
        val frame = ByteArray(PREFIX + 8 + body.size + 16)
        frame[0] = type.toByte()
        System.arraycopy(uLongLe(myPeerId), 0, frame, 1, 8)
        writeIntLe(frame, 9, myToken)
        val sealed = sealer.seal(frame, PREFIX, frame.copyOf(PREFIX), body)
        try {
            socket.send(DatagramPacket(frame, 0, PREFIX + sealed, to))
            lastSendNanos = System.nanoTime()
        } catch (_: Exception) {
        }
    }

    private fun tickNanos(): ByteArray {
        val nanos = System.nanoTime()
        return byteArrayOf(
            (nanos and 0xFF).toByte(),
            ((nanos ushr 8) and 0xFF).toByte(),
            ((nanos ushr 16) and 0xFF).toByte(),
            ((nanos ushr 24) and 0xFF).toByte(),
            ((nanos ushr 32) and 0xFF).toByte(),
            ((nanos ushr 40) and 0xFF).toByte(),
            ((nanos ushr 48) and 0xFF).toByte(),
            ((nanos ushr 56) and 0xFF).toByte(),
        )
    }

    private fun readTickNanos(body: ByteArray): Long {
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (body[i].toLong() and 0xFF)
        }
        return value
    }

    private fun fail(reason: String) {
        closed.set(true)
        established = false
        socket.close()
        onClosed?.invoke(reason)
    }

    private companion object {
        const val PREFIX = 13 // type(1) + sender peer id(8, LE) + token(4, LE)
        const val FRAME_PUNC = 0x50
        const val FRAME_PACK = 0x51
        const val FRAME_DATA = 0x52
        const val FRAME_PING = 0x53
        const val FRAME_PONG = 0x54
        const val FRAME_ANNOUNCE = 0x55
        const val FRAME_BYE = 0x56
        const val FRAME_HSCK = 0x57
        const val PUNCH_INTERVAL_MS = 200L
    }
}
