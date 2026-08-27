package cleveres.tricky.encryptor

import java.io.IOException
import java.io.InputStream
import kotlin.math.min

class BoundedInput(private val input: InputStream, private val size: Long) : InputStream() {
    private var read: Long = 0

    override fun read(): Int =
        if (read >= size) -1 else {
            try {
                val value = input.read()
                if (value != -1) read++
                value
            } catch (e: Exception) {
                -1
            }
        }
}

private const val MAX_XML_BYTES = 10 * 1024 * 1024

fun readBytes(inputStream: InputStream): ByteArray {
    var output = ByteArray(minOf(MAX_XML_BYTES, 64 * 1024))
    val data = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    try {
        while (true) {
            val nRead = inputStream.read(data, 0, data.size)
            if (nRead < 0) break
            if (nRead == 0) continue
            if (nRead > MAX_XML_BYTES - total) throw IOException("XML file exceeds 10 MiB")
            val required = total + nRead
            if (required > output.size) {
                val previous = output
                val nextSize = minOf(MAX_XML_BYTES, maxOf(required, previous.size * 2))
                output = previous.copyOf(nextSize)
                previous.fill(0)
            }
            data.copyInto(output, total, 0, nRead)
            total += nRead
        }
        return output.copyOf(total)
    } finally {
        data.fill(0)
        output.fill(0)
    }
}
