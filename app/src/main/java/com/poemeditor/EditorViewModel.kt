package com.poemeditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class EditorViewModel(
    private val sessionRepository: SessionRepository
) : ViewModel() {

    class Factory(
        private val sessionRepository: SessionRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(EditorViewModel::class.java)) {
                return EditorViewModel(sessionRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

    private val undoStack = ArrayDeque<EditorHistoryState>()
    private val redoStack = ArrayDeque<EditorHistoryState>()
    private val maxHistory = 50

    fun snapshotForUndo(snapshot: EditorHistoryState) {
        undoStack.addLast(snapshot)
        if (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(currentSnapshot: EditorHistoryState): EditorHistoryState? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(currentSnapshot)
        return previous
    }

    fun redo(currentSnapshot: EditorHistoryState): EditorHistoryState? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(currentSnapshot)
        return next
    }

    fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    fun loadSessionJson(id: String): org.json.JSONObject? = sessionRepository.loadSession(id)

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
    ) = sessionRepository.saveSession(
        id, name, columnData, columnBreaks,
        fontIndex, fontSizeSp, wordGapDp, gridTextColor, bgColor, bgImageUri,
        bgImageMatrixValues, inputMode, insertedImages, activeImageIndex,
        gridPadTop, gridPadBottom, gridPadLeft, gridPadRight
    )

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
    ) = sessionRepository.ensureDefaultSession(
        id, name, columnData, columnBreaks,
        fontIndex, fontSizeSp, wordGapDp, gridTextColor, bgColor, bgImageUri,
        bgImageMatrixValues, inputMode, insertedImages, activeImageIndex,
        gridPadTop, gridPadBottom, gridPadLeft, gridPadRight
    )
}
