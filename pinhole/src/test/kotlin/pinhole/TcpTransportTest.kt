package pinhole

import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TcpTransportTest {
    @Test
    fun linkLocalScopesRemainDistinctRoutes() {
        val bytes = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = 1 }
        val first = InetSocketAddress(Inet6Address.getByAddress(null, bytes, 1), 5004)
        val second = InetSocketAddress(Inet6Address.getByAddress(null, bytes, 2), 5004)
        assertNotEquals(IrohPath.Direct(first), IrohPath.Direct(second))
        assertNotEquals(IrohPath.DirectTcp(first, 1), IrohPath.DirectTcp(second, 1))
        assertEquals(2, setOf(IrohPath.Direct(first), IrohPath.Direct(second)).size)
    }

    @Test
    fun pendingStreamsAreCappedAndDisposalRetiresAllOfThem() {
        val servers = List(5) { ServerSocket(0, 4, InetAddress.getLoopbackAddress()) }
        val finished = CountDownLatch(1)
        val scheduler = ScheduledThreadPoolExecutor(1)
        val transport = TcpTransport(scheduler) { }
        val workers = servers.take(4).map { server -> thread(isDaemon = true) {
            try { server.accept().use { socket ->
                assertContentEquals(TcpTransport.PREFACE, readExactly(socket, 8))
                socket.getOutputStream().write(TcpTransport.PREFACE)
                finished.await(10, TimeUnit.SECONDS)
            } } catch (_: IOException) { }
        } }
        try {
            val addresses = servers.map { InetSocketAddress(it.inetAddress, it.localPort) }
            val paths = addresses.take(4).map { assertNotNull(transport.dial(it)) }
            until { paths.all { transport.isReady(it) } }
            assertEquals(paths[0], transport.dial(addresses[0]))
            assertEquals(null, transport.dial(addresses[4]))
            assertEquals(4, transport.liveLinks)
            paths.forEach { assertFalse(transport.isAuthenticated(it)) }
        } finally {
            transport.close()
            finished.countDown()
            servers.forEach { it.close() }
            workers.forEach { it.join(1000) }
            scheduler.shutdownNow()
            assertEquals(0, transport.liveLinks)
        }
    }

    @Test
    fun fragmentedAndCoalescedFramesPreserveBytesWithoutAuthenticatingTheStream() {
        val first = ByteArray(13) { it.toByte() }
        val second = ByteArray(8192) { (it * 17).toByte() }
        withPeer({ socket ->
            val output = socket.getOutputStream()
            val bytes = envelope(first) + envelope(second)
            bytes.asList().chunked(37).forEach { output.write(it.toByteArray()) }
        }) { transport, address, incoming ->
            val path = assertNotNull(transport.dial(address))
            val frames = List(2) { assertNotNull(incoming.poll(3, TimeUnit.SECONDS)) }
            assertContentEquals(first, frames[0].payload)
            assertContentEquals(second, frames[1].payload)
            assertEquals(path, frames[0].path)
            assertFalse(transport.isAuthenticated(path), "framing and received data are not peer proof")
        }
    }

    @Test
    fun invalidLengthsCloseBeforeAFrameIsPublished() {
        listOf(0, 1, 12, 8193, 65535).forEach { length ->
            withPeer({ it.getOutputStream().write(byteArrayOf((length ushr 8).toByte(), length.toByte())) }) { transport, address, incoming ->
                assertNotNull(transport.dial(address))
                until { transport.liveLinks == 0 }
                assertTrue(incoming.isEmpty())
            }
        }
    }

    @Test
    fun truncatedBodyClosesWithoutPublishingPartialData() {
        withPeer({ socket ->
            socket.getOutputStream().write(byteArrayOf(0, 13, 0x52, 1, 2))
            socket.shutdownOutput()
        }) { transport, address, incoming ->
            assertNotNull(transport.dial(address))
            until { transport.liveLinks == 0 }
            assertTrue(incoming.isEmpty())
        }
    }

    @Test
    fun incompleteHeaderHasAnAbsoluteDeadline() {
        withPeer({ it.getOutputStream().write(0) }) { transport, address, incoming ->
            assertNotNull(transport.dial(address))
            until(6500) { transport.liveLinks == 0 }
            assertTrue(incoming.isEmpty())
        }
    }

    @Test
    fun challengesBelongToOneLiveStreamGeneration() {
        withPeer({ }, connections = 2) { transport, address, _ ->
            val first = assertNotNull(transport.dial(address))
            until { transport.isReady(first) }
            val nonce = assertNotNull(transport.beginProof(first))
            assertFalse(transport.confirmProof(first, nonce xor 1L))
            // confirmProof is called by the session only after AEAD authenticates a PONG.
            assertTrue(transport.confirmProof(first, nonce))
            transport.close(first)
            assertFalse(transport.confirmProof(first, nonce))
            assertEquals(0, transport.liveLinks)
            val path = assertNotNull(transport.dial(address))
            until { transport.isReady(path) }
            assertNotEquals(first.connectionId, path.connectionId)
            val challenge = assertNotNull(transport.beginProof(path))
            assertNotEquals(nonce, challenge)
            assertFalse(transport.confirmProof(path, nonce))
            assertFalse(transport.isAuthenticated(path))
        }
    }

    @Test
    fun aVerifiedPackAndSealedDataCannotReplaceTheFreshTcpPong() {
        assertUnconfirmedPeerSays(0x52)
    }

    @Test
    fun aVerifiedPackAndSealedPongForAnotherNonceCannotAuthenticateTheStream() {
        assertUnconfirmedPeerSays(0x54)
    }

    /** Owned fake peer has the pinned private key and completes a valid PACK. It
     * deliberately supplies valid sealed traffic without answering this stream's
     * challenge, testing route authorization separately from key authentication. */
    private fun assertUnconfirmedPeerSays(responseType: Int) {
        val staticPrivate = Crypto.randomPrivateKey()
        val ephPrivate = Crypto.randomPrivateKey()
        val peerId = 0x7265_616c_7065_6572uL
        val token = 77331
        val protectedResponses = AtomicInteger()
        withPeer({ socket ->
            val output = socket.getOutputStream()
            var send: FrameSealer? = null
            var receive: FrameSealer? = null
            while (true) {
                val frame = readFrame(socket)
                if (frame[0].toInt() and 255 == 0x50) {
                    val clientId = readULongLe(frame, 1)
                    val lo = peerId < clientId
                    val keys = SessionKeys.derive(peerId, ephPrivate, staticPrivate, clientId,
                        frame.copyOfRange(13, 45), frame.copyOfRange(45, 77))
                    if (send == null) {
                        send = FrameSealer(keys, lo, sending = true)
                        receive = FrameSealer(keys, lo, sending = false)
                    }
                    val pack = prefix(0x51, peerId, readIntLe(frame, 9), 97)
                    writeIntLe(pack, 13, token)
                    Crypto.publicKey(ephPrivate).copyInto(pack, 17)
                    Crypto.publicKey(staticPrivate).copyInto(pack, 49)
                    (if (lo) keys.loConfirm else keys.hiConfirm).copyInto(pack, 81)
                    output.write(envelope(pack))
                } else if (frame[0].toInt() and 255 == 0x53) {
                    val ping = receive?.open(frame, frame.size) ?: continue
                    val response = if (responseType == 0x54)
                        uLongLe(readULongLe(ping, 0) xor 1uL) else "valid data before route proof".toByteArray()
                    val sealed = prefix(responseType, peerId, token, 13 + 24 + response.size)
                    send!!.seal(sealed, 13, sealed.copyOf(13), response)
                    output.write(envelope(sealed))
                    protectedResponses.incrementAndGet()
                }
            }
        }) { _, address, _ ->
            val ticket = ConnectionString(peerId, NatHint.Unknown,
                listOf(PinholeCandidate(CandidateKind.Direct, address)), Crypto.publicKey(staticPrivate), null).toString()
            val dialer = PinholeDialer(ticket, connectTimeoutMs = 1500, tcpOnly = true,
                publishDirectAddresses = false, portMapping = PortMappingOptions(enabled = false))
            var delivered = 0
            dialer.onReceived = { delivered++ }
            try {
                val error = assertFailsWith<IllegalStateException> { dialer.connect() }
                assertTrue(error.message.orEmpty().contains("timeout"), error.message)
                assertTrue(protectedResponses.get() > 0, "the peer must actually send valid sealed traffic")
                assertFalse(dialer.isConnected)
                assertEquals(0, delivered)
            } finally { dialer.close() }
        }
    }

    private fun withPeer(
        reply: (Socket) -> Unit,
        connections: Int = 1,
        test: (TcpTransport, InetSocketAddress, LinkedBlockingQueue<IrohDatagram>) -> Unit,
    ) {
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { server ->
            val finished = CountDownLatch(1)
            val scheduler = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
            val incoming = LinkedBlockingQueue<IrohDatagram>(16)
            val transport = TcpTransport(scheduler) { incoming.offer(it) }
            val peerFailure = AtomicReference<Throwable?>()
            val worker = thread(isDaemon = true) {
                try {
                    repeat(connections) {
                        server.accept().use { socket ->
                            assertContentEquals(TcpTransport.PREFACE, readExactly(socket, 8))
                            socket.getOutputStream().write(TcpTransport.PREFACE)
                            reply(socket)
                            if (connections == 1) finished.await(10, TimeUnit.SECONDS)
                            else while (socket.getInputStream().read() >= 0) { }
                        }
                    }
                } catch (_: IOException) { }
                catch (failure: Throwable) { peerFailure.set(failure) }
            }
            try { test(transport, InetSocketAddress(server.inetAddress, server.localPort), incoming) }
            finally {
                transport.close()
                finished.countDown()
                scheduler.shutdownNow()
                server.close()
                worker.join(1000)
                assertEquals(0, transport.liveLinks)
                peerFailure.get()?.let { throw AssertionError("The owned TCP fixture failed", it) }
            }
        }
    }

    private fun until(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition did not settle within ${timeoutMs}ms")
    }

    private fun readExactly(socket: Socket, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = socket.getInputStream().read(bytes, offset, count - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
        return bytes
    }

    private fun readFrame(socket: Socket): ByteArray {
        val header = readExactly(socket, 2)
        return readExactly(socket, ((header[0].toInt() and 255) shl 8) or (header[1].toInt() and 255))
    }

    private fun envelope(frame: ByteArray): ByteArray =
        byteArrayOf((frame.size ushr 8).toByte(), frame.size.toByte()) + frame

    private fun prefix(type: Int, peer: ULong, token: Int, length: Int): ByteArray = ByteArray(length).also {
        it[0] = type.toByte()
        uLongLe(peer).copyInto(it, 1)
        writeIntLe(it, 9, token)
    }
}
