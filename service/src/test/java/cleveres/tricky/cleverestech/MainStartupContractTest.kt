package cleveres.tricky.cleverestech

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainStartupContractTest {
    @Test
    fun `boot compatibility failure is routed to supervisor retry`() {
        val root = locateRoot()
        val mainSource = File(root, "service/src/main/java/cleveres/tricky/cleverestech/Main.kt").readText()
        val bootSource = File(root, "service/src/main/java/cleveres/tricky/cleverestech/BootLogic.kt").readText()

        assertTrue(bootSource.contains("fun run(): Boolean"))
        assertTrue(bootSource.contains("Logger.e(\"BootLogic failed\", e)\n            false"))
        assertTrue(mainSource.contains("check(BootLogic.run())"))
        assertTrue(mainSource.contains("Main: Exiting so the module supervisor can retry initialization"))
    }

    @Test
    fun `web ui adapter registers before backend readiness gate`() {
        val root = locateRoot()
        val source = File(root, "service/src/main/java/cleveres/tricky/cleverestech/Main.kt").readText()
        val entry = source.indexOf("fun main(args: Array<String>)")
        val registration = source.indexOf("startWebUiBridge(configDir, isTampered)", entry)
        val backendWait = source.indexOf("NativeBackend.awaitReady(BACKEND_STARTUP_TIMEOUT_MS)", entry)
        assertTrue(entry >= 0)
        assertTrue(registration > entry)
        assertTrue(backendWait > registration)
    }

    private fun locateRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(6) {
            if (File(current, "service").isDirectory && File(current, "rust").isDirectory) return current
            current = current.parentFile ?: return@repeat
        }
        error("Repository root not found")
    }
}
