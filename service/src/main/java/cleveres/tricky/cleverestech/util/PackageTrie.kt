package cleveres.tricky.cleverestech.util

import java.util.Arrays

class PackageTrie<T> {
    private class Node<T> {
        // Optimization: Use parallel arrays instead of HashMap to reduce memory overhead and avoid boxing.
        // For trie nodes, the number of children is usually small (often 1), making linear scan faster than hashing.
        // UPDATED: Keys are kept sorted to allow binary search, improving lookup time for nodes with many children.
        var keys: CharArray = CharArray(0)
        var children: Array<Node<T>?> = emptyArray()

        var value: T? = null
        var isWildcard = false
        var wildcardValue: T? = null

        fun getChild(char: Char): Node<T>? {
            val k = keys
            val len = k.size
            // Optimization: Linear scan for small arrays (<= 4 elements) is faster than binary search overhead.
            // Most trie nodes for package names are linear chains (1 child) or small branches.
            if (len <= 4) {
                for (i in 0 until len) {
                    if (k[i] == char) return children[i]
                }
                return null
            }
            // Optimized binary search for O(log N) lookup
            val idx = Arrays.binarySearch(k, char)
            return if (idx >= 0) children[idx] else null
        }

        fun addChild(char: Char): Node<T> {
            val k = keys
            // Check if child already exists using binary search
            val idx = Arrays.binarySearch(k, char)
            if (idx >= 0) {
                return children[idx]!!
            }

            // Not found, insert at sorted position
            val insertAt = -(idx + 1)

            val newSize = k.size + 1
            val newKeys = CharArray(newSize)

            // Generic array creation workaround
            val newChildren = arrayOfNulls<Node<T>>(newSize)

            // Copy before insertion point
            if (insertAt > 0) {
                System.arraycopy(k, 0, newKeys, 0, insertAt)
                System.arraycopy(children, 0, newChildren, 0, insertAt)
            }

            // Insert new child
            newKeys[insertAt] = char
            val newNode = Node<T>()
            newChildren[insertAt] = newNode

            // Copy after insertion point
            if (insertAt < k.size) {
                System.arraycopy(k, insertAt, newKeys, insertAt + 1, k.size - insertAt)
                System.arraycopy(children, insertAt, newChildren, insertAt + 1, children.size - insertAt)
            }

            keys = newKeys
            children = newChildren

            return newNode
        }
    }

    private val root = Node<T>()
    var size = 0
        private set

    fun add(
        rule: String,
        value: T,
    ) {
        size++
        var current = root
        var effectiveRule = rule
        var isWildcardRule = false
        if (rule.endsWith("*")) {
            effectiveRule = rule.dropLast(1)
            isWildcardRule = true
        }

        for (char in effectiveRule) {
            current = current.addChild(char)
        }

        if (isWildcardRule) {
            current.isWildcard = true
            current.wildcardValue = value
        } else {
            current.value = value
        }
    }

    fun clear() {
        // Clear root children to release memory
        root.keys = CharArray(0)
        root.children = emptyArray()
        root.value = null
        root.isWildcard = false
        root.wildcardValue = null
        size = 0
    }

    fun get(pkgName: String): T? {
        var current = root
        // Keep track of the most specific wildcard match found so far
        var bestMatch: T? = if (current.isWildcard) current.wildcardValue else null

        for (i in pkgName.indices) {
            val char = pkgName[i]
            val next = current.getChild(char) ?: return bestMatch
            current = next
            if (current.isWildcard) {
                bestMatch = current.wildcardValue
            }
        }
        // If we reached the end of the string, check for exact match value first, then fallback to wildcard
        return current.value ?: bestMatch
    }

    fun matches(pkgName: String): Boolean {
        return get(pkgName) != null
    }

    fun isEmpty() = size == 0
}
