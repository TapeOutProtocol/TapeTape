package org.tapetape.v2.ui.chat

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.emoji2.emojipicker.EmojiPickerView
import org.tapetape.v2.R
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import org.tapetape.v2.ui.voice.VoiceRecorderLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot

enum class AttachAction { Camera, Video, Gallery, Contact, File, Transfer, RedPacket, Sell }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InputBar(
    text: String,
    onText: (String) -> Unit,
    emojiOpen: Boolean,
    onEmojiToggle: () -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onVoice: (String, Long) -> Unit,
    onKeyboardMedia: (Uri, String) -> Unit,
    focusKey: Any? = null,
) {
    val c = LocalTapeTapeColors.current
    val focus = remember { FocusRequester() }
    val enterSends = remember { org.tapetape.v2.Prefs.enterSends }
    LaunchedEffect(focusKey) { if (focusKey != null) focus.requestFocus() }
    VoiceRecorderLayer(enabled = true, onVoice = onVoice) { mic ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.inputBar)
                .padding(vertical = 6.dp)
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .padding(start = 6.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onEmojiToggle),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painterResource(if (emojiOpen) R.drawable.ic_keyboard_grey600_24dp else R.drawable.ic_mood_grey600_24dp),
                    stringResource(R.string.menu_toggle_keyboard),
                    Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(c.hint),
                )
            }
            BasicTextField(
                text,
                onText,
                textStyle = TextStyle(fontSize = 16.sp, color = c.text),
                cursorBrush = SolidColor(c.dialogAccent),
                keyboardOptions = if (enterSends) KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send) else KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                maxLines = 5,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp, end = 4.dp)
                    .focusRequester(focus)
                    .contentReceiver { content ->
                        if (!content.hasMediaType(MediaType.Image)) return@contentReceiver content
                        val clip = content.clipEntry.clipData
                        for (i in 0 until clip.itemCount) {
                            clip.getItemAt(i).uri?.let { onKeyboardMedia(it, clip.description.getMimeType(0)) }
                        }
                        null
                    },
                decorationBox = { inner ->
                    Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text(stringResource(R.string.chat_input_placeholder), fontSize = 16.sp, color = c.hint)
                        inner()
                    }
                },
            )
            if (text.isBlank()) mic()
            val empty = text.isBlank()
            Box(
                Modifier
                    .padding(start = 4.dp, end = 10.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(c.roundButton)
                    .clickable(onClick = if (empty) onAttach else onSend),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(empty, transitionSpec = {
                    (scaleIn(tween(150), 0.6f) + fadeIn(tween(150))) togetherWith (scaleOut(tween(150), 0.6f) + fadeOut(tween(150)))
                }, label = "send") { isEmpty ->
                    if (isEmpty) Image(painterResource(R.drawable.ic_add_white_24dp), stringResource(R.string.menu_add_attachment), Modifier.size(26.dp))
                    else Image(painterResource(R.drawable.ic_send_white_24dp), stringResource(R.string.menu_send), Modifier.size(22.dp).padding(start = 2.dp), colorFilter = ColorFilter.tint(Color.White))
                }
            }
        }
    }
}

@Composable
fun EmojiPanel(onPick: (String) -> Unit) {
    val c = LocalTapeTapeColors.current
    AndroidView(
        factory = { ctx -> EmojiPickerView(ctx).apply { setOnEmojiPickedListener { onPick(it.emoji) } } },
        modifier = Modifier.fillMaxWidth().height(300.dp).background(c.inputBar),
    )
}

private fun hasGalleryPermission(context: Context): Boolean {
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
}

private fun recentImages(context: Context): List<Uri> {
    val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cur ->
        val out = mutableListOf<Uri>()
        while (cur.moveToNext() && out.size < 30) out += ContentUris.withAppendedId(uri, cur.getLong(0))
        return out
    }
    return emptyList()
}

@Composable
fun AttachSheet(payment: AttachAction?, onAction: (AttachAction) -> Unit, onRecent: (Uri) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(300)) }
    val recent by produceState(emptyList<Uri>()) {
        if (hasGalleryPermission(context)) value = withContext(Dispatchers.IO) { recentImages(context) }
    }
    Column(
        modifier
            .fillMaxWidth()
            .shadow(4.dp)
            .background(if (c.dark) Color(0xFF111111) else Color.White)
            .drawWithContent {
                val r = hypot(size.width, size.height) * reveal.value
                val path = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(size.width - 30.dp.toPx(), size.height), r)) }
                clipPath(path) { this@drawWithContent.drawContent() }
            }
            .padding(bottom = 12.dp),
    ) {
        if (recent.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                items(recent) { uri ->
                    RecentThumb(uri, Modifier.padding(end = 4.dp).size(80.dp).clickable { onRecent(uri) })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        val items = buildList {
            add(Triple(R.drawable.ic_camera_alt_white_24dp, Color(0xFF3B82F6), R.string.camera) to AttachAction.Camera)
            add(Triple(R.drawable.ic_videocam_white_24dp, Color(0xFFEF4444), R.string.video) to AttachAction.Video)
            add(Triple(R.drawable.ic_image_white_24dp, Color(0xFF8B5CF6), R.string.gallery) to AttachAction.Gallery)
            add(Triple(R.drawable.ic_insert_drive_file_white_24dp, Color(0xFF0EA5E9), R.string.file) to AttachAction.File)
            add(Triple(R.drawable.ic_person_white_24dp, Color(0xFF64748B), R.string.contact) to AttachAction.Contact)
            if (payment == AttachAction.Transfer) add(Triple(R.drawable.ic_transfer_card, Color(0xFF2563EB), R.string.transfer) to AttachAction.Transfer)
            if (payment == AttachAction.RedPacket) add(Triple(R.drawable.ic_gift, Color(0xFFF97316), R.string.red_packet) to AttachAction.RedPacket)
            add(Triple(R.drawable.ic_sell, Color(0xFF10B981), R.string.sell) to AttachAction.Sell)
        }
        items.chunked(4).forEachIndexed { row, list ->
            if (row > 0) Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                list.forEach { (spec, action) ->
                    AttachItem(spec.first, spec.second, spec.third, 100 + row * 50, Modifier.weight(1f)) { onAction(action) }
                }
                repeat(4 - list.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RecentThumb(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, android.util.Size(256, 256), null)
                else @Suppress("DEPRECATION") MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, ContentUris.parseId(uri), MediaStore.Images.Thumbnails.MINI_KIND, null)
            }.getOrNull()
        }
    }
    val b = bitmap
    if (b != null) Image(b.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Color(0x22888888)))
}

@Composable
private fun AttachItem(icon: Int, tile: Color, label: Int, delay: Int, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalTapeTapeColors.current
    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delay.toLong())
        scale.animateTo(1f, tween(260, easing = { android.view.animation.OvershootInterpolator(1.5f).getInterpolation(it) }))
    }
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(52.dp)
                .scale(scale.value)
                .clip(RoundedCornerShape(16.dp))
                .background(tile),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(icon), null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(Color.White))
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(label), fontSize = 12.sp, color = c.hint, maxLines = 1, softWrap = false)
    }
}
