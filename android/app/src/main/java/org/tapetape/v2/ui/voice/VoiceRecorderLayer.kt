package org.tapetape.v2.ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.theme.FailedRed
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_MS = 55_000L
private const val MIN_MS = 1_000L
private val HintGrey = Color(0xFF999999)

private val Overshoot = Easing { t ->
    val x = t - 1
    x * x * (3 * x + 2) + 1
}

private val AnticipateOvershoot = Easing { t ->
    val s = 3f
    if (t < 0.5f) {
        val x = t * 2
        0.5f * x * x * ((s + 1) * x - s)
    } else {
        val x = t * 2 - 2
        0.5f * (x * x * ((s + 1) * x + s) + 2)
    }
}

private enum class Phase { Idle, Holding, Locked }

private class Review(val file: File, val ms: Long)

private class VoiceRecorder(private val context: Context) {
    var phase by mutableStateOf(Phase.Idle)
    var drag by mutableStateOf(Offset.Zero)
    var review by mutableStateOf<Review?>(null)
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    fun elapsed() = SystemClock.elapsedRealtime() - startedAt

    fun start(): Boolean {
        val dir = File(context.cacheDir, "outgoing").apply { mkdirs() }
        val f = File(dir, "voice-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(VoiceCodec.SAMPLE_RATE)
            r.setAudioEncodingBitRate(VoiceCodec.BIT_RATE)
            r.setOutputFile(f.path)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            Logx.w("voice record start failed", e)
            r.release()
            f.delete()
            toast(context, context.getString(R.string.chat_unable_to_record_audio))
            return false
        }
        AudioPlayback.stop()
        recorder = r
        file = f
        startedAt = SystemClock.elapsedRealtime()
        drag = Offset.Zero
        phase = Phase.Holding
        Logx.i("voice record start ${f.name}")
        return true
    }

    fun stop() {
        val r = recorder ?: return
        val f = file!!
        val ms = min(elapsed(), MAX_MS)
        val ok = runCatching { r.stop() }.onFailure { Logx.w("voice record stop failed", it) }.isSuccess
        finish(r)
        when {
            ms < MIN_MS -> {
                Logx.i("voice record too short ms=$ms")
                f.delete()
                toast(context, context.getString(R.string.chat_record_explain))
            }
            !ok -> {
                f.delete()
                toast(context, context.getString(R.string.chat_unable_to_record_audio))
            }
            else -> {
                Logx.i("voice record stop ${f.name} ms=$ms size=${f.length()}")
                review = Review(f, ms)
            }
        }
    }

    fun cancel() {
        val r = recorder ?: return
        runCatching { r.stop() }
        finish(r)
        file?.delete()
        Logx.i("voice record cancel")
    }

    fun lock() {
        phase = Phase.Locked
        drag = Offset.Zero
        Logx.i("voice record locked")
    }

    private fun finish(r: MediaRecorder) {
        r.release()
        recorder = null
        phase = Phase.Idle
        drag = Offset.Zero
    }
}

@Composable
fun VoiceRecorderLayer(
    enabled: Boolean,
    onVoice: (path: String, durationMs: Long) -> Unit,
    inputRow: @Composable (mic: @Composable () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val c = LocalTapeTapeColors.current
    val density = LocalDensity.current
    val rec = remember { VoiceRecorder(context.applicationContext) }
    val canRecord by rememberUpdatedState(enabled)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        Logx.i("voice permission granted=$it")
    }
    var layerPos by remember { mutableStateOf(Offset.Zero) }
    var micCenter by remember { mutableStateOf(Offset.Zero) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val scale = remember { Animatable(0f) }
    val entry = remember { Animatable(0f) }
    val active = rec.phase != Phase.Idle
    val cancelPx = with(density) { 120.dp.toPx() }
    val lockPx = with(density) { 100.dp.toPx() }

    DisposableEffect(Unit) {
        onDispose { rec.cancel() }
    }
    LaunchedEffect(active) {
        if (active) {
            launch {
                entry.snapTo(0f)
                entry.animateTo(1f, tween(200))
            }
            scale.snapTo(0.5f)
            scale.animateTo(1f, tween(200, easing = Overshoot))
        } else {
            entry.snapTo(0f)
            scale.animateTo(0f, tween(300, easing = AnticipateOvershoot))
        }
    }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            elapsed = rec.elapsed()
            if (elapsed >= MAX_MS) {
                Logx.i("voice record auto stop")
                rec.stop()
            }
            delay(100)
        }
    }

    fun permitted(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        Logx.i("voice permission request")
        launcher.launch(Manifest.permission.RECORD_AUDIO)
        return false
    }

    val mic: @Composable () -> Unit = {
        Box(
            Modifier
                .size(48.dp)
                .onGloballyPositioned { micCenter = it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f) }
                .pointerInput(rec) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!canRecord || rec.phase != Phase.Idle || rec.review != null || !permitted()) return@awaitEachGesture
                        down.consume()
                        if (!rec.start()) return@awaitEachGesture
                        while (rec.phase == Phase.Holding) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (rec.phase != Phase.Holding) break
                            if (!change.pressed) {
                                rec.stop()
                                break
                            }
                            change.consume()
                            val d = change.position - down.position
                            rec.drag = if (abs(d.x) > abs(d.y)) Offset(min(0f, d.x), 0f) else Offset(0f, min(0f, d.y))
                            when {
                                d.x < -cancelPx -> rec.cancel()
                                d.y < -lockPx -> rec.lock()
                            }
                        }
                        if (rec.phase == Phase.Holding) rec.stop()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_mic_white_24dp),
                null,
                Modifier.size(24.dp).alpha(if (enabled) 1f else 0.4f),
                colorFilter = ColorFilter.tint(if (c.dark) Color.White else Color(0xFF757575)),
            )
        }
    }

    Box(Modifier.fillMaxWidth().onGloballyPositioned { layerPos = it.positionInRoot() }) {
        inputRow(mic)
        val center = micCenter - layerPos
        if (active) {
            RecordingBar(rec, elapsed, entry.value, cancelPx, Modifier.matchParentSize())
        }
        if (rec.phase == Phase.Holding) {
            LockPill(
                Modifier.offset {
                    IntOffset(
                        (center.x - 20.dp.toPx()).roundToInt(),
                        (center.y - 181.dp.toPx() + (1 - entry.value) * 40.dp.toPx()).roundToInt(),
                    )
                }.graphicsLayer { alpha = entry.value },
            )
        }
        if (scale.value > 0f) {
            RecordCircle(
                locked = rec.phase == Phase.Locked,
                onStop = rec::stop,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (center.x - 37.dp.toPx() + rec.drag.x).roundToInt(),
                            (center.y - 37.dp.toPx() + rec.drag.y).roundToInt(),
                        )
                    }
                    .graphicsLayer {
                        scaleX = scale.value.coerceAtLeast(0f)
                        scaleY = scale.value.coerceAtLeast(0f)
                    },
            )
        }
    }

    rec.review?.let { r ->
        VoiceEffectDialog(r.file, r.ms, onSend = { file, ms -> onVoice(file.path, ms) }, onClose = { rec.review = null })
    }
}

private fun formatTimer(ms: Long): String =
    String.format(Locale.US, "%02d:%02d.%d", ms / 60_000, ms / 1000 % 60, ms / 100 % 10)

@Composable
private fun RecordingBar(rec: VoiceRecorder, elapsed: Long, entry: Float, cancelPx: Float, modifier: Modifier) {
    val c = LocalTapeTapeColors.current
    val blink by rememberInfiniteTransition(label = "recDot").animateFloat(
        1f,
        0f,
        infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "recDotAlpha",
    )
    Row(
        modifier
            .background(c.inputBar)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).alpha(blink).background(FailedRed, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(formatTimer(elapsed), fontSize = 16.sp, color = c.text)
        if (rec.phase == Phase.Locked) {
            Box(Modifier.weight(1f).padding(end = 64.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.cancel).uppercase(),
                    color = FailedRed,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { rec.cancel() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        } else {
            Row(
                Modifier
                    .padding(start = 8.dp)
                    .graphicsLayer {
                        translationX = rec.drag.x + (1 - entry) * 40.dp.toPx()
                        alpha = entry * (1 - abs(rec.drag.x) / cancelPx).coerceIn(0f, 1f)
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.size(22.dp), tint = HintGrey)
                Text(stringResource(R.string.chat_record_slide_to_cancel), fontSize = 16.sp, color = HintGrey, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LockPill(modifier: Modifier) {
    val c = LocalTapeTapeColors.current
    val shape = RoundedCornerShape(20.dp)
    val tint = if (c.dark) Color.White else Color.Black
    Column(
        modifier
            .size(40.dp, 64.dp)
            .shadow(4.dp, shape)
            .background(if (c.dark) c.menu else Color.White, shape),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(10.dp))
        Image(painterResource(R.drawable.ic_lock_white_24dp), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(tint))
        Spacer(Modifier.height(2.dp))
        Icon(Icons.Filled.KeyboardArrowUp, null, Modifier.size(24.dp), tint = tint)
    }
}

@Composable
private fun RecordCircle(locked: Boolean, onStop: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .size(74.dp)
            .clip(CircleShape)
            .background(AudioOrange)
            .then(if (locked) Modifier.clickable(onClick = onStop) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (locked) {
            Box(Modifier.size(20.dp).background(Color.White, RoundedCornerShape(3.dp)))
        } else {
            Image(painterResource(R.drawable.ic_mic_white_48dp), null, Modifier.size(36.dp))
        }
    }
}
