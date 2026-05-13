package com.poemeditor

import android.graphics.Color
import android.graphics.Typeface

data class ColorOption(val color: Int, val label: String)
data class FontEntry(val label: String, val typeface: Typeface)

object AppConfig {

    val BG_COLORS = listOf(
        ColorOption(Color.WHITE,                 "白"),
        ColorOption(Color.parseColor("#FFF8E7"), "米"),
        ColorOption(Color.parseColor("#F5E6CA"), "古"),
        ColorOption(Color.parseColor("#FDE8E8"), "粉"),
        ColorOption(Color.parseColor("#FFE0B2"), "橙"),
        ColorOption(Color.parseColor("#E8F5E9"), "草"),
        ColorOption(Color.parseColor("#E8F4F8"), "藍"),
        ColorOption(Color.parseColor("#EDE7F6"), "紫"),
        ColorOption(Color.parseColor("#EFEBE9"), "茶"),
        ColorOption(Color.parseColor("#3E2723"), "棕"),
        ColorOption(Color.parseColor("#1A1A2E"), "夜"),
        ColorOption(Color.parseColor("#2B2B2B"), "深"),
        ColorOption(Color.BLACK,                 "黑"),
    )

    val TEXT_COLORS = listOf(
        ColorOption(Color.parseColor("#212121"), "墨"),
        ColorOption(Color.parseColor("#333333"), "炭"),
        ColorOption(Color.parseColor("#757575"), "灰"),
        ColorOption(Color.WHITE,                 "白"),
        ColorOption(Color.parseColor("#1B263B"), "藍墨"),
        ColorOption(Color.parseColor("#B22222"), "硃"),
        ColorOption(Color.parseColor("#D4AF37"), "金"),
        ColorOption(Color.parseColor("#00A86B"), "翠"),
        ColorOption(Color.parseColor("#4A192C"), "紫"),
        ColorOption(Color.parseColor("#D32F2F"), "赤"),
        ColorOption(Color.parseColor("#1976D2"), "藍"),
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
