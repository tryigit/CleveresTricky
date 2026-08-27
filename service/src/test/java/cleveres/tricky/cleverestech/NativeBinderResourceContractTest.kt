package cleveres.tricky.cleverestech

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBinderResourceContractTest {
    @Test
    fun `native interceptor registry has a bounded dead-target cleanup path`() {
        val source = nativeSource().readText()

        assertTrue(source.contains("kMaxInterceptorRegistrations"))
        assertTrue(source.contains("pruneDeadItemsLocked"))
        assertTrue(source.contains("items.size() >= kMaxInterceptorRegistrations"))
        assertTrue(source.contains("target == nullptr"))
    }

    private fun nativeSource(): File =
        listOf(
            File("module/src/main/cpp/binder_interceptor.cpp"),
            File("../module/src/main/cpp/binder_interceptor.cpp"),
        ).firstOrNull(File::isFile)
            ?: error("Could not locate binder_interceptor.cpp from ${File(".").absolutePath}")
}
