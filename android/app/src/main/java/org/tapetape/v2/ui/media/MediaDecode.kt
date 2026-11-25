package org.tapetape.v2.ui.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import org.tapetape.v2.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class MediaItem(val id: Int, val path: String, val mime: String, val sender: String, val time: Long)

internal fun isVideo(mime: String) = mime.startsWith("video/")

internal fun isGif(mime: String) = mime == "image/gif"

internal class Thumb(val bitmap: Bitmap?, val durationMs: Long)

internal object MediaDecode {
    private val cache = object : LruCache<String, Thumb>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Thumb) = (value.bitmap?.allocationByteCount ?: 0) / 1024 + 1
    }

    fun cached(path: String, maxSide: Int): Thumb? = cache.get("$path#$maxSide")

    suspend fun thumb(path: String, mime: String, maxSide: Int): Thumb = withContext(Dispatchers.IO) {
        val key = "$path#$maxSide"
        cache.get(key) ?: run {
            val start = System.currentTimeMillis()
            val thumb = runCatching {
                if (isVideo(mime)) videoFrame(path, maxSide) else Thumb(image(path, maxSide), 0)
            }.getOrElse {
                Logx.w("thumb failed $path", it)
                Thumb(null, 0)
            }
            Logx.d("thumb $path ${thumb.bitmap?.width}x${thumb.bitmap?.height} in ${System.currentTimeMillis() - start}ms")
            cache.put(key, thumb)
            thumb
        }
    }

    suspend fun full(path: String, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { image(path, maxSide) }.getOrElse {
            Logx.w("decode failed $path", it)
            null
        }
    }

    suspend fun animated(path: String, maxSide: Int): Drawable? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 28) return@withContext null
        runCatching {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
                decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, maxSide))
            }
        }.getOrElse {
            Logx.w("gif decode failed $path", it)
            null
        }
    }

    private fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    private fun image(path: String, maxSide: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
                decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, maxSide))
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        val bitmap = BitmapFactory.decodeFile(path, opts) ?: return null
        val degrees = runCatching { ExifInterface(path).rotationDegrees }.getOrDefault(0)
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private val ExifInterface.rotationDegrees: Int
        get() = when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private fun videoFrame(path: String, maxSide: Int): Thumb {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            return Thumb(frame?.let { scaleDown(it, maxSide) }, duration)
        } finally {
            retriever.release()
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxSide: Int): Bitmap {
        val side = maxOf(bitmap.width, bitmap.height)
        if (side <= maxSide) return bitmap
        val ratio = maxSide.toFloat() / side
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    }
}

internal fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
