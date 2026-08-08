package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class ConfigPatchLevelSharedUidTest {
    @Test
    fun testPatchLevelSharedUid() {
        Config.reset()

        val securityPatchStateField = Config::class.java.getDeclaredField("securityPatchState")
        securityPatchStateField.isAccessible = true

        val testPatchMap = mapOf("com.example.pkgB" to "2023-01-01")
        val defaultPatch = "2024-01-01"

        val stateClass =
            Config::class.java.declaredClasses.find { it.simpleName == "SecurityPatchState" }
                ?: throw ClassNotFoundException("SecurityPatchState not found")
        val stateConstructor = stateClass.getDeclaredConstructor(Map::class.java, Any::class.java)
        stateConstructor.isAccessible = true
        val state = stateConstructor.newInstance(testPatchMap, defaultPatch)

        securityPatchStateField.set(Config, state)

        // Mock packages for UID 1001: [com.example.pkgA, com.example.pkgB]
        val packages = arrayOf("com.example.pkgA", "com.example.pkgB")

        // Mock CachedPackage
        val cachedPackageClass = Class.forName("cleveres.tricky.cleverestech.Config\$CachedPackage")
        val constructor = cachedPackageClass.getDeclaredConstructor(Array<String>::class.java, Long::class.javaPrimitiveType)
        constructor.isAccessible = true
        val cachedPkg = constructor.newInstance(packages, System.currentTimeMillis())

        val packageCacheField = Config::class.java.getDeclaredField("packageCache")
        packageCacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val packageCache =
            packageCacheField.get(Config) as
                @Suppress("UNCHECKED_CAST")
                ConcurrentHashMap<Int, Any>
        packageCache[1001] = cachedPkg

        try {
            // Execute
            val level = Config.getPatchLevel(1001)

            // Expected: 202301 (from pkgB)
            // Actual (Bug): 202401 (default, because it only checks pkgA)
            assertEquals("Should use specific patch level if ANY package in UID matches", 202301, level)
        } finally {
            Config.reset()
        }
    }
}
