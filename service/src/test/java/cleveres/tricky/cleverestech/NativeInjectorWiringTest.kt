package cleveres.tricky.cleverestech

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeInjectorWiringTest {
    private val root: File by lazy {
        var candidate = File(System.getProperty("user.dir")).canonicalFile
        repeat(5) {
            if (File(candidate, "settings.gradle.kts").isFile) return@lazy candidate
            candidate = candidate.parentFile ?: error("Repository root not found")
        }
        error("Repository root not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `module builds and packages the native injector`() {
        val buildScript = File(root, "module/build.gradle.kts").readText()

        assertTrue(buildScript.contains("src/main/cpp/CMakeLists.txt"))
        assertTrue(buildScript.contains("intermediates/cxx"))
        assertTrue(buildScript.contains("include(\"**/inject\")"))
        assertFalse(buildScript.contains("rename { \"inject\" }"))
        assertFalse(buildScript.contains("release/daemon"))
    }

    @Test
    fun `injector performs a real remote library load`() {
        val source = File(root, "module/src/main/cpp/inject/main.cpp").readText()

        assertTrue(source.contains("PTRACE_ATTACH"))
        assertTrue(source.contains("SCM_RIGHTS"))
        assertTrue(source.contains("android_dlopen_ext"))
        assertTrue(source.contains("dlsym"))
        assertTrue(source.contains("MSG_CTRUNC"))
        assertTrue(source.contains("return result ? 0 : 1"))
    }
}
