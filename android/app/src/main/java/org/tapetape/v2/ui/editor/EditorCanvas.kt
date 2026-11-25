package org.tapetape.v2.ui.editor

import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke

private val penWidths = mapOf(
    Pen.Marker to listOf(4, 8, 16),
    Pen.Highlighter to listOf(14, 24, 40),
    Pen.Blur to listOf(16, 32, 56),
)

private enum class Handle { TopLeft, TopRight, BottomLeft, BottomRight, Move }

fun EditorController.display(w: Float, h: Float, pad: Float): Matrix {
    val m = orientation(state, scene.width, scene.height)
    val src = if (tool == Tool.Crop) RectF(0f, 0f, scene.width, scene.height) else state.crop.toRectF()
    m.mapRect(src)
    val aw = w - 2 * pad
    val ah = h - 2 * pad
    val s = min(aw / src.width(), ah / src.height())
    m.postTranslate(-src.left, -src.top)
    m.postScale(s, s)
    m.postTranslate(pad + (aw - src.width() * s) / 2, pad + (ah - src.height() * s) / 2)
    return m
}

@Composable
fun EditorCanvas(c: EditorController, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val cropPad = with(density) { 28.dp.toPx() }
    val textPx = with(density) { 36.dp.toPx() }
    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .then(if (c.tool == Tool.None) Modifier else Modifier.systemGestureExclusion())
            .pointerInput(c) { gestures(c, cropPad, textPx) },
    ) {
        val cropping = c.tool == Tool.Crop
        val d = c.display(size.width, size.height, if (cropping) cropPad else 0f)
        c.viewScale = d.mapRadius(1f)
        val s = c.state
        val editingId = c.editing?.id
        val shown = if (editingId == null) s else s.copy(overlays = s.overlays.filter { it.id != editingId })
        val selected = if (c.tool == Tool.None || c.tool == Tool.Text) c.selected else null
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.save()
            nc.concat(d)
            if (!cropping) nc.clipRect(s.crop.toRectF())
            c.scene.draw(nc, shown, c.live, selected, 2.dp.toPx() / c.viewScale)
            nc.restore()
        }
        if (cropping) drawCropFrame(s.crop.toRectF().also { d.mapRect(it) })
    }
}

private fun DrawScope.drawCropFrame(r: RectF) {
    val dim = Color.Black.copy(alpha = 0.6f)
    drawRect(dim, Offset.Zero, Size(size.width, r.top))
    drawRect(dim, Offset(0f, r.bottom), Size(size.width, size.height - r.bottom))
    drawRect(dim, Offset(0f, r.top), Size(r.left, r.height()))
    drawRect(dim, Offset(r.right, r.top), Size(size.width - r.right, r.height()))
    val grid = Color.White.copy(alpha = 0.35f)
    for (i in 1..2) {
        val x = r.left + r.width() * i / 3
        val y = r.top + r.height() * i / 3
        drawLine(grid, Offset(x, r.top), Offset(x, r.bottom), 1.dp.toPx())
        drawLine(grid, Offset(r.left, y), Offset(r.right, y), 1.dp.toPx())
    }
    drawRect(Color.White, Offset(r.left, r.top), Size(r.width(), r.height()), style = DrawStroke(1.5.dp.toPx()))
    val len = 22.dp.toPx()
    val t = 4.dp.toPx()
    val o = t / 2
    listOf(
        Triple(r.left - o, r.top - o, 1f to 1f),
        Triple(r.right + o, r.top - o, -1f to 1f),
        Triple(r.left - o, r.bottom + o, 1f to -1f),
        Triple(r.right + o, r.bottom + o, -1f to -1f),
    ).forEach { (x, y, dir) ->
        drawLine(Color.White, Offset(x, y), Offset(x + dir.first * len, y), t, StrokeCap.Square)
        drawLine(Color.White, Offset(x, y), Offset(x, y + dir.second * len), t, StrokeCap.Square)
    }
}

internal suspend fun AwaitPointerEventScope.track(down: PointerInputChange, onMove: (PointerInputChange) -> Unit) {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return
        onMove(change)
        change.consume()
        if (!change.pressed) return
    }
}

private suspend fun PointerInputScope.gestures(c: EditorController, cropPad: Float, textPx: Float) {
    awaitEachGesture {
        val down = awaitFirstDown()
        val pad = if (c.tool == Tool.Crop) cropPad else 0f
        val d = c.display(size.width.toFloat(), size.height.toFloat(), pad)
        when (c.tool) {
            Tool.Marker, Tool.Highlighter, Tool.Blur -> drawGesture(c, down, d)
            Tool.Crop -> cropGesture(c, down, d)
            else -> overlayGesture(c, down, d, textPx)
        }
    }
}

private suspend fun AwaitPointerEventScope.drawGesture(c: EditorController, down: PointerInputChange, d: Matrix) {
    val inv = d.inverted()
    val pen = when (c.tool) {
        Tool.Marker -> Pen.Marker
        Tool.Highlighter -> Pen.Highlighter
        else -> Pen.Blur
    }
    val width = penWidths.getValue(pen)[c.widthLevel].dp.toPx() / c.viewScale
    val points = mutableListOf(inv.map(down.position))
    down.consume()
    c.live = Stroke(pen, c.colorPos, width, points.toList())
    track(down) { change ->
        change.historical.forEach { points += inv.map(it.position) }
        points += inv.map(change.position)
        c.live = Stroke(pen, c.colorPos, width, points.toList())
    }
    c.live?.let { c.push(c.state.copy(strokes = c.state.strokes + it)) }
    c.live = null
}

private suspend fun AwaitPointerEventScope.cropGesture(c: EditorController, down: PointerInputChange, d: Matrix) {
    val bounds = RectF(0f, 0f, c.scene.width, c.scene.height).also { d.mapRect(it) }
    val start = c.state.crop.toRectF().also { d.mapRect(it) }
    val p = down.position
    val touch = 32.dp.toPx()
    fun near(x: Float, y: Float) = abs(p.x - x) < touch && abs(p.y - y) < touch
    val handle = when {
        near(start.left, start.top) -> Handle.TopLeft
        near(start.right, start.top) -> Handle.TopRight
        near(start.left, start.bottom) -> Handle.BottomLeft
        near(start.right, start.bottom) -> Handle.BottomRight
        start.contains(p.x, p.y) -> Handle.Move
        else -> return
    }
    val minW = min(64.dp.toPx(), start.width())
    val minH = min(64.dp.toPx(), start.height())
    val inv = d.inverted()
    down.consume()
    c.begin()
    track(down) { change ->
        val dx = change.position.x - p.x
        val dy = change.position.y - p.y
        val r = RectF(start)
        if (handle == Handle.Move) {
            r.offset(dx.coerceIn(bounds.left - start.left, bounds.right - start.right), dy.coerceIn(bounds.top - start.top, bounds.bottom - start.bottom))
        } else {
            if (handle == Handle.TopLeft || handle == Handle.BottomLeft) {
                r.left = (start.left + dx).coerceIn(bounds.left, start.right - minW)
            } else {
                r.right = (start.right + dx).coerceIn(start.left + minW, bounds.right)
            }
            if (handle == Handle.TopLeft || handle == Handle.TopRight) {
                r.top = (start.top + dy).coerceIn(bounds.top, start.bottom - minH)
            } else {
                r.bottom = (start.bottom + dy).coerceIn(start.top + minH, bounds.bottom)
            }
        }
        inv.mapRect(r)
        c.update(c.state.copy(crop = r.toRect()))
    }
    c.end()
}

private suspend fun AwaitPointerEventScope.overlayGesture(c: EditorController, down: PointerInputChange, d: Matrix, textPx: Float) {
    val inv = d.inverted()
    val p = inv.map(down.position)
    val hit = c.state.overlays.lastOrNull { c.scene.hit(it, p) }
    if (hit == null) {
        waitForUpOrCancellation() ?: return
        if (c.tool == Tool.Text && c.state.crop.contains(p)) c.startText(p, textPx) else c.selected = null
        return
    }
    c.selected = hit.id
    c.begin()
    val sign = if (c.state.flipped) -1f else 1f
    var travel = 0f
    var pinched = false
    while (true) {
        val ev = awaitPointerEvent()
        val pan = ev.calculatePan()
        val zoom = ev.calculateZoom()
        val rot = ev.calculateRotation()
        travel += pan.getDistance()
        if (zoom != 1f || rot != 0f) pinched = true
        val v = inv.mapVector(pan)
        c.updateOverlay(hit.id) { it.copy(center = it.center + v, scale = it.scale * zoom, rotation = it.rotation + rot * sign) }
        ev.changes.forEach { it.consume() }
        if (ev.changes.none { it.pressed }) break
    }
    val moved = pinched || travel > viewConfiguration.touchSlop
    c.end(moved)
    if (!moved && hit.sticker == null) c.editText(hit)
}
