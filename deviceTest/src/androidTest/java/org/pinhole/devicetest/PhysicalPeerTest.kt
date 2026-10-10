package org.pinhole.devicetest

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import pinhole.PinholeDialer

/** Opt-in real-device validation, using the shipping Kotlin transport and a live
 * .NET echo peer. A separate application ID preserves the installed voice app. */
@RunWith(AndroidJUnit4::class)
class PhysicalPeerTest {
    @Test
    fun encryptedRoundTripsAcrossThePhysicalNetwork() {
        val arguments = InstrumentationRegistry.getArguments()
        val ticket = arguments.getString("pinholeTicket").orEmpty()
        assumeTrue("Provide a live peer using -e pinholeTicket", ticket.isNotEmpty())
        val cellular = arguments.getString("requireCellular") == "true"
        val relayOnly = arguments.getString("relayOnly") == "true"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val networks = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun checkNetwork() {
            val active = networks.activeNetwork
            val capabilities = networks.getNetworkCapabilities(active)
            assertTrue("No active Internet network", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            if (cellular) {
                assertTrue("The active network must be cellular", capabilities!!.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                assertFalse("A VPN would change the cellular test topology", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                assertFalse("Wi-Fi must not carry the cellular test", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            }
        }
        checkNetwork()
        val echoes = LinkedBlockingQueue<ByteArray>(128)
        val dialer = PinholeDialer(ticket, connectTimeoutMs = 30_000, relayOnly = relayOnly)
        val rtts = mutableListOf<Long>()
        var attempts = 0
        val started = System.nanoTime()
        try {
            dialer.onReceived = { echoes.offer(it) }
            dialer.connect()
            val connectMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("The encrypted handshake did not establish", dialer.isConnected)
            val sizes = intArrayOf(1, 2, 60, 120, 333, 467, 777, 900, 1024, 1100, 1200, 60)
            repeat(48) { index ->
                val payload = ByteArray(sizes[index % sizes.size]) { offset -> (index * 31 + offset).toByte() }
                val sendAt = System.nanoTime()
                val deadline = sendAt + 10_000_000_000L
                var received: ByteArray? = null
                while (received == null && System.nanoTime() < deadline) {
                    dialer.send(payload)
                    attempts++
                    val waitUntil = minOf(deadline, System.nanoTime() + 1_000_000_000L)
                    while (System.nanoTime() < waitUntil) {
                        val echo = echoes.poll(maxOf(1L, waitUntil - System.nanoTime()), TimeUnit.NANOSECONDS) ?: break
                        // Retries can leave a duplicate echo from the preceding request.
                        if (echo.contentEquals(payload)) { received = echo; break }
                    }
                }
                assertTrue("Echo $index did not arrive before its deadline", received != null)
                assertArrayEquals(payload, received)
                rtts.add((System.nanoTime() - sendAt) / 1_000_000)
                Thread.sleep(100)
            }
            checkNetwork()
            assertTrue("The connection closed during the exchange", dialer.isConnected)
            val sorted = rtts.sorted()
            Log.i("PinholeDeviceTest", "PASS device=${Build.MANUFACTURER}/${Build.MODEL} android=${Build.VERSION.RELEASE} " +
                "cellular=$cellular relayOnly=$relayOnly path=${dialer.connectedPath} connectMs=$connectMs " +
                "echoes=${rtts.size} attempts=$attempts rttP50Ms=${sorted[sorted.size / 2]} " +
                "rttP95Ms=${sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.lastIndex)]} rttMaxMs=${sorted.last()}")
        } finally { dialer.close() }
        assertFalse("Closing must retire the connection", dialer.isConnected)
    }
}
