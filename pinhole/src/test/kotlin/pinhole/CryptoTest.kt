package pinhole

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CryptoTest {

    @Test
    fun x25519Rfc7748Vector() {
        // RFC 7748 §6.1: Alice's private + Bob's public → shared secret.
        val alicePrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPublic = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expected = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        assertContentEquals(expected, Crypto.agree(alicePrivate, bobPublic))
    }

    @Test
    fun x25519IsSymmetric() {
        val aPriv = Crypto.randomPrivateKey()
        val bPriv = Crypto.randomPrivateKey()
        val ab = Crypto.agree(aPriv, Crypto.publicKey(bPriv))
        val ba = Crypto.agree(bPriv, Crypto.publicKey(aPriv))
        assertContentEquals(ab, ba)
    }

    @Test
    fun hkdfRfc5869Vector() {
        // RFC 5869 test case 1 (SHA-256).
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val okm = Crypto.hkdf(ikm, salt, info, 42)
        assertContentEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    @Test
    fun sessionKeysAgreeFromBothSides() {
        val aEph = Crypto.randomPrivateKey()
        val aStatic = Crypto.randomPrivateKey()
        val bEph = Crypto.randomPrivateKey()
        val bStatic = Crypto.randomPrivateKey()
        val aId = 1000uL
        val bId = 2000uL

        val fromA = SessionKeys.derive(aId, aEph, aStatic, bId, Crypto.publicKey(bEph), Crypto.publicKey(bStatic))
        val fromB = SessionKeys.derive(bId, bEph, bStatic, aId, Crypto.publicKey(aEph), Crypto.publicKey(aStatic))

        assertContentEquals(fromA.transcriptHash, fromB.transcriptHash)
        assertContentEquals(fromA.loToHiKey, fromB.loToHiKey)
        assertContentEquals(fromA.hiToLoKey, fromB.hiToLoKey)
        assertContentEquals(fromA.loToHiSalt, fromB.loToHiSalt)
        assertContentEquals(fromA.hiToLoSalt, fromB.hiToLoSalt)
        assertContentEquals(fromA.loConfirm, fromB.loConfirm)
        assertContentEquals(fromA.hiConfirm, fromB.hiConfirm)

        // lo's confirm is loConfirm from both perspectives.
        assertEquals(fromA.loConfirm.size, 16)
    }

    @Test
    fun lowOrderPointIsRefused() {
        assertFailsWith<IllegalArgumentException> {
            Crypto.agree(Crypto.randomPrivateKey(), ByteArray(32))
        }
    }

    @Test
    fun uLongLeRoundTrips() {
        val value = 0xFEDCBA9876543210uL
        assertContentEquals(hex("1032547698badcfe"), uLongLe(value))
        assertEquals(value, readULongLe(uLongLe(value), 0))
        assertTrue(true)
    }

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
