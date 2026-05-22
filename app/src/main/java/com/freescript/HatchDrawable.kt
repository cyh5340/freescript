package com.freescript

import android.graphics.*
import android.graphics.drawable.Drawable

/**
 * A tiled grey "/" hatch pattern drawable.
 * Used as a background to visually distinguish "outside the content area" from the
 * document background, regardless of what colour the document background is.
 */
class HatchDrawable(density: Float) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        val spacing = (16f * density).toInt().coerceAtLeast(4)
        val bmp = Bitmap.createBitmap(spacing, spacing, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(150, 150, 150))
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 100, 100)
            strokeWidth = (1.5f * density).coerceAtLeast(1f)
            strokeCap = Paint.Cap.SQUARE
        }
        c.drawLine(0f, spacing.toFloat(), spacing.toFloat(), 0f, linePaint)
        paint.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun draw(canvas: Canvas) = canvas.drawRect(bounds, paint)
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.OPAQUE
}
