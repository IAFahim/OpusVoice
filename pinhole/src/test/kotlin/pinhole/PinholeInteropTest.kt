package pinhole

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import java.io.File
import java.net.URI
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * End-to-end interop against a real Pinhole.Net node. Skipped unless the
 * `pinhole.ticket` system property (from the PINHOLE_TICKET env var or
 * -PpinholeTicket=…) points at a live echo peer:
 *
 *   dotnet run --project interop/EchoPeer -p:PinholeRoot=../Pinhole.Net   # prints TICKET=…
 *   PINHOLE_TICKET=<ticket> ./gradlew :pinhole:test --tests '*Interop*'
 */
class PinholeInteropTest {

    @TestFactory
    fun discoveryAndRelayConnectionMatrix(): List<DynamicTest> {
        val path = System.getProperty("pinhole.interop.cases").orEmpty()
        if (path.isEmpty()) return emptyList()
        return File(path).readLines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split('|')
            require(parts.size == 5)
            DynamicTest.dynamicTest(parts[0]) {
                val dialer = PinholeDialer(parts[1], connectTimeoutMs = 20_000, discoveryUrl = URI(parts[2]),
                    relayOnly = parts[3] == "relay")
                dialer.debug = parts[3] == "upgrade"
                try {
                    if (parts[4] == "reject") {
                        val error = assertFailsWith<IllegalArgumentException> { dialer.connect() }
                        assertTrue(error.message.orEmpty().contains("signature"), "expected signature rejection: " + error.message)
                        assertTrue(!dialer.isConnected)
                    } else {
                        roundTrip(dialer)
                        if (parts[3] == "upgrade") {
                            val deadline = System.nanoTime() + 5_000_000_000L
                            while (dialer.connectedPath !is IrohPath.Direct && System.nanoTime() < deadline) Thread.sleep(20)
                        }
                        assertEquals(parts[3] == "relay", dialer.connectedPath is IrohPath.Relay, "unexpected connection path")
                    }
                } finally { dialer.close() }
            }
        }
    }

    @Test
    fun dialsDotNetEchoPeerAndRoundTripsDatagrams() {
        val ticket = System.getProperty("pinhole.ticket")?.trim().orEmpty()
        assumeTrue(ticket.isNotEmpty(), "PINHOLE_TICKET not set; interop test skipped")

        val dialer = PinholeDialer(ticket, connectTimeoutMs = 20_000)
        ConnectionString.parse(ticket).candidates.forEach {
            println("interop: candidate ${it.kind} ${it.address}")
        }
        try { roundTrip(dialer) } finally { dialer.close() }
    }

    private fun roundTrip(dialer: PinholeDialer) {
        val echoes = ConcurrentLinkedQueue<ByteArray>()
        val echoed = CountDownLatch(12)
        val connected = CountDownLatch(1)
        run {
            dialer.onConnected = { connected.countDown() }
            dialer.onReceived = { datagram ->
                echoes.add(datagram)
                echoed.countDown()
            }

            val address = dialer.connect()
            assertTrue(connected.await(5, TimeUnit.SECONDS), "onConnected never fired")
            assertTrue(address.port > 0, "answered path has no port")

            assertFailsWith<IllegalArgumentException> { dialer.send(ByteArray(0)) }
            assertFailsWith<IllegalArgumentException> { dialer.send(ByteArray(1201)) }
            val payloads = listOf(60, 1, 1100, 2, 467, 120, 900, 60, 1024, 10, 333, 777).mapIndexed { index, size ->
                ByteArray(size) { i -> (index * 31 + i).toByte() }
            }
            payloads.forEach { payload ->
                dialer.send(payload)
                Thread.sleep(15)
            }

            assertTrue(echoed.await(15, TimeUnit.SECONDS), "expected 12 echoes, got ${echoes.size}")
            val received = echoes.toList()
            assertEquals(payloads.size, received.size)
            payloads.zip(received).forEach { (sent, back) ->
                assertEquals(sent.size, back.size)
                sent.forEachIndexed { i, b -> assertEquals(b, back[i]) }
            }
        }
    }
}
