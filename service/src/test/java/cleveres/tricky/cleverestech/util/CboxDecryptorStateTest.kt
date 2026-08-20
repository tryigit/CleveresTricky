package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.NativeBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CboxDecryptorStateTest {
    @Test
    fun `lazy plaintext access cannot bypass later signature verification`() {
        val encrypted = byteArrayOf(1, 2, 3, 4)
        val calls = ArrayList<String?>()
        CboxDecryptor.backendOpenOverride = { _, _, publicKey ->
            calls += publicKey
            when (publicKey) {
                null -> signedPayload()
                GOOD_KEY -> signedPayload()
                else -> null
            }
        }

        try {
            val payload = CboxDecryptor.CboxPayload(encrypted, "password")
            assertEquals("fixture", payload.author)
            assertFalse(CboxDecryptor.verifySignature(payload, WRONG_KEY))
            assertTrue(CboxDecryptor.verifySignature(payload, GOOD_KEY))
            assertFalse(CboxDecryptor.verifySignature(payload, WRONG_KEY))
            assertEquals(listOf(null, WRONG_KEY, GOOD_KEY), calls)
            assertTrue(encrypted.all { it == 0.toByte() })
        } finally {
            CboxDecryptor.resetForTesting()
        }
    }

    @Test
    fun `signed plaintext remains hidden until verification succeeds`() {
        val encrypted = byteArrayOf(9, 10, 11, 12)
        CboxDecryptor.backendOpenOverride = { _, _, publicKey ->
            when (publicKey) {
                null, GOOD_KEY -> signedPayload()
                else -> null
            }
        }

        try {
            val payload = CboxDecryptor.CboxPayload(encrypted, "password")
            assertEquals("fixture", payload.author)
            assertEquals("", payload.xmlContent)
            assertTrue(payload.xmlContentBytes.isEmpty())
            assertTrue(CboxDecryptor.verifySignature(payload, GOOD_KEY))
            assertEquals("<AndroidAttestation/>", payload.xmlContent)
            val bytes = payload.xmlContentBytes
            try {
                assertEquals("<AndroidAttestation/>", String(bytes))
            } finally {
                bytes.fill(0)
            }
            assertTrue(encrypted.all { it == 0.toByte() })
        } finally {
            CboxDecryptor.resetForTesting()
        }
    }

    @Test
    fun `unsigned payload cannot be approved by adding a verification key later`() {
        val encrypted = byteArrayOf(5, 6, 7, 8)
        var keyedVerificationAttempted = false
        CboxDecryptor.backendOpenOverride = { _, _, publicKey ->
            if (publicKey != null) keyedVerificationAttempted = true
            NativeBackend.CboxPayload(
                author = "fixture",
                xmlContent = "<AndroidAttestation/>".toByteArray(),
                hasSignature = false,
            )
        }

        try {
            val payload = CboxDecryptor.CboxPayload(encrypted, "password")
            assertEquals("fixture", payload.author)
            assertFalse(CboxDecryptor.verifySignature(payload, GOOD_KEY))
            assertFalse(keyedVerificationAttempted)
            assertTrue(encrypted.all { it == 0.toByte() })
        } finally {
            CboxDecryptor.resetForTesting()
        }
    }

    private fun signedPayload() =
        NativeBackend.CboxPayload(
            author = "fixture",
            xmlContent = "<AndroidAttestation/>".toByteArray(),
            hasSignature = true,
        )

    companion object {
        private const val GOOD_KEY = "good-public-key"
        private const val WRONG_KEY = "wrong-public-key"
    }
}
