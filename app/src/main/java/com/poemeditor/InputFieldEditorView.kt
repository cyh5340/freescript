package com.poemeditor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Full-screen overlay that lets the user drag the top and bottom edges to set
 * the input field height. Left/right always span the full width.
 */
class InputFieldEditorView(
    context: Context,
    initTop: Int,
    initBottom: Int
) : View(context) {

    var onConfirm: ((top: Int, bottom: Int) -> Unit)? = null
    var onCancel:  (() -> Unit)? = null

    private val dp = resources.displayMetrics.density

    private var cT = initTop.toFloat()
    private var cB = initBottom.toFloat()

    private val minSize = 60f * dp

    private val dimPaint        = Paint().apply { color = Color.argb(160, 0, 0, 0) }
    private val borderPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = (2f * dp).coerceAtLeast(2f)
        color       = Color.WHITE
    }
    private val handlePaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint       = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.WHITE
        textSize  = 15f * dp
        textAlign = Paint.Align.CENTER
        typeface  = Typeface.DEFAULT_BOLD
    }
    private val cancelBtnPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 60, 60, 60) }
    private val confirmBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 33, 150, 243) }

    // Pill handle dimensions (horizontal bar centered on each edge)
    private val pillHalfW = 40f * dp
    private val pillHalfH = 5f * dp
    private val touchR    = 40f * dp

    private val btnH = 44f * dp
    private val btnW = 90f * dp

    private val cancelRect  = RectF()
    private val confirmRect = RectF()

    // 0 = top handle, 1 = bottom handle, -1 = none
    private var activeHandle = -1
    private var dragSY = 0f
    private var dcT    = 0f
    private var dcB    = 0f

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        val cx = w / 2f
        val bY = h - btnH - 20f * dp
        cancelRect.set( cx - 8f * dp - btnW, bY, cx - 8f * dp,         bY + btnH)
        confirmRect.set(cx + 8f * dp,         bY, cx + 8f * dp + btnW, bY + btnH)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f

        // Dim above and below the field; interior is clear.
        canvas.drawRect(0f, 0f, w, cT, dimPaint)
        canvas.drawRect(0f, cB, w, h,  dimPaint)

        // Top and bottom border lines (full width).
        canvas.drawLine(0f, cT, w, cT, borderPaint)
        canvas.drawLine(0f, cB, w, cB, borderPaint)

        // Pill handles centered on each edge.
        val r = pillHalfH
        canvas.drawRoundRect(cx - pillHalfW, cT - pillHalfH, cx + pillHalfW, cT + pillHalfH, r, r, handlePaint)
        canvas.drawRoundRect(cx - pillHalfW, cB - pillHalfH, cx + pillHalfW, cB + pillHalfH, r, r, handlePaint)

        // Cancel / Confirm buttons.
        val br = 22f * dp
        canvas.drawRoundRect(cancelRect,  br, br, cancelBtnPaint)
        canvas.drawRoundRect(confirmRect, br, br, confirmBtnPaint)
        val ty = textPaint.textSize * 0.37f
        canvas.drawText(context.getString(R.string.screenshot_cancel),
            cancelRect.centerX(),  cancelRect.centerY()  + ty, textPaint)
        canvas.drawText(context.getString(R.string.screenshot_confirm),
            confirmRect.centerX(), confirmRect.centerY() + ty, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = hitHandle(y)
                if (activeHandle >= 0) {
                    dragSY = y; dcT = cT; dcB = cB
                    return true
                }
                if (cancelRect.contains(x, y))  { onCancel?.invoke(); return true }
                if (confirmRect.contains(x, y)) { onConfirm?.invoke(cT.toInt(), cB.toInt()); return true }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle < 0) return true
                val dy = y - dragSY; val maxH = height.toFloat()
                when (activeHandle) {
                    0 -> cT = (dcT + dy).coerceIn(0f, cB - minSize)
                    1 -> cB = (dcB + dy).coerceIn(cT + minSize, maxH)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activeHandle = -1
        }
        return true
    }

    private fun hitHandle(y: Float): Int {
        val dTop = abs(y - cT)
        val dBot = abs(y - cB)
        // Pick whichever edge is closer, as long as it's within touchR.
        return when {
            dTop <= touchR && dTop <= dBot -> 0
            dBot <= touchR                -> 1
            else                          -> -1
        }
    }
}
