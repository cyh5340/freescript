package com.freescript

import android.graphics.Color
import android.graphics.Typeface

enum class CanvasMode { VERTICAL, HORIZONTAL, FREESTYLE }
enum class FreestyleInteractMode { MOVE }

data class ColorOption(val color: Int, val labelRes: Int)
data class FontEntry(val label: String, val typeface: Typeface)
data class FontOption(
    val label: String,
    val assetNames: List<String> = emptyList(),
    val fallbackFamily: String,
    val fallbackStyle: Int = Typeface.NORMAL
)

object AppConfig {

    val BG_COLORS = listOf(
        ColorOption(Color.WHITE,                 R.string.bg_color_white),
        ColorOption(Color.parseColor("#FFF8E7"), R.string.bg_color_cream),
        ColorOption(Color.parseColor("#F7F3E8"), R.string.bg_color_paper),
        ColorOption(Color.parseColor("#FFFDE7"), R.string.bg_color_ivory),
        ColorOption(Color.parseColor("#F5E6CA"), R.string.bg_color_antique),
        ColorOption(Color.parseColor("#FBE9E7"), R.string.bg_color_blush),
        ColorOption(Color.parseColor("#FDE8E8"), R.string.bg_color_pink),
        ColorOption(Color.parseColor("#FFE0B2"), R.string.bg_color_orange),
        ColorOption(Color.parseColor("#E8F5E9"), R.string.bg_color_green),
        ColorOption(Color.parseColor("#E0F2F1"), R.string.bg_color_mint),
        ColorOption(Color.parseColor("#E8F4F8"), R.string.bg_color_blue),
        ColorOption(Color.parseColor("#E0F7FA"), R.string.bg_color_cyan),
        ColorOption(Color.parseColor("#ECEFF1"), R.string.bg_color_mist),
        ColorOption(Color.parseColor("#EDE7F6"), R.string.bg_color_purple),
        ColorOption(Color.parseColor("#F3E5F5"), R.string.bg_color_lilac),
        ColorOption(Color.parseColor("#EFEBE9"), R.string.bg_color_tea),
        ColorOption(Color.parseColor("#3E2723"), R.string.bg_color_brown),
        ColorOption(Color.parseColor("#263238"), R.string.bg_color_slate),
        ColorOption(Color.parseColor("#1A1A2E"), R.string.bg_color_night),
        ColorOption(Color.parseColor("#2B2B2B"), R.string.bg_color_dark),
        ColorOption(Color.parseColor("#1C1C1E"), R.string.bg_color_system),
        ColorOption(Color.BLACK,                 R.string.bg_color_black),
    )

    val TEXT_COLORS = listOf(
        ColorOption(Color.parseColor("#212121"), R.string.text_color_ink),
        ColorOption(Color.parseColor("#333333"), R.string.text_color_charcoal),
        ColorOption(Color.parseColor("#757575"), R.string.text_color_gray),
        ColorOption(Color.WHITE,                 R.string.text_color_white),
        ColorOption(Color.parseColor("#1B263B"), R.string.text_color_indigo),
        ColorOption(Color.parseColor("#0D47A1"), R.string.text_color_navy),
        ColorOption(Color.parseColor("#1976D2"), R.string.text_color_blue),
        ColorOption(Color.parseColor("#00695C"), R.string.text_color_teal),
        ColorOption(Color.parseColor("#00838F"), R.string.text_color_ocean),
        ColorOption(Color.parseColor("#1B5E20"), R.string.text_color_forest),
        ColorOption(Color.parseColor("#00A86B"), R.string.text_color_jade),
        ColorOption(Color.parseColor("#5D4037"), R.string.text_color_brown),
        ColorOption(Color.parseColor("#4E342E"), R.string.text_color_coffee),
        ColorOption(Color.parseColor("#4A192C"), R.string.text_color_purple),
        ColorOption(Color.parseColor("#880E4F"), R.string.text_color_wine),
        ColorOption(Color.parseColor("#B22222"), R.string.text_color_vermilion),
        ColorOption(Color.parseColor("#D32F2F"), R.string.text_color_crimson),
        ColorOption(Color.parseColor("#D4AF37"), R.string.text_color_gold),
        ColorOption(Color.parseColor("#FF8F00"), R.string.text_color_amber),
    )

    val FONT_SIZE_LIST = listOf(
        14f to "14", 16f to "16", 18f to "18", 20f to "20", 24f to "24",
        28f to "28", 32f to "32", 36f to "36", 40f to "40", 48f to "48",
        56f to "56", 64f to "64", 72f to "72", 80f to "80", 88f to "88"
    )

    val BORDER_THICKNESS_LIST = listOf(
        0.5f to "0.5", 1f to "1", 2f to "2", 3f to "3", 5f to "5"
    )

    val BORDER_COLORS = listOf(
        ColorOption(Color.parseColor("#CCCCCC"), R.string.text_color_gray),
        ColorOption(Color.parseColor("#757575"), R.string.text_color_charcoal),
        ColorOption(Color.parseColor("#212121"), R.string.text_color_ink),
        ColorOption(Color.BLACK,                 R.string.bg_color_black),
        ColorOption(Color.WHITE,                 R.string.bg_color_white),
        ColorOption(Color.parseColor("#2196F3"), R.string.text_color_blue),
        ColorOption(Color.parseColor("#0D47A1"), R.string.text_color_navy),
        ColorOption(Color.parseColor("#00695C"), R.string.text_color_teal),
        ColorOption(Color.parseColor("#00838F"), R.string.text_color_ocean),
        ColorOption(Color.parseColor("#1B5E20"), R.string.text_color_forest),
        ColorOption(Color.parseColor("#5D4037"), R.string.text_color_brown),
        ColorOption(Color.parseColor("#4E342E"), R.string.text_color_coffee),
        ColorOption(Color.parseColor("#B22222"), R.string.text_color_vermilion),
        ColorOption(Color.parseColor("#880E4F"), R.string.text_color_wine),
        ColorOption(Color.parseColor("#D4AF37"), R.string.text_color_gold),
        ColorOption(Color.parseColor("#FF8F00"), R.string.text_color_amber),
        ColorOption(Color.parseColor("#00A86B"), R.string.text_color_jade),
        ColorOption(Color.parseColor("#4A192C"), R.string.text_color_purple),
    )

    val WORD_GAP_LIST = listOf(
        0f to "0", 5f to "5", 8f to "8", 10f to "10",
        12f to "12", 15f to "15", 18f to "18", 20f to "20",
        22f to "22", 25f to "25", 28f to "28", 30f to "30"
    )

    // Font options are centralized here to keep MainActivity focused on orchestration.
    // To avoid IP concerns, do not use proprietary system font file paths.
    val FONT_OPTIONS = listOf(
        FontOption(label = "黑體", fallbackFamily = "sans-serif", fallbackStyle = Typeface.NORMAL),
        FontOption(label = "粗黑", fallbackFamily = "sans-serif", fallbackStyle = Typeface.BOLD),
        FontOption(label = "宋體", fallbackFamily = "serif", fallbackStyle = Typeface.NORMAL),
        FontOption(label = "粗宋", fallbackFamily = "serif", fallbackStyle = Typeface.BOLD),
        FontOption(label = "仿宋", fallbackFamily = "serif", fallbackStyle = Typeface.ITALIC),
        FontOption(label = "等寬", fallbackFamily = "monospace", fallbackStyle = Typeface.NORMAL),

        // Common English-friendly families (system-based, IP-safe)
        FontOption(label = "Sans", fallbackFamily = "sans-serif", fallbackStyle = Typeface.NORMAL),
        FontOption(label = "Sans Bold", fallbackFamily = "sans-serif", fallbackStyle = Typeface.BOLD),
        FontOption(label = "Serif", fallbackFamily = "serif", fallbackStyle = Typeface.NORMAL),
        FontOption(label = "Serif Italic", fallbackFamily = "serif", fallbackStyle = Typeface.ITALIC),
        FontOption(label = "Mono", fallbackFamily = "monospace", fallbackStyle = Typeface.NORMAL),
        FontOption(label = "Mono Bold", fallbackFamily = "monospace", fallbackStyle = Typeface.BOLD),
        FontOption(label = "Cursive", fallbackFamily = "cursive", fallbackStyle = Typeface.NORMAL),

        FontOption(
            label = "霞鶩文楷",
            assetNames = listOf("LXGWWenKai-Regular.ttf"),
            fallbackFamily = "serif",
            fallbackStyle = Typeface.NORMAL
        ),
    )

    // Default session settings applied to every new document
    const val DEFAULT_FONT_INDEX     = 0
    const val DEFAULT_FONT_SIZE_SP   = 24f
    const val DEFAULT_WORD_GAP_DP    = 0f
    val       DEFAULT_TEXT_COLOR     = Color.BLACK
    val       DEFAULT_BG_COLOR       = Color.WHITE
    val       DEFAULT_BOX_BG_COLOR   = Color.TRANSPARENT
    const val DEFAULT_BORDER_VISIBLE = true
    val       DEFAULT_BORDER_COLOR   = Color.parseColor("#CCCCCC")
    const val DEFAULT_BORDER_IDX     = 1

    val PUNCT_LIST = listOf(
        "。", "，", "、", "？", "！", "：", "；", "︱", "︙", "·", "※",
        "﹁", "﹂", "﹃", "﹄", "︻", "︼", "︵", "︶",
        "︽", "︾", "︿", "﹀", "︗", "︘"
    )
}
