from pathlib import Path
import re

Path("service/src/test/java/cleveres/tricky/cleverestech/ConfigResetTest.kt").write_text('''package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigResetTest {
    @Test
    fun testResetClearsDynamicPatchCache() {
        val field = Config::class.java.getDeclaredField("dynamicPatchCache")
        field.isAccessible = true
        val cache = requireNotNull(field.get(Config))
        val pollutedKey = "2023-12-05"
        val pollutedValue = System.currentTimeMillis() to 202401
        cache.javaClass.getMethod("put", Any::class.java, Any::class.java).invoke(cache, pollutedKey, pollutedValue)

        val cached = cache.javaClass.getMethod("get", Any::class.java).invoke(cache, pollutedKey) as Pair<*, *>
        assertEquals(202401, cached.second)

        Config.reset()

        val cacheAfterReset = requireNotNull(field.get(Config))
        val size = cacheAfterReset.javaClass.getMethod("size").invoke(cacheAfterReset) as Int
        assertEquals(0, size)
    }
}
''')

Path("service/src/test/java/cleveres/tricky/cleverestech/ConfigTargetStateTest.kt").write_text('''package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.PackageTrie
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConfigTargetStateTest {
    @Before
    fun setUp() {
        resetConfig()
    }

    @After
    fun tearDown() {
        resetConfig()
    }

    private fun resetConfig() {
        setPrivateField(Config, "isTeeBrokenMode", false)
        setPrivateField(Config, "isGlobalMode", false)
        setPrivateField(Config, "isSpoofEnabled", true)
        Config.clockSource = { System.currentTimeMillis() }

        invokeMapMethod(getPrivateField(Config, "packageCache"), "clear")

        val targetStateClass = Class.forName("cleveres.tricky.cleverestech.Config\\$TargetState")
        val constructor = targetStateClass.getDeclaredConstructor(PackageTrie::class.java)
        constructor.isAccessible = true
        val emptyState = constructor.newInstance(PackageTrie<Boolean>())
        setPrivateField(Config, "targetState", emptyState)
    }

    @Test
    fun testNeedHack_caching() {
        var now = System.currentTimeMillis()
        Config.clockSource = { now }
        val hackTrie = PackageTrie<Boolean>()
        hackTrie.add("com.hack.me", true)

        val targetState = createTargetState(hackTrie)
        setPrivateField(Config, "targetState", targetState)

        val uid = 10_001
        mockPackage(uid, arrayOf("com.hack.me"))

        assertTrue("needHack should return true for com.hack.me", Config.needHack(uid))

        val hackCache = getFieldFromTargetState(targetState, "hackCache")
        assertTrue("Cache should contain entry for the app UID", invokeMapMethod(hackCache, "containsKey", uid) == true)

        invokeMapMethod(getPrivateField(Config, "packageCache"), "remove", uid)

        assertTrue("needHack should return cached true even if package info is gone", Config.needHack(uid))

        now += 61_000
        assertFalse("Expired UID decisions must be recalculated", Config.needHack(uid))
    }

    @Test
    fun `identity engine and legacy safe mode do not disable core targeting`() {
        val uid = 10_002
        mockPackage(uid, arrayOf("com.example.core"))
        setPrivateField(Config, "isGlobalMode", true)
        setPrivateField(Config, "isSpoofEnabled", false)
        setPrivateField(Config, "isTeeBrokenMode", true)

        assertTrue("Core targeting must remain active while identity spoofing is off", Config.needHack(uid))
    }

    private fun createTargetState(hack: PackageTrie<Boolean>): Any {
        val clazz = Class.forName("cleveres.tricky.cleverestech.Config\\$TargetState")
        val constructor = clazz.getDeclaredConstructor(PackageTrie::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(hack)
    }

    private fun getFieldFromTargetState(
        instance: Any,
        fieldName: String,
    ): Any {
        val field = instance.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return requireNotNull(field.get(instance))
    }

    private fun setPrivateField(
        instance: Any,
        fieldName: String,
        value: Any?,
    ) {
        val field = instance.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(instance, value)
    }

    private fun getPrivateField(
        instance: Any,
        fieldName: String,
    ): Any {
        val field = instance.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return requireNotNull(field.get(instance))
    }

    private fun invokeMapMethod(
        instance: Any,
        methodName: String,
        key: Any? = null,
    ): Any? =
        if (key == null) {
            instance.javaClass.getMethod(methodName).invoke(instance)
        } else {
            instance.javaClass.getMethod(methodName, Any::class.java).invoke(instance, key)
        }

    private fun mockPackage(
        uid: Int,
        packages: Array<String>,
    ) {
        Config.setPackagesForTesting(uid, packages)
    }
}
''')

for name in [
    "WebServerCsrfTest.kt",
    "WebServerSaveValidationTest.kt",
    "WebServerStoredXssTest.kt",
    "WebServerXssTest.kt",
]:
    path = Path("service/src/test/java/cleveres/tricky/cleverestech") / name
    text = path.read_text()
    text = text.replace('@Deprecated("NanoHTTPD deprecated this, ignore warning")', '@Deprecated("Deprecated by NanoHTTPD")')
    text, count = re.subn(r'(?m)^(\s*)override fun getParms\(', r'\1@Deprecated("Use getParameters")\n\1override fun getParms(', text)
    if count == 0:
        raise SystemExit(f"getParms override not found in {name}")
    path.write_text(text)
