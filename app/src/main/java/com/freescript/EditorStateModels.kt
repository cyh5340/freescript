package com.freescript

data class InsertedImageState(
    val uri: String,
    val matrix: FloatArray? = null
)

data class TextBoxInstance(
    val id: String = java.util.UUID.randomUUID().toString(),
    var leftPx: Float = 0f,
    var topPx: Float = 0f,
    var colCount: Int = 2,
    var rowCount: Int = 2,
    val columnData: MutableList<MutableList<String>> = mutableListOf(),
    val columnBreaks: MutableSet<Int> = mutableSetOf(),
    var fontIndex: Int = 0,
    var fontSizeSp: Float = 24f,
    var wordGapDp: Float = 0f,
    var gridTextColor: Int = android.graphics.Color.BLACK,
    var inputMode: InputMode = InputMode.SEQUENTIAL,
    var isHorizontal: Boolean = false,
    var boxBgColor: Int = android.graphics.Color.TRANSPARENT,
    var borderVisible: Boolean = true,
    var borderColor: Int = android.graphics.Color.parseColor("#CCCCCC"),
    var borderThicknessIdx: Int = 1
) {
    // Runtime-only; not serialized. Set from font catalogue whenever box is activated or updated.
    var typeface: android.graphics.Typeface = android.graphics.Typeface.DEFAULT
}

/**
 * DTO that consolidates all session-level state into one object, replacing the long
 * parameter chains across MainActivity → EditorViewModel → SessionRepository → SessionManager.
 * Vertical/horizontal sessions populate [textBoxes] with a single field; freestyle sessions
 * populate it with all boxes. Legacy JSON load paths produce this from the parsed JSON.
 */
data class SessionDocument(
    val id: String,
    val name: String,
    val canvasMode: String,
    val columnData: List<List<String>>,
    val columnBreaks: Set<Int>,
    val fontIndex: Int,
    val fontSizeSp: Float,
    val wordGapDp: Float,
    val gridTextColor: Int,
    val bgColor: Int,
    val bgImageUri: String?,
    val bgImageMatrix: FloatArray?,
    val inputMode: String,
    val insertedImages: List<InsertedImageState>,
    val activeImageIndex: Int,
    val gridPadTop: Int,
    val gridPadBottom: Int,
    val gridPadLeft: Int,
    val gridPadRight: Int,
    val textBoxes: List<TextBoxInstance> = emptyList(),
    val horizontalText: String = "",
    val screenshotCropLeft:   Int? = null,
    val screenshotCropTop:    Int? = null,
    val screenshotCropRight:  Int? = null,
    val screenshotCropBottom: Int? = null
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
    val activeImageIndex: Int,
    val textBoxes: List<TextBoxInstance> = emptyList()
)
