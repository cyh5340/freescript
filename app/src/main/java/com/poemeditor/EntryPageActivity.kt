package com.poemeditor

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import kotlin.math.roundToInt

class EntryPageActivity : AppCompatActivity() {

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var recentSessionsContainer: LinearLayout
    private lateinit var sessionListLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        sessionListLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val sessionId  = data.getStringExtra("session_id")
                val canvasMode = data.getStringExtra("canvas_mode")
                val intent = Intent(this, MainActivity::class.java).apply {
                    if (sessionId  != null) putExtra("SESSION_ID",  sessionId)
                    if (canvasMode != null) putExtra("CANVAS_MODE", canvasMode)
                }
                startActivity(intent)
            }
        }

        val dp = resources.displayMetrics.density

        recentSessionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            val hPad = (16 * dp).roundToInt()
            setPadding(hPad, 0, hPad, (32 * dp).roundToInt())
        }

        content.addView(buildHeader(dp))
        content.addView(buildNewDocSection(dp))
        content.addView(buildDivider(dp))
        content.addView(buildRecentSection(dp))

        val scroll = NestedScrollView(this).apply {
            setBackgroundColor(getColor(R.color.surface))
            layoutParams = ViewGroup.LayoutParams(MP, MP)
            addView(content)
        }

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        populateRecentSessions(resources.displayMetrics.density)
    }

    // ── Header ────────────────────────────────────────────────────────────

    private fun buildHeader(dp: Float): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MP, WC).also {
                it.topMargin    = (56 * dp).roundToInt()
                it.bottomMargin = (36 * dp).roundToInt()
            }
            addView(TextView(this@EntryPageActivity).apply {
                text      = getString(R.string.app_name)
                textSize  = 44f
                gravity   = Gravity.CENTER
                typeface  = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.text_darkest))
                layoutParams = LinearLayout.LayoutParams(MP, WC)
            })
        }

    // ── New document section (3 mode cards) ───────────────────────────────

    private fun buildNewDocSection(dp: Float): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)

            addView(sectionLabel(getString(R.string.entry_new_doc), dp))

            addView(LinearLayout(this@EntryPageActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MP, WC).also {
                    it.topMargin = (10 * dp).roundToInt()
                }
                addView(buildModeCard(CanvasMode.VERTICAL,
                    "直",
                    getString(R.string.entry_mode_vertical),
                    getString(R.string.entry_mode_vertical_desc), dp))
                addView(cardGap(dp))
                addView(buildModeCard(CanvasMode.HORIZONTAL,
                    "橫",
                    getString(R.string.entry_mode_horizontal),
                    getString(R.string.entry_mode_horizontal_desc), dp))
                addView(cardGap(dp))
                addView(buildModeCard(CanvasMode.FREESTYLE,
                    "自",
                    getString(R.string.entry_mode_freestyle),
                    getString(R.string.entry_mode_freestyle_desc), dp))
            })
        }

    private fun buildModeCard(
        mode: CanvasMode, icon: String, name: String, desc: String, dp: Float
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            val pad = (14 * dp).roundToInt()
            setPadding(pad, (18 * dp).roundToInt(), pad, (18 * dp).roundToInt())
            background = GradientDrawable().apply {
                setColor(getColor(R.color.surface))
                setStroke((1f * dp).roundToInt(), getColor(R.color.divider))
                cornerRadius = 12f * dp
            }

            addView(TextView(this@EntryPageActivity).apply {
                text      = icon
                textSize  = 32f
                gravity   = Gravity.CENTER
                setTextColor(getColor(R.color.text_darkest))
                layoutParams = LinearLayout.LayoutParams(MP, WC).also {
                    it.bottomMargin = (6 * dp).roundToInt()
                }
            })
            addView(TextView(this@EntryPageActivity).apply {
                text      = name
                textSize  = 13f
                gravity   = Gravity.CENTER
                typeface  = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.text_darkest))
                layoutParams = LinearLayout.LayoutParams(MP, WC).also {
                    it.bottomMargin = (4 * dp).roundToInt()
                }
            })
            addView(TextView(this@EntryPageActivity).apply {
                text      = desc
                textSize  = 10f
                gravity   = Gravity.CENTER
                setTextColor(getColor(R.color.text_hint))
                layoutParams = LinearLayout.LayoutParams(MP, WC)
            })

            setOnClickListener {
                val intent = Intent(this@EntryPageActivity, MainActivity::class.java)
                if (mode == CanvasMode.FREESTYLE) {
                    intent.putExtra("CANVAS_MODE", mode.name)
                } else {
                    val existing = SessionManager.listSessions(filesDir)
                        .firstOrNull { it.canvasMode == mode }
                    if (existing != null) intent.putExtra("SESSION_ID", existing.id)
                    else                  intent.putExtra("CANVAS_MODE", mode.name)
                }
                startActivity(intent)
            }
        }

    // ── Divider ───────────────────────────────────────────────────────────

    private fun buildDivider(dp: Float): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MP, 1).also {
                it.topMargin    = (28 * dp).roundToInt()
                it.bottomMargin = (20 * dp).roundToInt()
            }
            setBackgroundColor(getColor(R.color.divider))
        }

    // ── Recent documents section ──────────────────────────────────────────

    private fun buildRecentSection(dp: Float): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            addView(LinearLayout(this@EntryPageActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MP, WC)
                addView(sectionLabel(getString(R.string.entry_recent_docs), dp).apply {
                    layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                })
                addView(TextView(this@EntryPageActivity).apply {
                    text = getString(R.string.btn_all_docs) + "→"
                    textSize = 11f
                    setTextColor(getColor(R.color.chip_active))
                    layoutParams = LinearLayout.LayoutParams(WC, WC)
                    setOnClickListener { openSessionList() }
                })
            })
            addView(recentSessionsContainer.also {
                it.layoutParams = LinearLayout.LayoutParams(MP, WC).also { lp ->
                    lp.topMargin = (8 * dp).roundToInt()
                }
            })
        }

    private fun openSessionList() {
        sessionListLauncher.launch(
            Intent(this, SessionListActivity::class.java)
                .putExtra("current_session_id", "")
                .putExtra("current_canvas_mode", "VERTICAL")
        )
    }

    private fun populateRecentSessions(dp: Float) {
        recentSessionsContainer.removeAllViews()
        val sessions = SessionManager.listSessions(filesDir).take(3)
        if (sessions.isEmpty()) {
            recentSessionsContainer.addView(TextView(this).apply {
                text      = getString(R.string.session_empty_message)
                textSize  = 13f
                gravity   = Gravity.CENTER
                setTextColor(getColor(R.color.text_hint))
                val vPad = (18 * dp).roundToInt()
                setPadding(0, vPad, 0, vPad)
                layoutParams = LinearLayout.LayoutParams(MP, WC)
            })
        } else {
            sessions.forEachIndexed { i, meta ->
                if (i > 0) recentSessionsContainer.addView(rowDivider(dp))
                recentSessionsContainer.addView(buildSessionRow(meta, dp))
            }
        }
    }

    private fun buildSessionRow(meta: SessionManager.SessionMeta, dp: Float): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            val vPad = (14 * dp).roundToInt()
            setPadding(0, vPad, 0, vPad)

            addView(TextView(this@EntryPageActivity).apply {
                text = when (meta.canvasMode) {
                    CanvasMode.HORIZONTAL -> "橫"
                    CanvasMode.FREESTYLE  -> "自"
                    else                  -> "直"
                }
                textSize = 12f
                gravity  = Gravity.CENTER
                setTextColor(getColor(R.color.text_light))
                background = GradientDrawable().apply {
                    setColor(getColor(R.color.chip_active))
                    cornerRadius = 4f * dp
                }
                val bPad = (4 * dp).roundToInt()
                setPadding(bPad, bPad, bPad, bPad)
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.marginEnd = (10 * dp).roundToInt()
                }
            })

            addView(LinearLayout(this@EntryPageActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WC, 1f)

                addView(TextView(this@EntryPageActivity).apply {
                    text      = meta.name
                    textSize  = 14f
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.text_darkest))
                    layoutParams = LinearLayout.LayoutParams(MP, WC)
                })

                val statParts = buildList {
                    if (meta.wordCount  > 0) add("${meta.wordCount}${" " + getString(R.string.session_stat_chars)}")
                    if (meta.imageCount > 0) add("${meta.imageCount}${" " + getString(R.string.session_stat_images)}")
                }
                if (statParts.isNotEmpty()) {
                    addView(TextView(this@EntryPageActivity).apply {
                        text      = statParts.joinToString(" · ")
                        textSize  = 11f
                        setTextColor(getColor(R.color.text_hint))
                        layoutParams = LinearLayout.LayoutParams(MP, WC).also {
                            it.topMargin = (2 * dp).roundToInt()
                        }
                    })
                }
            })

            addView(TextView(this@EntryPageActivity).apply {
                text      = meta.formattedDate()
                textSize  = 11f
                gravity   = Gravity.END or Gravity.CENTER_VERTICAL
                setTextColor(getColor(R.color.text_hint))
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.marginStart = (12 * dp).roundToInt()
                }
            })

            setOnClickListener {
                startActivity(
                    Intent(this@EntryPageActivity, MainActivity::class.java)
                        .putExtra("SESSION_ID", meta.id)
                )
            }
        }

    // ── Shared helpers ────────────────────────────────────────────────────

    private fun sectionLabel(text: String, @Suppress("UNUSED_PARAMETER") dp: Float): TextView =
        TextView(this).apply {
            this.text = text
            textSize  = 11f
            setTextColor(getColor(R.color.text_hint))
            layoutParams = LinearLayout.LayoutParams(WC, WC)
        }

    private fun cardGap(dp: Float): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams((8 * dp).roundToInt(), 0)
        }

    private fun rowDivider(@Suppress("UNUSED_PARAMETER") dp: Float): View =
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(MP, 1)
            setBackgroundColor(getColor(R.color.row_active))
        }
}
