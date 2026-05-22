package com.freescript

/**
 * Encapsulates Latin word accumulation for HORIZONTAL input mode.
 * Tracks which cell is being built and the characters typed so far.
 */
class LatinWordBuffer {

    private val buf = StringBuilder()
    private var cell = -1

    val isEmpty  get() = buf.isEmpty()
    val isActive get() = buf.isNotEmpty()

    fun resetIfCellChanged(index: Int) {
        if (index != cell) clear()
    }

    /** Append one Latin char to the buffer; returns the new cell content. */
    fun append(c: Char, cellIndex: Int): String {
        buf.append(c)
        cell = cellIndex
        return buf.toString()
    }

    /** Remove the last char; returns the new cell content, or null if buffer is now empty. */
    fun backspace(): String? {
        if (buf.isEmpty()) return null
        buf.deleteCharAt(buf.length - 1)
        return if (buf.isEmpty()) null else buf.toString()
    }

    fun clear() { buf.clear(); cell = -1 }
}
