package pinhole

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Untrusted stream framing only. The session must authenticate a fresh challenge
 * on each stream before using it as a peer route. No listening port is opened. */
internal class TcpTransport(
    private val scheduler: ScheduledThreadPoolExecutor,
    private val received: (IrohDatagram) -> Unit,
) : Closeable {
    private val gate = Any()
    private val links = mutableMapOf<IrohPath.Direct, Link>()
    private val sequence = AtomicLong()
    private var closed = false

    fun dial(address: InetSocketAddress): IrohPath.DirectTcp? = synchronized(gate) {
        if (closed || address.isUnresolved || address.port == 0) return null
        val key = IrohPath.Direct(address)
        links[key]?.let { return it.path }
        if (links.size >= 4) return null
        Link(IrohPath.DirectTcp(address, sequence.incrementAndGet())).also {
            links[key] = it
            it.start()
        }.path
    }

    private fun link(path: IrohPath.DirectTcp): Link? = synchronized(gate) {
        links[IrohPath.Direct(path.address)]?.takeIf { !closed && it.path == path }
    }

    fun send(path: IrohPath.DirectTcp, payload: ByteArray): Boolean = link(path)?.send(payload) == true
    fun isReady(path: IrohPath.DirectTcp): Boolean = link(path)?.ready == true
    fun isAuthenticated(path: IrohPath.DirectTcp): Boolean = link(path)?.authenticated == true
    fun beginProof(path: IrohPath.DirectTcp): Long? = link(path)?.beginProof()
    fun confirmProof(path: IrohPath.DirectTcp, echoed: Long): Boolean = link(path)?.confirmProof(echoed) == true
    fun observeChallenge(path: IrohPath.DirectTcp, nonce: Long) { link(path)?.observeChallenge(nonce) }
    fun compareProofs(a: IrohPath.DirectTcp, b: IrohPath.DirectTcp): Int =
        link(a)?.proofOrder()?.let { left -> link(b)?.proofOrder()?.let { right ->
            val first = left.first.compareTo(right.first)
            if (first == 0) left.second.compareTo(right.second) else first
        } } ?: 0
    fun close(path: IrohPath.DirectTcp) { link(path)?.close() }
    internal val liveLinks: Int get() = synchronized(gate) { links.size }
    fun paths(): List<IrohPath.DirectTcp> = synchronized(gate) { if (closed) emptyList() else links.values.map { it.path } }

    override fun close() {
        val snapshot = synchronized(gate) {
            if (closed) return
            closed = true
            links.values.toList().also { links.clear() }
        }
        snapshot.forEach { it.close() }
    }

    private inner class Link(val path: IrohPath.DirectTcp) : Closeable {
        private val socket = Socket()
        private val stopped = AtomicBoolean(false)
        private val outbound = LinkedBlockingQueue<ByteArray>(32)
        private val proofGate = Any()
        private val challenge = readULongLe(Crypto.randomPrivateKey(), 0).toLong() and 0x3fff_ffff_ffff_ffffL
        private var proofStarted = false
        private var remoteChallenge = -1L
        @Volatile var ready = false
            private set
        @Volatile var authenticated = false
            private set
        private var reader: Thread? = null
        private var writer: Thread? = null
        private val proofDeadline = scheduler.schedule({ close() }, 8, TimeUnit.SECONDS)

        fun start() {
            reader = thread(isDaemon = true, name = "pinhole-tcp-read") {
                try {
                    socket.tcpNoDelay = true
                    socket.connect(path.address, 3000)
                    val negotiated = scheduler.schedule({ close() }, 3, TimeUnit.SECONDS)
                    try {
                        socket.getOutputStream().write(PREFACE)
                        val expected = readExactly(PREFACE.size, System.nanoTime() + 3_000_000_000L)
                        if (!expected.contentEquals(PREFACE)) throw IOException("unsupported Pinhole TCP framing")
                    } finally { negotiated.cancel(false) }
                    if (stopped.get()) return@thread
                    ready = true
                    writer = thread(isDaemon = true, name = "pinhole-tcp-write") { writeLoop() }
                    while (!stopped.get()) {
                        val frame = readFrame()
                        received(IrohDatagram(path, frame))
                    }
                } catch (_: IOException) {
                } finally { close() }
            }
        }

        fun send(frame: ByteArray): Boolean {
            require(frame.size in 13..8192) { "invalid Pinhole TCP frame length" }
            return ready && !stopped.get() && outbound.offer(frame.copyOf())
        }

        private fun writeLoop() {
            try {
                val output = socket.getOutputStream()
                while (!stopped.get()) {
                    val frame = outbound.take()
                    val packet = ByteArray(frame.size + 2)
                    packet[0] = (frame.size ushr 8).toByte()
                    packet[1] = frame.size.toByte()
                    frame.copyInto(packet, 2)
                    val deadline = scheduler.schedule({ close() }, 2, TimeUnit.SECONDS)
                    try { output.write(packet) } finally { deadline.cancel(false) }
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            } finally { close() }
        }

        private fun readFrame(): ByteArray {
            // Idle authenticated streams can wait. The absolute body deadline starts
            // with the first header byte; a byte trickle cannot renew it indefinitely.
            socket.soTimeout = 0
            val input = socket.getInputStream()
            val first = input.read()
            if (first < 0) throw EOFException("Pinhole TCP closed")
            val deadline = System.nanoTime() + 5_000_000_000L
            val second = readExactly(1, deadline, input)[0].toInt() and 255
            val length = (first shl 8) or second
            if (length !in 13..8192) throw IOException("invalid Pinhole TCP frame length")
            return readExactly(length, deadline, input)
        }

        private fun readExactly(length: Int, deadline: Long, input: InputStream = socket.getInputStream()): ByteArray {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw SocketTimeoutException("Pinhole TCP frame deadline")
                socket.soTimeout = maxOf(1, TimeUnit.NANOSECONDS.toMillis(remaining).toInt())
                val count = input.read(bytes, offset, length - offset)
                if (count < 0) throw EOFException("truncated Pinhole TCP frame")
                offset += count
            }
            return bytes
        }

        fun beginProof(): Long? = synchronized(proofGate) {
            if (!ready || stopped.get() || proofStarted) null
            else { proofStarted = true; challenge }
        }

        fun confirmProof(echoed: Long): Boolean = synchronized(proofGate) {
            if (!proofStarted || echoed != challenge || !ready || stopped.get()) false
            else { authenticated = true; proofDeadline.cancel(false); true }
        }

        fun observeChallenge(nonce: Long) = synchronized(proofGate) {
            if (remoteChallenge < 0 && nonce in 0..0x3fff_ffff_ffff_ffffL) remoteChallenge = nonce
        }

        fun proofOrder(): Pair<Long, Long> = synchronized(proofGate) {
            minOf(challenge, remoteChallenge) to maxOf(challenge, remoteChallenge)
        }

        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            ready = false
            proofDeadline.cancel(false)
            try { socket.close() } catch (_: IOException) { }
            reader?.interrupt()
            writer?.interrupt()
            outbound.clear()
            synchronized(gate) {
                val key = IrohPath.Direct(path.address)
                if (links[key] === this) links.remove(key)
            }
        }
    }

    internal companion object {
        val PREFACE = "PHNTCP1\n".toByteArray(Charsets.US_ASCII)
    }
}
