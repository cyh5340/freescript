package com.freescript

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import androidx.core.widget.NestedScrollView
import kotlin.math.roundToInt

class ViewFactory(private val context: Context, private val cb: Callbacks) {

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    // ── Exposed refs ────────────────────────────────────────────────────
    var allToolsPanel: LinearLayout? = null
    var punctToolbar: HorizontalScrollView? = null
    var toolsCell: LinearLayout? = null
    var docFileNameRef: TextView? = null
    var modeChipContainer: LinearLayout? = null
    var modeInputSectionRef: LinearLayout? = null
    var inputFieldCellRef: TextView? = null
    var inputFieldDividerRef: View? = null
    var fontSpinnerRef: Spinner? = null
    var fontSizeLabelRef: TextView? = null
    var gapValueLabelRef: TextView? = null
    var undoButton: TextView? = null
    var redoButton: TextView? = null
    var keyboardCell: LinearLayout? = null
    var insertImageContainer: LinearLayout? = null
    var scrollIndicatorContainer: FrameLayout? = null
    var scrollIndicatorThumb: View? = null
    var boxFillColorRow: View? = null
    var boxBorderVisibleCheckbox: android.widget.CheckBox? = null
    var fontApplyAllCheckbox: android.widget.CheckBox? = null
    var textColorApplyAllCheckbox: android.widget.CheckBox? = null

    private var boxFillApplyAllCheckbox: android.widget.CheckBox? = null
    private var boxBorderApplyAllCheckbox: android.widget.CheckBox? = null
    private val borderThicknessChips = mutableListOf<TextView>()

    private val textColorSwatches = mutableListOf<Pair<Int, View>>()
    private val bgColorSwatches = mutableListOf<Pair<Int, View>>()
    private val boxFillColorSwatches = mutableListOf<Pair<Int, View>>()
    private var boxFillNoneContainer: LinearLayout? = null
    private val boxBorderColorSwatches = mutableListOf<Pair<Int, View>>()

    fun isFreestyleApplyAllActive(): Boolean =
        (boxFillApplyAllCheckbox?.isChecked == true) || (boxBorderApplyAllCheckbox?.isChecked == true)

    fun syncColorSelectionUI() {
        val dp = context.resources.displayMetrics.density
        val textSelected = cb.getSelectedTextColor()
        val bgSelected = cb.getSelectedBgColor()
        val boxFillSelected = cb.getSelectedBoxFillColor()
        val boxBorderSelected = cb.getSelectedBoxBorderColor()

        textColorSwatches.forEach { (color, swatch) ->
            swatch.background = swatchDrawable(color, dp, color == textSelected)
        }
        bgColorSwatches.forEach { (color, swatch) ->
            swatch.background = swatchDrawable(color, dp, color == bgSelected)
        }
        boxFillColorSwatches.forEach { (color, swatch) ->
            swatch.background = swatchDrawable(color, dp, color == boxFillSelected)
        }
        boxBorderColorSwatches.forEach { (color, swatch) ->
            swatch.background = swatchDrawable(color, dp, color == boxBorderSelected)
        }

        boxFillNoneContainer?.background = if (boxFillSelected == Color.TRANSPARENT) {
            GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke((2.2f * dp).roundToInt(), context.getColor(R.color.row_active))
                cornerRadius = 6f * dp
            }
        } else null
    }

    fun syncBoxBorderUI(box: TextBoxInstance?) {
        val check = boxBorderVisibleCheckbox ?: return
        check.setOnCheckedChangeListener(null)
        check.isChecked = box?.borderVisible ?: cb.getSelectedBoxBorderVisible()
        val applyAllRef = boxBorderApplyAllCheckbox
        check.setOnCheckedChangeListener { _, isChecked ->
            cb.onBoxBorderVisibilityChanged(isChecked, applyAllRef?.isChecked == true)
        }
        val selIdx = box?.borderThicknessIdx ?: cb.getSelectedBoxBorderThicknessIndex()
        borderThicknessChips.forEachIndexed { i, chip ->
            (chip.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                if (i == selIdx) context.getColor(R.color.chip_active) else Color.TRANSPARENT)
        }
        syncColorSelectionUI()
    }

    private var isScrollIndicatorDragging = false
    private var scrollIndicatorDragStartX = 0f
    private var scrollIndicatorDragStartScrollX = 0
    private var scrollIndicatorDragStartY = 0f
    private var scrollIndicatorDragStartScrollY = 0

    interface Callbacks {
        fun provideFontCatalogue(): List<FontEntry>
        fun getFontIndex(): Int
        fun getFontSizeSp(): Float
        fun getWordGapDp(): Float
        fun getSelectedTypeface(): Typeface
        fun getInputMode(): InputMode
        fun getCurrentSessionName(): String
        fun onToolsToggle()
        fun onPunctInsert(punct: String)
        fun onFontSelected(pos: Int, applyAll: Boolean)
        fun onFontSizeChanged(newSize: Float, applyAll: Boolean)
        fun onWordGapChanged(newGap: Int, applyAll: Boolean)
        fun onFontApplyAllToggled(checked: Boolean)
        fun onBgColorSelected(color: Int)
        fun onBoxBgColorSelected(color: Int, applyAll: Boolean)
        fun onBoxFillApplyAllToggled(checked: Boolean)
        fun onBoxBorderVisibilityChanged(visible: Boolean, applyAll: Boolean)
        fun onBoxBorderColorChanged(color: Int, applyAll: Boolean)
        fun onBoxBorderThicknessChanged(idx: Int, applyAll: Boolean)
        fun onBoxBorderApplyAllToggled(checked: Boolean)
        fun getSelectedTextColor(): Int
        fun getSelectedBgColor(): Int
        fun getSelectedBoxFillColor(): Int
        fun getSelectedBoxBorderColor(): Int
        fun getSelectedBoxBorderVisible(): Boolean
        fun getSelectedBoxBorderThicknessIndex(): Int
        fun onInsertImageRequested()
        fun onRemoveInsertedImage()
        fun onTextColorSelected(color: Int, applyAll: Boolean)
        fun onTextColorApplyAllToggled(checked: Boolean)
        fun onModeSelected(mode: InputMode)
        fun onCopySelection()
        fun onCutSelection()
        fun onPasteAtSelection()
        fun onSelectEntireLine()
        fun onSelectEntireParagraph()
        fun onSelectAll()
        fun onHandlePasteClicked()
        fun onShowAllSessions()
        fun onCollapsePanel()
        fun onUndoAction()
        fun onRedoAction()
        fun onTabAction()
        fun onAboutClicked()
        fun onScreenshot()
        fun onKeyboardToggle()
        fun onInputFieldEdit()
        fun canUndo(): Boolean
        fun canRedo(): Boolean
        fun getNumColumns(): Int
        fun getCurrentCellSize(): Int
        fun getHScrollView(): HorizontalScrollView?
        fun getMainScrollView(): NestedScrollView
        fun getCanvasMode(): CanvasMode
    }

    // ── Scroll indicator ────────────────────────────────────────���────────
    fun buildScrollIndicator(dp: Float): FrameLayout {
        val trackH = (4 * dp).roundToInt().coerceAtLeast(4)

        val track = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(MP, FrameLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(android.graphics.Color.argb(35, 0, 0, 0))
                cornerRadius = trackH / 2f
            }
        }

        val thumb = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(android.graphics.Color.argb(130, 80, 80, 80))
                cornerRadius = trackH / 2f
            }
        }
        scrollIndicatorThumb = thumb

        val container = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(MP, trackH)
                .apply { setMargins((8 * dp).roundToInt(), (6 * dp).roundToInt(),
                                    (8 * dp).roundToInt(), (6 * dp).roundToInt()) }
            addView(track)
            addView(thumb)
            visibility = View.GONE
        }
        scrollIndicatorContainer = container

        container.setOnTouchListener { v, event ->
            val trackW = v.width
            val thumbW = (scrollIndicatorThumb?.width ?: 0)
            val usable = (trackW - thumbW).coerceAtLeast(1)
            val contentSize = cb.getNumColumns() * cb.getCurrentCellSize()
            if (cb.getCanvasMode() == CanvasMode.HORIZONTAL) {
                val sv = cb.getMainScrollView()
                val viewportH = sv.height
                val maxScrollY = (contentSize - viewportH).coerceAtLeast(1)
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isScrollIndicatorDragging = true
                        scrollIndicatorDragStartX = event.x
                        scrollIndicatorDragStartScrollY = sv.scrollY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isScrollIndicatorDragging) return@setOnTouchListener false
                        val dx = event.x - scrollIndicatorDragStartX
                        val scrollDelta = (dx / usable * maxScrollY).roundToInt()
                        sv.scrollTo(0, (scrollIndicatorDragStartScrollY + scrollDelta).coerceIn(0, maxScrollY))
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isScrollIndicatorDragging = false; true
                    }
                    else -> false
                }
            } else {
                val s = cb.getHScrollView() ?: return@setOnTouchListener false
                val viewportW = s.width
                val maxScrollX = (contentSize - viewportW).coerceAtLeast(1)
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isScrollIndicatorDragging = true
                        scrollIndicatorDragStartX = event.x
                        scrollIndicatorDragStartScrollX = s.scrollX
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!isScrollIndicatorDragging) return@setOnTouchListener false
                        val dx = event.x - scrollIndicatorDragStartX
                        val scrollDelta = (dx / usable * maxScrollX).roundToInt()
                        s.scrollTo((scrollIndicatorDragStartScrollX + scrollDelta).coerceIn(0, maxScrollX), 0)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isScrollIndicatorDragging = false; true
                    }
                    else -> false
                }
            }
        }

        return container
    }

    fun updateScrollIndicator() {
        val container = scrollIndicatorContainer ?: return
        val thumb = scrollIndicatorThumb ?: return
        val contentSize = cb.getNumColumns() * cb.getCurrentCellSize()
        val density = context.resources.displayMetrics.density
        val minThumbPx = (24 * density).roundToInt()
        if (cb.getCanvasMode() == CanvasMode.HORIZONTAL) {
            val sv = cb.getMainScrollView()
            val viewportH = sv.height
            if (viewportH <= 0 || contentSize <= viewportH) { container.visibility = View.GONE; return }
            container.visibility = View.VISIBLE
            container.post {
                val trackW = container.width
                if (trackW <= 0) return@post
                val ratio = viewportH.toFloat() / contentSize
                val thumbW = (trackW * ratio).roundToInt().coerceAtLeast(minThumbPx)
                val usable = (trackW - thumbW).coerceAtLeast(0)
                val maxScrollY = (contentSize - viewportH).coerceAtLeast(1)
                val thumbLeft = (usable * sv.scrollY.toFloat() / maxScrollY).roundToInt()
                thumb.layoutParams = (thumb.layoutParams as FrameLayout.LayoutParams).apply {
                    width = thumbW; leftMargin = thumbLeft
                }
                thumb.requestLayout()
            }
        } else {
            if (cb.getCanvasMode() == CanvasMode.FREESTYLE) { container.visibility = View.GONE; return }
            val s = cb.getHScrollView() ?: return
            val viewportW = s.width
            if (viewportW <= 0 || contentSize <= viewportW) { container.visibility = View.GONE; return }
            container.visibility = View.VISIBLE
            container.post {
                val trackW = container.width
                if (trackW <= 0) return@post
                val ratio = viewportW.toFloat() / contentSize
                val thumbW = (trackW * ratio).roundToInt().coerceAtLeast(minThumbPx)
                val usable = (trackW - thumbW).coerceAtLeast(0)
                val maxScrollX = (contentSize - viewportW).coerceAtLeast(1)
                val thumbLeft = (usable * s.scrollX.toFloat() / maxScrollX).roundToInt()
                thumb.layoutParams = (thumb.layoutParams as FrameLayout.LayoutParams).apply {
                    width = thumbW; leftMargin = thumbLeft
                }
                thumb.requestLayout()
            }
        }
    }

    // ── Bottom panel ─────────────────────────────────────────────────────
    fun buildBottomPanel(dp: Float): LinearLayout {
        allToolsPanel = buildAllToolsPanel(dp)
        val toolbar = buildToolbar(dp)
        punctToolbar = buildPunctToolbar(dp)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(MP, (1f * dp).roundToInt())
                setBackgroundColor(context.getColor(R.color.divider_dark))
            })
            addView(punctToolbar)
            addView(toolbar)
        }
    }

    // ── Toolbar ──────────────────────────────────────────────────────────
    private fun buildToolbar(dp: Float): FrameLayout {
        val toolbarH = (54 * dp).roundToInt()
        var mainBarRef: LinearLayout? = null
        var punctBarRef: LinearLayout? = null

        val iconW = (44 * dp).roundToInt()

        val mainBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(MP, toolbarH)
            setBackgroundColor(context.getColor(R.color.surface))

            val tCell = buildCategoryCell("⚙", toolbarH) { cb.onToolsToggle() }
            toolsCell = tCell
            addView(tCell)
            addView(divider(toolbarH, dp))

            val undoBtn = TextView(context).apply {
                text = "⟲"; textSize = 18f; gravity = Gravity.CENTER
                setTextColor(
                    context.getColor(
                        if (cb.canUndo()) R.color.text_dark else R.color.text_hint
                    )
                )
                layoutParams = LinearLayout.LayoutParams(iconW, toolbarH)
                setOnClickListener { cb.onUndoAction() }
            }
            undoButton = undoBtn
            addView(undoBtn)

            val redoBtn = TextView(context).apply {
                text = "⟳"; textSize = 18f; gravity = Gravity.CENTER
                setTextColor(
                    context.getColor(
                        if (cb.canRedo()) R.color.text_dark else R.color.text_hint
                    )
                )
                layoutParams = LinearLayout.LayoutParams(iconW, toolbarH)
                setOnClickListener { cb.onRedoAction() }
            }
            redoButton = redoBtn
            addView(redoBtn)
            addView(divider(toolbarH, dp))

            addView(TextView(context).apply {
                text = "→∣"; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.text_dark))
                layoutParams = LinearLayout.LayoutParams(iconW, toolbarH)
                setOnClickListener { cb.onTabAction() }
            })

            addView(divider(toolbarH, dp))

            val pCell = buildCategoryCell("。，、", toolbarH) {
                mainBarRef?.visibility = View.GONE
                punctBarRef?.visibility = View.VISIBLE
            }
            addView(pCell)

            addView(divider(toolbarH, dp))

            val kCell = buildCategoryCell("⌨", toolbarH) { cb.onKeyboardToggle() }
            keyboardCell = kCell
            addView(kCell)

            divider(toolbarH, dp).also { inputFieldDividerRef = it; addView(it) }

            addView(TextView(context).apply {
                text = "⌗"; textSize = 18f; gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.text_dark))
                layoutParams = LinearLayout.LayoutParams(iconW, toolbarH)
                setOnClickListener { cb.onInputFieldEdit() }
                inputFieldCellRef = this
            })

            addView(divider(toolbarH, dp))

            addView(TextView(context).apply {
                text = "⛶"; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.text_dark))
                layoutParams = LinearLayout.LayoutParams(iconW, toolbarH)
                setOnClickListener { cb.onScreenshot() }
            })
        }
        mainBarRef = mainBar

        val punctBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(MP, toolbarH)
            setBackgroundColor(context.getColor(R.color.surface))
            visibility = View.GONE

            addView(TextView(context).apply {
                text = "←"; textSize = 20f; gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.text_dark))
                layoutParams = LinearLayout.LayoutParams((54 * dp).roundToInt(), toolbarH)
                setOnClickListener {
                    punctBarRef?.visibility = View.GONE
                    mainBarRef.visibility = View.VISIBLE
                }
            })
            addView(divider(toolbarH, dp))
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(0, toolbarH, 1f)
                addView(buildPunctRow(dp))
            })
        }
        punctBarRef = punctBar

        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(MP, toolbarH)
            setBackgroundColor(context.getColor(R.color.surface))
            addView(mainBar)
            addView(punctBar)
        }
    }

    fun buildCategoryCell(label: String, toolbarH: Int, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, toolbarH, 1f)
            addView(iconText(label, 14f))
            setOnClickListener { onClick() }
        }

    // ── Punctuation toolbar ───────────────────────────────────────────────
    fun buildPunctToolbar(dp: Float): HorizontalScrollView =
        HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(context.getColor(R.color.panel_bg))
            visibility = View.GONE
            addView(buildPunctRow(dp))
        }

    fun buildPunctRow(dp: Float): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val vPad = (6 * dp).roundToInt()
            setPadding((8 * dp).roundToInt(), vPad, (8 * dp).roundToInt(), vPad)
            AppConfig.PUNCT_LIST.forEach { punct -> addView(buildPunctButton(punct, dp)) }
        }

    fun buildPunctButton(punct: String, dp: Float): TextView =
        TextView(context).apply {
            text = punct
            textSize = 20f
            typeface = cb.getSelectedTypeface()
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.text_dark))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke((1f * dp).roundToInt(), context.getColor(R.color.stroke))
                cornerRadius = 8f * dp
            }
            val hPad = (10 * dp).roundToInt();
            val vPad = (6 * dp).roundToInt()
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams =
                LinearLayout.LayoutParams(WC, WC).also { it.marginEnd = (6 * dp).roundToInt() }
            setOnClickListener { cb.onPunctInsert(punct) }
        }

    // ── All-tools panel ───────────────────────────────────────────────────
    fun buildAllToolsPanel(dp: Float): LinearLayout {
        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)

            addView(buildFontRow(dp))
            addView(subDivider(dp))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MP, WC)
                addView(HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER
                    layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                    addView(buildTextColorPanel(dp).also {
                        it.layoutParams = LinearLayout.LayoutParams(WC, WC)
                    })
                })
                addView(android.widget.CheckBox(context).apply {
                    text = context.getString(R.string.apply_all_label); textSize = 10f
                    setTextColor(context.getColor(R.color.text_lighter))
                    layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                        it.setMargins((4 * dp).roundToInt(), 0, (8 * dp).roundToInt(), 0)
                    }
                    visibility = View.GONE
                    setOnCheckedChangeListener { _, isChecked ->
                        cb.onTextColorApplyAllToggled(isChecked)
                    }
                    textColorApplyAllCheckbox = this
                })
            })
            addView(subDivider(dp))

            addView(buildBgColorHeaderRow(dp))
            addView(subDivider(dp))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(MP, WC)
                visibility = View.GONE
                addView(buildBoxFillColorRow(dp))
                addView(subDivider(dp))
                addView(buildBoxBorderRow(dp))
                addView(subDivider(dp))
            }.also { boxFillColorRow = it })

            addView(buildInsertPanel(dp))
            addView(subDivider(dp))

            addView(buildModeHeaderRow(dp))
            addView(subDivider(dp))

            addView(buildFileHeaderRow(dp))
            addView(subDivider(dp))

            addView(buildMoreRow(dp))
        }

        val scrollView = NestedScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(MP, 0, 1f)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(scrollContent)
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MP, (280 * dp).roundToInt(), Gravity.BOTTOM)
            setBackgroundColor(context.getColor(R.color.panel_bg))
            visibility = View.GONE

            addView(TextView(context).apply {
                text = "▾"; textSize = 12f; gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.text_light))
                layoutParams = LinearLayout.LayoutParams(MP, (36 * dp).roundToInt())
                setOnClickListener { cb.onCollapsePanel() }
            })
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(MP, (1f * dp).roundToInt())
                setBackgroundColor(context.getColor(R.color.stroke))
            })
            addView(scrollView)
            post { syncColorSelectionUI() }
        }
    }

    // ── Insert panel (image overlay) ──────────────────────────────────────
    private fun buildInsertPanel(dp: Float): View {
        val imgStatus = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(WC, WC)
        }
        insertImageContainer = imgStatus

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            val hPad = (12 * dp).roundToInt();
            val vPad = (10 * dp).roundToInt()
            setPadding(hPad, vPad, hPad, vPad)

            addView(TextView(context).apply {
                text = context.getString(R.string.label_insert); textSize = 11f
                setTextColor(context.getColor(R.color.text_lighter))
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.marginEnd = (8 * dp).roundToInt()
                }
            })

            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })

            addView(TextView(context).apply {
                text = context.getString(R.string.btn_select_image); textSize = 13f; gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.text_dark))
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke((1f * dp).roundToInt(), context.getColor(R.color.stroke))
                    cornerRadius = 20f * dp
                }
                val hP = (14 * dp).roundToInt();
                val vP = (5 * dp).roundToInt()
                setPadding(hP, vP, hP, vP)
                layoutParams = LinearLayout.LayoutParams(WC, WC)
                    .also { it.marginEnd = (8 * dp).roundToInt() }
                setOnClickListener { cb.onInsertImageRequested() }
            })
            addView(imgStatus)
        }
    }

    // ── Colour panels ─────────────────────────────────────────────────────
    fun buildTextColorPanel(dp: Float) = buildColorSwatchRow(
        dp = dp,
        colors = AppConfig.TEXT_COLORS,
        registry = textColorSwatches
    ) {
        cb.onTextColorSelected(it.color, textColorApplyAllCheckbox?.isChecked == true)
    }

    fun buildBgColorPanel(dp: Float) = buildColorSwatchRow(
        dp = dp,
        colors = AppConfig.BG_COLORS,
        registry = bgColorSwatches
    ) {
        cb.onBgColorSelected(it.color)
    }

    private fun buildBgColorHeaderRow(dp: Float): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            setPadding((12 * dp).roundToInt(), 0, 0, 0)

            addView(TextView(context).apply {
                text = context.getString(R.string.label_bg_color); textSize = 11f
                setTextColor(context.getColor(R.color.text_lighter))
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.marginEnd = (8 * dp).roundToInt()
                }
            })

            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                addView(buildBgColorPanel(dp).also {
                    it.layoutParams = LinearLayout.LayoutParams(WC, WC)
                })
            })
        }

    // Box fill-color row — shown only when a FREESTYLE textbox is active.
    // The "◼填色" icon title mirrors the fill-bucket convention used in drawing apps.
    private fun buildBoxFillColorRow(dp: Float): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            setPadding((12 * dp).roundToInt(), 0, 0, 0)

            addView(TextView(context).apply {
                text = context.getString(R.string.label_box_fill_color); textSize = 11f
                setTextColor(context.getColor(R.color.text_lighter))
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.marginEnd = (8 * dp).roundToInt()
                }
            })

            val swatchSz = (38 * dp).roundToInt()
            val swatchRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(WC, WC)
                val vPad = (10 * dp).roundToInt()
                setPadding(0, vPad, (8 * dp).roundToInt(), vPad)

                // "No fill" swatch — white square with a red diagonal slash
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                        it.marginEnd = (6 * dp).roundToInt()
                    }
                    boxFillNoneContainer = this
                    addView(object : android.view.View(context) {
                        private val strokePx = (1f * dp)
                        private val slashPx  = (1.5f * dp)
                        private val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = context.getColor(R.color.stroke); style = android.graphics.Paint.Style.STROKE; strokeWidth = strokePx
                        }
                        private val slashPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.RED; style = android.graphics.Paint.Style.STROKE; strokeWidth = slashPx
                        }
                        init { setBackgroundColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(swatchSz, swatchSz) }
                        override fun onDraw(c: android.graphics.Canvas) {
                            super.onDraw(c)
                            val w = width.toFloat(); val h = height.toFloat()
                            c.drawRect(strokePx/2, strokePx/2, w - strokePx/2, h - strokePx/2, borderPaint)
                            c.drawLine(w, 0f, 0f, h, slashPaint)
                        }
                    })
                    addView(TextView(context).apply {
                        text = context.getString(R.string.box_fill_none); textSize = 9f
                        setTextColor(context.getColor(R.color.text_medium)); gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(swatchSz, WC)
                    })
                    setOnClickListener {
                        cb.onBoxBgColorSelected(Color.TRANSPARENT, boxFillApplyAllCheckbox?.isChecked == true)
                    }
                })

                // Color swatches (reuse BG_COLORS)
                boxFillColorSwatches.clear()
                AppConfig.BG_COLORS.forEach { option ->
                    addView(buildSwatchItem(
                        option = option,
                        swatchSz = swatchSz,
                        dp = dp,
                        registry = boxFillColorSwatches
                    ) {
                        cb.onBoxBgColorSelected(option.color, boxFillApplyAllCheckbox?.isChecked == true)
                    })
                }
            }

            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                addView(swatchRow)
            })

            addView(android.widget.CheckBox(context).apply {
                text = context.getString(R.string.apply_all_label); textSize = 10f
                setTextColor(context.getColor(R.color.text_lighter))
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.setMargins((4 * dp).roundToInt(), 0, (8 * dp).roundToInt(), 0)
                }
                setOnCheckedChangeListener { _, isChecked ->
                    cb.onBoxFillApplyAllToggled(isChecked)
                    syncColorSelectionUI()
                }
                boxFillApplyAllCheckbox = this
            })
        }

    private fun buildBoxBorderRow(dp: Float): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            setPadding((12 * dp).roundToInt(), 0, 0, 0)

            addView(TextView(context).apply {
                text = context.getString(R.string.label_box_border); textSize = 11f
                setTextColor(context.getColor(R.color.text_lighter))
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.marginEnd = (6 * dp).roundToInt()
                }
            })

            // Visibility checkbox
            addView(android.widget.CheckBox(context).apply {
                text = context.getString(R.string.box_border_show); textSize = 10f
                isChecked = true
                setTextColor(context.getColor(R.color.text_dark))
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.marginEnd = (6 * dp).roundToInt()
                }
                val applyAllRef = boxBorderApplyAllCheckbox
                setOnCheckedChangeListener { _, isChecked ->
                    cb.onBoxBorderVisibilityChanged(isChecked, applyAllRef?.isChecked == true)
                }
                boxBorderVisibleCheckbox = this
            })

            // Scrollable area: thickness chips + color swatches
            val scrollContent = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(WC, WC)
                val vPad = (10 * dp).roundToInt()
                setPadding(0, vPad, (8 * dp).roundToInt(), vPad)

                // Thickness chips
                borderThicknessChips.clear()
                AppConfig.BORDER_THICKNESS_LIST.forEachIndexed { idx, (_, label) ->
                    addView(TextView(context).apply {
                        text = label; textSize = 11f; gravity = Gravity.CENTER
                        setTextColor(context.getColor(R.color.text_dark))
                        val bg = GradientDrawable().apply {
                            setColor(if (idx == 1) context.getColor(R.color.chip_active) else Color.TRANSPARENT)
                            cornerRadius = 4f * dp
                        }
                        background = bg
                        val hP = (8 * dp).roundToInt(); val vP = (5 * dp).roundToInt()
                        setPadding(hP, vP, hP, vP)
                        layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                            it.marginEnd = (4 * dp).roundToInt()
                        }
                        borderThicknessChips.add(this)
                        val localIdx = idx
                        setOnClickListener {
                            borderThicknessChips.forEachIndexed { i, chip ->
                                (chip.background as? GradientDrawable)?.setColor(
                                    if (i == localIdx) context.getColor(R.color.chip_active) else Color.TRANSPARENT)
                            }
                            cb.onBoxBorderThicknessChanged(localIdx, boxBorderApplyAllCheckbox?.isChecked == true)
                        }
                    })
                }

                // Thin divider between thickness and color
                addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams((1 * dp).roundToInt(), (24 * dp).roundToInt()).also {
                        it.setMargins((4 * dp).roundToInt(), 0, (8 * dp).roundToInt(), 0)
                    }
                    setBackgroundColor(context.getColor(R.color.divider))
                })

                // Border color swatches
                val swatchSz = (38 * dp).roundToInt()
                boxBorderColorSwatches.clear()
                AppConfig.BORDER_COLORS.forEach { option ->
                    addView(buildSwatchItem(
                        option = option,
                        swatchSz = swatchSz,
                        dp = dp,
                        registry = boxBorderColorSwatches
                    ) {
                        cb.onBoxBorderColorChanged(option.color, boxBorderApplyAllCheckbox?.isChecked == true)
                    })
                }
            }

            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                addView(scrollContent)
            })

            addView(android.widget.CheckBox(context).apply {
                text = context.getString(R.string.apply_all_label); textSize = 10f
                setTextColor(context.getColor(R.color.text_lighter))
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.setMargins((4 * dp).roundToInt(), 0, (8 * dp).roundToInt(), 0)
                }
                boxBorderApplyAllCheckbox = this
                val visCheck = boxBorderVisibleCheckbox
                val applyAllSelf = this
                // Re-wire the visibility checkbox so it can read the now-available apply-all ref
                visCheck?.setOnCheckedChangeListener { _, isChecked ->
                    cb.onBoxBorderVisibilityChanged(isChecked, applyAllSelf.isChecked)
                }
                setOnCheckedChangeListener { _, isChecked ->
                    cb.onBoxBorderApplyAllToggled(isChecked)
                    syncColorSelectionUI()
                }
            })
        }

    private fun buildColorSwatchRow(
        dp: Float,
        colors: List<ColorOption>,
        registry: MutableList<Pair<Int, View>>,
        onClick: (ColorOption) -> Unit
    ): LinearLayout {
        val swatchSz = (38 * dp).roundToInt()
        registry.clear()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            setPadding(
                (8 * dp).roundToInt(), (10 * dp).roundToInt(),
                (8 * dp).roundToInt(), (10 * dp).roundToInt()
            )
            colors.forEach { option ->
                addView(buildSwatchItem(
                    option = option,
                    swatchSz = swatchSz,
                    dp = dp,
                    registry = registry
                ) {
                    onClick(option)
                })
            }
        }
    }

    private fun buildSwatchItem(
        option: ColorOption,
        swatchSz: Int,
        dp: Float,
        registry: MutableList<Pair<Int, View>>,
        onClick: () -> Unit
    ): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams =
                LinearLayout.LayoutParams(WC, WC).also { it.marginEnd = (6 * dp).roundToInt() }
            val swatchView = View(context).apply {
                background = swatchDrawable(option.color, dp, selected = false)
                layoutParams = LinearLayout.LayoutParams(swatchSz, swatchSz)
            }
            registry.add(option.color to swatchView)
            addView(swatchView)
            addView(TextView(context).apply {
                text = context.getString(option.labelRes); textSize = 9f
                setTextColor(context.getColor(R.color.text_medium)); gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(swatchSz, WC)
            })
            setOnClickListener { onClick() }
        }

    // ── Font row ──────────────────────────────────────────────────────────
    fun buildFontRow(dp: Float): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MP, WC)
        val hPad = (8 * dp).roundToInt();
        val vPad = (8 * dp).roundToInt()
        setPadding(hPad, vPad, hPad, vPad)

        fun rowDivider() = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (1f * dp).roundToInt(), (28 * dp).roundToInt()
            ).also { it.marginStart = (8 * dp).roundToInt(); it.marginEnd = (8 * dp).roundToInt() }
            setBackgroundColor(context.getColor(R.color.divider))
        }

        fun label(t: String) = TextView(context).apply {
            text = t; textSize = 11f
            setTextColor(context.getColor(R.color.text_light))
            layoutParams =
                LinearLayout.LayoutParams(WC, WC).also { it.marginEnd = (4 * dp).roundToInt() }
        }

        fun chip(initial: String) = TextView(context).apply {
            text = initial; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.text_dark))
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.chip_active)); cornerRadius = 4f * dp
            }
            val hP = (8 * dp).roundToInt();
            val vP = (2 * dp).roundToInt()
            setPadding(hP, vP, hP, vP)
            layoutParams = LinearLayout.LayoutParams(WC, WC)
        }

        addView(label(context.getString(R.string.label_font)))
        addView(buildFontSpinner(dp).also {
            it.layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
        })
        addView(rowDivider())

        addView(label(context.getString(R.string.label_font_size)))
        val sizeChip = chip(cb.getFontSizeSp().toInt().toString())
        fontSizeLabelRef = sizeChip
        sizeChip.setOnClickListener {
            showPopupSeekbar(
                anchor = it,
                max = AppConfig.FONT_SIZE_LIST.size - 1,
                initial = AppConfig.FONT_SIZE_LIST.indexOfFirst { e -> e.first == cb.getFontSizeSp() }
                    .coerceAtLeast(0),
                format = { i -> AppConfig.FONT_SIZE_LIST[i].second }
            ) { p ->
                val newSize = AppConfig.FONT_SIZE_LIST[p].first
                if (newSize != cb.getFontSizeSp()) {
                    fontSizeLabelRef?.text = newSize.toInt().toString()
                    cb.onFontSizeChanged(newSize, fontApplyAllCheckbox?.isChecked == true)
                }
            }
        }
        addView(sizeChip)
        addView(rowDivider())

        addView(label(context.getString(R.string.label_word_gap)))
        val initialGapIdx = AppConfig.WORD_GAP_LIST.indexOfFirst { e -> e.first == cb.getWordGapDp() }
            .coerceAtLeast(0)
        val gapChip = chip(AppConfig.WORD_GAP_LIST[initialGapIdx].second)
        gapValueLabelRef = gapChip
        gapChip.setOnClickListener {
            showPopupSeekbar(
                anchor = it,
                max = AppConfig.WORD_GAP_LIST.size - 1,
                min = 0,
                initial = AppConfig.WORD_GAP_LIST.indexOfFirst { e -> e.first == cb.getWordGapDp() }
                    .coerceAtLeast(0),
                format = { i -> AppConfig.WORD_GAP_LIST[i].second }
            ) { p ->
                val newGap = AppConfig.WORD_GAP_LIST[p].first
                if (newGap != cb.getWordGapDp()) {
                    gapValueLabelRef?.text = AppConfig.WORD_GAP_LIST[p].second
                    cb.onWordGapChanged(newGap.toInt(), fontApplyAllCheckbox?.isChecked == true)
                }
            }
        }
        addView(gapChip)
        addView(android.widget.CheckBox(context).apply {
            text = context.getString(R.string.apply_all_label); textSize = 10f
            setTextColor(context.getColor(R.color.text_lighter))
            layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                it.setMargins((4 * dp).roundToInt(), 0, (8 * dp).roundToInt(), 0)
            }
            visibility = View.GONE
            setOnCheckedChangeListener { _, isChecked ->
                cb.onFontApplyAllToggled(isChecked)
            }
            fontApplyAllCheckbox = this
        })
    }

        private fun buildFontSpinner(dp: Float): Spinner {
            val catalogue = cb.provideFontCatalogue()
            val adapter = object : ArrayAdapter<FontEntry>(
                context, android.R.layout.simple_spinner_item, catalogue
            ) {
                override fun getView(pos: Int, cv: View?, parent: ViewGroup): View =
                    spinnerItemView(pos, compact = true, dp = dp)

                override fun getDropDownView(pos: Int, cv: View?, parent: ViewGroup): View =
                    spinnerItemView(pos, compact = false, dp = dp)
            }
            return Spinner(context).apply {
                this.adapter = adapter
                setSelection(cb.getFontIndex())
                layoutParams = LinearLayout.LayoutParams(MP, WC)
                background = null
                fontSpinnerRef = this
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                        cb.onFontSelected(pos, fontApplyAllCheckbox?.isChecked == true)
                    }

                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            }
        }

        private fun spinnerItemView(pos: Int, compact: Boolean, dp: Float): TextView {
            val entry = cb.provideFontCatalogue()[pos]
            return TextView(context).apply {
                text = entry.label
                typeface = entry.typeface
                textSize = if (compact) 14f else 16f
                setTextColor(context.getColor(R.color.text_darker))
                gravity = if (compact) Gravity.CENTER else Gravity.CENTER_VERTICAL
                val hPad = ((if (compact) 4 else 16) * dp).roundToInt()
                val vPad = ((if (compact) 2 else 12) * dp).roundToInt()
                setPadding(hPad, vPad, hPad, vPad)
                if (!compact) setBackgroundColor(context.getColor(R.color.surface))
            }
        }

        // ── Mode header row (輸入模式 section) ───────────────────────────────────
        private fun buildModeHeaderRow(dp: Float): LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MP, WC)
                val hPad = (12 * dp).roundToInt()
                val vPad = (10 * dp).roundToInt()
                setPadding(hPad, vPad, hPad, vPad)

                // Right section: Input Mode label + spacer + mode chips
                // Wrapped so updateModeChipVisibility() can hide it for non-VERTICAL sessions.
                val inputModeSection = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(MP, WC)

                    addView(TextView(context).apply {
                        text = context.getString(R.string.label_input_mode); textSize = 11f
                        setSingleLine(); maxLines = 1
                        setTextColor(context.getColor(R.color.text_lighter))
                        layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                            it.marginEnd = (8 * dp).roundToInt()
                        }
                    })
                    // Flex spacer: pushes chips to the trailing edge without forcing the label out
                    addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    })

                    val chips = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(WC, WC)
                    }
                    modeChipContainer = chips
                    listOf(InputMode.SCATTER to context.getString(R.string.mode_scatter),
                           InputMode.SEQUENTIAL to context.getString(R.string.mode_sequential))
                        .forEach { (mode, label) ->
                            chips.addView(TextView(context).apply {
                                text = label; textSize = 12f; gravity = Gravity.CENTER
                                setSingleLine(); maxLines = 1
                                tag = mode
                                setTextColor(context.getColor(R.color.text_dark))
                                background = GradientDrawable().apply {
                                    setColor(
                                        if (cb.getInputMode() == mode) context.getColor(R.color.chip_active)
                                        else Color.TRANSPARENT
                                    )
                                    setStroke((1f * dp).roundToInt(), context.getColor(R.color.stroke))
                                    cornerRadius = 20f * dp
                                }
                                val hP = (10 * dp).roundToInt()
                                val vP = (6 * dp).roundToInt()
                                setPadding(hP, vP, hP, vP)
                                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                                    it.marginEnd = (6 * dp).roundToInt()
                                }
                                setOnClickListener { cb.onModeSelected(mode) }
                            })
                        }
                    addView(chips)
                }
                modeInputSectionRef = inputModeSection
                addView(inputModeSection)
            }

        // ── More row ──────────────────────────────────────────────────────────
        private fun buildMoreRow(dp: Float): LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MP, WC)
                val hPad = (16 * dp).roundToInt(); val vPad = (10 * dp).roundToInt()
                setPadding(hPad, vPad, hPad, vPad)
                isClickable = true; isFocusable = true
                setOnClickListener { cb.onAboutClicked() }

                addView(TextView(context).apply {
                    text = context.getString(R.string.btn_more)
                    textSize = 14f
                    setTextColor(context.getColor(R.color.text_dark))
                    layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                })

                // Circle ⓘ icon
                val iconSz = (22 * dp).roundToInt()
                addView(TextView(context).apply {
                    text = "i"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(context.getColor(R.color.text_dark))
                    typeface = android.graphics.Typeface.create(
                        android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setStroke((1.5f * dp).roundToInt(), context.getColor(R.color.text_dark))
                        setColor(android.graphics.Color.TRANSPARENT)
                    }
                    layoutParams = LinearLayout.LayoutParams(iconSz, iconSz)
                })
            }

        // ── File header row (檔案 section) ────────────────────────────────────
        private fun buildFileHeaderRow(dp: Float): LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MP, WC)
                val hPad = (12 * dp).roundToInt();
                val vPad = (10 * dp).roundToInt()
                setPadding(hPad, vPad, hPad, vPad)

                addView(TextView(context).apply {
                    text = context.getString(R.string.label_file); textSize = 11f
                    setTextColor(context.getColor(R.color.text_lighter))
                    layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                        it.marginEnd = (8 * dp).roundToInt()
                    }
                })

                val nameTv = TextView(context).apply {
                    text = cb.getCurrentSessionName()
                    textSize = 14f
                    setTextColor(context.getColor(R.color.text_dark))
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                }
                docFileNameRef = nameTv
                addView(nameTv)

                addView(TextView(context).apply {
                    text = context.getString(R.string.btn_all_docs)+" →"; textSize = 12f; gravity = Gravity.CENTER
                    setTextColor(context.getColor(R.color.text_dark))
                    background = GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        setStroke((1f * dp).roundToInt(), context.getColor(R.color.stroke))
                        cornerRadius = 20f * dp
                    }
                    val hP = (12 * dp).roundToInt();
                    val vP = (5 * dp).roundToInt()
                    setPadding(hP, vP, hP, vP)
                    layoutParams = LinearLayout.LayoutParams(WC, WC)
                    setOnClickListener { cb.onShowAllSessions() }
                })
            }

        // ── Selection overlay views ───────────────────────────────────────────
        fun buildSelectionOptionsView(dp: Float): LinearLayout {
            val menuWidth = (90 * dp).roundToInt()

            fun btn(label: String, action: () -> Unit) = TextView(context).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.text_dark))
                val vP = (10 * dp).roundToInt()
                setPadding(0, vP, 0, vP)
                layoutParams = LinearLayout.LayoutParams(MP, WC)
                setOnClickListener { action() }
            }

            fun sep() = View(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams((90 * dp).roundToInt(), (1f * dp).roundToInt()).also {
                        it.gravity = Gravity.CENTER_HORIZONTAL
                    }
                setBackgroundColor(context.getColor(R.color.stroke))
            }

            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = FrameLayout.LayoutParams(menuWidth, WC)
                background = GradientDrawable().apply {
                    setColor(context.getColor(R.color.panel_bg))
                    setStroke((1f * dp).roundToInt(), context.getColor(R.color.stroke))
                    cornerRadius = 8f * dp
                }
                elevation = 16f * dp
                val pad = (6 * dp).roundToInt(); setPadding(0, pad, 0, pad)
                visibility = View.GONE
                addView(btn(context.getString(R.string.action_copy)) { cb.onCopySelection() })
                addView(sep())
                addView(btn(context.getString(R.string.action_cut)) { cb.onCutSelection() })
                addView(sep())
                addView(btn(context.getString(R.string.action_paste)) { cb.onPasteAtSelection() })
                addView(sep())
                addView(btn(context.getString(R.string.action_select_line)) { cb.onSelectEntireLine() })
                addView(sep())
                addView(btn(context.getString(R.string.action_select_paragraph)) { cb.onSelectEntireParagraph() })
                addView(sep())
                addView(btn(context.getString(R.string.action_select_all)) { cb.onSelectAll() })
            }
        }

        fun buildHandlePasteView(dp: Float): TextView =
            TextView(context).apply {
                text = context.getString(R.string.action_paste)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.text_dark))
                val hP = (14 * dp).roundToInt();
                val vP = (8 * dp).roundToInt()
                setPadding(hP, vP, hP, vP)
                layoutParams = FrameLayout.LayoutParams(WC, WC)
                background = GradientDrawable().apply {
                    setColor(context.getColor(R.color.panel_bg))
                    setStroke((1f * dp).roundToInt(), context.getColor(R.color.stroke))
                    cornerRadius = 8f * dp
                }
                elevation = 16f * dp
                visibility = View.GONE
                setOnClickListener { cb.onHandlePasteClicked() }
            }

        fun buildSelectionHandle(dp: Float): View {
            val sz = (28 * dp).roundToInt()
            return View(context).apply {
                layoutParams = FrameLayout.LayoutParams(sz, sz)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#2196F3"))
                }
                elevation = 20f * dp
                visibility = View.GONE
            }
        }

        // ── Shared helpers ────────────────────────────────────────────────────
        fun iconText(ch: String, size: Float) = TextView(context).apply {
            text = ch
            textSize = size
            setTextColor(context.getColor(R.color.text_dark))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(WC, WC)
        }

        fun divider(toolbarH: Int, dp: Float) = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (0.8f * dp).roundToInt(), toolbarH
            ).also {
                it.topMargin = (12 * dp).roundToInt()
                it.bottomMargin = (12 * dp).roundToInt()
            }
            setBackgroundColor(context.getColor(R.color.divider))
        }

        fun subDivider(dp: Float): View = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(MP, (1f * dp).roundToInt())
            setBackgroundColor(context.getColor(R.color.divider))
        }

        private fun swatchDrawable(color: Int, dp: Float, selected: Boolean) = GradientDrawable().apply {
            setColor(color)
            setStroke(
                if (selected) (3.6f * dp).roundToInt() else (1.5f * dp).roundToInt(),
                if (selected) Color.parseColor("#2196F3") else context.getColor(R.color.text_hint)
            )
            cornerRadius = 5f * dp
        }

        // ── Popup seekbar ─────────────────────────────────────────────────────
        private fun showPopupSeekbar(
            anchor: View, max: Int, initial: Int,
            format: (Int) -> String, min: Int = 0,
            onChange: (Int) -> Unit
        ) {
            val dp = context.resources.displayMetrics.density
            val popupWidth = (context.resources.displayMetrics.widthPixels * 0.82f).roundToInt()
            val clampedInitial = initial.coerceIn(min, max)

            val valueLabel = TextView(context).apply {
                text = format(clampedInitial); textSize = 13f; gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.text_dark))
                layoutParams = LinearLayout.LayoutParams((40 * dp).roundToInt(), WC)
            }
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(context.getColor(R.color.panel_bg))
                    setStroke((1f * dp).roundToInt(), context.getColor(R.color.stroke))
                    cornerRadius = 10f * dp
                }
                val pad = (12 * dp).roundToInt()
                setPadding(pad, pad, pad, pad)
                addView(SeekBar(context).apply {
                    this.max = max - min
                    progress = (clampedInitial - min).coerceAtLeast(0)
                    layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                            if (fromUser) {
                                val v = p + min; valueLabel.text = format(v); onChange(v)
                            }
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

    }