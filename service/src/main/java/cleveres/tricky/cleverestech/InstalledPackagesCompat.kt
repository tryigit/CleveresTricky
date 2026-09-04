package cleveres.tricky.cleverestech

import android.content.pm.IPackageManager
import android.os.Build
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Versioned package enumeration for the privileged service and Android platform-contract tests.
 *
 * Production normally uses the hidden IPackageManager Binder API. Android instrumentation runs in
 * the application hidden-API domain, where Android 17 can deny linking even when the runtime ABI is
 * known. In that case we fall back to Android's fixed `cmd package list packages` interface. The
 * fallback never invokes a shell, accepts only the integer user id, and bounds time and output.
 */
internal object InstalledPackagesCompat {
    private const val COMMAND_TIMEOUT_MS = 3_000L
    private const val PROCESS_EXIT_GRACE_MS = 250L
    private const val MAX_COMMAND_OUTPUT_BYTES = 1024 * 1024
    private const val MAX_COMMAND_PACKAGES = 100_000
    private const val MAX_CONCURRENT_COMMANDS = 4
    private const val PACKAGE_PREFIX = "package:"
    private val packageNamePattern = Regex("[A-Za-z0-9_.]{1,255}")
    private val commandPermits = Semaphore(MAX_CONCURRENT_COMMANDS, true)
    private val workerExecutor =
        java.util.concurrent.ThreadPoolExecutor(
            0,
            MAX_CONCURRENT_COMMANDS,
            30L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { runnable -> Thread(runnable, "ct-package-list").apply { isDaemon = true } }
        ).apply {
            allowCoreThreadTimeOut(true)
        }

    fun getInstalledPackageNames(
        packageManager: IPackageManager,
        userId: Int,
    ): List<String> =
        try {
            getInstalledPackageNamesViaBinder(packageManager, userId)
        } catch (error: LinkageError) {
            Logger.i("Hidden PackageManager package enumeration is unavailable; using bounded cmd fallback")
            runCatching { getInstalledPackageNamesViaCommand(userId) }.getOrDefault(emptyList())
        } catch (error: SecurityException) {
            Logger.i("Hidden PackageManager package enumeration was denied; using bounded cmd fallback")
            runCatching { getInstalledPackageNamesViaCommand(userId) }.getOrDefault(emptyList())
        } catch (error: Exception) {
            Logger.i("Hidden PackageManager package enumeration failed; using bounded cmd fallback")
            runCatching { getInstalledPackageNamesViaCommand(userId) }.getOrDefault(emptyList())
        }

    private fun getInstalledPackageNamesViaBinder(
        packageManager: IPackageManager,
        userId: Int,
    ): List<String> {
        val packages =
            when {
                Build.VERSION.SDK_INT >= 37 -> packageManager.getInstalledPackagesV17(0L, userId).list
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> packageManager.getInstalledPackages(0L, userId).list
                else -> packageManager.getInstalledPackages(0, userId).list
            }
        return packages?.mapNotNull { it.packageName } ?: emptyList()
    }

    private fun getInstalledPackageNamesViaCommand(userId: Int): List<String> {
        require(userId >= 0) { "Package-list user id must be non-negative" }
        if (!commandPermits.tryAcquire()) {
            throw IOException("Package-list command capacity is exhausted")
        }

        try {
            val process =
                try {
                    ProcessBuilder(
                        "/system/bin/cmd",
                        "package",
                        "list",
                        "packages",
                        "--user",
                        userId.toString(),
                    ).redirectErrorStream(true).start()
                } catch (error: Exception) {
                    Logger.e("Package-list command could not be started", error)
                    return emptyList()
                }

            val reader =
                FutureTask {
                    val packages = parsePackageListStream(process.inputStream)
                    if (!process.waitFor(PROCESS_EXIT_GRACE_MS, TimeUnit.MILLISECONDS)) {
                        throw IOException("Package-list command did not terminate after closing its output")
                    }
                    if (process.exitValue() != 0) {
                        throw IOException("Package-list command failed with exit code ${process.exitValue()}")
                    }
                    packages
                }
            try {
                try {
                    workerExecutor.execute(reader)
                } catch (error: RejectedExecutionException) {
                    throw IOException("Package-list command worker capacity is exhausted", error)
                }
                return reader.get(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (error: TimeoutException) {
                throw IOException("Package-list command timed out", error)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Package-list command was interrupted", error)
            } catch (error: ExecutionException) {
                val cause = error.cause
                if (cause is IOException) throw cause
                throw IOException("Package-list command failed", cause)
            } finally {
                reader.cancel(true)
                terminateProcessBeforePermitRelease(process)
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
            }
        } finally {
            commandPermits.release()
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun terminateProcessBeforePermitRelease(process: Process) {
        if (!process.isAlive) return
        process.destroyForcibly()
        var interrupted = false
        while (true) {
            try {
                process.waitFor()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    @androidx.annotation.VisibleForTesting
    internal fun parsePackageListStream(input: InputStream): List<String> {
        val packages = ArrayList<String>()
        SizeLimitedInputStream(input, MAX_COMMAND_OUTPUT_BYTES.toLong()).bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (!line.startsWith(PACKAGE_PREFIX)) continue
                val packageName = line.substring(PACKAGE_PREFIX.length)
                if (!packageNamePattern.matches(packageName)) continue
                require(packages.size < MAX_COMMAND_PACKAGES) { "Package-list output contains too many packages" }
                packages += packageName
            }
        }
        return packages
    }

    @androidx.annotation.VisibleForTesting
    internal fun parsePackageListOutput(output: ByteArray): List<String> {
        require(output.size <= MAX_COMMAND_OUTPUT_BYTES) { "Package-list output exceeds its size limit" }
        return parsePackageListStream(output.inputStream())
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() = Unit

    private class SizeLimitedInputStream(
        input: InputStream,
        private var remaining: Long,
    ) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining == 0L) return probeEndOfInput()
            val value = super.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            if (remaining == 0L) return probeEndOfInput()
            val allowed = minOf(length.toLong(), remaining).toInt()
            val count = super.read(bytes, offset, allowed)
            if (count > 0) remaining -= count.toLong()
            return count
        }

        private fun probeEndOfInput(): Int {
            if (super.read() < 0) return -1
            throw IOException("Package-list command output exceeds its size limit")
        }
    }
}
