package com.freescript

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Owns text selection state, handle positioning, copy/cut/paste, and the selection options bar.
 * Extracted from MainActivity (Phase 6 Priority 1).
 *
 * Wire-up: MainActivity creates this, delegates all selection callbacks to it, and exposes
 * [isSelecting], [selectionStart], [selectionEnd] as read-only properties for touch dispatch.
 */
class SelectionController(
    private val context: Context,
    private val cb: Callbacks
) {

    interface Callbacks {
        // View access
        fun getRootFrame(): android.widget.FrameLayout
        fun getPoemCanvas(): PoemCanvasView
        fun getStartHandle(): View?
        fun getEndHandle(): View?
        fun getSelectionOptionsView(): LinearLayout?
        fun getHandlePasteView(): TextView?

        // Grid state (read-only)
        fun getNumRows(): Int
        fun getMaxColumns(): Int
        fun getColumnData(): List<List<String>>
        fun getColumnBreaks(): Set<Int>
        fun getFocusedCellIndex(): Int

        // Mutation hooks (controller drives these back into MainActivity)
        fun setColumnChar(col: Int, row: Int, ch: String)
        fun reflowAfterCut(from: Int)       // calls reflowColumnData + postRefreshFocusColumn
        fun focusCell(index: Int)

        // IME
        fun hideIme()

        // Lifecycle hooks for selection mode. The live EditText overlay in HORIZONTAL mode
        // would otherwise intercept double-taps inside the selection region; the host hides
        // it on entry and restores it on exit.
        fun onSelectionEntered() {}
        fun onSelectionExited() {}
    }

    // ── Mutable selection state ───────────────────────────────────────
    var isSelecting    = false; private set
    var selectionStart = -1;    private set
    var selectionEnd   = -1;    private set

    private var highlightFrom    = -1
    private var highlightTo      = -1
    private var isFrameScheduled = false
    private var cachedPopupW     = -1
    private var cachedPopupH     = -1
    var tappedHandleIsStart: Boolean? = null

    // Drag cache (set by dispatchTouchEvent in MainActivity on ACTION_DOWN)
    val cachedRootLoc  = IntArray(2)
    var cachedCellSize = 0

    // ── Entry / exit ──────────────────────────────────────────────────

    fun enterSelectionMode(index: Int) {
        cb.hideIme()
        isSelecting = true
        highlightFrom = -1
        selectionStart = index; selectionEnd = index
        cb.onSelectionEntered()
        updateSelectionHighlight()
    }

    fun clearSelection() {
        val wasSelecting = isSelecting
        isSelecting = false; selectionStart = -1; selectionEnd = -1
        cb.getPoemCanvas().clearSelectionHighlight()
        highlightFrom = -1; highlightTo = -1
        isFrameScheduled = false
        cachedPopupW = -1; cachedPopupH = -1
        cb.getStartHandle()?.visibility = View.GONE
        cb.getEndHandle()?.visibility   = View.GONE
        cb.getSelectionOptionsView()?.visibility = View.GONE
        cb.getHandlePasteView()?.visibility = View.GONE
        tappedHandleIsStart = null
        if (wasSelecting) cb.onSelectionExited()
    }

    // ── Highlight ─────────────────────────────────────────────────────

    fun updateSelectionHighlight() {
        if (!isSelecting) return
        val total = cb.getMaxColumns() * cb.getNumRows()
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val to   = maxOf(selectionStart, selectionEnd).coerceAtMost((total - 1).coerceAtLeast(0))
        updateHighlightDiff(from, to)
        positionHandleAt(cb.getStartHandle(), from, atTop = true)
        positionHandleAt(cb.getEndHandle(),   to,   atTop = false)
        positionSelectionOptionsView()
    }

    fun updateHighlightDiff(newFrom: Int, newTo: Int) {
        cb.getPoemCanvas().setSelection(newFrom, newTo)
        highlightFrom = newFrom; highlightTo = newTo
    }

    fun repositionHandles() {
        if (!isSelecting) return
        val total = cb.getMaxColumns() * cb.getNumRows()
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val to   = maxOf(selectionStart, selectionEnd).coerceAtMost((total - 1).coerceAtLeast(0))
        positionHandleAt(cb.getStartHandle(), from, atTop = true)
        positionHandleAt(cb.getEndHandle(),   to,   atTop = false)
        positionSelectionOptionsView()
    }

    // ── Handle positioning ────────────────────────────────────────────

    fun positionHandleAt(handle: View?, index: Int, atTop: Boolean) {
        handle ?: return
        val canvas = cb.getPoemCanvas()
        val cellRect = canvas.cellRect(index) ?: return
        val canvasLoc = IntArray(2); canvas.getLocationOnScreen(canvasLoc)
        val rootLoc   = IntArray(2); cb.getRootFrame().getLocationOnScreen(rootLoc)
        val sz = handle.layoutParams.width.toFloat()
        // In horizontal input mode the cell grid (row * cs) diverges from where the text
        // actually renders (cumulative measureText), so anchor handles at the visual edge
        // of the cell rather than the grid. For other modes visualXForCell/visualEndXForCell
        // fall back to cellRect.left/right.
        val anchorX = if (atTop)
            (canvas.visualXForCell(index) ?: cellRect.left).toFloat()
        else
            (canvas.visualEndXForCell(index) ?: cellRect.right).toFloat()
        val x  = (canvasLoc[0] - rootLoc[0] + anchorX - sz / 2f)
        val y  = (canvasLoc[1] - rootLoc[1] + if (atTop) cellRect.top.toFloat() - sz else cellRect.bottom.toFloat())
        // (Previously had `if (x == 0f) return` as a "layout not yet measured" guard; with
        // visual-X anchoring x can legitimately compute to 0 — that guard hides handles for
        // first-row first-column selections, which is exactly the regression we hit.)
        handle.x = x.coerceAtLeast(0f); handle.y = y.coerceAtLeast(0f)
        handle.visibility = View.VISIBLE
    }

    fun fastPositionHandleByIndex(handle: View?, index: Int, atTop: Boolean) =
        positionHandleAt(handle, index, atTop)

    fun fastCellIndexAtPoint(rawX: Float, rawY: Float): Int {
        val canvas = cb.getPoemCanvas()
        val loc = IntArray(2); canvas.getLocationOnScreen(loc)
        val raw = canvas.cellIndexAtTouch(rawX - loc[0], rawY - loc[1])
        if (raw < 0) return raw
        val nr = cb.getNumRows().coerceAtLeast(1)
        val maxActiveCol = cb.getColumnData().indexOfLast { c -> c.any { it.isNotEmpty() } }.coerceAtLeast(0)
        val col = (raw / nr).coerceIn(0, maxActiveCol)
        return col * nr + raw % nr
    }

    private fun positionSelectionOptionsView() {
        val pop = cb.getSelectionOptionsView() ?: return
        if (!isSelecting) return
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val cellRect = cb.getPoemCanvas().cellRect(from) ?: return
        if (cachedPopupW < 0) {
            pop.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            cachedPopupW = pop.measuredWidth; cachedPopupH = pop.measuredHeight
        }
        val popW = cachedPopupW.toFloat()
        val dp   = context.resources.displayMetrics.density
        val gap  = 8f * dp

        val canvas = cb.getPoemCanvas()
        val canvasLoc = IntArray(2); canvas.getLocationOnScreen(canvasLoc)
        val rootLoc   = IntArray(2); cb.getRootFrame().getLocationOnScreen(rootLoc)
        // Anchor at the visual-X start of the selection's first cell so the popup tracks
        // word-per-cell layout in horizontal mode. Falls back to cellRect.left for other modes.
        val anchorLeft = canvas.visualXForCell(from) ?: cellRect.left
        val cellScreenX = canvasLoc[0] - rootLoc[0] + anchorLeft
        val cellScreenY = canvasLoc[1] - rootLoc[1] + cellRect.top

        var pvX = cellScreenX - popW - gap
        val rootW = cb.getRootFrame().width.toFloat()
        if (pvX < gap) pvX = (cellScreenX + cellRect.width() + gap).coerceAtMost(rootW - popW - gap)
        // Clamp Y so the popup never disappears off the top of the screen (notably for cells
        // on the first line in HORIZONTAL mode where cellScreenY can be 0 / negative).
        val popH = cachedPopupH.toFloat().coerceAtLeast(0f)
        val rootH = cb.getRootFrame().height.toFloat()
        var pvY = cellScreenY.toFloat()
        if (pvY < gap) pvY = (cellScreenY + cellRect.height() + gap).coerceAtLeast(gap)
        pvY = pvY.coerceAtMost((rootH - popH - gap).coerceAtLeast(gap))
        pop.x = pvX; pop.y = pvY
        pop.visibility = View.VISIBLE
    }

    // ── Select entire line / paragraph ────────────────────────────────

    fun selectEntireLine() {
        if (selectionStart < 0) return
        val nr    = cb.getNumRows().coerceAtLeast(1)
        val total = (cb.getMaxColumns() * nr - 1).coerceAtLeast(0)
        val col   = minOf(selectionStart, selectionEnd).coerceAtLeast(0) / nr
        selectionStart = (col * nr).coerceIn(0, total)
        selectionEnd   = ((col + 1) * nr - 1).coerceIn(0, total)
        updateSelectionHighlight()
    }

    fun selectEntireParagraph(numColumns: Int) {
        if (selectionStart < 0) return
        val nr     = cb.getNumRows().coerceAtLeast(1)
        val curCol = minOf(selectionStart, selectionEnd).coerceAtLeast(0) / nr
        val breaks = cb.getColumnBreaks().sorted()
        var paraStartCol = 0
        for (brk in breaks) { if (brk <= curCol) paraStartCol = brk else break }
        var paraEndCol = numColumns - 1
        for (brk in breaks) { if (brk > curCol) { paraEndCol = brk - 1; break } }
        var hasContent = false
        var last = -1
        for (col in paraStartCol..paraEndCol) {
            val cells = cb.getColumnData().getOrNull(col) ?: continue
            for (row in cells.indices) {
                if (cells[row].isNotBlank()) {
                    hasContent = true
                    last = col * nr + row
                }
            }
        }
        if (hasContent) {
            // Anchor at the paragraph's first cell so any leading tab / blank cells before
            // the first word are part of the selection.
            selectionStart = paraStartCol * nr
            selectionEnd = last
            updateSelectionHighlight()
        }
    }

    fun selectAll(maxColumns: Int) {
        val nr = cb.getNumRows().coerceAtLeast(1)
        var first = -1; var last = -1
        for (col in 0 until maxColumns) {
            val colData = cb.getColumnData().getOrNull(col) ?: continue
            for (row in colData.indices) {
                if (colData[row].isNotEmpty()) {
                    val i = col * nr + row
                    if (first < 0) first = i; last = i
                }
            }
        }
        if (first >= 0) {
            // Enter selection mode if the caller invoked selectAll without first entering
            // it (e.g. from the EditText action mode in HORIZONTAL hybrid). Without this,
            // updateSelectionHighlight() bails on !isSelecting and the highlight never paints.
            val entered = !isSelecting
            if (entered) {
                cb.hideIme()
                isSelecting = true
                highlightFrom = -1
            }
            selectionStart = first; selectionEnd = last
            if (entered) cb.onSelectionEntered()
            updateSelectionHighlight()
        }
    }

    // ── Drag state helpers (called by MainActivity.dispatchTouchEvent) ─

    fun onDragStart(isStartHandle: Boolean) {
        tappedHandleIsStart = isStartHandle
        cb.getHandlePasteView()?.visibility = View.GONE
    }

    fun showPasteView(isStart: Boolean) {
        if (cb.getSelectionOptionsView()?.visibility == View.VISIBLE) return
        val handle = if (isStart) cb.getStartHandle() else cb.getEndHandle()
        handle ?: return
        val pv = cb.getHandlePasteView() ?: return
        pv.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val pvW = pv.measuredWidth.toFloat()
        val pvH = pv.measuredHeight.toFloat()
        val gap = 6f * context.resources.displayMetrics.density
        val handleSz = handle.layoutParams.width.toFloat()
        var pvX = handle.x + handleSz / 2f - pvW / 2f
        var pvY = handle.y - pvH - gap
        val rootW = cb.getRootFrame().width.toFloat()
        pvX = pvX.coerceIn(gap, (rootW - pvW - gap).coerceAtLeast(gap))
        if (pvY < gap) pvY = handle.y + handleSz + gap
        pv.x = pvX; pv.y = pvY
        pv.visibility = View.VISIBLE
    }

    fun hidePasteView() {
        cb.getHandlePasteView()?.visibility = View.GONE
    }

    fun updateDragSelection(index: Int) {
        if (tappedHandleIsStart == true) selectionStart = index
        else if (tappedHandleIsStart == false) selectionEnd = index
        updateHighlightDiff(
            minOf(selectionStart, selectionEnd).coerceAtLeast(0),
            maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
        )
        positionHandleAt(cb.getStartHandle(), minOf(selectionStart, selectionEnd), atTop = true)
        positionHandleAt(cb.getEndHandle(),   maxOf(selectionStart, selectionEnd), atTop = false)
    }

    // ── Clipboard ─────────────────────────────────────────────────────

    private fun selectedTextToString(): String {
        val nr   = cb.getNumRows().coerceAtLeast(1)
        val max  = cb.getMaxColumns()
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val to   = maxOf(selectionStart, selectionEnd).coerceAtMost((max * nr - 1).coerceAtLeast(0))
        val sb   = StringBuilder()
        var prevCol = from / nr
        for (i in from..to) {
            val c = i / nr; val r = i % nr
            if (c != prevCol) { if (cb.getColumnBreaks().contains(c)) sb.append('\n'); prevCol = c }
            val cell = cb.getColumnData().getOrNull(c)?.getOrNull(r) ?: ""
            when {
                cell.isEmpty() -> sb.append('\t')   // empty cell = tab; preserved by paste
                cell == GridLogicHelper.FRONTIER_MARKER -> { /* skip marker */ }
                cell == GridLogicHelper.LINE_END_MARKER -> { /* skip marker */ }
                else -> sb.append(cell)
            }
        }
        return sb.toString()
    }

    fun copySelectedText() {
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("freescript", selectedTextToString()))
        Toast.makeText(context, context.getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
        clearSelection()
    }

    fun cutSelectedText() {
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("freescript", selectedTextToString()))
        val nr   = cb.getNumRows().coerceAtLeast(1)
        val max  = cb.getMaxColumns()
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val to   = maxOf(selectionStart, selectionEnd).coerceAtMost((max * nr - 1).coerceAtLeast(0))
        for (i in from..to) { val c = i / nr; val r = i % nr; cb.setColumnChar(c, r, "") }
        val focusTarget = from.coerceAtMost((max * nr - 1).coerceAtLeast(0))
        clearSelection()
        cb.reflowAfterCut(focusTarget)
        Toast.makeText(context, context.getString(R.string.toast_cut), Toast.LENGTH_SHORT).show()
    }
}
