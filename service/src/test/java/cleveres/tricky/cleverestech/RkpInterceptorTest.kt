package cleveres.tricky.cleverestech

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import cleveres.tricky.cleverestech.keystore.CertHack
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Comprehensive tests for RKP functionality.
 * These tests verify that all RKP spoofing components work correctly.
 */
@RunWith(JUnit4::class)
class RkpInterceptorTest {

    @Before
    fun setup() {
        // setup logging so we can see what happens during tests
        Logger.setImpl(object : Logger.LogImpl {
            override fun d(tag: String, msg: String) = println("D/$tag: $msg")
            override fun e(tag: String, msg: String) = println("E/$tag: $msg")
            override fun e(tag: String, msg: String, t: Throwable) {
                println("E/$tag: $msg")
                t.printStackTrace()
            }
            override fun i(tag: String, msg: String) = println("I/$tag: $msg")
        })
    }

    // ============ MacedPublicKey Tests ============

    @Test
    fun testMacedPublicKeyGeneration() {
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGen.generateKeyPair()
        
        val macedKey = CertHack.generateMacedPublicKey(keyPair)
        
        assertNotNull("macedKey should not be null", macedKey)
        assertTrue("macedKey should have content", macedKey!!.isNotEmpty())
        assertEquals("should start with COSE array header", 0x84.toByte(), macedKey[0])
        
        println("test passed: macedKey size=${macedKey.size}")
    }

    @Test
    fun testMacedPublicKeyMultipleGenerations() {
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(ECGenParameterSpec("secp256r1"))
        
        // generate multiple keys and make sure they're all different
        val keys = mutableListOf<ByteArray>()
        repeat(5) {
            val keyPair = keyPairGen.generateKeyPair()
            val macedKey = CertHack.generateMacedPublicKey(keyPair)
            assertNotNull(macedKey)
            keys.add(macedKey!!)
        }
        
        // verify all keys are unique
        val uniqueKeys = keys.map { it.contentHashCode() }.toSet()
        assertEquals("all generated keys should be unique", 5, uniqueKeys.size)
        
        println("test passed: generated 5 unique keys")
    }

    // ============ CertificateRequest Tests ============

    @Test
    fun testCertificateRequestResponseGeneration() {
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGen.generateKeyPair()
        
        val macedKey = CertHack.generateMacedPublicKey(keyPair)
        assertNotNull(macedKey)
        
        val publicKeys = listOf(macedKey!!)
        val challenge = "test_challenge".toByteArray()
        val deviceInfo = CertHack.createDeviceInfoCbor("google", "Google", "redfin", "Pixel 5", "redfin")
        assertNotNull(deviceInfo)
        
        val response = CertHack.createCertificateRequestResponse(publicKeys, challenge, deviceInfo!!)
        
        assertNotNull("response should not be null", response)
        assertTrue("response should have content", response!!.isNotEmpty())
        assertEquals("should start with CBOR array header", 0x84.toByte(), response[0])
        
        println("test passed: response size=${response.size}")
    }

    @Test
    fun testCertificateRequestWithMultipleKeys() {
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(ECGenParameterSpec("secp256r1"))
        
        // create multiple maced keys
        val publicKeys = mutableListOf<ByteArray>()
        repeat(3) {
            val keyPair = keyPairGen.generateKeyPair()
            val macedKey = CertHack.generateMacedPublicKey(keyPair)
            assertNotNull(macedKey)
            publicKeys.add(macedKey!!)
        }
        
        val challenge = "multi_key_test".toByteArray()
        val deviceInfo = CertHack.createDeviceInfoCbor("google", "Google", "husky", "Pixel 8 Pro", "husky")
        
        val response = CertHack.createCertificateRequestResponse(publicKeys, challenge, deviceInfo!!)
        
        assertNotNull(response)
        assertTrue(response!!.isNotEmpty())
        
        println("test passed: multi-key response size=${response.size}")
    }

    @Test
    fun testCertificateRequestWithEmptyChallenge() {
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGen.generateKeyPair()
        
        val macedKey = CertHack.generateMacedPublicKey(keyPair)
        val deviceInfo = CertHack.createDeviceInfoCbor("google", "Google", "generic", "Pixel", "generic")
        
        // empty challenge should still work
        val response = CertHack.createCertificateRequestResponse(
            listOf(macedKey!!),
            ByteArray(0),
            deviceInfo!!
        )
        
        assertNotNull(response)
        
        println("test passed: empty challenge handled")
    }

    // ============ DeviceInfo Tests ============

    @Test
    fun testDeviceInfoCborGeneration() {
        val deviceInfo = CertHack.createDeviceInfoCbor(
            "google", "Google", "husky", "Pixel 8 Pro", "husky"
        )
        
        assertNotNull("deviceInfo should not be null", deviceInfo)
        assertTrue("deviceInfo should have content", deviceInfo!!.isNotEmpty())
        assertEquals("should start with CBOR map header", 0xAA.toByte(), deviceInfo[0])
        
        // check that it contains expected values
        val content = String(deviceInfo, Charsets.UTF_8)
        assertTrue("should contain brand", content.contains("google"))
        assertTrue("should contain vb_state", content.contains("vb_state"))
        assertTrue("should contain green", content.contains("green"))
        assertTrue("should contain locked", content.contains("locked"))
        
        println("test passed: deviceInfo size=${deviceInfo.size}")
    }

    @Test
    fun testDeviceInfoWithNullValues() {
        val deviceInfo = CertHack.createDeviceInfoCbor(null, null, null, null, null)
        
        assertNotNull("deviceInfo should not be null", deviceInfo)
        assertTrue("deviceInfo should have content", deviceInfo!!.isNotEmpty())
        
        // should use defaults
        val content = String(deviceInfo, Charsets.UTF_8)
        assertTrue("should contain default brand", content.contains("google"))
        
        println("test passed: null values handled with defaults")
    }

    @Test
    fun testDeviceInfoWithVariousDevices() {
        val devices = listOf(
            Triple("google", "Pixel 8", "shiba"),
            Triple("google", "Pixel 7 Pro", "cheetah"),
            Triple("google", "Pixel 6", "oriole"),
            Triple("samsung", "Galaxy S23", "dm1q"),
            Triple("xiaomi", "14", "houji")
        )
        
        devices.forEach { (brand, model, device) ->
            val deviceInfo = CertHack.createDeviceInfoCbor(brand, brand.capitalize(), device, model, device)
            assertNotNull("deviceInfo for $model should not be null", deviceInfo)
            assertTrue("deviceInfo for $model should have content", deviceInfo!!.isNotEmpty())
            
            val content = String(deviceInfo, Charsets.UTF_8)
            assertTrue("should contain brand for $model", content.contains(brand))
        }
        
        println("test passed: tested ${devices.size} device configs")
    }

    // ============ Edge Cases ============

    @Test
    fun testLargeChallenge() {
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGen.generateKeyPair()
        
        val macedKey = CertHack.generateMacedPublicKey(keyPair)
        val deviceInfo = CertHack.createDeviceInfoCbor("google", "Google", "generic", "Pixel", "generic")
        
        // large challenge (1KB)
        val largeChallenge = ByteArray(1024) { it.toByte() }
        
        val response = CertHack.createCertificateRequestResponse(
            listOf(macedKey!!),
            largeChallenge,
            deviceInfo!!
        )
        
        assertNotNull(response)
        assertTrue(response!!.size > largeChallenge.size)
        
        println("test passed: large challenge handled, response size=${response.size}")
    }

    @Test
    fun testEmptyKeysList() {
        val deviceInfo = CertHack.createDeviceInfoCbor("google", "Google", "generic", "Pixel", "generic")
        val challenge = "test".toByteArray()
        
        val response = CertHack.createCertificateRequestResponse(
            emptyList(),
            challenge,
            deviceInfo!!
        )
        
        assertNotNull(response)
        
        println("test passed: empty keys list handled")
    }

    // ============ Performance Tests ============

    @Test
    fun testKeyGenerationPerformance() {
        val keyPairGen = KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(ECGenParameterSpec("secp256r1"))
        
        val startTime = System.currentTimeMillis()
        
        repeat(10) {
            val keyPair = keyPairGen.generateKeyPair()
            CertHack.generateMacedPublicKey(keyPair)
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        
        // should complete reasonably fast (under 2 seconds for 10 keys)
        assertTrue("key generation should be fast, took ${elapsed}ms", elapsed < 2000)
        
        println("test passed: generated 10 keys in ${elapsed}ms")
    }

    @Test
    fun testDeviceInfoGenerationPerformance() {
        val startTime = System.currentTimeMillis()
        
        repeat(100) {
            CertHack.createDeviceInfoCbor("google", "Google", "husky", "Pixel 8 Pro", "husky")
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        
        assertTrue("deviceInfo generation should be fast, took ${elapsed}ms", elapsed < 500)
        
        println("test passed: generated 100 deviceInfo in ${elapsed}ms")
    }
    
    // helper extension
    private fun String.capitalize(): String {
        return this.replaceFirstChar { it.uppercase() }
    }
}
