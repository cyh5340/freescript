package com.poemeditor

import android.content.Context
import android.util.TypedValue
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView

class GridEditorController(
    private val context: Context,
    private val maxColumns: Int,
    private val builtColumns: MutableSet<Int>,
    private val editTextFields: MutableList<EditText>,
    private val columnData: List<List<String>>,
    private val withRestoring: (block: () -> Unit) -> Unit,
    private val getNumRows: () -> Int,
    private val getCurrentCellSize: () -> Int,
    private val getFontSizeSp: () -> Float,
    private val getGridTextColor: () -> Int,
    private val getMainScrollWidth: () -> Int,
    private val getHScroll: () -> HorizontalScrollView?,
    private val makeCell: (row: Int, col: Int, index: Int, cellSize: Int, fontPx: Float) -> EditText
) {
    // Builds exactly the visible columns + all data columns. No async follow-on.
    // New columns are created on demand: typing triggers ensureBufferColumn,
    // scrolling triggers the onScrollChangeListener in rebuildGrid.
    fun buildInitialColumns() {
        val grid = (getHScroll()?.getChildAt(0) as? GridLayout) ?: return
        val fontPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            getFontSizeSp(),
            context.resources.displayMetrics
        )
        val cellSize = getCurrentCellSize()
        val visibleCols = (getMainScrollWidth() / cellSize.coerceAtLeast(1)).coerceAtLeast(1) + 1
        val lastDataCol = columnData.indexOfLast { it.any { ch -> ch.isNotEmpty() } }.coerceAtLeast(0)
        val buildEnd = maxOf(visibleCols - 1, lastDataCol).coerceAtMost(maxColumns - 1)
        for (col in 0..buildEnd) buildColumn(col, grid, fontPx, cellSize)
    }

    // Synchronously builds all columns from the next unbuilt one up to col.
    // Called before any access to a column not yet in editTextFields.
    fun ensureColumnBuilt(col: Int) {
        if (col < 0 || col >= maxColumns || col in builtColumns) return
        val grid = (getHScroll()?.getChildAt(0) as? GridLayout) ?: return
        val fontPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            getFontSizeSp(),
            context.resources.displayMetrics
        )
        val nextUnbuilt = (builtColumns.maxOrNull()?.plus(1)) ?: 0
        for (c in nextUnbuilt..col) buildColumn(c, grid, fontPx, getCurrentCellSize())
    }

    // Builds one column's EditText cells, fills data, appends to editTextFields/grid.
    // Must be called in col order 0,1,2,… because editTextFields is a contiguous list.
    private fun buildColumn(col: Int, grid: GridLayout, fontPx: Float, cellSize: Int) {
        val numRows = getNumRows()
        if (col in builtColumns || col >= maxColumns || numRows <= 0) return
        val colData = columnData.getOrNull(col)
        withRestoring {
            repeat(numRows) { row ->
                val index = col * numRows + row
                val et = makeCell(row, col, index, cellSize, fontPx)
                val ch = colData?.getOrNull(row) ?: ""
                if (ch.isNotEmpty()) et.setText(ch)
                et.setTextColor(getGridTextColor())
                editTextFields.add(et)
                grid.addView(et)
            }
        }
        builtColumns.add(col)
    }
}
