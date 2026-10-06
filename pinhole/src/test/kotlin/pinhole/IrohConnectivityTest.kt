package pinhole

import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IrohConnectivityTest {
    @Test
    fun closingRawTransportUnblocksPendingReceive() {
        val transport = IrohTransport()
        val waiting = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<IrohDatagram?> {
                waiting.countDown()
                transport.receive(60_000)
            }
            waiting.await()
            transport.close()
            assertNull(result.get(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { transport.close(); executor.shutdownNow() }
    }
    private val id = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"
    // Independent golden vectors from the official iroh-ffi / iroh-dns libraries.
    private val ticket = "endpointaab2cb576phbbpq5odorrz2lycmwpzgwgcn2kdk7dxoimzaskuy3qayaejuhi5dqom5c6l3bobztcljrfzzgk3dbpexg4mbonfzg62bonruw42zpaeah6aaaagaaqaibaaaaaaaaaaaaaaaaaaaaaaaaah776ay"
    private val pkarr = "5182e77a20ad8d14633a89ae1ecd54b6ed76876c46029d249550234f4828e5a38c099c2482f3f1941a314cd9d71135eee2eca8a44678290f172780daf240370400065d308086bd2c000080000000000300000000055f69726f683479716f6f787839753361656d68386d6f3577637171313679756675366a69746f7571316f347a613735316467657231696767687900001000010000003c00292872656c61793d68747470733a2f2f617073312d312e72656c61792e6e302e69726f682e6c696e6b2fc00c001000010000003c001413616464723d3132372e302e302e313a31303234c00c001000010000003c001110616464723d5b3a3a315d3a3635353335"

    @Test
    fun nativeIdentityAndTicketMatchExactly() {
        assertEquals(id, IrohIdentity(ByteArray(32) { it.toByte() }).endpointId)
        val address = IrohAddress.parse(ticket)
        assertEquals(id, address.endpointId)
        assertEquals(ticket, address.toString())
        assertEquals(1024, address.directAddresses[0].port)
        assertEquals(65535, address.directAddresses[1].port)
        assertEquals(id, IrohAddress.parse(IrohEncoding.base32(IrohEncoding.unhex(id))).endpointId)
        for (length in ticket.indices) assertNull(IrohAddress.tryParse(ticket.substring(0, length)))
        assertNull(IrohAddress.tryParse(ticket + "a"))
        assertNull(IrohAddress.tryParse("00".repeat(32)))
    }

    @Test
    fun nativeDiscoveryIsPinnedAndTamperingIsRejected() {
        val key = IrohEncoding.unhex(id)
        val payload = IrohEncoding.unhex(pkarr)
        assertEquals(ticket, IrohDiscovery.parsePayload(key, payload).toString())
        payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
        assertFailsWith<IllegalArgumentException> { IrohDiscovery.parsePayload(key, payload) }
    }

    @Test
    fun rawUdpPreservesSmallThenLargePacketsAndReplyPaths() {
        IrohTransport().use { a ->
            IrohTransport().use { b ->
                val target = IrohPath.Direct(InetSocketAddress(InetAddress.getLoopbackAddress(), b.localPort))
                for (size in listOf(0, 1, 1200, 60000, 12)) {
                    val payload = ByteArray(size) { it.toByte() }
                    a.sendTo(target, payload)
                    val atB = assertNotNull(b.receive(3000))
                    assertContentEquals(payload, atB.payload)
                    b.sendTo(atB.path, atB.payload)
                    assertContentEquals(payload, assertNotNull(a.receive(3000)).payload)
                }
            }
        }
    }
}
