package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFileOperations
import java.io.File
import java.io.InputStream

class MockSecureFileOperations : SecureFileOperations {
    override fun writeText(
        file: File,
        content: String,
    ) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override fun writeStream(
        file: File,
        inputStream: InputStream,
        limit: Long,
    ) {
        file.parentFile?.mkdirs()
        file.outputStream().use { output ->
            inputStream.copyTo(output)
        }
    }

    override fun mkdirs(
        file: File,
        mode: Int,
    ) {
        file.mkdirs()
    }

    override fun touch(
        file: File,
        mode: Int,
    ) {
        file.parentFile?.mkdirs()
        file.createNewFile()
    }
}
