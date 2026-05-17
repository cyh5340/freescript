package com.poemeditor

import android.graphics.Color
import android.graphics.Typeface

data class ColorOption(val color: Int, val labelRes: Int)
data class FontEntry(val label: String, val typeface: Typeface)

object AppConfig {

    val BG_COLORS = listOf(
        ColorOption(Color.WHITE,                 R.string.bg_color_white),
        ColorOption(Color.parseColor("#FFF8E7"), R.string.bg_color_cream),
        ColorOption(Color.parseColor("#F5E6CA"), R.string.bg_color_antique),
        ColorOption(Color.parseColor("#FDE8E8"), R.string.bg_color_pink),
        ColorOption(Color.parseColor("#FFE0B2"), R.string.bg_color_orange),
        ColorOption(Color.parseColor("#E8F5E9"), R.string.bg_color_green),
        ColorOption(Color.parseColor("#E8F4F8"), R.string.bg_color_blue),
        ColorOption(Color.parseColor("#EDE7F6"), R.string.bg_color_purple),
        ColorOption(Color.parseColor("#EFEBE9"), R.string.bg_color_tea),
        ColorOption(Color.parseColor("#3E2723"), R.string.bg_color_brown),
        ColorOption(Color.parseColor("#1A1A2E"), R.string.bg_color_night),
        ColorOption(Color.parseColor("#2B2B2B"), R.string.bg_color_dark),
        ColorOption(Color.BLACK,                 R.string.bg_color_black),
    )

    val TEXT_COLORS = listOf(
        ColorOption(Color.parseColor("#212121"), R.string.text_color_ink),
        ColorOption(Color.parseColor("#333333"), R.string.text_color_charcoal),
        ColorOption(Color.parseColor("#757575"), R.string.text_color_gray),
        ColorOption(Color.WHITE,                 R.string.text_color_white),
        ColorOption(Color.parseColor("#1B263B"), R.string.text_color_indigo),
        ColorOption(Color.parseColor("#B22222"), R.string.text_color_vermilion),
        ColorOption(Color.parseColor("#D4AF37"), R.string.text_color_gold),
        ColorOption(Color.parseColor("#00A86B"), R.string.text_color_jade),
        ColorOption(Color.parseColor("#4A192C"), R.string.text_color_purple),
        ColorOption(Color.parseColor("#D32F2F"), R.string.text_color_crimson),
        ColorOption(Color.parseColor("#1976D2"), R.string.text_color_blue),
    )

    val FONT_SIZE_LIST = listOf(
        14f to "14", 16f to "16", 18f to "18", 20f to "20", 24f to "24",
        28f to "28", 32f to "32", 36f to "36", 40f to "40", 48f to "48",
        56f to "56", 64f to "64", 72f to "72", 80f to "80", 88f to "88"
    )

    val WORD_GAP_LIST = listOf(
        3f to "0", 5f to "2", 8f to "5", 10f to "7",
        12f to "9", 15f to "12", 18f to "15", 20f to "17",
        22f to "19", 25f to "22", 28f to "25", 30f to "27"
    )

    val PUNCT_LIST = listOf(
        "。", "，", "、", "？", "！", "：", "；", "︱", "︙", "·", "※",
        "﹁", "﹂", "﹃", "﹄", "︻", "︼", "︵", "︶",
        "︽", "︾", "︿", "﹀", "︗", "︘"
    )
}
