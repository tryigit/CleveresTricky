package cleveres.tricky.cleverestech

import org.junit.Test
import kotlin.system.measureTimeMillis

class BootLogicPerfTest {
    @Test
    fun testGetSystemPropertyPerf() {
        val method = BootLogic::class.java.getDeclaredMethod("getSystemProperty", String::class.java)
        method.isAccessible = true

        // Warm up
        for (i in 0..5) {
            method.invoke(BootLogic, "ro.build.version.sdk")
        }

        val time =
            measureTimeMillis {
                for (i in 0..50) {
                    method.invoke(BootLogic, "ro.build.version.sdk")
                }
            }
        System.err.println("BASELINE_TIME_MS: " + time)
    }
}
