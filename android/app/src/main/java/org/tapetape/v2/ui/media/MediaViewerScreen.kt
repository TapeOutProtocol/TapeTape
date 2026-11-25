package org.tapetape.v2.ui.media

import android.os.Build
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.ui.bubbleTime
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.ToolbarIcon
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.theme.FailedRed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BarBackground = Color(0x99000000)

@Composable
fun MediaViewerScreen(items: List<MediaItem>, startIndex: Int, onDelete: ((MediaItem) -> Unit)?, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = rememberStorageAccess()
    val pager = rememberPagerState(startIndex.coerceIn(0, items.lastIndex)) { items.size }
    var bars by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf<MediaItem?>(null) }
    val current = items[pager.currentPage.coerceAtMost(items.lastIndex)]

    SystemBars(bars)
    LaunchedEffect(current.id) { Logx.d("viewer page ${pager.currentPage} id=${current.id} ${current.mime}") }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { items[it].id }) { page ->
            val item = items[page]
            if (isVideo(item.mime)) {
                VideoPage(item, active = pager.currentPage == page, controls = bars, onTap = { bars = !bars })
            } else {
                ImagePage(item, onTap = { bars = !bars })
            }
        }
        AnimatedVisibility(bars, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            TapeTapeToolbar(
                title = null,
                onNav = onClose,
                background = BarBackground,
                titleContent = {
                    Column(Modifier.weight(1f)) {
                        Text(current.sender, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(bubbleTime(context, current.time), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
                    }
                },
                actions = {
                    ToolbarIcon(R.drawable.ic_share_white_24dp, { FileActions.share(context, listOf(current.path), listOf(current.mime)) })
                    ToolbarIcon(R.drawable.ic_save_white_24dp, {
                        val item = current
                        storage {
                            scope.launch {
                                val ok = FileActions.saveToGallery(context, item.path, item.mime)
                                toast(context, context.getString(if (ok) R.string.saved else R.string.error))
                            }
                        }
                    })
                    if (onDelete != null) {
                        ToolbarIcon(R.drawable.ic_delete_white_24dp, { confirmDelete = current })
                    }
                },
            )
        }
    }

    confirmDelete?.let { item ->
        TapeTapeDialog(
            onDismiss = { confirmDelete = null },
            message = stringResource(R.string.ask_delete_message),
            dismiss = stringResource(R.string.cancel) to { confirmDelete = null },
            confirm = stringResource(R.string.delete) to {
                confirmDelete = null
                Logx.i("viewer delete id=${item.id}")
                onDelete?.invoke(item)
            },
            confirmColor = FailedRed,
        )
    }
}

@Composable
private fun SystemBars(visible: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    LaunchedEffect(visible) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(controller) {
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun ImagePage(item: MediaItem, onTap: () -> Unit) {
    val config = LocalConfiguration.current
    val maxSide = with(LocalDensity.current) { maxOf(config.screenWidthDp, config.screenHeightDp).dp.roundToPx() }.coerceAtMost(2048)
    val painter = if (isGif(item.mime) && Build.VERSION.SDK_INT >= 28) {
        rememberAnimatedPainter(item.path, maxSide)
    } else {
        produceState<Painter?>(null, item.path) {
            value = MediaDecode.full(item.path, maxSide)?.let { BitmapPainter(it.asImageBitmap()) }
        }.value
    }
    painter?.let { ZoomableImage(it, onTap) }
}

@Composable
private fun ZoomableImage(painter: Painter, onTap: () -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val intrinsic = painter.intrinsicSize
        val fit = minOf(size.width / intrinsic.width, size.height / intrinsic.height)
        val maxX = maxOf(0f, (intrinsic.width * fit * s - size.width) / 2)
        val maxY = maxOf(0f, (intrinsic.height * fit * s - size.height) / 2)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun zoomTo(newScale: Float, focus: Offset, pan: Offset = Offset.Zero) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val c = focus - center
        offset = clamp(c - (c - offset) * (newScale / scale) + pan, newScale)
        scale = newScale
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(painter) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { pos -> if (scale > 1f) zoomTo(1f, pos) else zoomTo(2.5f, pos) },
                )
            }
            .pointerInput(painter) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed > 1 || scale > 1f) {
                            val before = offset
                            zoomTo((scale * event.calculateZoom()).coerceIn(1f, 5f), event.calculateCentroid(), event.calculatePan())
                            if (pressed > 1 || offset.x != before.x || event.calculatePan().x == 0f) {
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Image(
            painter,
            null,
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun VideoPage(item: MediaItem, active: Boolean, controls: Boolean, onTap: () -> Unit) {
    val context = LocalContext.current
    val video = remember(item.path) { VideoView(context) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    DisposableEffect(video) {
        video.setOnPreparedListener {
            duration = it.duration.toLong()
            prepared = true
            Logx.d("video prepared ${item.path} ${duration}ms")
        }
        video.setOnCompletionListener {
            playing = false
            position = duration
        }
        video.setOnErrorListener { _, what, extra ->
            Logx.w("video error $what/$extra ${item.path}")
            toast(context, context.getString(R.string.error))
            true
        }
        video.setVideoPath(item.path)
        onDispose { video.stopPlayback() }
    }

    LaunchedEffect(active, prepared) {
        if (!prepared) return@LaunchedEffect
        if (active) video.start() else video.pause()
        playing = active
    }

    LaunchedEffect(playing) {
        while (playing) {
            position = video.currentPosition.toLong()
            delay(200)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                FrameLayout(it).apply {
                    addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) })
        AnimatedVisibility(controls, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(BarBackground)
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarIcon(if (playing) R.drawable.ic_pause_circle_fill_white_48dp else R.drawable.ic_play_circle_fill_white_48dp, {
                    if (playing) video.pause() else video.start()
                    playing = !playing
                })
                Text(formatDuration(position), color = Color.White, fontSize = 13.sp)
                Slider(
                    value = if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = {
                        position = (it * duration).toLong()
                        video.seekTo(position.toInt())
                    },
                    enabled = prepared,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(formatDuration(duration), color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}
