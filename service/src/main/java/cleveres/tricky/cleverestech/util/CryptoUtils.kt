package cleveres.tricky.cleverestech.util

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

object CryptoUtils {
    fun generateX25519KeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("X25519")
        return kpg.generateKeyPair()
    }

    fun generateEd25519KeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        return kpg.generateKeyPair()
    }

    fun ecdhDeriveKey(
        privateKey: PrivateKey,
        publicKeyBytes: ByteArray,
    ): ByteArray {
        val keyFactory = KeyFactory.getInstance("X25519")
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes))

        val keyAgreement = KeyAgreement.getInstance("X25519")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(publicKey, true)

        return keyAgreement.generateSecret()
    }

    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outLen: Int,
    ): ByteArray {
        // Extract
        val extractMac = Mac.getInstance("HmacSHA256")
        val saltKey = SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256")
        extractMac.init(saltKey)
        val prk = extractMac.doFinal(ikm)

        // Expand
        val expandMac = Mac.getInstance("HmacSHA256")
        val prkKey = SecretKeySpec(prk, "HmacSHA256")
        expandMac.init(prkKey)

        val result = ByteArray(outLen)
        var t = ByteArray(0)
        var generatedBytes = 0
        var blockIndex = 1

        while (generatedBytes < outLen) {
            expandMac.update(t)
            expandMac.update(info)
            expandMac.update(blockIndex.toByte())
            t = expandMac.doFinal()

            val toCopy = Math.min(t.size, outLen - generatedBytes)
            System.arraycopy(t, 0, result, generatedBytes, toCopy)
            generatedBytes += toCopy
            blockIndex++
        }

        return result
    }

    fun aesGcmEncrypt(
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun decodeSimpleCborArray(cborBytes: ByteArray): List<ByteArray> {
        val elements = mutableListOf<ByteArray>()
        var offset = 0

        if (offset >= cborBytes.size) return elements

        val initialByte = cborBytes[offset].toInt() and 0xFF
        offset++

        val arraySize: Int
        if (initialByte in 0x80..0x97) {
            arraySize = initialByte - 0x80
        } else if (initialByte == 0x98) {
            arraySize = cborBytes[offset].toInt() and 0xFF
            offset++
        } else if (initialByte == 0x99) {
            arraySize = ((cborBytes[offset].toInt() and 0xFF) shl 8) or (cborBytes[offset + 1].toInt() and 0xFF)
            offset += 2
        } else {
            return elements
        }

        for (i in 0 until arraySize) {
            if (offset >= cborBytes.size) break
            val startOffset = offset

            // Very simple CBOR object skipper (handles bstr, array, map roughly)
            offset = skipCborObject(cborBytes, offset)

            val elementSize = offset - startOffset
            val element = ByteArray(elementSize)
            System.arraycopy(cborBytes, startOffset, element, 0, elementSize)
            elements.add(element)
        }

        return elements
    }

    private fun skipCborObject(
        cborBytes: ByteArray,
        startOffset: Int,
    ): Int {
        var offset = startOffset
        if (offset >= cborBytes.size) return offset

        val initialByte = cborBytes[offset].toInt() and 0xFF
        val majorType = initialByte shr 5
        val additionalInfo = initialByte and 0x1F
        offset++

        val count =
            when (additionalInfo) {
                in 0..23 -> additionalInfo.toLong()
                24 -> {
                    offset++
                    (cborBytes[offset - 1].toInt() and 0xFF).toLong()
                }
                25 -> {
                    offset += 2
                    (((cborBytes[offset - 2].toInt() and 0xFF) shl 8) or (cborBytes[offset - 1].toInt() and 0xFF)).toLong()
                }
                26 -> {
                    offset += 4
                    0L
                } // Simplified
                27 -> {
                    offset += 8
                    0L
                } // Simplified
                else -> 0L
            }

        when (majorType) {
            0, 1 -> {} // Integer (already skipped size)
            2, 3 -> {
                offset += count.toInt()
            } // bstr, tstr
            4 -> { // array
                for (i in 0 until count) {
                    offset = skipCborObject(cborBytes, offset)
                }
            }
            5 -> { // map
                for (i in 0 until count * 2) {
                    offset = skipCborObject(cborBytes, offset)
                }
            }
            6 -> { // tag
                offset = skipCborObject(cborBytes, offset)
            }
            7 -> {} // simple
        }
        return offset
    }
}
