package com.poemeditor

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
    private var activeSessionId: String? = null
    private var renamedCurrentSessionName: String? = null

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

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
            setBackgroundColor(Color.WHITE)
            setPadding((4 * dp).roundToInt(), 0, (16 * dp).roundToInt(), 0)
            addView(TextView(this@SessionListActivity).apply {
                text = "←"
                textSize = 22f
                setTextColor(Color.parseColor("#333333"))  // symbol, not localised
                setPadding((12 * dp).roundToInt(), 0, (16 * dp).roundToInt(), 0)
                layoutParams = LinearLayout.LayoutParams(WC, WC)
                setOnClickListener { finishWithResult() }
            })
            addView(TextView(this@SessionListActivity).apply {
                text = getString(R.string.session_list_title)
                textSize = 17f
                setTextColor(Color.parseColor("#111111"))
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            })
            addView(TextView(this@SessionListActivity).apply {
                text = getString(R.string.btn_new_session)
                textSize = 13f
                setTextColor(Color.parseColor("#333333"))
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke((1f * dp).roundToInt(), Color.parseColor("#CCCCCC"))
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
            setTextColor(Color.parseColor("#222222"))
            setHintTextColor(Color.parseColor("#BBBBBB"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F5F5F5"))
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

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MP, MP)
            setBackgroundColor(Color.WHITE)
            addView(titleBar)
            addView(View(this@SessionListActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MP, (1f * dp).roundToInt())
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            })
            addView(searchEdit)
            addView(buildFilterRow(dp))
            addView(View(this@SessionListActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MP, (1f * dp).roundToInt())
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            })
            addView(scrollView)
        })

        applyFilters()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { finishWithResult() }

    private fun finishWithResult(openSessionId: String? = null) {
        val intent = Intent()
        if (openSessionId != null) intent.putExtra("session_id", openSessionId)
        val renamed = renamedCurrentSessionName
        if (renamed != null) intent.putExtra("renamed_current_name", renamed)
        setResult(
            if (openSessionId != null || renamed != null) RESULT_OK else RESULT_CANCELED,
            intent
        )
        finish()
    }

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
            setBackgroundColor(Color.WHITE)
            setPadding((12 * dp).roundToInt(), (6 * dp).roundToInt(),
                       (12 * dp).roundToInt(), (6 * dp).roundToInt())
            filters.forEach { (label, key) ->
                addView(TextView(this@SessionListActivity).apply {
                    text = label; textSize = 12f; gravity = Gravity.CENTER; tag = key
                    val active = dateFilter == key
                    setTextColor(if (active) Color.WHITE else Color.parseColor("#555555"))
                    background = GradientDrawable().apply {
                        setColor(if (active) Color.parseColor("#555555") else Color.TRANSPARENT)
                        setStroke((1f * dp).roundToInt(), Color.parseColor("#CCCCCC"))
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
                            chip.setTextColor(if (isActive) Color.WHITE else Color.parseColor("#555555"))
                            (chip.background as? GradientDrawable)?.setColor(
                                if (isActive) Color.parseColor("#555555") else Color.TRANSPARENT)
                        }
                        applyFilters()
                    }
                })
            }
        }
    }

    private fun applyFilters() {
        val now = System.currentTimeMillis()
        val weekMs  = 7L  * 24 * 60 * 60 * 1000
        val monthMs = 30L * 24 * 60 * 60 * 1000
        val filtered = allSessions.filter { meta ->
            val nameMatch = searchQuery.isEmpty() || meta.name.contains(searchQuery, ignoreCase = true)
            val dateMatch = when (dateFilter) {
                "week"  -> now - meta.lastAccessed <= weekMs
                "month" -> now - meta.lastAccessed <= monthMs
                else    -> true
            }
            nameMatch && dateMatch
        }
        rebuildList(filtered)
    }

    private fun rebuildList(sessions: List<SessionManager.SessionMeta>) {
        listContainer.removeAllViews()
        val dp = resources.displayMetrics.density
        if (sessions.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = getString(R.string.session_empty_message)
                textSize = 14f; setTextColor(Color.parseColor("#AAAAAA"))
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
                setOnClickListener { finishWithResult(meta.id) }
            }

            val nameCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            }
            nameCol.addView(TextView(this).apply {
                text = if (isActive) "${meta.name}${getString(R.string.session_current_indicator)}" else meta.name
                textSize = 15f
                setTextColor(if (isActive) Color.parseColor("#3F51B5") else Color.parseColor("#111111"))
                layoutParams = LinearLayout.LayoutParams(MP, WC)
            })
            val statParts = buildList {
                if (meta.wordCount > 0) add("${meta.wordCount}${getString(R.string.session_stat_chars)}")
                if (meta.imageCount > 0) add("${meta.imageCount}${getString(R.string.session_stat_images)}")
            }
            if (statParts.isNotEmpty()) {
                nameCol.addView(TextView(this).apply {
                    text = statParts.joinToString(" · ")
                    textSize = 11f
                    setTextColor(Color.parseColor("#AAAAAA"))
                    layoutParams = LinearLayout.LayoutParams(MP, WC).also {
                        it.topMargin = (2 * dp).roundToInt()
                    }
                })
            }
            row.addView(nameCol)
            row.addView(TextView(this).apply {
                text = meta.formattedDate(); textSize = 12f
                setTextColor(Color.parseColor("#AAAAAA"))
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.marginEnd = (4 * dp).roundToInt()
                }
            })
            row.addView(TextView(this).apply {
                text = "✏"; textSize = 16f; gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#888888"))
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
                setBackgroundColor(Color.parseColor("#F0F0F0"))
            })
        }
    }

    private fun createNewSession() {
        val dp = resources.displayMetrics.density
        val defaultName = SessionManager.nextNewSessionName(filesDir, getString(R.string.default_session_name))
        val editText = EditText(this).apply {
            setText(defaultName); setSingleLine(); selectAll()
            setPadding((16 * dp).roundToInt(), (12 * dp).roundToInt(),
                       (16 * dp).roundToInt(), (12 * dp).roundToInt())
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_new_session_title))
            .setView(editText)
            .setPositiveButton(getString(R.string.dialog_ok)) { _, _ ->
                val name = editText.text.toString().trim().ifEmpty { defaultName }
                val newId = java.util.UUID.randomUUID().toString()
                SessionManager.saveSession(
                    filesDir = filesDir, id = newId, name = name,
                    columnData = emptyList(), columnBreaks = emptySet(),
                    fontIndex = 0, fontSizeSp = 24f, wordGapDp = 3f,
                    gridTextColor = android.graphics.Color.BLACK,
                    bgColor = android.graphics.Color.WHITE,
                    bgImageUri = null, bgImageMatrixValues = null,
                    inputMode = "SEQUENTIAL"
                )
                finishWithResult(newId)
            }
            .setNegativeButton(getString(R.string.dialog_cancel), null)
            .show().also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.BLACK)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.BLACK)
            }
    }

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
            .show().also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.BLACK)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.BLACK)
            }
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
            .show().also { dialog ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.BLACK)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.BLACK)
            }
    }
}
