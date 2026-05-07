package com.poemeditor

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

    private data class SessionMeta(val id: String, val name: String, val lastAccessed: Long) {
        fun formattedDate(): String {
            val cal = java.util.Calendar.getInstance().also { it.timeInMillis = lastAccessed }
            val now = java.util.Calendar.getInstance()
            val sameDay = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
            val sameYear = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
            return when {
                sameDay  -> "今天"
                sameYear -> "${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
                else     -> "${cal.get(java.util.Calendar.YEAR)}/${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
            }
        }
    }

    private val sessionsDir get() = java.io.File(filesDir, "poems").also { it.mkdirs() }
    private lateinit var listContainer: LinearLayout
    private var allSessions = listOf<SessionMeta>()
    private var searchQuery = ""
    private var dateFilter = "all"

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        allSessions = loadAllSessions()
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
                setTextColor(Color.parseColor("#333333"))
                setPadding((12 * dp).roundToInt(), 0, (16 * dp).roundToInt(), 0)
                layoutParams = LinearLayout.LayoutParams(WC, WC)
                setOnClickListener { finish() }
            })
            addView(TextView(this@SessionListActivity).apply {
                text = "所有文檔"
                textSize = 17f
                setTextColor(Color.parseColor("#111111"))
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            })
            addView(TextView(this@SessionListActivity).apply {
                text = "${allSessions.size} 篇"
                textSize = 12f
                setTextColor(Color.parseColor("#AAAAAA"))
                layoutParams = LinearLayout.LayoutParams(WC, WC)
            })
        }

        val searchEdit = EditText(this).apply {
            hint = "搜尋文檔名稱"
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

        val filterRow = buildFilterRow(dp)

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
            addView(filterRow)
            addView(View(this@SessionListActivity).apply {
                layoutParams = LinearLayout.LayoutParams(MP, (1f * dp).roundToInt())
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            })
            addView(scrollView)
        })

        applyFilters()
    }

    private fun buildFilterRow(dp: Float): LinearLayout {
        val filters = listOf("全部" to "all", "本週" to "week", "本月" to "month")
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

    private fun rebuildList(sessions: List<SessionMeta>) {
        listContainer.removeAllViews()
        val dp = resources.displayMetrics.density
        if (sessions.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "沒有符合的文檔"
                textSize = 14f; setTextColor(Color.parseColor("#AAAAAA"))
                gravity = Gravity.CENTER
                setPadding(0, (48 * dp).roundToInt(), 0, 0)
                layoutParams = LinearLayout.LayoutParams(MP, WC)
            })
            return
        }
        sessions.forEach { meta ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MP, (52 * dp).roundToInt())
                setPadding((16 * dp).roundToInt(), 0, (16 * dp).roundToInt(), 0)
                setOnClickListener {
                    setResult(RESULT_OK, Intent().putExtra("session_id", meta.id))
                    finish()
                }
            }
            row.addView(TextView(this).apply {
                text = meta.name; textSize = 15f
                setTextColor(Color.parseColor("#111111"))
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            })
            row.addView(TextView(this).apply {
                text = meta.formattedDate(); textSize = 12f
                setTextColor(Color.parseColor("#AAAAAA"))
                layoutParams = LinearLayout.LayoutParams(WC, WC)
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

    private fun loadAllSessions(): List<SessionMeta> =
        (sessionsDir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { file ->
                try {
                    val j = org.json.JSONObject(file.readText())
                    SessionMeta(j.getString("id"), j.getString("name"), j.getLong("lastAccessed"))
                } catch (_: Exception) { null }
            }
            .sortedByDescending { it.lastAccessed }
}
