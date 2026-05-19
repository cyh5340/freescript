package com.poemeditor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Full-screen overlay that lets the user drag the field edges to resize.
 *
 * Modes:
 *   fourEdge=false, leftRightOnly=false  → VERTICAL: top/bottom handles only
 *   fourEdge=false, leftRightOnly=true   → HORIZONTAL: left/right handles only
 *   fourEdge=true                        → FREESTYLE box: all four edges draggable
 *
 * scaledContentRect: when provided, dim is clipped to this rect so the hatch
 * background behind the overlay shows through outside it.  A white border is
 * drawn at the rect boundary to make the "screen edge" explicit.
 *
 * Cancel / Confirm buttons are provided externally by the caller (MainActivity).
 */
class InputFieldEditorView(
    context: Context,
    initTop: Int,
    initBottom: Int,
    initLeft: Int = 0,
    initRight: Int = 0,
    private val fourEdge: Boolean = false,
    private val leftRightOnly: Boolean = false,
    private val scaledContentRect: Rect? = null
) : View(context) {

    private val dp = resources.displayMetrics.density

    private var cT = initTop.toFloat()
    private var cB = initBottom.toFloat()
    private var cL = initLeft.toFloat()
    private var cR = initRight.toFloat()

    val selectedLeft:   Int get() = cL.toInt()
    val selectedTop:    Int get() = cT.toInt()
    val selectedRight:  Int get() = cR.toInt()
    val selectedBottom: Int get() = cB.toInt()

    /** Called on every drag move with current (top, bottom, left, right) in view coordinates. */
    var onDrag: ((top: Int, bottom: Int, left: Int, right: Int) -> Unit)? = null

    private val minSize = 60f * dp

    private val dimPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = (2f * dp).coerceAtLeast(2f)
        color       = Color.WHITE
    }
    private val scaledBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = (3f * dp).coerceAtLeast(3f)
        color       = Color.WHITE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val pillHalfW = 40f * dp
    private val pillHalfH = 5f * dp
    private val touchR    = 40f * dp

    private var activeHandle = -1
    private var dragSX = 0f; private var dragSY = 0f
    private var dcT = 0f; private var dcB = 0f; private var dcL = 0f; private var dcR = 0f

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        when {
            leftRightOnly -> { if (cR == 0f) cR = w.toFloat() }
            !fourEdge     -> { cL = 0f; cR = w.toFloat() }
            else          -> { if (cR == 0f) cR = w.toFloat() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()

        // Clip dim drawing to scaledContentRect when provided so the hatch behind
        // the overlay is visible outside the "screen preview" boundary.
        val hasClip = scaledContentRect != null
        if (hasClip) {
            canvas.save()
            canvas.clipRect(scaledContentRect!!)
        }

        if (leftRightOnly) {
            canvas.drawRect(0f, 0f, w,   cT, dimPaint)
            canvas.drawRect(0f, cB, w,   h,  dimPaint)
            canvas.drawRect(0f, cT, cL,  cB, dimPaint)
            canvas.drawRect(cR, cT, w,   cB, dimPaint)
            canvas.drawLine(cL, cT, cR, cT, borderPaint)
            canvas.drawLine(cL, cB, cR, cB, borderPaint)
            canvas.drawLine(cL, cT, cL, cB, borderPaint)
            canvas.drawLine(cR, cT, cR, cB, borderPaint)
            val midY = (cT + cB) / 2f; val pr = pillHalfH
            canvas.drawRoundRect(cL - pillHalfH, midY - pillHalfW, cL + pillHalfH, midY + pillHalfW, pr, pr, handlePaint)
            canvas.drawRoundRect(cR - pillHalfH, midY - pillHalfW, cR + pillHalfH, midY + pillHalfW, pr, pr, handlePaint)
        } else {
            canvas.drawRect(0f, 0f,  w,  cT, dimPaint)
            canvas.drawRect(0f, cB,  w,  h,  dimPaint)
            if (fourEdge) {
                canvas.drawRect(0f, cT, cL, cB, dimPaint)
                canvas.drawRect(cR, cT, w,  cB, dimPaint)
            }
            canvas.drawLine(cL, cT, cR, cT, borderPaint)
            canvas.drawLine(cL, cB, cR, cB, borderPaint)
            if (fourEdge) {
                canvas.drawLine(cL, cT, cL, cB, borderPaint)
                canvas.drawLine(cR, cT, cR, cB, borderPaint)
            } else {
                canvas.drawLine(0f, cT, w, cT, borderPaint)
                canvas.drawLine(0f, cB, w, cB, borderPaint)
            }
            val midX = if (fourEdge) (cL + cR) / 2f else w / 2f
            val midY = (cT + cB) / 2f; val pr = pillHalfH
            canvas.drawRoundRect(midX - pillHalfW, cT - pillHalfH, midX + pillHalfW, cT + pillHalfH, pr, pr, handlePaint)
            canvas.drawRoundRect(midX - pillHalfW, cB - pillHalfH, midX + pillHalfW, cB + pillHalfH, pr, pr, handlePaint)
            if (fourEdge) {
                canvas.drawRoundRect(cL - pillHalfH, midY - pillHalfW, cL + pillHalfH, midY + pillHalfW, pr, pr, handlePaint)
                canvas.drawRoundRect(cR - pillHalfH, midY - pillHalfW, cR + pillHalfH, midY + pillHalfW, pr, pr, handlePaint)
            }
        }

        if (hasClip) {
            canvas.restore()
            // Draw the "screen preview" border outside the clip so it's always visible.
            val r = scaledContentRect!!
            canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), scaledBorderPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = hitHandle(x, y)
                if (activeHandle >= 0) {
                    dragSX = x; dragSY = y
                    dcT = cT; dcB = cB; dcL = cL; dcR = cR
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle < 0) return true
                val dx = x - dragSX; val dy = y - dragSY
                val maxH = height.toFloat(); val maxW = width.toFloat()
                when (activeHandle) {
                    0 -> cT = (dcT + dy).coerceIn(0f, cB - minSize)
                    1 -> cB = (dcB + dy).coerceIn(cT + minSize, maxH)
                    2 -> cL = (dcL + dx).coerceIn(0f, cR - minSize)
                    3 -> cR = (dcR + dx).coerceIn(cL + minSize, maxW)
                }
                invalidate()
                onDrag?.invoke(cT.toInt(), cB.toInt(), cL.toInt(), cR.toInt())
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activeHandle = -1
        }
        return true
    }

    private fun hitHandle(x: Float, y: Float): Int {
        if (leftRightOnly) {
            val dLeft = abs(x - cL); val dRight = abs(x - cR)
            return when {
                dLeft  <= touchR && dLeft <= dRight -> 2
                dRight <= touchR                    -> 3
                else                                -> -1
            }
        }
        val midX = if (fourEdge) (cL + cR) / 2f else width / 2f
        val midY = (cT + cB) / 2f
        val dTop = abs(y - cT); val dBot = abs(y - cB)
        if (fourEdge) {
            val dLeft = abs(x - cL); val dRight = abs(x - cR)
            val minD = minOf(dTop, dBot, dLeft, dRight)
            return when {
                minD > touchR                                          -> -1
                minD == dTop  && abs(x - midX) <= pillHalfW + touchR -> 0
                minD == dBot  && abs(x - midX) <= pillHalfW + touchR -> 1
                minD == dLeft && abs(y - midY) <= pillHalfW + touchR -> 2
                minD == dRight&& abs(y - midY) <= pillHalfW + touchR -> 3
                minD == dTop  -> 0; minD == dBot -> 1; minD == dLeft -> 2; else -> 3
            }
        }
        return when {
            dTop <= touchR && dTop <= dBot -> 0
            dBot <= touchR                -> 1
            else                          -> -1
        }
    }
}
