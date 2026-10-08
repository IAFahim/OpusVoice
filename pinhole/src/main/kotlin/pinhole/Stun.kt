package pinhole

import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

/** RFC 8489 binding messages. Transactions are matched by the transport, on its data socket. */
internal object Stun {
    private val cookie = byteArrayOf(0x21, 0x12, 0xa4.toByte(), 0x42)

    fun request(): ByteArray = ByteArray(20).also {
        SecureRandom().nextBytes(it)
        it[0] = 0; it[1] = 1; it[2] = 0; it[3] = 0
        cookie.copyInto(it, 4)
    }

    fun transaction(frame: ByteArray): String? =
        if (frame.size >= 20 && frame.copyOfRange(4, 8).contentEquals(cookie))
            IrohEncoding.hex(frame.copyOfRange(8, 20)) else null

    fun mappedAddress(frame: ByteArray, request: ByteArray): InetSocketAddress? {
        val expected = transaction(request) ?: return null
        if (frame.size < 20 || u16(frame, 0) != 0x0101 || transaction(frame) != expected) return null
        val length = u16(frame, 2)
        if (length % 4 != 0 || frame.size != 20 + length) return null
        var mapped: InetSocketAddress? = null
        var xorMapped: InetSocketAddress? = null
        var offset = 20
        while (offset < frame.size) {
            if (offset + 4 > frame.size) return null
            val type = u16(frame, offset)
            val size = u16(frame, offset + 2)
            val value = offset + 4
            val next = value + ((size + 3) and -4)
            if (next > frame.size) return null
            if (type == 0x0001 || type == 0x0020) {
                if (size < 4 || frame[value].toInt() != 0) return null
                val family = frame[value + 1].toInt() and 255
                val addressSize = when (family) { 1 -> 4; 2 -> 16; else -> return null }
                if (size != addressSize + 4) return null
                val xor = type == 0x0020
                val port = u16(frame, value + 2) xor if (xor) 0x2112 else 0
                if (port == 0) return null
                val address = ByteArray(addressSize) { i ->
                    (frame[value + 4 + i].toInt() xor if (xor) request[4 + i].toInt() else 0).toByte()
                }
                val ip = InetAddress.getByAddress(address)
                if (ip.isAnyLocalAddress || ip.isMulticastAddress) return null
                val endpoint = InetSocketAddress(ip, port)
                if (xor) xorMapped = xorMapped ?: endpoint else mapped = mapped ?: endpoint
            }
            offset = next
        }
        return xorMapped ?: mapped
    }

    private fun u16(frame: ByteArray, offset: Int): Int =
        ((frame[offset].toInt() and 255) shl 8) or (frame[offset + 1].toInt() and 255)
}
