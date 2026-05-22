package com.freescript

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import kotlin.math.roundToInt

class SessionListActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private var allSessions = listOf<SessionManager.SessionMeta>()
    private var searchQuery = ""
    private var dateFilter = "all"
    private var modeFilter = CanvasMode.VERTICAL
    private var activeSessionId: String? = null
    private var renamedCurrentSessionName: String? = null

    private val modeTabViews = mutableListOf<Triple<CanvasMode, TextView, View>>()

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeSessionId = intent.getStringExtra("current_session_id")
        modeFilter = try {
            CanvasMode.valueOf(intent.getStringExtra("current_canvas_mode") ?: "VERTICAL")
        } catch (_: Exception) { CanvasMode.VERTICAL }
        allSessions = SessionManager.listSessions(filesDir)
        val dp = resources.displayMetrics.density

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, (56 * dp).roundToInt())
            setBackgroundColor(getColor(R.color.surface))
            setPadding((4 * dp).roundToInt(), 0, (16 * dp).roundToInt(), 0)
            addView(TextView(this@SessionListActivity).apply {
                text = "←"
                textSize = 22f
                setTextColor(getColor(R.color.text_dark))
                setPadding((12 * dp).roundToInt(), 0, (16 * dp).roundToInt(), 0)
                layoutParams = LinearLayout.LayoutParams(WC, WC)
                setOnClickListener { finishWithResult() }
            })
            addView(TextView(this@SessionListActivity).apply {
                text = getString(R.string.session_list_title)
                textSize = 17f
                setTextColor(getColor(R.color.text_darkest))
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            })
            addView(TextView(this@SessionListActivity).apply {
                text = getString(R.string.btn_new_session)
                textSize = 13f
                setTextColor(getColor(R.color.text_dark))
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke((1f * dp).roundToInt(), getColor(R.color.stroke))
                    cornerRadius = 20f * dp
                }
                val hP = (14 * dp).roundToInt(); val vP = (5 * dp).roundToInt()
                setPadding(hP, vP, hP, vP)
                layoutParams = LinearLayout.LayoutParams(WC, WC)
                setOnClickListener { createNewSession() }
            })
        }

        val searchEdit = EditText(this).apply {
            hint = getString(R.string.hint_search_sessions)
            setSingleLine()
            textSize = 14f
            setTextColor(getColor(R.color.text_darker))
            setHintTextColor(getColor(R.color.text_hint))
            background = GradientDrawable().apply {
                setColor(getColor(R.color.panel_bg))
                cornerRadius = 8f * dp
            }
            val hPad = (12 * dp).roundToInt(); val vPad = (8 * dp).roundToInt()
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(MP, WC).also {
                it.marginStart = (12 * dp).roundToInt()
                it.marginEnd   = (12 * dp).roundToInt()
                it.topMargin   = (10 * dp).roundToInt()
                it.bottomMargin = (6 * dp).roundToInt()
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString() ?: ""; applyFilters()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        val scrollView = NestedScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MP, 0, 1f)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(listContainer)
        }

        fun divider() = View(this@SessionListActivity).apply {
            layoutParams = LinearLayout.LayoutParams(MP, (1f * dp).roundToInt())
            setBackgroundColor(getColor(R.color.divider))
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MP, MP)
            setBackgroundColor(getColor(R.color.surface))
            addView(titleBar)
            addView(divider())
            addView(buildModeTabRow(dp))
            addView(divider())
            addView(searchEdit)
            addView(buildFilterRow(dp))
            addView(divider())
            addView(scrollView)
        })

        updateModeTabs()
        applyFilters()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { finishWithResult() }

    private fun finishWithResult(openSessionId: String? = null, canvasMode: CanvasMode? = null) {
        val intent = Intent()
        if (openSessionId != null) intent.putExtra("session_id", openSessionId)
        if (canvasMode != null)    intent.putExtra("canvas_mode", canvasMode.name)
        val renamed = renamedCurrentSessionName
        if (renamed != null) intent.putExtra("renamed_current_name", renamed)
        setResult(
            if (openSessionId != null || renamed != null) RESULT_OK else RESULT_CANCELED,
            intent
        )
        finish()
    }

    // ── Mode tabs ─────────────────────────────────────────────────────────

    private fun buildModeTabRow(dp: Float): LinearLayout {
        val tabs = listOf(
            CanvasMode.VERTICAL   to getString(R.string.entry_mode_vertical),
            CanvasMode.HORIZONTAL to getString(R.string.entry_mode_horizontal),
            CanvasMode.FREESTYLE  to getString(R.string.entry_mode_freestyle),
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MP, (44 * dp).roundToInt())
            setBackgroundColor(getColor(R.color.surface))
            tabs.forEach { (mode, label) ->
                val underline = View(this@SessionListActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(MP, (2 * dp).roundToInt())
                }
                val tv = TextView(this@SessionListActivity).apply {
                    text = label
                    textSize = 14f
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(MP, 0, 1f)
                }
                modeTabViews.add(Triple(mode, tv, underline))
                addView(LinearLayout(this@SessionListActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, MP, 1f)
                    setOnClickListener {
                        modeFilter = mode
                        updateModeTabs()
                        applyFilters()
                    }
                    addView(tv)
                    addView(underline)
                })
            }
        }
    }

    private fun updateModeTabs() {
        modeTabViews.forEach { (mode, tv, underline) ->
            val active = mode == modeFilter
            tv.setTextColor(if (active) getColor(R.color.text_darkest) else getColor(R.color.text_hint))
            underline.setBackgroundColor(
                if (active) getColor(R.color.text_dark) else Color.TRANSPARENT)
        }
    }

    // ── Date filter row ───────────────────────────────────────────────────

    private fun buildFilterRow(dp: Float): LinearLayout {
        val filters = listOf(
            getString(R.string.filter_all)        to "all",
            getString(R.string.filter_this_week)  to "week",
            getString(R.string.filter_this_month) to "month"
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            setBackgroundColor(getColor(R.color.surface))
            setPadding((12 * dp).roundToInt(), (6 * dp).roundToInt(),
                       (12 * dp).roundToInt(), (6 * dp).roundToInt())
            filters.forEach { (label, key) ->
                addView(TextView(this@SessionListActivity).apply {
                    text = label; textSize = 12f; gravity = Gravity.CENTER; tag = key
                    val active = dateFilter == key
                    setTextColor(if (active) getColor(R.color.surface) else getColor(R.color.text_medium))
                    background = GradientDrawable().apply {
                        setColor(if (active) getColor(R.color.text_medium) else Color.TRANSPARENT)
                        setStroke((1f * dp).roundToInt(), getColor(R.color.stroke))
                        cornerRadius = 20f * dp
                    }
                    val hP = (12 * dp).roundToInt(); val vP = (4 * dp).roundToInt()
                    setPadding(hP, vP, hP, vP)
                    layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                        it.marginEnd = (8 * dp).roundToInt()
                    }
                    setOnClickListener {
                        dateFilter = key
                        val parent = this.parent as? LinearLayout ?: return@setOnClickListener
                        for (i in 0 until parent.childCount) {
                            val chip = parent.getChildAt(i) as? TextView ?: continue
                            val isActive = chip.tag == dateFilter
                            chip.setTextColor(if (isActive) getColor(R.color.surface) else getColor(R.color.text_medium))
                            (chip.background as? GradientDrawable)?.setColor(
                                if (isActive) getColor(R.color.text_medium) else Color.TRANSPARENT)
                        }
                        applyFilters()
                    }
                })
            }
        }
    }

    // ── Filtering & list ─────────────────────────────────────────────────

    private fun applyFilters() {
        val now = System.currentTimeMillis()
        val weekMs  = 7L  * 24 * 60 * 60 * 1000
        val monthMs = 30L * 24 * 60 * 60 * 1000
        val filtered = allSessions.filter { meta ->
            meta.canvasMode == modeFilter &&
            (searchQuery.isEmpty() || meta.name.contains(searchQuery, ignoreCase = true)) &&
            when (dateFilter) {
                "week"  -> now - meta.lastAccessed <= weekMs
                "month" -> now - meta.lastAccessed <= monthMs
                else    -> true
            }
        }
        rebuildList(filtered)
    }

    private fun rebuildList(sessions: List<SessionManager.SessionMeta>) {
        listContainer.removeAllViews()
        val dp = resources.displayMetrics.density
        if (sessions.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = getString(R.string.session_empty_message)
                textSize = 14f; setTextColor(getColor(R.color.text_hint))
                gravity = Gravity.CENTER
                setPadding(0, (48 * dp).roundToInt(), 0, 0)
                layoutParams = LinearLayout.LayoutParams(MP, WC)
            })
            return
        }
        sessions.forEach { meta ->
            val isActive = meta.id == activeSessionId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MP, WC)
                setPadding((16 * dp).roundToInt(), (8 * dp).roundToInt(),
                           (8 * dp).roundToInt(),  (8 * dp).roundToInt())
                setOnClickListener { finishWithResult(meta.id, meta.canvasMode) }
            }

            val nameCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            }
            nameCol.addView(TextView(this).apply {
                text = if (isActive) "${meta.name}${getString(R.string.session_current_indicator)}" else meta.name
                textSize = 15f
                setTextColor(if (isActive) Color.parseColor("#3F51B5") else getColor(R.color.text_darkest))
                layoutParams = LinearLayout.LayoutParams(MP, WC)
            })
            val statParts = buildList {
                if (meta.wordCount > 0) add("${meta.wordCount}${" " + getString(R.string.session_stat_chars)}")
                if (meta.imageCount > 0) add("${meta.imageCount}${" " + getString(R.string.session_stat_images)}")
            }
            if (statParts.isNotEmpty()) {
                nameCol.addView(TextView(this).apply {
                    text = statParts.joinToString(" · ")
                    textSize = 11f
                    setTextColor(getColor(R.color.text_hint))
                    layoutParams = LinearLayout.LayoutParams(MP, WC).also {
                        it.topMargin = (2 * dp).roundToInt()
                    }
                })
            }
            row.addView(nameCol)
            row.addView(TextView(this).apply {
                text = meta.formattedDate(); textSize = 12f
                setTextColor(getColor(R.color.text_hint))
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.marginEnd = (4 * dp).roundToInt()
                }
            })
            row.addView(TextView(this).apply {
                text = "✏"; textSize = 16f; gravity = Gravity.CENTER
                setTextColor(getColor(R.color.text_light))
                layoutParams = LinearLayout.LayoutParams((40 * dp).roundToInt(), (44 * dp).roundToInt())
                setOnClickListener { showRenameDialog(meta) }
            })
            row.addView(TextView(this).apply {
                text = "🗑"; textSize = 16f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#E53935"))
                layoutParams = LinearLayout.LayoutParams((40 * dp).roundToInt(), (44 * dp).roundToInt())
                setOnClickListener { showDeleteConfirmDialog(meta) }
            })

            listContainer.addView(row)
            listContainer.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(MP, (1f * dp).roundToInt()).also {
                    it.marginStart = (16 * dp).roundToInt()
                }
                setBackgroundColor(getColor(R.color.row_active))
            })
        }
    }

    // ── Create dialog ─────────────────────────────────────────────────────

    private fun createNewSession() {
        val dp = resources.displayMetrics.density
        val defaultName = SessionManager.nextNewSessionName(filesDir, getString(R.string.default_session_name))
        var selectedMode = modeFilter

        val editText = EditText(this).apply {
            setText(defaultName); setSingleLine(); selectAll()
            setPadding((16 * dp).roundToInt(), (12 * dp).roundToInt(),
                       (16 * dp).roundToInt(), (8 * dp).roundToInt())
        }

        val modeOptions = listOf(
            CanvasMode.VERTICAL   to getString(R.string.entry_mode_vertical),
            CanvasMode.HORIZONTAL to getString(R.string.entry_mode_horizontal),
            CanvasMode.FREESTYLE  to getString(R.string.entry_mode_freestyle),
        )
        val chipRefs = mutableListOf<Pair<CanvasMode, TextView>>()

        fun updateChips() {
            chipRefs.forEach { (mode, chip) ->
                val active = mode == selectedMode
                chip.setTextColor(if (active) getColor(R.color.surface) else getColor(R.color.text_dark))
                (chip.background as? GradientDrawable)?.setColor(
                    if (active) getColor(R.color.text_dark) else Color.TRANSPARENT)
            }
        }

        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).roundToInt(), (4 * dp).roundToInt(),
                       (16 * dp).roundToInt(), (12 * dp).roundToInt())
            modeOptions.forEach { (mode, label) ->
                val chip = TextView(this@SessionListActivity).apply {
                    text = label; textSize = 13f; gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        setStroke((1f * dp).roundToInt(), getColor(R.color.stroke))
                        cornerRadius = 20f * dp
                    }
                    val hP = (12 * dp).roundToInt(); val vP = (5 * dp).roundToInt()
                    setPadding(hP, vP, hP, vP)
                    layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                        it.marginEnd = (8 * dp).roundToInt()
                    }
                    setOnClickListener { selectedMode = mode; updateChips() }
                }
                chipRefs.add(mode to chip)
                addView(chip)
            }
        }

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(editText)
            addView(chipRow)
        }

        updateChips()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_new_session_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                val name = editText.text.toString().trim().ifEmpty { defaultName }
                val newId = java.util.UUID.randomUUID().toString()
                SessionManager.saveSession(
                    filesDir,
                    SessionDocument(
                        id = newId,
                        name = name,
                        canvasMode = selectedMode.name,
                        columnData = emptyList(),
                        columnBreaks = emptySet(),
                        fontIndex = 0,
                        fontSizeSp = 24f,
                        wordGapDp = 0f,
                        gridTextColor = android.graphics.Color.BLACK,
                        bgColor = android.graphics.Color.WHITE,
                        bgImageUri = null,
                        bgImageMatrix = null,
                        inputMode = "SEQUENTIAL",
                        insertedImages = emptyList(),
                        activeImageIndex = -1,
                        gridPadTop = 0,
                        gridPadBottom = 0,
                        gridPadLeft = 0,
                        gridPadRight = 0
                    )
                )
                finishWithResult(newId, selectedMode)
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    // ── Rename / Delete dialogs ───────────────────────────────────────────

    private fun showRenameDialog(meta: SessionManager.SessionMeta) {
        val dp = resources.displayMetrics.density
        val editText = EditText(this).apply {
            setText(meta.name); setSingleLine(); selectAll()
            setPadding((16 * dp).roundToInt(), (12 * dp).roundToInt(),
                       (16 * dp).roundToInt(), (12 * dp).roundToInt())
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_rename_title))
            .setView(editText)
            .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                val newName = editText.text.toString().trim().ifEmpty { meta.name }
                SessionManager.renameSession(filesDir, meta.id, newName)
                if (meta.id == activeSessionId) renamedCurrentSessionName = newName
                allSessions = SessionManager.listSessions(filesDir)
                applyFilters()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun showDeleteConfirmDialog(meta: SessionManager.SessionMeta) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_delete_title))
            .setMessage(getString(R.string.dialog_delete_message, meta.name))
            .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                SessionManager.deleteSession(filesDir, meta.id)
                allSessions = SessionManager.listSessions(filesDir)
                applyFilters()
                Toast.makeText(this, getString(R.string.toast_session_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }
}
