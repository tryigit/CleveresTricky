package cleveres.tricky.cleverestech.util

import android.system.Os
import cleveres.tricky.cleverestech.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface SecureFileOperations {
    fun writeText(
        file: File,
        content: String,
    )

    fun writeBytes(
        file: File,
        content: ByteArray,
    ) {
        throw UnsupportedOperationException("writeBytes is not implemented")
    }

    fun writeStream(
        file: File,
        inputStream: InputStream,
        limit: Long = -1L,
    ) {
        throw UnsupportedOperationException("writeStream is not implemented")
    }

    fun mkdirs(
        file: File,
        mode: Int,
    )

    fun touch(
        file: File,
        mode: Int,
    )
}

object SecureFile {
    @Volatile
    internal var impl: SecureFileOperations = DefaultSecureFileOperations()

    private val lock = ReentrantLock()

    fun writeText(
        file: File,
        content: String,
    ) {
        lock.withLock {
            impl.writeText(file, content)
        }
    }

    fun writeBytes(
        file: File,
        content: ByteArray,
    ) {
        lock.withLock {
            impl.writeBytes(file, content)
        }
    }

    fun writeStream(
        file: File,
        inputStream: InputStream,
        limit: Long = -1L,
    ) {
        lock.withLock {
            impl.writeStream(file, inputStream, limit)
        }
    }

    fun mkdirs(
        file: File,
        mode: Int,
    ) {
        lock.withLock {
            impl.mkdirs(file, mode)
        }
    }

    fun touch(
        file: File,
        mode: Int,
    ) {
        lock.withLock {
            impl.touch(file, mode)
        }
    }

    class DefaultSecureFileOperations : SecureFileOperations {
        override fun writeText(
            file: File,
            content: String,
        ) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            try {
                writeBytes(file, bytes)
            } finally {
                bytes.fill(0)
            }
        }

        override fun writeBytes(
            file: File,
            content: ByteArray,
        ) {
            atomicWrite(file) { output ->
                output.write(content)
            }
        }

        override fun writeStream(
            file: File,
            inputStream: InputStream,
            limit: Long,
        ) {
            require(limit >= -1L) { "limit must be -1 or non-negative" }
            atomicWrite(file) { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = inputStream.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (limit >= 0 && count.toLong() > limit - total) {
                        throw IOException("File size exceeds the $limit-byte limit")
                    }
                    output.write(buffer, 0, count)
                    total += count
                }
            }
        }

        private fun atomicWrite(
            file: File,
            writer: (FileOutputStream) -> Unit,
        ) {
            val parent =
                file.absoluteFile.parentFile
                    ?: throw IOException("Destination has no parent directory: $file")
            rejectSymbolicLinks(parent)
            if (Files.isSymbolicLink(file.toPath())) {
                throw IOException("Refusing symbolic-link destination: $file")
            }
            mkdirs(parent, DIRECTORY_MODE)

            val temporary =
                Files.createTempFile(
                    parent.toPath(),
                    ".${file.name}.",
                    ".tmp",
                ).toFile()

            try {
                enforceMode(temporary, FILE_MODE)
                FileOutputStream(temporary, false).use { output ->
                    writer(output)
                    output.flush()
                    output.fd.sync()
                }

                try {
                    Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: UnsupportedOperationException) {
                    Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                enforceMode(file, FILE_MODE)
            } catch (error: Exception) {
                if (temporary.exists() && !temporary.delete()) {
                    Logger.w("SecureFile: Could not remove temporary file ${temporary.name}")
                }
                throw if (error is IOException) {
                    error
                } else {
                    IOException("SecureFile: Failed to atomically write ${file.name}", error)
                }
            }
        }

        override fun mkdirs(
            file: File,
            mode: Int,
        ) {
            rejectSymbolicLinks(file)
            if (file.exists()) {
                if (!file.isDirectory) {
                    throw IOException("Path exists but is not a directory: $file")
                }
                enforceMode(file, mode)
                return
            }

            val parent = file.absoluteFile.parentFile
            if (parent != null && parent != file && !parent.exists()) {
                mkdirs(parent, mode)
            }
            if (!file.mkdir() && !file.isDirectory) {
                throw IOException("Could not create directory: $file")
            }
            enforceMode(file, mode)
        }

        override fun touch(
            file: File,
            mode: Int,
        ) {
            val parent =
                file.absoluteFile.parentFile
                    ?: throw IOException("Destination has no parent directory: $file")
            rejectSymbolicLinks(parent)
            if (Files.isSymbolicLink(file.toPath())) {
                throw IOException("Refusing symbolic-link destination: $file")
            }
            mkdirs(parent, DIRECTORY_MODE)
            if (!file.exists() && !file.createNewFile()) {
                throw IOException("Could not create file: $file")
            }
            if (!file.isFile) {
                throw IOException("Path exists but is not a regular file: $file")
            }
            enforceMode(file, mode)
        }

        private fun rejectSymbolicLinks(file: File) {
            var current: File? = file.absoluteFile
            while (current != null) {
                if (Files.isSymbolicLink(current.toPath())) {
                    throw IOException("Refusing path containing a symbolic link: $current")
                }
                current = current.parentFile
            }
        }

        private fun enforceMode(
            file: File,
            mode: Int,
        ) {
            try {
                Os.chmod(file.absolutePath, mode)
                return
            } catch (_: Exception) {
                // Android host-side unit tests do not provide a working Os stub.
            } catch (_: LinkageError) {
                // Fall back to java.io permission APIs on non-Android runtimes.
            }

            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            val readable = mode and 0b100_000_000 != 0
            val writable = mode and 0b010_000_000 != 0
            val executable = mode and 0b001_000_000 != 0
            if (readable && !file.setReadable(true, true)) {
                throw IOException("Could not set owner-read permission on $file")
            }
            if (writable && !file.setWritable(true, true)) {
                throw IOException("Could not set owner-write permission on $file")
            }
            if (executable && !file.setExecutable(true, true)) {
                throw IOException("Could not set owner-execute permission on $file")
            }
        }

        private companion object {
            const val FILE_MODE = 0b110_000_000 // 0600
            const val DIRECTORY_MODE = 0b111_000_000 // 0700
        }
    }
}
