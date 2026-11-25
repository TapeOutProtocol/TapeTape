package org.tapetape.v2.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import org.tapetape.v2.Logx
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

const val MAX_SIDE = 2560

enum class Tool { None, Crop, Marker, Highlighter, Text, Blur, Sticker }

enum class Pen { Marker, Highlighter, Blur }

data class Stroke(val pen: Pen, val colorPos: Float, val width: Float, val points: List<Offset>)

data class Overlay(
    val id: Long,
    val text: String,
    val sticker: String?,
    val colorPos: Float,
    val center: Offset,
    val scale: Float,
    val rotation: Float,
    val mirrored: Boolean,
)

data class EditState(
    val rotation: Int,
    val flipped: Boolean,
    val crop: Rect,
    val strokes: List<Stroke>,
    val overlays: List<Overlay>,
)

private val palette = intArrayOf(
    0xFFFFFFFF.toInt(), 0xFFFF1744.toInt(), 0xFFFFEA00.toInt(), 0xFF00E676.toInt(),
    0xFF00E5FF.toInt(), 0xFF2979FF.toInt(), 0xFFD500F9.toInt(), 0xFF000000.toInt(),
)

val paletteColors = palette.map { Color(it) }

fun colorAt(pos: Float): Int {
    val x = pos.coerceIn(0f, 1f) * (palette.size - 1)
    val i = x.toInt().coerceAtMost(palette.size - 2)
    return ColorUtils.blendARGB(palette[i], palette[i + 1], x - i)
}

fun orientation(s: EditState, w: Float, h: Float): Matrix {
    val m = Matrix()
    if (s.flipped) m.postScale(-1f, 1f, w / 2, h / 2)
    m.postRotate(s.rotation.toFloat(), w / 2, h / 2)
    val r = RectF(0f, 0f, w, h).also { m.mapRect(it) }
    m.postTranslate(-r.left, -r.top)
    return m
}

fun Rect.toRectF() = RectF(left, top, right, bottom)

fun RectF.toRect() = Rect(left, top, right, bottom)

fun Matrix.map(p: Offset): Offset {
    val a = floatArrayOf(p.x, p.y)
    mapPoints(a)
    return Offset(a[0], a[1])
}

fun Matrix.mapVector(v: Offset): Offset {
    val a = floatArrayOf(v.x, v.y)
    mapVectors(a)
    return Offset(a[0], a[1])
}

fun Matrix.inverted() = Matrix().also { invert(it) }

class Scene(val bitmap: Bitmap) {
    val width = bitmap.width.toFloat()
    val height = bitmap.height.toFloat()
    private val stickers = HashMap<String, Bitmap>()
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isFilterBitmap = false
        val block = max(width, height) / 48f
        val tw = max(1, (width / block).roundToInt())
        val th = max(1, (height / block).roundToInt())
        var small = bitmap
        while (small.width / 2 >= tw && small.height / 2 >= th) small = Bitmap.createScaledBitmap(small, small.width / 2, small.height / 2, true)
        small = Bitmap.createScaledBitmap(small, tw, th, true)
        shader = BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(width / small.width, height / small.height) })
        }
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = BASE
        setShadowLayer(6f, 0f, 2f, 0x66000000)
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCCFFFFFF.toInt()
    }

    /** Stickers are emoji drawn by the system emoji font. */
    fun sticker(emoji: String): Bitmap = stickers.getOrPut(emoji) { emojiBitmap(emoji) }

    private fun halfSize(o: Overlay): Pair<Float, Float> {
        if (o.sticker != null) return BASE / 2 to BASE / 2
        val lines = o.text.split('\n')
        val w = lines.maxOf { textPaint.measureText(it) }
        return w / 2 + PAD to lines.size * textPaint.fontSpacing / 2 + PAD
    }

    fun hit(o: Overlay, p: Offset): Boolean {
        val d = p - o.center
        val a = Math.toRadians(-o.rotation.toDouble())
        val x = (d.x * cos(a) - d.y * sin(a)) / o.scale
        val y = (d.x * sin(a) + d.y * cos(a)) / o.scale
        val (hw, hh) = halfSize(o)
        return x in -hw..hw && y in -hh..hh
    }

    fun draw(canvas: Canvas, s: EditState, live: Stroke?, selected: Long?, outline: Float) {
        canvas.drawBitmap(bitmap, 0f, 0f, imagePaint)
        val strokes = if (live == null) s.strokes else s.strokes + live
        strokes.filter { it.pen == Pen.Blur }.forEach { drawStroke(canvas, it) }
        strokes.filter { it.pen != Pen.Blur }.forEach { drawStroke(canvas, it) }
        s.overlays.forEach { drawOverlay(canvas, it, if (it.id == selected) outline else 0f) }
    }

    private fun drawStroke(canvas: Canvas, st: Stroke) {
        val paint = if (st.pen == Pen.Blur) blurPaint else strokePaint.apply {
            color = colorAt(st.colorPos)
            if (st.pen == Pen.Highlighter) alpha = 0x77
        }
        paint.strokeWidth = st.width
        canvas.drawPath(path(st.points), paint)
    }

    private fun drawOverlay(canvas: Canvas, o: Overlay, outline: Float) {
        canvas.save()
        canvas.translate(o.center.x, o.center.y)
        canvas.rotate(o.rotation)
        canvas.scale(if (o.mirrored) -o.scale else o.scale, o.scale)
        if (o.sticker != null) {
            canvas.drawBitmap(sticker(o.sticker), null, RectF(-BASE / 2, -BASE / 2, BASE / 2, BASE / 2), imagePaint)
        } else {
            textPaint.color = colorAt(o.colorPos)
            val lines = o.text.split('\n')
            val top = -lines.size * textPaint.fontSpacing / 2 - textPaint.fontMetrics.ascent
            lines.forEachIndexed { i, line -> canvas.drawText(line, 0f, top + i * textPaint.fontSpacing, textPaint) }
        }
        if (outline > 0f) {
            val (hw, hh) = halfSize(o)
            outlinePaint.strokeWidth = outline / o.scale
            canvas.drawRoundRect(-hw, -hh, hw, hh, PAD, PAD, outlinePaint)
        }
        canvas.restore()
    }

    private fun path(points: List<Offset>): Path {
        val p = Path()
        val first = points.first()
        p.moveTo(first.x, first.y)
        if (points.size == 1) {
            p.lineTo(first.x + 0.1f, first.y)
            return p
        }
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            p.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
        }
        points.last().let { p.lineTo(it.x, it.y) }
        return p
    }

    companion object {
        const val BASE = 100f
        const val PAD = 12f
    }
}

class EditorController(val scene: Scene) {
    var state by mutableStateOf(EditState(0, false, Rect(0f, 0f, scene.width, scene.height), emptyList(), emptyList()))
        private set
    private val history = mutableStateListOf<EditState>()
    private var gestureStart: EditState? = null
    private var nextId = 1L

    var tool by mutableStateOf(Tool.None)
    var colorPos by mutableFloatStateOf(0f)
    var widthLevel by mutableIntStateOf(1)
    var selected by mutableStateOf<Long?>(null)
    var live by mutableStateOf<Stroke?>(null)
    var editing by mutableStateOf<Overlay?>(null)
    var viewScale = 1f

    val canUndo get() = history.isNotEmpty()
    val selectedOverlay get() = state.overlays.find { it.id == selected }

    fun push(new: EditState) {
        if (new == state) return
        history.add(state)
        state = new
    }

    fun begin() {
        gestureStart = state
    }

    fun update(new: EditState) {
        state = new
    }

    fun end(keep: Boolean = true) {
        val start = gestureStart ?: return
        gestureStart = null
        if (!keep) state = start
        if (start != state) history.add(start)
    }

    fun undo() {
        state = history.removeAt(history.lastIndex)
        Logx.d("editor: undo, ${history.size} left")
    }

    fun rotate() = push(state.copy(rotation = (state.rotation + 270) % 360))

    fun flip() = push(state.copy(rotation = (360 - state.rotation) % 360, flipped = !state.flipped))

    fun deleteSelected() {
        push(state.copy(overlays = state.overlays.filter { it.id != selected }))
        selected = null
    }

    private fun newOverlay(text: String, sticker: String?, center: Offset, screenPx: Float) = Overlay(
        id = nextId++,
        text = text,
        sticker = sticker,
        colorPos = colorPos,
        center = center,
        scale = screenPx / (viewScale * Scene.BASE),
        rotation = (if (state.flipped) state.rotation else -state.rotation).toFloat(),
        mirrored = state.flipped,
    )

    fun addSticker(path: String, screenPx: Float) {
        val o = newOverlay("", path, state.crop.center, screenPx)
        push(state.copy(overlays = state.overlays + o))
        selected = o.id
        Logx.d("editor: sticker $path")
    }

    fun startText(at: Offset?, screenPx: Float) {
        editing = newOverlay("", null, at ?: state.crop.center, screenPx)
    }

    fun editText(o: Overlay) {
        colorPos = o.colorPos
        editing = o
    }

    fun finishText(text: String) {
        val o = editing ?: return
        editing = null
        val exists = state.overlays.any { it.id == o.id }
        val updated = o.copy(text = text, colorPos = colorPos)
        when {
            text.isBlank() && exists -> push(state.copy(overlays = state.overlays.filter { it.id != o.id }))
            text.isBlank() -> {}
            exists -> push(state.copy(overlays = state.overlays.map { if (it.id == o.id) updated else it }))
            else -> push(state.copy(overlays = state.overlays + updated))
        }
        selected = if (text.isBlank()) null else o.id
    }

    fun updateOverlay(id: Long, f: (Overlay) -> Overlay) {
        state = state.copy(overlays = state.overlays.map { if (it.id == id) f(it) else it })
    }
}

fun decodeImage(context: Context, uri: Uri): Bitmap {
    val cr = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
    val raw = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
    val exif = cr.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    val m = Matrix()
    when (exif) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> m.apply { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> m.apply { setRotate(-90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(270f)
    }
    val scale = min(1f, MAX_SIDE.toFloat() / max(raw.width, raw.height))
    m.postScale(scale, scale)
    val bitmap = if (m.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    Logx.i("editor: decoded ${bounds.outWidth}x${bounds.outHeight} sample=$sample exif=$exif -> ${bitmap.width}x${bitmap.height}")
    return bitmap
}

fun exportImage(context: Context, scene: Scene, s: EditState): String {
    val start = System.currentTimeMillis()
    val m = orientation(s, scene.width, scene.height)
    val r = s.crop.toRectF().also { m.mapRect(it) }
    val k = min(1f, MAX_SIDE / max(r.width(), r.height()))
    val out = Bitmap.createBitmap(max(1, (r.width() * k).roundToInt()), max(1, (r.height() * k).roundToInt()), Bitmap.Config.ARGB_8888)
    m.postTranslate(-r.left, -r.top)
    m.postScale(k, k)
    val canvas = Canvas(out)
    canvas.concat(m)
    scene.draw(canvas, s, null, null, 0f)
    val dir = File(context.cacheDir, "outgoing").apply { mkdirs() }
    val file = File(dir, "$start.jpg")
    file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    Logx.i("editor: exported ${out.width}x${out.height} ${file.length()}B in ${System.currentTimeMillis() - start}ms")
    out.recycle()
    return file.path
}

private const val STICKER_PX = 256

/** Renders one emoji with the system emoji font into a square bitmap. */
fun emojiBitmap(emoji: String): Bitmap {
    val bmp = Bitmap.createBitmap(STICKER_PX, STICKER_PX, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = STICKER_PX * 0.8f
    }
    val fm = paint.fontMetrics
    Canvas(bmp).drawText(emoji, STICKER_PX / 2f, STICKER_PX / 2f - (fm.ascent + fm.descent) / 2, paint)
    return bmp
}
