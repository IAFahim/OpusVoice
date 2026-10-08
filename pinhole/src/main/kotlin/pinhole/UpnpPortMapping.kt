package pinhole

import java.io.Closeable
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Proxy
import java.net.URI
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** IGD discovery and SOAP stay on the discovered responder, with bounded XML/HTTP.
 * Redirects and proxies cannot turn a LAN announcement into an arbitrary HTTP request. */
internal object UpnpPortMapping {
    private data class Device(val location: URI, val source: InetAddress)
    private data class Service(val type: String, val control: URI)
    private data class Soap(val body: Element?, val fault: Int)
    private val xmlType = "text/xml; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).callTimeout(2, TimeUnit.SECONDS).build()

    fun discover(io: MappingIo, target: InetSocketAddress, port: Int, lifetime: Long): RouterLease? {
        if (io.isClosed) return null
        for (device in devices(io, target)) {
            if (io.isClosed) return null
            try {
                val client = http.newBuilder().dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (!hostname.equals(device.location.host.removeSurrounding("[", "]"), ignoreCase = true))
                            throw IOException("IGD control host changed")
                        return listOf(device.source)
                    }
                }).build()
                val description = xml(request(io, client, Request.Builder().url(device.location.toString()).build())) ?: continue
                for (service in services(description, device.location)) {
                    if (!safeUri(service.control, device.location.host)) continue
                    val external = externalIp(io, client, service) ?: continue
                    val local = DatagramSocket().use { socket -> socket.connect(InetSocketAddress(device.source, 1900)); socket.localAddress }
                    if (local !is Inet4Address) continue // IPv6 firewall pinholes use their own service.
                    val lease = add(io, client, service, local, port, lifetime, external)
                    if (lease != null) return lease
                }
            } catch (_: Exception) { }
        }
        return null
    }

    private fun devices(io: MappingIo, target: InetSocketAddress): List<Device> {
        val found = mutableListOf<Device>()
        val socket = MulticastSocket(0)
        return try {
            io.use(socket) {
                socket.timeToLive = 2
                val requestHost = if (target.address.hostAddress!!.contains(':')) "[${target.address.hostAddress}]:${target.port}" else "${target.address.hostAddress}:${target.port}"
                for (version in 1..2) {
                    val bytes = ("M-SEARCH * HTTP/1.1\r\nHOST: $requestHost\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n" +
                        "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:$version\r\n\r\n").toByteArray(Charsets.US_ASCII)
                    socket.send(DatagramPacket(bytes, bytes.size, target))
                }
                val end = System.nanoTime() + (if (target.address.isMulticastAddress) 1_000_000_000L else 300_000_000L)
                while (!io.isClosed && System.nanoTime() < end && found.size < 4) {
                    socket.soTimeout = maxOf(1, ((end - System.nanoTime()) / 1_000_000).toInt())
                    val packet = DatagramPacket(ByteArray(8192), 8192)
                    try {
                        socket.receive(packet)
                        val text = packet.data.copyOf(packet.length).toString(Charsets.US_ASCII)
                        if (!text.startsWith("HTTP/1.1 200")) continue
                        if (!target.address.isMulticastAddress && packet.address != target.address) continue
                        if (!(packet.address.isSiteLocalAddress || packet.address.isLinkLocalAddress || packet.address.isLoopbackAddress)) continue
                        val location = text.lineSequence().firstNotNullOfOrNull { line ->
                            val colon = line.indexOf(':')
                            if (colon > 0 && line.substring(0, colon).trim().equals("LOCATION", true)) line.substring(colon + 1).trim() else null
                        } ?: continue
                        val uri = try { URI(location) } catch (_: Exception) { continue }
                        if (!safeUri(uri)) continue
                        val literal = uri.host.removeSurrounding("[", "]")
                        if ((literal.contains(':') || Regex("[0-9.]+").matches(literal)) &&
                            InetAddress.getByName(literal) != packet.address) continue
                        // The HTTP client pins even a hostname LOCATION to this responder.
                        val device = Device(uri, packet.address)
                        if (found.none { it.location == uri && it.source == packet.address }) found.add(device)
                    } catch (_: SocketTimeoutException) { break }
                }
                found
            }
        } catch (_: IOException) { found }
    }

    internal fun safeUri(uri: URI, host: String? = null): Boolean = uri.isAbsolute && uri.scheme in listOf("http", "https") &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0 &&
        (host == null || uri.host.equals(host, ignoreCase = true))

    private fun services(root: Element, location: URI): List<Service> {
        val baseText = descendants(root).firstOrNull { it.localName == "URLBase" }?.textContent?.trim()
        val base = if (baseText.isNullOrEmpty()) location else location.resolve(baseText).takeIf { safeUri(it, location.host) } ?: return emptyList()
        return descendants(root).filter { it.localName == "service" }.mapNotNull { element ->
            val values = children(element).associate { it.localName to it.textContent.trim() }
            val type = values["serviceType"] ?: return@mapNotNull null
            if (type !in listOf("urn:schemas-upnp-org:service:WANIPConnection:2", "urn:schemas-upnp-org:service:WANIPConnection:1",
                    "urn:schemas-upnp-org:service:WANPPPConnection:1")) return@mapNotNull null
            val control = values["controlURL"] ?: return@mapNotNull null
            Service(type, base.resolve(control))
        }.take(4).toList()
    }

    private fun children(element: Element): List<Element> = (0 until element.childNodes.length)
        .mapNotNull { element.childNodes.item(it) as? Element }

    private fun descendants(root: Element): Sequence<Element> = sequence {
        val pending = java.util.ArrayDeque<Element>(); pending.add(root)
        var count = 0
        while (pending.isNotEmpty()) {
            check(++count <= 2048) { "IGD XML has too many elements" }
            val next = pending.removeFirst(); yield(next); children(next).forEach { pending.addLast(it) }
        }
    }

    internal fun xml(text: String?): Element? {
        if (text == null || text.length > 65536 || Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true; factory.isExpandEntityReferences = false
            for (feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities",
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd")) {
                try { factory.setFeature(feature, false) } catch (_: Exception) { }
            }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            builder.parse(InputSource(StringReader(text))).documentElement
        } catch (_: Exception) { null }
    }

    private fun request(io: MappingIo?, client: OkHttpClient, request: Request): String? {
        val call = client.newCall(request)
        val operation = {
            call.execute().use { response ->
                val body = response.body ?: return@use null
                if (response.code != 200 && response.code != 500) return@use null
                body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (output.size() + read > 65536) throw IOException("IGD response exceeds limit")
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray().toString(Charsets.UTF_8)
                }
            }
        }
        return if (io == null) operation() else io.use(Closeable { call.cancel() }, operation)
    }

    private fun arg(name: String, value: String): String = "<$name>" + value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</$name>"

    private fun soap(io: MappingIo?, client: OkHttpClient, service: Service, action: String, args: String): Soap {
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"${service.type}\">$args</u:$action></s:Body></s:Envelope>"
        val request = Request.Builder().url(service.control.toString()).header("SOAPAction", "\"${service.type}#$action\"")
            .post(body.toRequestBody(xmlType)).build()
        val parsed = xml(request(io, client, request)) ?: return Soap(null, -1)
        val fault = descendants(parsed).firstOrNull { it.localName == "errorCode" }?.textContent?.trim()?.toIntOrNull()
        return Soap(parsed, fault ?: if (descendants(parsed).any { it.localName == action + "Response" }) 0 else -1)
    }

    private fun externalIp(io: MappingIo?, client: OkHttpClient, service: Service): InetAddress? {
        val reply = soap(io, client, service, "GetExternalIPAddress", "")
        if (reply.fault != 0 || reply.body == null) return null
        val text = descendants(reply.body).firstOrNull { it.localName == "NewExternalIPAddress" }?.textContent?.trim() ?: return null
        val octets = text.split('.')
        if (octets.size != 4 || octets.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } || it.toIntOrNull() !in 0..255 }) return null
        return InetAddress.getByAddress(octets.map { it.toInt().toByte() }.toByteArray())
            .takeUnless { it.isAnyLocalAddress || it.isMulticastAddress }
    }

    private fun mappingArgs(local: InetAddress, internalPort: Int, externalPort: Int, lifetime: Long) =
        arg("NewRemoteHost", "") + arg("NewExternalPort", externalPort.toString()) + arg("NewProtocol", "UDP") +
            arg("NewInternalPort", internalPort.toString()) + arg("NewInternalClient", local.hostAddress!!) + arg("NewEnabled", "1") +
            arg("NewPortMappingDescription", "pinhole") + arg("NewLeaseDuration", lifetime.toString())

    private fun add(io: MappingIo, client: OkHttpClient, service: Service, local: InetAddress,
        port: Int, lifetime: Long, external: InetAddress): RouterLease? {
        if (service.type.endsWith(":2")) {
            val reply = soap(io, client, service, "AddAnyPortMapping", mappingArgs(local, port, port, lifetime))
            val reserved = reply.body?.let { descendants(it).firstOrNull { node -> node.localName == "NewReservedPort" }?.textContent?.trim()?.toIntOrNull() }
            if (reply.fault == 0 && reserved != null && reserved in 1..65535) return Lease(io, client, service, local, port, InetSocketAddress(external, reserved), lifetime, anyPort = true)
        }
        for (offset in 0..2) {
            val suggested = 1 + (port - 1 + offset) % 65535
            var requested = lifetime
            var reply = soap(io, client, service, "AddPortMapping", mappingArgs(local, port, suggested, requested))
            if (reply.fault == 725) { requested = 0; reply = soap(io, client, service, "AddPortMapping", mappingArgs(local, port, suggested, 0)) }
            if (reply.fault == 0) return Lease(io, client, service, local, port, InetSocketAddress(external, suggested), lifetime, false, requested)
            if (reply.fault != 718) break
        }
        return null
    }

    private class Lease(val io: MappingIo, val client: OkHttpClient, val service: Service, val local: InetAddress,
        val port: Int, override var external: InetSocketAddress, override val lifetimeSeconds: Long,
        val anyPort: Boolean, val requestedLifetime: Long = lifetimeSeconds) : RouterLease {
        override fun renew(): Boolean {
            val ip = externalIp(io, client, service) ?: return false
            val action = if (anyPort) "AddAnyPortMapping" else "AddPortMapping"
            val reply = soap(io, client, service, action, mappingArgs(local, port, external.port, requestedLifetime))
            if (reply.fault != 0) return false
            val assigned = if (anyPort) reply.body?.let { descendants(it).firstOrNull { node -> node.localName == "NewReservedPort" }?.textContent?.trim()?.toIntOrNull() } else external.port
            if (assigned == null || assigned !in 1..65535) return false
            external = InetSocketAddress(ip, assigned); return true
        }
        override fun release() {
            val bounded = client.newBuilder().callTimeout(1, TimeUnit.SECONDS).build()
            soap(null, bounded, service, "DeletePortMapping", arg("NewRemoteHost", "") + arg("NewExternalPort", external.port.toString()) + arg("NewProtocol", "UDP"))
        }
    }
}
