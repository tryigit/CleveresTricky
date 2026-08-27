package cleveres.tricky.cleverestech.util

import org.junit.Assert.*
import org.junit.Test
import java.security.spec.InvalidKeySpecException
import javax.crypto.AEADBadTagException

class CryptoUtilsTest {

    @Test
    fun testGenerateX25519KeyPair() {
        val keyPair = CryptoUtils.generateX25519KeyPair()
        assertNotNull(keyPair)
        assertNotNull(keyPair.private)
        assertNotNull(keyPair.public)
        assertTrue(keyPair.public.algorithm == "X25519" || keyPair.public.algorithm == "XDH")
    }

    @Test
    fun testGenerateEd25519KeyPair() {
        val keyPair = CryptoUtils.generateEd25519KeyPair()
        assertNotNull(keyPair)
        assertNotNull(keyPair.private)
        assertNotNull(keyPair.public)
        assertTrue(keyPair.public.algorithm == "Ed25519" || keyPair.public.algorithm == "EdDSA")
    }

    @Test
    fun testEcdhDeriveKey() {
        val keyPair1 = CryptoUtils.generateX25519KeyPair()
        val keyPair2 = CryptoUtils.generateX25519KeyPair()

        val secret1 = CryptoUtils.ecdhDeriveKey(keyPair1.private, keyPair2.public.encoded)
        val secret2 = CryptoUtils.ecdhDeriveKey(keyPair2.private, keyPair1.public.encoded)

        assertArrayEquals(secret1, secret2)
        assertEquals(32, secret1.size)
    }

    @Test(expected = InvalidKeySpecException::class)
    fun testEcdhDeriveKeyInvalidPublicKey() {
        val keyPair1 = CryptoUtils.generateX25519KeyPair()
        CryptoUtils.ecdhDeriveKey(keyPair1.private, ByteArray(32)) // Invalid X509 public key bytes
    }

    @Test
    fun testHkdfSha256() {
        // Basic HKDF test
        val ikm = "initial keying material".toByteArray()
        val salt = "salt".toByteArray()
        val info = "info".toByteArray()
        val outLen = 32

        val out1 = CryptoUtils.hkdfSha256(ikm, salt, info, outLen)
        assertEquals(outLen, out1.size)

        val out2 = CryptoUtils.hkdfSha256(ikm, salt, info, outLen)
        assertArrayEquals(out1, out2)

        // RFC 5869 Test Case 1
        val rfcIkm = byteArrayOf(0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b)
        val rfcSalt = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c)
        val rfcInfo = byteArrayOf(0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte())
        val rfcOut = CryptoUtils.hkdfSha256(rfcIkm, rfcSalt, rfcInfo, 42)

        val rfcExpected = byteArrayOf(
            0x3c.toByte(), 0xb2.toByte(), 0x5f.toByte(), 0x25.toByte(), 0xfa.toByte(), 0xac.toByte(), 0xd5.toByte(), 0x7a.toByte(),
            0x90.toByte(), 0x43.toByte(), 0x4f.toByte(), 0x64.toByte(), 0xd0.toByte(), 0x36.toByte(), 0x2f.toByte(), 0x2a.toByte(),
            0x2d.toByte(), 0x2d.toByte(), 0x0a.toByte(), 0x90.toByte(), 0xcf.toByte(), 0x1a.toByte(), 0x5a.toByte(), 0x4c.toByte(),
            0x5d.toByte(), 0xb0.toByte(), 0x2d.toByte(), 0x56.toByte(), 0xec.toByte(), 0xc4.toByte(), 0xc5.toByte(), 0xbf.toByte(),
            0x34.toByte(), 0x00.toByte(), 0x72.toByte(), 0x08.toByte(), 0xd5.toByte(), 0xb8.toByte(), 0x87.toByte(), 0x18.toByte(),
            0x58.toByte(), 0x65.toByte()
        )
        assertArrayEquals(rfcExpected, rfcOut)

        // Empty salt
        val emptySaltOut = CryptoUtils.hkdfSha256(rfcIkm, ByteArray(0), rfcInfo, 42)
        assertNotNull(emptySaltOut)
    }

    @Test
    fun testAesGcmEncrypt() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { it.toByte() }
        val aad = "aad".toByteArray()
        val plaintext = "plaintext".toByteArray()

        val ciphertext = CryptoUtils.aesGcmEncrypt(key, iv, aad, plaintext)

        assertNotNull(ciphertext)
        assertEquals(plaintext.size + 16, ciphertext.size)

        // Verify with decryption
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = javax.crypto.spec.SecretKeySpec(key, "AES")
        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        cipher.updateAAD(aad)
        val decrypted = cipher.doFinal(ciphertext)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test(expected = AEADBadTagException::class)
    fun testAesGcmEncryptInvalidAad() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { it.toByte() }
        val aad = "aad".toByteArray()
        val plaintext = "plaintext".toByteArray()

        val ciphertext = CryptoUtils.aesGcmEncrypt(key, iv, aad, plaintext)

        // Decrypt with wrong AAD should fail
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = javax.crypto.spec.SecretKeySpec(key, "AES")
        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        cipher.updateAAD("wrong aad".toByteArray())
        cipher.doFinal(ciphertext)
    }

    @Test
    fun testDecodeSimpleCborArray() {
        val empty = CryptoUtils.decodeSimpleCborArray(byteArrayOf(0x80.toByte()))
        assertTrue(empty.isEmpty())

        val arr1 = CryptoUtils.decodeSimpleCborArray(byteArrayOf(0x81.toByte(), 0x01.toByte()))
        assertEquals(1, arr1.size)
        assertArrayEquals(byteArrayOf(0x01.toByte()), arr1[0])

        val arr2 = CryptoUtils.decodeSimpleCborArray(byteArrayOf(0x82.toByte(), 0x01.toByte(), 0x02.toByte()))
        assertEquals(2, arr2.size)
        assertArrayEquals(byteArrayOf(0x01.toByte()), arr2[0])
        assertArrayEquals(byteArrayOf(0x02.toByte()), arr2[1])

        val cbor24 = ByteArray(26)
        cbor24[0] = 0x98.toByte()
        cbor24[1] = 24.toByte()
        for (i in 0 until 24) {
            cbor24[2 + i] = i.toByte()
        }
        val arr24 = CryptoUtils.decodeSimpleCborArray(cbor24)
        assertEquals(24, arr24.size)

        val strArr = CryptoUtils.decodeSimpleCborArray(byteArrayOf(
            0x81.toByte(), 0x65.toByte(), 'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte()
        ))
        assertEquals(1, strArr.size)
        assertArrayEquals(byteArrayOf(0x65.toByte(), 'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte()), strArr[0])
    }

    @Test
    fun testSkipCborObjectCoverage() {
        val complex = byteArrayOf(
            0x86.toByte(),
            0x63.toByte(), 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(),
            0x42.toByte(), 0x01.toByte(), 0x02.toByte(),
            0x81.toByte(), 0x01.toByte(),
            0xa1.toByte(), 0x01.toByte(), 0x02.toByte(),
            0xc0.toByte(), 0x01.toByte(),
            0xf5.toByte()
        )
        val decoded = CryptoUtils.decodeSimpleCborArray(complex)
        assertEquals(6, decoded.size)
        assertEquals(4, decoded[0].size)
        assertEquals(3, decoded[1].size)
        assertEquals(2, decoded[2].size)
        assertEquals(3, decoded[3].size)
        assertEquals(2, decoded[4].size)
        assertEquals(1, decoded[5].size)
    }

    @Test
    fun testCborDifferentIntSizes() {
        val complex = byteArrayOf(
            0x84.toByte(),
            0x18.toByte(), 0x18.toByte(),
            0x19.toByte(), 0x01.toByte(), 0x00.toByte(),
            0x1a.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x1b.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
        )
        val decoded = CryptoUtils.decodeSimpleCborArray(complex)
        assertEquals(4, decoded.size)
        assertEquals(2, decoded[0].size)
        assertEquals(3, decoded[1].size)
        assertEquals(5, decoded[2].size)
        assertEquals(9, decoded[3].size)

        val array256 = ByteArray(3 + 256)
        array256[0] = 0x99.toByte()
        array256[1] = 0x01.toByte()
        array256[2] = 0x00.toByte()
        val dec256 = CryptoUtils.decodeSimpleCborArray(array256)
        assertEquals(256, dec256.size)
    }

    @Test
    fun testDecodeCborInvalidCases() {
        assertTrue(CryptoUtils.decodeSimpleCborArray(byteArrayOf()).isEmpty())
        assertTrue(CryptoUtils.decodeSimpleCborArray(byteArrayOf(0x99.toByte(), 0x01.toByte())).isEmpty())

        val nonArray = CryptoUtils.decodeSimpleCborArray(byteArrayOf(0x01.toByte()))
        assertTrue(nonArray.isEmpty())

        val shortArray = CryptoUtils.decodeSimpleCborArray(byteArrayOf(0x82.toByte(), 0x01.toByte()))
        assertEquals(1, shortArray.size)

        val shortSkip = CryptoUtils.decodeSimpleCborArray(byteArrayOf(0x81.toByte(), 0x18.toByte()))
        assertEquals(1, shortSkip.size)
    }
}
