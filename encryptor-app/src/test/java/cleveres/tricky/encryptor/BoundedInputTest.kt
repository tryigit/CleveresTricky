package cleveres.tricky.encryptor

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class BoundedInputTest {

    @Test
    fun `read returns minus one on exception`() {
        val throwingStream = object : InputStream() {
            override fun read(): Int {
                throw IOException("Simulated read exception")
            }
        }
        val bounded = BoundedInput(throwingStream, 100)
        assertEquals(-1, bounded.read())
    }

    @Test
    fun `read respects size limit`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        val bounded = BoundedInput(input, 3)
        assertEquals(1, bounded.read())
        assertEquals(2, bounded.read())
        assertEquals(3, bounded.read())
        assertEquals(-1, bounded.read())
    }

    @Test
    fun `read handles underlying stream end`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2))
        val bounded = BoundedInput(input, 5)
        assertEquals(1, bounded.read())
        assertEquals(2, bounded.read())
        assertEquals(-1, bounded.read())
        assertEquals(-1, bounded.read())
    }
}
