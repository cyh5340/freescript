package com.freescript

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * Owns keyboard/tools-panel mutual exclusion and toolbar state.
 * Extracted from MainActivity (Phase 6 Priority 1).
 *
 * Wire-up: MainActivity creates this during onCreate and delegates:
 *   - switchToTools() / switchToKeyboard() calls
 *   - hideBottomForOverlay() calls (screenshot + input-field editor)
 *   - collapseToolsPanel() calls
 *   - updateKeyboardButton() calls
 *   - imeIsVisible / lastKeyboardHeight writes (from insets listener)
 */
class KeyboardToolbarController(
    private val context: Context,
    private val cb: Callbacks
) {

    interface Callbacks {
        fun getRootFrame(): FrameLayout
        fun getAllToolsPanel(): LinearLayout
        fun getBottomPanel(): LinearLayout
        fun getMainScrollView(): View
        fun getGhostInput(): EditText
        fun getToolsCell(): LinearLayout?
        fun getKeyboardCellRef(): LinearLayout?

        fun isToolsVisible(): Boolean
        fun setToolsVisible(value: Boolean)
        fun getLastKeyboardHeight(): Int
        fun isImeVisible(): Boolean

        fun getColorRowActive(): Int
        fun getColorTextHint(): Int
        fun getToolbarStripHeight(): Int  // toolbar + divider height, excluding punctToolbar
        fun getToolbarStripWidth(): Int   // left-panel width in landscape (px)
        fun isLandscape(): Boolean

        // Side-effects triggered by switching
        fun onBeforeSwitchToTools()     // e.g. clearSelection()
        fun onAfterSwitchToTools()      // e.g. refreshInsertedImagePanel()
    }

    // ── Main switching ────────────────────────────────────────────────

    fun switchToTools() {
        cb.onBeforeSwitchToTools()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(cb.getRootFrame().windowToken, 0)
        cb.onAfterSwitchToTools()

        val lp = cb.getAllToolsPanel().layoutParams as FrameLayout.LayoutParams
        if (cb.isLandscape()) {
            // Landscape: panel opens as a left sidebar to the right of the toolbar strip.
            // Use Gravity.LEFT|TOP explicitly — Gravity.START can resolve to the right side
            // when any view in the hierarchy has RTL layout direction (e.g. hScroll).
            val panelW = (280 * context.resources.displayMetrics.density).roundToInt()
            lp.width = panelW
            lp.height = -1  // MATCH_PARENT
            lp.gravity = Gravity.LEFT or Gravity.TOP
            lp.leftMargin = cb.getToolbarStripWidth()
            lp.topMargin = 0; lp.rightMargin = 0; lp.bottomMargin = 0
        } else {
            val h = if (cb.getLastKeyboardHeight() > 0) cb.getLastKeyboardHeight()
                    else (280 * context.resources.displayMetrics.density).roundToInt()
            lp.width = -1  // MATCH_PARENT
            lp.height = h
            lp.gravity = Gravity.BOTTOM
            lp.leftMargin = 0; lp.topMargin = 0; lp.rightMargin = 0
            lp.bottomMargin = cb.getToolbarStripHeight()
            (cb.getMainScrollView() as? androidx.core.widget.NestedScrollView)
                ?.setPadding(0, 0, 0, h)
                ?: cb.getMainScrollView().setPadding(0, 0, 0, h)
        }
        cb.getAllToolsPanel().layoutParams = lp
        cb.getAllToolsPanel().visibility = View.VISIBLE
        cb.setToolsVisible(true)
        cb.getToolsCell()?.setBackgroundColor(cb.getColorRowActive())
    }

    fun switchToKeyboard() {
        collapseToolsPanel()
        val h = cb.getLastKeyboardHeight().coerceAtLeast(0)
        (cb.getMainScrollView() as? androidx.core.widget.NestedScrollView)
            ?.setPadding(0, 0, 0, h)
            ?: cb.getMainScrollView().setPadding(0, 0, 0, h)
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        cb.getGhostInput().requestFocus()
        imm.showSoftInput(cb.getGhostInput(), InputMethodManager.SHOW_IMPLICIT)
    }

    fun collapseToolsPanel() {
        cb.getAllToolsPanel().visibility = View.GONE
        cb.setToolsVisible(false)
        cb.getToolsCell()?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    /**
     * Hides allToolsPanel + bottomPanel without touching toolsVisible.
     * Used by screenshot flow and input-field editor so restoration paths
     * can still check toolsVisible to decide whether to restore the panel.
     */
    fun hideBottomForOverlay() {
        if (cb.isToolsVisible()) cb.getAllToolsPanel().visibility = View.GONE
        cb.getBottomPanel().visibility = View.GONE
    }

    fun updateKeyboardButton() {
        val active = cb.isImeVisible() && !cb.isToolsVisible()
        cb.getKeyboardCellRef()?.setBackgroundColor(
            if (active) cb.getColorRowActive() else android.graphics.Color.TRANSPARENT
        )
    }
}
