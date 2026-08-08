package cleveres.tricky.cleverestech

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ConfigAttestationPatchLevelsTest {
    private lateinit var patchFile: File
    private lateinit var originalSystemPropertiesGet: (String, String?) -> String?

    @Before
    fun setUp() {
        Config.reset()
        originalSystemPropertiesGet = systemPropertiesGet
        patchFile = File.createTempFile("security_patch", ".txt")
    }

    @After
    fun tearDown() {
        systemPropertiesGet = originalSystemPropertiesGet
        patchFile.delete()
        Config.reset()
    }

    @Test
    fun `component rules replace or omit each attestation tag independently`() {
        patchFile.writeText(
            """
            system=202411
            vendor=2024-11-05
            boot=no
            """.trimIndent(),
        )

        updateSecurityPatch(patchFile)

        val levels = Config.getAttestationPatchLevels(10_001)
        assertEquals(Config.PatchDisposition.REPLACE, levels.system.disposition)
        assertEquals(202411, levels.system.value)
        assertEquals(Config.PatchDisposition.REPLACE, levels.vendor.disposition)
        assertEquals(20241105, levels.vendor.value)
        assertEquals(Config.PatchDisposition.OMIT, levels.boot.disposition)
    }

    @Test
    fun `package section overrides one component and inherits the others`() {
        patchFile.writeText(
            """
            all=2025-09-15

            [com.example.attestation]
            system=2024-10-01
            vendor=device_default
            """.trimIndent(),
        )
        cachePackages(10_002, arrayOf("com.example.attestation"))

        updateSecurityPatch(patchFile)

        val levels = Config.getAttestationPatchLevels(10_002)
        assertEquals(Config.PatchDisposition.REPLACE, levels.system.disposition)
        assertEquals(202410, levels.system.value)
        assertEquals(Config.PatchDisposition.KEEP, levels.vendor.disposition)
        assertEquals(Config.PatchDisposition.REPLACE, levels.boot.disposition)
        assertEquals(20250915, levels.boot.value)
    }

    @Test
    fun `legacy wildcard rule overrides a global system component`() {
        patchFile.writeText(
            """
            system=2025-09-01
            com.example.*=2024-10-01
            """.trimIndent(),
        )
        cachePackages(10_003, arrayOf("com.example.attestation"))

        updateSecurityPatch(patchFile)

        val levels = Config.getAttestationPatchLevels(10_003)
        assertEquals(Config.PatchDisposition.REPLACE, levels.system.disposition)
        assertEquals(202410, levels.system.value)
    }

    @Test
    fun `prop reads the matching partition property`() {
        patchFile.writeText(
            """
            system=prop
            vendor=prop
            boot=prop
            """.trimIndent(),
        )
        systemPropertiesGet = { key, default ->
            when (key) {
                "ro.build.version.security_patch" -> "2024-01-05"
                "ro.vendor.build.security_patch" -> "2024-02-05"
                "ro.bootimage.build.version.security_patch" -> "2024-03-05"
                else -> default
            }
        }

        updateSecurityPatch(patchFile)

        val levels = Config.getAttestationPatchLevels(10_004)
        assertEquals(202401, levels.system.value)
        assertEquals(20240205, levels.vendor.value)
        assertEquals(20240305, levels.boot.value)
    }

    private fun updateSecurityPatch(file: File) {
        val method =
            Config::class.java.declaredMethods.first { it.name.startsWith("updateSecurityPatch") }
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
