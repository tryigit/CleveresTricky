package cleveres.tricky.cleverestech.util

import java.util.Arrays

class PackageTrie<T> {
    private class Node<T> {
        var keys: CharArray = CharArray(0)
        var children: Array<Node<T>?> = emptyArray()
        var value: T? = null
        var hasValue = false
        var isWildcard = false
        var wildcardValue: T? = null

        fun getChild(char: Char): Node<T>? {
            val k = keys
            val len = k.size
            if (len <= 4) {
                for (i in 0 until len) {
                    if (k[i] == char) return children[i]
                }
                return null
            }
            val idx = Arrays.binarySearch(k, char)
            return if (idx >= 0) children[idx] else null
        }

        fun addChild(char: Char): Node<T> {
            val k = keys
            val idx = Arrays.binarySearch(k, char)
            if (idx >= 0) {
                return children[idx]!!
            }

            val insertAt = -(idx + 1)
            val newSize = k.size + 1
            val newKeys = CharArray(newSize)
            val newChildren = arrayOfNulls<Node<T>>(newSize)

            if (insertAt > 0) {
                System.arraycopy(k, 0, newKeys, 0, insertAt)
                System.arraycopy(children, 0, newChildren, 0, insertAt)
            }

            newKeys[insertAt] = char
            val newNode = Node<T>()
            newChildren[insertAt] = newNode

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
        var current = root
        val isWildcardRule = rule.endsWith("*")
        val effectiveRule = if (isWildcardRule) rule.dropLast(1) else rule

        for (char in effectiveRule) {
            current = current.addChild(char)
        }

        if (isWildcardRule) {
            if (!current.isWildcard) size++
            current.isWildcard = true
            current.wildcardValue = value
        } else {
            if (!current.hasValue) size++
            current.hasValue = true
            current.value = value
        }
    }

    fun clear() {
        root.keys = CharArray(0)
        root.children = emptyArray()
        root.value = null
        root.hasValue = false
        root.isWildcard = false
        root.wildcardValue = null
        size = 0
    }

    fun get(pkgName: String): T? {
        var current = root
        var bestMatch: T? = if (current.isWildcard) current.wildcardValue else null

        for (char in pkgName) {
            val next = current.getChild(char) ?: return bestMatch
            current = next
            if (current.isWildcard) {
                bestMatch = current.wildcardValue
            }
        }
        return if (current.hasValue) current.value else bestMatch
    }

    fun matches(pkgName: String): Boolean = get(pkgName) != null

    fun isEmpty() = size == 0
}
