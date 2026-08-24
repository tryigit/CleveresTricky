package cleveres.tricky.cleverestech.util

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import java.io.IOException

class DeviceKeyManagerTest {
    private lateinit var keyStoreMock: KeyStore
    private lateinit var keyStoreStaticMock: MockedStatic<KeyStore>
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("devicekeymanager").toFile()
        SecureFile.resetDefaultForTesting()
        // Reset DeviceKeyManager state via reflection
        val instance = DeviceKeyManager
        val fallbackField = instance.javaClass.getDeclaredField("fallbackKey")
        fallbackField.isAccessible = true
        fallbackField.set(instance, null)

        val useFallbackField = instance.javaClass.getDeclaredField("useFallback")
        useFallbackField.isAccessible = true
        useFallbackField.set(instance, false)

        val cachedKeyField = instance.javaClass.getDeclaredField("cachedKey")
        cachedKeyField.isAccessible = true
        cachedKeyField.set(instance, null)

        // Setup Mocks
        keyStoreMock = mock(KeyStore::class.java)
        keyStoreStaticMock = mockStatic(KeyStore::class.java)
        keyStoreStaticMock.`when`<KeyStore> { KeyStore.getInstance("AndroidKeyStore") }.thenReturn(keyStoreMock)

        val secretKey = SecretKeySpec(ByteArray(32), "AES")
        val entry = mock(KeyStore.SecretKeyEntry::class.java)
        `when`(entry.secretKey).thenReturn(secretKey)
        `when`(keyStoreMock.getEntry(eq("cleveres_device_cache_key"), any())).thenReturn(entry)
    }

    @After
    fun tearDown() {
        keyStoreStaticMock.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun testKeyStoreLoadedOnce() {
        val data = "test data".toByteArray()

        // First call
        val result1 = DeviceKeyManager.encrypt(data)
        assertNotNull(result1)

        // Second call
        val result2 = DeviceKeyManager.encrypt(data)
        assertNotNull(result2)

        // Verify KeyStore.getInstance was called only once (optimization)
        keyStoreStaticMock.verify({ KeyStore.getInstance("AndroidKeyStore") }, times(1))
        verify(keyStoreMock, times(1)).load(null)
    }

    @Test
    fun testEncryptDecryptRoundTrip() {
        val data = ByteArray(4096) { index -> (index and 0xFF).toByte() }
        val encrypted = DeviceKeyManager.encrypt(data)
        assertNotNull(encrypted)

        val decrypted = DeviceKeyManager.decrypt(requireNotNull(encrypted))
        assertNotNull(decrypted)
        assertArrayEquals(data, decrypted)
    }

    @Test
    fun testLoadFallbackKey_CreatesNewKeyWhenFileDoesNotExist() {
        val keyFile = File(tempDir, "device_secret.key")
        assertFalse(keyFile.exists())

        // Force DeviceKeyManager to use fallback by throwing exception from KeyStore.getInstance
        keyStoreStaticMock.`when`<KeyStore> { KeyStore.getInstance("AndroidKeyStore") }.thenThrow(RuntimeException("Mock KeyStore Failure"))

        DeviceKeyManager.initialize(tempDir)

        assertTrue(keyFile.exists())
        assertTrue(keyFile.length() == 32L) // FALLBACK_KEY_BYTES

        val data = "test fallback data".toByteArray()
        val encrypted = DeviceKeyManager.encrypt(data)
        assertNotNull(encrypted)

        val decrypted = DeviceKeyManager.decrypt(requireNotNull(encrypted))
        assertNotNull(decrypted)
        assertArrayEquals(data, decrypted)
    }

    @Test
    fun testLoadFallbackKey_UsesExistingKey() {
        val keyFile = File(tempDir, "device_secret.key")

        // Setup existing key file
        val bytes = ByteArray(32) { (it and 0xFF).toByte() }
        SecureFile.writeBytes(keyFile, bytes)

        keyStoreStaticMock.`when`<KeyStore> { KeyStore.getInstance("AndroidKeyStore") }.thenThrow(RuntimeException("Mock KeyStore Failure"))

        DeviceKeyManager.initialize(tempDir)

        val data = "test fallback data".toByteArray()
        val encrypted = DeviceKeyManager.encrypt(data)
        assertNotNull(encrypted)

        val decrypted = DeviceKeyManager.decrypt(requireNotNull(encrypted))
        assertNotNull(decrypted)
        assertArrayEquals(data, decrypted)
    }

    @Test(expected = IOException::class)
    fun testLoadFallbackKey_FailsOnSymbolicLink() {
        val keyFile = File(tempDir, "device_secret.key")
        val target = File(tempDir, "target.key")
        target.createNewFile()
        Files.createSymbolicLink(keyFile.toPath(), target.toPath())

        keyStoreStaticMock.`when`<KeyStore> { KeyStore.getInstance("AndroidKeyStore") }.thenThrow(RuntimeException("Mock KeyStore Failure"))

        DeviceKeyManager.initialize(tempDir)
    }

    @Test(expected = IOException::class)
    fun testLoadFallbackKey_FailsOnInvalidSize() {
        val keyFile = File(tempDir, "device_secret.key")
        val invalidBytes = ByteArray(16) { (it and 0xFF).toByte() }
        SecureFile.writeBytes(keyFile, invalidBytes)

        keyStoreStaticMock.`when`<KeyStore> { KeyStore.getInstance("AndroidKeyStore") }.thenThrow(RuntimeException("Mock KeyStore Failure"))

        DeviceKeyManager.initialize(tempDir)
    }
}
