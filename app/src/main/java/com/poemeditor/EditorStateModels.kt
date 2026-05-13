package com.poemeditor

data class InsertedImageState(
    val uri: String,
    val matrix: FloatArray? = null
)

data class EditorHistoryState(
    val data: List<List<String>>,
    val breaks: Set<Int>,
    val fontIndex: Int,
    val fontSizeSp: Float,
    val wordGapDp: Float,
    val gridTextColor: Int,
    val bgColor: Int,
    val inputMode: InputMode,
    val insertedImages: List<InsertedImageState>,
    val activeImageIndex: Int
)