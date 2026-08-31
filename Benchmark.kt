import kotlin.system.measureNanoTime

fun main() {
    data class VaultItem(val file: java.io.File, val size: Long)
    val files = (1..100000).map { VaultItem(java.io.File("file_$it.txt"), 100L) }
    val selectedNames = (1..50000).map { "file_$it.txt" }.toSet()

    // Warmup
    for (i in 1..10) {
        files.filter { it.file.name in selectedNames }.map { it.file }
        files.mapNotNull { if (it.file.name in selectedNames) it.file else null }
    }

    val timeOld = measureNanoTime {
        for (i in 1..100) {
            files.filter { it.file.name in selectedNames }.map { it.file }
        }
    }

    val timeNew = measureNanoTime {
        for (i in 1..100) {
            files.mapNotNull { if (it.file.name in selectedNames) it.file else null }
        }
    }

    println("Baseline: ${timeOld / 1_000_000} ms")
    println("Optimized: ${timeNew / 1_000_000} ms")
}
