package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.Logger
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createFile
import kotlin.io.path.createTempDirectory

class DeviceKeyManagerInitializeTest {
    private lateinit var keyStoreMock: KeyStore
    private lateinit var keyStoreStaticMock: MockedStatic<KeyStore>
    private lateinit var tempDir: Path
    private lateinit var fallbackFile: File
    private lateinit var loggerStaticMock: MockedStatic<Logger>

    @Before
    fun setUp() {
        tempDir = createTempDirectory("test-key-manager")
        fallbackFile = File(tempDir.toFile(), "device_secret.key")

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

        keyStoreMock = mock(KeyStore::class.java)
        keyStoreStaticMock = mockStatic(KeyStore::class.java)
        keyStoreStaticMock.`when`<KeyStore> { KeyStore.getInstance("AndroidKeyStore") }.thenReturn(keyStoreMock)

        // Add basic KeyStore stubbing for AndroidKeyStore methods
        `when`(keyStoreMock.containsAlias("cleveres_device_cache_key")).thenReturn(true)

        loggerStaticMock = mockStatic(Logger::class.java)
    }

    @After
    fun tearDown() {
        keyStoreStaticMock.close()
        loggerStaticMock.close()
        tempDir.toFile().deleteRecursively()
    }

    private fun getUseFallback(): Boolean {
        val field = DeviceKeyManager.javaClass.getDeclaredField("useFallback")
        field.isAccessible = true
        return field.getBoolean(DeviceKeyManager)
    }

    private fun getFallbackKey(): Any? {
        val field = DeviceKeyManager.javaClass.getDeclaredField("fallbackKey")
        field.isAccessible = true
        return field.get(DeviceKeyManager)
    }

    @Test
    fun testInitialize_WithExistingFallbackFile() {
        fallbackFile.writeBytes(ByteArray(32) { it.toByte() })

        DeviceKeyManager.initialize(tempDir.toFile())

        assertTrue(getUseFallback())
        assertNotNull(getFallbackKey())
    }

    @Test
    fun testInitialize_AndroidKeyStoreSuccess() {
        val secretKey = SecretKeySpec(ByteArray(32), "AES")
        val entry = mock(KeyStore.SecretKeyEntry::class.java)
        `when`(entry.secretKey).thenReturn(secretKey)
        `when`(keyStoreMock.getEntry(eq("cleveres_device_cache_key"), any())).thenReturn(entry)

        DeviceKeyManager.initialize(tempDir.toFile())

        assertFalse(getUseFallback())
        assertNull(getFallbackKey())
    }

    @Test
    fun testInitialize_AndroidKeyStoreFailure() {
        keyStoreStaticMock.`when`<KeyStore> { KeyStore.getInstance("AndroidKeyStore") }.thenThrow(RuntimeException("KeyStore failed"))

        DeviceKeyManager.initialize(tempDir.toFile())

        assertTrue(getUseFallback())
        assertNotNull(getFallbackKey())
        assertTrue(fallbackFile.exists())
    }
}
