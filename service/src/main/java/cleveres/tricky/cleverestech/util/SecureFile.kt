package cleveres.tricky.cleverestech.util

import android.system.Os
import android.system.OsConstants
import cleveres.tricky.cleverestech.Logger
import java.io.File
import java.io.FileDescriptor
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface SecureFileOperations {
    fun writeText(file: File, content: String)

    fun writeBytes(file: File, content: ByteArray) {
        throw UnsupportedOperationException("writeBytes not implemented in mock")
    }

    fun writeStream(file: File, inputStream: InputStream, limit: Long = -1L) {}

    fun mkdirs(file: File, mode: Int)

    fun touch(file: File, mode: Int)
}

object SecureFile {
    @Volatile
    var impl: SecureFileOperations = DefaultSecureFileOperations()
    private val lock = ReentrantLock()

    fun writeText(file: File, content: String) {
        lock.withLock {
            impl.writeText(file, content)
        }
        ConfigCommandDispatcher.onTextWritten(file, content)
    }

    fun writeBytes(file: File, content: ByteArray) {
        lock.withLock {
            impl.writeBytes(file, content)
        }
    }

    fun writeStream(file: File, inputStream: InputStream, limit: Long = -1L) {
        lock.withLock {
            impl.writeStream(file, inputStream, limit)
        }
    }

    fun mkdirs(file: File, mode: Int) {
        lock.withLock {
            impl.mkdirs(file, mode)
        }
    }

    fun touch(file: File, mode: Int) {
        lock.withLock {
            impl.touch(file, mode)
        }
    }

    class DefaultSecureFileOperations : SecureFileOperations {
        override fun writeText(file: File, content: String) {
            writeBytes(file, content.toByteArray(Charsets.UTF_8))
        }

        override fun writeBytes(file: File, content: ByteArray) {
            val path = file.absolutePath
            val tmpPath = "$path.tmp"
            var fd: FileDescriptor? = null
            try {
                val mode = 384
                val flags = OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_WRONLY
                try {
                    fd = Os.open(tmpPath, flags, mode)
                } catch (e: Exception) {
                    file.writeBytes(content)
                    return
                } catch (e: NoClassDefFoundError) {
                    file.writeBytes(content)
                    return
                }

                // Ensure permissions are set correctly
                runCatching { Os.fchmod(fd, mode) }

                var bytesWritten = 0
                while (bytesWritten < content.size) {
                    val w = runCatching { Os.write(fd, content, bytesWritten, content.size - bytesWritten) }.getOrElse { 0 }
                    if (w <= 0) {
                        Logger.e("SecureFile: Partial write to $path (wrote $bytesWritten/${content.size} bytes)")
                        break
                    }
                    bytesWritten += w
                }

                if (bytesWritten < content.size) {
                    // Incomplete write — clean up temp file and throw
                    runCatching { Os.close(fd) }
                    fd = null
                    runCatching { Os.remove(tmpPath) }
                    throw java.io.IOException("SecureFile: Incomplete write to $path ($bytesWritten/${content.size} bytes)")
                }

                // Sync to verify write
                runCatching { Os.fsync(fd) }

                // Close before rename
                runCatching { Os.close(fd) }
                fd = null

                // Atomic rename
                runCatching { Os.rename(tmpPath, path) }.onFailure {
                    file.writeBytes(content)
                }
            } catch (e: Exception) {
                Logger.e("SecureFile: Failed to write to $path", e)
                runCatching { Os.remove(tmpPath) }
            } finally {
                if (fd != null) {
                    runCatching { Os.close(fd) }
                }
            }
        }

        override fun writeStream(file: File, inputStream: InputStream, limit: Long) {
            val path = file.absolutePath
            val tmpPath = "$path.tmp"
            var fd: FileDescriptor? = null
            try {
                val mode = 384
                val flags = OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_WRONLY
                try {
                    fd = Os.open(tmpPath, flags, mode)
                } catch (e: Exception) {
                    file.outputStream().use { inputStream.copyTo(it) }
                    return
                } catch (e: NoClassDefFoundError) {
                    file.outputStream().use { inputStream.copyTo(it) }
                    return
                }

                // Ensure permissions are set correctly
                runCatching { Os.fchmod(fd, mode) }

                val buffer = ByteArray(8192) // 8KB buffer
                var bytesRead: Int
                var totalBytesWritten = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (limit > 0 && totalBytesWritten + bytesRead > limit) {
                        throw java.io.IOException("File size exceeds limit of $limit bytes")
                    }

                    var chunkWritten = 0
                    while (chunkWritten < bytesRead) {
                        val w = runCatching { Os.write(fd, buffer, chunkWritten, bytesRead - chunkWritten) }.getOrElse { 0 }
                        if (w <= 0) {
                            Logger.e("SecureFile: Partial stream write to $path")
                            break
                        }
                        chunkWritten += w
                    }
                    if (chunkWritten < bytesRead) {
                        runCatching { Os.close(fd) }
                        fd = null
                        runCatching { Os.remove(tmpPath) }
                        throw java.io.IOException("SecureFile: Incomplete stream write to $path")
                    }
                    totalBytesWritten += bytesRead
                }

                // Sync to verify write
                runCatching { Os.fsync(fd) }

                // Close before rename
                runCatching { Os.close(fd) }
                fd = null

                // Atomic rename
                runCatching { Os.rename(tmpPath, path) }.onFailure {
                    file.outputStream().use { inputStream.copyTo(it) }
                }
            } catch (e: Exception) {
                Logger.e("SecureFile: Failed to write stream to $path", e)
                runCatching { Os.remove(tmpPath) }
            } finally {
                if (fd != null) {
                    runCatching { Os.close(fd) }
                }
            }
        }

        override fun mkdirs(file: File, mode: Int) {
            if (file.exists()) {
                if (file.isDirectory) {
                    runCatching { Os.chmod(file.absolutePath, mode) }.onFailure {
                        file.setExecutable(true, false)
                        file.setReadable(true, false)
                        file.setWritable(true, false)
                    }
                }
                return
            }
            // Ensure parent exists
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                mkdirs(parent, mode)
            }
            // Create directory
            try {
                runCatching {
                    Os.mkdir(file.absolutePath, mode)
                    // Enforce again just in case umask messed it up
                    Os.chmod(file.absolutePath, mode)
                }.onFailure {
                    file.mkdir()
                }
            } catch (e: Exception) {
                // Check if it was created by another thread/process in the meantime
                if (!file.exists()) {
                    Logger.e("SecureFile: Failed to mkdirs $file", e)
                } else {
                    runCatching { Os.chmod(file.absolutePath, mode) }.onFailure {
                        file.setExecutable(true, false)
                        file.setReadable(true, false)
                        file.setWritable(true, false)
                    }
                }
            }
        }

        override fun touch(file: File, mode: Int) {
            val path = file.absolutePath
            var fd: FileDescriptor? = null
            try {
                val flags = OsConstants.O_CREAT or OsConstants.O_WRONLY
                try {
                    fd = Os.open(path, flags, mode)
                    runCatching { Os.fchmod(fd, mode) }
                } catch (e: Exception) {
                    file.createNewFile()
                    return
                } catch (e: NoClassDefFoundError) {
                    file.createNewFile()
                    return
                }
            } catch (e: Exception) {
                Logger.e("SecureFile: Failed to touch $path", e)
            } finally {
                if (fd != null) {
                    runCatching { Os.close(fd) }
                }
            }
        }
    }
}
