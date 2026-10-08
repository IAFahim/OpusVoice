package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import pinhole.LanReceiver
import java.net.InetAddress
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [PinholeLanBrowserTest.NsdShadow::class])
class PinholeLanBrowserTest {
    @Implements(NsdManager::class)
    class NsdShadow {
        companion object {
            var discovery: NsdManager.DiscoveryListener? = null
            val callbacks = linkedMapOf<NsdManager.ServiceInfoCallback, NsdServiceInfo>()
            var resolver: NsdManager.ResolveListener? = null
            var resolveCalls = 0
            var stops = 0
        }
        @Implementation fun discoverServices(type: String, protocol: Int, listener: NsdManager.DiscoveryListener) {
            discovery = listener
            listener.onDiscoveryStarted(type)
        }
        // Older NsdManager constructors initialize a netd Messenger. The fake
        // discovery/resolve methods own this test's callbacks instead of that IPC.
        @Implementation fun init() = Unit
        @Implementation fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener) { stops++; listener.onDiscoveryStopped(LanReceiver.SERVICE_TYPE) }
        @Implementation fun registerServiceInfoCallback(info: NsdServiceInfo, executor: Executor, callback: NsdManager.ServiceInfoCallback) {
            callbacks[callback] = info
        }
        @Implementation fun unregisterServiceInfoCallback(callback: NsdManager.ServiceInfoCallback) { callbacks.remove(callback) }
        @Implementation fun resolveService(info: NsdServiceInfo, listener: NsdManager.ResolveListener) { resolveCalls++; resolver = listener }
    }

    @Before fun reset() {
        NsdShadow.discovery = null
        NsdShadow.callbacks.clear()
        NsdShadow.resolver = null
        NsdShadow.resolveCalls = 0
        NsdShadow.stops = 0
    }

    private fun info(index: Int = 1, address: String = "192.168.2.4") = NsdServiceInfo().apply {
        serviceName = index.toString(16).padStart(16, '0')
        serviceType = LanReceiver.SERVICE_TYPE
        port = 9000
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 34) hostAddresses = listOf(InetAddress.getByName(address)) else host = InetAddress.getByName(address)
        setAttribute("id", serviceName)
        setAttribute("hint", "1")
        setAttribute("key", (1..32).joinToString("") { "%02x".format(it) })
    }

    @Test fun monitorsUpdatesAndWithdrawsLostServices_ThenIgnoresLateCallbacksAfterClose() {
        var peers = emptyList<LanReceiver>()
        val errors = mutableListOf<String?>()
        val browser = PinholeLanBrowser(ApplicationProvider.getApplicationContext<Context>(), { peers = it }, { errors.add(it) })
        browser.start()
        val service = info()
        NsdShadow.discovery!!.onServiceFound(service)
        val callback = NsdShadow.callbacks.keys.single()
        callback.onServiceUpdated(service)
        assertEquals(1, peers.size)
        callback.onServiceUpdated(info(address = "fe80::abcd"))
        assertTrue(peers.single().addresses.single().address.isLinkLocalAddress)
        callback.onServiceLost()
        assertTrue(peers.isEmpty())
        callback.onServiceUpdated(service)
        assertEquals(1, peers.size)
        NsdShadow.discovery!!.onServiceLost(service)
        assertTrue(peers.isEmpty())
        assertTrue(NsdShadow.callbacks.isEmpty())
        callback.onServiceUpdated(service)
        assertTrue(peers.isEmpty())
        browser.close()
        browser.close()
        assertEquals(1, NsdShadow.stops)
        NsdShadow.discovery!!.onServiceFound(info(2))
        assertTrue(NsdShadow.callbacks.isEmpty())
        assertEquals(listOf<String?>(null), errors)
    }

    @Test fun filtersWrongServicesAndBoundsPlatformRegistrations() {
        val browser = PinholeLanBrowser(ApplicationProvider.getApplicationContext<Context>(), {}, {})
        browser.start()
        NsdShadow.discovery!!.onServiceFound(info().apply { serviceType = "_http._tcp." })
        repeat(100) { NsdShadow.discovery!!.onServiceFound(info(it + 1)) }
        assertEquals(16, NsdShadow.callbacks.size)
        browser.close()
        assertTrue(NsdShadow.callbacks.isEmpty())
    }

    @Test fun malformedUpdateAndPlatformRegistrationFailureLeaveNoDialablePeer() {
        var peers = emptyList<LanReceiver>()
        val browser = PinholeLanBrowser(ApplicationProvider.getApplicationContext<Context>(), { peers = it }, {})
        browser.start()
        NsdShadow.discovery!!.onServiceFound(info())
        val callback = NsdShadow.callbacks.keys.single()
        callback.onServiceUpdated(info().apply { setAttribute("key", "invalid") })
        assertTrue(peers.isEmpty())
        callback.onServiceInfoCallbackRegistrationFailed(3)
        callback.onServiceUpdated(info())
        assertTrue(peers.isEmpty())
        browser.close()
    }

    @Test
    @Config(sdk = [28])
    fun legacyResolutionIsSerialized_AndLostOrClosedResultsAreIgnored() {
        var peers = emptyList<LanReceiver>()
        val browser = PinholeLanBrowser(ApplicationProvider.getApplicationContext<Context>(), { peers = it }, {})
        browser.start()
        NsdShadow.discovery!!.onServiceFound(info(1))
        NsdShadow.discovery!!.onServiceFound(info(2))
        assertEquals(1, NsdShadow.resolveCalls)
        val first = NsdShadow.resolver!!
        NsdShadow.discovery!!.onServiceLost(info(1))
        first.onServiceResolved(info(1))
        assertTrue(peers.isEmpty())
        assertEquals(2, NsdShadow.resolveCalls)
        val second = NsdShadow.resolver!!
        second.onServiceResolved(info(2))
        assertEquals("0000000000000002", peers.single().id)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(15))
        assertEquals(3, NsdShadow.resolveCalls)
        val refreshed = NsdShadow.resolver!!
        refreshed.onServiceResolved(info(2, "192.168.2.99"))
        assertEquals("192.168.2.99", peers.single().addresses.single().address.hostAddress)
        browser.close()
        assertTrue(peers.isEmpty())
        second.onServiceResolved(info(2))
        refreshed.onServiceResolved(info(2))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(30))
        assertEquals(3, NsdShadow.resolveCalls)
        assertTrue(peers.isEmpty())
    }
}
