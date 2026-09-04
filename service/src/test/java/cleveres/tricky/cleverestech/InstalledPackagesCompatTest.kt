package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor

class InstalledPackagesCompatTest {
    @Test
    fun `package command parser accepts only canonical package records`() {
        val output =
            """
            package:com.example.alpha
            warning: ignored
            package:cleveres.tricky.cleverestech
            package:../escape
            package:com.example.with-dash
            package:com.example_beta
            
            """.trimIndent().toByteArray(Charsets.UTF_8)

        assertEquals(
            listOf(
                "com.example.alpha",
                "cleveres.tricky.cleverestech",
                "com.example_beta",
            ),
            InstalledPackagesCompat.parsePackageListOutput(output),
        )
    }

    @Test
    fun `package command parser rejects oversized output`() {
        val oversized = ByteArray(1024 * 1024 + 1) { 'a'.code.toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            InstalledPackagesCompat.parsePackageListOutput(oversized)
        }
    }

    @Test
    fun `stream parser enforces byte bound before allocating an oversized line`() {
        val input =
            object : InputStream() {
                var consumed = 0

                override fun read(): Int =
                    if (consumed++ <= 1024 * 1024) 'a'.code else -1

                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    val remaining = 1024 * 1024 + 1 - consumed
                    if (remaining <= 0) return -1
                    val count = minOf(length, remaining)
                    bytes.fill('a'.code.toByte(), offset, offset + count)
                    consumed += count
                    return count
                }
            }

        assertThrows(IOException::class.java) {
            InstalledPackagesCompat.parsePackageListStream(input)
        }
        assertTrue("Parser must probe at most one byte beyond the limit", input.consumed <= 1024 * 1024 + 1)
    }

    @Test
    fun `package command workers have no unbounded admission queue`() {
        val field = InstalledPackagesCompat::class.java.getDeclaredField("workerExecutor").apply { isAccessible = true }
        val executor = field.get(InstalledPackagesCompat) as ThreadPoolExecutor

        assertEquals(4, executor.maximumPoolSize)
        assertEquals(0, executor.queue.remainingCapacity())
    }

    @Test
    fun `package process admission is capped before worker execution`() {
        val field = InstalledPackagesCompat::class.java.getDeclaredField("commandPermits").apply { isAccessible = true }
        val permits = field.get(InstalledPackagesCompat) as Semaphore
        var acquired = 0

        try {
            repeat(4) {
                assertTrue("Expected one permit per allowed package command", permits.tryAcquire())
                acquired++
            }
            assertFalse("A fifth package command must be rejected before spawning", permits.tryAcquire())
        } finally {
            repeat(acquired) { permits.release() }
        }

        assertEquals(4, permits.availablePermits())
    }

    @Test
    fun `installed packages handles null slice gracefully`() {
        val mockPm =
            java.lang.reflect.Proxy.newProxyInstance(
                android.content.pm.IPackageManager::class.java.classLoader,
                arrayOf(android.content.pm.IPackageManager::class.java),
            ) { _, _, _ -> null } as android.content.pm.IPackageManager

        val result = InstalledPackagesCompat.getInstalledPackageNames(mockPm, 0)
        assertEquals(emptyList<String>(), result)
    }
}
