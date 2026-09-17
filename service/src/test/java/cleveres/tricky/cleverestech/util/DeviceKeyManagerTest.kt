package cleveres.tricky.cleverestech.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

class DeviceKeyManagerTest {
    private lateinit var keyStoreMock: KeyStore
    private lateinit var keyStoreStaticMock: MockedStatic<KeyStore>

    @Before
    fun setUp() {
        DeviceKeyManager.resetForTesting()

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

    @Test
    fun testInitializeAndroidKeyStoreGeneratesKeyWhenMissing() {
        val initializeMethod = DeviceKeyManager.javaClass.getDeclaredMethod("initializeAndroidKeyStore")
        initializeMethod.isAccessible = true

        // mock keystore to NOT contain the alias
        `when`(keyStoreMock.containsAlias("cleveres_device_cache_key")).thenReturn(false)

        val keyGeneratorMock = mock(KeyGenerator::class.java)
        mockStatic(KeyGenerator::class.java).use { keyGeneratorStaticMock ->
            keyGeneratorStaticMock.`when`<KeyGenerator> { KeyGenerator.getInstance(eq(KeyProperties.KEY_ALGORITHM_AES), eq("AndroidKeyStore")) }.thenReturn(keyGeneratorMock)

            mockConstruction(KeyGenParameterSpec.Builder::class.java) { mock, _ ->
                `when`(mock.setBlockModes(anyString())).thenReturn(mock)
                `when`(mock.setEncryptionPaddings(anyString())).thenReturn(mock)
                `when`(mock.setKeySize(anyInt())).thenReturn(mock)
                `when`(mock.build()).thenReturn(mock(KeyGenParameterSpec::class.java))
            }.use {
                initializeMethod.invoke(DeviceKeyManager)

                verify(keyGeneratorMock).init(any(KeyGenParameterSpec::class.java))
                verify(keyGeneratorMock).generateKey()
            }
        }
    }

    @Test
    fun testInitializeAndroidKeyStoreSkipsGenerationWhenKeyExists() {
        val initializeMethod = DeviceKeyManager.javaClass.getDeclaredMethod("initializeAndroidKeyStore")
        initializeMethod.isAccessible = true

        // mock keystore to contain the alias
        `when`(keyStoreMock.containsAlias("cleveres_device_cache_key")).thenReturn(true)

        val keyGeneratorMock = mock(KeyGenerator::class.java)
        mockStatic(KeyGenerator::class.java).use { keyGeneratorStaticMock ->
            keyGeneratorStaticMock.`when`<KeyGenerator> { KeyGenerator.getInstance(eq(KeyProperties.KEY_ALGORITHM_AES), eq("AndroidKeyStore")) }.thenReturn(keyGeneratorMock)

            initializeMethod.invoke(DeviceKeyManager)

            verify(keyGeneratorMock, never()).init(any(KeyGenParameterSpec::class.java))
            verify(keyGeneratorMock, never()).generateKey()
        }
    }

    @Test
    fun testInitializeAndroidKeyStoreThrowsWhenEntryNotSecretKey() {
        val initializeMethod = DeviceKeyManager.javaClass.getDeclaredMethod("initializeAndroidKeyStore")
        initializeMethod.isAccessible = true

        `when`(keyStoreMock.containsAlias("cleveres_device_cache_key")).thenReturn(true)

        // Return null or wrong entry type
        `when`(keyStoreMock.getEntry(eq("cleveres_device_cache_key"), any())).thenReturn(null)

        var exception: Exception? = null
        try {
            initializeMethod.invoke(DeviceKeyManager)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            exception = e.targetException as Exception
        }

        assertNotNull(exception)
        assertTrue(exception is IllegalArgumentException)
        assertEquals("AndroidKeyStore did not return the generated AES key", exception?.message)
    }
}
