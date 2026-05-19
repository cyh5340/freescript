package com.poemeditor

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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

class MainActivity : AppCompatActivity(), ViewFactory.Callbacks,
    InsertedImageController.Callbacks, SelectionController.Callbacks, KeyboardToolbarController.Callbacks {

    // ── Settings ───────────────────────────────────────────────────────
    private var fontSizeSp       = 24f
    private var bgColor          = Color.WHITE
    // gridTextColor is independently controlled via the text-colour panel;
    // it is never auto-derived from bgColor.
    private var gridTextColor    = Color.BLACK
    private var selectedTypeface = Typeface.DEFAULT
    private var wordGapDp        = 3f
    private var inputMode        = InputMode.SEQUENTIAL
    private var canvasMode       = CanvasMode.VERTICAL

    // ── Widget refs for programmatic sync (session load / new) ─────────
    private var fontSpinnerRef: Spinner? = null
    private var fontSizeLabelRef: TextView? = null
    private var gapValueLabelRef: TextView? = null
    private var modeChipContainer: LinearLayout? = null
    private var modeInputSectionRef: LinearLayout? = null

    // ── Horizontal mode ────────────────────────────────────────────────────
    // stableMaxWidth is the stable chars-per-line width for horizontal mode;
    // derived once when the container is measured (IME absent).
    private var stableMaxWidth = 0

    // ── Freestyle mode state ───────────────────────────────────────────────
    private val textBoxes = mutableListOf<TextBoxInstance>()
    private var activeTextBoxId: String? = null
    private var freestyleDragBox: TextBoxInstance? = null
    private var freestyleDragTouchOffX = 0f
    private var freestyleDragTouchOffY = 0f
    private var freestyleDragMoved = false
    private var freestyleLongPressHandled = false
    private var freestyleCreatingBox: TextBoxInstance? = null
    private var freestyleCreateOriginX = 0f
    private var freestyleCreateOriginY = 0f
    private var freestyleResizingBox: TextBoxInstance? = null
    private var freestyleResizeOriginX = 0f
    private var freestyleResizeOriginY = 0f
    private var freestyleResizeOrigCols = 0
    private var freestyleResizeOrigRows = 0
    private lateinit var freestyleLongPressDetector: android.view.GestureDetector
    // Refs to interact row chips exposed by ViewFactory
    private var inputFieldCellRef: TextView? = null
    private var inputFieldDividerRef: View? = null

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
    private val PREVIEW_SCALE = 0.75f
    private val FRONTIER_MARKER get() = GridLogicHelper.FRONTIER_MARKER
    private val LINE_END_MARKER get() = GridLogicHelper.LINE_END_MARKER
    private var currentCellSize = 0
    private var focusedCellIndex = -1  // index of the next insertion point; cursor drawn at top of this cell
    // columnData[col][row] = char at that cell; "" = empty.
    private var columnData = mutableListOf<MutableList<String>>()
    // Columns that begin with an explicit '\n' break (vs. auto-wrap).
    // Preserved across reflow so stanza structure survives gap/font changes.
    private var columnBreaks = mutableSetOf<Int>()
    private var needsReflow = false
    private var stableMaxHeight     = 0    // locked once at startup (IME hidden); used for all numRows calculations
    private var gridPaddingTopPx    = 0
    private var gridPaddingBottomPx = 0
    private var gridPaddingLeftPx   = 0
    private var gridPaddingRightPx  = 0
    private var lastKeyboardHeight = 0   // last measured IME height; allToolsPanel matches this height
    private var toolsVisible = false     // true when allToolsPanel is showing
    private var imeIsVisible = false     // tracks current IME visibility via insets listener
    private var keyboardCellRef: LinearLayout? = null
    private val translateHandler by lazy { Handler(Looper.getMainLooper()) }
    private var translateRunnable: Runnable? = null
    private val historyBurstHandler = Handler(Looper.getMainLooper())
    private var historyBurstReset: Runnable? = null
    private var historyBurstActive = false

    // ── Selection UI refs (state owned by SelectionController) ────────
    private var selectionOptionsView: LinearLayout? = null
    private var startHandle:     View? = null
    private var endHandle:       View? = null
    private var activeDragHandle: Boolean? = null  // true=start handle, false=end handle
    private var handleDragged    = false           // true once finger moves ≥1 cell while on a handle
    private var handlePasteView: TextView? = null  // mini bubble shown on handle tap (no drag)

    // ── Selection drag cache (populated once on ACTION_DOWN) ───────────
    private val cachedGridLoc  = IntArray(2)
    private val cachedRootLoc  = IntArray(2)
    private var cachedCellSize = 0
    private var cachedGridW    = 0
    // Vsync batching: latest drag hit index, consumed inside postOnAnimation
    private var pendingDragHit = -1
    private var isFrameScheduled = false

    // ── Priority-1 controllers ─────────────────────────────────────────
    private lateinit var imageCtrl: InsertedImageController
    private lateinit var selectionCtrl: SelectionController
    private lateinit var keyboardToolbarCtrl: KeyboardToolbarController

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

    override fun setColumnChar(col: Int, row: Int, ch: String) =
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
        if (canvasMode == CanvasMode.HORIZONTAL) {
            // In horizontal mode col = line; scroll mainScrollView vertically to show the line
            if (currentCellSize <= 0) return
            val lineTop    = col * currentCellSize
            val lineBottom = lineTop + currentCellSize
            val sv = mainScrollView.scrollY; val vp = mainScrollView.height
            when {
                lineTop  < sv       -> mainScrollView.smoothScrollTo(0, lineTop)
                lineBottom > sv + vp -> mainScrollView.smoothScrollTo(0, lineBottom - vp)
            }
            return
        }
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
    private fun focusCell(index: Int, showKeyboard: Boolean = false) {
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
                        val touchedIndex = imageCtrl.findTouchedImageIndex(midX, midY)
                        if (touchedIndex < 0) {
                            imageGestureActive = false
                            return super.dispatchTouchEvent(ev)
                        }
                        imageCtrl.activateImageAt(touchedIndex)
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

        if (selectionCtrl.isSelecting && ev.pointerCount == 1) {
            val sh = startHandle; val eh = endHandle
            if (sh != null && eh != null && sh.visibility == View.VISIBLE) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        handleDragged = false
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
                            selectionCtrl.onDragStart(activeDragHandle!!)
                            cachedCellSize = currentCellSize
                            return true  // consume DOWN so cells underneath don't clear selection
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dh = activeDragHandle
                        if (dh != null) {
                            val hit = selectionCtrl.fastCellIndexAtPoint(ev.rawX, ev.rawY)
                            if (hit >= 0) {
                                val prev = if (dh) selectionCtrl.selectionStart else selectionCtrl.selectionEnd
                                if (hit != prev) {
                                    handleDragged = true
                                    pendingDragHit = hit
                                    if (!isFrameScheduled) {
                                        isFrameScheduled = true
                                        rootFrame.postOnAnimation {
                                            if (selectionCtrl.isSelecting) {
                                                selectionCtrl.updateDragSelection(pendingDragHit)
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
                            if (!dragged && ev.actionMasked == MotionEvent.ACTION_UP) {
                                selectionCtrl.showPasteView(dh)
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
            showSoftInputOnFocus = false  // keyboard only appears via explicit ⌨ toggle
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
            if (selectionCtrl.isSelecting) selectionCtrl.repositionHandles()
            viewFactory.updateScrollIndicator()
        }
        mainScrollView.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
            if (canvasMode == CanvasMode.HORIZONTAL) viewFactory.updateScrollIndicator()
        })
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
        modeChipContainer    = viewFactory.modeChipContainer
        modeInputSectionRef  = viewFactory.modeInputSectionRef
        inputFieldCellRef        = viewFactory.inputFieldCellRef
        inputFieldDividerRef     = viewFactory.inputFieldDividerRef
        keyboardCellRef          = viewFactory.keyboardCell
        undoButton        = viewFactory.undoButton
        redoButton        = viewFactory.redoButton
        insertImageContainer = viewFactory.insertImageContainer
        imageCtrl            = InsertedImageController(this, this)
        selectionCtrl        = SelectionController(this, this)
        keyboardToolbarCtrl  = KeyboardToolbarController(this, this)
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
        imageCtrl.syncActiveImageFromList()

        // Track keyboard height and manage scroll padding in sync with IME state.
        ViewCompat.setOnApplyWindowInsetsListener(rootFrame) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (imeVisible && imeHeight > 0) lastKeyboardHeight = imeHeight
            imeIsVisible = imeVisible
            punctToolbar.visibility = if (imeVisible) View.VISIBLE else View.GONE
            if (imeVisible) {
                // Keyboard opened — dismiss tools panel and selection if active.
                if (toolsVisible) keyboardToolbarCtrl.collapseToolsPanel()
                selectionCtrl.clearSelection()
                scheduleTranslateForKeyboard()
            } else if (!toolsVisible) {
                // Keyboard closed and tools not shown — clear scroll padding.
                mainScrollView.setPadding(0, 0, 0, 0)
                mainScrollView.smoothScrollTo(0, 0)
            }
            keyboardToolbarCtrl.updateKeyboardButton()
            insets
        }

        loadState()

        val intentSessionId     = intent.getStringExtra("SESSION_ID")
        val intentCanvasModeStr = intent.getStringExtra("CANVAS_MODE")
        when {
            intentSessionId != null && intentSessionId != currentSessionId -> {
                val j = editorViewModel.loadSessionJson(intentSessionId)
                if (j != null) {
                    applyLoadedSession(SessionManager.parseSessionJson(j))
                    editorViewModel.clearHistory()
                }
            }
            intentCanvasModeStr != null -> {
                val newMode = try { CanvasMode.valueOf(intentCanvasModeStr) }
                             catch (_: Exception) { null }
                if (newMode != null) {
                    columnData.clear(); columnBreaks.clear()
                    textBoxes.clear(); activeTextBoxId = null
                    currentSessionId = java.util.UUID.randomUUID().toString()
                    currentSessionName = SessionManager.nextNewSessionName(
                        filesDir, getString(R.string.default_session_name))
                    canvasMode = newMode
                    updateToolbarSessionName()
                    editorViewModel.clearHistory()
                }
            }
            else -> ensureDefaultSession()
        }
        updateModeChipVisibility()
        applyCanvasModeLayout()
        updateUndoRedoButtons()

        // One-shot: build the grid once the container has real dimensions.
        // Settings changes (font size, gap, etc.) call rebuildGrid() explicitly.
        // We do NOT rebuild on keyboard open/close — that would destroy the focused
        // EditText and cause the keyboard to bounce.
        mainScrollView.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val imeVisible = ViewCompat.getRootWindowInsets(rootFrame)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    if (stableMaxHeight == 0 && !imeVisible && canvasMode == CanvasMode.VERTICAL) {
                        val h = mainScrollView.height - gridContainer.paddingTop - gridContainer.paddingBottom
                        if (h > 0) stableMaxHeight = h
                    }
                    if (stableMaxWidth == 0 && !imeVisible && canvasMode == CanvasMode.HORIZONTAL) {
                        val w = mainScrollView.width - gridContainer.paddingLeft - gridContainer.paddingRight
                        if (w > 0) stableMaxWidth = w
                    }
                    if (mainScrollView.width > 0 && mainScrollView.height > 0 && numRows == 0) {
                        mainScrollView.post {
                            when (canvasMode) {
                                CanvasMode.VERTICAL -> rebuildGrid(isInitialBoot = true, scrollToStart = true)
                                CanvasMode.HORIZONTAL -> {
                                    applyCanvasModeLayout()
                                    rebuildGrid(isInitialBoot = true, scrollToStart = true)
                                }
                                CanvasMode.FREESTYLE -> {
                                    applyCanvasModeLayout()
                                    val fontPx = TypedValue.applyDimension(
                                        TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
                                    val gapPx = wordGapDp * resources.displayMetrics.density
                                    val charSize = Paint().apply { textSize = fontPx; typeface = selectedTypeface }
                                        .measureText("測").roundToInt().coerceAtLeast(1)
                                    currentCellSize = (charSize + gapPx.roundToInt()).coerceAtLeast(1)
                                    val minW = mainScrollView.width
                                    val minH = mainScrollView.height.coerceAtLeast(1)
                                    poemCanvas.freestyleMinW = minW
                                    poemCanvas.freestyleMinH = minH
                                    poemCanvas.updateData(
                                        data = emptyList(), numRows = 10, maxColumns = 20,
                                        fontPx = fontPx, gapPx = gapPx,
                                        textColor = gridTextColor, typeface = selectedTypeface
                                    )
                                    numRows = 10; numColumns = 20
                                    poemCanvas.updateFreestyleBoxes(textBoxes, null, 0f, 0f)
                                }
                            }
                        }
                    }
                }
            }
        )
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

    // ── Selection ──────────────────────────────────────────────────────

    private fun cutSelectedText() { pushHistory(); selectionCtrl.cutSelectedText() }

    private fun pasteText() {
        pushHistory()
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val raw  = clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            ?.ifEmpty { null } ?: return
        val insertIdx = if (selectionCtrl.isSelecting && selectionCtrl.selectionStart >= 0)
            minOf(selectionCtrl.selectionStart, selectionCtrl.selectionEnd).coerceAtLeast(0)
        else
            focusedCellIndex.coerceAtLeast(0)
        selectionCtrl.clearSelection()
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
        selectionCtrl.clearSelection()
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

    private fun selectEntireLine()      = selectionCtrl.selectEntireLine()
    private fun selectEntireParagraph() = selectionCtrl.selectEntireParagraph(numColumns)

    // ── Reflow ─────────────────────────────────────────────────────────
    private fun reflowColumnData(newNumRows: Int) =
        GridLogicHelper.reflowColumnData(columnData, columnBreaks, newNumRows)

    private fun squeezeScatterOutOfRange(newNumRows: Int) =
        GridLogicHelper.squeezeScatterOutOfRange(columnData, newNumRows)

    private fun refreshGrid() {
        placeFrontierMarker()
        clearComposingPreview()
        if (::poemCanvas.isInitialized) {
            if (columnData.size > numColumns) {
                numColumns = columnData.size
                poemCanvas.setMaxColumns(numColumns)
                if (canvasMode == CanvasMode.VERTICAL || canvasMode == CanvasMode.HORIZONTAL)
                    viewFactory.updateScrollIndicator()
            }
            poemCanvas.refreshContent(columnData)
        }
        if (selectionCtrl.isSelecting) selectionCtrl.updateSelectionHighlight()
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
        if (canvasMode == CanvasMode.HORIZONTAL) {
            applyCanvasModeLayout()
            rebuildHorizontalGrid(isInitialBoot)
            return
        }
        if (canvasMode == CanvasMode.FREESTYLE) {
            applyCanvasModeLayout()
            updateFreestyleCanvas()
            return
        }
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

        selectionCtrl.clearSelection()
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

        // Minimum skeleton = exactly the visible columns; expand only when content demands more.
        val visibleCols = (availW / cellSize).coerceAtLeast(1)
        numColumns = maxOf(visibleCols, columnData.size)

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
        imageCtrl.updateActiveImageRuntimeState()
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
            .putString("canvas_mode", canvasMode.name)
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
            .putString("horizontal_text", "")
            .putString("text_boxes", SessionManager.textBoxesToJson(textBoxes).toString())
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
        try {
            val tbStr = prefs.getString("text_boxes", null)
            if (!tbStr.isNullOrEmpty()) {
                val loaded = SessionManager.parseTextBoxes(org.json.JSONArray(tbStr))
                textBoxes.clear(); textBoxes.addAll(loaded)
            }
        } catch (_: Exception) {}
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
        freestyleLongPressDetector = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    if (canvasMode != CanvasMode.FREESTYLE) return
                    if (poemCanvas.freestyleBoxAtTouch(e.x, e.y) == null) {
                        freestyleLongPressHandled = true
                        createFreestyleTextBox(e.x, e.y)
                    }
                }
            })

        poemCanvas.setOnTouchListener { _, event ->
            freestyleLongPressDetector.onTouchEvent(event)

            // Dismiss tools panel on any canvas tap regardless of mode
            if (event.action == MotionEvent.ACTION_DOWN && toolsVisible) keyboardToolbarCtrl.collapseToolsPanel()

            if (canvasMode == CanvasMode.FREESTYLE) {
                handleFreestyleTouchEvent(event)
                return@setOnTouchListener true
            }

            // VERTICAL mode
            if (event.action == MotionEvent.ACTION_UP) {
                val index = poemCanvas.cellIndexAtTouch(event.x, event.y)
                if (index >= 0) {
                    val now = System.currentTimeMillis()
                    if (!selectionCtrl.isSelecting && lastCanvasTapIndex == index && now - lastCanvasTapTime < 300L) {
                        lastCanvasTapTime = 0L; lastCanvasTapIndex = -1
                        selectionCtrl.enterSelectionMode(index)
                        return@setOnTouchListener true
                    }
                    if (selectionCtrl.isSelecting) {
                        selectionCtrl.clearSelection()
                    }
                    lastCanvasTapTime = now; lastCanvasTapIndex = index
                    // toolsVisible already cleared in ACTION_DOWN above
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
                    if (cellChar.isNotBlank() && cellChar != FRONTIER_MARKER && cellChar != LINE_END_MARKER) {
                        val cs = currentCellSize.coerceAtLeast(1)
                        val cellTopY = (index % nr) * cs
                        if (event.y < cellTopY + cs / 2f) focusCell(index)
                        else focusCell((index + 1).coerceAtMost(MAX_COLUMNS * numRows - 1))
                    } else {
                        focusCell(index)
                    }
                }
            }
            true
        }
    }

    private fun handleFreestyleTouchEvent(event: MotionEvent) {
        val x = event.x; val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                freestyleLongPressHandled = false
                freestyleDragMoved = false
                freestyleDragBox = null
                freestyleResizingBox = null

                val activeBox = textBoxes.find { it.id == activeTextBoxId }
                if (activeBox != null) {
                    when (poemCanvas.freestyleCornerActionAtTouch(x, y)) {
                        FreestyleCornerAction.MOVE -> {
                            freestyleDragBox = activeBox
                            freestyleDragTouchOffX = x - activeBox.leftPx
                            freestyleDragTouchOffY = y - activeBox.topPx
                            poemCanvas.parent?.requestDisallowInterceptTouchEvent(true)
                            freestyleLongPressDetector.onTouchEvent(
                                MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL })
                        }
                        FreestyleCornerAction.RESIZE -> {
                            freestyleResizingBox = activeBox
                            freestyleResizeOriginX = x
                            freestyleResizeOriginY = y
                            freestyleResizeOrigCols = activeBox.colCount
                            freestyleResizeOrigRows = activeBox.rowCount
                            poemCanvas.parent?.requestDisallowInterceptTouchEvent(true)
                            freestyleLongPressDetector.onTouchEvent(
                                MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL })
                        }
                        else -> {}
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val cs = currentCellSize.coerceAtLeast(1)
                val canvasW = poemCanvas.width.coerceAtLeast(1)
                val canvasH = poemCanvas.height.coerceAtLeast(1)

                val creatingBox = freestyleCreatingBox
                if (creatingBox != null) {
                    val dx = (x - freestyleCreateOriginX).coerceAtLeast(0f)
                    val dy = (y - freestyleCreateOriginY).coerceAtLeast(0f)
                    val maxCols = ((canvasW - freestyleCreateOriginX) / cs).toInt().coerceAtLeast(2)
                    val maxRows = ((canvasH - freestyleCreateOriginY) / cs).toInt().coerceAtLeast(2)
                    val newCols = (dx / cs).roundToInt().coerceIn(2, maxCols)
                    val newRows = (dy / cs).roundToInt().coerceIn(2, maxRows)
                    if (newCols != creatingBox.colCount || newRows != creatingBox.rowCount) {
                        creatingBox.leftPx = freestyleCreateOriginX
                        creatingBox.topPx = freestyleCreateOriginY
                        creatingBox.colCount = newCols
                        creatingBox.rowCount = newRows
                        creatingBox.columnData.clear()
                        for (col in 0 until newCols) creatingBox.columnData.add(MutableList(newRows) { "" })
                        poemCanvas.updateFreestyleBoxes(textBoxes, null, 0f, 0f)
                        poemCanvas.requestLayout()
                    }
                    freestyleDragMoved = true
                    return
                }

                val resizingBox = freestyleResizingBox
                if (resizingBox != null) {
                    freestyleDragMoved = true
                    val dx = x - freestyleResizeOriginX
                    val dy = y - freestyleResizeOriginY
                    val maxCols = ((canvasW - resizingBox.leftPx) / cs).toInt().coerceAtLeast(2)
                    val maxRows = ((canvasH - resizingBox.topPx) / cs).toInt().coerceAtLeast(2)
                    val newCols = (freestyleResizeOrigCols + dx / cs).roundToInt().coerceIn(2, maxCols)
                    val newRows = (freestyleResizeOrigRows + dy / cs).roundToInt().coerceIn(2, maxRows)
                    if (newCols != resizingBox.colCount || newRows != resizingBox.rowCount) {
                        resizingBox.colCount = newCols
                        resizingBox.rowCount = newRows
                        poemCanvas.updateFreestyleBoxes(textBoxes, activeTextBoxId, resizingBox.leftPx, resizingBox.topPx)
                        poemCanvas.requestLayout()
                    }
                    return
                }

                val dragBox = freestyleDragBox ?: return
                freestyleDragMoved = true
                val maxLeft = (canvasW - dragBox.colCount * cs).toFloat().coerceAtLeast(0f)
                val maxTop  = (canvasH - dragBox.rowCount * cs).toFloat().coerceAtLeast(0f)
                dragBox.leftPx = (x - freestyleDragTouchOffX).coerceIn(0f, maxLeft)
                dragBox.topPx  = (y - freestyleDragTouchOffY).coerceIn(0f, maxTop)
                poemCanvas.updateFreestyleBoxes(textBoxes, activeTextBoxId, dragBox.leftPx, dragBox.topPx)
                poemCanvas.requestLayout()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val creatingBox = freestyleCreatingBox
                if (creatingBox != null) {
                    freestyleCreatingBox = null
                    freestyleLongPressHandled = false
                    freestyleDragMoved = false
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        textBoxes.remove(creatingBox)
                        poemCanvas.updateFreestyleBoxes(textBoxes, null, 0f, 0f)
                        poemCanvas.requestLayout()
                    } else {
                        activateFreestyleBox(creatingBox)
                        persistCurrentState()
                    }
                    return
                }

                val resizingBox = freestyleResizingBox
                if (resizingBox != null) {
                    freestyleResizingBox = null
                    val wasDrag = freestyleDragMoved
                    freestyleDragMoved = false
                    if (event.actionMasked != MotionEvent.ACTION_CANCEL) {
                        if (wasDrag) {
                            // Reflow live columnData to new row count before saving back to box
                            val newNumRows = if (resizingBox.isHorizontal)
                                resizingBox.colCount.coerceAtLeast(2)
                            else resizingBox.rowCount.coerceAtLeast(2)
                            if (newNumRows != numRows) reflowColumnData(newNumRows)
                            numRows = newNumRows
                            deactivateFreestyleBox()
                            resizeBoxData(resizingBox, resizingBox.colCount, resizingBox.rowCount)
                            activateFreestyleBox(resizingBox)
                            persistCurrentState()
                        } else {
                            // Tap on ◢ — corner already confirmed on ACTION_DOWN, skip re-hit-test
                            showFreestyleBoxEditor(resizingBox)
                        }
                    }
                    return
                }

                val dragBox = freestyleDragBox
                val wasDrag = freestyleDragMoved
                freestyleDragBox = null
                freestyleDragMoved = false

                if (freestyleLongPressHandled) { freestyleLongPressHandled = false; return }
                if (dragBox != null && wasDrag) { persistCurrentState(); return }

                // Tap resolution
                val hitBox = poemCanvas.freestyleBoxAtTouch(x, y)
                when {
                    hitBox != null && hitBox.id != activeTextBoxId -> activateFreestyleBox(hitBox)
                    hitBox != null -> {
                        when (poemCanvas.freestyleCornerActionAtTouch(x, y)) {
                            FreestyleCornerAction.RESIZE      -> showFreestyleBoxEditor(hitBox)
                            FreestyleCornerAction.DELETE      -> deleteFreestyleBox(hitBox)
                            FreestyleCornerAction.TOGGLE_FLOW -> toggleFreestyleBoxFlow(hitBox)
                            FreestyleCornerAction.MOVE        -> { /* tap on move grip: no-op */ }
                            null -> {
                                val cellIdx = poemCanvas.cellIndexAtTouch(x, y)
                                if (cellIdx >= 0) {
                                    if (inputMode == InputMode.SEQUENTIAL) {
                                        val nr = numRows.coerceAtLeast(1)
                                        val cellChar = columnData.getOrNull(cellIdx / nr)?.getOrNull(cellIdx % nr) ?: ""
                                        if (cellChar.isEmpty()) {
                                            val target = findSequentialTapTarget(cellIdx)
                                            if (target >= 0 && target != cellIdx) {
                                                focusCell(target)
                                                return
                                            }
                                        }
                                    }
                                    focusCell(cellIdx)
                                }
                            }
                        }
                    }
                    activeTextBoxId != null -> {
                        // hitBox was null — touch may be in a corner that extends past the box boundary
                        val activeBox = textBoxes.find { it.id == activeTextBoxId }
                        when (poemCanvas.freestyleCornerActionAtTouch(x, y)) {
                            FreestyleCornerAction.RESIZE      -> if (activeBox != null) showFreestyleBoxEditor(activeBox)
                            FreestyleCornerAction.DELETE      -> if (activeBox != null) deleteFreestyleBox(activeBox)
                            FreestyleCornerAction.TOGGLE_FLOW -> if (activeBox != null) toggleFreestyleBoxFlow(activeBox)
                            FreestyleCornerAction.MOVE        -> { /* no-op */ }
                            null -> { deactivateFreestyleBox(); poemCanvas.refreshContent(emptyList()) }
                        }
                    }
                }
            }
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
        canvasMode  = try { CanvasMode.valueOf(prefs.getString("canvas_mode", "VERTICAL") ?: "VERTICAL") }
                      catch (_: Exception) { CanvasMode.VERTICAL }
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
        imageCtrl.syncActiveImageFromList()
        refreshInsertedImagePanel()
        persistCurrentState()
    }

    private fun storeInsertedImage(uri: Uri): String? = imageCtrl.storeInsertedImage(uri)

    private fun selectInsertedImage(index: Int) = imageCtrl.selectInsertedImage(index)

    private fun getBgImageMatrixValues(): FloatArray? = imageCtrl.getBgImageMatrixValues()

    private fun removeInsertedImage(index: Int = activeImageIndex) = imageCtrl.removeInsertedImage(index)

    private fun refreshInsertedImagePanel() = imageCtrl.refreshInsertedImagePanel()

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
            override fun hideBottomForOverlay() = keyboardToolbarCtrl.hideBottomForOverlay()
            override fun getNumRows()                = numRows
            override fun getCurrentCellSize()        = currentCellSize
            override fun onScreenshotRestored() {
                bottomPanel.visibility = View.VISIBLE
                if (::poemCanvas.isInitialized) poemCanvas.startCursorBlink()
                if (toolsVisible) allToolsPanel.visibility = View.VISIBLE
                viewFactory.updateScrollIndicator()
                if (selectionCtrl.isSelecting) selectionCtrl.updateSelectionHighlight()
            }
        })
    }

    private fun takeScreenshot() = screenshotController.takeScreenshot()

    private fun buildEditorButtonBar(onCancel: () -> Unit, onConfirm: () -> Unit): LinearLayout {
        val dp = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
            setBackgroundColor(Color.argb(230, 30, 30, 30))
            layoutParams = FrameLayout.LayoutParams(MP, (60 * dp).toInt(), Gravity.BOTTOM)

            fun btnView(label: String, bgColor: Int, action: () -> Unit) = TextView(this@MainActivity).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = 22f * dp
                }
                val hp = (28 * dp).toInt(); val vp = (10 * dp).toInt()
                setPadding(hp, vp, hp, vp)
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.marginEnd = (16 * dp).toInt()
                }
                setOnClickListener { action() }
            }

            addView(btnView(getString(R.string.screenshot_cancel),  Color.argb(200, 80, 80, 80),   onCancel))
            addView(btnView(getString(R.string.screenshot_confirm), Color.argb(230, 33, 150, 243), onConfirm))
        }
    }

    private fun showInputFieldEditor() {
        val toolsWereVisible = toolsVisible
        keyboardToolbarCtrl.hideBottomForOverlay()

        mainScrollView.pivotX = rootFrame.width / 2f
        mainScrollView.pivotY = rootFrame.height / 2f
        mainScrollView.scaleX = PREVIEW_SCALE
        mainScrollView.scaleY = PREVIEW_SCALE

        // Hatch background makes the area outside the scaled content clearly distinct
        // from any document background colour (black, white, grey, etc.).
        rootFrame.background = HatchDrawable(resources.displayMetrics.density)
        // Scroll indicator sits above mainScrollView in mainLayout and doesn't scale
        // with it, so hide it for the duration of adjustment.
        viewFactory.scrollIndicatorContainer?.visibility = View.GONE
        // mainScrollView is transparent by default; give it an opaque background so
        // the rootFrame hatch doesn't bleed through the content area.
        mainScrollView.setBackgroundColor(bgColor)

        // Visual rect of the scaled mainScrollView in rootFrame coordinates.
        val hm = (rootFrame.width  / 2f * (1f - PREVIEW_SCALE)).toInt()
        val vm = (rootFrame.height / 2f * (1f - PREVIEW_SCALE)).toInt()
        val scaledContentRect = Rect(hm, vm, rootFrame.width - hm, rootFrame.height - vm)

        val rootLoc   = IntArray(2); rootFrame.getLocationOnScreen(rootLoc)
        val scrollLoc = IntArray(2); mainScrollView.getLocationOnScreen(scrollLoc)

        fun restoreUI() {
            mainScrollView.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            mainScrollView.setBackgroundColor(Color.TRANSPARENT)
            rootFrame.setBackgroundColor(bgColor)
            viewFactory.updateScrollIndicator()
            bottomPanel.visibility = View.VISIBLE
            if (toolsWereVisible) keyboardToolbarCtrl.switchToTools()
        }

        lateinit var editorView: InputFieldEditorView
        var buttonBar: LinearLayout? = null

        fun dismiss() {
            rootFrame.removeView(editorView)
            buttonBar?.let { rootFrame.removeView(it) }
            restoreUI()
        }

        if (canvasMode == CanvasMode.HORIZONTAL) {
            val scrollLeft = scrollLoc[0] - rootLoc[0]
            val scrollTop  = scrollLoc[1] - rootLoc[1]
            val initLeft   = scrollLeft + (gridPaddingLeftPx  * PREVIEW_SCALE).toInt()
            val initRight  = (scrollLeft + (mainScrollView.width - gridPaddingRightPx) * PREVIEW_SCALE).toInt()
                .coerceAtMost(rootFrame.width)
            val initTop    = scrollTop + (gridPaddingTopPx * PREVIEW_SCALE).toInt()
            val initBottom = (scrollTop + (mainScrollView.height - gridPaddingBottomPx) * PREVIEW_SCALE).toInt()
                .coerceAtMost(rootFrame.height)
            editorView = InputFieldEditorView(
                this, initTop, initBottom, initLeft, initRight,
                leftRightOnly = true, scaledContentRect = scaledContentRect
            )
            editorView.layoutParams = FrameLayout.LayoutParams(MP, MP)
            buttonBar = buildEditorButtonBar(
                onCancel  = { dismiss() },
                onConfirm = {
                    val left = editorView.selectedLeft; val right = editorView.selectedRight
                    dismiss()
                    mainScrollView.post {
                        val sLeft = scrollLoc[0] - rootLoc[0]
                        gridPaddingLeftPx  = ((left  - sLeft) / PREVIEW_SCALE).toInt().coerceAtLeast(0)
                        gridPaddingRightPx = (mainScrollView.width - (right - sLeft) / PREVIEW_SCALE).toInt().coerceAtLeast(0)
                        gridContainer.setPadding(gridPaddingLeftPx, gridPaddingTopPx, gridPaddingRightPx, gridPaddingBottomPx)
                        stableMaxWidth = (mainScrollView.width - gridPaddingLeftPx - gridPaddingRightPx).coerceAtLeast(1)
                        rebuildGrid(); saveState()
                    }
                }
            )
        } else {
            val scrollTop  = scrollLoc[1] - rootLoc[1]
            val initTop    = scrollTop + (gridPaddingTopPx * PREVIEW_SCALE).toInt()
            val initBottom = (initTop + numRows * currentCellSize.coerceAtLeast(1) * PREVIEW_SCALE).toInt()
                .coerceAtMost(rootFrame.height)
            editorView = InputFieldEditorView(
                this, initTop, initBottom,
                scaledContentRect = scaledContentRect
            )
            editorView.layoutParams = FrameLayout.LayoutParams(MP, MP)
            buttonBar = buildEditorButtonBar(
                onCancel  = { dismiss() },
                onConfirm = {
                    val top = editorView.selectedTop; val bottom = editorView.selectedBottom
                    dismiss()
                    mainScrollView.post {
                        val sTop = scrollLoc[1] - rootLoc[1]
                        gridPaddingTopPx    = ((top    - sTop) / PREVIEW_SCALE).toInt().coerceAtLeast(0)
                        gridPaddingBottomPx = (mainScrollView.height - (bottom - sTop) / PREVIEW_SCALE).toInt().coerceAtLeast(0)
                        gridPaddingLeftPx   = 0; gridPaddingRightPx = 0
                        gridContainer.setPadding(0, gridPaddingTopPx, 0, gridPaddingBottomPx)
                        stableMaxHeight = (mainScrollView.height - gridPaddingTopPx - gridPaddingBottomPx).coerceAtLeast(1)
                        rebuildGrid(); saveState()
                    }
                }
            )
        }

        rootFrame.addView(editorView)
        rootFrame.addView(buttonBar)
    }

    private fun showBoxEditor(box: TextBoxInstance) {
        val toolsWereVisible = toolsVisible
        keyboardToolbarCtrl.hideBottomForOverlay()

        val canvasLoc = IntArray(2); poemCanvas.getLocationOnScreen(canvasLoc)
        val rootLoc   = IntArray(2); rootFrame.getLocationOnScreen(rootLoc)
        val cs = poemCanvas.cellSizePx.coerceAtLeast(1)

        val initLeft   = canvasLoc[0] - rootLoc[0] + box.leftPx.toInt()
        val initTop    = canvasLoc[1] - rootLoc[1] + box.topPx.toInt()
        val initRight  = initLeft + box.colCount * cs
        val initBottom = initTop  + box.rowCount * cs

        val editorView = InputFieldEditorView(
            this, initTop, initBottom, initLeft, initRight, fourEdge = true
        )
        editorView.layoutParams = FrameLayout.LayoutParams(MP, MP)

        var buttonBar: LinearLayout? = null

        fun restoreUI() {
            bottomPanel.visibility = View.VISIBLE
            if (toolsWereVisible) keyboardToolbarCtrl.switchToTools()
        }

        fun dismiss() {
            rootFrame.removeView(editorView)
            buttonBar?.let { rootFrame.removeView(it) }
            restoreUI()
        }

        buttonBar = buildEditorButtonBar(
            onCancel  = { dismiss() },
            onConfirm = {
                val left = editorView.selectedLeft; val top = editorView.selectedTop
                val right = editorView.selectedRight; val bottom = editorView.selectedBottom
                dismiss()
                val cLeft = (left  - (canvasLoc[0] - rootLoc[0])).toFloat().coerceAtLeast(0f)
                val cTop  = (top   - (canvasLoc[1] - rootLoc[1])).toFloat().coerceAtLeast(0f)
                val newCols = ((right  - left).coerceAtLeast(cs) / cs).coerceAtLeast(1)
                val newRows = ((bottom - top ).coerceAtLeast(cs) / cs).coerceAtLeast(1)
                box.leftPx   = cLeft; box.topPx    = cTop
                box.colCount = newCols; box.rowCount = newRows
                val prevNumRows = numRows
                numRows    = if (box.isHorizontal) box.colCount.coerceAtLeast(2) else box.rowCount.coerceAtLeast(2)
                numColumns = if (box.isHorizontal) box.rowCount.coerceAtLeast(2) else box.colCount.coerceAtLeast(2)
                if (numRows != prevNumRows) {
                    reflowColumnData(numRows)
                    placeFrontierMarker()
                }
                val fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
                val gapPx  = wordGapDp * resources.displayMetrics.density
                poemCanvas.updateData(columnData, numRows, numColumns, fontPx, gapPx, gridTextColor, selectedTypeface)
                currentCellSize = poemCanvas.cellSizePx.coerceAtLeast(1)
                poemCanvas.updateFreestyleBoxes(textBoxes, box.id, box.leftPx, box.topPx)
                persistCurrentState()
            }
        )

        rootFrame.addView(editorView)
        rootFrame.addView(buttonBar)
    }

    private fun showFreestyleBoxEditor(box: TextBoxInstance) {
        val toolsWereVisible = toolsVisible
        keyboardToolbarCtrl.hideBottomForOverlay()

        val canvasLoc = IntArray(2); poemCanvas.getLocationOnScreen(canvasLoc)
        val rootLoc   = IntArray(2); rootFrame.getLocationOnScreen(rootLoc)
        val cs = poemCanvas.cellSizePx.coerceAtLeast(1)
        val fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        val gapPx  = wordGapDp * resources.displayMetrics.density
        val canvasOffX = canvasLoc[0] - rootLoc[0]
        val canvasOffY = canvasLoc[1] - rootLoc[1]

        // Snapshot original state for cancel
        val origLeft    = box.leftPx;    val origTop  = box.topPx
        val origCols    = box.colCount;  val origRows = box.rowCount
        val origNumRows = numRows;       val origNumCols = numColumns
        val origData    = columnData.map { it.toMutableList() }.toMutableList()

        val initLeft   = canvasOffX + box.leftPx.toInt()
        val initTop    = canvasOffY + box.topPx.toInt()
        val initRight  = initLeft + box.colCount * cs
        val initBottom = initTop  + box.rowCount * cs

        fun applySize(top: Int, bottom: Int, left: Int, right: Int) {
            val newLeft = (left  - canvasOffX).toFloat().coerceAtLeast(0f)
            val newTop  = (top   - canvasOffY).toFloat().coerceAtLeast(0f)
            val newCols = ((right  - left ).coerceAtLeast(cs) / cs).coerceAtLeast(1)
            val newRows = ((bottom - top  ).coerceAtLeast(cs) / cs).coerceAtLeast(1)
            box.leftPx = newLeft;  box.topPx   = newTop
            box.colCount = newCols; box.rowCount = newRows
            val prevNR = numRows
            numRows    = if (box.isHorizontal) newCols.coerceAtLeast(2) else newRows.coerceAtLeast(2)
            numColumns = if (box.isHorizontal) newRows.coerceAtLeast(2) else newCols.coerceAtLeast(2)
            if (numRows != prevNR) { reflowColumnData(numRows); placeFrontierMarker() }
            poemCanvas.updateData(columnData, numRows, numColumns, fontPx, gapPx, gridTextColor, selectedTypeface)
            currentCellSize = poemCanvas.cellSizePx.coerceAtLeast(1)
            poemCanvas.updateFreestyleBoxes(textBoxes, box.id, box.leftPx, box.topPx)
        }

        val editorView = InputFieldEditorView(
            this, initTop, initBottom, initLeft, initRight, fourEdge = true
        )
        editorView.onDrag = { t, b, l, r -> applySize(t, b, l, r) }
        editorView.layoutParams = FrameLayout.LayoutParams(MP, MP)

        var buttonBar: LinearLayout? = null

        fun restoreUI() {
            bottomPanel.visibility = View.VISIBLE
            if (toolsWereVisible) keyboardToolbarCtrl.switchToTools()
        }

        fun dismiss() {
            rootFrame.removeView(editorView)
            buttonBar?.let { rootFrame.removeView(it) }
            restoreUI()
        }

        buttonBar = buildEditorButtonBar(
            onCancel = {
                dismiss()
                box.leftPx = origLeft; box.topPx = origTop
                box.colCount = origCols; box.rowCount = origRows
                numRows = origNumRows; numColumns = origNumCols
                columnData.clear(); origData.forEach { col -> columnData.add(col.toMutableList()) }
                poemCanvas.updateData(columnData, numRows, numColumns, fontPx, gapPx, gridTextColor, selectedTypeface)
                currentCellSize = poemCanvas.cellSizePx.coerceAtLeast(1)
                poemCanvas.updateFreestyleBoxes(textBoxes, box.id, box.leftPx, box.topPx)
                poemCanvas.refreshContent(columnData)
            },
            onConfirm = { dismiss(); persistCurrentState() }
        )

        rootFrame.addView(editorView)
        rootFrame.addView(buttonBar)
    }

    // ── Undo / Redo ────────────────────────────────────────────────────
    private fun snapshotState(): EditorHistoryState {
        imageCtrl.updateActiveImageRuntimeState()
        val boxSnapshot = textBoxes.map { box ->
            TextBoxInstance(
                id = box.id, leftPx = box.leftPx, topPx = box.topPx,
                colCount = box.colCount, rowCount = box.rowCount,
                columnData = box.columnData.map { it.toMutableList() }.toMutableList(),
                columnBreaks = box.columnBreaks.toMutableSet(),
                fontIndex = box.fontIndex, fontSizeSp = box.fontSizeSp,
                wordGapDp = box.wordGapDp, gridTextColor = box.gridTextColor,
                inputMode = box.inputMode, isHorizontal = box.isHorizontal
            )
        }
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
            activeImageIndex = activeImageIndex,
            textBoxes = boxSnapshot
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
        // Detach from any box reference before mutating
        columnData = mutableListOf()
        columnBreaks = mutableSetOf()
        state.data.forEach { columnData.add(it.toMutableList()) }
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

        // Restore mode-specific state
        textBoxes.clear()
        state.textBoxes.forEach { box ->
            textBoxes.add(TextBoxInstance(
                id = box.id, leftPx = box.leftPx, topPx = box.topPx,
                colCount = box.colCount, rowCount = box.rowCount,
                columnData = box.columnData.map { it.toMutableList() }.toMutableList(),
                columnBreaks = box.columnBreaks.toMutableSet(),
                fontIndex = box.fontIndex, fontSizeSp = box.fontSizeSp,
                wordGapDp = box.wordGapDp, gridTextColor = box.gridTextColor,
                inputMode = box.inputMode, isHorizontal = box.isHorizontal
            ))
        }
        if (canvasMode == CanvasMode.FREESTYLE) {
            activeTextBoxId = null
            columnData = mutableListOf()
            columnBreaks = mutableSetOf()
            updateFreestyleCanvas()
        }

        when (canvasMode) {
            CanvasMode.VERTICAL -> { needsReflow = (inputMode != InputMode.SCATTER); rebuildGrid() }
            CanvasMode.HORIZONTAL -> { needsReflow = (inputMode != InputMode.SCATTER); rebuildGrid() }
            else -> {}
        }
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
        imageCtrl.syncActiveImageFromList()
        refreshInsertedImagePanel()
    }

    private fun saveSession() {
        if (canvasMode == CanvasMode.FREESTYLE) {
            val box = textBoxes.find { it.id == activeTextBoxId }
            if (box != null) {
                box.fontIndex = fontIndex; box.fontSizeSp = fontSizeSp
                box.wordGapDp = wordGapDp; box.gridTextColor = gridTextColor
            }
        }
        imageCtrl.updateActiveImageRuntimeState()
        editorViewModel.saveSession(SessionDocument(
            id = currentSessionId, name = currentSessionName,
            canvasMode = canvasMode.name,
            columnData = columnData, columnBreaks = columnBreaks,
            fontIndex = fontIndex, fontSizeSp = fontSizeSp, wordGapDp = wordGapDp,
            gridTextColor = gridTextColor, bgColor = bgColor,
            bgImageUri = bgImageUri, bgImageMatrix = getBgImageMatrixValues(),
            inputMode = inputMode.name,
            insertedImages = copyInsertedImagesState(), activeImageIndex = activeImageIndex,
            gridPadTop = gridPaddingTopPx, gridPadBottom = gridPaddingBottomPx,
            gridPadLeft = gridPaddingLeftPx, gridPadRight = gridPaddingRightPx,
            textBoxes = textBoxes.toList()
        ))
    }

    private fun applyLoadedSession(doc: SessionDocument) {
        columnData   = doc.columnData.map { it.toMutableList() }.toMutableList()
        columnBreaks = doc.columnBreaks.toMutableSet()
        currentSessionId   = doc.id
        currentSessionName = doc.name
        canvasMode = try { CanvasMode.valueOf(doc.canvasMode) } catch (_: Exception) { CanvasMode.VERTICAL }
        updateToolbarSessionName()
        gridPaddingTopPx    = doc.gridPadTop
        gridPaddingBottomPx = doc.gridPadBottom
        gridPaddingLeftPx   = doc.gridPadLeft
        gridPaddingRightPx  = doc.gridPadRight
        gridContainer.setPadding(gridPaddingLeftPx, gridPaddingTopPx, gridPaddingRightPx, gridPaddingBottomPx)
        textBoxes.clear()
        textBoxes.addAll(doc.textBoxes)
        activeTextBoxId = null
        val mode = try { InputMode.valueOf(doc.inputMode) } catch (_: Exception) { InputMode.SEQUENTIAL }
        applySettings(
            fontIdx            = doc.fontIndex,
            sizeSp             = doc.fontSizeSp,
            gapDp              = doc.wordGapDp,
            textColor          = doc.gridTextColor,
            bgCol              = doc.bgColor,
            imgUri             = doc.bgImageUri,
            imgMatrixValues    = doc.bgImageMatrix,
            mode               = mode,
            images             = doc.insertedImages,
            selectedImageIndex = doc.activeImageIndex
        )
    }

    private fun loadSessionFile(id: String) {
        if (id == currentSessionId) return
        saveSession()
        val j = editorViewModel.loadSessionJson(id) ?: return
        applyLoadedSession(SessionManager.parseSessionJson(j))
        if (mainScrollView.height > 0) {
            stableMaxHeight = (mainScrollView.height - gridPaddingTopPx - gridPaddingBottomPx)
                .coerceAtLeast(1)
        }
        editorViewModel.clearHistory(); updateUndoRedoButtons()
        updateModeChipVisibility()
        applyCanvasModeLayout()
        if (canvasMode == CanvasMode.VERTICAL) {
            needsReflow = true; rebuildGrid(scrollToStart = true)
        } else if (canvasMode == CanvasMode.HORIZONTAL) {
            needsReflow = true; rebuildGrid(scrollToStart = true)
        } else if (canvasMode == CanvasMode.FREESTYLE) {
            val fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
            val gapPx = wordGapDp * resources.displayMetrics.density
            val charSize = Paint().apply { textSize = fontPx; typeface = selectedTypeface }
                .measureText("測").roundToInt().coerceAtLeast(1)
            currentCellSize = (charSize + gapPx.roundToInt()).coerceAtLeast(1)
            if (::poemCanvas.isInitialized) {
                val minW = mainScrollView.width.takeIf { it > 0 } ?: poemCanvas.freestyleMinW
                val minH = mainScrollView.height.takeIf { it > 0 } ?: poemCanvas.freestyleMinH
                poemCanvas.freestyleMinW = minW
                poemCanvas.freestyleMinH = minH
                poemCanvas.updateData(emptyList(), 10, 20, fontPx, gapPx, gridTextColor, selectedTypeface)
                numRows = 10; numColumns = 20
                poemCanvas.updateFreestyleBoxes(textBoxes, null, 0f, 0f)
            }
        }
    }

    private fun ensureDefaultSession() {
        editorViewModel.ensureDefaultSession(SessionDocument(
            id = currentSessionId, name = currentSessionName,
            canvasMode = canvasMode.name,
            columnData = columnData, columnBreaks = columnBreaks,
            fontIndex = fontIndex, fontSizeSp = fontSizeSp, wordGapDp = wordGapDp,
            gridTextColor = gridTextColor, bgColor = bgColor,
            bgImageUri = bgImageUri, bgImageMatrix = getBgImageMatrixValues(),
            inputMode = inputMode.name,
            insertedImages = copyInsertedImagesState(), activeImageIndex = activeImageIndex,
            gridPadTop = gridPaddingTopPx, gridPadBottom = gridPaddingBottomPx,
            gridPadLeft = gridPaddingLeftPx, gridPadRight = gridPaddingRightPx
        ))
    }

    private fun updateModeChipVisibility() {
        val showInput = canvasMode == CanvasMode.VERTICAL ||
                        canvasMode == CanvasMode.HORIZONTAL ||
                        (canvasMode == CanvasMode.FREESTYLE && activeTextBoxId != null)
        modeInputSectionRef?.visibility = if (showInput) View.VISIBLE else View.GONE
    }

    private fun applyCanvasModeLayout() {
        when (canvasMode) {
            CanvasMode.HORIZONTAL -> {
                hScroll?.visibility = View.VISIBLE
                hScroll?.layoutDirection = View.LAYOUT_DIRECTION_LTR
                poemCanvas.isHorizontalMode = true
                poemCanvas.isFreestyleMode = false

                inputFieldCellRef?.visibility = View.VISIBLE
                inputFieldDividerRef?.visibility = View.VISIBLE
            }
            CanvasMode.FREESTYLE -> {
                hScroll?.visibility = View.VISIBLE
                hScroll?.layoutDirection = View.LAYOUT_DIRECTION_RTL
                poemCanvas.isHorizontalMode = false
                poemCanvas.isFreestyleMode = true
                // interact section shown only by activateFreestyleBox
                inputFieldCellRef?.visibility = View.GONE
                inputFieldDividerRef?.visibility = View.GONE
                // FREESTYLE canvas is always full-screen; clear any padding from other modes
                gridPaddingTopPx = 0; gridPaddingBottomPx = 0
                gridPaddingLeftPx = 0; gridPaddingRightPx = 0
                gridContainer.setPadding(0, 0, 0, 0)
            }
            else -> {
                hScroll?.visibility = View.VISIBLE
                hScroll?.layoutDirection = View.LAYOUT_DIRECTION_RTL
                poemCanvas.isHorizontalMode = false
                poemCanvas.isFreestyleMode = false

                inputFieldCellRef?.visibility = View.VISIBLE
                inputFieldDividerRef?.visibility = View.VISIBLE
            }
        }
    }

    private fun rebuildHorizontalGrid(isInitialBoot: Boolean = false) {
        val availW = if (stableMaxWidth > 0) stableMaxWidth
                     else (mainScrollView.width - gridContainer.paddingLeft - gridContainer.paddingRight).coerceAtLeast(1)
        if (availW <= 0) return

        val fontPx   = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        val gapPx    = wordGapDp * resources.displayMetrics.density
        val charSize = Paint().apply { textSize = fontPx; typeface = selectedTypeface }
            .measureText("測").roundToInt().coerceAtLeast(1)
        val cellSize = (charSize + gapPx.roundToInt()).coerceAtLeast(1)

        // numRows = chars per line (horizontal), numColumns = line count (grows vertically)
        val newNumRows = (availW / cellSize).coerceAtLeast(1)

        if (inputMode != InputMode.SCATTER && (needsReflow || (numRows > 0 && newNumRows != numRows))) {
            reflowColumnData(newNumRows)
        }
        needsReflow = false
        numRows = newNumRows
        val availH = if (stableMaxHeight > 0) stableMaxHeight
                     else (mainScrollView.height - gridContainer.paddingTop - gridContainer.paddingBottom).coerceAtLeast(1)
        val visibleLines = (availH / cellSize).coerceAtLeast(1)
        numColumns = maxOf(visibleLines, columnData.size)
        currentCellSize = cellSize

        poemCanvas.updateData(
            data = columnData, numRows = numRows, maxColumns = numColumns,
            fontPx = fontPx, gapPx = gapPx, textColor = gridTextColor, typeface = selectedTypeface
        )

        placeFrontierMarker()

        if (!isInitialBoot) {
            val imeVisible = ViewCompat.getRootWindowInsets(rootFrame)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            val lastRealIdx = (0 until numColumns * numRows).indexOfLast { i ->
                val c = i / numRows; val r = i % numRows
                (columnData.getOrNull(c)?.getOrNull(r) ?: "").isNotEmpty()
            }
            val focusIdx = if (lastRealIdx < 0) 0
                else (lastRealIdx + 1).coerceAtMost(numColumns * numRows - 1)
            poemCanvas.postDelayed({ focusCell(focusIdx, showKeyboard = imeVisible) }, 50)
        }
    }

    private fun updateFreestyleCanvas() {
        if (!::poemCanvas.isInitialized) return
        poemCanvas.isFreestyleMode = true
        val w = mainScrollView.width.takeIf { it > 0 }
        val h = mainScrollView.height.takeIf { it > 0 }
        if (w != null) poemCanvas.freestyleMinW = w
        if (h != null) poemCanvas.freestyleMinH = h
        val activeBox = textBoxes.find { it.id == activeTextBoxId }
        if (activeBox != null && numRows > 0) {
            val fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
            val gapPx = wordGapDp * resources.displayMetrics.density
            poemCanvas.updateData(
                data = columnData, numRows = numRows, maxColumns = numColumns,
                fontPx = fontPx, gapPx = gapPx, textColor = gridTextColor, typeface = selectedTypeface
            )
        }
        poemCanvas.updateFreestyleBoxes(
            textBoxes, activeTextBoxId,
            activeBox?.leftPx ?: 0f, activeBox?.topPx ?: 0f
        )
    }

    private fun activateFreestyleBox(box: TextBoxInstance) {
        deactivateFreestyleBox()
        activeTextBoxId = box.id

        fontIndex = box.fontIndex.coerceIn(0, fontCatalogue.size - 1)
        selectedTypeface = fontCatalogue[fontIndex].typeface
        fontSizeSp = box.fontSizeSp
        wordGapDp = box.wordGapDp
        gridTextColor = box.gridTextColor
        inputMode = box.inputMode

        columnData = box.columnData
        columnBreaks = box.columnBreaks

        val dp = resources.displayMetrics.density
        val fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        val gapPx = wordGapDp * dp
        numRows    = if (box.isHorizontal) box.colCount.coerceAtLeast(2) else box.rowCount.coerceAtLeast(2)
        numColumns = if (box.isHorizontal) box.rowCount.coerceAtLeast(2) else box.colCount.coerceAtLeast(2)

        // updateData() computes cellSizePx internally; read it back instead of allocating a new Paint
        poemCanvas.updateData(
            data = columnData, numRows = numRows, maxColumns = numColumns,
            fontPx = fontPx, gapPx = gapPx, textColor = gridTextColor, typeface = selectedTypeface
        )
        currentCellSize = poemCanvas.cellSizePx.coerceAtLeast(1)

        poemCanvas.updateFreestyleBoxes(textBoxes, box.id, box.leftPx, box.topPx)

        placeFrontierMarker()
        poemCanvas.refreshContent(columnData)
        fontSpinnerRef?.setSelection(fontIndex)
        refreshModeChips()

        updateModeChipVisibility()
        poemCanvas.post { focusCell(0) }
    }

    private fun deactivateFreestyleBox() {
        val activeId = activeTextBoxId ?: return
        val box = textBoxes.find { it.id == activeId } ?: run {
            activeTextBoxId = null; return
        }
        box.fontIndex = fontIndex
        box.fontSizeSp = fontSizeSp
        box.wordGapDp = wordGapDp
        box.gridTextColor = gridTextColor
        box.inputMode = inputMode
        activeTextBoxId = null
        columnData = mutableListOf()
        columnBreaks = mutableSetOf()
        poemCanvas.stopCursorBlink()
        poemCanvas.updateFreestyleBoxes(textBoxes, null, 0f, 0f)
        updateModeChipVisibility()
    }

    private fun deleteFreestyleBox(box: TextBoxInstance) {
        deactivateFreestyleBox()
        textBoxes.removeAll { it.id == box.id }
        poemCanvas.updateFreestyleBoxes(textBoxes, null, 0f, 0f)
        poemCanvas.refreshContent(emptyList())
        persistCurrentState()
    }

    private fun toggleFreestyleBoxFlow(box: TextBoxInstance) {
        box.isHorizontal = !box.isHorizontal
        activateFreestyleBox(box)
        persistCurrentState()
    }

    private fun createFreestyleTextBox(x: Float, y: Float) {
        val cs = currentCellSize.coerceAtLeast(1)
        val canvasW = if (poemCanvas.width > 0) poemCanvas.width else mainScrollView.width
        val canvasH = if (poemCanvas.height > 0) poemCanvas.height
                      else (mainScrollView.height - gridContainer.paddingTop - gridContainer.paddingBottom).coerceAtLeast(1)
        val box = TextBoxInstance(
            leftPx = 0f,
            topPx  = 0f,
            colCount = (canvasW / cs).coerceAtLeast(2),
            rowCount = (canvasH / cs).coerceAtLeast(2),
            fontIndex = fontIndex,
            fontSizeSp = fontSizeSp,
            wordGapDp = wordGapDp,
            gridTextColor = gridTextColor
        )
        for (col in 0 until box.colCount) {
            box.columnData.add(MutableList(box.rowCount) { "" })
        }
        textBoxes.add(box)
        freestyleCreatingBox = box
        freestyleCreateOriginX = x
        freestyleCreateOriginY = y
        freestyleDragMoved = false
        poemCanvas.updateFreestyleBoxes(textBoxes, null, 0f, 0f)
        poemCanvas.requestLayout()
        // Activation deferred to ACTION_UP so the user can drag to resize first.
    }

    private fun resizeBoxData(box: TextBoxInstance, newCols: Int, newRows: Int) {
        while (box.columnData.size > newCols) box.columnData.removeAt(box.columnData.size - 1)
        while (box.columnData.size < newCols) box.columnData.add(MutableList(newRows) { "" })
        for (col in box.columnData) {
            while (col.size > newRows) col.removeAt(col.size - 1)
            while (col.size < newRows) col.add("")
        }
        box.colCount = newCols
        box.rowCount = newRows
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

    // ── InsertedImageController.Callbacks ────────────────────────────
    override fun getInsertImageContainer(): LinearLayout? = insertImageContainer
    override fun getCurrentSessionId(): String = currentSessionId
    override fun getInsertedImages(): MutableList<InsertedImageState> = insertedImages
    override fun getActiveImageIndex(): Int = activeImageIndex
    override fun setActiveImageIndex(index: Int) { activeImageIndex = index }
    override fun getBgImageUri(): String? = bgImageUri
    override fun setBgImageUri(uri: String?) { bgImageUri = uri }
    override fun getBgImageMatrix(): Matrix = bgImageMatrix
    override fun getBgImageViews(): MutableList<ImageView> = bgImageViews
    override fun setBgImageView(view: ImageView?) { bgImageView = view }
    override fun onImageStateChanged() { pushHistory(); persistCurrentState() }
    override fun getMaxInsertedImages(): Int = SessionManager.MAX_INSERTED_IMAGES

    // ── SelectionController.Callbacks ────────────────────────────────
    override fun getPoemCanvas(): PoemCanvasView = poemCanvas
    override fun getStartHandle(): View? = startHandle
    override fun getEndHandle(): View? = endHandle
    override fun getSelectionOptionsView(): LinearLayout? = selectionOptionsView
    override fun getHandlePasteView(): TextView? = handlePasteView
    override fun getNumRows(): Int = numRows
    override fun getMaxColumns(): Int = numColumns
    override fun getColumnData(): List<List<String>> = columnData
    override fun getColumnBreaks(): Set<Int> = columnBreaks
    override fun getFocusedCellIndex(): Int = focusedCellIndex
    override fun focusCell(index: Int) = focusCell(index, false)
    override fun reflowAfterCut(from: Int) {
        reflowColumnData(numRows)
        postRefreshFocusColumn(from)
    }
    override fun hideIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(rootFrame.windowToken, 0)
        currentFocus?.clearFocus()
    }

    // ── KeyboardToolbarController.Callbacks ───────────────────────────
    override fun getAllToolsPanel(): LinearLayout = allToolsPanel
    override fun getBottomPanel(): LinearLayout = bottomPanel
    override fun getGhostInput(): EditText = ghostInput
    override fun getToolsCell(): LinearLayout? = toolsCell
    override fun getKeyboardCellRef(): LinearLayout? = keyboardCellRef
    override fun isToolsVisible(): Boolean = toolsVisible
    override fun setToolsVisible(value: Boolean) { toolsVisible = value }
    override fun getLastKeyboardHeight(): Int = lastKeyboardHeight
    override fun isImeVisible(): Boolean = imeIsVisible
    override fun getColorRowActive(): Int = getColor(R.color.row_active)
    override fun getColorTextHint(): Int = getColor(R.color.text_hint)
    override fun onBeforeSwitchToTools() { selectionCtrl.clearSelection(); currentFocus?.clearFocus() }
    override fun onAfterSwitchToTools() { refreshInsertedImagePanel() }

    // ── Shared callbacks (getRootFrame / getMainScrollView / getCanvasMode) ───────────
    override fun getRootFrame(): FrameLayout = rootFrame
    override fun getMainScrollView(): NestedScrollView = mainScrollView
    override fun getCanvasMode(): CanvasMode = canvasMode

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
        if (canvasMode == CanvasMode.FREESTYLE) {
            textBoxes.find { it.id == activeTextBoxId }?.inputMode = mode
            if (mode == InputMode.SEQUENTIAL && previous == InputMode.SCATTER) {
                fillGapsForSequentialMode()
            }
            placeFrontierMarker()
            poemCanvas.refreshContent(columnData)
        } else if (mode == InputMode.SEQUENTIAL && previous == InputMode.SCATTER) {
            fillGapsForSequentialMode()
        }
        persistCurrentState()
    }

    override fun onToolsToggle() { if (toolsVisible) keyboardToolbarCtrl.collapseToolsPanel() else keyboardToolbarCtrl.switchToTools() }
    override fun onCollapsePanel() { keyboardToolbarCtrl.collapseToolsPanel() }
    override fun onKeyboardToggle() {
        if (imeIsVisible) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(rootFrame.windowToken, 0)
        } else {
            keyboardToolbarCtrl.switchToKeyboard()
        }
    }

    override fun onPunctInsert(punct: String) { insertPunct(punct) }

    override fun onFontSelected(pos: Int) {
        if (pos == fontIndex && numRows > 0) return
        pushHistory()
        fontIndex = pos
        selectedTypeface = fontCatalogue[pos].typeface
        if (canvasMode == CanvasMode.FREESTYLE) {
            val box = textBoxes.find { it.id == activeTextBoxId }
            if (box != null) { box.fontIndex = pos; activateFreestyleBox(box) }
            persistCurrentState(); return
        }
        needsReflow = true; rebuildGrid()
    }

    override fun onFontSizeChanged(newSize: Float) {
        if (newSize == fontSizeSp) return
        pushHistory()
        fontSizeSp = newSize
        if (canvasMode == CanvasMode.FREESTYLE) {
            val box = textBoxes.find { it.id == activeTextBoxId }
            if (box != null) { box.fontSizeSp = newSize; activateFreestyleBox(box) }
            persistCurrentState(); return
        }
        needsReflow = true; rebuildGrid()
    }

    override fun onWordGapChanged(newGap: Int) {
        val gap = newGap.toFloat()
        if (gap == wordGapDp) return
        pushHistory()
        wordGapDp = gap
        if (canvasMode == CanvasMode.FREESTYLE) {
            val box = textBoxes.find { it.id == activeTextBoxId }
            if (box != null) { box.wordGapDp = gap; activateFreestyleBox(box) }
            persistCurrentState(); return
        }
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
        gridTextColor = color
        if (canvasMode == CanvasMode.FREESTYLE) {
            val box = textBoxes.find { it.id == activeTextBoxId }
            if (box != null) { box.gridTextColor = color; activateFreestyleBox(box) }
            else updateFreestyleCanvas()
            persistCurrentState(); return
        }
        applyTextColor(color)
        persistCurrentState()
    }

    override fun onCopySelection() { selectionCtrl.copySelectedText() }
    override fun onCutSelection() { cutSelectedText() }
    override fun onPasteAtSelection() { pasteText() }
    override fun onSelectEntireLine() { selectEntireLine() }
    override fun onSelectEntireParagraph() { selectEntireParagraph() }
    override fun onSelectAll() = selectionCtrl.selectAll(numColumns)

    override fun onHandlePasteClicked() {
        val isStart = selectionCtrl.tappedHandleIsStart ?: return
        val targetIndex = if (isStart) selectionCtrl.selectionStart else selectionCtrl.selectionEnd
        pasteTextAt(targetIndex)
        selectionCtrl.hidePasteView()
    }

    override fun onShowAllSessions() {
        val intent = Intent(this, SessionListActivity::class.java)
            .putExtra("current_session_id", currentSessionId)
            .putExtra("current_canvas_mode", canvasMode.name)
        sessionListLauncher.launch(intent)
    }

    override fun onUndoAction() { performUndo() }
    override fun onRedoAction() { performRedo() }
    override fun onScreenshot() { takeScreenshot() }
    override fun onInputFieldEdit() {
        val activeBox = textBoxes.find { it.id == activeTextBoxId }
        if (canvasMode == CanvasMode.FREESTYLE && activeBox != null) showFreestyleBoxEditor(activeBox)
        else showInputFieldEditor()
    }
    override fun canUndo(): Boolean = editorViewModel.canUndo()
    override fun canRedo(): Boolean = editorViewModel.canRedo()
    override fun getNumColumns(): Int = numColumns
    override fun getCurrentCellSize(): Int = currentCellSize
    override fun getHScrollView(): HorizontalScrollView? = hScroll
}
