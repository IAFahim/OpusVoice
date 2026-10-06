package pinhole

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One direction of an established session: a strictly-increasing frame counter (AES-GCM
 * nonces never repeat), a key ratchet every 2^28 frames, and an IPsec-style replay window
 * on receive. Wire-compatible with Pinhole.Net's FrameSealer.
 *
 * Wire layout of a sealed body: [counter u64 LE][ciphertext||tag(16)]; the AAD is the
 * 13-byte frame prefix (type + sender peer id + token) plus the counter.
 */
internal class FrameSealer(keys: SessionKeys, iAmLo: Boolean, sending: Boolean) {
    private companion object {
        const val EPOCH_FRAMES: ULong = 268435456uL // 2^28
        const val WINDOW: ULong = 64uL
        const val TAG_LENGTH = 16
        const val PREFIX_LENGTH = 13
        const val COUNTER_LENGTH = 8
    }

    private val transcriptHash = keys.transcriptHash
    private val nonceSalt = if (loToHi(keys, iAmLo, sending)) keys.loToHiSalt else keys.hiToLoSalt
    private var key = (if (loToHi(keys, iAmLo, sending)) keys.loToHiKey else keys.hiToLoKey).copyOf()
    private var previousKey: ByteArray? = null
    private var epoch = 0uL
    private var nextCounter = 1uL
    private var highest = 0uL
    private var bitmap = 0uL
    private val lock = Any()
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

    private fun loToHi(keys: SessionKeys, iAmLo: Boolean, sending: Boolean) = sending == iAmLo

    /** Seals [plaintext] into [destination] at [offset]; returns the sealed body length. */
    fun seal(
        destination: ByteArray,
        offset: Int,
        prefix13: ByteArray,
        plaintext: ByteArray,
        plaintextOffset: Int = 0,
        plaintextLength: Int = plaintext.size - plaintextOffset,
    ): Int {
        synchronized(lock) {
            val counter = nextCounter++
            var frameEpoch = (counter - 1uL) / EPOCH_FRAMES
            while (epoch < frameEpoch) {
                previousKey = key
                key = SessionKeys.rekey(key, transcriptHash)
                epoch++
            }

            writeULong(destination, offset, counter)
            val nonce = nonceSalt + uLongLe(counter)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(prefix13 + uLongLe(counter))
            val sealed = cipher.doFinal(plaintext, plaintextOffset, plaintextLength)
            System.arraycopy(sealed, 0, destination, offset + COUNTER_LENGTH, sealed.size)
            return COUNTER_LENGTH + sealed.size
        }
    }

    /** Opens a full wire frame ([prefix][counter][ciphertext||tag]); null when tampered,
     *  replayed, or from an unreachable epoch. */
    fun open(frame: ByteArray, length: Int): ByteArray? {
        if (length < PREFIX_LENGTH + COUNTER_LENGTH + TAG_LENGTH) {
            return null
        }

        val counter = readULongLe(frame, PREFIX_LENGTH)
        if (counter == 0uL) {
            return null
        }

        val frameEpoch = (counter - 1uL) / EPOCH_FRAMES
        synchronized(lock) {
            if (!replayEligible(counter)) {
                return null
            }

            // Candidate key without committing: current epoch, the next epoch's derivation
            // (adopted only on success), or the previous epoch's retained key.
            var activeKey = key
            var adopted = false
            when {
                frameEpoch == epoch -> {}
                frameEpoch == epoch + 1uL -> {
                    activeKey = SessionKeys.rekey(key, transcriptHash)
                    adopted = true
                }
                frameEpoch + 1uL == epoch -> {
                    activeKey = previousKey ?: return null
                }
                else -> return null
            }

            val nonce = nonceSalt + uLongLe(counter)
            val aad = frame.copyOf(PREFIX_LENGTH) + uLongLe(counter)
            val plaintext: ByteArray
            try {
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(activeKey, "AES"), GCMParameterSpec(128, nonce))
                cipher.updateAAD(aad)
                plaintext = cipher.doFinal(frame, PREFIX_LENGTH + COUNTER_LENGTH, length - PREFIX_LENGTH - COUNTER_LENGTH)
            } catch (_: Exception) {
                return null // forged or corrupted in flight: no counter burned, no key adopted
            }

            commitReplay(counter)
            if (adopted) {
                previousKey = key
                key = activeKey
                epoch = frameEpoch
            }
            return plaintext
        }
    }

    private fun replayEligible(counter: ULong): Boolean {
        if (counter > highest) {
            return true
        }
        val delta = highest - counter
        return delta < WINDOW && (bitmap shr delta.toInt() and 1uL) == 0uL
    }

    private fun commitReplay(counter: ULong) {
        if (counter > highest) {
            val shift = counter - highest
            bitmap = if (shift >= WINDOW) 1uL else (bitmap shl shift.toInt()) or 1uL
            highest = counter
        } else {
            bitmap = bitmap or (1uL shl (highest - counter).toInt())
        }
    }

    private fun writeULong(data: ByteArray, offset: Int, value: ULong) {
        var v = value
        for (i in 0 until 8) {
            data[offset + i] = (v and 0xFFuL).toByte()
            v = v shr 8
        }
    }
}
