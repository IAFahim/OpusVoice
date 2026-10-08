package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import pinhole.LanReceiver
import pinhole.LanReceiverCatalog
import java.io.Closeable
import java.net.NetworkInterface
import java.util.concurrent.Executor

/** The platform owns multicast/record caching. Browsing is limited to the app's
 * foreground lifecycle and never opens a microphone or initiates a session. */
internal class PinholeLanBrowser(
    context: Context,
    private val onPeers: (List<LanReceiver>) -> Unit,
    private val onError: (String?) -> Unit,
) : Closeable {
    private val context = context.applicationContext
    private val manager = try { this.context.getSystemService(NsdManager::class.java) } catch (_: RuntimeException) { null }
    private val gate = Any()
    private val catalog = LanReceiverCatalog()
    private val services = linkedMapOf<String, NsdServiceInfo>()
    private val monitors = linkedMapOf<String, Closeable>()
    private val pending = ArrayDeque<Pair<String, NsdServiceInfo>>()
    private var resolving: NsdManager.ResolveListener? = null
    private var resolvingKey: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val legacyRefresh = object : Runnable {
        override fun run() = synchronized(gate) {
            if (closed) return@synchronized
            services.forEach { (id, service) ->
                if (id != resolvingKey && pending.none { it.first == id }) pending.addLast(id to service)
            }
            resolveNext()
            handler.postDelayed(this, 15_000)
            Unit
        }
    }
    private var multicastLock: WifiManager.MulticastLock? = null
    private var closed = false
    private var started = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onStartDiscoveryFailed(type: String, code: Int) {
            synchronized(gate) { if (!closed) { started = false; onError("Nearby discovery unavailable ($code)") } }
            close()
        }
        override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onServiceFound(info: NsdServiceInfo) = found(info)
        override fun onServiceLost(info: NsdServiceInfo) = lost(info)
    }

    fun start() {
        synchronized(gate) {
            if (closed || started) return
            if (manager == null) { onError("Nearby discovery unavailable"); return }
            try {
                // Older system NSD stacks need the Wi-Fi multicast filter released.
                if (Build.VERSION.SDK_INT < 34) {
                    multicastLock = context.getSystemService(WifiManager::class.java)
                        ?.createMulticastLock("opusvoice-pinhole-discovery")?.apply {
                            setReferenceCounted(false)
                            acquire()
                        }
                }
                started = true
                manager.discoverServices(LanReceiver.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
                if (Build.VERSION.SDK_INT < 34) handler.postDelayed(legacyRefresh, 15_000)
                onError(null)
            } catch (e: RuntimeException) {
                onError("Nearby discovery unavailable: ${e.message}")
                close()
            }
        }
    }

    private fun key(info: NsdServiceInfo): String = info.serviceName + "@" +
        if (Build.VERSION.SDK_INT >= 33) (info.network?.toString() ?: "local") else "local"

    private fun found(info: NsdServiceInfo): Unit = synchronized(gate) {
        if (closed || !LanReceiver.isService(info.serviceName, info.serviceType)) return@synchronized
        val id = key(info)
        if (services.containsKey(id) || services.size >= 16) return@synchronized
        services[id] = info
        if (Build.VERSION.SDK_INT >= 34) monitor(id, info)
        else { pending.addLast(id to info); resolveNext() }
    }

    private fun lost(info: NsdServiceInfo): Unit = synchronized(gate) {
        if (closed) return@synchronized
        val id = key(info)
        services.remove(id)
        pending.removeAll { it.first == id }
        monitors.remove(id)?.close()
        catalog.remove(id)
        onPeers(catalog.snapshot())
    }

    @RequiresApi(34)
    private fun monitor(id: String, original: NsdServiceInfo) {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(info: NsdServiceInfo) = resolved(id, original, info)
            override fun onServiceLost() = synchronized(gate) {
                if (!closed && services[id] === original) { catalog.remove(id); onPeers(catalog.snapshot()) }
            }
            override fun onServiceInfoCallbackRegistrationFailed(code: Int) = synchronized(gate) {
                if (services[id] === original) {
                    services.remove(id)
                    monitors.remove(id)
                    catalog.remove(id)
                    if (!closed) onPeers(catalog.snapshot())
                }
            }
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        monitors[id] = Closeable {
            try { manager?.unregisterServiceInfoCallback(callback) } catch (_: RuntimeException) { }
        }
        try { manager!!.registerServiceInfoCallback(original, Executor { it.run() }, callback) }
        catch (_: RuntimeException) { monitors.remove(id)?.close(); services.remove(id) }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (closed || resolving != null || pending.isEmpty()) return
        val (id, original) = pending.removeFirst()
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) = synchronized(gate) {
                if (resolving !== this) return@synchronized
                resolving = null
                resolvingKey = null
                resolved(id, original, info)
                resolveNext()
            }
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) = synchronized(gate) {
                if (resolving !== this) return@synchronized
                resolving = null
                resolvingKey = null
                if (services[id] === original) { catalog.remove(id); onPeers(catalog.snapshot()) }
                resolveNext()
            }
        }
        resolving = listener
        resolvingKey = id
        try { manager!!.resolveService(original, listener) }
        catch (_: RuntimeException) {
            resolving = null; resolvingKey = null; services.remove(id); catalog.remove(id)
            onPeers(catalog.snapshot()); resolveNext()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolved(id: String, original: NsdServiceInfo, info: NsdServiceInfo): Unit = synchronized(gate) {
        if (closed || services[id] !== original || !info.serviceName.equals(original.serviceName, true)) return@synchronized
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        var scope = 0
        if (Build.VERSION.SDK_INT >= 33 && info.network != null) {
            try {
                val name = context.getSystemService(ConnectivityManager::class.java)?.getLinkProperties(info.network!!)?.interfaceName
                if (name != null) scope = NetworkInterface.getByName(name)?.index ?: 0
            } catch (_: Exception) { } // a scoped platform address can still be used
        }
        catalog.update(id, LanReceiver.fromService(info.serviceName, info.serviceType, info.port,
            addresses, info.attributes, scope))
        onPeers(catalog.snapshot())
    }

    override fun close(): Unit = synchronized(gate) {
        if (closed) return@synchronized
        closed = true
        handler.removeCallbacks(legacyRefresh)
        if (started) try { manager?.stopServiceDiscovery(discovery) } catch (_: RuntimeException) { }
        monitors.values.toList().forEach { it.close() }
        monitors.clear()
        // Pre-34 NSD cannot cancel an in-flight one-shot resolution. Detach its
        // listener; late callbacks are ignored and the platform times it out.
        if (Build.VERSION.SDK_INT >= 34) resolving?.let {
            try { manager?.stopServiceResolution(it) } catch (_: RuntimeException) { }
        }
        resolving = null
        resolvingKey = null
        pending.clear()
        services.clear()
        catalog.clear()
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        onPeers(emptyList())
    }
}
