package com.poemeditor

import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.EditText

class CellInputController {
    private var lastTapIndex = -1
    private var lastTapTime = 0L

    fun attachTouchListener(
        et: EditText,
        index: Int,
        getInputMode: () -> InputMode,
        getIsSelecting: () -> Boolean,
        clearSelection: () -> Unit,
        hideHandlePasteMenu: () -> Unit,
        enterSelectionMode: (Int) -> Unit,
        isToolsVisible: () -> Boolean,
        dismissToolsPanel: () -> Unit,
        getLastKeyboardHeight: () -> Int,
        setMainScrollPaddingBottom: (Int) -> Unit,
        getNumRows: () -> Int,
        focusCell: (Int) -> Unit,
        scrollToColumn: (Int) -> Unit,
        findSequentialTapTarget: (Int) -> Int
    ) {
        et.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val now = System.currentTimeMillis()

                // Double-tap → enter selection mode
                if (!getIsSelecting() && lastTapIndex == index && now - lastTapTime < 300L) {
                    lastTapTime = 0L
                    lastTapIndex = -1
                    enterSelectionMode(index)
                    return@setOnTouchListener true
                }

                // Tap outside handles while selecting → dismiss selection, fall through to normal tap
                if (getIsSelecting()) {
                    hideHandlePasteMenu()
                    clearSelection()
                    // fall through: normal focus/keyboard logic runs below
                }

                lastTapTime = now
                lastTapIndex = index

                if (isToolsVisible()) {
                    dismissToolsPanel()
                    val keyboardHeight = getLastKeyboardHeight()
                    if (keyboardHeight > 0) setMainScrollPaddingBottom(keyboardHeight)
                }

                if (getInputMode() == InputMode.SEQUENTIAL && et.text.isEmpty()) {
                    val numRows = getNumRows()
                    val target = findSequentialTapTarget(index)
                    if (target >= 0 && target != index) {
                        focusCell(target)
                        scrollToColumn(target / numRows.coerceAtLeast(1))
                        return@setOnTouchListener true
                    }
                }

                et.requestFocus()
                et.setSelection(et.text.length)
                focusCell(index)
            }
            true
        }
    }

    fun attachKeyListener(
        et: EditText,
        index: Int,
        isRestoring: () -> Boolean,
        pushHistory: () -> Unit,
        insertColumnBreak: (Int, Int) -> Unit,
        getNumRows: () -> Int,
        getInputMode: () -> InputMode,
        hasColumnBreak: (Int) -> Boolean,
        removeColumnBreak: (Int) -> Unit,
        getColumnChar: (Int, Int) -> String,
        deletePreviousSequentialCell: (Int, Int, Int) -> Unit,
        handleScatterEmptyBackspace: (Int, Int, Int) -> Unit
    ) {
        et.setOnKeyListener { _, keyCode, event ->
            if (isRestoring()) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DEL && keyCode != KeyEvent.KEYCODE_ENTER) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) {
                val numRows = getNumRows().coerceAtLeast(1)
                when (keyCode) {
                    KeyEvent.KEYCODE_ENTER -> {
                        pushHistory()
                        insertColumnBreak(index / numRows, index % numRows)
                    }

                    KeyEvent.KEYCODE_DEL -> {
                        pushHistory()
                        val cellCol = index / numRows
                        val cellRow = index % numRows
                        // Row 0 of a break column → undo the column break (SEQUENTIAL only).
                        if (cellRow == 0 && cellCol > 0 && getInputMode() != InputMode.SCATTER
                            && hasColumnBreak(cellCol)
                        ) {
                            removeColumnBreak(cellCol)
                            return@setOnKeyListener true
                        }
                        if (getInputMode() == InputMode.SCATTER) {
                            val curChar = getColumnChar(cellCol, cellRow)
                            if (curChar.isNotBlank()) {
                                // Island: backspace-delete with reflow, same as SEQUENTIAL.
                                deletePreviousSequentialCell(cellCol, cellRow, index)
                            } else {
                                handleScatterEmptyBackspace(cellCol, cellRow, index)
                            }
                        } else {
                            deletePreviousSequentialCell(cellCol, cellRow, index)
                        }
                    }
                }
            }
            true
        }
    }
}
