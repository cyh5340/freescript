package com.poemeditor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaScannerConnection
import android.provider.MediaStore
import android.view.View
import android.widget.Toast

object ScreenshotHelper {

    fun captureView(root: View, height: Int): Bitmap? {
        val w = root.width
        if (w <= 0 || height <= 0) return null
        val bmp = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        return bmp
    }

    fun saveToGallery(context: Context, bmp: Bitmap) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "poemeditor_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PoemEditor")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return
                context.contentResolver.openOutputStream(uri)
                    ?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_PICTURES)
                val file = java.io.File(dir, "poemeditor_${System.currentTimeMillis()}.png")
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            }
            Toast.makeText(context, "已儲存至相簿", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "儲存失敗", Toast.LENGTH_SHORT).show()
        }
    }
}
