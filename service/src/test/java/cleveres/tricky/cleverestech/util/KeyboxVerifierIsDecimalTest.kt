package cleveres.tricky.cleverestech.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboxVerifierIsDecimalTest {

    @Test
    fun `isDecimal returns true for valid positive numbers`() {
        assertTrue(KeyboxVerifier.isDecimal("0"))
        assertTrue(KeyboxVerifier.isDecimal("1"))
        assertTrue(KeyboxVerifier.isDecimal("1234567890"))
    }

    @Test
    fun `isDecimal returns true for valid negative numbers`() {
        assertTrue(KeyboxVerifier.isDecimal("-1"))
        assertTrue(KeyboxVerifier.isDecimal("-10"))
        assertTrue(KeyboxVerifier.isDecimal("-1234567890"))
    }

    @Test
    fun `isDecimal returns false for empty strings`() {
        assertFalse(KeyboxVerifier.isDecimal(""))
    }

    @Test
    fun `isDecimal returns false for strings with invalid characters`() {
        assertFalse(KeyboxVerifier.isDecimal("a"))
        assertFalse(KeyboxVerifier.isDecimal("123a456"))
        assertFalse(KeyboxVerifier.isDecimal(" "))
        assertFalse(KeyboxVerifier.isDecimal("123 456"))
        assertFalse(KeyboxVerifier.isDecimal("!@#$"))
        assertFalse(KeyboxVerifier.isDecimal("-"))
        assertFalse(KeyboxVerifier.isDecimal("--1"))
    }

    @Test
    fun `isDecimal returns false for numbers with leading zeros except zero`() {
        assertFalse(KeyboxVerifier.isDecimal("01"))
        assertFalse(KeyboxVerifier.isDecimal("-01"))
        assertFalse(KeyboxVerifier.isDecimal("00"))
        assertTrue(KeyboxVerifier.isDecimal("-0"))
    }
}
