package pinhole

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end interop against a real Pinhole.Net node. Skipped unless the
 * `pinhole.ticket` system property (from the PINHOLE_TICKET env var or
 * -PpinholeTicket=…) points at a live echo peer:
 *
 *   dotnet run --project interop/EchoPeer -p:PinholeRoot=../Pinhole.Net   # prints TICKET=…
 *   PINHOLE_TICKET=<ticket> ./gradlew :pinhole:test --tests '*Interop*'
 */
class PinholeInteropTest {

    @Test
    fun dialsDotNetEchoPeerAndRoundTripsDatagrams() {
        val ticket = System.getProperty("pinhole.ticket")?.trim().orEmpty()
        assumeTrue(ticket.isNotEmpty(), "PINHOLE_TICKET not set; interop test skipped")

        val dialer = PinholeDialer(ticket, connectTimeoutMs = 20_000)
        val echoes = ConcurrentLinkedQueue<ByteArray>()
        val echoed = CountDownLatch(12)
        val connected = CountDownLatch(1)
        try {
            dialer.onConnected = { connected.countDown() }
            dialer.onReceived = { datagram ->
                echoes.add(datagram)
                echoed.countDown()
            }

            val address = dialer.connect()
            assertTrue(connected.await(5, TimeUnit.SECONDS), "onConnected never fired")
            assertTrue(address.port > 0, "answered path has no port")

            val payloads = (0 until 12).map { index ->
                ByteArray(60 + index * 37) { i -> (index * 31 + i).toByte() } // RTP-ish sizes
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
        } finally {
            dialer.close()
        }
    }
}
