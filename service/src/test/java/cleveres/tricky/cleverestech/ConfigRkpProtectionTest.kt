package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

class ConfigRkpProtectionTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Config.reset()
        tempDir = Files.createTempDirectory("rkp_protection").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        Config.reset()
    }

    @Test
    fun `RKP service packages stay outside global substitution scope`() {
        invokeUpdater("updateGlobalMode", File(tempDir, "global_mode").apply { createNewFile() })
        cachePackages(10_100, arrayOf("com.android.rkpd"))
        cachePackages(10_101, arrayOf("com.android.rkpdapp"))
        cachePackages(10_102, arrayOf("com.google.android.rkpd"))
        cachePackages(10_103, arrayOf("com.google.android.rkpdapp"))
        cachePackages(10_104, arrayOf("com.google.android.go.rkpd"))
        cachePackages(10_105, arrayOf("com.android.remoteprovisioner"))
        cachePackages(10_106, arrayOf("com.google.android.remoteprovisioner"))
        cachePackages(10_107, arrayOf("com.android.rkpd.example"))
        cachePackages(10_108, emptyArray())

        assertFalse(Config.needHack(10_100))
        assertFalse(Config.needHack(10_101))
        assertFalse(Config.needHack(10_102))
        assertFalse(Config.needHack(10_103))
        assertFalse(Config.needHack(10_104))
        assertFalse(Config.needHack(10_105))
        assertFalse(Config.needHack(10_106))
        assertTrue(Config.needHack(10_107))
        assertFalse(Config.needHack(10_108))

        invokeUpdater("updateSpoofEnabled", null)
        assertTrue(Config.needHack(10_107))
    }

    private fun invokeUpdater(
        prefix: String,
        file: File?,
    ) {
        val method = Config::class.java.declaredMethods.first { it.name.startsWith(prefix) }
        method.isAccessible = true
        method.invoke(Config, file)
    }

    private fun cachePackages(
        uid: Int,
        packages: Array<String>,
    ) {
        val cachedPackageClass = Class.forName("cleveres.tricky.cleverestech.Config\$CachedPackage")
        val constructor =
            cachedPackageClass.getDeclaredConstructor(Array<String>::class.java, Long::class.javaPrimitiveType)
        constructor.isAccessible = true
        val cached = constructor.newInstance(packages, System.currentTimeMillis())

        val field = Config::class.java.getDeclaredField("packageCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(Config) as ConcurrentHashMap<Int, Any>
        cache[uid] = cached
    }
}
