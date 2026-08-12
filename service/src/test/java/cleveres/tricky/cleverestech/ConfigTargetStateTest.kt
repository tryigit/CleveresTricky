package cleveres.tricky.cleverestech

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
        Config.reset()
    }

    @Test
    fun testNeedHack_caching() {
        val hackTrie = PackageTrie<Boolean>()
        hackTrie.add("com.hack.me", true)

        val targetState = createTargetState(hackTrie)
        setPrivateField(Config, "targetState", targetState)

        val uid = 10_001
        mockPackage(uid, arrayOf("com.hack.me"))

        // Freeze the test clock only after the package-cache entry is created.
        // setPackagesForTesting timestamps entries with the real clock, so freezing
        // it first can make a freshly inserted entry appear a few milliseconds in
        // the future and trigger the protected-empty-UID path nondeterministically.
        var now = System.currentTimeMillis()
        Config.clockSource = { now }

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
        val clazz = Class.forName("cleveres.tricky.cleverestech.Config\$TargetState")
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
