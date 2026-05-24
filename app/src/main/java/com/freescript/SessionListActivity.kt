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
    private lateinit var actionBar: LinearLayout
    private lateinit var actionBarLabel: TextView
    private lateinit var masterCheckbox: CheckBox
    private var allSessions = listOf<SessionManager.SessionMeta>()
    private var searchQuery = ""
    private var dateFilter = "all"
    private var activeSessionId: String? = null
    private var renamedCurrentSessionName: String? = null
    private val selectedIds = mutableSetOf<String>()
    /** null = top-level view (folders + root sessions); non-null = inside folder. */
    private var currentFolder: String? = null

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeSessionId = intent.getStringExtra("current_session_id")
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
                setOnClickListener { handleBack() }
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
                setOnClickListener { showNewMenu() }
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

        actionBar = buildActionBar(dp)
        actionBar.visibility = View.GONE

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MP, MP)
            setBackgroundColor(getColor(R.color.surface))
            addView(titleBar)
            addView(divider())
            addView(searchEdit)
            addView(buildFilterRow(dp))
            addView(actionBar)
            addView(divider())
            addView(scrollView)
        })

        applyFilters()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { handleBack() }

    private fun handleBack() {
        if (selectedIds.isNotEmpty()) {
            // Treat back as exit-from-selection so the list state isn't lost.
            clearSelection()
            return
        }
        if (currentFolder != null) {
            // Inside a folder: back arrow returns to the top-level (folder + root) view.
            currentFolder = null
            applyFilters()
            return
        }
        finishWithResult()
    }

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

    // ── Multi-select action bar ───────────────────────────────────────────

    private fun buildActionBar(dp: Float): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, (44 * dp).roundToInt())
            setBackgroundColor(getColor(R.color.panel_bg))
            setPadding((12 * dp).roundToInt(), 0, (12 * dp).roundToInt(), 0)
            masterCheckbox = CheckBox(this@SessionListActivity).apply {
                setOnClickListener {
                    if (isChecked) selectAllFiltered() else clearSelection()
                }
            }
            addView(masterCheckbox)
            actionBarLabel = TextView(this@SessionListActivity).apply {
                textSize = 13f
                setTextColor(getColor(R.color.text_dark))
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f).also {
                    it.marginStart = (8 * dp).roundToInt()
                }
            }
            addView(actionBarLabel)
            addView(TextView(this@SessionListActivity).apply {
                text = getString(R.string.btn_move)
                textSize = 13f
                setTextColor(getColor(R.color.text_dark))
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke((1f * dp).roundToInt(), getColor(R.color.stroke))
                    cornerRadius = 20f * dp
                }
                val hP = (14 * dp).roundToInt(); val vP = (5 * dp).roundToInt()
                setPadding(hP, vP, hP, vP)
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.marginEnd = (8 * dp).roundToInt()
                }
                setOnClickListener { showMoveToFolderDialog(selectedIds.toList()) }
            })
            addView(TextView(this@SessionListActivity).apply {
                text = getString(R.string.btn_delete)
                textSize = 13f
                setTextColor(Color.parseColor("#E53935"))
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke((1f * dp).roundToInt(), Color.parseColor("#E53935"))
                    cornerRadius = 20f * dp
                }
                val hP = (14 * dp).roundToInt(); val vP = (5 * dp).roundToInt()
                setPadding(hP, vP, hP, vP)
                layoutParams = LinearLayout.LayoutParams(WC, WC)
                setOnClickListener { showMultiDeleteDialog(selectedIds.toList()) }
            })
        }
    }

    private fun selectAllFiltered() {
        val filtered = filteredSessions()
        selectedIds.clear()
        filtered.forEach { selectedIds.add(it.id) }
        applyFilters()
    }

    private fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        applyFilters()
    }

    private fun updateActionBar() {
        if (selectedIds.isEmpty()) {
            actionBar.visibility = View.GONE
            return
        }
        actionBar.visibility = View.VISIBLE
        actionBarLabel.text = getString(R.string.selected_count, selectedIds.size)
        val filteredCount = filteredSessions().size
        masterCheckbox.setOnCheckedChangeListener(null)
        masterCheckbox.isChecked = filteredCount > 0 && selectedIds.size >= filteredCount
        masterCheckbox.setOnClickListener {
            if (masterCheckbox.isChecked) selectAllFiltered() else clearSelection()
        }
    }

    // ── Filtering & list ─────────────────────────────────────────────────

    private fun filteredSessions(): List<SessionManager.SessionMeta> {
        val now = System.currentTimeMillis()
        val weekMs  = 7L  * 24 * 60 * 60 * 1000
        val monthMs = 30L * 24 * 60 * 60 * 1000
        val folderScope = currentFolder
        return allSessions.filter { meta ->
            (searchQuery.isEmpty() || meta.name.contains(searchQuery, ignoreCase = true)) &&
            when (dateFilter) {
                "week"  -> now - meta.lastAccessed <= weekMs
                "month" -> now - meta.lastAccessed <= monthMs
                else    -> true
            } &&
            // Folder scoping: top-level view shows only root sessions (folders are listed
            // separately above); inside-folder view shows only sessions in that folder.
            when (folderScope) {
                null -> meta.folder.isEmpty()
                else -> meta.folder == folderScope
            }
        }
    }

    /** Folders to display in the current view. Top-level only shows folders that have at
     *  least one session matching the current mode/date/search filters, plus all empty
     *  folders (so newly-created empty folders remain visible). Inside-folder view shows
     *  no folders (1-level deep only). */
    private fun visibleFolders(): List<String> {
        if (currentFolder != null) return emptyList()
        val now = System.currentTimeMillis()
        val weekMs  = 7L  * 24 * 60 * 60 * 1000
        val monthMs = 30L * 24 * 60 * 60 * 1000
        val matching = allSessions.asSequence().filter { meta ->
            (searchQuery.isEmpty() || meta.name.contains(searchQuery, ignoreCase = true)) &&
            when (dateFilter) {
                "week"  -> now - meta.lastAccessed <= weekMs
                "month" -> now - meta.lastAccessed <= monthMs
                else    -> true
            }
        }.mapNotNull { it.folder.ifEmpty { null } }.toSet()
        // Include empty (sessionless) folders too so the user sees folders they just made.
        return (matching + SessionManager.listFolders(filesDir)).distinct().sorted()
    }

    private fun applyFilters() {
        rebuildList(filteredSessions(), visibleFolders())
        updateActionBar()
    }

    private fun rebuildList(
        sessions: List<SessionManager.SessionMeta>,
        folders: List<String>
    ) {
        listContainer.removeAllViews()
        val dp = resources.displayMetrics.density

        // Breadcrumb / current-folder header when drilled into a folder.
        currentFolder?.let { folder ->
            listContainer.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(getColor(R.color.panel_bg))
                setPadding((12 * dp).roundToInt(), (10 * dp).roundToInt(),
                           (12 * dp).roundToInt(), (10 * dp).roundToInt())
                layoutParams = LinearLayout.LayoutParams(MP, WC)
                setOnClickListener { handleBack() }
                addView(TextView(this@SessionListActivity).apply {
                    text = "←"; textSize = 18f
                    setTextColor(getColor(R.color.text_dark))
                    layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                        it.marginEnd = (8 * dp).roundToInt()
                    }
                })
                addView(TextView(this@SessionListActivity).apply {
                    text = "📁 $folder"; textSize = 14f
                    setTextColor(getColor(R.color.text_darkest))
                    layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                })
            })
        }

        // Folder rows: tap to drill in. Long-press deletes the folder + its sessions.
        folders.forEach { folder ->
            val folderRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MP, WC)
                setPadding((8 * dp).roundToInt(), (8 * dp).roundToInt(),
                           (8 * dp).roundToInt(), (8 * dp).roundToInt())
                setOnClickListener {
                    currentFolder = folder
                    selectedIds.clear()
                    applyFilters()
                }
            }
            folderRow.addView(TextView(this).apply {
                text = "📁"; textSize = 18f
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.marginStart = (8 * dp).roundToInt()
                    it.marginEnd = (12 * dp).roundToInt()
                }
            })
            folderRow.addView(TextView(this).apply {
                text = folder; textSize = 15f
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(getColor(R.color.text_darkest))
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            })
            // Item count for this folder (across all modes).
            val count = allSessions.count { it.folder == folder }
            if (count > 0) {
                folderRow.addView(TextView(this).apply {
                    text = "$count"
                    textSize = 12f
                    setTextColor(getColor(R.color.text_hint))
                    layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                        it.marginEnd = (4 * dp).roundToInt()
                    }
                })
            }
            folderRow.addView(TextView(this).apply {
                text = "✏"; textSize = 16f; gravity = Gravity.CENTER
                setTextColor(getColor(R.color.text_light))
                layoutParams = LinearLayout.LayoutParams((40 * dp).roundToInt(), (44 * dp).roundToInt())
                setOnClickListener { showRenameFolderDialog(folder) }
            })
            folderRow.addView(TextView(this).apply {
                text = "🗑"; textSize = 16f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#E53935"))
                layoutParams = LinearLayout.LayoutParams((40 * dp).roundToInt(), (44 * dp).roundToInt())
                setOnClickListener { showDeleteFolderDialog(folder) }
            })
            listContainer.addView(folderRow)
            listContainer.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(MP, (1f * dp).roundToInt()).also {
                    it.marginStart = (16 * dp).roundToInt()
                }
                setBackgroundColor(getColor(R.color.row_active))
            })
        }

        if (sessions.isEmpty() && folders.isEmpty()) {
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
            val isChecked = selectedIds.contains(meta.id)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MP, WC)
                setPadding((8 * dp).roundToInt(), (8 * dp).roundToInt(),
                           (8 * dp).roundToInt(), (8 * dp).roundToInt())
                setOnClickListener {
                    if (selectedIds.isNotEmpty()) {
                        toggleSelection(meta.id)
                    } else {
                        finishWithResult(meta.id, meta.canvasMode)
                    }
                }
                setOnLongClickListener {
                    toggleSelection(meta.id)
                    true
                }
            }

            row.addView(CheckBox(this).apply {
                this.isChecked = isChecked
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.marginEnd = (4 * dp).roundToInt()
                }
                setOnClickListener { toggleSelection(meta.id) }
            })

            val nameCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            }
            nameCol.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MP, WC)
                addView(TextView(this@SessionListActivity).apply {
                    text = when (meta.canvasMode) {
                        CanvasMode.HORIZONTAL -> "橫"
                        CanvasMode.FREESTYLE  -> "自"
                        else                  -> "直"
                    }
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.text_light))
                    background = GradientDrawable().apply {
                        setColor(getColor(R.color.chip_active))
                        cornerRadius = 3f * dp
                    }
                    val bPad = (4 * dp).roundToInt()
                    setPadding(bPad, bPad, bPad, bPad)
                    layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                        it.marginEnd = (8 * dp).roundToInt()
                    }
                })
                addView(TextView(this@SessionListActivity).apply {
                    text = if (isActive) "${meta.name}${getString(R.string.session_current_indicator)}" else meta.name
                    textSize = 15f
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(if (isActive) Color.parseColor("#3F51B5") else getColor(R.color.text_darkest))
                    layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                })
            })
            val infoParts = buildList {
                if (meta.folder.isNotEmpty()) add("📁 ${meta.folder}")
                if (meta.wordCount > 0) add("${meta.wordCount}${" " + getString(R.string.session_stat_chars)}")
                if (meta.imageCount > 0) add("${meta.imageCount}${" " + getString(R.string.session_stat_images)}")
            }
            if (infoParts.isNotEmpty()) {
                nameCol.addView(TextView(this).apply {
                    text = infoParts.joinToString(" · ")
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

    private fun toggleSelection(id: String) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        applyFilters()
    }

    // ── New menu (Folder / Document) ──────────────────────────────────────

    private fun showNewMenu() {
        val items = arrayOf(
            getString(R.string.new_kind_folder),
            getString(R.string.new_kind_document),
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_new_picker_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showNewFolderDialog()
                    1 -> showNewSessionDialog()
                }
            }
            .show()
    }

    private fun showNewFolderDialog() {
        val dp = resources.displayMetrics.density
        val baseName = SessionManager.nextNewFolderName(filesDir, getString(R.string.default_folder_name))
        val editText = EditText(this).apply {
            setText(baseName); setSingleLine(); selectAll()
            setPadding((16 * dp).roundToInt(), (12 * dp).roundToInt(),
                       (16 * dp).roundToInt(), (12 * dp).roundToInt())
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_new_folder_title))
            .setView(editText)
            .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                val name = editText.text.toString().trim().ifEmpty { baseName }
                SessionManager.createFolder(filesDir, name)
                // Folder list change doesn't affect session listing; nothing to refresh here
                // beyond surfacing the new option in the next "New Document" dialog.
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    // ── Create document dialog ────────────────────────────────────────────

    private fun showNewSessionDialog() {
        val dp = resources.displayMetrics.density
        val defaultName = SessionManager.nextNewSessionName(filesDir, getString(R.string.default_session_name))
        var selectedMode = CanvasMode.VERTICAL
        var selectedFolder = currentFolder ?: ""  // "" = root

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

        val folderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).roundToInt(), 0, (16 * dp).roundToInt(), (12 * dp).roundToInt())
        }
        val folderLabel = TextView(this).apply {
            val initialDisplay = if (selectedFolder.isEmpty()) getString(R.string.folder_root) else selectedFolder
            text = "${getString(R.string.label_folder)}: $initialDisplay"
            textSize = 13f
            setTextColor(getColor(R.color.text_dark))
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            setOnClickListener {
                pickFolder(includeRoot = true) { picked ->
                    selectedFolder = picked
                    val display = if (picked.isEmpty()) getString(R.string.folder_root) else picked
                    text = "${getString(R.string.label_folder)}: $display"
                }
            }
        }
        folderRow.addView(folderLabel)

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(editText)
            addView(chipRow)
            addView(folderRow)
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
                    ),
                    folder = selectedFolder
                )
                finishWithResult(newId, selectedMode)
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    // ── Folder picker ─────────────────────────────────────────────────────

    /** Shows a folder picker; calls [onPicked] with the selected folder path (empty = root). */
    private fun pickFolder(includeRoot: Boolean, onPicked: (String) -> Unit) {
        val folders = SessionManager.listFolders(filesDir).sorted()
        val labels = mutableListOf<String>()
        val values = mutableListOf<String>()
        if (includeRoot) {
            labels.add(getString(R.string.folder_root))
            values.add("")
        }
        folders.forEach { labels.add(it); values.add(it) }
        if (labels.isEmpty()) {
            // Fall back to creating a folder inline if there are none and we hid root.
            showNewFolderDialog()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_move_title))
            .setItems(labels.toTypedArray()) { _, which -> onPicked(values[which]) }
            .show()
    }

    // ── Folder actions ────────────────────────────────────────────────────

    private fun showDeleteFolderDialog(folder: String) {
        val sessionsInFolder = allSessions.filter { it.folder == folder }
        val msg = if (sessionsInFolder.isEmpty()) {
            getString(R.string.dialog_delete_folder_empty_message, folder)
        } else {
            val header = getString(R.string.dialog_delete_folder_message, folder, sessionsInFolder.size)
            val list = sessionsInFolder.joinToString("\n") { "• ${it.name}" }
            "$header\n\n$list"
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_delete_folder_title))
            .setMessage(msg)
            .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                SessionManager.deleteFolder(filesDir, folder)
                if (currentFolder == folder) currentFolder = null
                allSessions = SessionManager.listSessions(filesDir)
                selectedIds.clear()
                applyFilters()
                Toast.makeText(this, getString(R.string.toast_session_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun showRenameFolderDialog(folder: String) {
        val dp = resources.displayMetrics.density
        val editText = EditText(this).apply {
            setText(folder); setSingleLine(); selectAll()
            setPadding((16 * dp).roundToInt(), (12 * dp).roundToInt(),
                       (16 * dp).roundToInt(), (12 * dp).roundToInt())
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_rename_folder_title))
            .setView(editText)
            .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isEmpty() || newName == folder) return@setPositiveButton
                val ok = SessionManager.renameFolder(filesDir, folder, newName)
                if (!ok) {
                    Toast.makeText(this, getString(R.string.toast_folder_rename_failed), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (currentFolder == folder) currentFolder = newName
                allSessions = SessionManager.listSessions(filesDir)
                applyFilters()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    // ── Multi-select actions ──────────────────────────────────────────────

    private fun showMultiDeleteDialog(ids: List<String>) {
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_delete_multi_title))
            .setMessage(getString(R.string.dialog_delete_multi_message, ids.size))
            .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                ids.forEach { SessionManager.deleteSession(filesDir, it) }
                selectedIds.clear()
                allSessions = SessionManager.listSessions(filesDir)
                applyFilters()
                Toast.makeText(this, getString(R.string.toast_session_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }

    private fun showMoveToFolderDialog(ids: List<String>) {
        if (ids.isEmpty()) return
        pickFolder(includeRoot = true) { target ->
            ids.forEach { SessionManager.moveSession(filesDir, it, target) }
            selectedIds.clear()
            allSessions = SessionManager.listSessions(filesDir)
            applyFilters()
            Toast.makeText(this, getString(R.string.toast_moved), Toast.LENGTH_SHORT).show()
        }
    }

    // ── Rename / Delete dialogs (single-row) ──────────────────────────────

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
                selectedIds.remove(meta.id)
                allSessions = SessionManager.listSessions(filesDir)
                applyFilters()
                Toast.makeText(this, getString(R.string.toast_session_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show()
    }
}
