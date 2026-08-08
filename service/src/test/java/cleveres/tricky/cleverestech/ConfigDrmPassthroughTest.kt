package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

class ConfigDrmPassthroughTest {
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        Config.reset()
        tempDir = Files.createTempDirectory("drm_passthrough").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        Config.reset()
    }

    @Test
    fun `DRM passthrough excludes a targeted package only while enabled`() {
        val uid = 10_100
        val target = File(tempDir, "target.txt").apply { writeText("com.example.video\n") }
        val drmPackages = File(tempDir, "drm_packages.txt").apply { writeText("com.example.video\n") }
        val enabled = File(tempDir, "drm_passthrough").apply { createNewFile() }
        cachePackages(uid, arrayOf("com.example.video"))

        invokeUpdater("updateTargetPackages", target)
        invokeUpdater("updateDrmPackages", drmPackages)
        invokeUpdater("updateDrmPassthrough", enabled)
        assertFalse(Config.needHack(uid))

        drmPackages.writeText("com.example.video;invalid\n")
        invokeUpdater("updateDrmPackages", drmPackages)
        assertFalse(Config.needHack(uid))

        invokeUpdater("updateDrmPassthrough", null)
        assertTrue(Config.needHack(uid))
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
