package com.poemeditor

import java.io.File

class SessionRepository(private val filesDir: File) {

    fun saveSession(
        id: String,
        name: String,
        columnData: List<List<String>>,
        columnBreaks: Set<Int>,
        fontIndex: Int,
        fontSizeSp: Float,
        wordGapDp: Float,
        gridTextColor: Int,
        bgColor: Int,
        bgImageUri: String?,
        bgImageMatrixValues: FloatArray?,
        inputMode: String,
        insertedImages: List<InsertedImageState>,
        activeImageIndex: Int,
        gridPadTop: Int = 0, gridPadBottom: Int = 0,
        gridPadLeft: Int = 0, gridPadRight: Int = 0
    ) {
        SessionManager.saveSession(
            filesDir, id, name, columnData, columnBreaks,
            fontIndex, fontSizeSp, wordGapDp, gridTextColor, bgColor, bgImageUri,
            bgImageMatrixValues, inputMode, insertedImages, activeImageIndex,
            gridPadTop, gridPadBottom, gridPadLeft, gridPadRight
        )
    }

    fun loadSession(id: String): org.json.JSONObject? = SessionManager.loadSession(filesDir, id)

    fun ensureDefaultSession(
        id: String,
        name: String,
        columnData: List<List<String>>,
        columnBreaks: Set<Int>,
        fontIndex: Int,
        fontSizeSp: Float,
        wordGapDp: Float,
        gridTextColor: Int,
        bgColor: Int,
        bgImageUri: String?,
        bgImageMatrixValues: FloatArray?,
        inputMode: String,
        insertedImages: List<InsertedImageState>,
        activeImageIndex: Int,
        gridPadTop: Int = 0, gridPadBottom: Int = 0,
        gridPadLeft: Int = 0, gridPadRight: Int = 0
    ) {
        SessionManager.ensureDefaultSession(
            filesDir, id, name, columnData, columnBreaks,
            fontIndex, fontSizeSp, wordGapDp, gridTextColor, bgColor, bgImageUri,
            bgImageMatrixValues, inputMode, insertedImages, activeImageIndex,
            gridPadTop, gridPadBottom, gridPadLeft, gridPadRight
        )
    }
}
