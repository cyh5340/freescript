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
        activeImageIndex: Int
    ) {
        SessionManager.saveSession(
            filesDir,
            id,
            name,
            columnData,
            columnBreaks,
            fontIndex,
            fontSizeSp,
            wordGapDp,
            gridTextColor,
            bgColor,
            bgImageUri,
            bgImageMatrixValues,
            inputMode,
            insertedImages,
            activeImageIndex
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
        activeImageIndex: Int
    ) {
        SessionManager.ensureDefaultSession(
            filesDir,
            id,
            name,
            columnData,
            columnBreaks,
            fontIndex,
            fontSizeSp,
            wordGapDp,
            gridTextColor,
            bgColor,
            bgImageUri,
            bgImageMatrixValues,
            inputMode,
            insertedImages,
            activeImageIndex
        )
    }
}
