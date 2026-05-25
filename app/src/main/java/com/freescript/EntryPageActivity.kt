package com.freescript

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt
import kotlin.math.sqrt

class EntryPageActivity : AppCompatActivity() {

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var recentSessionsContainer: LinearLayout
    private lateinit var sessionListLauncher: ActivityResultLauncher<Intent>
    private var isLandscape = false

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        LocaleHelper.applyNightMode(this)
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

        setContentView(buildRoot())
    }

    override fun onResume() {
        super.onResume()
        populateRecentSessions()
    }

    // ── Root dispatcher ────────────────────────────────────────────────────

    private fun buildRoot(): View {
        val dm = resources.displayMetrics
        isLandscape = dm.widthPixels > dm.heightPixels
        return if (isLandscape) buildRootLandscape(dm, dm.density)
               else             buildRootPortrait(dm, dm.density)
    }

    // ── Portrait layout ────────────────────────────────────────────────────
    //
    //  ┌──────────────────┬──────────┐
    //  │  自  fS×fS       │          │  top row height = vH = avail
    //  ├──────────────────┤  直 vW×vH│
    //  │  recent (×3)     │          │  ← flex area in left-column gap
    //  │  All Files →     │          │
    //  └──────────────────┴──────────┘
    //  ┌──────────────────────────────┐  ← gapV = gapH
    //  │       橫  avail×hH           │
    //  └──────────────────────────────┘

    private fun buildRootPortrait(dm: android.util.DisplayMetrics, dp: Float): View {
        val pad  = (16 * dp).roundToInt()
        val avail = dm.widthPixels - 2 * pad

        val gapH = (20 * dp).roundToInt()
        val gapV = gapH
        val u    = (avail - gapH).toDouble()
        val vW       = ((2*u + avail - sqrt(avail * (4*u + avail))) / 2.0).roundToInt()
        val leftColW = avail - gapH - vW
        val fS       = leftColW
        val vH       = avail
        val hH       = vW

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.surface))
            layoutParams = ViewGroup.LayoutParams(MP, MP)
            setPadding(pad, pad, pad, pad)

            // Top row
            addView(LinearLayout(this@EntryPageActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MP, vH)

                // Left column: freestyle card + recent files + all files link
                addView(LinearLayout(this@EntryPageActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(leftColW, MP)

                    addView(buildModeCard(CanvasMode.FREESTYLE, dp), LinearLayout.LayoutParams(fS, fS))

                    addView(vGap(gapV))

                    recentSessionsContainer = LinearLayout(this@EntryPageActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(MP, 0, 1f)
                    }
                    addView(recentSessionsContainer)

                    addView(buildAllFilesRow(dp))
                })

                addView(hGap(gapH))

                // Right column: vertical card (full row height)
                addView(buildModeCard(CanvasMode.VERTICAL, dp), LinearLayout.LayoutParams(vW, vH))
            })

            addView(vGap(gapV))

            // Horizontal card (full width)
            addView(buildModeCard(CanvasMode.HORIZONTAL, dp, horizontal = true),
                LinearLayout.LayoutParams(avail, hH))

            addView(vGap(gapV))
            addView(android.widget.FrameLayout(this@EntryPageActivity).apply {
                setBackgroundColor(getColor(R.color.surface))
                layoutParams = LinearLayout.LayoutParams(MP, WC)
                addView(android.widget.ImageView(this@EntryPageActivity).apply {
                    setImageResource(R.drawable.poemeditor_logo)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    layoutParams = android.widget.FrameLayout.LayoutParams(MP, WC)
                })
                addView(LinearLayout(this@EntryPageActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = android.widget.FrameLayout.LayoutParams(WC, WC,
                        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also {
                        it.bottomMargin = (20 * dp).roundToInt()
                    }
                    addView(android.widget.TextView(this@EntryPageActivity).apply {
                        text = "2026"
                        textSize = 10f
                        setTextColor(getColor(R.color.text_hint))
                    })
                    addView(android.widget.TextView(this@EntryPageActivity).apply {
                        text = "  ⚙"
                        textSize = 13f
                        setTextColor(getColor(R.color.text_hint))
                        setOnClickListener {
                            startActivity(Intent(this@EntryPageActivity, AboutActivity::class.java))
                        }
                    })
                    addView(android.widget.TextView(this@EntryPageActivity).apply {
                        text = "  " + (try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" })
                        textSize = 10f
                        setTextColor(getColor(R.color.text_hint))
                    })
                })
            })
        }
    }

    // ── Landscape layout ───────────────────────────────────────────────────
    //
    //  ┌──────────────────────┬─────────────────────┐
    //  │  自  fS×fS  │        │  session list        │
    //  │             │ 直 vW×vH│  (scrollable)        │
    //  │  (gap)      │        ├─────────────────────┤
    //  ├─────────────┴────────┤  All files →         │
    //  │  橫  avail×hH        │  logo + ⚙            │
    //  └──────────────────────┴─────────────────────┘

    private fun buildRootLandscape(dm: android.util.DisplayMetrics, dp: Float): View {
        val pad   = (16 * dp).roundToInt()
        val gapH  = (20 * dp).roundToInt()
        val gapV  = gapH
        val halfW = dm.widthPixels / 2

        // Same proportion math as portrait, but based on left-half available width
        val avail    = halfW - pad - pad / 2
        val u        = (avail - gapH).toDouble()
        val vW       = ((2*u + avail - sqrt(avail * (4*u + avail))) / 2.0).roundToInt()
        val leftColW = avail - gapH - vW
        val fS       = leftColW
        val hH       = vW
        // Cap top-row height so top row + gap + horizontal card fits within screen height
        val availScreenH = dm.heightPixels - 2 * pad
        val vH = minOf(avail, availScreenH - gapV - hH)

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(getColor(R.color.surface))
            layoutParams = ViewGroup.LayoutParams(MP, MP)

            // Left half: FREESTYLE top-left, VERTICAL top-right, HORIZONTAL bottom
            addView(LinearLayout(this@EntryPageActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(halfW, MP)
                setPadding(pad, pad, pad / 2, pad)

                // Top row: FREESTYLE (left col) + VERTICAL (right col)
                addView(LinearLayout(this@EntryPageActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(MP, vH)

                    addView(LinearLayout(this@EntryPageActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(leftColW, MP)
                        addView(buildModeCard(CanvasMode.FREESTYLE, dp),
                            LinearLayout.LayoutParams(fS, fS))
                    })

                    addView(hGap(gapH))

                    addView(buildModeCard(CanvasMode.VERTICAL, dp),
                        LinearLayout.LayoutParams(vW, vH))
                })

                addView(vGap(gapV))

                addView(buildModeCard(CanvasMode.HORIZONTAL, dp, horizontal = true),
                    LinearLayout.LayoutParams(avail, hH))
            })

            // Vertical divider
            addView(View(this@EntryPageActivity).apply {
                setBackgroundColor(getColor(R.color.divider))
                layoutParams = LinearLayout.LayoutParams((1 * dp).roundToInt(), MP)
            })

            // Right half: scrollable session list + all files + logo
            addView(LinearLayout(this@EntryPageActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, MP, 1f)
                setPadding(pad / 2, pad, pad, pad)

                // Scrollable session list fills available space
                addView(ScrollView(this@EntryPageActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(MP, 0, 1f)
                    isScrollbarFadingEnabled = true
                    addView(LinearLayout(this@EntryPageActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = ViewGroup.LayoutParams(MP, WC)
                        recentSessionsContainer = this
                    })
                })

                addView(buildAllFilesRow(dp))

                // Logo + settings gear
                addView(android.widget.FrameLayout(this@EntryPageActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(MP, (224 * dp).roundToInt())
                    addView(android.widget.ImageView(this@EntryPageActivity).apply {
                        setImageResource(R.drawable.poemeditor_logo)
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        layoutParams = android.widget.FrameLayout.LayoutParams(MP, MP)
                    })
                    addView(LinearLayout(this@EntryPageActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = android.widget.FrameLayout.LayoutParams(WC, WC,
                            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also {
                            it.bottomMargin = (6 * dp).roundToInt()
                        }
                        addView(android.widget.TextView(this@EntryPageActivity).apply {
                            text = "2026"
                            textSize = 10f
                            setTextColor(getColor(R.color.text_hint))
                        })
                        addView(android.widget.TextView(this@EntryPageActivity).apply {
                            text = "  ⚙"
                            textSize = 13f
                            setTextColor(getColor(R.color.text_hint))
                            setOnClickListener {
                                startActivity(Intent(this@EntryPageActivity, AboutActivity::class.java))
                            }
                        })
                        addView(android.widget.TextView(this@EntryPageActivity).apply {
                            text = "  " + (try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" })
                            textSize = 10f
                            setTextColor(getColor(R.color.text_hint))
                        })
                    })
                })
            })
        }
    }

    // ── Mode cards (clean buttons, no embedded sessions) ───────────────────

    private fun buildModeCard(mode: CanvasMode, dp: Float, horizontal: Boolean = false): View {
        val icon = when (mode) {
            CanvasMode.VERTICAL   -> "直"
            CanvasMode.HORIZONTAL -> "橫"
            CanvasMode.FREESTYLE  -> "自"
        }
        val label = when (mode) {
            CanvasMode.VERTICAL   -> getString(R.string.entry_mode_vertical)
            CanvasMode.HORIZONTAL -> getString(R.string.entry_mode_horizontal)
            CanvasMode.FREESTYLE  -> getString(R.string.entry_mode_freestyle)
        }
        val desc = when (mode) {
            CanvasMode.VERTICAL   -> getString(R.string.entry_mode_vertical_desc)
            CanvasMode.HORIZONTAL -> getString(R.string.entry_mode_horizontal_desc)
            CanvasMode.FREESTYLE  -> getString(R.string.entry_mode_freestyle_desc)
        }
        val pad = (14 * dp).roundToInt()

        return LinearLayout(this).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity     = when {
                horizontal -> Gravity.CENTER_VERTICAL
                mode == CanvasMode.VERTICAL -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                else -> Gravity.CENTER
            }
            background  = cardBg(dp)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { launchMode(mode) }

            val iconView = cardIcon(icon, dp).also {
                it.layoutParams = if (horizontal)
                    LinearLayout.LayoutParams(WC, WC).also { lp -> lp.marginEnd = (14 * dp).roundToInt() }
                else
                    LinearLayout.LayoutParams(MP, WC)
            }
            addView(iconView)

            if (horizontal) {
                addView(LinearLayout(this@EntryPageActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(WC, WC)
                    addView(cardLabel(label, dp))
                    addView(cardDesc(desc, dp))
                })
            } else if (mode == CanvasMode.VERTICAL) {
                // Two vertical text columns side-by-side below the icon.
                // Right column = label, left column = description (traditional RTL column order).
                addView(LinearLayout(this@EntryPageActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    // weight=1 fills remaining card height after the icon → text never overflows
                    layoutParams = LinearLayout.LayoutParams(MP, 0, 1f).also {
                        it.topMargin = (6 * dp).roundToInt()
                    }
                    // desc on the left — MP height so gravity=CENTER vertically centers the text
                    addView(TextView(this@EntryPageActivity).apply {
                        text = desc.map { it.toString() }.joinToString("\n")
                        textSize = 9f
                        gravity = Gravity.CENTER
                        setTextColor(getColor(R.color.text_hint))
                        layoutParams = LinearLayout.LayoutParams(WC, MP).also {
                            it.marginEnd = (8 * dp).roundToInt()
                        }
                    })
                    // label on the right
                    addView(TextView(this@EntryPageActivity).apply {
                        text = label.map { it.toString() }.joinToString("\n")
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setTextColor(getColor(R.color.text_dark))
                        layoutParams = LinearLayout.LayoutParams(WC, MP)
                    })
                })
            } else {
                addView(cardLabel(label, dp))
                addView(cardDesc(desc, dp))
            }
        }
    }

    private fun buildAllFilesRow(dp: Float): View =
        FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            val vPad = (10 * dp).roundToInt()
            setPadding(0, vPad, 0, vPad)
            addView(TextView(this@EntryPageActivity).apply {
                text = "${getString(R.string.btn_all_docs)} →"
                textSize = 12f
                setTextColor(getColor(R.color.text_medium))
                layoutParams = FrameLayout.LayoutParams(WC, WC, Gravity.END or Gravity.CENTER_VERTICAL)
                setOnClickListener { openSessionList() }
            })
        }

    // ── Recent sessions (squeezed in left-column gap) ──────────────────────

    private fun populateRecentSessions() {
        val dp = resources.displayMetrics.density
        recentSessionsContainer.removeAllViews()
        val sessions = if (isLandscape) SessionManager.listSessions(filesDir)
                      else            SessionManager.listSessions(filesDir).take(3)
        sessions.forEachIndexed { i, meta ->
            if (i > 0) recentSessionsContainer.addView(
                hDivider().also { it.layoutParams = LinearLayout.LayoutParams(MP, 1) }
            )
            recentSessionsContainer.addView(buildSessionRow(meta, dp))
        }
    }

    private fun buildSessionRow(meta: SessionManager.SessionMeta, dp: Float): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            val vPad = (7 * dp).roundToInt()
            setPadding(0, vPad, 0, vPad)
            isClickable = true
            isFocusable = true

            // Mode chip + name on one line
            addView(LinearLayout(this@EntryPageActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MP, WC)

                addView(TextView(this@EntryPageActivity).apply {
                    text = when (meta.canvasMode) {
                        CanvasMode.HORIZONTAL -> "橫"
                        CanvasMode.FREESTYLE  -> "自"
                        else                  -> "直"
                    }
                    textSize = 9f
                    gravity  = Gravity.CENTER
                    setTextColor(getColor(R.color.text_light))
                    background = GradientDrawable().apply {
                        setColor(getColor(R.color.chip_active))
                        cornerRadius = 3f * dp
                    }
                    val bPad = (3 * dp).roundToInt()
                    setPadding(bPad, bPad, bPad, bPad)
                    layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                        it.marginEnd = (6 * dp).roundToInt()
                    }
                })

                addView(TextView(this@EntryPageActivity).apply {
                    text = meta.name
                    textSize = 12f
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(getColor(R.color.text_darkest))
                    layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
                })
            })

            // Timestamp below name
            addView(TextView(this@EntryPageActivity).apply {
                text = meta.formattedDate()
                textSize = 9f
                setTextColor(getColor(R.color.text_hint))
                layoutParams = LinearLayout.LayoutParams(MP, WC).also {
                    it.topMargin = (2 * dp).roundToInt()
                }
            })

            setOnClickListener {
                startActivity(
                    Intent(this@EntryPageActivity, MainActivity::class.java)
                        .putExtra("SESSION_ID", meta.id)
                )
            }
        }

    // ── Navigation ─────────────────────────────────────────────────────────

    private fun launchMode(mode: CanvasMode) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("CANVAS_MODE", mode.name))
    }

    private fun openSessionList() {
        sessionListLauncher.launch(
            Intent(this, SessionListActivity::class.java)
                .putExtra("current_session_id", "")
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun cardIcon(text: String, dp: Float) = TextView(this).apply {
        this.text = text
        textSize  = 36f
        gravity   = Gravity.CENTER
        typeface  = Typeface.DEFAULT_BOLD
        setTextColor(getColor(R.color.text_darkest))
    }

    private fun cardLabel(text: String, dp: Float) = TextView(this).apply {
        this.text = text
        textSize  = 13f
        gravity   = Gravity.CENTER
        setTextColor(getColor(R.color.text_dark))
        layoutParams = LinearLayout.LayoutParams(WC, WC).also {
            it.topMargin = (4 * dp).roundToInt()
        }
    }

    private fun cardDesc(text: String, dp: Float) = TextView(this).apply {
        this.text = text
        textSize  = 9f
        gravity   = Gravity.CENTER
        setTextColor(getColor(R.color.text_hint))
        layoutParams = LinearLayout.LayoutParams(WC, WC).also {
            it.topMargin = (2 * dp).roundToInt()
        }
    }

    private fun cardBg(dp: Float) = GradientDrawable().apply {
        setColor(getColor(R.color.panel_bg))
        cornerRadius = 12f * dp
        setStroke((1f * dp).roundToInt(), getColor(R.color.divider))
    }

    private fun hDivider() = View(this).apply { setBackgroundColor(getColor(R.color.divider)) }
    private fun hGap(px: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(px, MP) }
    private fun vGap(px: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(MP, px) }
}
