package cleveres.tricky.cleverestech

import java.util.Collections
import java.util.LinkedHashMap

internal class KeyCache<K, V>(private val maxEntries: Int) {
    private val map = Collections.synchronizedMap(object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxEntries
        }
    })

    operator fun get(key: K): V? = map[key]
    operator fun set(key: K, value: V) {
        map[key] = value
    }
}
