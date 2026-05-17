package com.poemeditor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View

/**
 * Custom canvas-based renderer for the poem grid.
 * Replaces the GridLayout + EditText approach: all characters, cursor, and
 * selection highlights are drawn via Canvas.drawText / Canvas.drawRect.
 *
 * Zero object allocations inside onDraw — all Paint objects and scratch
 * variables are pre-allocated in the init block or as fields.
 */
class PoemCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Data state ────────────────────────────────────────────────────
    private var columnData: List<List<String>> = emptyList()
    private var numRowsVal  = 0
    private var maxColsVal  = 50
    private var cellSizePx  = 0

    // ── Text rendering ────────────────────────────────────────────────
    // All Paint objects are pre-allocated; only their fields change.
    private val textPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.GRAY
    }
    private val lineEndPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.argb(120, 160, 160, 160)
    }
    private val selPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cursorPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap   = Paint.Cap.ROUND
    }

    // Pre-computed once per updateData call; no recomputation in onDraw.
    private var baselineOffset = 0f   // distance from cell top to text baseline

    // ── Cursor blink ──────────────────────────────────────────────────
    var cursorIndex          = -1
    var hideLineEndMarkers   = false
    private var cursorVisible = false
    private val cursorHandler = Handler(Looper.getMainLooper())
    private val cursorBlinkRunnable = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            invalidate()
            cursorHandler.postDelayed(this, 500L)
        }
    }

    // ── Selection ─────────────────────────────────────────────────────
    var selectionFrom = -1
    var selectionTo   = -1

    // ── Composing preview overlay ─────────────────────────────────────
    // Maps cell index → char to render in gray (composing hints from IME).
    // Independent from columnData so the canonical data is never polluted.
    private var previewOverlay: Map<Int, String> = emptyMap()

    init {
        isFocusable          = false
        isFocusableInTouchMode = false
        selPaint.color = try {
            context.getColor(R.color.selection_highlight)
        } catch (_: Exception) {
            Color.argb(80, 33, 150, 243)
        }
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Full update: recalculates cell size from font metrics, requests layout,
     * and redraws. Call when font, size, gap, color, or typeface changes.
     */
    fun updateData(
        data:       List<List<String>>,
        numRows:    Int,
        maxColumns: Int,
        fontPx:     Float,
        gapPx:      Float,
        textColor:  Int,
        typeface:   Typeface
    ) {
        columnData  = data
        numRowsVal  = numRows
        maxColsVal  = maxColumns

        textPaint.textSize  = fontPx
        textPaint.typeface  = typeface
        textPaint.color     = textColor
        previewPaint.textSize = fontPx
        previewPaint.typeface = typeface
        lineEndPaint.textSize = fontPx * 0.65f
        lineEndPaint.typeface = typeface
        cursorPaint.color     = textColor

        // Mirror the rebuildGrid cell-size formula exactly.
        val charSize  = textPaint.measureText("測").toInt().coerceAtLeast(1)
        cellSizePx    = (charSize + gapPx.toInt()).coerceAtLeast(1)

        // Vertical centering: compute baseline from cell top once.
        val fm = textPaint.fontMetrics
        baselineOffset = (cellSizePx - (fm.descent - fm.ascent)) / 2f - fm.ascent

        requestLayout()
        invalidate()
    }

    /**
     * Lightweight content-only refresh. Does NOT recompute cell size.
     * Call after columnData mutations (typing, paste, undo, etc.).
     */
    fun refreshContent(data: List<List<String>>) {
        columnData = data
        invalidate()
    }

    fun setPreviewOverlay(overlay: Map<Int, String>) {
        previewOverlay = overlay
        invalidate()
    }

    fun setCursor(index: Int) {
        cursorIndex   = index
        cursorVisible = true
        invalidate()
    }

    fun setSelection(from: Int, to: Int) {
        selectionFrom = from
        selectionTo   = to
        invalidate()
    }

    fun clearSelectionHighlight() {
        selectionFrom = -1
        selectionTo   = -1
        invalidate()
    }

    fun startCursorBlink() {
        cursorHandler.removeCallbacks(cursorBlinkRunnable)
        cursorVisible = true
        cursorHandler.postDelayed(cursorBlinkRunnable, 500L)
        invalidate()
    }

    fun stopCursorBlink() {
        cursorHandler.removeCallbacks(cursorBlinkRunnable)
        cursorVisible = false
        invalidate()
    }

    fun updateTextColor(color: Int) {
        textPaint.color = color
        cursorPaint.color = color
        invalidate()
    }

    // ── Coordinate helpers ────────────────────────────────────────────

    /**
     * Converts a touch point relative to this View's top-left into a
     * logical cell index. Returns -1 if outside the valid area.
     */
    fun cellIndexAtTouch(relX: Float, relY: Float): Int {
        val cs = cellSizePx.takeIf { it > 0 } ?: return -1
        val nr = numRowsVal.takeIf { it > 0 } ?: return -1
        val totalW = maxColsVal * cs
        if (relX < 0 || relX >= totalW || relY < 0 || relY >= nr * cs) return -1
        val col = (totalW - relX.toInt() - 1) / cs    // RTL: col 0 is rightmost
        val row = relY.toInt() / cs
        return col.coerceIn(0, maxColsVal - 1) * nr + row.coerceIn(0, nr - 1)
    }

    /**
     * Returns the bounding Rect of a cell (relative to this View), or null
     * if dimensions are not yet initialised.
     */
    fun cellRect(index: Int): Rect? {
        val cs = cellSizePx.takeIf { it > 0 } ?: return null
        val nr = numRowsVal.takeIf { it > 0 } ?: return null
        val col = index / nr; val row = index % nr
        if (col >= maxColsVal) return null
        val totalW = maxColsVal * cs
        val left   = totalW - (col + 1) * cs
        val top    = row * cs
        return Rect(left, top, left + cs, top + cs)
    }

    // ── View overrides ────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cs = cellSizePx.coerceAtLeast(1)
        setMeasuredDimension(maxColsVal * cs, numRowsVal.coerceAtLeast(1) * cs)
    }

    override fun onDraw(canvas: Canvas) {
        val cs = cellSizePx.takeIf { it > 0 } ?: return
        val nr = numRowsVal.takeIf { it > 0 } ?: return
        val totalW = maxColsVal * cs

        val selFrom = selectionFrom; val selTo = selectionTo
        val hasSelection = selFrom >= 0 && selTo >= 0

        var cellL: Float

        for (col in 0 until maxColsVal) {
            val colData = columnData.getOrNull(col)
            // RTL: column 0 is at the far right.
            cellL = (totalW - (col + 1) * cs).toFloat()
            val textX = cellL + cs / 2f   // center X for Paint.Align.CENTER

            for (row in 0 until nr) {
                val index   = col * nr + row
                val cellTop = (row * cs).toFloat()

                // Selection background.
                if (hasSelection && index in selFrom..selTo) {
                    canvas.drawRect(cellL, cellTop, cellL + cs, cellTop + cs, selPaint)
                }

                // Character to draw: preview overlay takes priority over columnData.
                val previewCh = previewOverlay[index]
                val dataCh    = colData?.getOrNull(row) ?: ""

                when {
                    previewCh != null -> {
                        canvas.drawText(previewCh, textX, cellTop + baselineOffset, previewPaint)
                    }
                    dataCh == GridLogicHelper.LINE_END_MARKER && !hideLineEndMarkers -> {
                        // Vertical centre for the smaller line-end glyph.
                        val fm = lineEndPaint.fontMetrics
                        val lbo = (cs - (fm.descent - fm.ascent)) / 2f - fm.ascent
                        canvas.drawText(dataCh, textX, cellTop + lbo, lineEndPaint)
                    }
                    dataCh.isNotEmpty() && dataCh != GridLogicHelper.FRONTIER_MARKER && dataCh != GridLogicHelper.LINE_END_MARKER -> {
                        canvas.drawText(dataCh, textX, cellTop + baselineOffset, textPaint)
                    }
                }
            }

            // Cursor — drawn once when we reach the cursor's column.
            if (cursorVisible && cursorIndex >= 0 && nr > 0) {
                val curCol = cursorIndex / nr
                val curRow = cursorIndex % nr
                if (curCol == col) {
                    val cellTop  = (curRow * cs).toFloat()
                    val margin   = cs * 0.08f
                    val lineY    = cellTop + 3f
                    canvas.drawLine(
                        cellL + margin, lineY,
                        cellL + cs - margin, lineY,
                        cursorPaint
                    )
                }
            }
        }
    }

}
