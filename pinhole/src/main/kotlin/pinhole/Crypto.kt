package pinhole

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** X25519, SHA-256, HMAC and RFC 5869 HKDF primitives. */
internal object Crypto {
    private val random = SecureRandom()

    fun randomPrivateKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun randomPeerId(): ULong {
        while (true) {
            val id = random.nextLong().toULong()
            if (id != 0uL) return id
        }
    }

    fun randomToken(): Int {
        while (true) {
            val token = random.nextInt()
            if (token != 0) return token
        }
    }

    fun publicKey(privateKey: ByteArray): ByteArray =
        X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    /** Raw X25519; refuses all-zero (low-order point) agreements like Pinhole.Net. */
    fun agree(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val shared = ByteArray(32)
        try {
            agreement.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), shared, 0)
        } catch (e: IllegalStateException) {
            // BouncyCastle rejects small-subgroup (all-zero) agreements itself.
            throw IllegalArgumentException("peer public key is a low-order point", e)
        }
        require(shared.any { it.toInt() != 0 }) { "peer public key is a low-order point" }
        return shared
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            digest.update(part)
        }
        return digest.digest()
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** RFC 5869: extract with salt, expand with info. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        var previous = ByteArray(0)
        val out = ByteArray(length)
        var generated = 0
        var counter = 1
        while (generated < length) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
            val chunk = minOf(previous.size, length - generated)
            System.arraycopy(previous, 0, out, generated, chunk)
            generated += chunk
            counter++
        }
        return out
    }
}

internal fun uLongLe(value: ULong): ByteArray {
    val out = ByteArray(8)
    var v = value
    for (i in 0 until 8) {
        out[i] = (v and 0xFFuL).toByte()
        v = v shr 8
    }
    return out
}

internal fun readULongLe(data: ByteArray, offset: Int): ULong {
    var value = 0uL
    for (i in 7 downTo 0) {
        value = (value shl 8) or (data[offset + i].toULong() and 0xFFuL)
    }
    return value
}

internal fun readIntLe(data: ByteArray, offset: Int): Int {
    return (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8) or
        ((data[offset + 2].toInt() and 0xFF) shl 16) or
        ((data[offset + 3].toInt() and 0xFF) shl 24)
}

internal fun writeIntLe(data: ByteArray, offset: Int, value: Int) {
    data[offset] = (value and 0xFF).toByte()
    data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

/** Session keys one handshake produced. "lo" is the side with the smaller peer id. */
internal class SessionKeys(
    val transcriptHash: ByteArray,
    val loToHiKey: ByteArray,
    val hiToLoKey: ByteArray,
    val loToHiSalt: ByteArray,
    val hiToLoSalt: ByteArray,
    val loConfirm: ByteArray,
    val hiConfirm: ByteArray,
) {
    companion object {
        private val INFO = "pinhole-session-v1".toByteArray(Charsets.US_ASCII)
        private val LABEL = "pinhole-hs1".toByteArray(Charsets.US_ASCII)
        private val REKEY_INFO = "pinhole-rekey-v1".toByteArray(Charsets.US_ASCII)

        /**
         * Triple-DH key schedule (Noise-style) over one ephemeral and one static X25519 key
         * per side, folded through HKDF-SHA256 with the transcript hash as salt. Both peer
         * ids order the roles, so both endpoints derive identical keys no matter who dialed.
         */
        fun derive(
            myPeerId: ULong,
            myEphPrivate: ByteArray,
            myStaticPrivate: ByteArray,
            peerPeerId: ULong,
            peerEphPublic: ByteArray,
            peerStaticPublic: ByteArray,
        ): SessionKeys {
            val iAmLo = myPeerId < peerPeerId
            val ee = Crypto.agree(myEphPrivate, peerEphPublic)
            val se = if (iAmLo) {
                Crypto.agree(myStaticPrivate, peerEphPublic)
            } else {
                Crypto.agree(myEphPrivate, peerStaticPublic)
            }
            val es = if (iAmLo) {
                Crypto.agree(myEphPrivate, peerStaticPublic)
            } else {
                Crypto.agree(myStaticPrivate, peerEphPublic)
            }
            val ikm = ee + se + es

            val myEphPublic = Crypto.publicKey(myEphPrivate)
            val myStaticPublic = Crypto.publicKey(myStaticPrivate)
            val loPeerId = if (iAmLo) myPeerId else peerPeerId
            val hiPeerId = if (iAmLo) peerPeerId else myPeerId
            val th = transcript(
                loPeerId, hiPeerId,
                if (iAmLo) myEphPublic else peerEphPublic,
                if (iAmLo) myStaticPublic else peerStaticPublic,
                if (iAmLo) peerEphPublic else myEphPublic,
                if (iAmLo) peerStaticPublic else myStaticPublic,
            )

            val okm = Crypto.hkdf(ikm, th, INFO, 136)
            return SessionKeys(
                transcriptHash = th,
                loToHiKey = okm.copyOfRange(0, 32),
                hiToLoKey = okm.copyOfRange(32, 64),
                loToHiSalt = okm.copyOfRange(128, 132),
                hiToLoSalt = okm.copyOfRange(132, 136),
                loConfirm = confirm(okm.copyOfRange(64, 96), th),
                hiConfirm = confirm(okm.copyOfRange(96, 128), th),
            )
        }

        private fun transcript(
            loPeerId: ULong,
            hiPeerId: ULong,
            loEph: ByteArray,
            loStatic: ByteArray,
            hiEph: ByteArray,
            hiStatic: ByteArray,
        ): ByteArray = Crypto.sha256(
            LABEL, uLongLe(loPeerId), uLongLe(hiPeerId), loEph, loStatic, hiEph, hiStatic,
        )

        private fun confirm(key: ByteArray, transcript: ByteArray): ByteArray =
            Crypto.hmacSha256(key, transcript).copyOfRange(0, 16)

        fun rekey(currentKey: ByteArray, transcriptHash: ByteArray): ByteArray =
            Crypto.hkdf(currentKey, transcriptHash, REKEY_INFO, 32)
    }
}
