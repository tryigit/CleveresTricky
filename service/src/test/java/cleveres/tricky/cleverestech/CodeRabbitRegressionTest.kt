package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger

class CodeRabbitRegressionTest {
    @Test
    fun `forced package process cleanup waits through interruption`() {
        val process = InterruptOnceProcess()

        try {
            InstalledPackagesCompat.terminateProcessBeforePermitRelease(process)

            assertTrue("Cleanup must request forcible termination", process.destroyed)
            assertEquals("Cleanup must retry waitFor after interruption", 2, process.waitCalls.get())
            assertFalse("Cleanup must not return while the child is alive", process.isAlive)
            assertTrue("Interrupted status must be restored after cleanup", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `keybox child observer reference clears even when stop throws`() {
        val watcher = KeyboxDirectoryRefreshWatcher
        val type = watcher::class.java
        val childField = type.getDeclaredField("childObserver").apply { isAccessible = true }
        val generationField = type.getDeclaredField("childGeneration").apply { isAccessible = true }
        val disarmMethod = type.getDeclaredMethod("disarmChildLocked").apply { isAccessible = true }
        val originalChild = childField.get(watcher)
        val originalGeneration = generationField.getLong(watcher)
        val throwingHandle =
            object : RuntimeWatchHandle {
                override fun startWatching() = Unit

                override fun stopWatching() {
                    throw IOException("injected stop failure")
                }
            }

        childField.set(watcher, throwingHandle)
        try {
            val failure =
                assertThrows(InvocationTargetException::class.java) {
                    disarmMethod.invoke(watcher)
                }
            assertTrue(failure.cause is IOException)
            assertNull("Retired child handle must be cleared before stopWatching", childField.get(watcher))
        } finally {
            childField.set(watcher, originalChild)
            generationField.setLong(watcher, originalGeneration)
        }
    }

    private class InterruptOnceProcess : Process() {
        var destroyed = false
        val waitCalls = AtomicInteger(0)
        private var alive = true

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            if (waitCalls.incrementAndGet() == 1) {
                throw InterruptedException("injected interruption")
            }
            alive = false
            return 0
        }

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("still running")
            return 0
        }

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }

        override fun isAlive(): Boolean = alive
    }
}
