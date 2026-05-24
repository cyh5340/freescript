package com.freescript

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.DashPathEffect
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

enum class FreestyleCornerAction { MOVE, DELETE, TOGGLE_FLOW, RESIZE }

class PoemCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Data state ────────────────────────────────────────────────
    private var columnData: List<List<String>> = emptyList()
    private var numRowsVal  = 0
    private var maxColsVal  = 50
    var cellSizePx  = 0
        private set

    // ── Text rendering ────────────────────────────────────────────
    private val textPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.GRAY
    }
    // LEFT-aligned version of previewPaint for horizontal mode visual-X rendering
    private val previewPaintLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        color = Color.GRAY
    }
    private val lineEndPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.argb(120, 160, 160, 160)
    }
    private val wordPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val selPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cursorPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap   = Paint.Cap.ROUND
    }

    private var baselineOffset = 0f
    private var lineEndBaselineOffset = 0f
    private val moveArrowPath = Path()

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.argb(80, 140, 140, 140)
    }
    private val hintBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(80, 140, 140, 140)
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    // ── Cursor blink ──────────────────────────────────────────────
    var cursorIndex          = -1
    var hideLineEndMarkers   = false
    private var cursorVisible = false
    private val cursorHandler = Handler(Looper.getMainLooper())
    private val cursorBlinkRunnable = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            invalidateCursorRegion(cursorIndex)
            cursorHandler.postDelayed(this, 500L)
        }
    }

    // ── Selection ─────────────────────────────────────────────────
    var selectionFrom = -1
    var selectionTo   = -1

    // ── Composing preview overlay ─────────────────────────────────
    private var previewOverlay: Map<Int, String> = emptyMap()

    // ── Horizontal mode ───────────────────────────────────────────
    // col = line index (top→bottom), row = char index within line (left→right)
    // Canvas: width = numRows * cs, height = numCols * cs
    var isHorizontalMode = false
        set(value) { field = value; requestLayout(); invalidate() }

    // True when MainActivity is overlaying liveHorizontalEditor on the focused cell.
    // The cursor blinker is suppressed whenever this is set (the EditText draws its own
    // cursor); the focused cell's CONTENT is only skipped when the editor has text
    // ([liveEditorCoversCell] below). When the editor is empty we want the cell content
    // to remain visible — otherwise focusing an existing word makes it vanish.
    var liveEditorOverlayActive = false
        set(value) { if (field != value) { field = value; invalidate() } }

    // True only when the live editor is positioned over the focused cell AND has typed text
    // covering it. The renderer skips drawing the cell's data only when this is true.
    var liveEditorCoversCell = false
        set(value) { if (field != value) { field = value; invalidate() } }

    // ── Freestyle mode ────────────────────────────────────────────
    // colCount = X dimension (width / cs), rowCount = Y dimension (height / cs)
    // regardless of the box's isHorizontal direction flag.
    var isFreestyleMode = false
        set(value) { field = value; invalidate() }
    var freestyleMinW = 0
    var freestyleMinH = 0
    private var freestyleBoxList: List<TextBoxInstance> = emptyList()
    private var activeFreestyleBoxId: String? = null
    var activeBoxOffsetX = 0f
        private set
    var activeBoxOffsetY = 0f
        private set
    private val boxCellSizeCache = mutableMapOf<String, Int>()

    private val activeBox get() = freestyleBoxList.find { it.id == activeFreestyleBoxId }

    private val boxBgFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val boxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#CCCCCC")
    }
    private val inactiveBoxTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.DKGRAY
    }
    private val cornerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val cornerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#2196F3")
    }
    private val cornerIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#2196F3")
    }

    init {
        // ghostInput in MainActivity is the real IME bridge; this view stays non-focusable
        // so the system never routes key events here instead of to ghostInput.
        isFocusable          = false
        isFocusableInTouchMode = false
        selPaint.color = try {
            context.getColor(R.color.selection_highlight)
        } catch (_: Exception) {
            Color.argb(80, 33, 150, 243)
        }
    }

    // True when the active input surface is horizontal (LTR) text — either the full
    // HORIZONTAL canvas or a FREESTYLE box with isHorizontal = true. Mirrors the
    // liveEditorActive property in MainActivity using this view's own state flags.
    private val isHorizontalInput: Boolean
        get() = isHorizontalMode || (isFreestyleMode && activeBox?.isHorizontal == true)

    /**
     * Describes to the IME how to treat this view if it ever becomes the focused target.
     *
     * fullEditor = isHorizontalInput:
     *   true  → IME treats this as a real editor; English keyboards enable their prediction
     *           dictionary and suggestion strip (required for AUTO_CORRECT to work).
     *   false → IME treats this as a dummy/display-only view; English keyboards suppress
     *           suggestions even when AUTO_CORRECT is set. CJK keyboards ignore this flag
     *           and force the candidate bar open regardless.
     *
     * ghostInput (EditText) is the primary IME bridge and holds focus in practice;
     * this method serves as the correct handshake if the view ever receives focus directly.
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        val horizontal = isHorizontalInput
        outAttrs.inputType = if (horizontal) {
            EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT
        } else {
            EditorInfo.TYPE_CLASS_TEXT or
                    EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
                    EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd   = 0
        return BaseInputConnection(this, horizontal)
    }

    // ── Public API ────────────────────────────────────────────────

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
        wordPaint.textSize  = fontPx
        wordPaint.typeface  = typeface
        wordPaint.color     = textColor
        previewPaint.textSize = fontPx
        previewPaint.typeface = typeface
        previewPaintLeft.textSize = fontPx
        previewPaintLeft.typeface = typeface
        lineEndPaint.textSize = fontPx * 0.65f
        lineEndPaint.typeface = typeface
        cursorPaint.color     = textColor
        hintPaint.textSize    = fontPx
        hintPaint.typeface    = typeface

        val charSize  = textPaint.measureText("測").toInt().coerceAtLeast(1)
        cellSizePx    = (charSize + gapPx.toInt()).coerceAtLeast(1)

        val fm = textPaint.fontMetrics
        baselineOffset = (cellSizePx - (fm.descent - fm.ascent)) / 2f - fm.ascent
        val lineEndFm = lineEndPaint.fontMetrics
        lineEndBaselineOffset = (cellSizePx - (lineEndFm.descent - lineEndFm.ascent)) / 2f - lineEndFm.ascent

        requestLayout()
        invalidate()
    }

    fun refreshContent(data: List<List<String>>) {
        columnData = data
        invalidate()
    }

    fun setMaxColumns(n: Int) {
        if (maxColsVal == n) return
        maxColsVal = n
        requestLayout()
        invalidate()
    }

    fun setPreviewOverlay(overlay: Map<Int, String>) {
        previewOverlay = overlay
        invalidate()
    }

    fun setCursor(index: Int) {
        val oldCursorIndex = cursorIndex
        cursorIndex = index
        cursorVisible = true
        invalidateCursorTransition(oldCursorIndex, index)
    }

    fun setSelection(from: Int, to: Int) {
        val oldFrom = selectionFrom
        val oldTo = selectionTo
        selectionFrom = from
        selectionTo = to
        invalidateSelectionTransition(oldFrom, oldTo, from, to)
    }

    fun clearSelectionHighlight() {
        val oldFrom = selectionFrom
        val oldTo = selectionTo
        selectionFrom = -1
        selectionTo = -1
        invalidateSelectionTransition(oldFrom, oldTo, -1, -1)
    }

    fun startCursorBlink() {
        cursorHandler.removeCallbacks(cursorBlinkRunnable)
        cursorVisible = true
        cursorHandler.postDelayed(cursorBlinkRunnable, 500L)
        invalidateCursorRegion(cursorIndex)
    }

    fun stopCursorBlink() {
        cursorHandler.removeCallbacks(cursorBlinkRunnable)
        cursorVisible = false
        invalidateCursorRegion(cursorIndex)
    }

    fun updateTextColor(color: Int) {
        textPaint.color = color
        wordPaint.color = color   // horizontal-mode Latin word cells use wordPaint
        cursorPaint.color = color
        invalidate()
    }

    fun updateFreestyleBoxes(
        boxes: List<TextBoxInstance>,
        activeId: String?,
        activeOffX: Float,
        activeOffY: Float
    ) {
        freestyleBoxList = boxes
        activeFreestyleBoxId = activeId
        activeBoxOffsetX = activeOffX
        activeBoxOffsetY = activeOffY
        boxCellSizeCache.clear()
        val density = context.resources.displayMetrics.density
        for (box in boxes) {
            if (box.id == activeId) {
                boxCellSizeCache[box.id] = cellSizePx
            } else {
                val fontPx = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP, box.fontSizeSp,
                    context.resources.displayMetrics)
                inactiveBoxTextPaint.textSize = fontPx
                inactiveBoxTextPaint.typeface = box.typeface
                val charSize = inactiveBoxTextPaint.measureText("測").toInt().coerceAtLeast(1)
                boxCellSizeCache[box.id] = (charSize + (box.wordGapDp * density).toInt()).coerceAtLeast(1)
            }
        }
        requestLayout()
        invalidate()
    }

    /** Returns the box at the canvas touch point, or null. colCount=X, rowCount=Y always. */
    fun freestyleBoxAtTouch(x: Float, y: Float): TextBoxInstance? {
        if (!isFreestyleMode) return null
        for (box in freestyleBoxList.reversed()) {
            val cs = boxCellSizeCache[box.id] ?: cellSizePx
            if (x >= box.leftPx && x < box.leftPx + box.colCount * cs &&
                y >= box.topPx  && y < box.topPx  + box.rowCount * cs) {
                return box
            }
        }
        return null
    }

    /**
     * Returns which corner action icon the touch lands on for the active box, or null.
     * Top-left=MOVE  Top-right=DELETE  Bottom-left=TOGGLE_FLOW  Bottom-right=RESIZE
     */
    fun freestyleCornerActionAtTouch(x: Float, y: Float): FreestyleCornerAction? {
        if (!isFreestyleMode || activeFreestyleBoxId == null) return null
        val box = activeBox ?: return null
        val cs = cellSizePx.takeIf { it > 0 } ?: return null
        val bx = activeBoxOffsetX; val by = activeBoxOffsetY
        val boxW = box.colCount * cs.toFloat()
        val boxH = box.rowCount * cs.toFloat()
        val dp = context.resources.displayMetrics.density
        val hitSz = dp * CORNER_HIT_DP
        // Hit areas mirror the visual icons: each is a hitSz square hanging fully outside the box
        val corners = listOf(
            (bx - hitSz          to by - hitSz)           to FreestyleCornerAction.MOVE,
            (bx + boxW           to by - hitSz)            to FreestyleCornerAction.DELETE,
            (bx - hitSz          to by + boxH)             to FreestyleCornerAction.TOGGLE_FLOW,
            (bx + boxW           to by + boxH)             to FreestyleCornerAction.RESIZE
        )
        return corners.firstOrNull { (pos, _) ->
            val (hx, hy) = pos
            x >= hx && x <= hx + hitSz && y >= hy && y <= hy + hitSz
        }?.second
    }

    // ── Coordinate helpers ────────────────────────────────────────

    fun cellIndexAtTouch(relX: Float, relY: Float): Int {
        val cs = cellSizePx.takeIf { it > 0 } ?: return -1
        val nr = numRowsVal.takeIf { it > 0 } ?: return -1
        if (isFreestyleMode) {
            if (activeFreestyleBoxId == null) return -1
            val localX = relX - activeBoxOffsetX
            val localY = relY - activeBoxOffsetY
            return if (activeBox?.isHorizontal == true) {
                // horizontal: numRows=colCount (chars/line, X), maxColsVal=rowCount (lines, Y)
                if (localX < 0 || localX >= nr * cs || localY < 0 || localY >= maxColsVal * cs) return -1
                val col = (localY.toInt() / cs).coerceIn(0, maxColsVal - 1)
                val row = (localX.toInt() / cs).coerceIn(0, nr - 1)
                col * nr + row
            } else {
                // vertical: numRows=rowCount (chars/col, Y), maxColsVal=colCount (cols, X)
                val totalW = maxColsVal * cs
                if (localX < 0 || localX >= totalW || localY < 0 || localY >= nr * cs) return -1
                val col = (totalW - localX.toInt() - 1) / cs
                val row = localY.toInt() / cs
                col.coerceIn(0, maxColsVal - 1) * nr + row.coerceIn(0, nr - 1)
            }
        }
        if (isHorizontalMode) {
            if (relX < 0 || relX >= nr * cs || relY < 0 || relY >= maxColsVal * cs) return -1
            val col = (relY.toInt() / cs).coerceIn(0, maxColsVal - 1)
            val row = (relX.toInt() / cs).coerceIn(0, nr - 1)
            return col * nr + row
        }
        val totalW = maxColsVal * cs
        if (relX < 0 || relX >= totalW || relY < 0 || relY >= nr * cs) return -1
        val col = (totalW - relX.toInt() - 1) / cs
        val row = relY.toInt() / cs
        return col.coerceIn(0, maxColsVal - 1) * nr + row.coerceIn(0, nr - 1)
    }

    /**
     * Returns the visual-X canvas-local pixel position where the cell at the given index
     * would START in HORIZONTAL mode, accounting for Latin words' real measureText widths
     * (visual-X advance). For VERTICAL / FREESTYLE this just returns cellRect.left.
     * Use this for overlay positioning (e.g. liveHorizontalEditor) so the overlay aligns
     * with the canvas's drawn text instead of the grid.
     */
    /**
     * Right edge of the given cell's visual content in canvas-local pixels. For HORIZONTAL
     * input the right edge equals the next cell's visualXForCell. For other modes it falls
     * back to cellRect.right. Returns null if the index is out of bounds.
     */
    fun visualEndXForCell(index: Int): Int? {
        val cs = cellSizePx.takeIf { it > 0 } ?: return null
        val nr = numRowsVal.takeIf { it > 0 } ?: return null
        val col = index / nr; val row = index % nr
        if (col >= maxColsVal || row < 0 || row >= nr) return null
        if (!isHorizontalInput) return cellRect(index)?.right
        val baseX = if (isFreestyleMode) activeBoxOffsetX else 0f
        val colData = columnData.getOrNull(col)
        var vx = baseX
        for (r in 0..row) {
            val ch = colData?.getOrNull(r) ?: ""
            val effectiveCh = if (ch.isEmpty() || ch == GridLogicHelper.FRONTIER_MARKER)
                (previewOverlay[col * nr + r] ?: ch)
            else ch
            vx = when {
                effectiveCh.isEmpty() || effectiveCh == GridLogicHelper.FRONTIER_MARKER ||
                        effectiveCh == GridLogicHelper.LINE_END_MARKER ->
                    baseX + (r + 1) * cs.toFloat()
                effectiveCh[0].code in 1..127 -> vx + textPaint.measureText(effectiveCh)
                else -> baseX + (r + 1) * cs.toFloat()
            }
        }
        return vx.toInt()
    }

    fun visualXForCell(index: Int): Int? {
        val cs = cellSizePx.takeIf { it > 0 } ?: return null
        val nr = numRowsVal.takeIf { it > 0 } ?: return null
        val col = index / nr; val row = index % nr
        if (col >= maxColsVal || row < 0 || row >= nr) return null
        if (!isHorizontalInput) return cellRect(index)?.left
        // FREESTYLE-horizontal cells draw inside a translated canvas region; the live editor
        // overlay positions in rootFrame coords, so we must include the box offset here.
        val baseX = if (isFreestyleMode) activeBoxOffsetX else 0f
        val colData = columnData.getOrNull(col)
        var vx = baseX
        for (r in 0 until row) {
            val ch = colData?.getOrNull(r) ?: ""
            val effectiveCh = if (ch.isEmpty() || ch == GridLogicHelper.FRONTIER_MARKER)
                (previewOverlay[col * nr + r] ?: ch)
            else ch
            vx = when {
                effectiveCh.isEmpty() || effectiveCh == GridLogicHelper.FRONTIER_MARKER ||
                        effectiveCh == GridLogicHelper.LINE_END_MARKER ->
                    baseX + (r + 1) * cs.toFloat()
                effectiveCh[0].code in 1..127 -> vx + textPaint.measureText(effectiveCh)
                else -> baseX + (r + 1) * cs.toFloat()
            }
        }
        return vx.toInt()
    }

    fun cellRect(index: Int): Rect? {
        val cs = cellSizePx.takeIf { it > 0 } ?: return null
        val nr = numRowsVal.takeIf { it > 0 } ?: return null
        val col = index / nr; val row = index % nr
        if (col >= maxColsVal) return null
        if (isHorizontalMode) {
            return Rect(row * cs, col * cs, (row + 1) * cs, (col + 1) * cs)
        }
        if (isFreestyleMode) {
            return if (activeBox?.isHorizontal == true) {
                // horizontal: col=line(y), row=char(x)
                Rect(
                    (row * cs + activeBoxOffsetX).toInt(),
                    (col * cs + activeBoxOffsetY).toInt(),
                    ((row + 1) * cs + activeBoxOffsetX).toInt(),
                    ((col + 1) * cs + activeBoxOffsetY).toInt()
                )
            } else {
                // vertical: col=column(x from right), row=char(y)
                val totalW = maxColsVal * cs
                val left = totalW - (col + 1) * cs
                Rect(
                    (left + activeBoxOffsetX).toInt(),
                    (row * cs + activeBoxOffsetY).toInt(),
                    (left + cs + activeBoxOffsetX).toInt(),
                    ((row + 1) * cs + activeBoxOffsetY).toInt()
                )
            }
        }
        val totalW = maxColsVal * cs
        val left   = totalW - (col + 1) * cs
        val top    = row * cs
        return Rect(left, top, left + cs, top + cs)
    }

    private fun invalidateCursorRegion(index: Int) {
        invalidate()
    }

    private fun invalidateCursorTransition(oldIndex: Int, newIndex: Int) {
        invalidate()
    }

    private fun invalidateSelectionTransition(oldFrom: Int, oldTo: Int, newFrom: Int, newTo: Int) {
        invalidate()
    }

    // ── View overrides ────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (isFreestyleMode) {
            val cs = cellSizePx.coerceAtLeast(20)
            val w = if (freestyleMinW > 0) freestyleMinW else cs * 20
            val h = if (freestyleMinH > 0) freestyleMinH else cs * 30
            setMeasuredDimension(w, h)
            return
        }
        val cs = cellSizePx.coerceAtLeast(1)
        if (isHorizontalMode) {
            setMeasuredDimension(numRowsVal.coerceAtLeast(1) * cs, maxColsVal * cs)
            return
        }
        setMeasuredDimension(maxColsVal * cs, numRowsVal.coerceAtLeast(1) * cs)
    }

    override fun onDraw(canvas: Canvas) {
        val cs = cellSizePx.takeIf { it > 0 } ?: return
        val nr = numRowsVal.takeIf { it > 0 } ?: return

        if (isFreestyleMode) {
            drawFreestyleCanvas(canvas, cs, nr)
            if (!hasRealContent()) drawFreestyleHint(canvas, cs)
            return
        }
        if (isHorizontalMode) {
            drawHorizontalContent(canvas, cs, nr)
        } else {
            drawMainContent(canvas, cs, nr)
        }
        if (!hasRealContent()) drawHint(canvas, cs, nr)
    }

    // ── Drawing helpers ───────────────────────────────────────────

    private fun drawMainContent(canvas: Canvas, cs: Int, nr: Int) {
        val totalW = maxColsVal * cs
        val selFrom = selectionFrom; val selTo = selectionTo
        val hasSelection = selFrom >= 0 && selTo >= 0
        val clip = canvas.clipBounds
        val startCol = ((totalW - clip.right) / cs).coerceIn(0, (maxColsVal - 1).coerceAtLeast(0))
        val endCol = ((totalW - clip.left - 1) / cs).coerceIn(0, (maxColsVal - 1).coerceAtLeast(0))
        val startRow = (clip.top / cs).coerceIn(0, (nr - 1).coerceAtLeast(0))
        val endRow = ((clip.bottom - 1) / cs).coerceIn(0, (nr - 1).coerceAtLeast(0))
        if (startCol > endCol || startRow > endRow) return

        for (col in startCol..endCol) {
            val colData = columnData.getOrNull(col)
            val cellL = (totalW - (col + 1) * cs).toFloat()
            val textX = cellL + cs / 2f

            for (row in startRow..endRow) {
                val index = col * nr + row
                val cellTop = (row * cs).toFloat()

                if (hasSelection && index in selFrom..selTo) {
                    canvas.drawRect(cellL, cellTop, cellL + cs, cellTop + cs, selPaint)
                }

                val previewCh = previewOverlay[index]
                val dataCh = colData?.getOrNull(row) ?: ""

                when {
                    previewCh != null -> {
                        canvas.drawText(previewCh, textX, cellTop + baselineOffset, previewPaint)
                    }
                    dataCh == GridLogicHelper.LINE_END_MARKER && !hideLineEndMarkers -> {
                        canvas.drawText(dataCh, textX, cellTop + lineEndBaselineOffset, lineEndPaint)
                    }
                    dataCh.isNotEmpty() && dataCh != GridLogicHelper.FRONTIER_MARKER
                            && dataCh != GridLogicHelper.LINE_END_MARKER -> {
                        canvas.drawText(dataCh, textX, cellTop + baselineOffset, textPaint)
                    }
                }
            }

            if (cursorVisible && cursorIndex >= 0 && nr > 0) {
                val curCol = cursorIndex / nr
                val curRow = cursorIndex % nr
                if (curCol == col && curRow in startRow..endRow) {
                    val cellTop = (curRow * cs).toFloat()
                    val margin = cs * 0.08f
                    val lineY = cellTop + 3f
                    canvas.drawLine(cellL + margin, lineY, cellL + cs - margin, lineY, cursorPaint)
                }
            }
        }
    }

    // Horizontal mode: col = line (top→bottom), row = char within line (left→right).
    // Latin words/spaces track real text-metric positions; CJK snaps back to the cell grid.
    // Cursor is a vertical bar at the visual start of the focused cell.
    private fun drawHorizontalContent(canvas: Canvas, cs: Int, nr: Int) {
        val selFrom = selectionFrom; val selTo = selectionTo
        val hasSelection = selFrom >= 0 && selTo >= 0
        val clip = canvas.clipBounds
        val startCol = (clip.top / cs).coerceIn(0, (maxColsVal - 1).coerceAtLeast(0))
        val endCol = ((clip.bottom - 1) / cs).coerceIn(0, (maxColsVal - 1).coerceAtLeast(0))
        val startRow = (clip.left / cs).coerceIn(0, (nr - 1).coerceAtLeast(0))
        val endRow = ((clip.right - 1) / cs).coerceIn(0, (nr - 1).coerceAtLeast(0))
        if (startCol > endCol || startRow > endRow) return

        for (col in startCol..endCol) {
            val colData = columnData.getOrNull(col)
            val lineTop = (col * cs).toFloat()

            // Pre-compute the visual X start of each row, scanning from row 0.
            // Must start from 0 so clipped lines get correct positions.
            // For empty cells, preview overlay chars (composing) count toward visual advance
            // so the grey preview sequence doesn't jump back to the grid.
            val rowVisualX = FloatArray(nr)
            var vx = 0f
            for (r in 0 until nr) {
                rowVisualX[r] = vx
                val ch = colData?.getOrNull(r) ?: ""
                // Use the preview char for advance if the cell is empty or carries only a
                // FRONTIER_MARKER — the marker is non-empty so isEmpty() alone misses it.
                val effectiveCh = if (ch.isEmpty() || ch == GridLogicHelper.FRONTIER_MARKER)
                    (previewOverlay[col * nr + r] ?: ch)
                else
                    ch
                vx = when {
                    effectiveCh.isEmpty() || effectiveCh == GridLogicHelper.FRONTIER_MARKER ||
                            effectiveCh == GridLogicHelper.LINE_END_MARKER ->
                        (r + 1) * cs.toFloat()
                    // ASCII (code 1-127): letters, digits, punctuation — use real metrics
                    effectiveCh[0].code in 1..127 -> vx + textPaint.measureText(effectiveCh)
                    // Non-ASCII (CJK, full-width symbols): snap to grid so wordGap applies
                    else -> (r + 1) * cs.toFloat()
                }
            }

            for (row in startRow..endRow) {
                val index   = col * nr + row
                val cellLeft = (row * cs).toFloat()
                val textX   = cellLeft + cs / 2f
                val vxHere  = rowVisualX[row]

                if (hasSelection && index in selFrom..selTo) {
                    // Highlight tracks visual-X so the rectangle covers the actual drawn
                    // text (word-per-cell cells render narrower than cs). The next row's
                    // visual-X is the right edge of this cell; for the last row in the
                    // line, fall back to the visual extent reached by the loop above.
                    val vxRight = if (row + 1 < nr) rowVisualX[row + 1] else vx
                    canvas.drawRect(vxHere, lineTop, vxRight, lineTop + cs, selPaint)
                }

                val previewCh = previewOverlay[index]
                val dataCh   = colData?.getOrNull(row) ?: ""

                // Skip drawing the focused cell only when the live EditText actually has
                // text covering the cell — otherwise an empty editor would hide an already-
                // committed word at the focus cell.
                val skipForOverlay = liveEditorCoversCell && index == cursorIndex
                if (skipForOverlay) {
                    // skip both content and preview at this cell
                } else when {
                    previewCh != null -> {
                        // ASCII composing chars use visual-X (LEFT anchor); CJK preview stays on grid
                        if (previewCh[0].code in 1..127)
                            canvas.drawText(previewCh, vxHere, lineTop + baselineOffset, previewPaintLeft)
                        else
                            canvas.drawText(previewCh, textX, lineTop + baselineOffset, previewPaint)
                    }
                    dataCh == GridLogicHelper.LINE_END_MARKER && !hideLineEndMarkers -> {
                        canvas.drawText(dataCh, textX, lineTop + lineEndBaselineOffset, lineEndPaint)
                    }
                    dataCh.isNotEmpty() && dataCh != GridLogicHelper.FRONTIER_MARKER
                            && dataCh != GridLogicHelper.LINE_END_MARKER -> {
                        when {
                            dataCh.length > 1 ->
                                canvas.drawText(dataCh, vxHere, lineTop + baselineOffset, wordPaint)
                            dataCh == " " -> { /* space advances vx but draws nothing */ }
                            // All ASCII (letters, digits, punctuation like . , ! ?) use visual-X
                            dataCh[0].code in 1..127 ->
                                canvas.drawText(dataCh, vxHere, lineTop + baselineOffset, wordPaint)
                            // Non-ASCII (CJK) stays centered on its grid cell
                            else ->
                                canvas.drawText(dataCh, textX, lineTop + baselineOffset, textPaint)
                        }
                    }
                }
            }

            if (cursorVisible && cursorIndex >= 0 && !liveEditorOverlayActive) {
                val curCol = cursorIndex / nr
                val curRow = cursorIndex % nr
                if (curCol == col && curRow in startRow..endRow) {
                    val curVx  = rowVisualX.getOrElse(curRow) { curRow * cs.toFloat() }
                    val margin = cs * 0.08f
                    canvas.drawLine(curVx + margin, lineTop + 3f,
                                    curVx + margin, lineTop + cs - 3f, cursorPaint)
                }
            }
        }
    }

    private fun drawFreestyleCanvas(canvas: Canvas, cs: Int, nr: Int) {
        // Inactive boxes first
        for (box in freestyleBoxList) {
            if (box.id == activeFreestyleBoxId) continue
            val boxCs = boxCellSizeCache[box.id] ?: cs
            val boxW = box.colCount * boxCs   // colCount = X
            val boxH = box.rowCount * boxCs   // rowCount = Y
            if (box.boxBgColor != Color.TRANSPARENT) {
                boxBgFillPaint.color = box.boxBgColor
                canvas.drawRect(box.leftPx, box.topPx, box.leftPx + boxW, box.topPx + boxH, boxBgFillPaint)
            }
            if (box.borderVisible) {
                val thicknessDp = AppConfig.BORDER_THICKNESS_LIST.getOrNull(box.borderThicknessIdx)?.first ?: 1f
                boxBorderPaint.strokeWidth = thicknessDp * context.resources.displayMetrics.density
                boxBorderPaint.color = box.borderColor
                canvas.drawRect(box.leftPx, box.topPx, box.leftPx + boxW, box.topPx + boxH, boxBorderPaint)
            }
            canvas.save()
            canvas.clipRect(box.leftPx, box.topPx, box.leftPx + boxW, box.topPx + boxH)
            canvas.translate(box.leftPx, box.topPx)
            drawInactiveBoxContent(canvas, box, boxCs)
            canvas.restore()
        }

        val ab = activeBox ?: return
        val boxW = ab.colCount * cs.toFloat()   // colCount = X
        val boxH = ab.rowCount * cs.toFloat()   // rowCount = Y
        val bx = activeBoxOffsetX; val by = activeBoxOffsetY
        if (ab.boxBgColor != Color.TRANSPARENT) {
            boxBgFillPaint.color = ab.boxBgColor
            canvas.drawRect(bx, by, bx + boxW, by + boxH, boxBgFillPaint)
        }
        if (ab.borderVisible) {
            val thicknessDp = AppConfig.BORDER_THICKNESS_LIST.getOrNull(ab.borderThicknessIdx)?.first ?: 1f
            boxBorderPaint.strokeWidth = thicknessDp * context.resources.displayMetrics.density
            boxBorderPaint.color = ab.borderColor
            canvas.drawRect(bx, by, bx + boxW, by + boxH, boxBorderPaint)
        }
        canvas.save()
        canvas.clipRect(bx, by, bx + boxW, by + boxH)
        canvas.translate(bx, by)
        if (ab.isHorizontal) drawHorizontalContent(canvas, cs, nr)
        else                  drawMainContent(canvas, cs, nr)
        canvas.restore()

        // 4 corner action icons
        val dp    = context.resources.displayMetrics.density
        val visSz = CORNER_VIS_DP * dp
        val isZh  = context.resources.configuration.locales[0].language == "zh"
        val flowLabel = when {
            ab.isHorizontal && isZh  -> "橫"
            ab.isHorizontal          -> "H"
            isZh                     -> "直"
            else                     -> "V"
        }
        // Icons hang outside the box, touching each corner from the exterior
        val icons = listOf(
            (bx - visSz          to by - visSz)           to null,      // top-left:     MOVE
            (bx + boxW           to by - visSz)            to "×",       // top-right:    DELETE
            (bx - visSz          to by + boxH)             to flowLabel, // bottom-left:  TOGGLE_FLOW
            (bx + boxW           to by + boxH)             to "◢"        // bottom-right: RESIZE
        )
        cornerIconPaint.textSize = visSz * 0.62f
        for ((pos, label) in icons) {
            val (hx, hy) = pos
            canvas.drawRect(hx, hy, hx + visSz, hy + visSz, cornerFillPaint)
            canvas.drawRect(hx, hy, hx + visSz, hy + visSz, cornerStrokePaint)
            if (label != null) {
                canvas.drawText(label, hx + visSz / 2f, hy + visSz * 0.77f, cornerIconPaint)
            } else {
                drawMoveArrowIcon(canvas, hx, hy, visSz, cornerIconPaint)
            }
        }
    }

    private fun drawInactiveBoxContent(canvas: Canvas, box: TextBoxInstance, cs: Int) {
        // Match the active box's rendering size: both use the box's own fontPx so
        // selecting / deselecting a box doesn't visibly resize the text. (Previously
        // inactive boxes drew at cs * 0.75f, which made the text jump 25% smaller on
        // deselect.)
        val fontPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_SP, box.fontSizeSp,
            context.resources.displayMetrics
        )
        inactiveBoxTextPaint.textSize = fontPx
        inactiveBoxTextPaint.typeface = box.typeface
        inactiveBoxTextPaint.color = box.gridTextColor
        val fm = inactiveBoxTextPaint.fontMetrics
        val baseline = (cs - (fm.descent - fm.ascent)) / 2f - fm.ascent

        if (box.isHorizontal) {
            // col = line index (0 until rowCount, Y), row = char within line (0 until colCount, X)
            // Pack ASCII cells via cumulative measureText (visual-X), the same rule
            // drawHorizontalContent uses for the active box. Without this, word cells were
            // placed at the row*cs grid — leaving a cellSize-wide gap after each word once
            // the box was deactivated.
            val savedAlign = inactiveBoxTextPaint.textAlign
            for (col in 0 until box.rowCount) {
                val colData = box.columnData.getOrNull(col)
                val lineTop = (col * cs).toFloat()
                var vx = 0f
                for (row in 0 until box.colCount) {
                    val ch = colData?.getOrNull(row) ?: ""
                    val isMarker = ch.isEmpty() ||
                            ch == GridLogicHelper.FRONTIER_MARKER ||
                            ch == GridLogicHelper.LINE_END_MARKER
                    if (!isMarker) {
                        if (ch[0].code in 1..127) {
                            // ASCII (letters, digits, punct, space, word-per-cell): visual-X left anchor.
                            if (ch != " ") {
                                inactiveBoxTextPaint.textAlign = Paint.Align.LEFT
                                canvas.drawText(ch, vx, lineTop + baseline, inactiveBoxTextPaint)
                            }
                            vx += inactiveBoxTextPaint.measureText(ch)
                        } else {
                            // Non-ASCII (CJK / full-width): grid-centered.
                            inactiveBoxTextPaint.textAlign = savedAlign
                            canvas.drawText(ch, row * cs + cs / 2f, lineTop + baseline, inactiveBoxTextPaint)
                            vx = (row + 1) * cs.toFloat()
                        }
                    } else {
                        // Empty / marker cell: snap vx to the grid as in the active renderer.
                        vx = (row + 1) * cs.toFloat()
                    }
                }
            }
            inactiveBoxTextPaint.textAlign = savedAlign
        } else {
            // col = column (0 until colCount, X from right), row = char (0 until rowCount, Y)
            val totalW = box.colCount * cs
            for (col in 0 until box.colCount) {
                val colData = box.columnData.getOrNull(col)
                val cellL = (totalW - (col + 1) * cs).toFloat()
                val textX = cellL + cs / 2f
                for (row in 0 until box.rowCount) {
                    val ch = colData?.getOrNull(row) ?: ""
                    if (ch.isNotEmpty() && ch != GridLogicHelper.FRONTIER_MARKER
                            && ch != GridLogicHelper.LINE_END_MARKER) {
                        canvas.drawText(ch, textX, row * cs + baseline, inactiveBoxTextPaint)
                    }
                }
            }
        }
    }

    private fun drawMoveArrowIcon(canvas: Canvas, hx: Float, hy: Float, sz: Float, paint: Paint) {
        val cx      = hx + sz / 2f
        val cy      = hy + sz / 2f
        val tip     = sz * 0.44f  // center → arrowhead tip
        val headW   = sz * 0.20f  // arrowhead half-width
        val headLen = sz * 0.22f  // arrowhead depth
        val stemW   = sz * 0.07f  // cross-arm half-width

        val savedStyle = paint.style
        paint.style = Paint.Style.FILL

        moveArrowPath.reset()

        // ↑ arrowhead
        moveArrowPath.moveTo(cx,          cy - tip)
        moveArrowPath.lineTo(cx - headW,  cy - tip + headLen)
        moveArrowPath.lineTo(cx + headW,  cy - tip + headLen)
        moveArrowPath.close()

        // ↓ arrowhead
        moveArrowPath.moveTo(cx,          cy + tip)
        moveArrowPath.lineTo(cx + headW,  cy + tip - headLen)
        moveArrowPath.lineTo(cx - headW,  cy + tip - headLen)
        moveArrowPath.close()

        // ← arrowhead
        moveArrowPath.moveTo(cx - tip,    cy)
        moveArrowPath.lineTo(cx - tip + headLen, cy + headW)
        moveArrowPath.lineTo(cx - tip + headLen, cy - headW)
        moveArrowPath.close()

        // → arrowhead
        moveArrowPath.moveTo(cx + tip,    cy)
        moveArrowPath.lineTo(cx + tip - headLen, cy - headW)
        moveArrowPath.lineTo(cx + tip - headLen, cy + headW)
        moveArrowPath.close()

        // vertical stem connecting the four arrowheads
        moveArrowPath.addRect(
            cx - stemW, cy - tip + headLen,
            cx + stemW, cy + tip - headLen,
            Path.Direction.CW
        )
        // horizontal stem
        moveArrowPath.addRect(
            cx - tip + headLen, cy - stemW,
            cx + tip - headLen, cy + stemW,
            Path.Direction.CW
        )

        canvas.drawPath(moveArrowPath, paint)
        paint.style = savedStyle
    }

    private fun hasRealContent(): Boolean {
        if (isFreestyleMode) return freestyleBoxList.isNotEmpty()
        return columnData.any { col ->
            col.any { ch ->
                ch.isNotBlank()
                    && ch != GridLogicHelper.FRONTIER_MARKER
                    && ch != GridLogicHelper.LINE_END_MARKER
            }
        }
    }

    // Draws hint characters in the first visible column/line for VERTICAL and HORIZONTAL modes.
    private fun drawHint(canvas: Canvas, cs: Int, nr: Int) {
        val hintChars = context.getString(R.string.hint_start_writing).map { it.toString() }
        if (isHorizontalMode) {
            hintChars.forEachIndexed { row, ch ->
                if (row < nr) {
                    canvas.drawText(ch, row * cs + cs / 2f, baselineOffset, hintPaint)
                }
            }
        } else {
            // Rightmost column (col 0 in RTL) is always in the initial scroll viewport.
            val textX = maxColsVal * cs - cs / 2f
            hintChars.forEachIndexed { row, ch ->
                if (row < nr) {
                    canvas.drawText(ch, textX, row * cs + baselineOffset, hintPaint)
                }
            }
        }
    }

    // Draws a dashed hint box centered in the freestyle canvas (which is screen-sized).
    private fun drawFreestyleHint(canvas: Canvas, cs: Int) {
        val label = context.getString(R.string.hint_freestyle)
        val boxW = hintPaint.measureText(label) + cs * 1.2f
        val boxH = 2.5f * cs
        val cx = width / 2f
        val top = cs * 3f
        val bottom = top + boxH
        val left = cx - boxW / 2f
        val right = cx + boxW / 2f
        val r = cs * 0.3f
        val cy = (top + bottom) / 2f

        canvas.drawRoundRect(left, top, right, bottom, r, r, hintBoxPaint)

        val fm = hintPaint.fontMetrics
        canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, hintPaint)
    }

    companion object {
        const val CORNER_VIS_DP = 18f   // visual square size
        const val CORNER_HIT_DP = 24f   // touch hit area
    }
}
