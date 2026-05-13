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
    private val editTextFields = mutableListOf<EditText>()
    private var numRows     = 0
    private var numColumns  = 0
    private var isRestoring  = false
    private var isPreviewing = false
    private val previewCells = mutableListOf<Int>()
    private var hScroll: HorizontalScrollView? = null
    private val MAX_COLUMNS = 50
    private var currentCellSize = 0
    private var focusedCellIndex = -1  // index of EditText with current focus highlight, -1 = none
    private var cursorBefore    = false // true = insert BEFORE focused cell, false = insert AFTER
    // columnData[col][row] = char at that cell; "" = empty.
    private val columnData = mutableListOf<MutableList<String>>()
    // Columns that begin with an explicit '\n' break (vs. auto-wrap).
    // Preserved across reflow so stanza structure survives gap/font changes.
    private val columnBreaks = mutableSetOf<Int>()
    private var needsReflow = false
    private var stableMaxHeight = 0       // locked once at startup (IME hidden); used for all numRows calculations
    private var lastKeyboardHeight = 0   // last measured IME height; allToolsPanel matches this height
    private var toolsVisible = false     // true when allToolsPanel is showing instead of keyboard
    private val translateHandler by lazy { Handler(Looper.getMainLooper()) }
    private var translateRunnable: Runnable? = null

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

    // ── Lazy grid build ────────────────────────────────────────────────
    private val builtColumns = HashSet<Int>()
    private lateinit var gridEditorController: GridEditorController
    private lateinit var cellInputController: CellInputController

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

            rootFrame.addView(view, 0)
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
        val nextIdx = (index + 1).coerceAtMost(total - 1)
        focusedCellIndex = nextIdx
        cursorBefore = true
        editTextFields.getOrNull(nextIdx)?.requestFocus()
        scrollToColumn(nextIdx / numRows)
        placeFrontierMarker()
    }

    private fun postRefreshFocusColumn(targetIndex: Int, col: Int = targetIndex / numRows) {
        gridContainer.post {
            refreshGrid()
            focusCell(targetIndex)
            scrollToColumn(col)
        }
    }

    // Highlights the focused cell and records the logical insertion side (before/after).
    // cursorBefore: true  = next keystroke inserts BEFORE this cell's character.
    //               false = next keystroke inserts AFTER  (default).
    // showKeyboard: true  = open IME and re-bind it; false = keep IME closed (style rebuilds).
    // No Paint or Canvas work — purely a data + focus update.
    private fun focusCell(index: Int, cursorBefore: Boolean = true, showKeyboard: Boolean = true) {
        gridEditorController.ensureColumnBuilt(index / numRows.coerceAtLeast(1))
        val et = editTextFields.getOrNull(index) ?: return
        this.cursorBefore = cursorBefore
        if (!isSelecting) editTextFields.getOrNull(focusedCellIndex)?.setBackgroundColor(Color.TRANSPARENT)
        focusedCellIndex = index
        et.requestFocus()
        et.setSelection(0)  // top of cell = natural insertion point in vertical writing
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (showKeyboard) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
        if (showKeyboard) et.postDelayed({ imm.restartInput(et); et.setSelection(0) }, 100)
        scheduleTranslateForKeyboard()
    }

    // Debounced entry-point for translateForKeyboard.  Cancels any pending call and
    // reschedules 100 ms out so rapid focus changes don't pile up and the keyboard
    // animation has time to finish before we measure rect.bottom.
    private fun scheduleTranslateForKeyboard() {
        translateRunnable?.let { translateHandler.removeCallbacks(it) }
        translateRunnable = Runnable {
            val focused = currentFocus
            if (focused is EditText && focused in editTextFields) translateForKeyboard(focused)
        }.also { translateHandler.postDelayed(it, 100) }
    }

    // Scrolls mainScrollView so the focused cell clears the keyboard.
    // Mechanism: paddingBottom = kbdHeight creates extra scroll room at the bottom
    // (clipToPadding=false makes it reachable), then smoothScrollBy brings the cell
    // into view.  No translationY — the window size stays constant (adjustNothing).
    private fun translateForKeyboard(et: EditText) {
        val rect = Rect()
        window.decorView.getWindowVisibleDisplayFrame(rect)
        val screenHeight = resources.displayMetrics.heightPixels
        val kbdHeight = screenHeight - rect.bottom

        if (kbdHeight <= 0) {
            mainScrollView.setPadding(0, 0, 0, 0)
            return
        }

        mainScrollView.setPadding(0, 0, 0, kbdHeight)

        et.post {
            val loc = IntArray(2)
            et.getLocationOnScreen(loc)
            val etBottom = loc[1] + et.height
            val keyboardTop = rect.bottom 

            // 2. 計算格子的底部是否被鍵盤擋住
            if (etBottom > keyboardTop) {
                val delta = etBottom - keyboardTop + rect.height() * 0.3f
                mainScrollView.smoothScrollBy(0, delta.toInt())
            }
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
                                                val newFrom = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
                                                val newTo   = maxOf(selectionStart, selectionEnd)
                                                    .coerceAtMost(editTextFields.size - 1)
                                                updateHighlightDiff(newFrom, newTo)
                                                if (activeDh != null) {
                                                    val draggedIdx = (if (activeDh) selectionStart else selectionEnd)
                                                        .coerceIn(0, editTextFields.size - 1)
                                                    val otherIdx   = (if (activeDh) selectionEnd   else selectionStart)
                                                        .coerceIn(0, editTextFields.size - 1)
                                                    fastPositionHandleByIndex(
                                                        if (activeDh) startHandle else endHandle,
                                                        draggedIdx, atTop = (draggedIdx == newFrom))
                                                    fastPositionHandleByIndex(
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
            setPadding(0, (16 * dp).roundToInt(), 0, 0)
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

        cellInputController = CellInputController()

        gridEditorController = GridEditorController(
            context = this,
            maxColumns = MAX_COLUMNS,
            builtColumns = builtColumns,
            editTextFields = editTextFields,
            columnData = columnData,
            withRestoring = { block -> withRestoring(block) },
            getNumRows = { numRows },
            getCurrentCellSize = { currentCellSize },
            getFontSizeSp = { fontSizeSp },
            getGridTextColor = { gridTextColor },
            getMainScrollWidth = { mainScrollView.width },
            getHScroll = { hScroll },
            makeCell = { row, col, index, cellSize, fontPx ->
                makeCell(row, col, index, cellSize, fontPx)
            }
        )

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
                        val h = mainScrollView.height - gridContainer.paddingTop
                        if (h > 0) stableMaxHeight = h
                    }
                    if (mainScrollView.width > 0 && mainScrollView.height > 0 && editTextFields.isEmpty()) {
                        // Defer one frame so the activity shell is drawn before we allocate
                        // cells on the UI thread — reduces visible startup lag.
                        mainScrollView.post { rebuildGrid(isInitialBoot = true) }
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
        if (!isSelecting) return
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val etFrom = editTextFields.getOrNull(from) ?: return
        if (cachedPopupW < 0) {
            pop.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            cachedPopupW = pop.measuredWidth; cachedPopupH = pop.measuredHeight
        }
        val popW = cachedPopupW.toFloat()
        val dp = resources.displayMetrics.density
        val gap = 8f * dp

        val fromLoc = IntArray(2); etFrom.getLocationOnScreen(fromLoc)
        val rootLoc = IntArray(2); rootFrame.getLocationOnScreen(rootLoc)

        // 獲取選取格子的左邊界 (相對於 rootFrame)
        val cellLeft = (fromLoc[0] - rootLoc[0]).toFloat()
        val cellTop  = (fromLoc[1] - rootLoc[1]).toFloat()

        // ── 關鍵修改：往左顯示 ──
        // 原本是：popX = cellRight + gap
        // 現在改為：將選單的右邊 (popX + popW) 對齊到格子的左邊界再減去間距
        var popX = cellLeft - popW - gap

        // ── 邊界檢查 ──
        // 如果左邊沒位置了（超出螢幕左側），才反轉到右邊顯示
        if (popX < gap) {
            val etRight = cellLeft + etFrom.width
            popX = etRight + gap
        }

        // 限制在螢幕內
        val rootW = rootFrame.width.toFloat()
        popX = popX.coerceIn(gap, (rootW - popW - gap).coerceAtLeast(gap))

        // Y 軸維持原樣或置中於選取範圍
        val popY = cellTop.coerceIn(gap, (rootFrame.height - cachedPopupH - gap).coerceAtLeast(gap))

        pop.x = popX
        pop.y = popY
        pop.visibility = View.VISIBLE
    }


    private fun insertPunct(punct: String) {
        pushHistory()
        val focused = currentFocus
        val idx = editTextFields.indexOf(focused as? EditText)
        if (idx < 0) return
        val cellCol = idx / numRows
        val cellRow = idx % numRows
        val originalChar = columnData.getOrNull(cellCol)?.getOrNull(cellRow) ?: ""
        // Empty cell or SCATTER-mode space: place directly.
        // Island (non-blank, any mode) or SEQUENTIAL occupied: insert-shift.
        if (originalChar.isEmpty() || (inputMode == InputMode.SCATTER && originalChar.isBlank())) {
            setColumnChar(cellCol, cellRow, punct)
            withRestoring { editTextFields[idx].setText(punct) }
            advanceToNextCell(idx, editTextFields.size)
        } else {
            val focusTarget = performInsert(idx, punct) ?: return
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
        withRestoring { editTextFields.forEach { it.setTextColor(color) } }
    }

    // ── Tools panel toggle ────────────────────────────────────────────
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
        // Pre-set padding to lastKeyboardHeight so the grid doesn't jump when
        // the keyboard appears at exactly that height. The insets listener will
        // confirm the exact value once the keyboard is fully shown.
        mainScrollView.setPadding(0, 0, 0, lastKeyboardHeight.coerceAtLeast(0))
        toolsVisible = false
        toolsCell?.setBackgroundColor(Color.TRANSPARENT)
        val et = editTextFields.getOrNull(focusedCellIndex.coerceAtLeast(0))
            ?: editTextFields.firstOrNull() ?: return
        et.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
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
        if (highlightFrom >= 0) {
            val to = highlightTo.coerceAtMost(editTextFields.size - 1)
            for (i in highlightFrom..to) editTextFields.getOrNull(i)?.setBackgroundColor(Color.TRANSPARENT)
        }
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
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val to   = maxOf(selectionStart, selectionEnd).coerceAtMost(editTextFields.size - 1)
        updateHighlightDiff(from, to)
        positionHandleAt(startHandle, from, atTop = true)
        positionHandleAt(endHandle,   to,   atTop = false)
        positionSelectionOptionsView()
    }

    // Diff-update: only repaint cells at the boundary between old and new selection ranges.
    private fun updateHighlightDiff(newFrom: Int, newTo: Int) {
        val sel = getColor(R.color.selection_highlight)
        val prevFrom = highlightFrom; val prevTo = highlightTo
        if (prevFrom < 0) {
            for (i in newFrom..newTo) editTextFields.getOrNull(i)?.setBackgroundColor(sel)
        } else {
            val lo = minOf(prevFrom, newFrom); val hi = maxOf(prevTo, newTo)
            for (i in lo..hi) {
                val inNew = i in newFrom..newTo
                val inOld = i in prevFrom..prevTo
                if (inNew && !inOld) editTextFields.getOrNull(i)?.setBackgroundColor(sel)
                else if (!inNew && inOld) editTextFields.getOrNull(i)?.setBackgroundColor(Color.TRANSPARENT)
            }
        }
        highlightFrom = newFrom; highlightTo = newTo
    }

    private fun repositionHandles() {
        if (!isSelecting) return
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val to   = maxOf(selectionStart, selectionEnd).coerceAtMost(editTextFields.size - 1)
        positionHandleAt(startHandle, from, atTop = true)
        positionHandleAt(endHandle,   to,   atTop = false)
        positionSelectionOptionsView()
    }

    private fun positionHandleAt(handle: View?, index: Int, atTop: Boolean) {
        handle ?: return
        val et = editTextFields.getOrNull(index) ?: return
        val rect = android.graphics.Rect()
        if (!et.getGlobalVisibleRect(rect) || rect.width() == 0) return
        val rootLoc = IntArray(2); rootFrame.getLocationOnScreen(rootLoc)
        val sz = handle.layoutParams.width.toFloat()
        val x  = rect.left - rootLoc[0] + rect.width() / 2f - sz / 2f
        val y  = if (atTop) (rect.top  - rootLoc[1]).toFloat() - sz
                 else        (rect.bottom - rootLoc[1]).toFloat()
        if (x == 0f) return  // skip frame if position looks uninitialized
        handle.x = x; handle.y = y.coerceAtLeast(0f)
        handle.visibility = View.VISIBLE
    }

    private fun cellIndexAtScreenPoint(screenX: Float, screenY: Float): Int {
        val cs = currentCellSize.takeIf { it > 0 } ?: return -1
        val grid = (hScroll?.getChildAt(0) as? GridLayout) ?: return -1
        val loc = IntArray(2); grid.getLocationOnScreen(loc)
        val relX = screenX - loc[0]; val relY = screenY - loc[1]
        val gridW = numColumns * cs
        if (relX < 0 || relX >= gridW || relY < 0 || relY >= numRows * cs) return -1
        val col = (gridW - relX.toInt() - 1) / cs        // RTL: col 0 is rightmost
        val row = relY.toInt() / cs
        return (col * numRows + row).coerceIn(0, editTextFields.size - 1)
    }

    // Uses col-0 cell's physical screen position as the anchor — no grid-width math required.
    // Clamps col to last occupied column so handles don't jump into empty space.
    private fun fastCellIndexAtPoint(rawX: Float, rawY: Float): Int {
        val cs = cachedCellSize.takeIf { it > 0 } ?: return -1
        val firstCell = editTextFields.getOrNull(0) ?: return -1
        val loc = IntArray(2); firstCell.getLocationOnScreen(loc)
        // Col 0 center in screen space; RTL: higher col index is further left.
        val centerX = loc[0] + cs / 2f
        val topY    = loc[1].toFloat()
        val relX = centerX - rawX          // positive = left of col 0 center
        val relY = rawY - topY             // positive = below row 0 top
        val col = (relX / cs).toInt().coerceIn(0, numColumns - 1)
        val row = (relY / cs).toInt().coerceIn(0, numRows - 1)
        val maxActiveCol = columnData.indexOfLast { c -> c.any { it.isNotEmpty() } }.coerceAtLeast(0)
        return (col.coerceIn(0, maxActiveCol) * numRows + row).coerceIn(0, editTextFields.size - 1)
    }

    // Uses col-0 cell's physical screen position as the anchor — immune to gridW / scrollX drift.
    private fun fastPositionHandleByIndex(handle: View?, index: Int, atTop: Boolean) {
        handle ?: return
        if (index < 0 || index >= editTextFields.size) return
        val cs = cachedCellSize.toFloat(); if (cs <= 0f) return
        val firstCell = editTextFields.getOrNull(0) ?: return
        val loc     = IntArray(2); firstCell.getLocationOnScreen(loc)
        val rootLoc = IntArray(2); rootFrame.getLocationOnScreen(rootLoc)
        val col = index / numRows; val row = index % numRows
        val sz  = handle.layoutParams.width.toFloat()
        // Center X of col 0 in rootFrame coordinates; each col steps left by cs.
        val centerX = (loc[0] - rootLoc[0]) + cs / 2f
        val x = centerX - col * cs - sz / 2f
        if (x <= 0f && col == 0) return   // cell not laid out yet — skip frame
        val rowTopY = (loc[1] - rootLoc[1]).toFloat()
        val y = (rowTopY + row * cs + (if (atTop) -sz else cs)).coerceAtLeast(0f)
        handle.x = x; handle.y = y
        handle.visibility = View.VISIBLE
    }

    private fun selectedTextToString(): String {
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val to   = maxOf(selectionStart, selectionEnd).coerceAtMost(editTextFields.size - 1)
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
        Toast.makeText(this, "已複製", Toast.LENGTH_SHORT).show()
        clearSelection()
    }

    private fun cutSelectedText() {
        pushHistory()
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("poemeditor", selectedTextToString()))
        val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val to   = maxOf(selectionStart, selectionEnd).coerceAtMost(editTextFields.size - 1)
        for (i in from..to) { val c = i / numRows; val r = i % numRows; setColumnChar(c, r, "") }
        val focusTarget = from.coerceAtMost(editTextFields.size - 1)
        clearSelection()
        reflowColumnData(numRows)
        postRefreshFocusColumn(focusTarget)
        Toast.makeText(this, "已剪下", Toast.LENGTH_SHORT).show()
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
        val focusTarget = (iCol * numRows + iRow + chars.length).coerceAtMost(editTextFields.size - 1)
        postRefreshFocusColumn(focusTarget)
        Toast.makeText(this, "已貼上", Toast.LENGTH_SHORT).show()
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
        val focusTarget = (iCol * numRows + iRow + chars.length).coerceAtMost(editTextFields.size - 1)
        postRefreshFocusColumn(focusTarget)
        Toast.makeText(this, "已貼上", Toast.LENGTH_SHORT).show()
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
        val col = minOf(selectionStart, selectionEnd).coerceAtLeast(0) / numRows
        selectionStart = (col * numRows).coerceIn(0, editTextFields.size - 1)
        selectionEnd   = ((col + 1) * numRows - 1).coerceIn(0, editTextFields.size - 1)
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

    // ── Lightweight content refresh (no view destruction) ─────────────
    // Used for backspace/delete so the keyboard never flickers.
    // Diffs each cell against columnData and only calls setText when the value changed,
    // keeping every EditText instance alive so the IMM never loses its focus target.
    private fun refreshGrid() {
        clearComposingPreview()
        focusedCellIndex = -1
        val builtColCount = if (numRows > 0) editTextFields.size / numRows else 0
        withRestoring {
            for (col in 0 until builtColCount) {
                val colData = columnData.getOrNull(col)
                for (row in 0 until numRows) {
                    val expected = colData?.getOrNull(row) ?: ""
                    val et = editTextFields.getOrNull(col * numRows + row) ?: continue
                    if (et.text.toString() != expected) {
                        et.setText(expected)
                        et.setTextColor(gridTextColor)
                    }
                }
            }
        }
        ensureBufferColumn()
        if (isSelecting) updateSelectionHighlight()
    }

    // ── Grid ───────────────────────────────────────────────────────────

    private fun insertCharsAt(insertCol: Int, insertRow: Int, newChars: String) =
        GridLogicHelper.insertCharsAt(columnData, columnBreaks, insertCol, insertRow, newChars, numRows, MAX_COLUMNS)

    // Shared insertion core: computes insert position from cursorBefore, calls insertCharsAt,
    // returns the focus target (one past the last inserted char), or null if blocked by a break.
    private fun performInsert(index: Int, newChars: String): Int? {
        if (newChars.isEmpty()) return null
        val cellCol = index / numRows
        val cellRow = index % numRows
        val total = editTextFields.size.takeIf { it > 0 } ?: return null
        val before = focusedCellIndex == index && cursorBefore
        val (iCol, iRow) = if (before) cellCol to cellRow
                           else {
                               val nr = cellRow + 1
                               if (nr >= numRows) (cellCol + 1) to 0 else cellCol to nr
                           }
        if (iCol > cellCol && columnBreaks.contains(iCol)) return null
        insertCharsAt(iCol, iRow, newChars)
        var fCol = iCol; var fRow = iRow
        repeat(newChars.length) { fRow++; if (fRow >= numRows) { fRow = 0; fCol++ } }
        return (fCol * numRows + fRow).coerceAtMost(total - 1)
    }

    // Enter key: column-break insertion.
    // Characters below the cursor in the current column are extracted, the column is
    // trimmed to the cursor row, and the extracted tail is prepended to the next column
    // (displacing any existing content right) via a structural column insert.
    private fun insertColumnBreak(cellCol: Int, cursorRow: Int) {
        val nextCol = (cellCol + 1).coerceAtMost(MAX_COLUMNS - 1)
        // With setSelection(0) the cursor sits before the character at cursorRow.
        // cursorRow > 0          → cursor is before that char; include it in the tail so it moves.
        // cursorRow == 0, break  → start of an existing paragraph; shift the whole column.
        // cursorRow == 0, normal → first char stays, everything below moves (standard first-Enter).
        val tailStartRow = cursorRow
        val colData = columnData.getOrNull(cellCol)
        val tail = mutableListOf<String>()
        if (colData != null) {
            for (r in tailStartRow until colData.size) tail.add(colData[r])
            while (tail.isNotEmpty() && tail.last().isEmpty()) tail.removeLast()
            while (colData.size > tailStartRow) colData.removeAt(colData.size - 1)
        }
        if (inputMode == InputMode.SCATTER) {
            while (columnData.size <= nextCol) columnData.add(mutableListOf())
            columnData[nextCol] = tail
            columnBreaks.add(nextCol)
            postRefreshFocusColumn(nextCol * numRows, nextCol)
            return
        }
        // Structural column insert: slide every columnData entry and every break index
        // at or beyond nextCol right by 1, then place tail (possibly empty) as the new
        // column at nextCol.  insertCharsAt would merge columns — wrong here.
        while (columnData.size < nextCol) columnData.add(mutableListOf())
        columnData.add(nextCol, tail)
        val shifted = columnBreaks.mapTo(mutableSetOf()) { if (it >= nextCol) it + 1 else it }
        shifted.add(nextCol)
        columnBreaks.clear(); columnBreaks.addAll(shifted)
        while (columnData.size > MAX_COLUMNS) columnData.removeAt(columnData.size - 1)
        columnBreaks.removeAll { it >= MAX_COLUMNS }
        postRefreshFocusColumn(nextCol * numRows, nextCol)
    }

    // Backspace at row 0 of a column-break column: reverse the break.
    // Removes the break marker so reflowColumnData merges the two columns,
    // then lands the cursor at the last occupied position in the preceding column.
    private fun removeColumnBreak(cellCol: Int) {
        columnBreaks.remove(cellCol)
        // Capture prevCol content count BEFORE reflow: stream position of the cursor char after merge.
        val prevContentCount = run {
            val last = columnData.getOrNull(cellCol - 1)?.indexOfLast { it.isNotEmpty() } ?: -1
            (last + 1).coerceAtLeast(0)
        }
        editTextFields.getOrNull((cellCol - 1) * numRows)?.requestFocus()
        gridContainer.post {
            if (inputMode != InputMode.SCATTER) reflowColumnData(numRows)
            needsReflow = false
            refreshGrid()
            val focusTarget = ((cellCol - 1) * numRows + prevContentCount)
                .coerceAtMost(editTextFields.size - 1)
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
            editTextFields.getOrNull(prevCol * numRows)?.requestFocus()
            gridContainer.post {
                reflowColumnData(numRows)
                refreshGrid()
                val focusTarget = (prevCol * numRows + lastOccupied.coerceAtLeast(0))
                    .coerceAtMost(editTextFields.size - 1)
                focusCell(focusTarget)
            }
        } else {
            val prevIndex = index - 1
            val colList = columnData.getOrNull(cellCol)
            if (colList != null && (cellRow - 1) < colList.size) {
                withRestoring { colList.removeAt(cellRow - 1) }
            }
            editTextFields.getOrNull(prevIndex)?.requestFocus()
            gridContainer.post {
                reflowColumnData(numRows)
                refreshGrid()
                focusCell(prevIndex)
            }
        }
    }

    private fun ensureBufferColumn() {
        // Ensure there is at least one empty column beyond the last data column.
        val neededCol = columnData.size.coerceIn(0, MAX_COLUMNS - 1)
        gridEditorController.ensureColumnBuilt(neededCol)
    }

    private fun rebuildGrid(isInitialBoot: Boolean = false) {
        val availW = gridContainer.measuredWidth
        // adjustNothing: mainScrollView.height is stable when keyboard/tools panel opens/closes.
        // Do NOT subtract paddingBottom here — that padding is keyboard avoidance headroom.
        val currentH = mainScrollView.height - gridContainer.paddingTop
        if (availW <= 0 || currentH <= 0) return
        val imeVisible = ViewCompat.getRootWindowInsets(rootFrame)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        // stableMaxHeight is locked once at startup (before any IME activity).
        val availH = if (stableMaxHeight > 0) stableMaxHeight else currentH

        // Anchor: leftmost column visible in the viewport (grid is always MAX_COLUMNS wide).
        val anchorCol: Int = run {
            val s = hScroll ?: return@run 0
            val cs = currentCellSize.takeIf { it > 0 } ?: return@run 0
            val viewport = s.width.takeIf { it > 0 } ?: return@run 0
            val gw = maxOf(MAX_COLUMNS * cs, s.width)
            (gw - (s.scrollX + viewport)) / cs
        }.coerceIn(0, MAX_COLUMNS - 1)

        builtColumns.clear()

        clearSelection()
        previewCells.clear(); isPreviewing = false
        focusedCellIndex = -1

        val fontPx   = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        val gapPx    = (wordGapDp * resources.displayMetrics.density).roundToInt()
        val charSize = Paint().apply { textSize = fontPx; typeface = selectedTypeface }
            .measureText("測").roundToInt().coerceAtLeast(1)
        val cellSize = (charSize + gapPx).coerceAtLeast(1)

        // Floor division guarantees numRows * cellSize <= availH (no vertical overflow).
        numRows = availH / cellSize
        numColumns = MAX_COLUMNS   // grid is always MAX_COLUMNS wide for accurate scrollbar
        if (numRows <= 0) return

        if (needsReflow) {
            if (inputMode != InputMode.SCATTER) reflowColumnData(numRows)
            needsReflow = false
        }

        val gridHeight = numRows * cellSize
        check(gridHeight <= availH) { "gridHeight=$gridHeight > availH=$availH" }
        currentCellSize = cellSize

        // Clear focus before destroying views; only hide IME when it's already closed.
        editTextFields.forEach { it.clearFocus() }
        currentFocus?.clearFocus()
        val imm0 = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (!imeVisible) imm0.hideSoftInputFromWindow(rootFrame.windowToken, 0)

        gridContainer.removeAllViews(); editTextFields.clear()

        // Skeleton grid: full MAX_COLUMNS width so scrollbar length is correct from frame 1.
        // Cells are added lazily by buildInitialColumns / ensureColumnBuilt.
        val grid = GridLayout(this).apply {
            rowCount = numRows; columnCount = MAX_COLUMNS
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = ViewGroup.LayoutParams(MAX_COLUMNS * cellSize, gridHeight)
        }
        hScroll = HorizontalScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(MP, gridHeight)
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(grid)
        }
        hScroll?.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (isSelecting) repositionHandles()
            // Build columns as the user scrolls into unbuilt territory.
            val cs = currentCellSize.takeIf { it > 0 } ?: return@setOnScrollChangeListener
            val gw = MAX_COLUMNS * cs
            // Highest col index whose right edge is still inside or past the left viewport edge.
            val highestVisible = ((gw - scrollX) / cs).coerceIn(0, MAX_COLUMNS - 1)
            val lastBuilt = builtColumns.maxOrNull() ?: -1
            if (highestVisible > lastBuilt) {
                gridEditorController.ensureColumnBuilt((highestVisible + 1).coerceAtMost(MAX_COLUMNS - 1))
            }
        }
        gridContainer.addView(hScroll)

        // Restore scroll so anchorCol stays at the right edge after layout.
        hScroll?.viewTreeObserver?.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                hScroll?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                val s = hScroll ?: return
                val gw = maxOf(MAX_COLUMNS * cellSize, s.width)
                val targetScrollX = (gw - anchorCol * cellSize - s.width).coerceAtLeast(0)
                s.post { s.scrollTo(targetScrollX, 0) }
            }
        })

        // Build exactly the visible columns + data columns; no background loop.
        gridEditorController.buildInitialColumns()

        // Drop the SEQUENTIAL writing-frontier marker into the cell right after the last
        // char so the user can tap into it and append.
        placeFrontierMarker()

        // All data columns are guaranteed built by the sync batch, so lastFilled is correct.
        val lastFilled = editTextFields.indexOfLast { it.text.isNotEmpty() }
        val focusIdx = if (lastFilled < 0) 0 else (lastFilled + 1).coerceAtMost(editTextFields.size - 1)
        val focusEt  = editTextFields.getOrNull(focusIdx)
        if (!isInitialBoot && focusEt != null) {
            focusEt.postDelayed({ focusCell(focusIdx, showKeyboard = imeVisible) }, 50)
            focusEt.postDelayed({
                val focused = currentFocus
                if (focused is EditText && focused in editTextFields) translateForKeyboard(focused)
            }, 200)
        }
    }

    // ── Composing preview ──────────────────────────────────────────────
    private fun showComposingPreview(text: String, startIndex: Int) {
        clearComposingPreview()
        val total = editTextFields.size.takeIf { it > 0 } ?: return
        isPreviewing = true
        editTextFields[startIndex].setTextColor(Color.GRAY)
        var next = (startIndex + 1) % total
        for (i in 1 until text.length) {
            if (next == startIndex) break
            val et = editTextFields[next]
            if (et.text.isNotEmpty()) break
            et.setText(text[i].toString()); et.setTextColor(Color.GRAY)
            previewCells.add(next); next = (next + 1) % total
        }
        isPreviewing = false
    }

    private fun clearComposingPreview() {
        isPreviewing = true
        previewCells.forEach { idx ->
            if (idx < editTextFields.size) {
                editTextFields[idx].text.clear()
                editTextFields[idx].setTextColor(gridTextColor)
            }
        }
        previewCells.clear(); isPreviewing = false
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

    // ── Cell factory ───────────────────────────────────────────────────
    private fun makeCell(row: Int, col: Int, index: Int, cellSize: Int, fontPx: Float): EditText {
        val et = EditText(this)
        et.layoutParams = GridLayout.LayoutParams(GridLayout.spec(row), GridLayout.spec(col))
            .also { it.width = cellSize; it.height = cellSize }
        et.maxLines  = 1
        et.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        et.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        et.privateImeOptions = "nm"
        et.gravity        = Gravity.CENTER
        et.textDirection  = View.TEXT_DIRECTION_LTR
        et.textAlignment  = View.TEXT_ALIGNMENT_CENTER
        et.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontPx)
        et.typeface  = selectedTypeface
        et.setTextColor(gridTextColor)
        et.setBackgroundColor(Color.TRANSPARENT)
        et.setPadding(0, 0, 0, 0)
        et.isCursorVisible = true
        // Unique id + tag prevent the system from confusing views across grid rebuilds,
        // which would cause stale state (ghost cursors, wrong IMM binding) to leak through.
        et.id  = View.generateViewId()
        et.tag = "cell_${col}_$row"

        // Focus change: sync highlight + keep column visible + push above keyboard.
        // Runs for ALL focus acquisitions — whether via focusCell(), touch, or keyboard nav.
        // The focusedCellIndex != index guard prevents the double-set when focusCell() has
        // already updated the highlight before calling requestFocus().
        et.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && focusedCellIndex != index) {
                focusedCellIndex = index
            }
            if (!hasFocus) return@setOnFocusChangeListener
            val s = hScroll ?: return@setOnFocusChangeListener
            et.post {
                if (s.width <= 0 || et.width <= 0) return@post
                val cellLeft  = et.left
                val cellRight = cellLeft + et.width
                val sx = s.scrollX; val vp = s.width
                when {
                    cellLeft  < sx       -> s.smoothScrollTo(cellLeft, 0)
                    cellRight > sx + vp  -> s.smoothScrollTo(cellRight - vp, 0)
                }
                translateForKeyboard(et)
            }
        }

        // Touch handler: event is always consumed (return true) so the native EditText
        // cursor-placement logic never runs and the cursor cannot land on the left side.
        // SEQUENTIAL empty cells redirect to the first empty row in column-major order.
        cellInputController.attachTouchListener(
            et = et,
            index = index,
            getInputMode = { inputMode },
            getIsSelecting = { isSelecting },
            clearSelection = { clearSelection() },
            hideHandlePasteMenu = { handlePasteView?.visibility = View.GONE },
            enterSelectionMode = { enterSelectionMode(it) },
            isToolsVisible = { toolsVisible },
            dismissToolsPanel = {
                allToolsPanel.visibility = View.GONE
                toolsVisible = false
                toolsCell?.setBackgroundColor(Color.TRANSPARENT)
            },
            getLastKeyboardHeight = { lastKeyboardHeight },
            setMainScrollPaddingBottom = { mainScrollView.setPadding(0, 0, 0, it) },
            getNumColumns = { numColumns },
            getNumRows = { numRows },
            getEditFieldAt = { editTextFields.getOrNull(it) },
            focusCell = { focusCell(it, showKeyboard = true) },
            scrollToColumn = { scrollToColumn(it) }
        )

        // Key handler for Enter and DEL (hardware keyboard + many soft keyboards).
        // Enter = insert a blank row at the current position, pushing content down.
        // DEL   = remove the current row's element, shift content up, move to index-1.
        cellInputController.attachKeyListener(
            et = et,
            index = index,
            isRestoring = { isRestoring },
            pushHistory = { pushHistory() },
            insertColumnBreak = { c, r -> insertColumnBreak(c, r) },
            getNumRows = { numRows },
            getInputMode = { inputMode },
            hasColumnBreak = { columnBreaks.contains(it) },
            removeColumnBreak = { removeColumnBreak(it) },
            getColumnChar = { c, r -> columnData.getOrNull(c)?.getOrNull(r) ?: "" },
            deletePreviousSequentialCell = { c, r, i -> deletePreviousSequentialCell(c, r, i) },
            handleScatterEmptyBackspace = { cellCol, cellRow, i ->
                val colList = columnData.getOrNull(cellCol)
                val focusTarget = (i - 1).coerceAtLeast(0)
                if (colList != null && cellRow < colList.size) {
                    withRestoring { colList.removeAt(cellRow); colList.add("") }
                }
                editTextFields.getOrNull(focusTarget)?.requestFocus()
                gridContainer.post { refreshGrid(); focusCell(focusTarget) }
            }
        )

        // Soft IME Enter: same column-break behaviour as hardware KEYCODE_ENTER.
        // Returning true keeps the keyboard open.
        et.setOnEditorActionListener { _, _, _ ->
            pushHistory()
            insertColumnBreak(index / numRows, index % numRows)
            true
        }

        var suppress = false
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isRestoring || isPreviewing) return
                val composing = s is Spanned && s.getSpans(0, s.length, Any::class.java)
                    .any { span -> s.getSpanFlags(span) and Spanned.SPAN_COMPOSING != 0 }
                if (composing && !s.isNullOrEmpty()) showComposingPreview(s.toString(), index)
                else if (!composing) { clearComposingPreview(); editTextFields.getOrNull(index)?.setTextColor(gridTextColor) }
            }
            override fun afterTextChanged(editable: Editable?) {
                if (isRestoring || isPreviewing) return
                if (suppress) { suppress = false; return }
                val total = editTextFields.size.takeIf { it > 0 } ?: return
                if (index >= total) return
                val stillComposing = editable is Spanned && editable.getSpans(0, editable.length, Any::class.java)
                    .any { span -> (editable as Spanned).getSpanFlags(span) and Spanned.SPAN_COMPOSING != 0 }
                if (stillComposing) return
                clearComposingPreview()
                val raw = editable?.toString() ?: return
                pushHistory()
                val cellCol = index / numRows
                val cellRow = index % numRows
                if (raw.isEmpty()) {
                    val originalChar = columnData.getOrNull(cellCol)?.getOrNull(cellRow) ?: ""
                    // Island: non-blank cell backspace-deletes with reflow regardless of mode.
                    if (originalChar.isNotEmpty() && (inputMode == InputMode.SEQUENTIAL || originalChar.isNotBlank())) {
                        deletePreviousSequentialCell(cellCol, cellRow, index)
                    } else {
                        setColumnChar(cellCol, cellRow, "")
                        if (index > 0) {
                            editTextFields.getOrNull(index - 1)?.requestFocus()
                            scrollToColumn((index - 1) / numRows)
                        }
                    }
                    return
                }
                editTextFields[index].setTextColor(gridTextColor)
                // Normalise line endings from paste
                val text = raw.replace("\r\n", "\n").replace("\r", "\n")
                val originalChar = columnData.getOrNull(cellCol)?.getOrNull(cellRow) ?: ""

                if (text.contains('\n') && text.count { it == '\n' } == 1) {
                    val withoutBreak = text.replace("\n", "")
                    if (withoutBreak == originalChar || (originalChar.isEmpty() && withoutBreak.isEmpty())) {
                        suppress = true
                        editable.replace(0, editable.length, originalChar)
                        gridContainer.post { insertColumnBreak(cellCol, cellRow) }
                        return
                    }
                }

                if (inputMode == InputMode.SCATTER && originalChar.isNotEmpty()
                        && text.length > 1 && !text.contains('\n')) {
                    if (originalChar.isNotBlank()) {
                        // Island: multi-char commit on a non-blank cell → insert-shift.
                        val newChars: String? = when {
                            text.startsWith(originalChar) -> text.substring(1)
                            text.endsWith(originalChar)   -> text.dropLast(1)
                            else                          -> null
                        }
                        if (newChars != null && newChars.isNotEmpty()) {
                            val before = focusedCellIndex == index && cursorBefore
                            val focusTarget = performInsert(index, newChars) ?: run {
                                suppress = true; editable.replace(0, editable.length, originalChar); return
                            }
                            suppress = true
                            editable.replace(0, editable.length,
                                if (before) newChars[0].toString() else originalChar)
                            postRefreshFocusColumn(focusTarget)
                            return
                        }
                    }
                    // Space cell or fallback: SCATTER overwrite.
                    val replacement = when {
                        text.startsWith(originalChar) -> text.substring(1).firstOrNull()
                        text.endsWith(originalChar)   -> text.dropLast(1).lastOrNull()
                        else                          -> text.lastOrNull()
                    }?.toString() ?: return
                    setColumnChar(cellCol, cellRow, replacement)
                    suppress = true
                    editable.replace(0, editable.length, replacement)
                    advanceToNextCell(index, total)
                    return
                }

                if (text.length > 1 || text.contains('\n')) {
                    // ── Insert-shift (SEQUENTIAL only) ────────────────────────────────
                    if (text.length >= 2 && !text.contains('\n')
                            && originalChar.isNotEmpty() && inputMode == InputMode.SEQUENTIAL) {
                        val before = focusedCellIndex == index && cursorBefore
                        val newChars: String = when {
                            text.startsWith(originalChar) -> text.substring(1)
                            text.endsWith(originalChar)   -> text.dropLast(1)
                            else -> {
                                val pivot = if (before) text.lastIndexOf(originalChar) else text.indexOf(originalChar)
                                if (pivot >= 0) {
                                    text.removeRange(pivot, pivot + originalChar.length)
                                } else {
                                    // IME variant: original char not present in committed buffer.
                                    // Keep insertion semantics by treating one edge char as the anchor.
                                    if (before) text.dropLast(originalChar.length) else text.drop(originalChar.length)
                                }
                            }
                        }
                        if (newChars.isEmpty()) return
                        val focusTarget = performInsert(index, newChars) ?: run {
                            suppress = true
                            editable.replace(0, editable.length, originalChar)
                            return
                        }
                        // Fix this cell's EditText immediately to prevent a flicker frame.
                        // before=true  → cell now holds newChars[0] (original shifted down)
                        // before=false → cell still holds originalChar (new chars went below)
                        suppress = true
                        editable.replace(0, editable.length,
                            if (before) newChars[0].toString() else originalChar)
                        gridContainer.post {
                            refreshGrid()
                            editTextFields.getOrNull(focusTarget)
                                ?.postDelayed({ focusCell(focusTarget) }, 50)
                        }
                        return
                    }
                    // ── End insert-shift ─────────────────────────────────────────────

                    var pCol = cellCol
                    var pRow = cellRow
                    var charIdx = 0

                    // Clear stale break markers for columns this paste will overwrite
                    columnBreaks.filter { it >= cellCol }.forEach { columnBreaks.remove(it) }

                    // Leading '\n' chars each start an explicit new column
                    while (charIdx < text.length && text[charIdx] == '\n') {
                        pCol++; pRow = 0; charIdx++
                        columnBreaks.add(pCol)
                        if (pCol >= MAX_COLUMNS) { charIdx = text.length; break }
                    }

                    if (charIdx < text.length) {
                        suppress = true
                        editable.replace(0, editable.length, text[charIdx].toString())
                        setColumnChar(pCol, pRow, text[charIdx].toString())
                        pRow++; charIdx++
                        if (pRow >= numRows) { pRow = 0; pCol++ }

                        withRestoring {
                            while (charIdx < text.length && pCol < MAX_COLUMNS) {
                                val ch = text[charIdx++]
                                if (ch == '\n') { pCol++; pRow = 0; columnBreaks.add(pCol); continue }
                                if (pRow >= numRows) { pRow = 0; pCol++ }
                                if (pCol >= MAX_COLUMNS) break
                                setColumnChar(pCol, pRow, ch.toString())
                                pRow++
                            }
                        }
                    } else {
                        suppress = true
                        editable.clear()
                    }

                    val finalPCol = pCol; val finalPRow = pRow
                    gridContainer.post {
                        ensureBufferColumn()
                        refreshGrid()
                        val focusCol = if (finalPRow >= numRows) minOf(finalPCol + 1, numColumns - 1) else finalPCol
                        val focusRow = if (finalPRow >= numRows) 0 else finalPRow
                        val focusIdx = (focusCol * numRows + focusRow).coerceIn(0, editTextFields.size - 1)
                        focusCell(focusIdx)
                        scrollToColumn(focusCol)
                    }
                } else {
                    // Island: non-blank occupied cell in SCATTER → insert-shift.
                    if (inputMode == InputMode.SCATTER && originalChar.isNotBlank()) {
                        val before = focusedCellIndex == index && cursorBefore
                        val focusTarget = performInsert(index, raw) ?: run {
                            suppress = true; editable.replace(0, editable.length, originalChar); return
                        }
                        suppress = true
                        editable.replace(0, editable.length,
                            if (before) raw else originalChar)
                        postRefreshFocusColumn(focusTarget)
                    } else {
                        setColumnChar(cellCol, cellRow, raw)
                        ensureBufferColumn()
                        advanceToNextCell(index, editTextFields.size)
                    }
                }
            }
        })
        return et
    }

    // ── Settings load ──────────────────────────────────────────────────
    // Called before UI construction so spinners/panels are initialised with correct values.
    private fun loadSettings() {
        fontIndex = prefs.getInt("font_index", 0).coerceIn(0, fontCatalogue.size - 1)
        selectedTypeface = fontCatalogue[fontIndex].typeface
        fontSizeSp  = prefs.getFloat("font_size_sp", 24f)
        wordGapDp   = prefs.getFloat("word_gap_dp", 3f)
        gridTextColor = prefs.getInt("text_color", Color.BLACK)
        bgColor     = prefs.getInt("bg_color", Color.WHITE)
        bgImageUri  = prefs.getString("bg_image_uri", null)
        inputMode   = when (prefs.getString("input_mode", "SEQUENTIAL")) {
            "SCATTER" -> InputMode.SCATTER; else -> InputMode.SEQUENTIAL }
        currentSessionId   = prefs.getString("current_session_id",  null) ?: java.util.UUID.randomUUID().toString()
        currentSessionName = prefs.getString("current_session_name", "文檔") ?: "文檔"

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
            Toast.makeText(this, "最多可插入 5 張圖片", Toast.LENGTH_SHORT).show()
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

    private fun loadBgImageFromUri(uriStr: String?, matrixValues: FloatArray? = null) {
        if (uriStr == null) { bgImageView?.visibility = View.GONE; bgImageUri = null; return }
        val uri = try { Uri.parse(uriStr) } catch (_: Exception) { return }
        if (bgImageView == null) {
            bgImageView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(MP, MP)
                scaleType = ImageView.ScaleType.MATRIX
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            rootFrame.addView(bgImageView, 0)
        } else {
            bgImageView!!.scaleType = ImageView.ScaleType.MATRIX
        }
        try {
            val stream = when (uri.scheme) {
                "file" -> java.io.FileInputStream(java.io.File(uri.path ?: return))
                else   -> contentResolver.openInputStream(uri)
            } ?: run { bgImageView?.visibility = View.GONE; return }
            val bmp = stream.use { BitmapFactory.decodeStream(it) }
            bgImageView!!.setImageBitmap(bmp)
            bgImageView!!.visibility = View.VISIBLE
            bgImageUri = uriStr
            val storedMatrix = matrixValues ?: run { val p = pendingBgImageMatrix; pendingBgImageMatrix = null; p }
            if (storedMatrix != null) {
                bgImageMatrix.setValues(storedMatrix)
                bgImageView!!.imageMatrix = bgImageMatrix
            } else {
                bgImageView!!.post { initBgImageMatrix(bmp.width, bmp.height) }
            }
            updateActiveImageRuntimeState()
        } catch (_: Exception) {
            bgImageView?.visibility = View.GONE
        }
    }

    private fun initBgImageMatrix(imgW: Int, imgH: Int) {
        val viewW = rootFrame.width.toFloat()
        val viewH = mainScrollView.height.toFloat()
        if (viewW <= 0 || viewH <= 0 || imgW <= 0 || imgH <= 0) return
        val scale = minOf(viewW / imgW, viewH / imgH)
        bgImageMatrix.reset()
        bgImageMatrix.setScale(scale, scale)
        bgImageMatrix.postTranslate((viewW - imgW * scale) / 2f, (viewH - imgH * scale) / 2f)
        bgImageView?.imageMatrix = bgImageMatrix
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
    private fun takeScreenshot() {
        val wasToolsVisible = toolsVisible
        if (toolsVisible) allToolsPanel.visibility = View.GONE
        startHandle?.visibility = View.GONE
        endHandle?.visibility = View.GONE
        selectionOptionsView?.visibility = View.GONE
        handlePasteView?.visibility = View.GONE
        val focusedEt = editTextFields.getOrNull(focusedCellIndex)
        focusedEt?.isCursorVisible = false

        val bmp = ScreenshotHelper.captureView(rootFrame, mainScrollView.height)
        if (bmp != null) ScreenshotHelper.saveToGallery(this, bmp)

        focusedEt?.isCursorVisible = true
        if (wasToolsVisible) allToolsPanel.visibility = View.VISIBLE
        if (isSelecting) {
            startHandle?.visibility = View.VISIBLE
            endHandle?.visibility = View.VISIBLE
            positionSelectionOptionsView()
        }
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

        if (inputMode != InputMode.SCATTER) reflowColumnData(numRows)
        placeFrontierMarker()
        refreshGrid()
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
            getBgImageMatrixValues(), inputMode.name, copyInsertedImagesState(), activeImageIndex)
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
        editorViewModel.clearHistory(); updateUndoRedoButtons()
        needsReflow = true; rebuildGrid()
    }

    private fun ensureDefaultSession() {
        editorViewModel.ensureDefaultSession(currentSessionId, currentSessionName,
            columnData, columnBreaks, fontIndex, fontSizeSp, wordGapDp,
            gridTextColor, bgColor, bgImageUri, getBgImageMatrixValues(), inputMode.name,
            copyInsertedImagesState(), activeImageIndex)
    }

    private fun refreshModeChips() {
        val container = modeChipContainer ?: return
        for (i in 0 until container.childCount) {
            val chip = container.getChildAt(i) as? TextView ?: continue
            (chip.background as? GradientDrawable)?.setColor(
                if (chip.tag == inputMode) getColor(R.color.chip_active) else Color.TRANSPARENT)
        }
    }

    // When switching SCATTER → SEQUENTIAL, fill empty slots between first and last
    // occupied cell with a half-width space so auto-advance never skips a gap.
    private fun fillGapsForSequentialMode() {
        if (numRows <= 0) return
        val paraStarts = mutableListOf(0).also { it.addAll(columnBreaks.sorted()) }
        withRestoring {
            for (i in paraStarts.indices) {
                val paraStart = paraStarts[i]
                val paraEnd = if (i + 1 < paraStarts.size) paraStarts[i + 1] - 1 else numColumns - 1
                var firstIdx = -1; var lastIdx = -1
                for (col in paraStart..paraEnd) {
                    val colData = columnData.getOrNull(col) ?: continue
                    for (row in colData.indices) {
                        if (colData[row].isNotEmpty()) {
                            val idx = col * numRows + row
                            if (firstIdx < 0) firstIdx = idx
                            lastIdx = idx
                        }
                    }
                }
                if (firstIdx < 0) continue
                for (idx in firstIdx..lastIdx) {
                    val c = idx / numRows; val r = idx % numRows
                    if ((columnData.getOrNull(c)?.getOrNull(r) ?: "").isEmpty()) setColumnChar(c, r, " ")
                }
            }
        }
        placeFrontierMarker()
        refreshGrid()
    }

    // Places a half-width space at the cell immediately after the last char of each
    // paragraph in SEQUENTIAL mode. The marker makes the writing-frontier cell tappable
    // (it's no longer empty, so the tap-redirect in CellInputController skips it) and
    // typing onto a space cell goes through the existing insert-shift path, pushing the
    // marker one cell forward automatically.
    private fun placeFrontierMarker() {
        if (inputMode != InputMode.SEQUENTIAL || numRows <= 0) return
        val paraStarts = mutableListOf(0).also { it.addAll(columnBreaks.sorted()) }
        withRestoring {
            for (i in paraStarts.indices) {
                val paraStart = paraStarts[i]
                val paraEnd = if (i + 1 < paraStarts.size) paraStarts[i + 1] - 1 else numColumns - 1
                var lastIdx = -1
                for (col in paraStart..paraEnd) {
                    val colData = columnData.getOrNull(col) ?: continue
                    for (row in colData.indices) {
                        if (colData[row].isNotEmpty()) lastIdx = col * numRows + row
                    }
                }
                if (lastIdx < 0) continue
                val nextIdx = lastIdx + 1
                val nextCol = nextIdx / numRows
                val nextRow = nextIdx % numRows
                if (nextCol > paraEnd) continue
                if ((columnData.getOrNull(nextCol)?.getOrNull(nextRow) ?: "").isEmpty()) {
                    setColumnChar(nextCol, nextRow, " ")
                    editTextFields.getOrNull(nextIdx)?.let { et ->
                        if (et.text.toString() != " ") et.setText(" ")
                    }
                }
            }
        }
    }

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
        if (pos == fontIndex && editTextFields.isNotEmpty()) return
        pushHistory()
        fontIndex = pos
        selectedTypeface = fontCatalogue[pos].typeface
        val fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        val gapPx = (wordGapDp * resources.displayMetrics.density).roundToInt()
        val newCellSize = (Paint().apply { textSize = fontPx; typeface = selectedTypeface }
            .measureText("測").roundToInt().coerceAtLeast(1) + gapPx).coerceAtLeast(1)
        if (newCellSize == currentCellSize && editTextFields.isNotEmpty()) {
            withRestoring { editTextFields.forEach { it.typeface = selectedTypeface } }
            persistCurrentState()
        } else {
            needsReflow = true; rebuildGrid()
        }
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
        var first = -1; var last = -1
        for (i in editTextFields.indices) {
            val c = i / numRows; val r = i % numRows
            if ((columnData.getOrNull(c)?.getOrNull(r) ?: "").isNotEmpty()) {
                if (first < 0) first = i; last = i
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
    override fun canUndo(): Boolean = editorViewModel.canUndo()
    override fun canRedo(): Boolean = editorViewModel.canRedo()
}
