package com.freescript

import android.content.res.Resources
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.widget.HorizontalScrollView
import androidx.core.widget.NestedScrollView

/**
 * Owns viewport/caret scroll coordination and keyboard-time caret visibility.
 * Extracted from MainActivity (Wave 1).
 */
class ScrollCoordinator(
    private val resources: Resources,
    private val cb: Callbacks
) {
    interface Callbacks {
        fun getCanvasMode(): CanvasMode
        fun getCurrentCellSize(): Int
        fun getNumColumns(): Int
        fun getFocusedCellIndex(): Int
        fun getPoemCanvas(): PoemCanvasView
        fun getMainScrollView(): NestedScrollView
        fun getHScrollView(): HorizontalScrollView?
    }

    private val translateHandler = Handler(Looper.getMainLooper())
    private var translateRunnable: Runnable? = null

    fun scrollToColumn(col: Int) {
        if (cb.getCanvasMode() == CanvasMode.HORIZONTAL) {
            // In horizontal mode col = line; scroll mainScrollView vertically to show the line.
            val cellSize = cb.getCurrentCellSize()
            if (cellSize <= 0) return
            val mainScrollView = cb.getMainScrollView()
            val lineTop = col * cellSize
            val lineBottom = lineTop + cellSize
            val sv = mainScrollView.scrollY
            val vp = mainScrollView.height
            when {
                lineTop < sv -> mainScrollView.smoothScrollTo(0, lineTop)
                lineBottom > sv + vp -> mainScrollView.smoothScrollTo(0, lineBottom - vp)
            }
            return
        }

        val s = cb.getHScrollView() ?: return
        val cellSize = cb.getCurrentCellSize()
        val numColumns = cb.getNumColumns()
        if (cellSize <= 0 || s.width <= 0 || numColumns <= 0) return
        val gw = maxOf(numColumns * cellSize, s.width)
        val physLeft = gw - (col + 1) * cellSize
        val physRight = gw - col * cellSize
        val sx = s.scrollX
        val vp = s.width
        when {
            physLeft < sx -> s.smoothScrollTo(physLeft, 0)
            physRight > sx + vp -> s.smoothScrollTo(physRight - vp, 0)
        }
    }

    fun scrollToTopIfCursorInUpperView(index: Int) {
        val poemCanvas = cb.getPoemCanvas()
        val mainScrollView = cb.getMainScrollView()
        val cellRect = poemCanvas.cellRect(index.coerceAtLeast(0)) ?: return

        val canvasLoc = IntArray(2)
        poemCanvas.getLocationOnScreen(canvasLoc)
        val scrollLoc = IntArray(2)
        mainScrollView.getLocationOnScreen(scrollLoc)
        val cellTopInView = canvasLoc[1] + cellRect.top - scrollLoc[1]
        if (cellTopInView <= mainScrollView.height / 2) {
            mainScrollView.post { mainScrollView.scrollTo(0, 0) }
        }
    }

    fun scheduleTranslateForKeyboard() {
        if (cb.getFocusedCellIndex() < 0) return
        translateRunnable?.let { translateHandler.removeCallbacks(it) }
        translateRunnable = Runnable { translateForKeyboard() }
            .also { translateHandler.postDelayed(it, 100) }
    }

    fun translateForKeyboard(retry: Int = 0) {
        if (cb.getFocusedCellIndex() < 0) return
        val mainScrollView = cb.getMainScrollView()
        val poemCanvas = cb.getPoemCanvas()

        mainScrollView.post {
            val focusedIndex = cb.getFocusedCellIndex()
            if (focusedIndex < 0) return@post
            val cellRect = poemCanvas.cellRect(focusedIndex) ?: return@post
            val margin = (16 * resources.displayMetrics.density).toInt()
            val targetRect = Rect(cellRect).apply { bottom += margin }
            mainScrollView.offsetDescendantRectToMyCoords(poemCanvas, targetRect)

            val visibleTop = mainScrollView.scrollY
            val visibleBottom = visibleTop + mainScrollView.height
            val delta = when {
                targetRect.bottom > visibleBottom -> targetRect.bottom - visibleBottom
                targetRect.top < visibleTop -> targetRect.top - visibleTop
                else -> 0
            }
            if (delta != 0) {
                mainScrollView.smoothScrollBy(0, delta)
            }

            if (retry < 4) {
                translateHandler.postDelayed({ translateForKeyboard(retry + 1) }, 60)
            }
        }
    }
}
