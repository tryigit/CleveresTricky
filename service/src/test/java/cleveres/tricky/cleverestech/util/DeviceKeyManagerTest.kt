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
import java.security.KeyStore
import javax.crypto.spec.SecretKeySpec

class DeviceKeyManagerTest {
    private lateinit var keyStoreMock: KeyStore
    private lateinit var keyStoreStaticMock: MockedStatic<KeyStore>

    @Before
    fun setUp() {
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
}
