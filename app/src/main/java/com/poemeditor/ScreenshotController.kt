package com.poemeditor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.widget.TextView
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.math.hypot

/**
 * Owns the entire screenshot flow:
 *   takeScreenshot() → (dismiss keyboard if needed) → showCropUI() → capture → save → restore
 *
 * MainActivity provides views and state via Callbacks; no logic lives in MainActivity.
 */
class ScreenshotController(private val context: Context, private val cb: Callbacks) {

    interface Callbacks {
        fun getRootFrame(): FrameLayout
        fun getMainScrollView(): View
        fun getBottomPanel(): View
        fun getGridContainer(): View
        fun getPoemCanvas(): PoemCanvasView
        fun getScrollIndicatorContainer(): View?
        /** startHandle, endHandle, selectionOptionsView, handlePasteView */
        fun getTransientViews(): List<View?>
        fun getAllToolsPanel(): View?
        fun isToolsVisible(): Boolean
        /** Hide allToolsPanel (if open) and bottomPanel for a full-screen overlay. */
        fun hideBottomForOverlay()
        fun getNumRows(): Int
        fun getCurrentCellSize(): Int
        /** Restore selection handles, scroll indicator, tools panel, cursor blink. */
        fun onScreenshotRestored()
    }

    private val MP = ViewGroup.LayoutParams.MATCH_PARENT

    fun takeScreenshot() {
        cb.getTransientViews().forEach { it?.visibility = View.GONE }
        cb.getScrollIndicatorContainer()?.visibility = View.GONE
        cb.getPoemCanvas().stopCursorBlink()

        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val keyboardWasOpen = imm.isAcceptingText

        fun showCropUI() {
            val rootFrame = cb.getRootFrame()
            cb.getMainScrollView().scrollTo(0, 0)
            cb.getMainScrollView().setPadding(0, 0, 0, 0)
            cb.hideBottomForOverlay()

            val rootLoc = IntArray(2).also { rootFrame.getLocationInWindow(it) }
            val gridLoc = IntArray(2).also { cb.getGridContainer().getLocationInWindow(it) }
            val gap     = cb.getGridContainer().paddingTop
            val initTop    = gridLoc[1] - rootLoc[1]
            val initBottom = initTop + gap + cb.getNumRows() * cb.getCurrentCellSize() + gap

            val poemCanvas = cb.getPoemCanvas()
            val cropView = ScreenshotCropView(context, 0, initTop, rootFrame.width, initBottom)
            cropView.layoutParams = FrameLayout.LayoutParams(MP, MP)

            // Apply initial state (default: show markers)
            poemCanvas.hideLineEndMarkers = !cropView.showLineEndMarkers

            // Live-update the poem canvas whenever the checkbox is toggled
            cropView.onLineEndMarkersChanged = { show ->
                poemCanvas.hideLineEndMarkers = !show
                poemCanvas.invalidate()
            }

            cropView.onConfirm = { cropL, cropT, cropW, cropH ->
                cropView.visibility = View.INVISIBLE
                rootFrame.post {
                    val bmp = captureView(rootFrame, cropL, cropT, cropW, cropH)
                    poemCanvas.hideLineEndMarkers = false
                    rootFrame.removeView(cropView)
                    if (bmp != null) {
                        val indicator = createSavingIndicator()
                        rootFrame.addView(indicator)
                        saveToGallery(bmp) {
                            rootFrame.removeView(indicator)
                            cb.onScreenshotRestored()
                        }
                    } else {
                        cb.onScreenshotRestored()
                    }
                }
            }
            cropView.onCancel = {
                poemCanvas.hideLineEndMarkers = false
                rootFrame.removeView(cropView)
                cb.onScreenshotRestored()
            }
            rootFrame.addView(cropView)
        }

        if (keyboardWasOpen) {
            imm.hideSoftInputFromWindow(cb.getRootFrame().windowToken, 0)
            cb.getRootFrame().viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        cb.getRootFrame().viewTreeObserver.removeOnGlobalLayoutListener(this)
                        showCropUI()
                    }
                }
            )
        } else {
            showCropUI()
        }
    }

    private fun captureView(root: FrameLayout, cropLeft: Int, cropTop: Int, cropWidth: Int, cropHeight: Int): Bitmap? {
        if (cropWidth <= 0 || cropHeight <= 0) return null
        val bmp = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { translate(-cropLeft.toFloat(), -cropTop.toFloat()) }.let { root.draw(it) }
        return bmp
    }

    private fun saveToGallery(bmp: Bitmap, onComplete: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        try {
            val name = "poemeditor_${System.currentTimeMillis()}.png"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: run { onComplete(); return }
                context.contentResolver.openOutputStream(uri)
                    ?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Toast.makeText(context, context.getString(R.string.screenshot_saved), Toast.LENGTH_SHORT).show()
                onComplete()
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val file = java.io.File(dir, name)
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                // Toast and restore only after the media scanner confirms the file is indexed
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, _ ->
                    main.post {
                        Toast.makeText(context, context.getString(R.string.screenshot_saved), Toast.LENGTH_SHORT).show()
                        onComplete()
                    }
                }
            }
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.screenshot_failed), Toast.LENGTH_SHORT).show()
            onComplete()
        }
    }

    private fun createSavingIndicator(): View {
        val dp = context.resources.displayMetrics.density
        val bg = GradientDrawable().apply {
            setColor(Color.argb(210, 30, 30, 30))
            cornerRadius = 16f * dp
        }
        return TextView(context).apply {
            text = context.getString(R.string.screenshot_saving)
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            background = bg
            val padH = (24 * dp).toInt(); val padV = (12 * dp).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
    }
}

/**
 * Full-screen overlay that lets the user drag four corner handles to choose
 * a capture region, then confirm or cancel.
 *
 * The selected area shows the live content through a dim surround.
 * Corner coordinates are in rootFrame pixel space (1-to-1 with View.draw output).
 */
class ScreenshotCropView(
    context: Context,
    initLeft:   Int,
    initTop:    Int,
    initRight:  Int,
    initBottom: Int
) : View(context) {

    /** Called with the crop rect in rootFrame pixel coordinates. */
    var onConfirm: ((left: Int, top: Int, width: Int, height: Int) -> Unit)? = null
    var onCancel:  (() -> Unit)? = null

    /** Whether the ↵ line-end marker should appear in the captured image. */
    var showLineEndMarkers = true

    private val labelCancel  = context.getString(R.string.screenshot_cancel)
    private val labelConfirm = context.getString(R.string.screenshot_confirm)

    /** Called immediately when the checkbox is toggled, so the caller can update the live view. */
    var onLineEndMarkersChanged: ((Boolean) -> Unit)? = null

    private val dp = resources.displayMetrics.density

    // Crop rect (rootFrame / view coordinates)
    private var cL = initLeft.toFloat()
    private var cT = initTop.toFloat()
    private var cR = initRight.toFloat()
    private var cB = initBottom.toFloat()

    private val minSize = 40f * dp

    // ── Paints ────────────────────────────────────────────────────────────
    private val dimPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (2f * dp).coerceAtLeast(2f)
        color = Color.WHITE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * dp
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val cancelBtnPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 60, 60, 60) }
    private val confirmBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 33, 150, 243) }
    private val checkboxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 33, 150, 243) }
    private val checkboxStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (2f * dp).coerceAtLeast(2f)
        color = Color.WHITE
    }

    private val handleR = 14f * dp
    private val touchR  = 36f * dp

    private val btnH = 44f * dp
    private val btnW = 90f * dp
    private val cancelRect  = RectF()
    private val confirmRect = RectF()

    private val cbSize = 20f * dp
    private val checkboxRect    = RectF()   // visual square
    private val checkboxTouchRect = RectF() // enlarged hit area (includes ↵ label)

    // ── Drag state ────────────────────────────────────────────────────────
    private var activeHandle = -1
    private var dragSX = 0f; private var dragSY = 0f
    private var dcL = 0f; private var dcT = 0f; private var dcR = 0f; private var dcB = 0f

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        val cx  = w / 2f
        val bY  = h - btnH - 20f * dp

        // Widen the centre gap to fit [□ ↵] between the two buttons
        val symbolW = textPaint.measureText("↵")
        val centerRowW = cbSize + 8f * dp + symbolW   // checkbox + gap + symbol
        val sideGap = centerRowW / 2f + 12f * dp      // half of center element + breathing room
        cancelRect.set( cx - sideGap - btnW, bY, cx - sideGap, bY + btnH)
        confirmRect.set(cx + sideGap,        bY, cx + sideGap + btnW, bY + btnH)

        // Checkbox vertically centred on the button row
        val cbLeft = cx - centerRowW / 2f
        val cbTop  = bY + (btnH - cbSize) / 2f
        checkboxRect.set(cbLeft, cbTop, cbLeft + cbSize, cbTop + cbSize)
        // Touch area spans the full centre gap height
        checkboxTouchRect.set(cx - sideGap + 4f * dp, bY,
                              cx + sideGap - 4f * dp, bY + btnH)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()

        // Dim the four sides surrounding the crop rect
        canvas.drawRect(0f, 0f, w,  cT, dimPaint)
        canvas.drawRect(0f, cB, w,  h,  dimPaint)
        canvas.drawRect(0f, cT, cL, cB, dimPaint)
        canvas.drawRect(cR, cT, w,  cB, dimPaint)

        // Crop border
        canvas.drawRect(cL, cT, cR, cB, borderPaint)

        // Corner handles
        canvas.drawCircle(cL, cT, handleR, handlePaint)
        canvas.drawCircle(cR, cT, handleR, handlePaint)
        canvas.drawCircle(cL, cB, handleR, handlePaint)
        canvas.drawCircle(cR, cB, handleR, handlePaint)

        // Buttons
        val br = 22f * dp
        canvas.drawRoundRect(cancelRect,  br, br, cancelBtnPaint)
        canvas.drawRoundRect(confirmRect, br, br, confirmBtnPaint)
        val ty = textPaint.textSize * 0.37f
        canvas.drawText(labelCancel,  cancelRect.centerX(),  cancelRect.centerY()  + ty, textPaint)
        canvas.drawText(labelConfirm, confirmRect.centerX(), confirmRect.centerY() + ty, textPaint)

        // Checkbox + ↵ label, inline between the two buttons
        val cbR = 3f * dp
        if (showLineEndMarkers) {
            canvas.drawRoundRect(checkboxRect, cbR, cbR, checkboxFillPaint)
        }
        canvas.drawRoundRect(checkboxRect, cbR, cbR, checkboxStrokePaint)
        val symbolX = checkboxRect.right + 8f * dp + textPaint.measureText("↵") / 2f
        val symbolY = checkboxRect.centerY() + textPaint.textSize * 0.37f
        canvas.drawText("↵", symbolX, symbolY, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = hitHandle(x, y)
                if (activeHandle >= 0) {
                    dragSX = x; dragSY = y
                    dcL = cL; dcT = cT; dcR = cR; dcB = cB
                    return true
                }
                if (cancelRect.contains(x, y))  { onCancel?.invoke(); return true }
                if (confirmRect.contains(x, y)) {
                    onConfirm?.invoke(cL.toInt(), cT.toInt(), (cR - cL).toInt(), (cB - cT).toInt())
                    return true
                }
                if (checkboxTouchRect.contains(x, y)) {
                    showLineEndMarkers = !showLineEndMarkers
                    onLineEndMarkersChanged?.invoke(showLineEndMarkers)
                    invalidate()
                    return true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle < 0) return true
                val dx = x - dragSX; val dy = y - dragSY
                val maxW = width.toFloat(); val maxH = height.toFloat()
                when (activeHandle) {
                    0 -> { cL = (dcL + dx).coerceIn(0f, cR - minSize); cT = (dcT + dy).coerceIn(0f, cB - minSize) }
                    1 -> { cR = (dcR + dx).coerceIn(cL + minSize, maxW); cT = (dcT + dy).coerceIn(0f, cB - minSize) }
                    2 -> { cL = (dcL + dx).coerceIn(0f, cR - minSize); cB = (dcB + dy).coerceIn(cT + minSize, maxH) }
                    3 -> { cR = (dcR + dx).coerceIn(cL + minSize, maxW); cB = (dcB + dy).coerceIn(cT + minSize, maxH) }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activeHandle = -1
        }
        return true
    }

    private fun hitHandle(x: Float, y: Float): Int {
        val pts = arrayOf(cL to cT, cR to cT, cL to cB, cR to cB)
        return pts.indexOfFirst { (hx, hy) -> hypot(x - hx, y - hy) <= touchR }
    }
}
