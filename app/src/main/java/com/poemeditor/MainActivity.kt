package com.poemeditor

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.ViewModelProvider
import kotlin.math.roundToInt

enum class InputMode { SCATTER, SEQUENTIAL }

private typealias SessionMeta = SessionManager.SessionMeta

class MainActivity : AppCompatActivity(), ViewFactory.Callbacks {

    // ── Settings ───────────────────────────────────────────────────────
    private var fontSizeSp       = 24f
    private var bgColor          = Color.WHITE
    // gridTextColor is independently controlled via the text-colour panel;
    // it is never auto-derived from bgColor.
    private var gridTextColor    = Color.BLACK
    private var selectedTypeface = Typeface.DEFAULT
    private var wordGapDp        = 3f
    private var inputMode        = InputMode.SEQUENTIAL

    // ── Widget refs for programmatic sync (session load / new) ─────────
    private var fontSpinnerRef: Spinner? = null
    private var fontSizeLabelRef: TextView? = null
    private var gapValueLabelRef: TextView? = null
    private var modeChipContainer: LinearLayout? = null

    // ── Background image ───────────────────────────────────────────────
    private var bgImageView: ImageView? = null
    private var bgImageUri: String? = null
    private val insertedImages = mutableListOf<InsertedImageState>()
    private var activeImageIndex = -1
    private val MAX_INSERTED_IMAGES get() = SessionManager.MAX_INSERTED_IMAGES
    private lateinit var imagePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var sessionListLauncher: ActivityResultLauncher<Intent>

    // ── Document management ────────────────────────────────────────────
    private var currentSessionId: String = java.util.UUID.randomUUID().toString()
    private var currentSessionName: String = "文檔"
    private var docFileNameRef: TextView? = null

    // ── UI refs ────────────────────────────────────────────────────────
    private lateinit var rootFrame:             FrameLayout
    private lateinit var gridContainer:         LinearLayout
    private lateinit var allToolsPanel:         LinearLayout
    private var toolsCell:                      LinearLayout? = null
    private lateinit var bottomPanel:           LinearLayout
    private lateinit var mainLayout:            LinearLayout
    private lateinit var mainScrollView:        NestedScrollView
    private lateinit var punctToolbar:          HorizontalScrollView
    private lateinit var viewFactory:           ViewFactory

    // ── Persistence ────────────────────────────────────────────────────
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var editorViewModel: EditorViewModel

    // ── Grid state ─────────────────────────────────────────────────────
    private lateinit var poemCanvas: PoemCanvasView
    private lateinit var ghostInput: EditText
    private val GHOST_SENTINEL = "‌"   // zero-width non-joiner; invisible sentinel in ghost EditText
    private var numRows     = 0
    private var numColumns  = 0
    private var isRestoring  = false
    private var isPreviewing = false
    private var hScroll: HorizontalScrollView? = null
    private val MAX_COLUMNS = 50
    private val FRONTIER_MARKER get() = GridLogicHelper.FRONTIER_MARKER
    private val LINE_END_MARKER get() = GridLogicHelper.LINE_END_MARKER
    private var currentCellSize = 0
    private var focusedCellIndex = -1  // index of the next insertion point; cursor drawn at top of this cell
    // columnData[col][row] = char at that cell; "" = empty.
    private val columnData = mutableListOf<MutableList<String>>()
    // Columns that begin with an explicit '\n' break (vs. auto-wrap).
    // Preserved across reflow so stanza structure survives gap/font changes.
    private val columnBreaks = mutableSetOf<Int>()
    private var needsReflow = false
    private var stableMaxHeight     = 0    // locked once at startup (IME hidden); used for all numRows calculations
    private var gridPaddingTopPx    = 0
    private var gridPaddingBottomPx = 0
    private var gridPaddingLeftPx   = 0
    private var gridPaddingRightPx  = 0
    private var lastKeyboardHeight = 0   // last measured IME height; allToolsPanel matches this height
    private var toolsVisible = false     // true when allToolsPanel is showing instead of keyboard
    private val translateHandler by lazy { Handler(Looper.getMainLooper()) }
    private var translateRunnable: Runnable? = null
    private val historyBurstHandler = Handler(Looper.getMainLooper())
    private var historyBurstReset: Runnable? = null
    private var historyBurstActive = false

    // ── Selection state ────────────────────────────────────────────────
    private var isSelecting      = false
    private var selectionStart   = -1
    private var selectionEnd     = -1
    private var selectionOptionsView: LinearLayout? = null
    private var startHandle:     View? = null
    private var endHandle:       View? = null
    private var activeDragHandle: Boolean? = null  // true=start handle, false=end handle
    private var handleDragged    = false           // true once finger moves ≥1 cell while on a handle
    private var handlePasteView: TextView? = null  // mini bubble shown on handle tap (no drag)
    private var tappedHandleIsStart: Boolean? = null

    // ── Selection drag cache (populated once on ACTION_DOWN) ───────────
    private val cachedGridLoc  = IntArray(2)
    private val cachedRootLoc  = IntArray(2)
    private var cachedCellSize = 0
    private var cachedGridW    = 0
    // Highlight range tracked to diff-update only changed cells
    private var highlightFrom  = -1
    private var highlightTo    = -1
    // Popup dimensions cached after first measure so ACTION_MOVE skips measure()
    private var cachedPopupW   = -1
    private var cachedPopupH   = -1
    // True when a postOnAnimation frame is already scheduled for this drag gesture
    private var isFrameScheduled = false

    // ── Undo / Redo ────────────────────────────────────────────────────
    private var undoButton: TextView? = null
    private var redoButton: TextView? = null

    // ── Inserted image overlay ─────────────────────────────────────────
    private val bgImageMatrix = Matrix()
    private val bgImageViews = mutableListOf<ImageView>()
    private var imageGestureActive = false
    private var imageGestureStartDist = 0f
    private var imageGestureStartMidX = 0f
    private var imageGestureStartMidY = 0f
    private val imageGestureMatrix = Matrix()
    private var pendingBgImageMatrix: FloatArray? = null
    private var insertImageContainer: LinearLayout? = null

    private fun setColumnChar(col: Int, row: Int, ch: String) =
        GridLogicHelper.setColumnChar(columnData, col, row, ch)

    private fun clearDocumentContent() {
        columnData.clear()
        columnBreaks.clear()
    }

    private fun columnDataToJson() = SessionManager.columnDataToJson(columnData)

    private fun columnBreaksToJson() = SessionManager.columnBreaksToJson(columnBreaks)

    private fun loadColumnDataFromJson(cols: org.json.JSONArray) {
        columnData.clear(); columnData.addAll(SessionManager.loadColumnDataFromJson(cols))
    }

    private fun loadColumnBreaksFromJson(breaks: org.json.JSONArray) {
        columnBreaks.clear(); columnBreaks.addAll(SessionManager.loadColumnBreaksFromJson(breaks))
    }

    private fun cloneMatrix(values: FloatArray?): FloatArray? = values?.copyOf()

    private fun copyInsertedImagesState(): List<InsertedImageState> =
        insertedImages.map { InsertedImageState(it.uri, cloneMatrix(it.matrix)) }

    private fun parseInsertedImages(json: org.json.JSONArray?) =
        SessionManager.parseInsertedImages(json)

    private fun insertedImagesToJsonString(images: List<InsertedImageState>) =
        SessionManager.insertedImagesToJsonString(images)

    private fun updateActiveImageRuntimeState() {
        if (activeImageIndex !in insertedImages.indices) return
        val currentUri = bgImageUri ?: return
        if (insertedImages[activeImageIndex].uri != currentUri) return
        insertedImages[activeImageIndex] = InsertedImageState(currentUri, getBgImageMatrixValues())
    }

    private fun loadImageBitmap(uriStr: String): Bitmap? {
        val uri = try { Uri.parse(uriStr) } catch (_: Exception) { return null }
        return try {
            val stream = when (uri.scheme) {
                "file" -> java.io.FileInputStream(java.io.File(uri.path ?: return null))
                else   -> contentResolver.openInputStream(uri)
            } ?: return null
            stream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
    }

    private fun defaultImageMatrixValues(imgW: Int, imgH: Int, index: Int): FloatArray {
        val metrics = resources.displayMetrics
        val viewW = rootFrame.width.toFloat().takeIf { it > 0f } ?: metrics.widthPixels.toFloat()
        val viewH = mainScrollView.height.toFloat().takeIf { it > 0f } ?: (metrics.heightPixels * 0.7f)

        val pad = 14f * metrics.density
        val gap = 10f * metrics.density
        val columns = 3
        val col = index % columns
        val row = index / columns

        val slotW = ((viewW - pad * 2f - gap * (columns - 1)) / columns).coerceAtLeast(1f)
        val slotH = (viewH * 0.32f).coerceAtLeast(1f)
        val scale = minOf(slotW / imgW, slotH / imgH).coerceAtLeast(0.05f)

        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(pad + col * (slotW + gap), pad + row * (slotH + gap))
        }
        return FloatArray(9).also { matrix.getValues(it) }
    }

    private fun renderInsertedImages() {
        bgImageViews.forEach { rootFrame.removeView(it) }
        bgImageViews.clear()
        bgImageView = null
        bgImageUri = null
        bgImageMatrix.reset()

        if (insertedImages.isEmpty()) {
            activeImageIndex = -1
            return
        }

        activeImageIndex = activeImageIndex.coerceIn(0, insertedImages.lastIndex)
        insertedImages.forEachIndexed { idx, state ->
            val bmp = loadImageBitmap(state.uri) ?: return@forEachIndexed
            val view = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(MP, MP)
                scaleType = ImageView.ScaleType.MATRIX
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageBitmap(bmp)
            }

            val matrixValues = cloneMatrix(state.matrix) ?: defaultImageMatrixValues(bmp.width, bmp.height, idx)
            val matrix = Matrix().apply { setValues(matrixValues) }
            view.imageMatrix = matrix

            rootFrame.addView(view, bgImageViews.size)
            bgImageViews.add(view)

            if (state.matrix == null && idx in insertedImages.indices) {
                insertedImages[idx] = InsertedImageState(state.uri, cloneMatrix(matrixValues))
            }

            if (idx == activeImageIndex) {
                bgImageView = view
                bgImageUri = state.uri
                bgImageMatrix.setValues(matrixValues)
            }
        }

        if (bgImageView == null) {
            bgImageUri = null
            bgImageMatrix.reset()
        }
    }

    private fun findTouchedImageIndex(rootX: Float, rootY: Float): Int {
        if (insertedImages.isEmpty() || bgImageViews.isEmpty()) return -1
        val ordered = bgImageViews.indices.sortedByDescending { idx ->
            rootFrame.indexOfChild(bgImageViews[idx])
        }
        for (idx in ordered) {
            val view = bgImageViews.getOrNull(idx) ?: continue
            if (view.visibility != View.VISIBLE) continue
            val drawable = view.drawable ?: continue
            val dw = drawable.intrinsicWidth.toFloat().takeIf { it > 0f } ?: continue
            val dh = drawable.intrinsicHeight.toFloat().takeIf { it > 0f } ?: continue
            val inverse = Matrix()
            if (!view.imageMatrix.invert(inverse)) continue
            val p = floatArrayOf(rootX, rootY)
            inverse.mapPoints(p)
            if (p[0] >= 0f && p[0] <= dw && p[1] >= 0f && p[1] <= dh) return idx
        }
        return -1
    }

    private fun activateImageAt(index: Int) {
        if (index !in insertedImages.indices) return
        val view = bgImageViews.getOrNull(index) ?: return
        updateActiveImageRuntimeState()
        activeImageIndex = index
        bgImageView = view
        bgImageUri = insertedImages[index].uri
        bgImageMatrix.set(view.imageMatrix)
        // Bring selected image to front among image views (below mainLayout)
        if (bgImageViews.size > 1) {
            rootFrame.removeView(view)
            rootFrame.addView(view, bgImageViews.size - 1)
        }
    }

    private fun syncActiveImageFromList() {
        renderInsertedImages()
    }

    private fun updateToolbarSessionName() {
        docFileNameRef?.text = currentSessionName
    }

    private inline fun withRestoring(block: () -> Unit) {
        isRestoring = true
        try {
            block()
        } finally {
            isRestoring = false
        }
    }

    private fun scrollToColumn(col: Int) {
        val s = hScroll ?: return
        if (currentCellSize <= 0 || s.width <= 0 || numColumns <= 0) return
        val gw = maxOf(numColumns * currentCellSize, s.width)
        val physLeft  = gw - (col + 1) * currentCellSize
        val physRight = gw - col * currentCellSize
        val sx = s.scrollX; val vp = s.width
        when {
            physLeft  < sx       -> s.smoothScrollTo(physLeft, 0)
            physRight > sx + vp  -> s.smoothScrollTo(physRight - vp, 0)
        }
    }

    private fun persistCurrentState() {
        saveSession()
        saveState()
    }

    private fun advanceToNextCell(index: Int, total: Int) {
        val nr = numRows.coerceAtLeast(1)
        val nextIdx = (index + 1).coerceAtMost(total - 1)
        val prevCol = index / nr
        val nextCol = nextIdx / nr
        focusedCellIndex = nextIdx
        poemCanvas.setCursor(nextIdx)
        scrollToTopIfCursorInUpperView(nextIdx)
        scrollToColumn(nextCol)
        if (nextCol != prevCol) mainScrollView.scrollTo(0, 0)
        placeFrontierMarker()
    }

    private fun postRefreshFocusColumn(targetIndex: Int, col: Int = targetIndex / numRows) {
        gridContainer.post {
            refreshGrid()
            focusCell(targetIndex)
            scrollToColumn(col)
        }
    }

    private fun scrollToTopIfCursorInUpperView(index: Int) {
        if (!::poemCanvas.isInitialized || !::mainScrollView.isInitialized) return
        val cellRect = poemCanvas.cellRect(index.coerceAtLeast(0)) ?: return
        val upperViewLimit = mainScrollView.height * 0.35f
        if (cellRect.top <= upperViewLimit) {
            mainScrollView.post { mainScrollView.scrollTo(0, 0) }
        }
    }

    // Sets focusedCellIndex and draws the cursor at the top of that cell.
    // The cursor bar sits between (index-1) and index; typing inserts AT index.
    // showKeyboard: true  = open IME; false = keep IME closed (style rebuilds).
    private fun focusCell(index: Int, showKeyboard: Boolean = true) {
        val col = index / numRows.coerceAtLeast(1)
        if (col >= numColumns) return
        focusedCellIndex = index
        poemCanvas.setCursor(index)
        scrollToTopIfCursorInUpperView(index)
        poemCanvas.startCursorBlink()
        ghostInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (showKeyboard) {
            imm.showSoftInput(ghostInput, InputMethodManager.SHOW_IMPLICIT)
            ghostInput.postDelayed({ imm.restartInput(ghostInput) }, 100)
        }
        scheduleTranslateForKeyboard()
    }

    // Debounced entry-point for translateForKeyboard.  Cancels any pending call and
    // reschedules 100 ms out so rapid focus changes don't pile up and the keyboard
    // animation has time to finish before we measure rect.bottom.
    private fun scheduleTranslateForKeyboard() {
        translateRunnable?.let { translateHandler.removeCallbacks(it) }
        translateRunnable = Runnable { translateForKeyboard() }
            .also { translateHandler.postDelayed(it, 100) }
    }

    // Scrolls mainScrollView so the focused cell clears the keyboard.
    // Mechanism: paddingBottom = kbdHeight creates extra scroll room at the bottom
    // (clipToPadding=false makes it reachable), then smoothScrollBy brings the cell
    // into view.  No translationY — the window size stays constant (adjustNothing).
    private fun translateForKeyboard() {
        val rect = Rect()
        window.decorView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = resources.displayMetrics.heightPixels
        val kbdHeight = screenHeight - rect.bottom

        if (kbdHeight <= 0) {
            mainScrollView.setPadding(0, 0, 0, 0)
            return
        }

        mainScrollView.setPadding(0, 0, 0, kbdHeight)

        val cellRect = poemCanvas.cellRect(focusedCellIndex.coerceAtLeast(0)) ?: return
        val canvasLoc = IntArray(2); poemCanvas.getLocationOnScreen(canvasLoc)
        val cellBottom = canvasLoc[1] + cellRect.bottom
        if (cellBottom > rect.bottom) {
            val delta = cellBottom - rect.bottom + rect.height() * 0.3f
            mainScrollView.smoothScrollBy(0, delta.toInt())
        }
    }

    private val MP get() = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC get() = ViewGroup.LayoutParams.WRAP_CONTENT

    // ── Font catalogue ─────────────────────────────────────────────────
    private fun loadFont(
        assetNames:   List<String> = emptyList(),
        systemPaths:  List<String> = emptyList(),
        fallbackFamily: String,
        fallbackStyle:  Int = Typeface.NORMAL
    ): Typeface {
        for (name in assetNames) {
            try { return Typeface.createFromAsset(assets, "fonts/$name") } catch (_: Exception) {}
        }
        for (path in systemPaths) {
            try {
                val f = java.io.File(path)
                if (f.exists() && f.canRead()) return Typeface.createFromFile(f)
            } catch (_: Exception) {}
        }
        return Typeface.create(fallbackFamily, fallbackStyle)
    }

    private val fontCatalogue: List<FontEntry> by lazy {
        listOf(
            FontEntry("黑體", Typeface.create("sans-serif", Typeface.NORMAL)),
            FontEntry("宋體", Typeface.create("serif",      Typeface.NORMAL)),
            FontEntry("霞鶩文楷", loadFont(
                assetNames  = listOf("LXGWWenKai-Regular.ttf"),
                systemPaths = listOf(
                    "/system/fonts/DroidSansKaiTi.ttf",
                    "/system/fonts/FZKTJW.TTF",
                    "/system/fonts/Kai.ttf",
                ),
                fallbackFamily = "serif", fallbackStyle = Typeface.NORMAL
            )),
            FontEntry("仿宋", loadFont(
                assetNames  = listOf("LXGWWenKai-Light.ttf", "ZCOOLXiaoWei-Regular.ttf"),
                systemPaths = listOf(
                    "/system/fonts/DroidSansFangSong.ttf",
                    "/system/fonts/FZFSJW.TTF",
                    "/system/fonts/FangSong.ttf",
                ),
                fallbackFamily = "serif", fallbackStyle = Typeface.ITALIC
            )),
            FontEntry("粗黑", Typeface.create("sans-serif", Typeface.BOLD)),
            FontEntry("等寬", Typeface.create("monospace",  Typeface.NORMAL)),
        )
    }
    private var fontIndex = 0

    // ── onCreate ───────────────────────────────────────────────────────
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // ── Inserted image pan/zoom (2-finger) ────────────────────────────
        if (bgImageViews.isNotEmpty()) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount == 2) {
                        val rootLoc = IntArray(2); rootFrame.getLocationOnScreen(rootLoc)
                        val midX = (ev.getX(0) + ev.getX(1)) / 2f - rootLoc[0]
                        val midY = (ev.getY(0) + ev.getY(1)) / 2f - rootLoc[1]
                        val touchedIndex = findTouchedImageIndex(midX, midY)
                        if (touchedIndex < 0) {
                            imageGestureActive = false
                            return super.dispatchTouchEvent(ev)
                        }
                        activateImageAt(touchedIndex)
                        imageGestureStartMidX = midX
                        imageGestureStartMidY = midY
                        imageGestureStartDist = Math.hypot(
                            (ev.getX(1) - ev.getX(0)).toDouble(),
                            (ev.getY(1) - ev.getY(0)).toDouble()).toFloat()
                        imageGestureMatrix.set(bgImageMatrix)
                        imageGestureActive = true
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (imageGestureActive && ev.pointerCount >= 2) {
                        val rootLoc = IntArray(2); rootFrame.getLocationOnScreen(rootLoc)
                        val newMidX = (ev.getX(0) + ev.getX(1)) / 2f - rootLoc[0]
                        val newMidY = (ev.getY(0) + ev.getY(1)) / 2f - rootLoc[1]
                        val newDist = Math.hypot(
                            (ev.getX(1) - ev.getX(0)).toDouble(),
                            (ev.getY(1) - ev.getY(0)).toDouble()).toFloat()
                        val scaleFactor = if (imageGestureStartDist > 0f) newDist / imageGestureStartDist else 1f
                        val m = Matrix(imageGestureMatrix)
                        m.postScale(scaleFactor, scaleFactor, imageGestureStartMidX, imageGestureStartMidY)
                        m.postTranslate(newMidX - imageGestureStartMidX, newMidY - imageGestureStartMidY)
                        bgImageMatrix.set(m)
                        bgImageView?.imageMatrix = bgImageMatrix
                        return true
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (imageGestureActive) {
                        imageGestureActive = false
                        persistCurrentState()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (imageGestureActive) {
                        imageGestureActive = false
                        persistCurrentState()
                    }
                }
            }
        }

        if (isSelecting && ev.pointerCount == 1) {
            val sh = startHandle; val eh = endHandle
            if (sh != null && eh != null && sh.visibility == View.VISIBLE) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        handleDragged = false
                        handlePasteView?.visibility = View.GONE
                        // Measure once: root offset and touch-in-rootFrame coords
                        rootFrame.getLocationOnScreen(cachedRootLoc)
                        val tx = ev.rawX - cachedRootLoc[0]; val ty = ev.rawY - cachedRootLoc[1]
                        val hitR = 36f * resources.displayMetrics.density
                        fun hcx(h: View) = h.x + (h.width.takeIf { it > 0 } ?: h.layoutParams.width) / 2f
                        fun hcy(h: View) = h.y + (h.height.takeIf { it > 0 } ?: h.layoutParams.height) / 2f
                        activeDragHandle = when {
                            Math.hypot((tx - hcx(sh)).toDouble(), (ty - hcy(sh)).toDouble()) <= hitR -> true
                            Math.hypot((tx - hcx(eh)).toDouble(), (ty - hcy(eh)).toDouble()) <= hitR -> false
                            else -> null
                        }
                        if (activeDragHandle != null) {
                            cachedCellSize = currentCellSize
                            return true  // consume DOWN so cells underneath don't clear selection
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dh = activeDragHandle
                        if (dh != null) {
                            val hit = fastCellIndexAtPoint(ev.rawX, ev.rawY)
                            if (hit >= 0) {
                                val prev = if (dh) selectionStart else selectionEnd
                                if (hit != prev) {
                                    handleDragged = true
                                    // ── Logic only: update indices, no UI work ──────────
                                    if (dh) selectionStart = hit else selectionEnd = hit
                                    // ── Schedule one render per vsync ──────────────────
                                    if (!isFrameScheduled) {
                                        isFrameScheduled = true
                                        rootFrame.postOnAnimation {
                                            if (isSelecting) {
                                                val activeDh = activeDragHandle
                                                val total = (MAX_COLUMNS * numRows - 1).coerceAtLeast(0)
                                                val newFrom = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
                                                val newTo   = maxOf(selectionStart, selectionEnd).coerceAtMost(total)
                                                updateHighlightDiff(newFrom, newTo)
                                                if (activeDh != null) {
                                                    val draggedIdx = (if (activeDh) selectionStart else selectionEnd)
                                                        .coerceIn(0, total)
                                                    val otherIdx   = (if (activeDh) selectionEnd   else selectionStart)
                                                        .coerceIn(0, total)
                                                    positionHandleAt(
                                                        if (activeDh) startHandle else endHandle,
                                                        draggedIdx, atTop = (draggedIdx == newFrom))
                                                    positionHandleAt(
                                                        if (activeDh) endHandle else startHandle,
                                                        otherIdx, atTop = (otherIdx == newFrom))
                                                }
                                                positionSelectionOptionsView()
                                            }
                                            isFrameScheduled = false
                                        }
                                    }
                                }
                            }
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dh = activeDragHandle
                        val dragged = handleDragged
                        activeDragHandle = null
                        handleDragged = false
                        if (dh != null) {
                            // Tap on handle (no real drag) → show paste bubble only if options bar is hidden
                            if (!dragged && ev.actionMasked == MotionEvent.ACTION_UP
                                    && selectionOptionsView?.visibility != View.VISIBLE) {
                                val handle = if (dh) startHandle else endHandle
                                if (handle != null) showHandlePasteMenu(handle, isStart = dh)
                            }
                            return true  // consume — prevent cell underneath from dismissing selection
                        }
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density

        prefs = getSharedPreferences("poem_editor_state", Context.MODE_PRIVATE)
        editorViewModel = ViewModelProvider(
            this,
            EditorViewModel.Factory(SessionRepository(filesDir))
        )[EditorViewModel::class.java]
        loadSettings()
        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) applyInsertedImage(uri)
        }
        sessionListLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val renamedName = data.getStringExtra("renamed_current_name")
                if (renamedName != null) {
                    currentSessionName = renamedName
                    updateToolbarSessionName()
                    prefs.edit().putString("current_session_name", renamedName).apply()
                }
                val id = data.getStringExtra("session_id") ?: return@registerForActivityResult
                loadSessionFile(id)
            }
        }

        rootFrame = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(MP, MP)
            setBackgroundColor(bgColor)
            // Do not intercept touches at the root level — all interaction belongs to children.
            // This is especially important when mainLayout is translated upward (keyboard push-up)
            // so that taps on the visible portion of the screen reach the correct child views.
            isClickable = false
            isFocusable = false
        }

        // Main vertical layout: mainScrollView fills remaining space above bottomPanel.
        // adjustNothing keeps window size stable when keyboard opens.
        // translateForKeyboard() adds paddingBottom to mainScrollView equal to keyboard
        // height, then smoothScrollBy to bring the focused cell above the keyboard.
        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MP, MP)
        }

        gridContainer = LinearLayout(this).apply {
            gravity = Gravity.TOP or Gravity.END
            layoutParams = LinearLayout.LayoutParams(MP, WC)   // WC: sized by content
            setPadding(gridPaddingLeftPx, gridPaddingTopPx, gridPaddingRightPx, gridPaddingBottomPx)
        }
        // NestedScrollView gives vertical scrollability; paddingBottom = kbdHeight creates
        // extra scroll room so the last grid row can be scrolled above the keyboard.
        // clipToPadding=false makes that padding region reachable by scrolling.
        mainScrollView = NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MP, 0, 1f)  // fill remaining space
            isFillViewport = true
            clipToPadding  = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(gridContainer)
        }

        // ── Canvas + ghost input ────────────────────────────────────────
        poemCanvas = PoemCanvasView(this)
        ghostInput = EditText(this).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            alpha = 0f
            background = null
            isCursorVisible = false
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            privateImeOptions = "nm"
            isFocusable = true
            isFocusableInTouchMode = true
        }

        hScroll = HorizontalScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(poemCanvas)
        }
        hScroll?.setOnScrollChangeListener { _, _, _, _, _ ->
            if (isSelecting) repositionHandles()
            viewFactory.updateScrollIndicator()
        }
        gridContainer.addView(hScroll)

        viewFactory      = ViewFactory(this, this)
        bottomPanel      = viewFactory.buildBottomPanel(dp)
        allToolsPanel    = viewFactory.allToolsPanel!!
        punctToolbar     = viewFactory.punctToolbar!!
        toolsCell        = viewFactory.toolsCell
        docFileNameRef    = viewFactory.docFileNameRef
        fontSpinnerRef    = viewFactory.fontSpinnerRef
        fontSizeLabelRef  = viewFactory.fontSizeLabelRef
        gapValueLabelRef  = viewFactory.gapValueLabelRef
        modeChipContainer = viewFactory.modeChipContainer
        undoButton        = viewFactory.undoButton
        redoButton        = viewFactory.redoButton
        insertImageContainer = viewFactory.insertImageContainer
        updateToolbarSessionName()
        mainLayout.addView(viewFactory.buildScrollIndicator(dp))
        mainLayout.addView(mainScrollView)
        mainLayout.addView(bottomPanel)
        rootFrame.addView(mainLayout)
        rootFrame.addView(allToolsPanel)
        setContentView(rootFrame)
        val dp0 = resources.displayMetrics.density
        startHandle = viewFactory.buildSelectionHandle(dp0).also { rootFrame.addView(it) }
        endHandle   = viewFactory.buildSelectionHandle(dp0).also { rootFrame.addView(it) }
        selectionOptionsView = viewFactory.buildSelectionOptionsView(dp0).also { rootFrame.addView(it) }
        handlePasteView = viewFactory.buildHandlePasteView(dp0).also { rootFrame.addView(it) }
        rootFrame.addView(ghostInput)
        setupGhostInput()
        setupCanvasTouchListener()
        syncActiveImageFromList()

        // Track keyboard height and manage scroll padding in sync with IME state.
        ViewCompat.setOnApplyWindowInsetsListener(rootFrame) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (imeVisible && imeHeight > 0) lastKeyboardHeight = imeHeight
            punctToolbar.visibility = if (imeVisible) View.VISIBLE else View.GONE
            if (imeVisible) {
                // Keyboard opened — dismiss tools panel and selection if active.
                if (toolsVisible) {
                    allToolsPanel.visibility = View.GONE
                    toolsVisible = false
                    toolsCell?.setBackgroundColor(Color.TRANSPARENT)
                }
                if (isSelecting) clearSelection()
                scheduleTranslateForKeyboard()
            } else if (!toolsVisible) {
                // Keyboard closed and tools not shown — clear scroll padding.
                mainScrollView.setPadding(0, 0, 0, 0)
                mainScrollView.smoothScrollTo(0, 0)
            }
            insets
        }

        loadState()
        ensureDefaultSession()

        // One-shot: build the grid once the container has real dimensions.
        // Settings changes (font size, gap, etc.) call rebuildGrid() explicitly.
        // We do NOT rebuild on keyboard open/close — that would destroy the focused
        // EditText and cause the keyboard to bounce.
        mainScrollView.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val imeVisible = ViewCompat.getRootWindowInsets(rootFrame)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    if (stableMaxHeight == 0 && !imeVisible) {
                        val h = mainScrollView.height - gridContainer.paddingTop - gridContainer.paddingBottom
                        if (h > 0) stableMaxHeight = h
                    }
                    if (mainScrollView.width > 0 && mainScrollView.height > 0 && numRows == 0) {
                        // Defer one frame so the activity shell is drawn before we allocate
                        // cells on the UI thread — reduces visible startup lag.
                        mainScrollView.post { rebuildGrid(isInitialBoot = true, scrollToStart = true) }
                    }
                }
            }
        )
    }


    private fun showHandlePasteMenu(handle: View, isStart: Boolean) {
        val pv = handlePasteView ?: return
        tappedHandleIsStart = isStart
        pv.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val pvW = pv.measuredWidth.toFloat()
        val pvH = pv.measuredHeight.toFloat()
        val dp = resources.displayMetrics.density
        val gap = 6f * dp
        val handleSz = handle.layoutParams.width.toFloat()
        var pvX = handle.x + handleSz / 2f - pvW / 2f
        var pvY = handle.y - pvH - gap
        val rootW = rootFrame.width.toFloat()
        pvX = pvX.coerceIn(gap, (rootW - pvW - gap).coerceAtLeast(gap))
        if (pvY < gap) pvY = handle.y + handleSz + gap
        pv.x = pvX; pv.y = pvY
        pv.visibility = View.VISIBLE
    }

    private fun positionSelectionOptionsView() {
        val pop = selectionOptionsView ?: return
        if (!isSelecting || !::poemCanvas.isInitialized) return
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val cellRect = poemCanvas.cellRect(from) ?: return
        if (cachedPopupW < 0) {
            pop.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            cachedPopupW = pop.measuredWidth; cachedPopupH = pop.measuredHeight
        }
        val popW = cachedPopupW.toFloat()
        val dp  = resources.displayMetrics.density
        val gap = 8f * dp

        val canvasLoc = IntArray(2); poemCanvas.getLocationOnScreen(canvasLoc)
        val rootLoc   = IntArray(2); rootFrame.getLocationOnScreen(rootLoc)
        val cellLeft = (canvasLoc[0] - rootLoc[0] + cellRect.left).toFloat()
        val cellTop  = (canvasLoc[1] - rootLoc[1] + cellRect.top).toFloat()

        var popX = cellLeft - popW - gap
        if (popX < gap) popX = cellLeft + cellRect.width() + gap
        val rootW = rootFrame.width.toFloat()
        popX = popX.coerceIn(gap, (rootW - popW - gap).coerceAtLeast(gap))
        val popY = cellTop.coerceIn(gap, (rootFrame.height - cachedPopupH - gap).coerceAtLeast(gap))

        pop.x = popX; pop.y = popY
        pop.visibility = View.VISIBLE
    }


    private fun insertPunct(punct: String) {
        pushHistory()
        val idx = focusedCellIndex.coerceAtLeast(0)
        val nr = numRows.coerceAtLeast(1)
        val cellCol = idx / nr
        val cellRow = idx % nr
        val originalChar = columnData.getOrNull(cellCol)?.getOrNull(cellRow) ?: ""
        if (originalChar.isEmpty() || (inputMode == InputMode.SCATTER && originalChar.isBlank())) {
            setColumnChar(cellCol, cellRow, punct)
            poemCanvas.refreshContent(columnData)
            advanceToNextCell(idx, MAX_COLUMNS * numRows)
        } else {
            makeRoomBeforeParagraphBreakForLineEndInsert(cellCol, cellRow, punct.length)
            val focusTarget = performInsert(punct) ?: return
            postRefreshFocusColumn(focusTarget)
        }
    }

    // Apply background colour.  gridTextColor is NOT touched — it is controlled
    // exclusively by the text-colour panel (applyTextColor).  No rebuild needed:
    // the root frame background change is instant and cell content is unchanged.
    // Clears any active background image so the solid colour is visible.
    private fun applyBackground(color: Int) {
        bgColor = color
        rootFrame.setBackgroundColor(color)
        persistCurrentState()
    }

    // Apply text colour independently.  Updates the toolbar swatch and every live
    // EditText cell without rebuilding the grid.  isRestoring guards the loop so
    // the per-cell setTextColor call never triggers a TextWatcher cycle.
    private fun applyTextColor(color: Int) {
        gridTextColor = color
        if (::poemCanvas.isInitialized) poemCanvas.updateTextColor(color)
    }

    // ── Tools panel toggle ────────────────────────────────────────────
    private fun hideBottomForOverlay() {
        if (toolsVisible) allToolsPanel.visibility = View.GONE
        bottomPanel.visibility = View.GONE
    }

    private fun switchToTools() {
        clearSelection()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(rootFrame.windowToken, 0)
        currentFocus?.clearFocus()
        refreshInsertedImagePanel()
        val h = if (lastKeyboardHeight > 0) lastKeyboardHeight
                else (280 * resources.displayMetrics.density).roundToInt()
        val lp = allToolsPanel.layoutParams as FrameLayout.LayoutParams
        lp.height = h
        allToolsPanel.layoutParams = lp
        allToolsPanel.visibility = View.VISIBLE
        mainScrollView.setPadding(0, 0, 0, h)
        toolsVisible = true
        toolsCell?.setBackgroundColor(getColor(R.color.row_active))
    }

    private fun switchToKeyboard() {
        allToolsPanel.visibility = View.GONE
        mainScrollView.setPadding(0, 0, 0, lastKeyboardHeight.coerceAtLeast(0))
        toolsVisible = false
        toolsCell?.setBackgroundColor(Color.TRANSPARENT)
        ghostInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(ghostInput, InputMethodManager.SHOW_IMPLICIT)
    }

    // ── Selection ──────────────────────────────────────────────────────

    private fun enterSelectionMode(index: Int) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(rootFrame.windowToken, 0)
        currentFocus?.clearFocus()
        isSelecting = true
        highlightFrom = -1  // force full repaint on first updateHighlightDiff call
        selectionStart = index; selectionEnd = index
        updateSelectionHighlight()
    }

    private fun clearSelection() {
        isSelecting = false; selectionStart = -1; selectionEnd = -1
        if (::poemCanvas.isInitialized) poemCanvas.clearSelectionHighlight()
        highlightFrom = -1; highlightTo = -1
        isFrameScheduled = false
        cachedPopupW = -1; cachedPopupH = -1
        startHandle?.visibility = View.GONE
        endHandle?.visibility   = View.GONE
        selectionOptionsView?.visibility = View.GONE
        handlePasteView?.visibility = View.GONE
        tappedHandleIsStart = null
    }

    private fun updateSelectionHighlight() {
        if (!isSelecting) return
        val total = MAX_COLUMNS * numRows
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val to   = maxOf(selectionStart, selectionEnd).coerceAtMost((total - 1).coerceAtLeast(0))
        updateHighlightDiff(from, to)
        positionHandleAt(startHandle, from, atTop = true)
        positionHandleAt(endHandle,   to,   atTop = false)
        positionSelectionOptionsView()
    }

    private fun updateHighlightDiff(newFrom: Int, newTo: Int) {
        if (::poemCanvas.isInitialized) poemCanvas.setSelection(newFrom, newTo)
        highlightFrom = newFrom; highlightTo = newTo
    }

    private fun repositionHandles() {
        if (!isSelecting) return
        val total = MAX_COLUMNS * numRows
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val to   = maxOf(selectionStart, selectionEnd).coerceAtMost((total - 1).coerceAtLeast(0))
        positionHandleAt(startHandle, from, atTop = true)
        positionHandleAt(endHandle,   to,   atTop = false)
        positionSelectionOptionsView()
    }

    private fun positionHandleAt(handle: View?, index: Int, atTop: Boolean) {
        handle ?: return
        if (!::poemCanvas.isInitialized) return
        val cellRect = poemCanvas.cellRect(index) ?: return
        val canvasLoc = IntArray(2); poemCanvas.getLocationOnScreen(canvasLoc)
        val rootLoc   = IntArray(2); rootFrame.getLocationOnScreen(rootLoc)
        val sz = handle.layoutParams.width.toFloat()
        val x  = (canvasLoc[0] - rootLoc[0] + cellRect.left + cellRect.width() / 2f - sz / 2f)
        val y  = (canvasLoc[1] - rootLoc[1] + if (atTop) cellRect.top.toFloat() - sz else cellRect.bottom.toFloat())
        if (x == 0f) return
        handle.x = x; handle.y = y.coerceAtLeast(0f)
        handle.visibility = View.VISIBLE
    }

    private fun fastCellIndexAtPoint(rawX: Float, rawY: Float): Int {
        if (!::poemCanvas.isInitialized) return -1
        val loc = IntArray(2); poemCanvas.getLocationOnScreen(loc)
        val raw = poemCanvas.cellIndexAtTouch(rawX - loc[0], rawY - loc[1])
        if (raw < 0) return raw
        val nr = numRows.coerceAtLeast(1)
        val maxActiveCol = columnData.indexOfLast { c -> c.any { it.isNotEmpty() } }.coerceAtLeast(0)
        val col = (raw / nr).coerceIn(0, maxActiveCol)
        return col * nr + raw % nr
    }

    private fun selectedTextToString(): String {
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val to   = maxOf(selectionStart, selectionEnd).coerceAtMost((MAX_COLUMNS * numRows - 1).coerceAtLeast(0))
        val sb = StringBuilder()
        var prevCol = from / numRows
        for (i in from..to) {
            val c = i / numRows; val r = i % numRows
            if (c != prevCol) { if (columnBreaks.contains(c)) sb.append('\n'); prevCol = c }
            sb.append(columnData.getOrNull(c)?.getOrNull(r) ?: "")
        }
        return sb.toString()
    }

    private fun copySelectedText() {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("poemeditor", selectedTextToString()))
        Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
        clearSelection()
    }

    private fun cutSelectedText() {
        pushHistory()
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("poemeditor", selectedTextToString()))
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val to   = maxOf(selectionStart, selectionEnd).coerceAtMost((MAX_COLUMNS * numRows - 1).coerceAtLeast(0))
        for (i in from..to) { val c = i / numRows; val r = i % numRows; setColumnChar(c, r, "") }
        val focusTarget = from.coerceAtMost((MAX_COLUMNS * numRows - 1).coerceAtLeast(0))
        clearSelection()
        reflowColumnData(numRows)
        postRefreshFocusColumn(focusTarget)
        Toast.makeText(this, getString(R.string.toast_cut), Toast.LENGTH_SHORT).show()
    }

    private fun pasteText() {
        pushHistory()
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val raw  = clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            ?.ifEmpty { null } ?: return
        val insertIdx = if (isSelecting && selectionStart >= 0)
            minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        else
            focusedCellIndex.coerceAtLeast(0)
        clearSelection()
        val iCol  = insertIdx / numRows
        val iRow  = insertIdx % numRows
        val chars = raw.replace("\r\n", "\n").replace("\r", "\n").filter { it != '\n' }
        if (chars.isEmpty()) return
        pasteCharsAt(iCol, iRow, chars)
        val focusTarget = (iCol * numRows + iRow + chars.length).coerceAtMost((MAX_COLUMNS * numRows - 1).coerceAtLeast(0))
        postRefreshFocusColumn(focusTarget)
        Toast.makeText(this, getString(R.string.toast_pasted), Toast.LENGTH_SHORT).show()
    }

    private fun pasteTextAt(insertIdx: Int) {
        pushHistory()
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val raw  = clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            ?.ifEmpty { null } ?: return
        clearSelection()
        val iCol  = insertIdx / numRows
        val iRow  = insertIdx % numRows
        val chars = raw.replace("\r\n", "\n").replace("\r", "\n").filter { it != '\n' }
        if (chars.isEmpty()) return
        pasteCharsAt(iCol, iRow, chars)
        val focusTarget = (iCol * numRows + iRow + chars.length).coerceAtMost((MAX_COLUMNS * numRows - 1).coerceAtLeast(0))
        postRefreshFocusColumn(focusTarget)
        Toast.makeText(this, getString(R.string.toast_pasted), Toast.LENGTH_SHORT).show()
    }

    private fun pasteCharsAt(iCol: Int, iRow: Int, chars: String) {
        val originalChar = columnData.getOrNull(iCol)?.getOrNull(iRow) ?: ""
        if (inputMode == InputMode.SCATTER && originalChar.isBlank()) {
            withRestoring {
                var wc = iCol; var wr = iRow
                for (ch in chars) {
                    if (wc >= MAX_COLUMNS) break
                    if (wc > iCol && columnBreaks.contains(wc)) break
                    setColumnChar(wc, wr, ch.toString())
                    wr++; if (wr >= numRows) { wr = 0; wc++ }
                }
            }
        } else {
            insertCharsAt(iCol, iRow, chars)
        }
    }

    private fun selectEntireLine() {
        if (selectionStart < 0) return
        val total = (MAX_COLUMNS * numRows - 1).coerceAtLeast(0)
        val col = minOf(selectionStart, selectionEnd).coerceAtLeast(0) / numRows.coerceAtLeast(1)
        selectionStart = (col * numRows).coerceIn(0, total)
        selectionEnd   = ((col + 1) * numRows - 1).coerceIn(0, total)
        updateSelectionHighlight()
    }

    private fun selectEntireParagraph() {
        if (selectionStart < 0) return
        val curCol = minOf(selectionStart, selectionEnd).coerceAtLeast(0) / numRows
        val breaks = columnBreaks.sorted()
        // Paragraph start: rightmost break <= curCol (col 0 if none)
        var paraStartCol = 0
        for (brk in breaks) { if (brk <= curCol) paraStartCol = brk else break }
        // Paragraph end: column before the next break after curCol, or last column
        var paraEndCol = numColumns - 1
        for (brk in breaks) { if (brk > curCol) { paraEndCol = brk - 1; break } }
        var first = -1; var last = -1
        for (col in paraStartCol..paraEndCol) {
            val cells = columnData.getOrNull(col) ?: continue
            for (row in cells.indices) {
                if (cells[row].isNotBlank()) {
                    val idx = col * numRows + row
                    if (first < 0) first = idx
                    last = idx
                }
            }
        }
        if (first >= 0) { selectionStart = first; selectionEnd = last; updateSelectionHighlight() }
    }

    // ── Reflow ─────────────────────────────────────────────────────────
    private fun reflowColumnData(newNumRows: Int) =
        GridLogicHelper.reflowColumnData(columnData, columnBreaks, newNumRows)

    private fun squeezeScatterOutOfRange(newNumRows: Int) =
        GridLogicHelper.squeezeScatterOutOfRange(columnData, newNumRows)

    private fun refreshGrid() {
        placeFrontierMarker()
        clearComposingPreview()
        if (::poemCanvas.isInitialized) poemCanvas.refreshContent(columnData)
        if (isSelecting) updateSelectionHighlight()
    }

    // ── Grid ───────────────────────────────────────────────────────────

    private fun insertCharsAt(insertCol: Int, insertRow: Int, newChars: String) =
        GridLogicHelper.insertCharsAt(columnData, columnBreaks, insertCol, insertRow, newChars, numRows, MAX_COLUMNS)

    // Inserts newChars at focusedCellIndex and returns the new cursor position
    // (one past the last inserted char), or null if the text is empty.
    private fun performInsert(newChars: String): Int? {
        if (newChars.isEmpty()) return null
        val nr = numRows.coerceAtLeast(1)
        val total = (MAX_COLUMNS * numRows).takeIf { it > 0 } ?: return null
        val iCol = focusedCellIndex / nr
        val iRow = focusedCellIndex % nr
        insertCharsAt(iCol, iRow, newChars)
        var fCol = iCol; var fRow = iRow
        repeat(newChars.length - 1) { fRow++; if (fRow >= numRows) { fRow = 0; fCol++ } }
        return (fCol * numRows + fRow + 1).coerceAtMost(total - 1)
    }

    private fun makeRoomBeforeParagraphBreakForLineEndInsert(
        cellCol: Int,
        cellRow: Int,
        insertedLength: Int
    ) {
        if (inputMode != InputMode.SEQUENTIAL || insertedLength <= 0) return
        val breakCol = cellCol + 1
        if (!columnBreaks.contains(breakCol)) return
        if ((columnData.getOrNull(cellCol)?.getOrNull(cellRow) ?: "") != LINE_END_MARKER) return

        val nr = numRows.coerceAtLeast(1)
        val columnsUsedByInsertedText = ((cellRow + insertedLength + nr - 1) / nr).coerceAtLeast(1)
        while (columnData.size < breakCol) columnData.add(mutableListOf())
        repeat(columnsUsedByInsertedText) { columnData.add(breakCol, mutableListOf()) }
        while (columnData.size > MAX_COLUMNS) columnData.removeAt(columnData.size - 1)

        val shiftedBreaks = columnBreaks.mapTo(mutableSetOf()) {
            if (it >= breakCol) it + columnsUsedByInsertedText else it
        }
        shiftedBreaks.removeAll { it >= MAX_COLUMNS }
        columnBreaks.clear(); columnBreaks.addAll(shiftedBreaks)

        setColumnChar(cellCol, cellRow, "")
    }

    // Enter key: place ↵ at cursor, move the tail to a new structural column, and jump there.
    // SEQUENTIAL uses a structural column insert so subsequent paragraphs shift right.
    // SCATTER writes ↵ in place and adds a column break without structural shift.
    private fun handleEnter() {
        val nr = numRows.coerceAtLeast(1)
        val iCol = focusedCellIndex / nr
        val iRow = focusedCellIndex % nr
        val nextCol = (iCol + 1).coerceAtMost(MAX_COLUMNS - 1)
        if (inputMode == InputMode.SCATTER) {
            setColumnChar(iCol, iRow, LINE_END_MARKER)
            while (columnData.size <= nextCol) columnData.add(mutableListOf())
            columnBreaks.add(nextCol)
        } else {
            // Extract content from cursor row onwards (the tail that moves to the new column).
            val colData = columnData.getOrNull(iCol)
            val tail = mutableListOf<String>()
            if (colData != null) {
                for (r in iRow until colData.size) tail.add(colData[r])
                while (tail.isNotEmpty() && (tail.last().isBlank() || tail.last() == LINE_END_MARKER)) tail.removeLast()
                while (colData.size > iRow) colData.removeAt(colData.size - 1)
            }
            // Place ↵ at the split point in the current column.
            setColumnChar(iCol, iRow, LINE_END_MARKER)
            // Structural insert: push all columns at/beyond nextCol one slot right.
            while (columnData.size < nextCol) columnData.add(mutableListOf())
            columnData.add(nextCol, tail)
            val shifted = columnBreaks.mapTo(mutableSetOf()) { if (it >= nextCol) it + 1 else it }
            shifted.add(nextCol)
            columnBreaks.clear(); columnBreaks.addAll(shifted)
            while (columnData.size > MAX_COLUMNS) columnData.removeAt(columnData.size - 1)
            columnBreaks.removeAll { it >= MAX_COLUMNS }
        }
        postRefreshFocusColumn(nextCol * numRows, nextCol)
    }

    // Backspace on ↵ / at row 0 of a paragraph column: reverse the break.
    // Clears the LINE_END_MARKER from the preceding column, removes the break,
    // reflows, and lands the cursor at the last occupied position in the preceding column.
    private fun removeColumnBreak(cellCol: Int) {
        columnBreaks.remove(cellCol)
        val prevCol = cellCol - 1
        val prevColData = columnData.getOrNull(prevCol)
        if (prevColData != null) {
            val markerIdx = prevColData.indexOfLast { it == LINE_END_MARKER }
            if (markerIdx >= 0) withRestoring { prevColData[markerIdx] = "" }
        }
        val prevContentCount = run {
            val last = columnData.getOrNull(prevCol)?.indexOfLast { it.isNotEmpty() } ?: -1
            (last + 1).coerceAtLeast(0)
        }
        gridContainer.post {
            if (inputMode != InputMode.SCATTER) reflowColumnData(numRows)
            needsReflow = false
            refreshGrid()
            val focusTarget = (prevCol * numRows + prevContentCount)
                .coerceAtMost((MAX_COLUMNS * numRows - 1).coerceAtLeast(0))
            focusCell(focusTarget)
        }
    }

    private fun deletePreviousSequentialCell(cellCol: Int, cellRow: Int, index: Int) {
        if (cellRow == 0) {
            if (cellCol == 0) return
            val prevCol = cellCol - 1
            val prevColList = columnData.getOrNull(prevCol)
            val lastOccupied = prevColList?.indexOfLast { it.isNotEmpty() } ?: -1
            if (prevColList != null && lastOccupied >= 0) {
                withRestoring { prevColList.removeAt(lastOccupied) }
            }
            gridContainer.post {
                reflowColumnData(numRows)
                refreshGrid()
                val focusTarget = (prevCol * numRows + lastOccupied.coerceAtLeast(0))
                    .coerceAtMost((MAX_COLUMNS * numRows - 1).coerceAtLeast(0))
                focusCell(focusTarget)
            }
        } else {
            val prevIndex = index - 1
            val colList = columnData.getOrNull(cellCol)
            if (colList != null && (cellRow - 1) < colList.size) {
                withRestoring { colList.removeAt(cellRow - 1) }
            }
            gridContainer.post {
                reflowColumnData(numRows)
                refreshGrid()
                focusCell(prevIndex)
            }
        }
    }

    private fun rebuildGrid(isInitialBoot: Boolean = false, scrollToStart: Boolean = false) {
        val availW = gridContainer.measuredWidth
        val currentH = mainScrollView.height - gridContainer.paddingTop - gridContainer.paddingBottom
        if (availW <= 0 || currentH <= 0) return
        val imeVisible = ViewCompat.getRootWindowInsets(rootFrame)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        val availH = if (stableMaxHeight > 0) stableMaxHeight else currentH

        val anchorCol: Int = if (scrollToStart) 0 else run {
            val s = hScroll ?: return@run 0
            val cs = currentCellSize.takeIf { it > 0 } ?: return@run 0
            val viewport = s.width.takeIf { it > 0 } ?: return@run 0
            val gw = maxOf(numColumns * cs, s.width)
            (gw - (s.scrollX + viewport)) / cs
        }.coerceIn(0, (numColumns - 1).coerceAtLeast(0))

        clearSelection()
        isPreviewing = false
        focusedCellIndex = -1

        val fontPx   = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        val gapPx    = (wordGapDp * resources.displayMetrics.density)
        val charSize = Paint().apply { textSize = fontPx; typeface = selectedTypeface }
            .measureText("測").roundToInt().coerceAtLeast(1)
        val cellSize = (charSize + gapPx.roundToInt()).coerceAtLeast(1)

        val newNumRows = availH / cellSize
        if (newNumRows <= 0) return

        // In SCATTER mode, squeeze any islands that stick out beyond the new boundary.
        if (inputMode == InputMode.SCATTER && numRows > 0 && newNumRows < numRows) {
            squeezeScatterOutOfRange(newNumRows)
        }

        // Reflow when explicitly requested OR when numRows changed in SEQUENTIAL mode.
        // Covers both shrink (content overflows) and grow (content should repack).
        if (inputMode != InputMode.SCATTER &&
            (needsReflow || (numRows > 0 && newNumRows != numRows))) {
            reflowColumnData(newNumRows)
        }
        needsReflow = false

        numRows = newNumRows

        // MAX_COLUMNS is the minimum skeleton width. After reflow the data may span more
        // columns; expand numColumns to match so nothing is visually clipped.
        numColumns = maxOf(MAX_COLUMNS, columnData.size)

        currentCellSize = cellSize

        poemCanvas.updateData(
            data       = columnData,
            numRows    = numRows,
            maxColumns = numColumns,
            fontPx     = fontPx,
            gapPx      = gapPx,
            textColor  = gridTextColor,
            typeface   = selectedTypeface
        )

        val scrollAnchor = anchorCol
        hScroll?.post {
            val s = hScroll ?: return@post
            val sWidth = s.width.takeIf { it > 0 } ?: return@post
            val gw = maxOf(numColumns * currentCellSize, sWidth)
            s.scrollTo((gw - scrollAnchor * currentCellSize - sWidth).coerceAtLeast(0), 0)
            viewFactory.updateScrollIndicator()
        } ?: viewFactory.updateScrollIndicator()

        placeFrontierMarker()

        if (!isInitialBoot) {
            val lastRealIdx = (0 until numColumns * numRows).indexOfLast { i ->
                val c = i / numRows; val r = i % numRows
                (columnData.getOrNull(c)?.getOrNull(r) ?: "").isNotEmpty()
            }
            val focusIdx = if (lastRealIdx < 0) 0
                else (lastRealIdx + 1).coerceAtMost(numColumns * numRows - 1)
            poemCanvas.postDelayed({ focusCell(focusIdx, showKeyboard = imeVisible) }, 50)
        }
    }

    // ── Composing preview ──────────────────────────────────────────────
    private fun showComposingPreview(text: String, startIndex: Int) {
        if (!::poemCanvas.isInitialized || numRows <= 0) return
        clearComposingPreview()
        val total = MAX_COLUMNS * numRows
        if (startIndex < 0 || startIndex >= total) return
        isPreviewing = true
        val overlay = mutableMapOf<Int, String>()
        overlay[startIndex] = text.firstOrNull()?.toString() ?: ""
        var next = startIndex + 1
        for (i in 1 until text.length) {
            if (next >= total) break
            val c = next / numRows; val r = next % numRows
            if ((columnData.getOrNull(c)?.getOrNull(r) ?: "").isNotEmpty()) break
            overlay[next] = text[i].toString()
            next++
        }
        poemCanvas.setPreviewOverlay(overlay)
        isPreviewing = false
    }

    private fun clearComposingPreview() {
        isPreviewing = true
        if (::poemCanvas.isInitialized) poemCanvas.setPreviewOverlay(emptyMap())
        isPreviewing = false
    }

    // ── Persistence ────────────────────────────────────────────────────
    override fun onStop() {
        super.onStop()
        persistCurrentState()
    }

    private fun saveState() {
        updateActiveImageRuntimeState()
        val matVals = getBgImageMatrixValues()
        val matStr = if (matVals != null) {
            org.json.JSONArray().also { arr -> matVals.forEach { arr.put(it.toDouble()) } }.toString()
        } else ""
        prefs.edit()
            .putString("column_data", columnDataToJson().toString())
            .putString("column_breaks", columnBreaksToJson().toString())
            .putInt("font_index", fontIndex)
            .putFloat("font_size_sp", fontSizeSp)
            .putFloat("word_gap_dp", wordGapDp)
            .putInt("text_color", gridTextColor)
            .putInt("bg_color", bgColor)
            .putString("input_mode", inputMode.name)
            .putString("bg_image_uri", bgImageUri)
            .putString("bg_image_matrix", matStr)
            .putString("inserted_images", insertedImagesToJsonString(copyInsertedImagesState()))
            .putInt("active_image_index", activeImageIndex)
            .putString("current_session_id", currentSessionId)
            .putString("current_session_name", currentSessionName)
            .putInt("grid_pad_top",    gridPaddingTopPx)
            .putInt("grid_pad_bottom", gridPaddingBottomPx)
            .putInt("grid_pad_left",   gridPaddingLeftPx)
            .putInt("grid_pad_right",  gridPaddingRightPx)
            .apply()
    }

    private fun loadState() {
        val colsStr = prefs.getString("column_data", null) ?: return
        try {
            loadColumnDataFromJson(org.json.JSONArray(colsStr))
            loadColumnBreaksFromJson(org.json.JSONArray(prefs.getString("column_breaks", "[]")!!))
        } catch (_: Exception) {
            clearDocumentContent()
        }
    }

    // ── Ghost input + canvas touch setup ──────────────────────────────

    private fun resetGhostSentinel() {
        withRestoring {
            ghostInput.setText(GHOST_SENTINEL)
            ghostInput.setSelection(GHOST_SENTINEL.length)
        }
    }

    private fun setupGhostInput() {
        resetGhostSentinel()

        ghostInput.setOnKeyListener { _, keyCode, event ->
            if (isRestoring) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DEL && keyCode != KeyEvent.KEYCODE_ENTER) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) {
                val nr = numRows.coerceAtLeast(1)
                val cellCol = focusedCellIndex / nr
                val cellRow = focusedCellIndex % nr
                when (keyCode) {
                    KeyEvent.KEYCODE_ENTER -> {
                        pushHistory()
                        handleEnter()
                    }
                    KeyEvent.KEYCODE_DEL -> {
                        pushHistory()
                        if (cellRow == 0 && cellCol > 0 && inputMode != InputMode.SCATTER && columnBreaks.contains(cellCol)) {
                            removeColumnBreak(cellCol)
                            return@setOnKeyListener true
                        }
                        val origChar = columnData.getOrNull(cellCol)?.getOrNull(cellRow) ?: ""
                        if (origChar == LINE_END_MARKER && columnBreaks.contains(cellCol + 1)) {
                            removeColumnBreak(cellCol + 1)
                            return@setOnKeyListener true
                        }
                        if (inputMode == InputMode.SCATTER) {
                            val curChar = columnData.getOrNull(cellCol)?.getOrNull(cellRow) ?: ""
                            if (curChar.isNotBlank()) {
                                deletePreviousSequentialCell(cellCol, cellRow, focusedCellIndex)
                            } else {
                                val colList = columnData.getOrNull(cellCol)
                                val focusTarget = (focusedCellIndex - 1).coerceAtLeast(0)
                                if (colList != null && cellRow < colList.size) {
                                    withRestoring { colList.removeAt(cellRow); colList.add("") }
                                }
                                gridContainer.post { refreshGrid(); focusCell(focusTarget) }
                            }
                        } else {
                            deletePreviousSequentialCell(cellCol, cellRow, focusedCellIndex)
                        }
                    }
                }
            }
            true
        }

        ghostInput.setOnEditorActionListener { _, _, _ ->
            pushHistory()
            handleEnter()
            true
        }

        ghostInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isRestoring || isPreviewing) return
                val full = s?.toString() ?: return
                val raw = if (full.startsWith(GHOST_SENTINEL)) full.drop(GHOST_SENTINEL.length) else full
                val composing = s is Spanned && s.getSpans(0, s.length, Any::class.java)
                    .any { span -> s.getSpanFlags(span) and Spanned.SPAN_COMPOSING != 0 }
                if (composing && raw.isNotEmpty()) showComposingPreview(raw, focusedCellIndex)
                else if (!composing) clearComposingPreview()
            }

            override fun afterTextChanged(editable: Editable?) {
                if (isRestoring || isPreviewing) return
                val nr = numRows.coerceAtLeast(1)
                val totalCells = MAX_COLUMNS * nr
                if (totalCells <= 0) return

                val stillComposing = editable is Spanned && editable.getSpans(0, editable.length, Any::class.java)
                    .any { span -> (editable as Spanned).getSpanFlags(span) and Spanned.SPAN_COMPOSING != 0 }
                if (stillComposing) return

                clearComposingPreview()
                val full = editable?.toString() ?: return
                val raw  = if (full.startsWith(GHOST_SENTINEL)) full.drop(GHOST_SENTINEL.length) else full
                val text = raw.replace("\r\n", "\n").replace("\r", "\n")

                val index   = focusedCellIndex.coerceIn(0, totalCells - 1)
                val cellCol = index / nr
                val cellRow = index % nr
                val originalChar = columnData.getOrNull(cellCol)?.getOrNull(cellRow) ?: ""

                if (raw.isEmpty()) {
                    // Sentinel deleted → backspace
                    pushHistoryBreakingBurst()
                    resetGhostSentinel()
                    if (originalChar == LINE_END_MARKER && columnBreaks.contains(cellCol + 1)) {
                        // Backspace on ↵ removes the column break, same as DEL at row-0 of break col.
                        removeColumnBreak(cellCol + 1)
                        return
                    }
                    if (originalChar.isNotEmpty() && (inputMode == InputMode.SEQUENTIAL || originalChar.isNotBlank())) {
                        deletePreviousSequentialCell(cellCol, cellRow, index)
                    } else {
                        setColumnChar(cellCol, cellRow, "")
                        poemCanvas.refreshContent(columnData)
                        if (index > 0) {
                            focusedCellIndex = index - 1
                            poemCanvas.setCursor(focusedCellIndex)
                            scrollToTopIfCursorInUpperView(focusedCellIndex)
                            scrollToColumn((index - 1) / nr)
                        }
                    }
                    return
                }

                // Single newline — ghost input only carries new chars, so text=="\n" is unambiguous.
                if (text == "\n") {
                    pushHistoryBreakingBurst()
                    resetGhostSentinel()
                    gridContainer.post { handleEnter() }
                    return
                }

                // Insert-shift: occupied non-frontier cell, any commit length, no newlines.
                // Covers both single-char and multi-char IME commits (e.g. CJK composition).
                if (originalChar.isNotBlank() && originalChar != FRONTIER_MARKER && !text.contains('\n')) {
                    pushHistoryBreakingBurst()
                    makeRoomBeforeParagraphBreakForLineEndInsert(cellCol, cellRow, text.length)
                    val focusTarget = performInsert(text) ?: run { resetGhostSentinel(); return }
                    resetGhostSentinel()
                    postRefreshFocusColumn(focusTarget)
                    return
                }

                // Multi-char / paste into empty or frontier cell
                if (text.length > 1 || text.contains('\n')) {
                    pushHistoryBreakingBurst()

                    // Multi-char paste
                    var pCol = cellCol; var pRow = cellRow; var charIdx = 0
                    columnBreaks.filter { it >= cellCol }.forEach { columnBreaks.remove(it) }
                    while (charIdx < text.length && text[charIdx] == '\n') {
                        pCol++; pRow = 0; charIdx++; columnBreaks.add(pCol)
                        if (pCol >= MAX_COLUMNS) { charIdx = text.length; break }
                    }
                    var lastWrittenIdx = (pCol * nr + pRow).coerceAtMost(totalCells - 1)
                    if (charIdx < text.length) {
                        val maxCol = if (inputMode == InputMode.SCATTER && nr > 0)
                            (columnData.size - 1).coerceAtLeast(0)
                        else MAX_COLUMNS - 1
                        lastWrittenIdx = pCol * nr + pRow
                        setColumnChar(pCol, pRow, text[charIdx].toString())
                        pRow++; charIdx++
                        if (pRow >= nr) { pRow = 0; pCol++ }
                        withRestoring {
                            while (charIdx < text.length && pCol <= maxCol) {
                                val ch = text[charIdx++]
                                if (ch == '\n') { pCol++; pRow = 0; columnBreaks.add(pCol); continue }
                                if (pRow >= nr) { pRow = 0; pCol++ }
                                if (pCol > maxCol) break
                                lastWrittenIdx = pCol * nr + pRow
                                setColumnChar(pCol, pRow, ch.toString())
                                pRow++
                            }
                        }
                    }
                    val finalLastWrittenIdx = lastWrittenIdx.coerceIn(0, totalCells - 1)
                    val finalFocusIdx = (finalLastWrittenIdx + 1).coerceAtMost(totalCells - 1)
                    resetGhostSentinel()
                    gridContainer.post {
                        refreshGrid()
                        focusCell(finalFocusIdx)
                        scrollToColumn(finalFocusIdx / nr)
                    }
                    return
                }

                // Fast path: single char into empty / space / frontier cell
                maybePushHistoryForTyping()
                setColumnChar(cellCol, cellRow, text)
                poemCanvas.refreshContent(columnData)
                resetGhostSentinel()
                advanceToNextCell(index, totalCells)
            }
        })
    }

    private var lastCanvasTapIndex = -1
    private var lastCanvasTapTime  = 0L

    private fun setupCanvasTouchListener() {
        poemCanvas.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val index = poemCanvas.cellIndexAtTouch(event.x, event.y)
                if (index >= 0) {
                    val now = System.currentTimeMillis()
                    if (!isSelecting && lastCanvasTapIndex == index && now - lastCanvasTapTime < 300L) {
                        lastCanvasTapTime = 0L; lastCanvasTapIndex = -1
                        enterSelectionMode(index)
                        return@setOnTouchListener true
                    }
                    if (isSelecting) {
                        handlePasteView?.visibility = View.GONE
                        clearSelection()
                    }
                    lastCanvasTapTime = now; lastCanvasTapIndex = index
                    if (toolsVisible) {
                        allToolsPanel.visibility = View.GONE
                        toolsVisible = false
                        toolsCell?.setBackgroundColor(Color.TRANSPARENT)
                        if (lastKeyboardHeight > 0) mainScrollView.setPadding(0, 0, 0, lastKeyboardHeight)
                    }
                    val nr = numRows.coerceAtLeast(1)
                    val cellChar = columnData.getOrNull(index / nr)?.getOrNull(index % nr) ?: ""
                    if (inputMode == InputMode.SEQUENTIAL && cellChar.isEmpty()) {
                        val target = findSequentialTapTarget(index)
                        if (target >= 0 && target != index) {
                            focusCell(target)
                            scrollToColumn(target / nr)
                            return@setOnTouchListener true
                        }
                    }
                    // Occupied non-frontier/non-lineend cell: top half → cursor before, bottom half → cursor after.
                    if (cellChar.isNotBlank() && cellChar != FRONTIER_MARKER && cellChar != LINE_END_MARKER) {
                        val cs = currentCellSize.coerceAtLeast(1)
                        val cellTopY = (index % nr) * cs
                        val tapInTopHalf = event.y < cellTopY + cs / 2f
                        if (tapInTopHalf) {
                            focusCell(index)
                        } else {
                            focusCell((index + 1).coerceAtMost(MAX_COLUMNS * numRows - 1))
                        }
                    } else {
                        focusCell(index)
                    }
                }
            }
            true
        }
    }

    // ── Settings load ──────────────────────────────────────────────────
    // Called before UI construction so spinners/panels are initialised with correct values.
    private fun loadSettings() {
        fontIndex = prefs.getInt("font_index", 0).coerceIn(0, fontCatalogue.size - 1)
        selectedTypeface = fontCatalogue[fontIndex].typeface
        fontSizeSp  = prefs.getFloat("font_size_sp", 24f)
        val dp = resources.displayMetrics.density
        gridPaddingTopPx    = prefs.getInt("grid_pad_top",    (16f * dp).roundToInt())
        gridPaddingBottomPx = prefs.getInt("grid_pad_bottom", 0)
        gridPaddingLeftPx   = prefs.getInt("grid_pad_left",   0)
        gridPaddingRightPx  = prefs.getInt("grid_pad_right",  0)
        wordGapDp   = prefs.getFloat("word_gap_dp", 3f)
        gridTextColor = prefs.getInt("text_color", Color.BLACK)
        bgColor     = prefs.getInt("bg_color", Color.WHITE)
        bgImageUri  = prefs.getString("bg_image_uri", null)
        inputMode   = when (prefs.getString("input_mode", "SEQUENTIAL")) {
            "SCATTER" -> InputMode.SCATTER; else -> InputMode.SEQUENTIAL }
        currentSessionId   = prefs.getString("current_session_id",  null) ?: java.util.UUID.randomUUID().toString()
        currentSessionName = prefs.getString("current_session_name", getString(R.string.default_session_name)) ?: getString(R.string.default_session_name)

        val loadedInsertedImages = try {
            parseInsertedImages(org.json.JSONArray(prefs.getString("inserted_images", "[]") ?: "[]"))
        } catch (_: Exception) { mutableListOf() }

        insertedImages.clear()
        insertedImages.addAll(loadedInsertedImages.take(MAX_INSERTED_IMAGES))
        activeImageIndex = prefs.getInt("active_image_index", if (insertedImages.isNotEmpty()) 0 else -1)
            .coerceIn(-1, insertedImages.lastIndex)

        val matStr = prefs.getString("bg_image_matrix", null)
        if (!matStr.isNullOrEmpty()) {
            try {
                val arr = org.json.JSONArray(matStr)
                if (arr.length() == 9) pendingBgImageMatrix = FloatArray(9) { i -> arr.getDouble(i).toFloat() }
            } catch (_: Exception) {}
        }

        // Backward compatibility: migrate legacy single-image prefs when list is absent.
        if (insertedImages.isEmpty() && !bgImageUri.isNullOrEmpty()) {
            insertedImages.add(InsertedImageState(bgImageUri!!, cloneMatrix(pendingBgImageMatrix)))
            activeImageIndex = 0
        }
    }

    // ── Background image ───────────────────────────────────────────────
    private fun applyInsertedImage(uri: Uri) {
        if (insertedImages.size >= MAX_INSERTED_IMAGES) {
            Toast.makeText(this, getString(R.string.toast_max_images), Toast.LENGTH_SHORT).show()
            return
        }
        pushHistory()
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: Exception) {}
        val storedUri = storeInsertedImage(uri) ?: uri.toString()
        insertedImages.add(InsertedImageState(storedUri, null))
        activeImageIndex = insertedImages.lastIndex
        syncActiveImageFromList()
        refreshInsertedImagePanel()
        persistCurrentState()
    }

    private fun storeInsertedImage(uri: Uri): String? {
        return try {
            val dir = java.io.File(filesDir, "backgrounds").also { it.mkdirs() }
            val file = java.io.File(dir, "${currentSessionId}_${System.currentTimeMillis()}.bg")
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            Uri.fromFile(file).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun selectInsertedImage(index: Int) {
        if (index !in insertedImages.indices || index == activeImageIndex) return
        pushHistory()
        updateActiveImageRuntimeState()
        activeImageIndex = index
        syncActiveImageFromList()
        refreshInsertedImagePanel()
        persistCurrentState()
    }

    private fun getBgImageMatrixValues(): FloatArray? {
        if (bgImageUri == null) return null
        return FloatArray(9).also { bgImageMatrix.getValues(it) }
    }

    private fun removeInsertedImage(index: Int = activeImageIndex) {
        if (index !in insertedImages.indices) return
        pushHistory()
        insertedImages.removeAt(index)
        activeImageIndex = if (insertedImages.isEmpty()) -1 else minOf(index, insertedImages.lastIndex)
        syncActiveImageFromList()
        refreshInsertedImagePanel()
        persistCurrentState()
    }

    private fun refreshInsertedImagePanel() {
        val container = insertImageContainer ?: return
        container.removeAllViews()
        val dp = resources.displayMetrics.density
        insertedImages.forEachIndexed { idx, img ->
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.marginEnd = (6 * dp).roundToInt()
                }

                addView(ImageView(this@MainActivity).apply {
                    val avatarSize = (22 * dp).roundToInt()
                    layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageURI(Uri.parse(img.uri))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(
                            (if (idx == activeImageIndex) 2f else 1f * dp).roundToInt(),
                            getColor(if (idx == activeImageIndex) R.color.text_dark else R.color.stroke)
                        )
                    }
                    clipToOutline = true
                    contentDescription = "已插入圖片 ${idx + 1}"
                    setOnClickListener { selectInsertedImage(idx) }
                })

                addView(TextView(this@MainActivity).apply {
                    text = "×"
                    textSize = 10f
                    setTextColor(getColor(R.color.text_dark))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams((12 * dp).roundToInt(), (12 * dp).roundToInt())
                        .also { it.marginStart = (2 * dp).roundToInt() }
                    setOnClickListener { removeInsertedImage(idx) }
                })
            })
        }
        container.addView(TextView(this).apply {
            text = "${insertedImages.size}/$MAX_INSERTED_IMAGES"
            textSize = 11f
            setTextColor(getColor(R.color.text_light))
        })
    }

    // ── Screenshot ─────────────────────────────────────────────────────
    private val screenshotController: ScreenshotController by lazy {
        ScreenshotController(this, object : ScreenshotController.Callbacks {
            override fun getRootFrame()              = rootFrame
            override fun getMainScrollView()         = mainScrollView as View
            override fun getBottomPanel()            = bottomPanel    as View
            override fun getGridContainer()          = gridContainer  as View
            override fun getPoemCanvas()             = poemCanvas
            override fun getScrollIndicatorContainer() = viewFactory.scrollIndicatorContainer
            override fun getTransientViews()         = listOf(startHandle, endHandle,
                                                               selectionOptionsView, handlePasteView)
            override fun getAllToolsPanel()           = allToolsPanel as View?
            override fun isToolsVisible()            = toolsVisible
            override fun hideBottomForOverlay() = this@MainActivity.hideBottomForOverlay()
            override fun getNumRows()                = numRows
            override fun getCurrentCellSize()        = currentCellSize
            override fun onScreenshotRestored() {
                bottomPanel.visibility = View.VISIBLE
                if (::poemCanvas.isInitialized) poemCanvas.startCursorBlink()
                if (toolsVisible) allToolsPanel.visibility = View.VISIBLE
                viewFactory.updateScrollIndicator()
                if (isSelecting) {
                    startHandle?.visibility = View.VISIBLE
                    endHandle?.visibility   = View.VISIBLE
                    positionSelectionOptionsView()
                }
            }
        })
    }

    private fun takeScreenshot() = screenshotController.takeScreenshot()

    private fun showInputFieldEditor() {
        val toolsWereVisible = toolsVisible
        hideBottomForOverlay()

        val rootLoc   = IntArray(2); rootFrame.getLocationOnScreen(rootLoc)
        val scrollLoc = IntArray(2); mainScrollView.getLocationOnScreen(scrollLoc)
        val scrollTop = scrollLoc[1] - rootLoc[1]

        val initTop    = scrollTop + gridPaddingTopPx
        val initBottom = (initTop + numRows * currentCellSize.coerceAtLeast(1))
            .coerceAtMost(rootFrame.height)

        val editorView = InputFieldEditorView(this, initTop, initBottom)
        editorView.layoutParams = FrameLayout.LayoutParams(MP, MP)

        fun restoreUI() {
            bottomPanel.visibility = View.VISIBLE
            if (toolsWereVisible) switchToTools()
        }

        editorView.onCancel = {
            rootFrame.removeView(editorView)
            restoreUI()
        }

        editorView.onConfirm = { top, bottom ->
            rootFrame.removeView(editorView)
            restoreUI()
            // Defer one frame so the layout settles with the toolbar visible before
            // we measure mainScrollView.height for the padding calculation.
            mainScrollView.post {
                val sTop = scrollLoc[1] - rootLoc[1]
                gridPaddingTopPx    = (top    - sTop).coerceAtLeast(0)
                gridPaddingBottomPx = (mainScrollView.height - (bottom - sTop)).coerceAtLeast(0)
                gridPaddingLeftPx   = 0
                gridPaddingRightPx  = 0
                gridContainer.setPadding(0, gridPaddingTopPx, 0, gridPaddingBottomPx)
                stableMaxHeight = (mainScrollView.height - gridPaddingTopPx - gridPaddingBottomPx)
                    .coerceAtLeast(1)
                rebuildGrid()
                saveState()
            }
        }

        rootFrame.addView(editorView)
    }

    // ── Undo / Redo ────────────────────────────────────────────────────
    private fun snapshotState(): EditorHistoryState {
        updateActiveImageRuntimeState()
        return EditorHistoryState(
            data = columnData.map { it.toList() },
            breaks = columnBreaks.toSet(),
            fontIndex = fontIndex,
            fontSizeSp = fontSizeSp,
            wordGapDp = wordGapDp,
            gridTextColor = gridTextColor,
            bgColor = bgColor,
            inputMode = inputMode,
            insertedImages = copyInsertedImagesState(),
            activeImageIndex = activeImageIndex
        )
    }

    private fun pushHistory() {
        editorViewModel.snapshotForUndo(snapshotState())
        updateUndoRedoButtons()
    }

    // Captures the pre-burst state on the FIRST keystroke of a typing burst, then
    // suppresses pushHistory() for subsequent keystrokes until the user pauses ≥1 s.
    // Structural operations (delete, paste, Enter, insert-shift) call
    // pushHistoryBreakingBurst() instead, which always pushes and resets the burst so
    // the next typing run starts a fresh undo entry.
    private fun maybePushHistoryForTyping() {
        if (!historyBurstActive) {
            pushHistory()
            historyBurstActive = true
        }
        historyBurstReset?.let { historyBurstHandler.removeCallbacks(it) }
        historyBurstReset = Runnable { historyBurstActive = false }
            .also { historyBurstHandler.postDelayed(it, 1_000L) }
    }

    private fun pushHistoryBreakingBurst() {
        historyBurstActive = false
        historyBurstReset?.let { historyBurstHandler.removeCallbacks(it) }
        historyBurstReset = null
        pushHistory()
    }

    private fun performUndo() {
        val state = editorViewModel.undo(snapshotState()) ?: return
        restoreHistoryState(state)
    }

    private fun performRedo() {
        val state = editorViewModel.redo(snapshotState()) ?: return
        restoreHistoryState(state)
    }

    private fun restoreHistoryState(state: EditorHistoryState) {
        columnData.clear()
        state.data.forEach { columnData.add(it.toMutableList()) }
        columnBreaks.clear()
        columnBreaks.addAll(state.breaks)

        insertedImages.clear()
        insertedImages.addAll(state.insertedImages.take(MAX_INSERTED_IMAGES).map {
            InsertedImageState(it.uri, cloneMatrix(it.matrix))
        })
        activeImageIndex = state.activeImageIndex.coerceIn(-1, insertedImages.lastIndex)

        applySettings(
            fontIdx = state.fontIndex,
            sizeSp = state.fontSizeSp,
            gapDp = state.wordGapDp,
            textColor = state.gridTextColor,
            bgCol = state.bgColor,
            mode = state.inputMode,
            images = copyInsertedImagesState(),
            selectedImageIndex = activeImageIndex
        )

        needsReflow = (inputMode != InputMode.SCATTER)
        rebuildGrid()
        updateUndoRedoButtons()
    }

    private fun updateUndoRedoButtons() {
        undoButton?.setTextColor(getColor(
            if (editorViewModel.canUndo()) R.color.text_dark else R.color.text_hint))
        redoButton?.setTextColor(getColor(
            if (editorViewModel.canRedo()) R.color.text_dark else R.color.text_hint))
    }

    // Applies all per-session settings to data model + live UI controls.
    // Does NOT call rebuildGrid — caller is responsible.
    private fun applySettings(
        fontIdx: Int = fontIndex,
        sizeSp: Float = fontSizeSp,
        gapDp: Float = wordGapDp,
        textColor: Int = gridTextColor,
        bgCol: Int = bgColor,
        imgUri: String? = bgImageUri,
        imgMatrixValues: FloatArray? = null,
        mode: InputMode = inputMode,
        images: List<InsertedImageState> = copyInsertedImagesState(),
        selectedImageIndex: Int = activeImageIndex
    ) {
        fontIndex = fontIdx.coerceIn(0, fontCatalogue.size - 1)
        selectedTypeface = fontCatalogue[fontIndex].typeface
        fontSizeSp = sizeSp
        wordGapDp = gapDp
        gridTextColor = textColor
        bgColor = bgCol
        inputMode = mode

        insertedImages.clear()
        insertedImages.addAll(images.take(MAX_INSERTED_IMAGES).map {
            InsertedImageState(it.uri, cloneMatrix(it.matrix))
        })

        if (insertedImages.isEmpty() && !imgUri.isNullOrEmpty()) {
            insertedImages.add(InsertedImageState(imgUri, cloneMatrix(imgMatrixValues)))
        }
        activeImageIndex = selectedImageIndex.coerceIn(-1, insertedImages.lastIndex)
        if (activeImageIndex < 0 && insertedImages.isNotEmpty()) activeImageIndex = 0

        fontSpinnerRef?.setSelection(fontIndex)
        fontSizeLabelRef?.text = fontSizeSp.toInt().toString()
        gapValueLabelRef?.text = wordGapDp.toInt().toString()

        applyTextColor(gridTextColor)
        refreshModeChips()
        rootFrame.setBackgroundColor(bgColor)
        syncActiveImageFromList()
        refreshInsertedImagePanel()
    }

    private fun saveSession() {
        updateActiveImageRuntimeState()
        editorViewModel.saveSession(currentSessionId, currentSessionName, columnData, columnBreaks,
            fontIndex, fontSizeSp, wordGapDp, gridTextColor, bgColor, bgImageUri,
            getBgImageMatrixValues(), inputMode.name, copyInsertedImagesState(), activeImageIndex,
            gridPaddingTopPx, gridPaddingBottomPx, gridPaddingLeftPx, gridPaddingRightPx)
    }

    private fun loadSessionFile(id: String) {
        if (id == currentSessionId) return
        saveSession()
        val j = editorViewModel.loadSessionJson(id) ?: return
        loadColumnDataFromJson(j.getJSONArray("columnData"))
        loadColumnBreaksFromJson(j.getJSONArray("columnBreaks"))
        currentSessionId = id; currentSessionName = j.getString("name")
        updateToolbarSessionName()

        val matArr = j.optJSONArray("bgImageMatrix")
        val matValues = if (matArr != null && matArr.length() == 9)
            FloatArray(9) { i -> matArr.getDouble(i).toFloat() } else null

        val loadedImages = parseInsertedImages(j.optJSONArray("insertedImages"))
        val legacyUri = j.optString("bgImageUri", "").ifEmpty { null }
        if (loadedImages.isEmpty() && !legacyUri.isNullOrEmpty()) {
            loadedImages.add(InsertedImageState(legacyUri, cloneMatrix(matValues)))
        }
        val loadedActiveIndex = j.optInt("activeImageIndex", if (loadedImages.isNotEmpty()) 0 else -1)

        applySettings(
            fontIdx        = j.optInt("fontIndex", fontIndex),
            sizeSp         = j.optDouble("fontSizeSp", fontSizeSp.toDouble()).toFloat(),
            gapDp          = j.optDouble("wordGapDp", wordGapDp.toDouble()).toFloat(),
            textColor      = j.optInt("gridTextColor", gridTextColor),
            bgCol          = j.optInt("bgColor", bgColor),
            imgUri         = legacyUri,
            imgMatrixValues = matValues,
            mode           = when (j.optString("inputMode", inputMode.name)) {
                "SCATTER" -> InputMode.SCATTER; else -> InputMode.SEQUENTIAL },
            images = loadedImages,
            selectedImageIndex = loadedActiveIndex
        )
        val dp = resources.displayMetrics.density
        gridPaddingTopPx    = j.optInt("gridPadTop",    (16f * dp).roundToInt())
        gridPaddingBottomPx = j.optInt("gridPadBottom", 0)
        gridPaddingLeftPx   = j.optInt("gridPadLeft",   0)
        gridPaddingRightPx  = j.optInt("gridPadRight",  0)
        gridContainer.setPadding(gridPaddingLeftPx, gridPaddingTopPx,
            gridPaddingRightPx, gridPaddingBottomPx)
        if (mainScrollView.height > 0) {
            stableMaxHeight = (mainScrollView.height - gridPaddingTopPx - gridPaddingBottomPx)
                .coerceAtLeast(1)
        }

        editorViewModel.clearHistory(); updateUndoRedoButtons()
        needsReflow = true; rebuildGrid(scrollToStart = true)
    }

    private fun ensureDefaultSession() {
        editorViewModel.ensureDefaultSession(currentSessionId, currentSessionName,
            columnData, columnBreaks, fontIndex, fontSizeSp, wordGapDp,
            gridTextColor, bgColor, bgImageUri, getBgImageMatrixValues(), inputMode.name,
            copyInsertedImagesState(), activeImageIndex,
            gridPaddingTopPx, gridPaddingBottomPx, gridPaddingLeftPx, gridPaddingRightPx)
    }

    private fun refreshModeChips() {
        val container = modeChipContainer ?: return
        for (i in 0 until container.childCount) {
            val chip = container.getChildAt(i) as? TextView ?: continue
            (chip.background as? GradientDrawable)?.setColor(
                if (chip.tag == inputMode) getColor(R.color.chip_active) else Color.TRANSPARENT)
        }
    }

    private fun fillGapsForSequentialMode() {
        GridLogicHelper.fillGapsForSequentialMode(columnData, columnBreaks, numRows, numColumns)
        refreshGrid()
    }

    private fun placeFrontierMarker() {
        GridLogicHelper.placeFrontierMarker(columnData, columnBreaks, numRows, numColumns,
            inputMode == InputMode.SEQUENTIAL)
    }

    private fun findSequentialTapTarget(tappedIndex: Int): Int =
        GridLogicHelper.findSequentialTapTarget(columnData, columnBreaks, numRows, tappedIndex)

    // ── ViewFactory.Callbacks ──────────────────────────────────────────
    override fun provideFontCatalogue(): List<FontEntry> = fontCatalogue
    override fun getFontIndex(): Int = fontIndex
    override fun getFontSizeSp(): Float = fontSizeSp
    override fun getWordGapDp(): Float = wordGapDp
    override fun getSelectedTypeface(): Typeface = selectedTypeface
    override fun getInputMode(): InputMode = inputMode
    override fun getCurrentSessionName(): String = currentSessionName

    override fun onModeSelected(mode: InputMode) {
        if (mode == inputMode) return
        pushHistory()
        val previous = inputMode
        inputMode = mode
        refreshModeChips()
        if (mode == InputMode.SEQUENTIAL && previous == InputMode.SCATTER) fillGapsForSequentialMode()
        persistCurrentState()
    }

    override fun onToolsToggle() { if (toolsVisible) switchToKeyboard() else switchToTools() }
    override fun onCollapsePanel() { switchToKeyboard() }

    override fun onPunctInsert(punct: String) { insertPunct(punct) }

    override fun onFontSelected(pos: Int) {
        if (pos == fontIndex && numRows > 0) return
        pushHistory()
        fontIndex = pos
        selectedTypeface = fontCatalogue[pos].typeface
        needsReflow = true; rebuildGrid()
    }

    override fun onFontSizeChanged(newSize: Float) {
        if (newSize == fontSizeSp) return
        pushHistory()
        fontSizeSp = newSize
        needsReflow = true; rebuildGrid()
    }

    override fun onWordGapChanged(newGap: Int) {
        val gap = newGap.toFloat()
        if (gap == wordGapDp) return
        pushHistory()
        wordGapDp = gap
        needsReflow = true; rebuildGrid()
    }

    override fun onBgColorSelected(color: Int) {
        if (color == bgColor) return
        pushHistory()
        applyBackground(color)
    }
    override fun onInsertImageRequested() { imagePickerLauncher.launch(arrayOf("image/*")) }
    override fun onRemoveInsertedImage() { removeInsertedImage() }
    override fun onTextColorSelected(color: Int) {
        if (color == gridTextColor) return
        pushHistory()
        applyTextColor(color)
        persistCurrentState()
    }

    override fun onCopySelection() { copySelectedText() }
    override fun onCutSelection() { cutSelectedText() }
    override fun onPasteAtSelection() { pasteText() }
    override fun onSelectEntireLine() { selectEntireLine() }
    override fun onSelectEntireParagraph() { selectEntireParagraph() }
    override fun onSelectAll() {
        val nr = numRows.coerceAtLeast(1)
        var first = -1; var last = -1
        for (col in 0 until MAX_COLUMNS) {
            val colData = columnData.getOrNull(col) ?: continue
            for (row in colData.indices) {
                if (colData[row].isNotEmpty()) {
                    val i = col * nr + row
                    if (first < 0) first = i; last = i
                }
            }
        }
        if (first >= 0) { selectionStart = first; selectionEnd = last; updateSelectionHighlight() }
    }

    override fun onHandlePasteClicked() {
        val isStart = tappedHandleIsStart ?: return
        val targetIndex = if (isStart) selectionStart else selectionEnd
        pasteTextAt(targetIndex)
        handlePasteView?.visibility = View.GONE
        tappedHandleIsStart = null
    }

    override fun onShowAllSessions() {
        val intent = Intent(this, SessionListActivity::class.java)
            .putExtra("current_session_id", currentSessionId)
        sessionListLauncher.launch(intent)
    }

    override fun onUndoAction() { performUndo() }
    override fun onRedoAction() { performRedo() }
    override fun onScreenshot() { takeScreenshot() }
    override fun onInputFieldEdit() { showInputFieldEditor() }
    override fun canUndo(): Boolean = editorViewModel.canUndo()
    override fun canRedo(): Boolean = editorViewModel.canRedo()
    override fun getNumColumns(): Int = numColumns
    override fun getCurrentCellSize(): Int = currentCellSize
    override fun getHScrollView(): HorizontalScrollView? = hScroll
}
