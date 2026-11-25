package org.tapetape.v2.ui.editor

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.ui.common.ToolbarIcon
import org.tapetape.v2.ui.theme.Accent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MediaSendScreen(uri: Uri, isVideo: Boolean, onSend: (path: String, caption: String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var caption by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var stickers by remember { mutableStateOf(false) }
    val scene by produceState<Scene?>(null, uri, isVideo) {
        if (!isVideo) value = withContext(Dispatchers.IO) { Scene(decodeImage(context, uri)) }
    }
    val c = remember(scene) { scene?.let(::EditorController) }
    LaunchedEffect(uri) { Logx.i("editor: open video=$isVideo $uri") }
    BackHandler {
        Logx.i("editor: cancel")
        onCancel()
    }

    fun send() {
        if (busy) return
        busy = true
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                if (isVideo) copyMedia(context, uri) else exportImage(context, c!!.scene, c.state)
            }
            onSend(path, caption.trim())
        }
    }

    fun select(t: Tool) {
        c!!.tool = if (c.tool == t) Tool.None else t
        c.selected = null
        Logx.d("editor: tool ${c.tool}")
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarIcon(R.drawable.ic_close_white_24dp, {
                    Logx.i("editor: cancel")
                    onCancel()
                })
                Spacer(Modifier.weight(1f))
                if (c != null && c.editing == null) {
                    ToolButton(R.drawable.ic_crop_32, stringResource(R.string.editor_crop), c.tool == Tool.Crop) { select(Tool.Crop) }
                    ToolButton(R.drawable.ic_brush_marker_32, stringResource(R.string.editor_brush_marker), c.tool == Tool.Marker) { select(Tool.Marker) }
                    ToolButton(R.drawable.ic_brush_highlight_32, stringResource(R.string.editor_brush_highlight), c.tool == Tool.Highlighter) { select(Tool.Highlighter) }
                    ToolButton(R.drawable.ic_text_32, stringResource(R.string.editor_add_text), c.tool == Tool.Text) {
                        select(Tool.Text)
                        if (c.tool == Tool.Text) c.startText(null, with(density) { 36.dp.toPx() })
                    }
                    ToolButton(R.drawable.ic_blur_on_white_24, stringResource(R.string.editor_blur), c.tool == Tool.Blur) { select(Tool.Blur) }
                    ToolButton(R.drawable.ic_emoji_32, stringResource(R.string.sticker), stickers) {
                        c.tool = Tool.None
                        stickers = true
                    }
                    ToolButton(R.drawable.ic_undo_32, stringResource(R.string.undo), enabled = c.canUndo) { c.undo() }
                }
            }
            if (c != null) ToolOptions(c, c.editing == null)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isVideo -> VideoPreview(uri)
                    c != null -> EditorCanvas(c)
                    else -> CircularProgressIndicator(color = Color.White)
                }
            }
            CaptionBar(caption, { caption = it }, busy, ::send)
        }
        c?.editing?.let { o ->
            TextEditor(o.text, c.colorPos, { c.colorPos = it }, c::finishText)
        }
    }
    if (stickers && c != null) {
        StickerSheet(
            onPick = {
                stickers = false
                c.addSticker(it, with(density) { 120.dp.toPx() })
            },
            onDismiss = { stickers = false },
        )
    }
}

@Composable
private fun ToolOptions(c: EditorController, visible: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (visible) when (c.tool) {
            Tool.Marker, Tool.Highlighter -> {
                ColorBar(c.colorPos, { c.colorPos = it }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                WidthPicker(c.widthLevel) { c.widthLevel = it }
            }
            Tool.Blur -> {
                Text(stringResource(R.string.editor_draw_anywhere_to_blur), color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                WidthPicker(c.widthLevel) { c.widthLevel = it }
            }
            Tool.Crop -> {
                Spacer(Modifier.weight(1f))
                ToolButton(R.drawable.ic_rotate_32, stringResource(R.string.editor_rotate)) { c.rotate() }
                Spacer(Modifier.width(24.dp))
                ToolButton(R.drawable.ic_flip_32, stringResource(R.string.editor_flip)) { c.flip() }
                Spacer(Modifier.weight(1f))
            }
            else -> if (c.selectedOverlay != null) {
                Spacer(Modifier.weight(1f))
                ToolButton(R.drawable.ic_trash_filled_32, stringResource(R.string.delete)) { c.deleteSelected() }
            }
        }
    }
}

@Composable
private fun CaptionBar(caption: String, onCaption: (String) -> Unit, busy: Boolean, onSend: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        BasicTextField(
            value = caption,
            onValueChange = onCaption,
            textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
            cursorBrush = SolidColor(Accent),
            maxLines = 5,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF262626))
                .padding(horizontal = 18.dp, vertical = 13.dp),
            decorationBox = { inner ->
                Box {
                    if (caption.isEmpty()) Text(stringResource(R.string.v2_add_caption), color = Color(0xFF9E9E9E), fontSize = 17.sp)
                    inner()
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Accent)
                .clickable(enabled = !busy, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Image(painterResource(R.drawable.ic_send_white_24dp), stringResource(R.string.menu_send))
            }
        }
    }
}

@Composable
private fun VideoPreview(uri: Uri) {
    var playing by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<VideoView?>(null) }
    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = null, indication = null) {
                if (playing) view?.pause() else view?.start()
                playing = !playing
            },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = {
                VideoView(it).apply {
                    setVideoURI(uri)
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        seekTo(1)
                    }
                    view = this
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.stopPlayback() },
        )
        if (!playing) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(40.dp), tint = Color.White)
            }
        }
    }
}

private fun copyMedia(context: Context, uri: Uri): String {
    val cr = context.contentResolver
    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(cr.getType(uri)) ?: uri.lastPathSegment!!.substringAfterLast('.')
    val dir = File(context.cacheDir, "outgoing").apply { mkdirs() }
    val file = File(dir, "${System.currentTimeMillis()}.$ext")
    cr.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
    Logx.i("editor: copied video ${file.length()}B -> ${file.name}")
    return file.path
}
