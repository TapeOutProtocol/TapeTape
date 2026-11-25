package org.tapetape.v2.ui.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import kotlinx.coroutines.delay
import java.util.Locale

val AudioOrange = Color(0xFFF68751)
private val TrackColor = AudioOrange.copy(alpha = 0.27f)

object AudioPlayback {
    var path by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    private var player: MediaPlayer? = null

    fun toggle(path: String, startMs: Long) {
        val current = player
        if (current != null && this.path == path) {
            if (current.isPlaying) current.pause() else current.start()
            playing = current.isPlaying
            Logx.d("audio ${if (playing) "resume" else "pause"} $path")
            return
        }
        stop()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            p.setDataSource(path)
            p.prepare()
            if (startMs > 0) p.seekTo(startMs.toInt())
            p.setOnCompletionListener {
                Logx.d("audio done $path")
                stop()
            }
            p.start()
        } catch (e: Exception) {
            Logx.w("audio play failed $path", e)
            p.release()
            return
        }
        player = p
        this.path = path
        playing = true
        Logx.i("audio play $path from=$startMs")
    }

    fun seek(ms: Long) {
        player?.seekTo(ms.toInt())
    }

    fun position(): Long = player?.currentPosition?.toLong() ?: 0L

    fun duration(): Long = player?.duration?.toLong() ?: 0L

    fun stop() {
        player?.release()
        player = null
        path = null
        playing = false
    }

    fun release(path: String) {
        if (this.path == path) {
            Logx.d("audio release $path")
            stop()
        }
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}

@Composable
fun AudioPlayer(path: String, durationMs: Long, modifier: Modifier = Modifier) {
    val active = AudioPlayback.path == path
    val playing = active && AudioPlayback.playing
    var position by remember(path) { mutableLongStateOf(0L) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val total = (if (active) AudioPlayback.duration() else 0L).takeIf { it > 0 } ?: durationMs

    LaunchedEffect(playing) {
        while (playing) {
            position = AudioPlayback.position()
            delay(50)
        }
    }
    LaunchedEffect(active) {
        if (!active) position = 0L
    }
    DisposableEffect(path) {
        onDispose { AudioPlayback.release(path) }
    }

    fun seekTo(fraction: Float) {
        position = (fraction * total).toLong()
        if (active) AudioPlayback.seek(position)
        Logx.d("audio seek $path to=$position")
    }

    val fraction = drag ?: if (total > 0) (position.toFloat() / total).coerceIn(0f, 1f) else 0f
    val shown = when {
        drag != null -> (fraction * total).toLong()
        active || position > 0 -> position
        else -> durationMs
    }
    val morph by animateFloatAsState(if (playing) 1f else 0f, tween(300), label = "playPause")

    Row(modifier.width(210.dp)) {
        Box(
            Modifier
                .size(53.dp)
                .clip(CircleShape)
                .clickable { AudioPlayback.toggle(path, position) },
        ) {
            Canvas(Modifier.size(53.dp)) { drawPlayPause(morph) }
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f).padding(top = 5.5.dp)) {
            SeekLine(
                fraction,
                onDrag = { drag = it },
                onDragEnd = {
                    drag?.let(::seekTo)
                    drag = null
                },
                onTap = ::seekTo,
                modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 10.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(formatDuration(shown), color = AudioOrange, fontSize = 14.sp)
        }
    }
}

private fun DrawScope.drawPlayPause(t: Float) {
    val r = 20.dp.toPx()
    val cx = size.width / 2
    val cy = size.height / 2
    drawCircle(AudioOrange, r, Offset(cx, cy))
    fun lerp(a: Float, b: Float) = a + (b - a) * t
    val top = cy - 0.42f * r
    val bottom = cy + 0.42f * r
    val quarter = (bottom - top) / 4
    val left = cx - 0.26f * r
    val right = cx + 0.47f * r
    val mid = (left + right) / 2
    val barW = 0.22f * r
    val gap = 0.12f * r
    val path = Path().apply {
        moveTo(lerp(left, cx - gap - barW), top)
        lineTo(lerp(mid, cx - gap), top + quarter * (1 - t))
        lineTo(lerp(mid, cx - gap), bottom - quarter * (1 - t))
        lineTo(lerp(left, cx - gap - barW), bottom)
        close()
        moveTo(lerp(mid, cx + gap), top + quarter * (1 - t))
        lineTo(lerp(right, cx + gap + barW), lerp(cy, top))
        lineTo(lerp(right, cx + gap + barW), lerp(cy, bottom))
        lineTo(lerp(mid, cx + gap), bottom - quarter * (1 - t))
        close()
    }
    drawPath(path, Color.White)
}

@Composable
private fun SeekLine(
    fraction: Float,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onTap: (Float) -> Unit,
    modifier: Modifier,
) {
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val tap by rememberUpdatedState(onTap)
    Canvas(
        modifier
            .height(24.dp)
            .pointerInput(Unit) {
                val r = 6.dp.toPx()
                fun at(x: Float) = ((x - r) / (size.width - 2 * r)).coerceIn(0f, 1f)
                detectTapGestures { tap(at(it.x)) }
            }
            .pointerInput(Unit) {
                val r = 6.dp.toPx()
                fun at(x: Float) = ((x - r) / (size.width - 2 * r)).coerceIn(0f, 1f)
                detectHorizontalDragGestures(
                    onDragStart = { drag(at(it.x)) },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                ) { change, _ ->
                    change.consume()
                    drag(at(change.position.x))
                }
            },
    ) {
        val r = 6.dp.toPx()
        val y = size.height / 2
        val x = r + (size.width - 2 * r) * fraction
        val stroke = 2.dp.toPx()
        drawLine(TrackColor, Offset(r, y), Offset(size.width - r, y), stroke, StrokeCap.Round)
        drawLine(AudioOrange, Offset(r, y), Offset(x, y), stroke, StrokeCap.Round)
        drawCircle(AudioOrange, r, Offset(x, y))
    }
}
