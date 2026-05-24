package com.freescript

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout

/**
 * Owns the live EditText overlay used in HORIZONTAL canvas mode and in FREESTYLE
 * boxes whose `isHorizontal` flag is true. The overlay sits on top of the focused
 * cell so Gboard composes / autocorrects natively for it. Buffered text commits
 * into the canvas cell model at boundary characters (space, punctuation, digits,
 * CJK) — letters and apostrophes accumulate as a single word-per-cell.
 *
 * Extracted from MainActivity to keep the editor's lifecycle, listeners and
 * pre-commit wrap logic together; MainActivity now wires it once and forwards
 * a few well-defined events.
 */
class LiveHorizontalEditorController(
    private val context: Context,
    private val cb: Callbacks
) {

    interface Callbacks {
        // Settings (read each call so font/colour/size updates land immediately)
        fun getSelectedTypeface(): Typeface
        fun getFontSizeSp(): Float
        fun getGridTextColor(): Int
        fun getBgColor(): Int
        fun getCanvasMode(): CanvasMode
        fun getCurrentCellSize(): Int
        fun isLiveEditorActive(): Boolean
        fun isHostRestoring(): Boolean

        // Grid state
        fun getFocusedCellIndex(): Int
        fun setFocusedCellIndex(idx: Int)
        fun getNumRows(): Int
        fun getNumColumns(): Int
        fun getColumnData(): List<List<String>>
        fun setColumnChar(col: Int, row: Int, ch: String)

        // Views
        fun getRootFrame(): FrameLayout
        fun getPoemCanvas(): PoemCanvasView
        fun getSelectionController(): SelectionController

        // Editor-driven actions delegated back to MainActivity
        fun pushHistory()
        fun pushHistoryBreakingBurst()
        fun maybePushHistoryForTyping()
        fun handleEnter()
        fun advanceToNextCell(idx: Int, total: Int)
    }

    val editor: EditText = EditText(context).apply {
        setTextColor(Color.BLACK)
        setBackgroundColor(Color.TRANSPARENT)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        visibility = View.GONE
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
        isFocusable = true
        isFocusableInTouchMode = true
        // Suppress the EditText's own selection action mode + long-press selection so the
        // system grey handles never appear. Selection is canvas-only (blue ovals); the live
        // editor is just for typing.
        isLongClickable = false
    }

    /** True while the controller is programmatically rewriting the editor's text. */
    var isResetting: Boolean = false
        private set

    /** Add the editor view to the root frame. Call once during MainActivity.onCreate(). */
    fun attach(rootFrame: FrameLayout) {
        rootFrame.addView(
            editor,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    /** Install the listeners. Call once after [attach]. */
    fun setupListeners() {
        editor.typeface = cb.getSelectedTypeface()
        editor.textSize = cb.getFontSizeSp()
        editor.setTextColor(cb.getGridTextColor())

        // Suppress every native EditText action mode (selection bar + insertion paste
        // bubble). Selection is handled exclusively by the canvas (blue ovals); we never
        // want the system grey handles competing with it.
        val suppressActionMode = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu) = false
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
        editor.customSelectionActionModeCallback = suppressActionMode
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            editor.customInsertionActionModeCallback = suppressActionMode
        }

        editor.setOnEditorActionListener { _, _, _ ->
            if (!cb.isLiveEditorActive()) return@setOnEditorActionListener false
            val text = editor.text?.toString() ?: ""
            if (text.isNotEmpty()) commitWord("$text ", text.length)
            cb.pushHistory()
            cb.handleEnter()
            updatePosition()
            true
        }

        editor.setOnKeyListener { _, keyCode, event ->
            if (!cb.isLiveEditorActive()) return@setOnKeyListener false
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_DEL && editor.text.isNullOrEmpty()) {
                // Backspace at empty editor: pop the previous cell's content.
                val prevIdx = cb.getFocusedCellIndex() - 1
                if (prevIdx >= 0) {
                    val nr = cb.getNumRows().coerceAtLeast(1)
                    val pCol = prevIdx / nr; val pRow = prevIdx % nr
                    val poemCanvas = cb.getPoemCanvas()
                    val columnData = cb.getColumnData()
                    val prevContent = columnData.getOrNull(pCol)?.getOrNull(pRow) ?: ""
                    cb.pushHistoryBreakingBurst()
                    if (prevContent.length > 1) {
                        cb.setColumnChar(pCol, pRow, prevContent.dropLast(1))
                        poemCanvas.refreshContent(columnData)
                    } else {
                        cb.setColumnChar(pCol, pRow, "")
                        poemCanvas.refreshContent(columnData)
                        cb.setFocusedCellIndex(prevIdx)
                        poemCanvas.setCursor(prevIdx)
                        updatePosition()
                    }
                }
                return@setOnKeyListener true
            }
            false
        }

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(editable: Editable?) {
                if (cb.isHostRestoring() || isResetting) return
                if (!cb.isLiveEditorActive()) return
                // Loop so a multi-word paste commits every word in one shot. commitWord does
                // a suppressed setText with the remainder; the watcher reentry returns early
                // on isResetting, so we re-read the editable here and keep committing until
                // only an in-progress (no-boundary) word remains.
                while (true) {
                    val text = editor.text?.toString() ?: return
                    val hasText = text.isNotEmpty()
                    editor.setBackgroundColor(if (hasText) cb.getBgColor() else Color.TRANSPARENT)
                    cb.getPoemCanvas().liveEditorCoversCell = hasText
                    if (!hasText) return
                    maybeWrapToNextLine()
                    val current = editor.text
                    val composing = current is Spanned && current.getSpans(0, current.length, Any::class.java)
                        .any { span -> (current as Spanned).getSpanFlags(span) and Spanned.SPAN_COMPOSING != 0 }
                    if (composing) return
                    val boundaryIdx = text.indexOfFirst {
                        !(it in 'a'..'z' || it in 'A'..'Z' || it == '\'')
                    }
                    if (boundaryIdx < 0) return
                    commitWord(text, boundaryIdx)
                }
            }
        })
    }

    /** Drop in-progress text and hide the editor — used by selection entry / session load. */
    fun clearAndHide() {
        isResetting = true
        editor.setText("")
        isResetting = false
        editor.visibility = View.GONE
        cb.getPoemCanvas().liveEditorOverlayActive = false
        cb.getPoemCanvas().liveEditorCoversCell = false
    }

    /** Drop in-progress text without changing visibility — used by mode/session resets. */
    fun clearText() {
        isResetting = true
        editor.setText("")
        isResetting = false
    }

    /** Live colour swap (bg = canvas bg, fg = text colour). */
    fun applyColors(bgColor: Int, textColor: Int) {
        editor.setBackgroundColor(bgColor)
        editor.setTextColor(textColor)
    }

    /**
     * Pre-commit line wrap. When the in-progress text would extend past the line's
     * pixel width, advance focusedCellIndex to the start of the next line so the user
     * sees the word relocate immediately instead of waiting for the space-press commit.
     *
     * Skipped when the cursor is already at the start of a line (row == 0), and only
     * the leading Latin run participates in the measurement — CJK / punctuation commit
     * one-per-cell, so cell-count advancement handles their wrapping naturally.
     */
    private fun maybeWrapToNextLine() {
        if (!cb.isLiveEditorActive()) return
        val nr = cb.getNumRows().coerceAtLeast(1)
        // FREESTYLE boxes have small numColumns; allow the cursor to roam past the box
        // (overflow cells are preserved on disk) by using the MAX_STORAGE_COLS ceiling.
        val totalCells = storageCellCount(nr)
        val focusedIdx = cb.getFocusedCellIndex()
        if (focusedIdx < 0 || focusedIdx >= totalCells) return
        val cs = cb.getCurrentCellSize()
        if (cs <= 0) return
        val text = editor.text?.toString() ?: return
        if (text.isEmpty()) return
        val row = focusedIdx % nr
        if (row == 0) return
        val leadingLatin = text.takeWhile { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' }
        if (leadingLatin.isEmpty()) return
        val paint = Paint().apply {
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, cb.getFontSizeSp(), context.resources.displayMetrics
            )
            typeface = cb.getSelectedTypeface()
        }
        val textWidth = paint.measureText(leadingLatin)
        val poemCanvas = cb.getPoemCanvas()
        val visualX = poemCanvas.visualXForCell(focusedIdx)?.toFloat() ?: 0f
        val baseX = if (cb.getCanvasMode() == CanvasMode.FREESTYLE) poemCanvas.activeBoxOffsetX else 0f
        val linePixelWidth = (nr * cs).toFloat()
        if (visualX - baseX + textWidth <= linePixelWidth) return
        val nextLineStart = ((focusedIdx / nr) + 1) * nr
        if (nextLineStart >= totalCells) return
        cb.setFocusedCellIndex(nextLineStart)
        poemCanvas.setCursor(nextLineStart)
        updatePosition()
    }

    /**
     * Commit the word in the editor up to [boundaryIdx], write the boundary character
     * to the next cell, advance focusedCellIndex past both, and reseed the editor with
     * any chars after the boundary so the next word can start mid-flight.
     */
    fun commitWord(text: String, boundaryIdx: Int) {
        val wordPart = text.substring(0, boundaryIdx)
        val boundaryChar = text[boundaryIdx].toString()
        val remainder = text.substring(boundaryIdx + 1)
        val nr = cb.getNumRows().coerceAtLeast(1)
        // Allow overflow past the visible box in FREESTYLE; commit can advance focus into
        // invisible cells which become visible again when the user enlarges the box.
        val totalCells = storageCellCount(nr)
        var index = cb.getFocusedCellIndex().coerceIn(0, totalCells - 1)

        // Visual-width wrap at commit time: if placing the word at the current cell would
        // push past the line's pixel width, advance focusedCellIndex to the first cell of
        // the next line BEFORE writing.
        //
        // visualXForCell returns an ABSOLUTE canvas-X (it folds in activeBoxOffsetX for
        // FREESTYLE). linePixelWidth is BOX-LOCAL (nr * cs). Subtract baseX so the two
        // values are in the same reference frame — without this, a box positioned far from
        // canvas origin would falsely conclude every word overflows and wrap each one onto
        // its own line.
        val cs = cb.getCurrentCellSize()
        if (wordPart.isNotEmpty() && cs > 0) {
            val poemCanvas = cb.getPoemCanvas()
            val linePixelWidth = nr * cs
            val visualX = poemCanvas.visualXForCell(index)?.toFloat() ?: 0f
            val baseX = if (cb.getCanvasMode() == CanvasMode.FREESTYLE) poemCanvas.activeBoxOffsetX else 0f
            val paint = Paint().apply {
                textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, cb.getFontSizeSp(), context.resources.displayMetrics
                )
                typeface = cb.getSelectedTypeface()
            }
            val wordWidth = paint.measureText(wordPart)
            val row = index % nr
            if (row > 0 && (visualX - baseX) + wordWidth > linePixelWidth) {
                val nextLineStart = ((index / nr) + 1) * nr
                if (nextLineStart < totalCells) {
                    index = nextLineStart
                    cb.setFocusedCellIndex(index)
                }
            }
        }
        val cellCol = index / nr
        val cellRow = index % nr
        cb.maybePushHistoryForTyping()
        if (wordPart.isNotEmpty()) cb.setColumnChar(cellCol, cellRow, wordPart)
        val boundaryCell = if (wordPart.isNotEmpty()) index + 1 else index
        if (boundaryCell < totalCells) {
            // Tab from a pasted clipboard string maps back to an empty cell (the original
            // tab-as-cursor-advance), not a literal "\t" character in the cell.
            val cellValue = if (boundaryChar == "\t") "" else boundaryChar
            cb.setColumnChar(boundaryCell / nr, boundaryCell % nr, cellValue)
        }
        cb.getPoemCanvas().refreshContent(cb.getColumnData())
        isResetting = true
        editor.setText(remainder)
        editor.setSelection(remainder.length)
        isResetting = false
        cb.advanceToNextCell(boundaryCell, totalCells)
        updatePosition()
    }

    /** Position the editor at the focused cell's pixel location within rootFrame. */
    fun updatePosition() {
        val poemCanvas = cb.getPoemCanvas()
        val cs = cb.getCurrentCellSize()
        if (!cb.isLiveEditorActive() || cb.getNumRows() <= 0 || cs <= 0) {
            editor.visibility = View.GONE
            poemCanvas.liveEditorOverlayActive = false
            poemCanvas.liveEditorCoversCell = false
            return
        }
        val cellRect = poemCanvas.cellRect(cb.getFocusedCellIndex()) ?: run {
            editor.visibility = View.GONE
            poemCanvas.liveEditorOverlayActive = false
            poemCanvas.liveEditorCoversCell = false
            return
        }
        // Use the canvas's visual-X so the overlay aligns with the rendered text (Latin
        // word cells render narrower than cellSize, packing later cells tighter than the
        // grid would imply).
        val visualXCanvas = poemCanvas.visualXForCell(cb.getFocusedCellIndex()) ?: cellRect.left
        val rect = Rect(visualXCanvas, cellRect.top, visualXCanvas + cs, cellRect.bottom)
        cb.getRootFrame().offsetDescendantRectToMyCoords(poemCanvas, rect)
        val params = editor.layoutParams as FrameLayout.LayoutParams
        params.leftMargin = rect.left
        params.topMargin = rect.top
        // WRAP_CONTENT width with a minimum of cellSize so the editor only intercepts
        // touches over its own typed text — surrounding canvas cells stay tappable.
        params.width = ViewGroup.LayoutParams.WRAP_CONTENT
        params.height = cs
        editor.layoutParams = params
        editor.minWidth = cs
        editor.typeface = cb.getSelectedTypeface()
        editor.textSize = cb.getFontSizeSp()
        editor.setTextColor(cb.getGridTextColor())
        val hasText = !editor.text.isNullOrEmpty()
        editor.setBackgroundColor(if (hasText) cb.getBgColor() else Color.TRANSPARENT)
        editor.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        editor.visibility = View.VISIBLE
        poemCanvas.liveEditorOverlayActive = true
        poemCanvas.liveEditorCoversCell = hasText
    }

    /** True if the editor currently has text the user hasn't committed yet. */
    fun hasPendingText(): Boolean = !editor.text.isNullOrEmpty()

    /** Snapshot of the editor's current in-progress text. */
    fun getPendingText(): String = editor.text?.toString() ?: ""

    /**
     * Universal storage ceiling for cell advancement. HORIZONTAL canvas already has
     * numColumns >= MAX_STORAGE_COLS, so this is a no-op there; FREESTYLE-horizontal uses
     * a small numColumns (line count), and we want typing to overflow the box rather than
     * be blocked, so we round up to the same ceiling MainActivity uses.
     */
    private fun storageCellCount(nr: Int): Int =
        maxOf(cb.getNumColumns(), MAX_STORAGE_COLS).coerceAtLeast(1) * nr

    companion object {
        /** Mirrors MainActivity.MAX_COLUMNS — the universal column-storage ceiling. */
        private const val MAX_STORAGE_COLS = 50
    }
}
