package cleveres.tricky.cleverestech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

@RunWith(AndroidJUnit4::class)
class SelinuxInstrumentationTest {
    private fun executeCommand(vararg command: String): Pair<Int, String> {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val exitCode = process.waitFor()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val errorOutput = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            Pair(exitCode, output + errorOutput)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "Unknown error")
        }
    }

    @Test
    fun testSelinuxContextsAndAccess() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testFile = File(appContext.filesDir, "test_selinux.txt")
        testFile.writeText("test_content")

        assertTrue("File should be created", testFile.exists())

        // Execute ls -Z to check the SELinux context
        val (exitCode, output) = executeCommand("ls", "-Z", testFile.absolutePath)

        // Output should contain the file path and something like u:object_r:app_data_file:s0
        assertTrue("ls -Z should execute successfully", exitCode == 0)
        assertNotNull("Output should not be null", output)
        assertTrue("Output should contain u:object_r or similar SELinux context", output.contains("u:object_r:") || output.contains("?"))

        testFile.delete()
    }

    @Test
    fun testChconFallback() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testFile = File(appContext.filesDir, "test_chcon.txt")
        testFile.writeText("test_content")

        assertTrue("File should be created", testFile.exists())

        // Try to change context, expected to fail on regular non-root app context
        val (exitCode, output) = executeCommand("chcon", "u:object_r:system_file:s0", testFile.absolutePath)

        // Either chcon is not found, or it fails due to permission denied
        // In both cases, exit code shouldn't be 0 for a non-root app trying to set system_file
        assertTrue("chcon should fail for non-root apps setting system context", exitCode != 0)
        assertTrue(
            "Output should indicate permission denied or not found",
            output.contains("Permission denied", ignoreCase = true) ||
                output.contains("not found", ignoreCase = true) ||
                output.contains("Operation not permitted", ignoreCase = true),
        )

        testFile.delete()
    }
}
