package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import pinhole.PortMappingOptions

/** Use the same active network routes as the wildcard session socket. Network/host
 * changes are polled by the mapper; no restricted /proc access or guessed routers. */
fun pinholeRouterMapping(context: Context): PortMappingOptions {
    val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    return PortMappingOptions(gateways = {
        try {
            val network = connectivity.boundNetworkForProcess ?: connectivity.activeNetwork
            val properties = network?.let { connectivity.getLinkProperties(it) }
            properties?.routes?.filter { it.isDefaultRoute }?.mapNotNull { route ->
                val gateway = route.gateway ?: return@mapNotNull null
                if (gateway.isAnyLocalAddress || gateway.isMulticastAddress) return@mapNotNull null
                val scoped = if (gateway is Inet6Address && gateway.isLinkLocalAddress && gateway.scopeId == 0) {
                    val nif = properties.interfaceName?.let { NetworkInterface.getByName(it) } ?: return@mapNotNull null
                    Inet6Address.getByAddress(null, gateway.address, nif)
                } else gateway
                InetSocketAddress(scoped, 5351)
            }?.distinct()?.take(4) ?: emptyList()
        } catch (_: Exception) { emptyList() }
    })
}
