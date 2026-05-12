package com.poemeditor

import android.app.AlertDialog
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

class MainActivity : AppCompatActivity() {

    // ── Color configuration ────────────────────────────────────────────
    // Both palettes are iterated at runtime to build panel UI dynamically.
    private data class ColorOption(val color: Int, val label: String)

    private val BG_COLORS = listOf(
        ColorOption(Color.WHITE,                 "白"),
        ColorOption(Color.parseColor("#FFF8E7"), "米"),
        ColorOption(Color.parseColor("#F5E6CA"), "古"),
        ColorOption(Color.parseColor("#E8F4F8"), "藍"),
        ColorOption(Color.parseColor("#1A1A2E"), "夜"),
        ColorOption(Color.parseColor("#2B2B2B"), "深"),
        ColorOption(Color.BLACK,                 "黑"),
    )

    private val TEXT_COLORS = listOf(
        ColorOption(Color.parseColor("#212121"), "墨"),
        ColorOption(Color.parseColor("#333333"), "炭"),
        ColorOption(Color.parseColor("#757575"), "灰"),
        ColorOption(Color.WHITE,                 "白"),
        ColorOption(Color.parseColor("#1B263B"), "藍墨"),
        ColorOption(Color.parseColor("#B22222"), "硃"),
        ColorOption(Color.parseColor("#D4AF37"), "金"),
        ColorOption(Color.parseColor("#00A86B"), "翠"),
        ColorOption(Color.parseColor("#4A192C"), "紫"),
        ColorOption(Color.parseColor("#D32F2F"), "赤"),
        ColorOption(Color.parseColor("#1976D2"), "藍"),
    )

    private val fontSizeList = listOf(
        14f to "14", 16f to "16", 18f to "18", 20f to "20", 24f to "24",
        28f to "28", 32f to "32", 36f to "36", 40f to "40", 48f to "48"
    )

    // ── Settings ───────────────────────────────────────────────────────
    private var fontSizeSp       = 24f
    private var bgColor          = Color.WHITE
    // gridTextColor is independently controlled via the text-colour panel;
    // it is never auto-derived from bgColor.
    private var gridTextColor    = Color.BLACK
    private var selectedTypeface = Typeface.DEFAULT
    private var wordGapDp        = 0f
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
    private lateinit var allToolsPanel:         NestedScrollView
    private lateinit var toolbar:               FrameLayout
    private var toolsCell:                      LinearLayout? = null
    private var punctCellRef:                   LinearLayout? = null
    private lateinit var bottomPanel:           LinearLayout
    private lateinit var mainLayout:            LinearLayout
    private lateinit var mainScrollView:        NestedScrollView
    private lateinit var punctToolbar:          HorizontalScrollView

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

    private fun refreshModeChips() {
        val container = modeChipContainer ?: return
        for (i in 0 until container.childCount) {
            val chip = container.getChildAt(i) as? TextView ?: continue
            (chip.background as? GradientDrawable)?.setColor(
                if (chip.tag == inputMode) getColor(R.color.chip_active) else Color.TRANSPARENT)
        }
    }


    // Highlights the focused cell and records the logical insertion side (before/after).
    // cursorBefore: true  = next keystroke inserts BEFORE this cell's character.
    //               false = next keystroke inserts AFTER  (default).
    // showKeyboard: true  = open IME and re-bind it; false = keep IME closed (style rebuilds).
    // No Paint or Canvas work — purely a data + focus update.
    private fun focusCell(index: Int, cursorBefore: Boolean = true, showKeyboard: Boolean = true) {
        val et = editTextFields.getOrNull(index) ?: return
        this.cursorBefore = cursorBefore
        editTextFields.getOrNull(focusedCellIndex)?.setBackgroundColor(Color.TRANSPARENT)
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
    data class FontEntry(val label: String, val typeface: Typeface)

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

        bottomPanel = buildBottomPanel(dp)
        mainLayout.addView(mainScrollView)
        mainLayout.addView(bottomPanel)
        rootFrame.addView(mainLayout)
        rootFrame.addView(allToolsPanel)
        setContentView(rootFrame)
        loadBgImageFromUri(bgImageUri)

        // Track keyboard height and manage scroll padding in sync with IME state.
        ViewCompat.setOnApplyWindowInsetsListener(rootFrame) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (imeVisible && imeHeight > 0) lastKeyboardHeight = imeHeight
            punctToolbar.visibility = if (imeVisible) View.VISIBLE else View.GONE
            if (imeVisible) {
                // Keyboard opened — if tools panel was showing, dismiss it now.
                if (toolsVisible) {
                    allToolsPanel.visibility = View.GONE
                    toolsVisible = false
                    toolsCell?.setBackgroundColor(Color.TRANSPARENT)
                }
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

    // ── Bottom panel ───────────────────────────────────────────────────
    private fun buildBottomPanel(dp: Float): LinearLayout {
        allToolsPanel = buildAllToolsPanel(dp)
        toolbar = buildToolbar(dp)
        punctToolbar = buildPunctToolbar(dp)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            addView(View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MP, (1f * dp).roundToInt())
                setBackgroundColor(getColor(R.color.divider_dark))
            })
            addView(punctToolbar)
            addView(toolbar)
        }
    }

    // ── Toolbar (always visible, 54 dp) ───────────────────────────────
    // mainBar:  工具 | 標點   — default visible
    // punctBar: ← | scrollable punct row — shown when 標點 is tapped
    private fun buildToolbar(dp: Float): FrameLayout {
        val toolbarH = (54 * dp).roundToInt()

        var mainBarRef: LinearLayout? = null
        var punctBarRef: LinearLayout? = null

        val mainBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(MP, toolbarH)
            setBackgroundColor(Color.WHITE)

            toolsCell = buildCategoryCell("工具", toolbarH, dp) {
                if (toolsVisible) switchToKeyboard() else switchToTools()
            }
            addView(toolsCell)
            addView(divider(toolbarH, dp))

            val pCell = buildCategoryCell("標點", toolbarH, dp) {
                mainBarRef?.visibility = View.GONE
                punctBarRef?.visibility = View.VISIBLE
            }
            punctCellRef = pCell
            addView(pCell)
        }
        mainBarRef = mainBar

        val punctBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(MP, toolbarH)
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE

            addView(TextView(this@MainActivity).apply {
                text = "←"; textSize = 20f; gravity = Gravity.CENTER
                setTextColor(getColor(R.color.text_dark))
                layoutParams = LinearLayout.LayoutParams((54 * dp).roundToInt(), toolbarH)
                setOnClickListener {
                    punctBarRef?.visibility = View.GONE
                    mainBarRef?.visibility = View.VISIBLE
                }
            })
            addView(divider(toolbarH, dp))
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(0, toolbarH, 1f)
                addView(buildPunctRow(dp))
            })
        }
        punctBarRef = punctBar

        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MP, toolbarH)
            setBackgroundColor(Color.WHITE)
            addView(mainBar)
            addView(punctBar)
        }
    }

    private fun buildCategoryCell(label: String, toolbarH: Int, dp: Float, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, toolbarH, 1f)
            addView(iconText(label, 14f))
            setOnClickListener { onClick() }
        }

    private fun iconText(ch: String, size: Float) = TextView(this).apply {
        text = ch
        textSize = size
        setTextColor(getColor(R.color.text_dark))
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(WC, WC)
    }

    private fun divider(toolbarH: Int, dp: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            (0.8f * dp).roundToInt(), toolbarH
        ).also {
            it.topMargin    = (12 * dp).roundToInt()
            it.bottomMargin = (12 * dp).roundToInt()
        }
        setBackgroundColor(getColor(R.color.divider))
    }

    private fun swatchDrawable(color: Int, dp: Float) = GradientDrawable().apply {
        setColor(color)
        setStroke((1.5f * dp).roundToInt(), getColor(R.color.text_hint))
        cornerRadius = 5f * dp
    }

    // ── Font spinner ───────────────────────────────────────────────────
    private fun buildFontSpinner(dp: Float): Spinner {
        val adapter = object : ArrayAdapter<FontEntry>(
            this, android.R.layout.simple_spinner_item, fontCatalogue
        ) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View =
                spinnerItemView(pos, compact = true, dp = dp)
            override fun getDropDownView(pos: Int, cv: View?, parent: ViewGroup): View =
                spinnerItemView(pos, compact = false, dp = dp)
        }

        return Spinner(this).apply {
            this.adapter = adapter
            setSelection(fontIndex)
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            background = null
            fontSpinnerRef = this

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (pos == fontIndex && editTextFields.isNotEmpty()) return
                    fontIndex = pos
                    selectedTypeface = fontCatalogue[pos].typeface
                    // Only rebuild if the new font changes the measured cell size.
                    // Same-size font changes (e.g. style-only) update typefaces in-place
                    // so the IME connection is never broken and the keyboard never flickers.
                    val fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
                    val gapPx  = (wordGapDp * resources.displayMetrics.density).roundToInt()
                    val newCellSize = (Paint().apply { textSize = fontPx; typeface = selectedTypeface }
                        .measureText("測").roundToInt().coerceAtLeast(1) + gapPx).coerceAtLeast(1)
                    if (newCellSize == currentCellSize && editTextFields.isNotEmpty()) {
                        withRestoring { editTextFields.forEach { it.typeface = selectedTypeface } }
                    } else {
                        needsReflow = true; rebuildGrid()
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
    }

    private fun spinnerItemView(pos: Int, compact: Boolean, dp: Float): TextView {
        val entry = fontCatalogue[pos]
        return TextView(this).apply {
            text = entry.label
            typeface = entry.typeface
            textSize = if (compact) 14f else 16f
            setTextColor(getColor(R.color.text_darker))
            gravity = if (compact) Gravity.CENTER else Gravity.CENTER_VERTICAL
            val hPad = ((if (compact) 4 else 16) * dp).roundToInt()
            val vPad = ((if (compact) 2 else 12) * dp).roundToInt()
            setPadding(hPad, vPad, hPad, vPad)
            if (!compact) setBackgroundColor(Color.WHITE)
        }
    }

    // ── Punctuation toolbar ────────────────────────────────────────────
    private val PUNCT_LIST = listOf(
        // 括號與引號
        "﹁", "﹂", "﹃", "﹄", "︻", "︼","︵", "︶", 
        "︽", "︾", "︿", "﹀", "︗", "︘", 
        // 基礎標點
        "。", "，", "、","？", "！", "：", "；", 
        // 延伸
        "︱", "︙", "·", "※"
    )

    private fun buildPunctToolbar(dp: Float): HorizontalScrollView {
        return HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(getColor(R.color.panel_bg))
            visibility = View.GONE
            addView(buildPunctRow(dp))
        }
    }

    private fun buildPunctRow(dp: Float): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val vPad = (6 * dp).roundToInt()
            setPadding((8 * dp).roundToInt(), vPad, (8 * dp).roundToInt(), vPad)
            PUNCT_LIST.forEach { punct -> addView(buildPunctButton(punct, dp)) }
        }

    private fun buildPunctButton(punct: String, dp: Float): TextView =
        TextView(this).apply {
            text = punct
            textSize = 20f
            typeface = selectedTypeface
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_dark))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke((1f * dp).roundToInt(), getColor(R.color.stroke))
                cornerRadius = 8f * dp
            }
            val hPad = (10 * dp).roundToInt()
            val vPad = (6 * dp).roundToInt()
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                it.marginEnd = (6 * dp).roundToInt()
            }
            setOnClickListener { insertPunct(punct) }
        }

    private fun insertPunct(punct: String) {
        val focused = currentFocus
        val idx = editTextFields.indexOf(focused as? EditText)
        if (idx < 0) return
        val cellCol = idx / numRows
        val cellRow = idx % numRows
        val originalChar = columnData.getOrNull(cellCol)?.getOrNull(cellRow) ?: ""
        if (originalChar.isEmpty() || inputMode == InputMode.SCATTER) {
            setColumnChar(cellCol, cellRow, punct)
            withRestoring { editTextFields[idx].setText(punct) }
            advanceToNextCell(idx, editTextFields.size)
        } else {
            val focusTarget = performInsert(idx, punct) ?: return
            postRefreshFocusColumn(focusTarget)
        }
    }

    // Shared swatch-item factory used by both colour panels.
    private fun buildSwatchItem(option: ColorOption, swatchSz: Int, dp: Float,
                                onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                it.marginEnd = (6 * dp).roundToInt()
            }
            addView(View(this@MainActivity).apply {
                background = swatchDrawable(option.color, dp)
                layoutParams = LinearLayout.LayoutParams(swatchSz, swatchSz)
            })
            addView(TextView(this@MainActivity).apply {
                text = option.label; textSize = 9f
                setTextColor(Color.DKGRAY); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(swatchSz, WC)
            })
            setOnClickListener { onClick() }
        }

    private fun buildColorSwatchRow(
        dp: Float,
        colors: List<ColorOption>,
        extra: Pair<ColorOption, () -> Unit>? = null,
        onClick: (ColorOption) -> Unit
    ): LinearLayout {
        val swatchSz = (38 * dp).roundToInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            setPadding((8 * dp).roundToInt(), (10 * dp).roundToInt(),
                       (8 * dp).roundToInt(), (10 * dp).roundToInt())
            colors.forEach { option -> addView(buildSwatchItem(option, swatchSz, dp) { onClick(option) }) }
            extra?.let { (opt, action) -> addView(buildSwatchItem(opt, swatchSz, dp, action)) }
        }
    }

    private fun buildBgColorPanel(dp: Float) = buildColorSwatchRow(
        dp, BG_COLORS,
        extra = ColorOption(getColor(R.color.divider), "自訂") to { imagePickerLauncher.launch(arrayOf("image/*")) }
    ) { applyBackground(it.color) }

    private fun buildTextColorPanel(dp: Float) = buildColorSwatchRow(dp, TEXT_COLORS) { applyTextColor(it.color) }

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

    private fun buildModePanel(dp: Float): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            setPadding((8 * dp).roundToInt(), (10 * dp).roundToInt(),
                       (8 * dp).roundToInt(), (10 * dp).roundToInt())
            modeChipContainer = this
            listOf(InputMode.SCATTER to "灑花輸入", InputMode.SEQUENTIAL to "連續輸入")
                .forEach { (mode, label) ->
                    val container = this
                    addView(TextView(this@MainActivity).apply {
                        text = label
                        textSize = 13f
                        gravity = Gravity.CENTER
                        tag = mode
                        setTextColor(getColor(R.color.text_dark))
                        background = GradientDrawable().apply {
                            setColor(if (inputMode == mode) getColor(R.color.chip_active) else Color.TRANSPARENT)
                            setStroke((1f * dp).roundToInt(), getColor(R.color.stroke))
                            cornerRadius = 20f * dp
                        }
                        val hPad = (14 * dp).roundToInt(); val vPad = (6 * dp).roundToInt()
                        setPadding(hPad, vPad, hPad, vPad)
                        layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                            it.marginEnd = (6 * dp).roundToInt()
                        }
                        setOnClickListener {
                            val previous = inputMode
                            inputMode = mode
                            refreshModeChips()
                            // Fill gaps only when leaving SCATTER for SEQUENTIAL so the
                            // auto-advance cursor never jumps over an unintended empty cell.
                            if (mode == InputMode.SEQUENTIAL && previous == InputMode.SCATTER) {
                                fillGapsForSequentialMode()
                            }
                        }
                    })
                }
        }
    }

    // ── Mode switch helper ─────────────────────────────────────────────
    // When switching from SCATTER → SEQUENTIAL, every empty cell between the
    // first and last occupied cell (in column-major index order) is filled with
    // a half-width space so SEQUENTIAL's auto-advance never skips over a gap.
    // isRestoring guards the TextWatcher so setting spaces doesn't trigger the
    // auto-jump logic or modify columnData through the normal input path.
    private fun fillGapsForSequentialMode() {
        if (numRows <= 0) return
        val totalCells = numRows * numColumns

        // Find the first and last occupied cell by column-major index.
        var firstIdx = -1
        var lastIdx  = -1
        for (idx in 0 until totalCells) {
            val c = idx / numRows; val r = idx % numRows
            if ((columnData.getOrNull(c)?.getOrNull(r) ?: "").isNotEmpty()) {
                if (firstIdx < 0) firstIdx = idx
                lastIdx = idx
            }
        }
        if (firstIdx < 0) return   // grid is empty — nothing to fill

        // Fill every empty slot in [firstIdx, lastIdx] with a space.
        withRestoring {          // suppress TextWatcher auto-advance
            for (idx in firstIdx..lastIdx) {
                val c = idx / numRows; val r = idx % numRows
                if ((columnData.getOrNull(c)?.getOrNull(r) ?: "").isEmpty()) {
                    setColumnChar(c, r, " ")
                }
            }
        }

        // Diff-update visible cells in place — no view destruction, no keyboard flicker.
        refreshGrid()
    }

    // ── Tools panel toggle ────────────────────────────────────────────
    private fun switchToTools() {
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

    // ── All-tools panel ────────────────────────────────────────────────
    // Single scrollable overlay that replaces the keyboard at the same height.
    // Sections: 字型 (font+size+text color) | 排版 (gap+bg color) | 寫作 (mode+punct) | 檔案 (doc)
    private fun buildAllToolsPanel(dp: Float): NestedScrollView {
        val hscroll = { inner: LinearLayout ->
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(MP, WC)
                addView(inner.also { it.layoutParams = LinearLayout.LayoutParams(WC, WC) })
            }
        }
        fun sectionLabel(text: String) = TextView(this).apply {
            this.text = text; textSize = 10f
            setTextColor(getColor(R.color.text_lighter))
            val hP = (12 * dp).roundToInt(); val vP = (6 * dp).roundToInt()
            setPadding(hP, vP, hP, vP)
            layoutParams = LinearLayout.LayoutParams(MP, WC)
        }

        val docListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)
        }
        docListContainer = docListLayout

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)

            addView(sectionLabel("字型"))
            addView(buildFontRow(dp))
            addView(subDivider(dp))
            addView(hscroll(buildTextColorPanel(dp)))
            addView(subDivider(dp))

            addView(sectionLabel("排版"))
            addView(hscroll(buildBgColorPanel(dp)))
            addView(subDivider(dp))

            addView(sectionLabel("寫作"))
            addView(buildModePanel(dp))
            addView(subDivider(dp))

            addView(sectionLabel("檔案"))
            addView(buildDocActionRow(dp))
            addView(subDivider(dp))
            addView(docListLayout)
        }

        return NestedScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MP, (280 * dp).roundToInt(), Gravity.BOTTOM)
            setBackgroundColor(getColor(R.color.panel_bg))
            visibility = View.GONE
            addView(content)
        }
    }

    private fun buildDocActionRow(dp: Float): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            val hPad = (12 * dp).roundToInt(); val vPad = (8 * dp).roundToInt()
            setPadding(hPad, vPad, hPad, vPad)
            listOf(
                "更名" to Runnable { showRenameDialog() },
                "新增" to Runnable { newSession(); refreshDocPanel() },
                "全部" to Runnable {
                    sessionListLauncher.launch(Intent(this@MainActivity, SessionListActivity::class.java))
                }
            ).forEach { (label, action) ->
                addView(TextView(this@MainActivity).apply {
                    text = label; textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.text_dark))
                    background = GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        setStroke((1f * dp).roundToInt(), getColor(R.color.stroke))
                        cornerRadius = 20f * dp
                    }
                    val hP = (14 * dp).roundToInt(); val vP = (5 * dp).roundToInt()
                    setPadding(hP, vP, hP, vP)
                    layoutParams = LinearLayout.LayoutParams(WC, WC).also { it.marginEnd = (8 * dp).roundToInt() }
                    setOnClickListener { action.run() }
                })
            }
        }

    private fun buildFontRow(dp: Float): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MP, WC)
        val hPad = (8 * dp).roundToInt(); val vPad = (8 * dp).roundToInt()
        setPadding(hPad, vPad, hPad, vPad)

        fun rowDivider() = View(this@MainActivity).apply {
            layoutParams = LinearLayout.LayoutParams(
                (1f * dp).roundToInt(), (28 * dp).roundToInt()
            ).also { it.marginStart = (8 * dp).roundToInt(); it.marginEnd = (8 * dp).roundToInt() }
            setBackgroundColor(getColor(R.color.divider))
        }
        fun label(t: String) = TextView(this@MainActivity).apply {
            text = t; textSize = 11f
            setTextColor(getColor(R.color.text_light))
            layoutParams = LinearLayout.LayoutParams(WC, WC).also { it.marginEnd = (4 * dp).roundToInt() }
        }
        fun chip(initial: String) = TextView(this@MainActivity).apply {
            text = initial; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_dark))
            background = GradientDrawable().apply {
                setColor(getColor(R.color.chip_active)); cornerRadius = 4f * dp
            }
            val hP = (8 * dp).roundToInt(); val vP = (2 * dp).roundToInt()
            setPadding(hP, vP, hP, vP)
            layoutParams = LinearLayout.LayoutParams(WC, WC)
        }

        // 字體
        addView(label("字體"))
        addView(buildFontSpinner(dp).also { it.layoutParams = LinearLayout.LayoutParams(0, WC, 1f) })

        addView(rowDivider())

        // 字號 — tappable chip → popup seekbar
        addView(label("字號"))
        val sizeChip = chip(fontSizeSp.toInt().toString())
        fontSizeLabelRef = sizeChip
        sizeChip.setOnClickListener {
            showPopupSeekbar(
                anchor = it,
                max    = fontSizeList.size - 1,
                initial = fontSizeList.indexOfFirst { e -> e.first == fontSizeSp }.coerceAtLeast(0),
                format  = { i -> fontSizeList[i].second }
            ) { p ->
                val newSize = fontSizeList[p].first
                if (newSize != fontSizeSp) {
                    fontSizeSp = newSize
                    fontSizeLabelRef?.text = newSize.toInt().toString()
                    needsReflow = true; rebuildGrid()
                }
            }
        }
        addView(sizeChip)

        addView(rowDivider())

        // 字距 — tappable chip → popup seekbar
        addView(label("字距"))
        val gapChip = chip(wordGapDp.toInt().toString())
        gapValueLabelRef = gapChip
        gapChip.setOnClickListener {
            showPopupSeekbar(
                anchor  = it,
                max     = 20,
                initial = wordGapDp.toInt(),
                format  = { i -> "$i" }
            ) { p ->
                wordGapDp = p.toFloat()
                gapValueLabelRef?.text = "$p"
                needsReflow = true; rebuildGrid()
            }
        }
        addView(gapChip)
    }

    // Shows a floating seekbar popup anchored above `anchor`.
    // `format` converts a progress value to a display string shown inside the popup.
    // `onChange` is called on every drag step while `fromUser` is true.
    private fun showPopupSeekbar(
        anchor: View, max: Int, initial: Int,
        format: (Int) -> String,
        onChange: (Int) -> Unit
    ) {
        val dp = resources.displayMetrics.density
        val popupWidth = (resources.displayMetrics.widthPixels * 0.82f).roundToInt()

        val valueLabel = TextView(this).apply {
            text = format(initial); textSize = 13f; gravity = Gravity.CENTER
            setTextColor(getColor(R.color.text_dark))
            layoutParams = LinearLayout.LayoutParams((40 * dp).roundToInt(), WC)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(getColor(R.color.panel_bg))
                setStroke((1f * dp).roundToInt(), getColor(R.color.stroke))
                cornerRadius = 10f * dp
            }
            val pad = (12 * dp).roundToInt()
            setPadding(pad, pad, pad, pad)
            addView(SeekBar(this@MainActivity).apply {
                this.max = max; progress = initial
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) { valueLabel.text = format(p); onChange(p) }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
            addView(valueLabel)
        }
        container.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        PopupWindow(container, popupWidth, container.measuredHeight, true).apply {
            elevation = 8f * dp
            isOutsideTouchable = true
            val xOff = ((anchor.width - popupWidth) / 2)
            val yOff = -(container.measuredHeight + anchor.height + (6 * dp).roundToInt())
            showAsDropDown(anchor, xOff, yOff)
        }
    }

    private fun subDivider(dp: Float): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MP, (1f * dp).roundToInt())
        setBackgroundColor(getColor(R.color.divider))
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
        withRestoring {
            for (col in 0 until numColumns) {
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
    // In SEQUENTIAL mode: characters below the cursor in the current column are extracted,
    // the column is trimmed to the cursor row, and the extracted tail is prepended to
    // the next column (displacing any existing content right) via insertCharsAt.
    // In SCATTER mode: split into nextCol too, but overwrite nextCol instead of
    // inserting a new column and shifting existing columns.
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
        // Always do a structural column insert: slide every columnData entry and every
        // break index at or beyond nextCol right by 1, then place tail (possibly empty)
        // as the new column at nextCol.
        // Using insertCharsAt here instead would prepend tail into nextCol's existing
        // content and merge the two columns — wrong when nextCol already has characters.
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
        val grid = (hScroll?.getChildAt(0) as? GridLayout) ?: return
        val cellSize = currentCellSize.takeIf { it > 0 } ?: return
        val newCol = numColumns
        numColumns++
        grid.columnCount = numColumns
        val fontPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        repeat(numRows) { row ->
            val index = newCol * numRows + row
            val et = makeCell(row, newCol, index, cellSize, fontPx)
            editTextFields.add(et); grid.addView(et)
        }
    }

    private fun ensureBufferColumn() {
        while (columnData.size + 1 > numColumns && numColumns < MAX_COLUMNS) expandGrid()
    }

    private fun rebuildGrid(isInitialBoot: Boolean = false) {
        val availW = gridContainer.measuredWidth
        // adjustNothing: mainScrollView.height is stable when keyboard/tools panel opens/closes.
        // Do NOT subtract paddingBottom here — that padding is keyboard avoidance headroom,
        // not a real height reduction, and would cause numRows to shrink while typing.
        val currentH = mainScrollView.height - gridContainer.paddingTop
        if (availW <= 0 || currentH <= 0) return
        val imeVisible = ViewCompat.getRootWindowInsets(rootFrame)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        // stableMaxHeight is locked once at startup (before any IME activity) and
        // used as the authoritative height so numRows never changes with keyboard state.
        val availH = if (stableMaxHeight > 0) stableMaxHeight else currentH

        // Compute anchor: which column sits at the right edge of the viewport right now.
        val oldCols = numColumns
        val anchorCol: Int = run {
            val s = hScroll ?: return@run 0
            val cs = currentCellSize.takeIf { it > 0 } ?: return@run 0
            val viewport = s.width.takeIf { it > 0 } ?: return@run 0
            val gw = maxOf(oldCols * cs, s.width)
            (gw - (s.scrollX + viewport)) / cs
        }.coerceIn(0, maxOf(oldCols - 1, 0))

        previewCells.clear(); isPreviewing = false
        focusedCellIndex = -1

        val fontPx   = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        val gapPx    = (wordGapDp * resources.displayMetrics.density).roundToInt()
        val charSize = Paint().apply { textSize = fontPx; typeface = selectedTypeface }
            .measureText("測").roundToInt().coerceAtLeast(1)
        val cellSize = (charSize + gapPx).coerceAtLeast(1)

        // Floor division guarantees numRows * cellSize <= availH (no vertical overflow).
        numRows = availH / cellSize
        numColumns = (maxOf(columnData.size, columnBreaks.maxOrNull()?.plus(1) ?: 0) + 1)
            .coerceIn(1, MAX_COLUMNS)
        if (numRows <= 0) return

        // Reflow columnData BEFORE tearing down the grid so the restore loop below
        // always reads from already-redistributed data.
        // SCATTER mode is skipped: absolute (col, row) tap positions must not move.
        if (needsReflow) {
            if (inputMode != InputMode.SCATTER) reflowColumnData(numRows)
            needsReflow = false
        }

        // Explicit grid height — must never exceed availH.
        val gridHeight = numRows * cellSize
        check(gridHeight <= availH) { "gridHeight=$gridHeight > availH=$availH" }

        currentCellSize = cellSize

        // Prevent ghost cursors: clear focus on every EditText before destroying views.
        // Only hide the keyboard when it is already closed — if it was open, we will
        // re-open it immediately via focusCell(showKeyboard=true) and hiding+re-showing
        // would cause a visible bounce animation.  clearFocus() alone detaches the IME
        // from the old views; imm.restartInput() in focusCell rebinds it to the new ones.
        editTextFields.forEach { it.clearFocus() }
        currentFocus?.clearFocus()
        val imm0 = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (!imeVisible) imm0.hideSoftInputFromWindow(rootFrame.windowToken, 0)

        gridContainer.removeAllViews(); editTextFields.clear()

        val grid = GridLayout(this).apply {
            rowCount = numRows; columnCount = numColumns
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = ViewGroup.LayoutParams(numColumns * cellSize, gridHeight)
        }
        // Each index encodes (col = index/numRows, row = index%numRows).
        // row is always in 0 until numRows, so (row+1)*cellSize <= gridHeight <= availH.
        // Only numColumns cells are created now; expandGrid() adds more on demand.
        repeat(numRows * numColumns) { index ->
            val cellRow = index % numRows
            val cellCol = index / numRows
            check(cellRow < numRows && (cellRow + 1) * cellSize <= availH) {
                "Cell row=$cellRow overflows availH=$availH with cellSize=$cellSize"
            }
            val et = makeCell(cellRow, cellCol, index, cellSize, fontPx)
            editTextFields.add(et); grid.addView(et)
        }
        hScroll = HorizontalScrollView(this).apply {
            // Explicit gridHeight: gridContainer is WC so MATCH_PARENT would collapse to 0.
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(MP, gridHeight)
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(grid)
        }
        gridContainer.addView(hScroll)

        // After layout, restore scroll so anchorCol stays at the right edge.
        hScroll?.viewTreeObserver?.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                hScroll?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                val s = hScroll ?: return
                val gw = maxOf(numColumns * cellSize, s.width)
                val targetScrollX = (gw - anchorCol * cellSize - s.width).coerceAtLeast(0)
                s.post { s.scrollTo(targetScrollX, 0) }
            }
        })

        withRestoring {
            for (col in 0 until minOf(numColumns, columnData.size)) {
                val colData = columnData[col]
                for (row in 0 until minOf(numRows, colData.size)) {
                    val ch = colData[row]
                    if (ch.isNotEmpty()) editTextFields[col * numRows + row].setText(ch)
                }
            }
        }

        val lastFilled = editTextFields.indexOfLast { it.text.isNotEmpty() }
        val focusIdx = if (lastFilled < 0) 0 else (lastFilled + 1).coerceAtMost(editTextFields.size - 1)
        val focusEt  = editTextFields[focusIdx]
        if (!isInitialBoot) {
            // Restore keyboard state: if it was open before the rebuild, re-open it.
            // If it was closed (user was browsing styles), keep it closed so we don't
            // interrupt them with an unwanted keyboard popup.
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
                // If the tools panel is visible, the first tap dismisses it and brings up
                // the keyboard — the cell still receives focus, but the panel closes first.
                if (toolsVisible) {
                    allToolsPanel.visibility = View.GONE
                    toolsVisible = false
                    toolsCell?.setBackgroundColor(Color.TRANSPARENT)
                    // Pre-set padding to keyboard height so the grid never jumps.
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
                        // Row 0 of a break column → undo the column break (merge back).
                        if (cellRow == 0 && cellCol > 0 && inputMode != InputMode.SCATTER
                                && columnBreaks.contains(cellCol)) {
                            removeColumnBreak(cellCol)
                            return@setOnKeyListener true
                        }
                        if (inputMode == InputMode.SCATTER) {
                            val colList = columnData.getOrNull(cellCol)
                            val focusTarget = (index - 1).coerceAtLeast(0)
                            if (colList != null && cellRow < colList.size) {
                                withRestoring {
                                    colList.removeAt(cellRow)
                                    colList.add("")
                                }
                            }
                            editTextFields.getOrNull(focusTarget)?.requestFocus()
                            gridContainer.post { refreshGrid(); focusCell(focusTarget) }
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
                    if (originalChar.isNotEmpty() && inputMode == InputMode.SEQUENTIAL) {
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
                    val replacement = when {
                        text.startsWith(originalChar) -> text.substring(1).firstOrNull()
                        text.endsWith(originalChar)   -> text.dropLast(1).lastOrNull()
                        else                          -> text.lastOrNull()
                    }?.toString() ?: return
                    setColumnChar(cellCol, cellRow, replacement)
                    suppress = true
                    editable.replace(0, editable.length, replacement)
                    ensureBufferColumn()
                    advanceToNextCell(index, editTextFields.size)
                    return
                }

                if (text.length > 1 || text.contains('\n')) {
                    // ── Insert-shift (cursor-position-aware, any number of new chars) ──
                    // Triggered when the IME commits one or more chars into an occupied cell
                    // in SEQUENTIAL mode.  The EditText content is a mix of the original char
                    // and the newly committed chars.  We extract the new portion by stripping
                    // the preserved original, then shift existing content right and insert.
                    //
                    // Left side (cursorBefore=true)  → insert at the current cell; original shifts down.
                    // Right side (cursorBefore=false) → insert at the cell below; current cell unchanged.
                    // If the original char is not found in the new text (IME fully replaced it),
                    // fall through to the paste path — we cannot determine user intent safely.
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
                    setColumnChar(cellCol, cellRow, raw)
                    ensureBufferColumn()
                    advanceToNextCell(index, editTextFields.size)
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
        wordGapDp   = prefs.getFloat("word_gap_dp", 0f)
        gridTextColor = prefs.getInt("text_color", Color.BLACK)
        bgColor     = prefs.getInt("bg_color", Color.WHITE)
        inputMode   = when (prefs.getString("input_mode", "SEQUENTIAL")) {
            "SCATTER" -> InputMode.SCATTER
            else      -> InputMode.SEQUENTIAL
        }
        bgImageUri  = prefs.getString("bg_image_uri", null)
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

        // Sync UI widgets — guards in each listener prevent side-effect loops:
        // font/size spinners early-return when pos == current index + grid non-empty;
        // seekBar fromUser=false skips rebuildGrid.
        fontSpinnerRef?.setSelection(fontIndex)
        fontSizeLabelRef?.text = fontSizeSp.toInt().toString()
        gapValueLabelRef?.text = wordGapDp.toInt().toString()

        applyTextColor(gridTextColor)

        if (!imgUri.isNullOrEmpty()) {
            loadBgImageFromUri(imgUri)
        } else {
            bgImageUri = null
            bgImageView?.visibility = View.GONE
            rootFrame.setBackgroundColor(bgColor)
        }

        refreshModeChips()
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
        // Reset all settings to defaults for a fresh session
        applySettings(
            fontIdx = 0, sizeSp = 24f, gapDp = 0f,
            textColor = Color.BLACK, bgCol = Color.WHITE,
            imgUri = null, mode = InputMode.SEQUENTIAL
        )
        needsReflow = true; rebuildGrid()
    }

    private fun nextNewSessionName(): String = SessionManager.nextNewSessionName(filesDir)

    private fun ensureDefaultSession() {
        SessionManager.ensureDefaultSession(filesDir, currentSessionId, currentSessionName,
            columnData, columnBreaks, fontIndex, fontSizeSp, wordGapDp,
            gridTextColor, bgColor, bgImageUri, inputMode.name)
    }
}
