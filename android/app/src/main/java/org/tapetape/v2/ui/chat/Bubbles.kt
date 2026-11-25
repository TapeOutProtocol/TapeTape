package org.tapetape.v2.ui.chat

import android.util.Patterns
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import org.tapetape.v2.ui.theme.FailedRed
import androidx.compose.foundation.layout.fillMaxSize
import org.tapetape.v2.core.DownloadState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.Message
import org.tapetape.v2.core.MessageKind
import org.tapetape.v2.ui.bubbleTime
import org.tapetape.v2.ui.chatlist.DeliveryIcon
import org.tapetape.v2.ui.proxy.isTappableProxyLink
import org.tapetape.v2.ui.common.Avatar
import org.tapetape.v2.ui.common.avatarColor
import org.tapetape.v2.ui.media.MediaThumbnail
import org.tapetape.v2.ui.parseInvite
import org.tapetape.v2.ui.theme.Accent
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import org.tapetape.v2.ui.voice.AudioPlayer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class BubbleActions(
    val onClick: (Message) -> Unit,
    val onLongClick: (Message) -> Unit,
    val onReply: (Message) -> Unit,
    val onQuote: (Int) -> Unit,
    val onReactions: (Message) -> Unit,
    val onReact: (Message, String) -> Unit,
    val onDownload: (Message) -> Unit,
)

fun previewText(context: android.content.Context, m: Message?): String = when (m?.kind) {
    null -> ""
    MessageKind.Image -> "📷 " + context.getString(R.string.image).let { if (m.text.isBlank()) it else m.text }
    MessageKind.Gif -> context.getString(R.string.gif)
    MessageKind.Sticker -> context.getString(R.string.sticker)
    MessageKind.Video -> "🎥 " + m.text.ifBlank { context.getString(R.string.video) }
    MessageKind.Voice -> "🎤 " + context.getString(R.string.voice_message)
    MessageKind.Audio -> "🎵 " + (m.fileName ?: context.getString(R.string.audio))
    MessageKind.File -> "📎 " + (m.fileName ?: context.getString(R.string.file))
    MessageKind.Vcard -> "👤 " + (m.vcard?.name ?: context.getString(R.string.contact))
    MessageKind.System -> systemText(context, m)
    MessageKind.RedPacket -> "🧧 " + (m.packet?.text?.ifBlank { null } ?: context.getString(R.string.red_packet))
    MessageKind.Transfer -> "💰 " + (m.packet?.let { it.amount + " " + it.symbol } ?: context.getString(R.string.transfer))
    else -> m.text
}

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "%.1f MB".format(bytes / 1024f / 1024f)
}

fun timerText(context: android.content.Context, m: Message): String {
    val s = m.timerChanged ?: 0
    val by = if (m.fromContact == null) null else m.senderName
    fun pick(you: Int, other: Int) = if (by == null) context.getString(you) else context.getString(other, by)
    fun pickN(you: Int, other: Int, n: Int) = if (by == null) context.getString(you, n.toString()) else context.getString(other, n.toString(), by)
    return when {
        s == 0 -> pick(R.string.ephemeral_timer_disabled_by_you, R.string.ephemeral_timer_disabled_by_other)
        s == 60 -> pick(R.string.ephemeral_timer_1_minute_by_you, R.string.ephemeral_timer_1_minute_by_other)
        s == 3600 -> pick(R.string.ephemeral_timer_1_hour_by_you, R.string.ephemeral_timer_1_hour_by_other)
        s == 86400 -> pick(R.string.ephemeral_timer_1_day_by_you, R.string.ephemeral_timer_1_day_by_other)
        s == 604800 -> pick(R.string.ephemeral_timer_1_week_by_you, R.string.ephemeral_timer_1_week_by_other)
        s < 60 -> pickN(R.string.ephemeral_timer_seconds_by_you, R.string.ephemeral_timer_seconds_by_other, s)
        s < 3600 -> pickN(R.string.ephemeral_timer_minutes_by_you, R.string.ephemeral_timer_minutes_by_other, s / 60)
        s < 86400 -> pickN(R.string.ephemeral_timer_hours_by_you, R.string.ephemeral_timer_hours_by_other, s / 3600)
        s < 604800 -> pickN(R.string.ephemeral_timer_days_by_you, R.string.ephemeral_timer_days_by_other, s / 86400)
        else -> pickN(R.string.ephemeral_timer_weeks_by_you, R.string.ephemeral_timer_weeks_by_other, s / 604800)
    }
}

@Composable
fun DateHeader(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier
                .background(Color(0x55444444), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun SystemPill(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = Color.White,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(Color(0x55444444), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(m: Message, quoted: Message?, isGroup: Boolean, selected: Boolean, highlight: Boolean, actions: BubbleActions, progress: Float? = null) {
    val c = LocalTapeTapeColors.current
    if (m.kind == MessageKind.System) {
        val context = LocalContext.current
        val text = remember(m) { systemText(context, m) }
        if (m.system is org.tapetape.v2.core.SystemMsg.PacketClaimed) org.tapetape.v2.ui.packets.PacketClaimedPill(text) else SystemPill(text)
        return
    }
    val out = m.outgoing
    val density = LocalDensity.current
    val trigger = with(density) { 64.dp.toPx() }
    val max = with(density) { 96.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (selected) c.selected else if (highlight) Color(0x33FFC107) else Color.Transparent)
            .pointerInput(m.id) {
                var fired = false
                detectHorizontalDragGestures(
                    onDragStart = { fired = false },
                    onDragEnd = {
                        if (offset.value >= trigger) actions.onReply(m)
                        scope.launch { offset.animateTo(0f, tween(150)) }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, tween(150)) } },
                ) { change, delta ->
                    val next = (offset.value + delta).coerceIn(0f, max)
                    if (next != offset.value) change.consume()
                    if (!fired && next >= trigger) {
                        fired = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    scope.launch { offset.snapTo(next) }
                }
            }
            .combinedClickable(onClick = { actions.onClick(m) }, onLongClick = { actions.onLongClick(m) })
            .padding(bottom = 3.dp, top = 1.dp),
    ) {
        if (offset.value > 0f) {
            val p = (offset.value / trigger).coerceIn(0f, 1f)
            Image(
                painterResource(R.drawable.ic_reply_white_24dp),
                null,
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
                    .size(20.dp * (1f + 0.2f * p))
                    .alpha(p),
                colorFilter = ColorFilter.tint(c.preview),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .padding(start = if (out) 32.dp else 12.dp, end = if (out) 12.dp else 32.dp),
            horizontalAlignment = if (out) Alignment.End else Alignment.Start,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (isGroup && !out) {
                    val avatar = remember(m.fromContact) { m.fromContact?.let { runCatching { org.tapetape.v2.TapeTape.account?.contact(it)?.avatarPath }.getOrNull() } }
                    Avatar(m.senderName, avatar, 36.dp)
                    Spacer(Modifier.width(6.dp))
                }
                when {
                    m.packet != null -> org.tapetape.v2.ui.packets.PacketBubble(m, m.packet, out, isGroup)
                    m.kind == MessageKind.Sticker && m.download == null -> StickerBubble(m, out)
                    else -> Bubble(m, quoted, isGroup, out, actions, progress)
                }
            }
            if (m.reactions.isNotEmpty()) {
                Row(
                    Modifier.padding(start = if (isGroup && !out) 42.dp else 0.dp, top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    m.reactions.forEach { r ->
                        Text(
                            if (r.count > 1) "${r.emoji} ${r.count}" else r.emoji,
                            fontSize = 14.sp,
                            color = c.bubbleText,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (r.mine) Color(0xFFD7F2C4) else c.inBubble)
                                .border(1.dp, Color(0x22000000), RoundedCornerShape(12.dp))
                                .clickable { actions.onReactions(m) }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: Message, quoted: Message?, isGroup: Boolean, out: Boolean, actions: BubbleActions, progress: Float?) {
    val c = LocalTapeTapeColors.current
    val shape = if (out) RoundedCornerShape(12.dp, 12.dp, 0.dp, 12.dp) else RoundedCornerShape(12.dp, 12.dp, 12.dp, 0.dp)
    val media = m.isMedia || (m.kind == MessageKind.Sticker && m.download != null)
    val mediaOnly = media && m.text.isBlank() && m.replyTo == null && !m.forwarded && !(isGroup && !out)
    Column(
        Modifier
            .then(if (media) Modifier.width(if (mediaOnly) 260.dp else 272.dp) else Modifier.width(IntrinsicSize.Max).widthIn(max = 300.dp))
            .clip(shape)
            .background(if (out) c.outBubble else c.inBubble)
            .padding(if (mediaOnly) 0.dp else 6.dp),
    ) {
        if (m.forwarded) {
            Text(stringResource(R.string.forwarded), color = c.preview, fontSize = 13.sp, fontStyle = FontStyle.Italic, modifier = Modifier.padding(horizontal = 2.dp))
        }
        if (isGroup && !out) {
            Text(m.senderName, color = avatarColor(m.senderName), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
        }
        if (m.replyTo != null) Quote(quoted, out) { actions.onQuote(m.replyTo) }
        when (m.kind) {
            MessageKind.Image, MessageKind.Gif, MessageKind.Video, MessageKind.Sticker -> MediaContent(m, out, overlayFooter = m.text.isBlank(), progress) { actions.onDownload(m) }
            MessageKind.Voice, MessageKind.Audio -> if (m.download != null) FileContent(m, progress) { actions.onDownload(m) } else AudioPlayer(m.file.orEmpty(), m.durationMs, Modifier.width(240.dp))
            MessageKind.File -> FileContent(m, progress) { actions.onDownload(m) }
            MessageKind.Vcard -> VcardContent(m)
            else -> Unit
        }
        if (progress != null && m.download == null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
                color = Accent,
                trackColor = Color(0x22000000),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
        if (m.text.isNotBlank()) {
            LinkText(m.text, Modifier.padding(horizontal = 2.dp, vertical = if (media) 4.dp else 0.dp))
        }
        if (!(media && m.text.isBlank())) Footer(m, out, Modifier.align(Alignment.End).padding(top = 2.dp, end = 2.dp))
    }
}

@Composable
private fun MediaContent(m: Message, out: Boolean, overlayFooter: Boolean, progress: Float?, onDownload: () -> Unit) {
    val ratio = if (m.width > 0 && m.height > 0) (m.width.toFloat() / m.height).coerceIn(0.5f, 2f) else 1f
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        if (m.download != null) {
            Box(Modifier.fillMaxSize().background(Color(0xFF8E8E93)), contentAlignment = Alignment.Center) {
                DownloadPill(m, progress, onDownload)
            }
        } else MediaThumbnail(m.file.orEmpty(), m.mime.orEmpty(), Modifier.fillMaxWidth().fillMaxHeight())
        if (m.kind == MessageKind.Video && m.download == null) {
            Image(painterResource(R.drawable.ic_play_circle_fill_white_48dp), null, Modifier.align(Alignment.Center).size(56.dp))
        }
        if (overlayFooter) {
            Footer(
                m, out,
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                onImage = true,
            )
        }
    }
}

@Composable
private fun StickerBubble(m: Message, out: Boolean) {
    Column(horizontalAlignment = if (out) Alignment.End else Alignment.Start) {
        MediaThumbnail(m.file.orEmpty(), m.mime ?: "image/webp", Modifier.size(175.dp), androidx.compose.ui.layout.ContentScale.Fit)
        Footer(
            m, out,
            Modifier
                .padding(top = 2.dp)
                .background(Color(0x99000000), RoundedCornerShape(10.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
            onImage = true,
        )
    }
}

@Composable
private fun FileContent(m: Message, progress: Float?, onDownload: () -> Unit) {
    val c = LocalTapeTapeColors.current
    val pending = m.download
    Row(
        Modifier
            .padding(4.dp)
            .widthIn(min = 200.dp)
            .then(if (pending != null && pending != DownloadState.Downloading) Modifier.clickable(onClick = onDownload) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
            if (pending == DownloadState.Downloading) {
                CircularProgressIndicator(progress = { progress ?: 0f }, Modifier.size(40.dp), color = Color.White, strokeWidth = 3.dp, trackColor = Color(0x44FFFFFF))
            }
            Image(painterResource(if (pending != null) R.drawable.ic_download_white_24dp else R.drawable.ic_insert_drive_file_white_24dp), null, Modifier.size(24.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(m.fileName ?: previewText(LocalContext.current, m), color = c.bubbleText, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val status = pending?.let { downloadLabel(it, progress) }
            Text(listOfNotNull(formatSize(m.size), status).joinToString(" · "), color = if (pending == DownloadState.Failed) FailedRed else c.preview, fontSize = 13.sp)
        }
    }
}

@Composable
private fun downloadLabel(state: DownloadState, progress: Float?): String = when (state) {
    DownloadState.Available -> stringResource(R.string.download)
    DownloadState.Downloading -> stringResource(R.string.downloading) + (progress?.let { " ${(it * 100).roundToInt()}%" } ?: "")
    DownloadState.Failed -> stringResource(R.string.download_failed)
}

@Composable
private fun DownloadPill(m: Message, progress: Float?, onDownload: () -> Unit) {
    val state = m.download ?: return
    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x99000000))
            .then(if (state != DownloadState.Downloading) Modifier.clickable(onClick = onDownload) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state == DownloadState.Downloading) {
            CircularProgressIndicator(progress = { progress ?: 0f }, Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp, trackColor = Color(0x44FFFFFF))
        } else {
            Image(painterResource(R.drawable.ic_download_white_24dp), null, Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(downloadLabel(state, progress) + "  " + formatSize(m.size), color = if (state == DownloadState.Failed) Color(0xFFFF8A80) else Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun VcardContent(m: Message) {
    val c = LocalTapeTapeColors.current
    val name = m.vcard?.name.orEmpty()
    Row(Modifier.padding(4.dp).widthIn(min = 180.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(name, null, 44.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(name, color = c.bubbleText, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(stringResource(R.string.contact), color = c.preview, fontSize = 13.sp)
        }
    }
}

@Composable
fun Footer(m: Message, out: Boolean, modifier: Modifier = Modifier, onImage: Boolean = false) {
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val meta = if (onImage) Color.White else if (out) c.outMeta else c.inMeta
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.msg_encr_out), null, Modifier.size(10.dp), colorFilter = ColorFilter.tint(meta))
        Spacer(Modifier.width(4.dp))
        Text(bubbleTime(context, m.time), color = meta, fontSize = 12.sp)
        if (out) {
            Spacer(Modifier.width(6.dp))
            DeliveryIcon(m.state, meta, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun LinkText(text: String, modifier: Modifier) {
    val c = LocalTapeTapeColors.current
    val annotated = remember(text) { linkify(text) }
    Text(annotated, color = c.bubbleText, fontSize = 16.sp, lineHeight = 21.sp, modifier = modifier)
}

private val PROXY_LINK = Regex("(?i)\\b(?:ss|socks5h?|tg)://\\S+")

private fun linkRanges(text: String): List<IntRange> {
    val web = Patterns.WEB_URL.matcher(text)
    val ranges = PROXY_LINK.findAll(text).map { it.range }.toMutableList()
    while (web.find()) ranges += web.start() until web.end()
    return ranges.sortedBy { it.first }
}

private fun linkify(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    val style = TextLinkStyles(SpanStyle(color = Accent, textDecoration = TextDecoration.Underline))
    for (range in linkRanges(text)) {
        if (range.first < last) continue
        append(text.substring(last, range.first))
        val raw = text.substring(range)
        val url = if (raw.contains("://")) raw else "https://$raw"
        val link = if (parseInvite(url) != null || isTappableProxyLink(url)) {
            LinkAnnotation.Clickable("link", style) { TapeTape.pendingLink.value = url }
        } else {
            LinkAnnotation.Url(url, style)
        }
        withLink(link) { append(raw) }
        last = range.last + 1
    }
    append(text.substring(last))
}

@Composable
fun Quote(quoted: Message?, out: Boolean, onClick: () -> Unit) {
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val name = when {
        quoted == null -> ""
        quoted.fromContact == null -> stringResource(R.string.self)
        else -> quoted.senderName
    }
    val color = if (quoted?.fromContact == null) c.quoteBar else avatarColor(name)
    Row(
        Modifier
            .padding(bottom = 4.dp)
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(4.dp))
            .background(if (out) Color(0x14000000) else Color(0x0F000000))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(color))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (quoted != null) {
                Text(name, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(previewText(context, quoted), color = c.bubbleText, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Text(stringResource(R.string.ConversationFragment_quoted_message_not_found), color = c.preview, fontSize = 14.sp)
            }
        }
        if (quoted?.isMedia == true && quoted.download == null) {
            MediaThumbnail(quoted.file.orEmpty(), quoted.mime.orEmpty(), Modifier.size(44.dp).padding(2.dp).clip(RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
fun ReplyStrip(m: Message, onClose: () -> Unit) {
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val name = if (m.fromContact == null) stringResource(R.string.self) else m.senderName
    val color = if (m.fromContact == null) c.quoteBar else avatarColor(name)
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.inputBar)
            .padding(start = 16.dp, top = 8.dp)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(color))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(name, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(previewText(context, m), color = c.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (m.isMedia) {
            MediaThumbnail(m.file.orEmpty(), m.mime.orEmpty(), Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)))
        }
        org.tapetape.v2.ui.common.ToolbarIcon(R.drawable.ic_close_white_24dp, onClose, tint = c.preview)
    }
}

fun highlighted(text: String, query: String): AnnotatedString = buildAnnotatedString {
    var start = 0
    while (query.isNotEmpty()) {
        val i = text.indexOf(query, start, ignoreCase = true)
        if (i < 0) break
        append(text.substring(start, i))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i, i + query.length)) }
        start = i + query.length
    }
    append(text.substring(start))
}
