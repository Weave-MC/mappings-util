package com.grappenmaker.mappings

import org.objectweb.asm.ClassReader

internal inline fun walkInheritance(
    loader: (name: String) -> ByteArray?,
    start: String,
    isEnd: (curr: String) -> Boolean,
): String? {
    val queue = ArrayDeque<String>()
    val seen = hashSetOf<String>()
    queue.addLast(start)

    while (queue.isNotEmpty()) {
        val curr = queue.removeLast()
        if (isEnd(curr)) return curr

        val bytes = loader(curr) ?: continue
        val reader = ClassReader(bytes)

        reader.superName?.let { if (seen.add(it)) queue.addLast(it) }
        queue += reader.interfaces.filter { seen.add(it) }
    }

    return null
}