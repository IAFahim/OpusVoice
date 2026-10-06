package pinhole

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FrameSealerTest {

    private fun keys(): SessionKeys {
        val aEph = Crypto.randomPrivateKey()
        val aStatic = Crypto.randomPrivateKey()
        val bEph = Crypto.randomPrivateKey()
        val bStatic = Crypto.randomPrivateKey()
        return SessionKeys.derive(1000uL, aEph, aStatic, 2000uL, Crypto.publicKey(bEph), Crypto.publicKey(bStatic))
    }

    @Test
    fun sealOpenRoundTripAcrossRoles() {
        val k = keys()
        val aSend = FrameSealer(k, iAmLo = true, sending = true)
        val bRecv = FrameSealer(k, iAmLo = false, sending = false)
        val bSend = FrameSealer(k, iAmLo = false, sending = true)
        val aRecv = FrameSealer(k, iAmLo = true, sending = false)

        val prefix = ByteArray(13) { (it * 3).toByte() }
        val payload = ByteArray(300) { (it % 251).toByte() }

        for (sealer in listOf(aSend, bSend)) {
            val receiver = if (sealer === aSend) bRecv else aRecv
            val frame = ByteArray(13 + 8 + payload.size + 16)
            System.arraycopy(prefix, 0, frame, 0, 13) // the AAD is the frame's own prefix
            val sealed = sealer.seal(frame, 13, frame.copyOf(13), payload)
            assertEquals(8 + payload.size + 16, sealed)
            val opened = assertNotNull(receiver.open(frame, 13 + sealed))
            assertContentEquals(payload, opened)
        }
    }

    @Test
    fun tamperedFrameIsRefused() {
        val k = keys()
        val send = FrameSealer(k, iAmLo = true, sending = true)
        val recv = FrameSealer(k, iAmLo = false, sending = false)
        val prefix = ByteArray(13)
        val frame = ByteArray(13 + 8 + 32 + 16)
        val sealed = send.seal(frame, 13, prefix, ByteArray(32) { 7 })
        frame[20] = (frame[20].toInt() xor 1).toByte() // flip one ciphertext bit
        assertNull(recv.open(frame, 13 + sealed))
    }

    @Test
    fun tamperedPrefixIsRefused() {
        val k = keys()
        val send = FrameSealer(k, iAmLo = true, sending = true)
        val recv = FrameSealer(k, iAmLo = false, sending = false)
        val frame = ByteArray(13 + 8 + 16 + 16)
        java.util.Arrays.fill(frame, 0, 13, 1)
        val sealed = send.seal(frame, 13, frame.copyOf(13), ByteArray(16) { 9 })
        val mutated = frame.copyOf().also { it[5] = 0x77 } // AAD change, ciphertext intact
        assertNull(recv.open(mutated, 13 + sealed))
    }

    @Test
    fun replayedFrameIsRefused() {
        val k = keys()
        val send = FrameSealer(k, iAmLo = true, sending = true)
        val recv = FrameSealer(k, iAmLo = false, sending = false)
        val frame = ByteArray(13 + 8 + 8 + 16)
        val sealed = send.seal(frame, 13, ByteArray(13), ByteArray(8) { 3 })
        assertNotNull(recv.open(frame, 13 + sealed))
        assertNull(recv.open(frame, 13 + sealed)) // same counter again
    }

    @Test
    fun counterStartsAtOneAndIncrements() {
        val k = keys()
        val send = FrameSealer(k, iAmLo = true, sending = true)
        val frame = ByteArray(13 + 8 + 4 + 16)
        send.seal(frame, 13, ByteArray(13), ByteArray(4))
        assertEquals(1uL, readULongLe(frame, 13))
        send.seal(frame, 13, ByteArray(13), ByteArray(4))
        assertEquals(2uL, readULongLe(frame, 13))
    }

    @Test
    fun lowOrderPointDerivationFails() {
        assertFailsWith<IllegalArgumentException> {
            SessionKeys.derive(
                1000uL,
                Crypto.randomPrivateKey(),
                Crypto.randomPrivateKey(),
                2000uL,
                ByteArray(32), // zero point
                Crypto.randomPrivateKey().let(Crypto::publicKey),
            )
        }
    }
}
