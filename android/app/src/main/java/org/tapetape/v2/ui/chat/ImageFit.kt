package org.tapetape.v2.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import org.tapetape.v2.Logx
import java.io.ByteArrayOutputStream
import java.io.File

fun fitImage(path: String, maxBytes: Long, out: File, startSide: Int = 1280): String {
    val src = File(path)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var side = startSide
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
    val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return path
    val rotated = rotate(decoded, exifDegrees(path))
    if (src.length() <= maxBytes && rotated === decoded && maxOf(bounds.outWidth, bounds.outHeight) <= side) return path
    while (true) {
        val scaled = scale(rotated, side)
        for (quality in intArrayOf(85, 75, 65, 55)) {
            val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            if (bytes.size() <= maxBytes || side <= 320) {
                out.writeBytes(bytes.toByteArray())
                Logx.d("image fit ${src.length()} -> ${bytes.size()} side=$side q=$quality")
                return out.path
            }
        }
        side = side * 3 / 4
    }
}

private fun rotate(b: Bitmap, degrees: Int): Bitmap =
    if (degrees == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

private fun scale(b: Bitmap, side: Int): Bitmap {
    val longest = maxOf(b.width, b.height)
    if (longest <= side) return b
    val f = side.toFloat() / longest
    return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
}

private fun exifDegrees(path: String) = when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
    ExifInterface.ORIENTATION_ROTATE_90 -> 90
    ExifInterface.ORIENTATION_ROTATE_180 -> 180
    ExifInterface.ORIENTATION_ROTATE_270 -> 270
    else -> 0
}
