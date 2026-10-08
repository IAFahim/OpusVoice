package pinhole

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*
import com.sun.net.httpserver.HttpServer

class PortMappingTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val nonce = ByteArray(12) { (it + 1).toByte() }

    // Independent RFC fixtures: offsets are deliberately not taken from the codec.
    private fun pcpReply(request: ByteArray, ip: InetAddress = InetAddress.getByName("203.0.113.7"), lifetime: Int = 2, port: Int = 45000): ByteArray =
        ByteArray(60).also {
            it[0] = 2; it[1] = 0x81.toByte()
            ByteBuffer.wrap(it).putInt(4, lifetime).putInt(8, 123)
            request.copyOfRange(24, 36).copyInto(it, 24)
            it[36] = 17; request.copyOfRange(40, 42).copyInto(it, 40)
            ByteBuffer.wrap(it).putShort(42, port.toShort())
            if (ip.address.size == 4) { it[54] = -1; it[55] = -1; ip.address.copyInto(it, 56) }
            else ip.address.copyInto(it, 44)
        }

    @Test fun pcpUsesTheRfcHeaderClientAddressNonceAndMapBody() {
        val request = RouterProtocols.pcpRequest(nonce, loopback, 31000, 7200)
        assertEquals(60, request.size)
        assertEquals(7200, ByteBuffer.wrap(request).getInt(4))
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 127, 0, 0, 1), request.copyOfRange(8, 24))
        assertContentEquals(nonce, request.copyOfRange(24, 36))
        assertEquals(17, request[36].toInt())
        assertEquals(31000, ByteBuffer.wrap(request).getShort(40).toInt() and 65535)
        val grant = assertNotNull(RouterProtocols.pcpResponse(pcpReply(request), request))
        assertEquals(InetSocketAddress("203.0.113.7", 45000), grant.external)
    }

    @Test fun pcpRejectsWrongNonceProtocolPortResultLifetimeAndUnicastAddress() {
        val request = RouterProtocols.pcpRequest(nonce, loopback, 31000, 60)
        val valid = pcpReply(request)
        for ((offset, value) in listOf(0 to 1, 1 to 1, 3 to 2, 24 to 99, 36 to 6, 40 to 0)) {
            assertNull(RouterProtocols.pcpResponse(valid.copyOf().also { it[offset] = value.toByte() }, request), "offset $offset")
        }
        assertNull(RouterProtocols.pcpResponse(valid.copyOf(59), request))
        assertNull(RouterProtocols.pcpResponse(pcpReply(request, lifetime = 0), request))
        assertNull(RouterProtocols.pcpResponse(pcpReply(request, port = 0), request))
        assertNull(RouterProtocols.pcpResponse(pcpReply(request, ip = InetAddress.getByName("0.0.0.0")), request))
        assertNull(RouterProtocols.pcpResponse(pcpReply(request, ip = InetAddress.getByName("224.0.0.1")), request))
    }

    @Test fun pcpDecodesNativeIpv6AndKeepsTheNonceOnRenewAndRelease() {
        Gateway { request -> pcpReply(request, InetAddress.getByName("2001:db8::7")) }.use { gateway ->
            MappingIo().use { io ->
                val lease = assertNotNull(RouterProtocols.pcp(io, listOf(gateway.address), 31000, 2))
                assertEquals(InetSocketAddress(InetAddress.getByName("2001:db8::7"), 45000), lease.external)
                assertTrue(lease.renew()); lease.release()
                val packets = (1..3).map { gateway.requests.poll(2, TimeUnit.SECONDS)!! }
                assertTrue(packets.all { (ByteBuffer.wrap(it).getShort(40).toInt() and 65535) == 31000 })
                assertContentEquals(packets[0].copyOfRange(24, 36), packets[1].copyOfRange(24, 36))
                assertContentEquals(packets[0].copyOfRange(24, 36), packets[2].copyOfRange(24, 36))
                assertEquals(0, ByteBuffer.wrap(packets[2]).getInt(4))
            }
        }
    }

    @Test fun udpRepliesArePinnedToTheGatewayAndBadTransactionsAreIgnored() {
        DatagramSocket(InetSocketAddress(loopback, 0)).use { other ->
            val first = AtomicInteger()
            Gateway { request, destination, socket ->
                val good = pcpReply(request)
                if (first.getAndIncrement() == 0) {
                    other.send(DatagramPacket(pcpReply(request, port = 49999), 60, destination))
                    val bad = pcpReply(request, port = 49998).also { it[24] = (it[24].toInt() xor 1).toByte() }
                    socket.send(DatagramPacket(bad, bad.size, destination))
                }
                good
            }.use { gateway ->
                MappingIo().use { io ->
                    val lease = assertNotNull(RouterProtocols.pcp(io, listOf(gateway.address), 31000, 2))
                    assertEquals(45000, lease.external.port); lease.release()
                }
            }
        }
    }

    @Test fun lostRequestsAreRetriedWithTheSamePcpNonce() {
        val attempts = AtomicInteger()
        Gateway { request -> if (attempts.getAndIncrement() == 0) null else pcpReply(request) }.use { gateway ->
            MappingIo().use { io ->
                val lease = assertNotNull(RouterProtocols.pcp(io, listOf(gateway.address), 31000, 2))
                val first = gateway.requests.poll(2, TimeUnit.SECONDS)!!
                val second = gateway.requests.poll(2, TimeUnit.SECONDS)!!
                assertContentEquals(first, second); lease.release()
            }
        }
    }

    private fun pmpReply(request: ByteArray): ByteArray = if (request.size == 2) byteArrayOf(0, -128, 0, 0, 0, 0, 0, 123, -53, 0, 113, 8)
        else ByteArray(16).also {
            it[1] = -127; request.copyOfRange(4, 6).copyInto(it, 8)
            ByteBuffer.wrap(it).putShort(10, 45001.toShort()).putInt(12, if (ByteBuffer.wrap(request).getInt(8) == 0) 0 else 2)
        }

    @Test fun natPmpUsesTheAudioPortAndValidatesTheInternalPort() {
        val request = RouterProtocols.natPmpRequest(31000, 7200)
        assertContentEquals(byteArrayOf(0, 1, 0, 0, 0x79, 0x18, 0, 0, 0, 0, 0x1c, 0x20), request)
        val ip = InetAddress.getByName("203.0.113.8")
        assertEquals(45001, assertNotNull(RouterProtocols.natPmpResponse(pmpReply(request), request, ip)).external.port)
        assertNull(RouterProtocols.natPmpResponse(pmpReply(request).also { it[8] = 0 }, request, ip))
        Gateway { bytes -> pmpReply(bytes) }.use { gateway ->
            MappingIo().use { io ->
                val lease = assertNotNull(RouterProtocols.natPmp(io, listOf(gateway.address), 31000, 2))
                assertTrue(lease.renew()); lease.release()
                val packets = (1..5).map { gateway.requests.poll(2, TimeUnit.SECONDS)!! }
                assertEquals(listOf(2, 12, 2, 12, 12), packets.map { it.size })
                assertEquals(0, ByteBuffer.wrap(packets.last()).getInt(8))
            }
        }
    }

    @Test fun defaultMapperPublishesRenewsWithdrawsExpiredMappingsAndReleases() {
        val maps = AtomicInteger()
        Gateway { request -> if (ByteBuffer.wrap(request).getInt(4) == 0) pcpReply(request, lifetime = 0)
            else if (maps.incrementAndGet() == 1) pcpReply(request, lifetime = 1) else null }.use { gateway ->
            val observed = LinkedBlockingQueue<InetSocketAddress>()
            val events = LinkedBlockingQueue<String>()
            val mapper = RouterPortMapper(31000, PortMappingOptions(gateways = { listOf(gateway.address) }, leaseSeconds = 1)) {
                events.add(it?.toString() ?: "withdrawn")
                if (it != null) observed.add(it)
            }
            try {
                assertEquals(45000, observed.poll(2, TimeUnit.SECONDS)!!.port)
                assertNotNull(events.poll(1, TimeUnit.SECONDS))
                assertEquals("withdrawn", events.poll(1600, TimeUnit.MILLISECONDS))
                assertNull(mapper.current)
            } finally { mapper.close(); assertTrue(mapper.awaitStopped()) }
            assertTrue(gateway.requests.toList().any { ByteBuffer.wrap(it).getInt(4) == 0 })
        }
    }

    @Test fun networkChangeRetractsAndReplacesTheOldMapping() {
        Gateway { request -> pcpReply(request, port = 45000) }.use { old ->
            Gateway { request -> pcpReply(request, port = 45002) }.use { fresh ->
                val routes = java.util.concurrent.atomic.AtomicReference(listOf(old.address))
                val events = LinkedBlockingQueue<String>()
                val mapper = RouterPortMapper(31000, PortMappingOptions(gateways = { routes.get() })) { events.add(it?.port?.toString() ?: "withdrawn") }
                try {
                    assertEquals("45000", events.poll(2, TimeUnit.SECONDS))
                    routes.set(listOf(fresh.address))
                    assertEquals("withdrawn", events.poll(2, TimeUnit.SECONDS))
                    assertEquals("45002", events.poll(2, TimeUnit.SECONDS))
                } finally { mapper.close(); assertTrue(mapper.awaitStopped()) }
                assertTrue(old.requests.toList().any { ByteBuffer.wrap(it).getInt(4) == 0 })
            }
        }
    }

    @Test fun closeCancelsAnInFlightGatewayReceiveAndDisabledMappingMakesNoRequests() {
        Gateway { _: ByteArray -> null }.use { gateway ->
            val mapper = RouterPortMapper(31000, PortMappingOptions(gateways = { listOf(gateway.address) })) { }
            assertNotNull(gateway.requests.poll(2, TimeUnit.SECONDS)); mapper.close(); assertTrue(mapper.awaitStopped())
            val disabled = RouterPortMapper(31000, PortMappingOptions(enabled = false, gateways = { error("disabled gateway lookup") })) { error("disabled publication") }
            disabled.close(); assertTrue(disabled.awaitStopped())
        }
    }

    @Test fun nativeIpv6GatewayReceivesTheActualIpv6ClientAddress() {
        val local6 = InetAddress.getByName("::1")
        Gateway(local6) { request -> pcpReply(request, InetAddress.getByName("2001:db8::9")) }.use { gateway ->
            MappingIo().use { io ->
                val lease = assertNotNull(RouterProtocols.pcp(io, listOf(gateway.address), 31000, 2))
                val request = gateway.requests.poll(2, TimeUnit.SECONDS)!!
                assertContentEquals(local6.address, request.copyOfRange(8, 24))
                assertEquals(InetSocketAddress(InetAddress.getByName("2001:db8::9"), 45000), lease.external)
                lease.release()
            }
        }
    }

    @Test fun aPcpRefusalFallsBackToNatPmpWithoutDelayingTheCaller() {
        Gateway { request -> if (request[0].toInt() == 2) ByteArray(24).also { it[0] = 2; it[1] = -127; it[3] = 1 }
            else pmpReply(request) }.use { gateway ->
            val events = LinkedBlockingQueue<InetSocketAddress>()
            val mapper = RouterPortMapper(31000, PortMappingOptions(gateways = { listOf(gateway.address) })) { if (it != null) events.add(it) }
            try { assertEquals(45001, events.poll(2, TimeUnit.SECONDS)!!.port) }
            finally { mapper.close(); assertTrue(mapper.awaitStopped()) }
            assertTrue(gateway.requests.toList().any { it[0].toInt() == 2 })
            assertTrue(gateway.requests.toList().any { it.size == 12 && it[0].toInt() == 0 })
        }
    }

    @Test fun upnpV2MapsRenewsAndDeletesItsReservedPort() {
        Igd(2).use { gateway ->
            MappingIo().use { io ->
                val lease = assertNotNull(UpnpPortMapping.discover(io, gateway.ssdp.address, 31000, 2))
                assertEquals(45003, lease.external.port); assertTrue(lease.renew()); lease.release()
                assertTrue(gateway.actions.contains("AddAnyPortMapping"))
                assertTrue(gateway.actions.contains("DeletePortMapping"))
                assertTrue(gateway.bodies.filter { it.contains("NewInternalPort") }.all { it.contains("<NewInternalPort>31000</NewInternalPort>") })
            }
        }
    }

    @Test fun upnpV1RetriesConflictsAndSupportsPermanentOnlyLeases() {
        Igd(1, conflict = true, permanent = true).use { gateway ->
            MappingIo().use { io ->
                val lease = assertNotNull(UpnpPortMapping.discover(io, gateway.ssdp.address, 31000, 2))
                assertEquals(31001, lease.external.port); assertTrue(lease.renew()); lease.release()
                assertTrue(gateway.bodies.any { it.contains("<NewLeaseDuration>0</NewLeaseDuration>") })
            }
        }
    }

    @Test fun defaultStrategyFallsThroughBothUdpProtocolsToUpnp() {
        Gateway { request -> if (request[0].toInt() == 2) ByteArray(24).also { it[0] = 2; it[1] = -127; it[3] = 1 }
            else ByteArray(8).also { it[1] = -128; it[3] = 5 } }.use { udp ->
            Igd(2).use { igd ->
                val events = LinkedBlockingQueue<InetSocketAddress>()
                val mapper = RouterPortMapper(31000, PortMappingOptions(gateways = { listOf(udp.address) }, ssdpTarget = igd.ssdp.address)) {
                    if (it != null) events.add(it)
                }
                try { assertEquals(45003, events.poll(3, TimeUnit.SECONDS)!!.port) }
                finally { mapper.close(); assertTrue(mapper.awaitStopped()) }
                assertTrue(igd.actions.contains("DeletePortMapping"))
            }
        }
    }

    @Test fun hostileXmlAndCrossHostControlUrlsCannotCreateMappings() {
        assertNull(UpnpPortMapping.xml("<!DOCTYPE root [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><root>&x;</root>"))
        assertFalse(UpnpPortMapping.safeUri(URI("http://user:password@127.0.0.1/control")))
        assertFalse(UpnpPortMapping.safeUri(URI("http://127.0.0.2/control"), "127.0.0.1"))
        Igd(2, hostileControl = true).use { gateway ->
            MappingIo().use { io -> assertNull(UpnpPortMapping.discover(io, gateway.ssdp.address, 31000, 2)) }
            assertTrue(gateway.actions.isEmpty())
        }
    }

    private class Gateway(listen: InetAddress, private val respond: (ByteArray, InetSocketAddress, DatagramSocket) -> ByteArray?) : Closeable {
        constructor(respond: (ByteArray) -> ByteArray?) : this(InetAddress.getByName("127.0.0.1"), { bytes, _, _ -> respond(bytes) })
        constructor(respond: (ByteArray, InetSocketAddress, DatagramSocket) -> ByteArray?) : this(InetAddress.getByName("127.0.0.1"), respond)
        constructor(listen: InetAddress, respond: (ByteArray) -> ByteArray?) : this(listen, { bytes, _, _ -> respond(bytes) })
        val socket = DatagramSocket(InetSocketAddress(listen, 0))
        val address get() = socket.localSocketAddress as InetSocketAddress
        val requests = LinkedBlockingQueue<ByteArray>()
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) try {
                val packet = DatagramPacket(ByteArray(2048), 2048); socket.receive(packet)
                val bytes = packet.data.copyOf(packet.length); requests.add(bytes)
                respond(bytes, packet.socketAddress as InetSocketAddress, socket)?.let { socket.send(DatagramPacket(it, it.size, packet.socketAddress)) }
            } catch (_: java.io.IOException) { }
        }
        override fun close() { socket.close(); worker.join(1000) }
    }

    private class Igd(version: Int, conflict: Boolean = false, permanent: Boolean = false, hostileControl: Boolean = false) : Closeable {
        val actions = java.util.concurrent.CopyOnWriteArrayList<String>()
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val ssdp: Gateway
        init {
            http.createContext("/device.xml") { exchange ->
                val control = if (hostileControl) "http://127.0.0.2/control" else "/control"
                val response = "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><device><serviceList><service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:$version</serviceType><controlURL>$control</controlURL></service></serviceList></device></root>".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong()); exchange.responseBody.use { it.write(response) }
            }
            http.createContext("/control") { exchange ->
                val action = exchange.requestHeaders.getFirst("SOAPAction").trim('"').substringAfter('#')
                val body = exchange.requestBody.bufferedReader().use { it.readText() }; actions.add(action); bodies.add(body)
                val fault = if (action == "AddPortMapping" && conflict && body.contains("<NewExternalPort>31000</NewExternalPort>")) 718
                    else if (action == "AddPortMapping" && permanent && !body.contains("<NewLeaseDuration>0</NewLeaseDuration>")) 725 else 0
                val inner = if (fault != 0) "<s:Fault><detail><UPnPError><errorCode>$fault</errorCode></UPnPError></detail></s:Fault>"
                    else "<u:${action}Response xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:$version\">" +
                        when (action) { "GetExternalIPAddress" -> "<NewExternalIPAddress>203.0.113.8</NewExternalIPAddress>"; "AddAnyPortMapping" -> "<NewReservedPort>45003</NewReservedPort>"; else -> "" } + "</u:${action}Response>"
                val response = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>$inner</s:Body></s:Envelope>".toByteArray()
                exchange.sendResponseHeaders(if (fault == 0) 200 else 500, response.size.toLong()); exchange.responseBody.use { it.write(response) }
            }
            http.start()
            ssdp = Gateway { _: ByteArray -> "HTTP/1.1 200 OK\r\nLOCATION: http://127.0.0.1:${http.address.port}/device.xml\r\n\r\n".toByteArray() }
        }
        override fun close() { ssdp.close(); http.stop(0) }
    }
}
