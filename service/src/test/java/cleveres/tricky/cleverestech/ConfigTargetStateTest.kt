package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.PackageTrie
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

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

        val packageCache =
            getPrivateField(Config, "packageCache") as
                @Suppress("UNCHECKED_CAST")
                ConcurrentHashMap<Int, Any>
        packageCache.clear()

        val targetStateClass = Class.forName("cleveres.tricky.cleverestech.Config\$TargetState")
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

        val hackCache =
            getFieldFromTargetState(targetState, "hackCache") as
                @Suppress("UNCHECKED_CAST")
                ConcurrentHashMap<Int, Any>
        assertTrue("Cache should contain entry for the app UID", hackCache.containsKey(uid))

        val packageCache =
            getPrivateField(Config, "packageCache") as
                @Suppress("UNCHECKED_CAST")
                ConcurrentHashMap<Int, Any>
        packageCache.remove(uid)

        assertTrue("needHack should return cached true even if package info is gone", Config.needHack(uid))

        now += 61_000
        assertFalse("Expired UID decisions must be recalculated", Config.needHack(uid))
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
        return field.get(instance)
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
    ): Any? {
        val field = instance.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(instance)
    }

    private fun mockPackage(
        uid: Int,
        packages: Array<String>,
    ) {
        val packageCache =
            getPrivateField(Config, "packageCache") as
                @Suppress("UNCHECKED_CAST")
                ConcurrentHashMap<Int, Any>

        val cachedPackageClass = Class.forName("cleveres.tricky.cleverestech.Config\$CachedPackage")
        val constructor = cachedPackageClass.getDeclaredConstructor(Array<String>::class.java, Long::class.javaPrimitiveType)
        constructor.isAccessible = true
        val cachedPkg = constructor.newInstance(packages, System.currentTimeMillis())

        packageCache[uid] = cachedPkg
    }
}
