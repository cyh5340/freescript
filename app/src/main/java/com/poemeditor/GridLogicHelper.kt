package com.poemeditor

object GridLogicHelper {

    const val FRONTIER_MARKER = "​"   // zero-width space; writing-frontier cell in SEQUENTIAL
    const val LINE_END_MARKER = "↵"   // ↵; paragraph-end marker

    fun setColumnChar(columnData: MutableList<MutableList<String>>, col: Int, row: Int, ch: String) {
        while (columnData.size <= col) columnData.add(mutableListOf())
        while (columnData[col].size <= row) columnData[col].add("")
        columnData[col][row] = ch
    }

    fun reflowColumnData(
        columnData: MutableList<MutableList<String>>,
        columnBreaks: MutableSet<Int>,
        newNumRows: Int
    ) {
        if (newNumRows <= 0) return
        val maxCol = maxOf(columnData.size, columnBreaks.maxOrNull()?.plus(1) ?: 0)
        val stream = mutableListOf<String?>()
        for (col in 0 until maxCol) {
            if (col > 0 && columnBreaks.contains(col)) stream.add(null)
            val colData = columnData.getOrNull(col) ?: continue
            val lastNonEmpty = colData.indexOfLast { it.isNotEmpty() }
            if (lastNonEmpty < 0) continue
            for (row in 0..lastNonEmpty) stream.add(colData[row])
        }
        columnData.clear(); columnBreaks.clear()
        var col = 0; var row = 0
        for (item in stream) {
            if (item == null) {
                col++; row = 0
                columnBreaks.add(col)
            } else {
                if (row >= newNumRows) { col++; row = 0 }
                setColumnChar(columnData, col, row, item); row++
            }
        }
    }

    fun insertCharsAt(
        columnData: MutableList<MutableList<String>>,
        columnBreaks: MutableSet<Int>,
        insertCol: Int, insertRow: Int, newChars: String,
        numRows: Int, maxColumns: Int
    ) {
        if (newChars.isEmpty()) return
        val flat = mutableListOf<String>()
        var c = insertCol; var rStart = insertRow
        while (c < maxColumns) {
            if (c > insertCol && columnBreaks.contains(c)) break
            val colData = columnData.getOrNull(c) ?: break
            for (r in rStart until colData.size) flat.add(colData[r])
            c++; rStart = 0
        }
        while (flat.isNotEmpty() && flat.last().isBlank()) flat.removeLast()

        c = insertCol; rStart = insertRow
        while (c < maxColumns) {
            if (c > insertCol && columnBreaks.contains(c)) break
            val colData = columnData.getOrNull(c) ?: break
            for (r in rStart until colData.size) colData[r] = ""
            c++; rStart = 0
        }

        var wc = insertCol; var wr = insertRow
        for (ch in newChars) {
            if (wc >= maxColumns) break
            if (wc > insertCol && columnBreaks.contains(wc)) break
            setColumnChar(columnData, wc, wr, ch.toString())
            wr++; if (wr >= numRows) { wr = 0; wc++ }
        }
        for (ch in flat) {
            if (wc >= maxColumns) break
            if (wc > insertCol && columnBreaks.contains(wc)) break
            setColumnChar(columnData, wc, wr, ch)
            wr++; if (wr >= numRows) { wr = 0; wc++ }
        }
    }

    fun placeFrontierMarker(
        columnData: MutableList<MutableList<String>>,
        columnBreaks: Set<Int>,
        numRows: Int,
        numColumns: Int,
        isSequential: Boolean
    ) {
        if (!isSequential || numRows <= 0) return
        val paraStarts = mutableListOf(0).also { it.addAll(columnBreaks.sorted()) }
        for (i in paraStarts.indices) {
            val paraStart = paraStarts[i]
            val paraEnd = if (i + 1 < paraStarts.size) paraStarts[i + 1] - 1 else numColumns - 1
            val isLastPara = (i + 1 >= paraStarts.size)
            val expectedMarker = if (!isLastPara) LINE_END_MARKER else FRONTIER_MARKER

            var lastRealIdx = -1
            for (col in paraStart..paraEnd) {
                val colData = columnData.getOrNull(col) ?: continue
                for (row in colData.indices) {
                    val ch = colData[row]
                    if (ch.isNotEmpty() && ch != FRONTIER_MARKER && ch != LINE_END_MARKER)
                        lastRealIdx = col * numRows + row
                }
            }

            if (lastRealIdx < 0) {
                if (!isLastPara) {
                    val cur = columnData.getOrNull(paraStart)?.getOrNull(0) ?: ""
                    if (cur != LINE_END_MARKER) setColumnChar(columnData, paraStart, 0, LINE_END_MARKER)
                }
                continue
            }

            val frontierIdx = lastRealIdx + 1
            val markerIdx = if (isLastPara) frontierIdx
                           else ((lastRealIdx / numRows) + 1) * numRows
            val markerCol = markerIdx / numRows
            val markerRow = markerIdx % numRows
            if (markerCol > paraEnd) continue

            for (col in paraStart..paraEnd) {
                val colData = columnData.getOrNull(col) ?: continue
                for (row in colData.indices) {
                    if (colData[row] == FRONTIER_MARKER || colData[row] == LINE_END_MARKER) {
                        if (col * numRows + row < markerIdx)
                            setColumnChar(columnData, col, row, " ")
                    }
                }
            }

            val currentAtMarker = columnData.getOrNull(markerCol)?.getOrNull(markerRow) ?: ""
            if ((currentAtMarker.isEmpty() || currentAtMarker == FRONTIER_MARKER || currentAtMarker == LINE_END_MARKER)
                && currentAtMarker != expectedMarker) {
                setColumnChar(columnData, markerCol, markerRow, expectedMarker)
            }
        }
    }

    /**
     * In SCATTER mode, shrinking the column height leaves islands stranded beyond the
     * new boundary. This function collects those out-of-range non-blank characters per
     * column and repacks them at the bottom of the valid area (rows 0..newNumRows-1),
     * preserving their original top-to-bottom order. Characters that can't fit are dropped.
     */
    fun squeezeScatterOutOfRange(
        columnData: MutableList<MutableList<String>>,
        newNumRows: Int
    ) {
        if (newNumRows <= 0) return
        for (col in columnData) {
            if (col.size <= newNumRows) continue

            // Collect non-blank, non-marker chars from the overflow rows, top to bottom.
            val overflow = (newNumRows until col.size).mapNotNull { r ->
                val ch = col[r]
                if (ch.isNotBlank() && ch != FRONTIER_MARKER && ch != LINE_END_MARKER) ch else null
            }

            // Trim column to the new valid height.
            while (col.size > newNumRows) col.removeAt(col.lastIndex)
            while (col.size < newNumRows) col.add("")

            if (overflow.isEmpty()) continue

            // Free slots in the valid area, from bottom (newNumRows-1) upward.
            val freeSlots = (newNumRows - 1 downTo 0).filter { r -> col[r].isBlank() }
            val numToPlace = minOf(overflow.size, freeSlots.size)
            if (numToPlace == 0) continue

            // Place the first numToPlace overflow chars at the bottom free slots,
            // preserving their original top-to-bottom order.
            // freeSlots[0] is the bottom-most free slot → gets the last char of the group.
            val chars = overflow.take(numToPlace).reversed()
            for (i in chars.indices) col[freeSlots[i]] = chars[i]
        }
    }

    fun fillGapsForSequentialMode(
        columnData: MutableList<MutableList<String>>,
        columnBreaks: MutableSet<Int>,
        numRows: Int,
        numColumns: Int
    ) {
        if (numRows <= 0) return
        val paraStarts = mutableListOf(0).also { it.addAll(columnBreaks.sorted()) }
        for (i in paraStarts.indices) {
            val paraStart = paraStarts[i]
            val paraEnd = if (i + 1 < paraStarts.size) paraStarts[i + 1] - 1 else numColumns - 1

            var firstContentCol = -1
            var lastContentCol = -1
            for (col in paraStart..paraEnd) {
                val colData = columnData.getOrNull(col) ?: continue
                if (colData.any { it.isNotEmpty() }) {
                    if (firstContentCol < 0) firstContentCol = col
                    lastContentCol = col
                }
            }
            if (firstContentCol < 0) continue

            for (col in paraStart..paraEnd) {
                val colData = columnData.getOrNull(col)
                val hasContent = colData != null && colData.any { it.isNotEmpty() }
                if (hasContent) {
                    var lastOccRow = -1
                    for (row in colData!!.indices) { if (colData[row].isNotEmpty()) lastOccRow = row }
                    for (row in 0..lastOccRow) {
                        if ((columnData.getOrNull(col)?.getOrNull(row) ?: "").isEmpty())
                            setColumnChar(columnData, col, row, " ")
                    }
                    if (lastOccRow + 1 < numRows &&
                        (columnData.getOrNull(col)?.getOrNull(lastOccRow + 1) ?: "").isEmpty())
                        setColumnChar(columnData, col, lastOccRow + 1, FRONTIER_MARKER)
                } else if (col in (firstContentCol + 1)..(lastContentCol - 1)) {
                    if ((columnData.getOrNull(col)?.getOrNull(0) ?: "").isEmpty())
                        setColumnChar(columnData, col, 0, FRONTIER_MARKER)
                }
            }
        }
    }

    fun findSequentialTapTarget(
        columnData: List<List<String>>,
        columnBreaks: Set<Int>,
        numRows: Int,
        tappedIndex: Int
    ): Int {
        if (numRows <= 0) return -1
        val tappedCol = tappedIndex / numRows
        val sortedBreaks = columnBreaks.sorted()
        val paraStart = sortedBreaks.lastOrNull { it <= tappedCol } ?: 0

        val tappedColData = columnData.getOrNull(tappedCol)
        if (tappedColData != null) {
            for (row in tappedColData.indices) {
                if (tappedColData[row] == FRONTIER_MARKER || tappedColData[row] == LINE_END_MARKER)
                    return tappedCol * numRows + row
            }
        }

        for (col in (tappedCol - 1) downTo paraStart) {
            val colData = columnData.getOrNull(col) ?: continue
            for (row in colData.indices) {
                if (colData[row] == FRONTIER_MARKER || colData[row] == LINE_END_MARKER)
                    return col * numRows + row
            }
        }

        val paragraphHasContent = (paraStart..tappedCol).any { col ->
            columnData.getOrNull(col)?.any { it.isNotEmpty() } == true
        }
        if (!paragraphHasContent) return paraStart * numRows

        return -1
    }
}
