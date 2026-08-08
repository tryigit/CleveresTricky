package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class ConfigPatchLevelTest {
    @Test
    fun testGetPatchLevel_usesPackageName() {
        Config.reset()

        // 1. Mock Package Cache for UID 1002
        val packages = arrayOf("com.example.patched")
        val cachedPackageClass = Class.forName("cleveres.tricky.cleverestech.Config\$CachedPackage")
        val cacheConstructor = cachedPackageClass.getDeclaredConstructor(Array<String>::class.java, Long::class.javaPrimitiveType)
        cacheConstructor.isAccessible = true
        val cachedPkg = cacheConstructor.newInstance(packages, System.currentTimeMillis())

        val packageCacheField = Config::class.java.getDeclaredField("packageCache")
        packageCacheField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val packageCache =
            packageCacheField.get(Config) as
                @Suppress("UNCHECKED_CAST")
                ConcurrentHashMap<Int, Any>
        packageCache[1002] = cachedPkg

        // 2. Inject Security Patch Map via SecurityPatchState
        val securityPatchStateField = Config::class.java.getDeclaredField("securityPatchState")
        securityPatchStateField.isAccessible = true

        val testPatchMap = mapOf("com.example.patched" to "2023-12-05")

        val stateClass =
            Config::class.java.declaredClasses.find { it.simpleName == "SecurityPatchState" }
                ?: throw ClassNotFoundException("SecurityPatchState not found")
        val constructor = stateClass.getDeclaredConstructor(Map::class.java, Any::class.java)
        constructor.isAccessible = true
        val state = constructor.newInstance(testPatchMap, null)

        securityPatchStateField.set(Config, state)

        try {
            // 3. Verify Patch Level
            // 2023-12-05 -> 202312
            val level = Config.getPatchLevel(1002)
            assertEquals(202312, level)
        } finally {
            Config.reset()
        }
    }
}
