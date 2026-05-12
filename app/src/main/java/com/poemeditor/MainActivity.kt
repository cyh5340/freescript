package com.poemeditor

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
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
    private lateinit var imagePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var sessionListLauncher: ActivityResultLauncher<Intent>

    // ── Document management ────────────────────────────────────────────
    private var currentSessionId: String = java.util.UUID.randomUUID().toString()
    private var currentSessionName: String = "文檔"
    private var docListContainer: LinearLayout? = null

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
    private var lastTapIndex     = -1
    private var lastTapTime      = 0L
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
    private var incrementalBuildRunnable: Runnable? = null
    private var buildGeneration = 0

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
        saveSession(currentSessionId, currentSessionName)
        saveState()
    }

    private fun advanceToNextCell(index: Int, total: Int) {
        val nextIdx = (index + 1).coerceAtMost(total - 1)
        focusedCellIndex = nextIdx
        cursorBefore = true
        editTextFields.getOrNull(nextIdx)?.requestFocus()
        scrollToColumn(nextIdx / numRows)
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
        ensureColumnBuilt(index / numRows.coerceAtLeast(1))
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
        loadSettings()
        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) applyBackgroundImage(uri)
        }
        sessionListLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val id = result.data?.getStringExtra("session_id") ?: return@registerForActivityResult
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

        viewFactory      = ViewFactory(this, this)
        bottomPanel      = viewFactory.buildBottomPanel(dp)
        allToolsPanel    = viewFactory.allToolsPanel!!
        punctToolbar     = viewFactory.punctToolbar!!
        toolsCell        = viewFactory.toolsCell
        docListContainer = viewFactory.docListContainer
        fontSpinnerRef    = viewFactory.fontSpinnerRef
        fontSizeLabelRef  = viewFactory.fontSizeLabelRef
        gapValueLabelRef  = viewFactory.gapValueLabelRef
        modeChipContainer = viewFactory.modeChipContainer
        mainLayout.addView(mainScrollView)
        mainLayout.addView(bottomPanel)
        rootFrame.addView(mainLayout)
        rootFrame.addView(allToolsPanel)
        setContentView(rootFrame)
        val dp0 = resources.displayMetrics.density
        startHandle = viewFactory.buildSelectionHandle(isStart = true,  dp0).also { rootFrame.addView(it) }
        endHandle   = viewFactory.buildSelectionHandle(isStart = false, dp0).also { rootFrame.addView(it) }
        selectionOptionsView = viewFactory.buildSelectionOptionsView(dp0).also { rootFrame.addView(it) }
        handlePasteView = viewFactory.buildHandlePasteView(dp0).also { rootFrame.addView(it) }
        loadBgImageFromUri(bgImageUri)

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
        val to   = maxOf(selectionStart, selectionEnd).coerceAtMost(editTextFields.size - 1)
        val etFrom = editTextFields.getOrNull(from) ?: return
        val etTo   = editTextFields.getOrNull(to)   ?: return
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
        bgImageUri = null
        bgImageView?.visibility = View.GONE
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
        refreshDocPanel()
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
        insertCharsAt(iCol, iRow, chars)
        val focusTarget = (iCol * numRows + iRow + chars.length).coerceAtMost(editTextFields.size - 1)
        postRefreshFocusColumn(focusTarget)
        Toast.makeText(this, "已貼上", Toast.LENGTH_SHORT).show()
    }

    private fun pasteTextAt(insertIdx: Int) {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val raw  = clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            ?.ifEmpty { null } ?: return
        clearSelection()
        val iCol  = insertIdx / numRows
        val iRow  = insertIdx % numRows
        val chars = raw.replace("\r\n", "\n").replace("\r", "\n").filter { it != '\n' }
        if (chars.isEmpty()) return
        insertCharsAt(iCol, iRow, chars)
        val focusTarget = (iCol * numRows + iRow + chars.length).coerceAtMost(editTextFields.size - 1)
        postRefreshFocusColumn(focusTarget)
        Toast.makeText(this, "已貼上", Toast.LENGTH_SHORT).show()
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
                if (cells[row].isNotEmpty()) {
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

    private fun expandGrid() {
        // Grid is always MAX_COLUMNS wide; use ensureColumnBuilt for on-demand cell creation.
        ensureBufferColumn()
    }

    private fun ensureBufferColumn() {
        // Ensure there is at least one empty column beyond the last data column.
        val neededCol = columnData.size.coerceIn(0, MAX_COLUMNS - 1)
        ensureColumnBuilt(neededCol)
    }

    // Builds one column's EditText cells, fills data, appends to editTextFields/grid.
    // Must be called in col order 0,1,2,… because editTextFields is a contiguous list.
    private fun buildColumn(col: Int, grid: GridLayout, fontPx: Float, cellSize: Int) {
        if (col in builtColumns || col >= MAX_COLUMNS || numRows <= 0) return
        val colData = columnData.getOrNull(col)
        isRestoring = true
        try {
            repeat(numRows) { row ->
                val index = col * numRows + row
                val et = makeCell(row, col, index, cellSize, fontPx)
                val ch = colData?.getOrNull(row) ?: ""
                if (ch.isNotEmpty()) et.setText(ch)
                et.setTextColor(gridTextColor)
                editTextFields.add(et)
                grid.addView(et)
            }
        } finally {
            isRestoring = false
        }
        builtColumns.add(col)
    }

    // Builds exactly the visible columns + all data columns. No async follow-on.
    // New columns are created on demand: typing triggers ensureBufferColumn,
    // scrolling triggers the onScrollChangeListener in rebuildGrid.
    private fun buildInitialColumns(anchorCol: Int) {
        val grid = (hScroll?.getChildAt(0) as? GridLayout) ?: return
        val fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        val cellSize = currentCellSize
        val visibleCols = (mainScrollView.width / cellSize.coerceAtLeast(1)).coerceAtLeast(1) + 1
        val lastDataCol = columnData.indexOfLast { it.any { ch -> ch.isNotEmpty() } }.coerceAtLeast(0)
        val buildEnd = maxOf(visibleCols - 1, lastDataCol).coerceAtMost(MAX_COLUMNS - 1)
        for (col in 0..buildEnd) buildColumn(col, grid, fontPx, cellSize)
    }

    // Synchronously builds all columns from the next unbuilt one up to col.
    // Called before any access to a column not yet in editTextFields.
    private fun ensureColumnBuilt(col: Int) {
        if (col < 0 || col >= MAX_COLUMNS || col in builtColumns) return
        val grid = (hScroll?.getChildAt(0) as? GridLayout) ?: return
        val fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        val nextUnbuilt = (builtColumns.maxOrNull()?.plus(1)) ?: 0
        for (c in nextUnbuilt..col) buildColumn(c, grid, fontPx, currentCellSize)
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

        // Cancel any ongoing async build before tearing down views.
        incrementalBuildRunnable?.let { rootFrame.removeCallbacks(it) }
        incrementalBuildRunnable = null
        buildGeneration++
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
                ensureColumnBuilt((highestVisible + 1).coerceAtMost(MAX_COLUMNS - 1))
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
        buildInitialColumns(anchorCol)

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
        et.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val now = System.currentTimeMillis()

                // Double-tap → enter selection mode
                if (!isSelecting && lastTapIndex == index && now - lastTapTime < 300L) {
                    lastTapTime = 0L; lastTapIndex = -1
                    enterSelectionMode(index)
                    return@setOnTouchListener true
                }

                // Tap outside handles while selecting → dismiss selection, fall through to normal tap
                if (isSelecting) {
                    handlePasteView?.visibility = View.GONE
                    clearSelection()
                    // fall through: normal focus/keyboard logic runs below
                }

                lastTapTime = now; lastTapIndex = index

                if (toolsVisible) {
                    allToolsPanel.visibility = View.GONE
                    toolsVisible = false
                    toolsCell?.setBackgroundColor(Color.TRANSPARENT)
                    if (lastKeyboardHeight > 0) mainScrollView.setPadding(0, 0, 0, lastKeyboardHeight)
                }
                if (inputMode == InputMode.SEQUENTIAL && et.text.isEmpty()) {
                    var searchCol = index / numRows
                    var target = -1
                    while (searchCol < numColumns) {
                        val emptyRow = (0 until numRows).firstOrNull { r ->
                            editTextFields.getOrNull(searchCol * numRows + r)?.text?.isEmpty() == true
                        }
                        if (emptyRow != null) { target = searchCol * numRows + emptyRow; break }
                        searchCol++
                    }
                    if (target >= 0 && target != index) {
                        focusCell(target)
                        scrollToColumn(target / numRows)
                        return@setOnTouchListener true
                    }
                }
                et.requestFocus()
                et.setSelection(et.text.length)
                focusCell(index, showKeyboard = true)
            }
            true
        }

        // Key handler for Enter and DEL (hardware keyboard + many soft keyboards).
        // Enter = insert a blank row at the current position, pushing content down.
        // DEL   = remove the current row's element, shift content up, move to index-1.
        et.setOnKeyListener { _, keyCode, event ->
            if (isRestoring) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DEL && keyCode != KeyEvent.KEYCODE_ENTER) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_ENTER -> {
                        insertColumnBreak(index / numRows, index % numRows)
                    }
                    KeyEvent.KEYCODE_DEL -> {
                        val cellCol = index / numRows
                        val cellRow = index % numRows
                        // Row 0 of a break column → undo the column break (SEQUENTIAL only).
                        if (cellRow == 0 && cellCol > 0 && inputMode != InputMode.SCATTER
                                && columnBreaks.contains(cellCol)) {
                            removeColumnBreak(cellCol)
                            return@setOnKeyListener true
                        }
                        if (inputMode == InputMode.SCATTER) {
                            val curChar = columnData.getOrNull(cellCol)?.getOrNull(cellRow) ?: ""
                            if (curChar.isNotBlank()) {
                                // Island: backspace-delete with reflow, same as SEQUENTIAL.
                                deletePreviousSequentialCell(cellCol, cellRow, index)
                            } else {
                                val colList = columnData.getOrNull(cellCol)
                                val focusTarget = (index - 1).coerceAtLeast(0)
                                if (colList != null && cellRow < colList.size) {
                                    withRestoring { colList.removeAt(cellRow); colList.add("") }
                                }
                                editTextFields.getOrNull(focusTarget)?.requestFocus()
                                gridContainer.post { refreshGrid(); focusCell(focusTarget) }
                            }
                        } else {
                            deletePreviousSequentialCell(cellCol, cellRow, index)
                        }
                    }
                }
            }
            true
        }

        // Soft IME Enter: same column-break behaviour as hardware KEYCODE_ENTER.
        // Returning true keeps the keyboard open.
        et.setOnEditorActionListener { _, _, _ ->
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
                        val newChars: String? = when {
                            text.startsWith(originalChar) -> text.substring(1)
                            text.endsWith(originalChar)   -> text.dropLast(1)
                            else                          -> null  // IME replaced entirely — paste path
                        }
                        if (newChars != null && newChars.isNotEmpty()) {
                            val before = focusedCellIndex == index && cursorBefore
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
    }

    // ── Background image ───────────────────────────────────────────────
    private fun applyBackgroundImage(uri: Uri) {
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: Exception) {}
        val storedUri = storeBackgroundImage(uri) ?: uri.toString()
        loadBgImageFromUri(storedUri)
        persistCurrentState()
    }

    private fun storeBackgroundImage(uri: Uri): String? {
        return try {
            val dir = java.io.File(filesDir, "backgrounds").also { it.mkdirs() }
            val file = java.io.File(dir, "$currentSessionId.bg")
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            Uri.fromFile(file).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun loadBgImageFromUri(uriStr: String?) {
        if (uriStr == null) { bgImageView?.visibility = View.GONE; bgImageUri = null; return }
        val uri = try { Uri.parse(uriStr) } catch (_: Exception) { return }
        if (bgImageView == null) {
            bgImageView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(MP, MP)
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            rootFrame.addView(bgImageView, 0)  // behind all other views
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
        } catch (_: Exception) {
            bgImageView?.visibility = View.GONE
        }
    }

    // ── Document management ────────────────────────────────────────────
    // Rebuilds the doc panel list showing the 3 most recent sessions.
    // No ScrollView — content is sized to fit the fixed 120dp overlay.
    private fun refreshDocPanel() {
        val container = docListContainer ?: return
        val dp = resources.displayMetrics.density
        container.removeAllViews()
        val sessions = listSessions().take(3)
        if (sessions.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "尚無文檔"
                textSize = 12f; setTextColor(getColor(R.color.text_hint))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MP, WC)
                setPadding(0, (10 * dp).roundToInt(), 0, 0)
            })
            return
        }
        sessions.forEach { meta ->
            val isActive = meta.id == currentSessionId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MP, (26 * dp).roundToInt())
                setPadding((14 * dp).roundToInt(), 0, (8 * dp).roundToInt(), 0)
                if (isActive) setBackgroundColor(getColor(R.color.row_active))
            }
            row.addView(TextView(this).apply {
                text = meta.name; textSize = 12f
                setTextColor(if (isActive) getColor(R.color.text_darkest) else getColor(R.color.text_dark))
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                setOnClickListener { loadSessionFile(meta.id); refreshDocPanel() }
            })
            row.addView(TextView(this).apply {
                text = meta.formattedDate(); textSize = 10f
                setTextColor(getColor(R.color.text_hint))
                layoutParams = LinearLayout.LayoutParams(WC, WC).also { it.marginEnd = (6 * dp).roundToInt() }
            })
            row.addView(TextView(this).apply {
                text = "×"; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(getColor(R.color.stroke))
                layoutParams = LinearLayout.LayoutParams((28 * dp).roundToInt(), (28 * dp).roundToInt())
                setOnClickListener { deleteSession(meta.id); refreshDocPanel() }
            })
            container.addView(row)
        }
    }

    private fun showRenameDialog() {
        val dp = resources.displayMetrics.density
        val editText = EditText(this).apply {
            setText(currentSessionName); setSingleLine(); selectAll()
            setPadding((16 * dp).roundToInt(), (12 * dp).roundToInt(),
                       (16 * dp).roundToInt(), (12 * dp).roundToInt())
        }
        AlertDialog.Builder(this)
            .setTitle("重新命名")
            .setView(editText)
            .setPositiveButton("確定") { _, _ ->
                val name = editText.text.toString().trim().ifEmpty { currentSessionName }
                saveSession(currentSessionId, name)
                refreshDocPanel()
            }
            .setNegativeButton("取消", null)
            .show().also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.BLACK)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.BLACK)
            }
    }

    private fun listSessions(): List<SessionMeta> = SessionManager.listSessions(filesDir)

    // Applies all per-session settings to data model + live UI controls.
    // Does NOT call rebuildGrid — caller is responsible.
    private fun applySettings(
        fontIdx: Int = fontIndex,
        sizeSp: Float = fontSizeSp,
        gapDp: Float = wordGapDp,
        textColor: Int = gridTextColor,
        bgCol: Int = bgColor,
        imgUri: String? = bgImageUri,
        mode: InputMode = inputMode
    ) {
        fontIndex = fontIdx.coerceIn(0, fontCatalogue.size - 1)
        selectedTypeface = fontCatalogue[fontIndex].typeface
        fontSizeSp = sizeSp
        wordGapDp = gapDp
        gridTextColor = textColor
        bgColor = bgCol
        inputMode = mode

        fontSpinnerRef?.setSelection(fontIndex)
        fontSizeLabelRef?.text = fontSizeSp.toInt().toString()
        gapValueLabelRef?.text = wordGapDp.toInt().toString()

        applyTextColor(gridTextColor)
        refreshModeChips()

        if (!imgUri.isNullOrEmpty()) {
            loadBgImageFromUri(imgUri)
        } else {
            bgImageUri = null
            bgImageView?.visibility = View.GONE
            rootFrame.setBackgroundColor(bgColor)
        }
    }

    private fun saveSession(id: String, name: String) {
        SessionManager.saveSession(filesDir, id, name, columnData, columnBreaks,
            fontIndex, fontSizeSp, wordGapDp, gridTextColor, bgColor, bgImageUri, inputMode.name)
        currentSessionId = id; currentSessionName = name
    }

    private fun loadSessionFile(id: String) {
        if (id == currentSessionId) return
        saveSession(currentSessionId, currentSessionName)
        val j = SessionManager.loadSession(filesDir, id) ?: return
        loadColumnDataFromJson(j.getJSONArray("columnData"))
        loadColumnBreaksFromJson(j.getJSONArray("columnBreaks"))
        currentSessionId = id; currentSessionName = j.getString("name")
        applySettings(
            fontIdx   = j.optInt("fontIndex", fontIndex),
            sizeSp    = j.optDouble("fontSizeSp", fontSizeSp.toDouble()).toFloat(),
            gapDp     = j.optDouble("wordGapDp", wordGapDp.toDouble()).toFloat(),
            textColor = j.optInt("gridTextColor", gridTextColor),
            bgCol     = j.optInt("bgColor", bgColor),
            imgUri    = j.optString("bgImageUri", "").ifEmpty { null },
            mode      = when (j.optString("inputMode", inputMode.name)) {
                "SCATTER" -> InputMode.SCATTER; else -> InputMode.SEQUENTIAL }
        )
        needsReflow = true; rebuildGrid()
    }

    private fun deleteSession(id: String) {
        SessionManager.deleteSession(filesDir, id)
        if (currentSessionId == id) { currentSessionId = java.util.UUID.randomUUID().toString() }
    }

    private fun newSession() {
        saveSession(currentSessionId, currentSessionName)  // auto-save current before creating new
        clearDocumentContent()
        currentSessionId = java.util.UUID.randomUUID().toString()
        currentSessionName = nextNewSessionName()
        applySettings(
            fontIdx = 0, sizeSp = 24f, gapDp = 3f,
            textColor = Color.BLACK, bgCol = Color.WHITE, imgUri = null,
            mode = InputMode.SEQUENTIAL
        )
        needsReflow = true; rebuildGrid()
    }

    private fun nextNewSessionName(): String = SessionManager.nextNewSessionName(filesDir)

    private fun ensureDefaultSession() {
        SessionManager.ensureDefaultSession(filesDir, currentSessionId, currentSessionName,
            columnData, columnBreaks, fontIndex, fontSizeSp, wordGapDp,
            gridTextColor, bgColor, bgImageUri, inputMode.name)
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
        var firstIdx = -1; var lastIdx = -1
        for (idx in 0 until numRows * numColumns) {
            val c = idx / numRows; val r = idx % numRows
            if ((columnData.getOrNull(c)?.getOrNull(r) ?: "").isNotEmpty()) {
                if (firstIdx < 0) firstIdx = idx; lastIdx = idx
            }
        }
        if (firstIdx < 0) return
        withRestoring {
            for (idx in firstIdx..lastIdx) {
                val c = idx / numRows; val r = idx % numRows
                if ((columnData.getOrNull(c)?.getOrNull(r) ?: "").isEmpty()) setColumnChar(c, r, " ")
            }
        }
        refreshGrid()
    }

    // ── ViewFactory.Callbacks ──────────────────────────────────────────
    override fun provideFontCatalogue(): List<FontEntry> = fontCatalogue
    override fun getFontIndex(): Int = fontIndex
    override fun getFontSizeSp(): Float = fontSizeSp
    override fun getWordGapDp(): Float = wordGapDp
    override fun getSelectedTypeface(): Typeface = selectedTypeface
    override fun getInputMode(): InputMode = inputMode

    override fun onModeSelected(mode: InputMode) {
        val previous = inputMode
        inputMode = mode
        refreshModeChips()
        if (mode == InputMode.SEQUENTIAL && previous == InputMode.SCATTER) fillGapsForSequentialMode()
    }

    override fun onToolsToggle() { if (toolsVisible) switchToKeyboard() else switchToTools() }
    override fun onCollapsePanel() { switchToKeyboard() }

    override fun onPunctInsert(punct: String) { insertPunct(punct) }

    override fun onFontSelected(pos: Int) {
        if (pos == fontIndex && editTextFields.isNotEmpty()) return
        fontIndex = pos
        selectedTypeface = fontCatalogue[pos].typeface
        val fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        val gapPx = (wordGapDp * resources.displayMetrics.density).roundToInt()
        val newCellSize = (Paint().apply { textSize = fontPx; typeface = selectedTypeface }
            .measureText("測").roundToInt().coerceAtLeast(1) + gapPx).coerceAtLeast(1)
        if (newCellSize == currentCellSize && editTextFields.isNotEmpty()) {
            withRestoring { editTextFields.forEach { it.typeface = selectedTypeface } }
        } else {
            needsReflow = true; rebuildGrid()
        }
    }

    override fun onFontSizeChanged(newSize: Float) {
        fontSizeSp = newSize
        needsReflow = true; rebuildGrid()
    }

    override fun onWordGapChanged(newGap: Int) {
        wordGapDp = newGap.toFloat()
        needsReflow = true; rebuildGrid()
    }

    override fun onBgColorSelected(color: Int) { applyBackground(color) }
    override fun onImagePickerRequested() { imagePickerLauncher.launch(arrayOf("image/*")) }
    override fun onTextColorSelected(color: Int) { applyTextColor(color) }

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

    override fun onRenameSession() { showRenameDialog() }
    override fun onNewSession() { newSession(); refreshDocPanel() }
    override fun onShowAllSessions() {
        sessionListLauncher.launch(Intent(this, SessionListActivity::class.java))
    }
}
