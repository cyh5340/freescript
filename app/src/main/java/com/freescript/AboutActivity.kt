package com.freescript

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import kotlin.math.roundToInt

class AboutActivity : AppCompatActivity() {

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private val WC = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(buildRoot())
    }

    private fun buildRoot(): View {
        val dp  = resources.displayMetrics.density
        val pad = (24 * dp).roundToInt()

        return ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(MP, MP)
            setBackgroundColor(getColor(R.color.surface))

            addView(LinearLayout(this@AboutActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(MP, WC)
                setPadding(pad, (pad * 2.5f).roundToInt(), pad, pad)

                // App logo
                addView(FrameLayout(this@AboutActivity).apply {
                    setBackgroundColor(getColor(R.color.surface))
                    layoutParams = LinearLayout.LayoutParams(MP, (160 * dp).roundToInt()).also {
                        it.bottomMargin = (36 * dp).roundToInt()
                    }
                    addView(ImageView(this@AboutActivity).apply {
                        setImageResource(R.drawable.poemeditor_logo)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = FrameLayout.LayoutParams(MP, MP)
                    })
                    addView(LinearLayout(this@AboutActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = FrameLayout.LayoutParams(WC, WC,
                            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also {
                            it.bottomMargin = (8 * dp).roundToInt()
                        }
                        addView(TextView(this@AboutActivity).apply {
                            text = "2026"
                            textSize = 10f
                            gravity = Gravity.CENTER
                            setTextColor(getColor(R.color.text_hint))
                        })
                        addView(ImageView(this@AboutActivity).apply {
                            setImageResource(R.drawable.ic_github)
                            imageTintList = android.content.res.ColorStateList.valueOf(
                                getColor(R.color.text_hint))
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            val sz = (14 * dp).roundToInt()
                            layoutParams = LinearLayout.LayoutParams(sz, sz).also {
                                it.marginStart = (6 * dp).roundToInt()
                            }
                            isClickable = true
                            isFocusable = true
                            contentDescription = "GitHub"
                            setOnClickListener {
                                startActivity(Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/cyh5340/freescript")))
                            }
                        })
                    })
                })

                // Language
                addView(sectionLabel(getString(R.string.label_language), dp))
                addView(vGap((12 * dp).roundToInt()))
                val savedLang = LocaleHelper.getLanguage(this@AboutActivity)
                val lang = if (savedLang.isNotEmpty()) savedLang else {
                    // No saved preference — mirror the actual system locale
                    val sysLang = resources.configuration.locales[0].language
                    if (sysLang == "zh") "zh" else "en"
                }
                addView(buildChipRow(
                    listOf(getString(R.string.lang_en) to "en",
                           getString(R.string.lang_zh) to "zh"),
                    lang, dp
                ) { chosen ->
                    LocaleHelper.setLanguage(this@AboutActivity, chosen)
                    val i = Intent(this@AboutActivity, EntryPageActivity::class.java)
                    i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(i)
                })

                addView(vGap((28 * dp).roundToInt()))

                // Theme
                addView(sectionLabel(getString(R.string.label_theme), dp))
                addView(vGap((12 * dp).roundToInt()))
                val nightMode = LocaleHelper.getNightMode(this@AboutActivity)
                val themeKey = when (nightMode) {
                    AppCompatDelegate.MODE_NIGHT_NO  -> "light"
                    AppCompatDelegate.MODE_NIGHT_YES -> "dark"
                    else -> {
                        // No saved preference — mirror the actual system dark-mode state
                        val nightBits = resources.configuration.uiMode and
                            android.content.res.Configuration.UI_MODE_NIGHT_MASK
                        if (nightBits == android.content.res.Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
                    }
                }
                addView(buildChipRow(
                    listOf(getString(R.string.theme_light) to "light",
                           getString(R.string.theme_dark)  to "dark"),
                    themeKey, dp
                ) { chosen ->
                    val mode = if (chosen == "dark") AppCompatDelegate.MODE_NIGHT_YES
                               else AppCompatDelegate.MODE_NIGHT_NO
                    LocaleHelper.setNightMode(this@AboutActivity, mode)
                    recreate()
                })
            })
        }
    }

    private fun sectionLabel(text: String, dp: Float) = TextView(this).apply {
        this.text = text
        textSize  = 12f
        setTextColor(getColor(R.color.text_lighter))
        layoutParams = LinearLayout.LayoutParams(MP, WC).also {
            it.bottomMargin = 0
        }
    }

    private fun buildChipRow(
        options: List<Pair<String, String>>,
        selected: String,
        dp: Float,
        onSelect: (String) -> Unit
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(WC, WC)
        options.forEach { (label, key) ->
            val active = key == selected
            addView(TextView(this@AboutActivity).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER
                setTextColor(if (active) getColor(R.color.surface) else getColor(R.color.text_dark))
                background = GradientDrawable().apply {
                    cornerRadius = 20f * dp
                    if (active) setColor(getColor(R.color.text_dark))
                    else { setColor(Color.TRANSPARENT); setStroke((1f * dp).roundToInt(), getColor(R.color.stroke)) }
                }
                val hP = (20 * dp).roundToInt(); val vP = (8 * dp).roundToInt()
                setPadding(hP, vP, hP, vP)
                layoutParams = LinearLayout.LayoutParams(WC, WC).also {
                    it.marginEnd = (10 * dp).roundToInt()
                }
                setOnClickListener { onSelect(key) }
            })
        }
    }

    private fun vGap(px: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MP, px)
    }
}
