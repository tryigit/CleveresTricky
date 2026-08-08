package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.KeyboxVerifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class KeyboxVerifierHashTest {
    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun testCheckHashOptimization() {
        // We use reflection to access the private checkHash method
        val method = KeyboxVerifier::class.java.getDeclaredMethod("checkHash", ByteArray::class.java, String::class.java, Set::class.java)
        method.isAccessible = true

        val input = "test content".toByteArray()
        val algorithm = "SHA-256"

        // Calculate expected hash using the old inefficient method (to ensure compatibility)
        // This verifies that the new implementation (using HexFormat) produces the exact same output
        // as the old implementation (using String.format) which the rest of the system expects.
        val digest = MessageDigest.getInstance(algorithm).digest(input)
        val expectedHex = digest.joinToString("") { "%02x".format(it) }

        val set = setOf(expectedHex)

        // Invoke the optimized method
        val result = method.invoke(KeyboxVerifier, input, algorithm, set) as Boolean

        assertTrue("Optimized checkHash should find the hash in the set", result)

        // Test with negative bytes (0xFF) to ensure formatting is correct
        val negativeInput = byteArrayOf(-1, 0, 1) // 0xFF, 0x00, 0x01
        // SHA-256 of this
        val negDigest = MessageDigest.getInstance(algorithm).digest(negativeInput)
        val negHex = negDigest.joinToString("") { "%02x".format(it) }

        val negSet = setOf(negHex)
        val negResult = method.invoke(KeyboxVerifier, negativeInput, algorithm, negSet) as Boolean

        assertTrue("Optimized checkHash should handle negative bytes correctly", negResult)

        // Test failure case
        val wrongSet = setOf("0000000000000000000000000000000000000000000000000000000000000000")
        val failResult = method.invoke(KeyboxVerifier, input, algorithm, wrongSet) as Boolean

        assertFalse("Optimized checkHash should fail if hash not in set", failResult)
    }
}
