package cleveres.tricky.encryptor

import org.junit.Test
import kotlin.system.measureNanoTime

class BenchmarkTest {
    @Test
    fun testBenchmark() {
        data class VaultItem(val file: java.io.File, val size: Long)
        val files = (1..10000).map { VaultItem(java.io.File("file_$it.txt"), 100L) }
        val selectedNames = (1..5000).map { "file_$it.txt" }.toSet()

        var dummy1 = 0
        var dummy2 = 0
        // Warmup
        for (i in 1..10) {
            dummy1 += files.filter { it.file.name in selectedNames }.map { it.file }.size
            dummy2 += files.mapNotNull { if (it.file.name in selectedNames) it.file else null }.size
        }

        var resultOld = 0
        val timeOld = measureNanoTime {
            for (i in 1..100) {
                resultOld += files.filter { it.file.name in selectedNames }.map { it.file }.size
            }
        }

        var resultNew = 0
        val timeNew = measureNanoTime {
            for (i in 1..100) {
                resultNew += files.mapNotNull { if (it.file.name in selectedNames) it.file else null }.size
            }
        }

        require(resultOld == resultNew) { "Results mismatch: $resultOld vs $resultNew" }
        println("Dummy values: $dummy1, $dummy2")
        println("Baseline: ${timeOld / 1_000_000} ms")
        println("Optimized: ${timeNew / 1_000_000} ms")
    }
}
