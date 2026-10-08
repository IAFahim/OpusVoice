package pinhole

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StunTest {
    // Independent RFC 5769 sections 2.2/2.3, including nonzero attribute padding.
    private val ipv4 = IrohEncoding.unhex("0101003c2112a442b7e7a701bc34d686fa87dfae8022000b7465737420766563746f7220002000080001a147e112a643000800142b91f599fd9e90c38c7489f92af9ba53f06be7d780280004c07d4c96")
    private val ipv6 = IrohEncoding.unhex("010100482112a442b7e7a701bc34d686fa87dfae8022000b7465737420766563746f7220002000140002a1470113a9faa5d3f179bc25f4b5bed2b9d900080014a382954e4be67bf11784c97c8292c275bfe3ed4180280004c8fb0b4c")
    private val request = ipv4.copyOf(20).also { it[0] = 0; it[1] = 1; it[2] = 0; it[3] = 0 }

    @Test
    fun officialIpv4AndIpv6VectorsDecode() {
        assertEquals(InetSocketAddress(InetAddress.getByName("192.0.2.1"), 32853), Stun.mappedAddress(ipv4, request))
        assertEquals(InetSocketAddress(InetAddress.getByName("2001:db8:1234:5678:11:2233:4455:6677"), 32853), Stun.mappedAddress(ipv6, request))
    }

    @Test
    fun malformedAndUnrelatedRepliesCannotSupplyCandidates() {
        for (length in ipv6.indices) assertNull(Stun.mappedAddress(ipv6.copyOf(length), request))
        assertNull(Stun.mappedAddress(ipv4 + byteArrayOf(0), request))
        assertNull(Stun.mappedAddress(ipv4.copyOf().also { it[8] = (it[8].toInt() xor 1).toByte() }, request))
        assertNull(Stun.mappedAddress(ipv4.copyOf().also { it[38] = 0x7f }, request))
    }

    @Test
    fun probesReuseTheDataSocket_RetryLoss_AndCheckTheReplySource() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            IrohTransport().use { transport ->
                DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { server ->
                    server.soTimeout = 2500
                    val result = executor.submit<InetSocketAddress?> { transport.probeStun(server.localSocketAddress as InetSocketAddress, 2500) }
                    val first = receive(server)
                    assertEquals(transport.localPort, first.port)
                    val reply = ipv4.copyOf().also { first.data.copyInto(it, 8, 8, 20) }
                    // A different socket knows the transaction, but cannot forge this server's answer.
                    DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { impostor ->
                        impostor.send(DatagramPacket(reply, reply.size, first.socketAddress))
                    }
                    val retry = receive(server)
                    assertContentEquals(first.data.copyOf(first.length), retry.data.copyOf(retry.length))
                    val ordinary = byteArrayOf(42, 43, 44)
                    server.send(DatagramPacket(ordinary, ordinary.size, first.socketAddress))
                    server.send(DatagramPacket(reply, reply.size, first.socketAddress))
                    assertEquals(InetSocketAddress(InetAddress.getByName("192.0.2.1"), 32853), result.get(3, TimeUnit.SECONDS))
                    var datagram = assertNotNull(transport.receive(1000))
                    if (!datagram.payload.contentEquals(ordinary)) datagram = assertNotNull(transport.receive(1000))
                    assertContentEquals(ordinary, datagram.payload)
                }
            }
        } finally { executor.shutdownNow() }
    }

    @Test
    fun closingTransportCancelsPendingStun() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            IrohTransport().use { transport ->
                DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { server ->
                    server.soTimeout = 2000
                    val result = executor.submit<InetSocketAddress?> { transport.probeStun(server.localSocketAddress as InetSocketAddress, 10_000) }
                    receive(server)
                    transport.close()
                    assertNull(result.get(1, TimeUnit.SECONDS))
                }
            }
        } finally { executor.shutdownNow() }
    }

    private fun receive(socket: DatagramSocket): DatagramPacket = DatagramPacket(ByteArray(512), 512).also { socket.receive(it) }
}
