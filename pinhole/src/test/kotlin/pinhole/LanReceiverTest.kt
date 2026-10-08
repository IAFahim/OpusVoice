package pinhole

import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanReceiverTest {
    private val id = "1234567812345678"
    private val key = ByteArray(32) { (it + 1).toByte() }
    private fun attributes(id: String = this.id, key: ByteArray = this.key) = mapOf(
        "id" to id.toByteArray(), "key" to key.joinToString("") { "%02x".format(it) }.toByteArray(), "hint" to "1".toByteArray())
    private fun receiver(id: String = this.id, address: String = "192.168.3.5", key: ByteArray = this.key) =
        assertNotNull(LanReceiver.fromService(id, LanReceiver.SERVICE_TYPE, 7001, listOf(InetAddress.getByName(address)), attributes(id, key)))

    @Test fun validatesEncryptedReceiverAndEncodesDotNetTicketLayout() {
        val receiver = receiver()
        val payload = org.bouncycastle.util.encoders.Base64.decode(receiver.ticket.substringAfter(':').replace('-', '+').replace('_', '/') + "==")
        // Independent expected v2 envelope: flags, little-endian id, hint, one A candidate.
        val expected = byteArrayOf(2, 1, 0x78, 0x56, 0x34, 0x12, 0x78, 0x56, 0x34, 0x12, 1, 1, 4,
            192.toByte(), 168.toByte(), 3, 5, 0x1b, 0x59, 1) + key
        assertContentEquals(expected, payload)
        val ticket = ConnectionString.parse(receiver.ticket)
        assertEquals(id.toULong(16), ticket.peerId)
        assertContentEquals(key, ticket.staticKey)
        assertEquals(7001, ticket.candidates.single().address.port)
    }

    @Test fun rejectsMalformedServiceKeysIdsHintsPortsAndNonUnicastAddresses() {
        val ip = listOf(InetAddress.getByName("192.168.3.5"))
        fun parse(name: String = id, type: String = LanReceiver.SERVICE_TYPE, port: Int = 7001,
                  addresses: List<InetAddress> = ip, attrs: Map<String, ByteArray> = attributes()) =
            LanReceiver.fromService(name, type, port, addresses, attrs)
        assertNull(parse(name = " $id"))
        assertNull(parse(type = "_http._tcp."))
        assertNull(parse(port = 0))
        assertNull(parse(port = 65536))
        assertNull(parse(attrs = attributes().minus("key")))
        assertNull(parse(attrs = attributes() + ("key" to "z".repeat(64).toByteArray())))
        assertNull(parse(attrs = attributes() + ("key" to "0".repeat(64).toByteArray())))
        assertNull(parse(attrs = attributes() + ("id" to "1234567812345679".toByteArray())))
        assertNull(parse(attrs = attributes() + ("hint" to "9".toByteArray())))
        for (value in listOf("0.0.0.0", "::", "224.1.2.3", "ff02::fb", "255.255.255.255"))
            assertNull(parse(addresses = listOf(InetAddress.getByName(value))))
        assertNotNull(parse(type = "_PINHOLE._UDP.LOCAL."))
    }

    @Test fun preservesPlatformScopeAndScopesBareIpv6_WithoutSerializingLocalIndexes() {
        val scoped = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::abcd").address, 7)
        val bare = InetAddress.getByName("fe80::beef")
        val receiver = assertNotNull(LanReceiver.fromService(id, LanReceiver.SERVICE_TYPE, 7001,
            listOf(scoped, bare), attributes(), scope = 13))
        assertEquals(listOf(7, 13), receiver.addresses.map { (it.address as Inet6Address).scopeId })
        assertTrue(ConnectionString.parse(receiver.ticket).candidates.all { (it.address.address as Inet6Address).scopeId == 0 })
    }

    @Test fun catalogMergesNetworksRemovesLostServicesAndRejectsKeyReplacement() {
        val catalog = LanReceiverCatalog()
        val first = receiver()
        catalog.update("one", first)
        catalog.update("two", receiver(address = "fe80::abcd"))
        assertEquals(2, catalog.snapshot().single().addresses.size)
        catalog.update("one", receiver(address = "192.168.3.99", key = ByteArray(32) { 44 }))
        assertEquals(first.addresses.first(), catalog.snapshot().single().addresses.first())
        catalog.remove("one")
        assertTrue(catalog.snapshot().single().addresses.single().address.isLinkLocalAddress)
        catalog.clear()
        assertTrue(catalog.snapshot().isEmpty())
    }

    @Test fun catalogAndAddressSetsAreBoundedAndAllTicketVersionsRoundTrip() {
        val catalog = LanReceiverCatalog()
        repeat(80) { index -> val name = (index + 1).toString(16).padStart(16, '0'); catalog.update(name, receiver(id = name)) }
        assertEquals(32, catalog.snapshot().size)
        val cs = ConnectionString.parse(receiver().ticket)
        for ((static, endpoint) in listOf(null to null, key to null, key to ByteArray(32) { 9 })) {
            val ticket = ConnectionString(cs.peerId, cs.natHint, cs.candidates, static, endpoint).toString()
            val parsed = ConnectionString.parse(ticket)
            assertEquals(cs.peerId, parsed.peerId)
            assertContentEquals(static, parsed.staticKey)
            assertContentEquals(endpoint, parsed.endpointKey)
        }
    }
}
