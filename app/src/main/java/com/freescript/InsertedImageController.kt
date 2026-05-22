package com.freescript

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Owns all inserted-image lifecycle, z-ordering, and avatar-panel rendering.
 * Extracted from MainActivity (Phase 6 Priority 1).
 *
 * Wire-up: in MainActivity.onCreate, instantiate this controller and delegate all
 * image-related calls to it. MainActivity keeps [insertedImages], [activeImageIndex],
 * [bgImageView], [bgImageUri], [bgImageMatrix] as shared state that the controller
 * reads and mutates via [Callbacks].
 */
class InsertedImageController(
    private val context: Context,
    private val cb: Callbacks
) {

    interface Callbacks {
        fun getRootFrame(): FrameLayout
        fun getMainScrollView(): View              // for default matrix height calculation
        fun getInsertImageContainer(): LinearLayout?
        fun getCurrentSessionId(): String

        // Shared mutable image state (controller reads and writes these)
        fun getInsertedImages(): MutableList<InsertedImageState>
        fun getActiveImageIndex(): Int
        fun setActiveImageIndex(index: Int)
        fun getBgImageUri(): String?
        fun setBgImageUri(uri: String?)
        fun getBgImageMatrix(): Matrix
        fun getBgImageViews(): MutableList<ImageView>
        fun setBgImageView(view: ImageView?)

        // For persistence hooks
        fun onImageStateChanged()   // calls pushHistory() + persistCurrentState()
        fun getMaxInsertedImages(): Int
    }

    // ── Bitmap loading ────────────────────────────────────────────────

    fun loadImageBitmap(uriStr: String): Bitmap? {
        val uri = try { Uri.parse(uriStr) } catch (_: Exception) { return null }
        return try {
            val stream = when (uri.scheme) {
                "file" -> java.io.FileInputStream(java.io.File(uri.path ?: return null))
                else   -> context.contentResolver.openInputStream(uri)
            } ?: return null
            stream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
    }

    fun defaultImageMatrixValues(imgW: Int, imgH: Int, index: Int): FloatArray {
        val metrics = context.resources.displayMetrics
        val rootFrame = cb.getRootFrame()
        val scrollView = cb.getMainScrollView()
        val viewW = rootFrame.width.toFloat().takeIf { it > 0f } ?: metrics.widthPixels.toFloat()
        val viewH = scrollView.height.toFloat().takeIf { it > 0f } ?: (metrics.heightPixels * 0.7f)
        val pad = 14f * metrics.density
        val gap = 10f * metrics.density
        val columns = 3
        val col = index % columns
        val row = index / columns
        val slotW = ((viewW - pad * 2f - gap * (columns - 1)) / columns).coerceAtLeast(1f)
        val slotH = (viewH * 0.32f).coerceAtLeast(1f)
        val scale = minOf(slotW / imgW, slotH / imgH).coerceAtLeast(0.05f)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(pad + col * (slotW + gap), pad + row * (slotH + gap))
        }
        return FloatArray(9).also { matrix.getValues(it) }
    }

    // ── Render / sync ─────────────────────────────────────────────────

    fun renderInsertedImages() {
        val rootFrame = cb.getRootFrame()
        val bgImageViews = cb.getBgImageViews()
        val insertedImages = cb.getInsertedImages()

        bgImageViews.forEach { rootFrame.removeView(it) }
        bgImageViews.clear()
        cb.setBgImageView(null)
        cb.setBgImageUri(null)
        cb.getBgImageMatrix().reset()

        if (insertedImages.isEmpty()) {
            cb.setActiveImageIndex(-1)
            return
        }

        val activeIdx = cb.getActiveImageIndex().coerceIn(0, insertedImages.lastIndex)
        cb.setActiveImageIndex(activeIdx)

        insertedImages.forEachIndexed { idx, state ->
            val bmp = loadImageBitmap(state.uri) ?: return@forEachIndexed
            val view = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.MATRIX
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageBitmap(bmp)
            }
            val matrixValues = state.matrix?.copyOf() ?: defaultImageMatrixValues(bmp.width, bmp.height, idx)
            val matrix = Matrix().apply { setValues(matrixValues) }
            view.imageMatrix = matrix

            rootFrame.addView(view, bgImageViews.size)
            bgImageViews.add(view)

            if (state.matrix == null && idx in insertedImages.indices) {
                insertedImages[idx] = InsertedImageState(state.uri, matrixValues.copyOf())
            }
            if (idx == activeIdx) {
                cb.setBgImageView(view)
                cb.setBgImageUri(state.uri)
                cb.getBgImageMatrix().setValues(matrixValues)
            }
        }

        if (cb.getBgImageUri() == null) cb.getBgImageMatrix().reset()
    }

    fun syncActiveImageFromList() = renderInsertedImages()

    // ── Active image management ───────────────────────────────────────

    fun updateActiveImageRuntimeState() {
        val idx = cb.getActiveImageIndex()
        val images = cb.getInsertedImages()
        if (idx !in images.indices) return
        val currentUri = cb.getBgImageUri() ?: return
        if (images[idx].uri != currentUri) return
        val vals = FloatArray(9).also { cb.getBgImageMatrix().getValues(it) }
        images[idx] = InsertedImageState(currentUri, vals)
    }

    fun getBgImageMatrixValues(): FloatArray? {
        if (cb.getBgImageUri() == null) return null
        return FloatArray(9).also { cb.getBgImageMatrix().getValues(it) }
    }

    fun activateImageAt(index: Int) {
        val images = cb.getInsertedImages()
        val bgImageViews = cb.getBgImageViews()
        if (index !in images.indices) return
        val view = bgImageViews.getOrNull(index) ?: return
        updateActiveImageRuntimeState()
        cb.setActiveImageIndex(index)
        cb.setBgImageView(view)
        cb.setBgImageUri(images[index].uri)
        cb.getBgImageMatrix().set(view.imageMatrix)
        if (bgImageViews.size > 1) {
            val rootFrame = cb.getRootFrame()
            rootFrame.removeView(view)
            rootFrame.addView(view, bgImageViews.size - 1)
        }
    }

    fun findTouchedImageIndex(rootX: Float, rootY: Float): Int {
        val images = cb.getInsertedImages()
        val bgImageViews = cb.getBgImageViews()
        if (images.isEmpty() || bgImageViews.isEmpty()) return -1
        val rootFrame = cb.getRootFrame()
        val ordered = bgImageViews.indices.sortedByDescending { rootFrame.indexOfChild(bgImageViews[it]) }
        for (idx in ordered) {
            val view = bgImageViews.getOrNull(idx) ?: continue
            if (view.visibility != View.VISIBLE) continue
            val drawable = view.drawable ?: continue
            val dw = drawable.intrinsicWidth.toFloat().takeIf { it > 0f } ?: continue
            val dh = drawable.intrinsicHeight.toFloat().takeIf { it > 0f } ?: continue
            val inverse = Matrix()
            if (!view.imageMatrix.invert(inverse)) continue
            val p = floatArrayOf(rootX, rootY)
            inverse.mapPoints(p)
            if (p[0] >= 0f && p[0] <= dw && p[1] >= 0f && p[1] <= dh) return idx
        }
        return -1
    }

    // ── Image store / select / remove ─────────────────────────────────

    fun storeInsertedImage(uri: Uri): String? {
        return try {
            val dir = java.io.File(context.filesDir, "backgrounds").also { it.mkdirs() }
            val file = java.io.File(dir, "${cb.getCurrentSessionId()}_${System.currentTimeMillis()}.bg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            Uri.fromFile(file).toString()
        } catch (_: Exception) { null }
    }

    fun selectInsertedImage(index: Int) {
        val images = cb.getInsertedImages()
        if (index !in images.indices || index == cb.getActiveImageIndex()) return
        updateActiveImageRuntimeState()
        cb.setActiveImageIndex(index)
        syncActiveImageFromList()
        refreshInsertedImagePanel()
        cb.onImageStateChanged()
    }

    fun removeInsertedImage(index: Int) {
        val images = cb.getInsertedImages()
        if (index !in images.indices) return
        images.removeAt(index)
        val newActive = if (images.isEmpty()) -1 else minOf(index, images.lastIndex)
        cb.setActiveImageIndex(newActive)
        syncActiveImageFromList()
        refreshInsertedImagePanel()
        cb.onImageStateChanged()
    }

    // ── Avatar panel ──────────────────────────────────────────────────

    fun refreshInsertedImagePanel() {
        val container = cb.getInsertImageContainer() ?: return
        val images = cb.getInsertedImages()
        val max = cb.getMaxInsertedImages()
        val dp = context.resources.displayMetrics.density

        container.removeAllViews()
        images.forEachIndexed { idx, img ->
            container.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = (6 * dp).roundToInt() }

                addView(ImageView(context).apply {
                    val avatarSize = (22 * dp).roundToInt()
                    layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageURI(Uri.parse(img.uri))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(
                            (if (idx == cb.getActiveImageIndex()) 2f else 1f * dp).roundToInt(),
                            context.getColor(if (idx == cb.getActiveImageIndex()) R.color.text_dark else R.color.stroke)
                        )
                    }
                    clipToOutline = true
                    contentDescription = "已插入圖片 ${idx + 1}"
                    setOnClickListener { selectInsertedImage(idx) }
                })

                addView(TextView(context).apply {
                    text = "×"
                    textSize = 10f
                    setTextColor(context.getColor(R.color.text_dark))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        (12 * dp).roundToInt(), (12 * dp).roundToInt()
                    ).also { it.marginStart = (2 * dp).roundToInt() }
                    setOnClickListener { removeInsertedImage(idx) }
                })
            })
        }
        container.addView(TextView(context).apply {
            text = "${images.size}/$max"
            textSize = 11f
            setTextColor(context.getColor(R.color.text_light))
        })
    }
}
