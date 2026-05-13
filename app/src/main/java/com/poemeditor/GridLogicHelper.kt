package com.poemeditor

object GridLogicHelper {

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
        var blankBudget = newChars.length
        val shifted = ArrayList<String>(flat.size)
        for (ch in flat) {
            if (blankBudget > 0 && ch.isBlank()) {
                blankBudget--
                continue
            }
            shifted.add(ch)
        }

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
        for (ch in shifted) {
            if (wc >= maxColumns) break
            if (wc > insertCol && columnBreaks.contains(wc)) break
            setColumnChar(columnData, wc, wr, ch)
            wr++; if (wr >= numRows) { wr = 0; wc++ }
        }
    }
}
