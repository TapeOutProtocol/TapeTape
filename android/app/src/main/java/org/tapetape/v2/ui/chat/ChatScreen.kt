package org.tapetape.v2.ui.chat

import org.tapetape.v2.ui.packets.openPacket
import org.tapetape.v2.ui.shop.ShopEntry
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.scrollBy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.Chat
import org.tapetape.v2.core.CoreException
import org.tapetape.v2.core.Contact
import org.tapetape.v2.core.Message
import org.tapetape.v2.core.MessageKind
import org.tapetape.v2.core.MessageState
import org.tapetape.v2.core.DownloadState
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.Screen
import org.tapetape.v2.ui.common.ChatAvatar
import org.tapetape.v2.ui.common.chatTitle
import org.tapetape.v2.ui.common.MenuItem
import org.tapetape.v2.ui.common.MenuOverlap
import org.tapetape.v2.ui.common.OverflowMenu
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.TapeTapeMenu
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.ToolbarIcon
import org.tapetape.v2.ui.common.copyToClipboard
import org.tapetape.v2.ui.common.coreQuery
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.contacts.ContactRow
import org.tapetape.v2.ui.contacts.SearchToolbar
import org.tapetape.v2.ui.dayHeader
import org.tapetape.v2.ui.editor.MediaSendScreen
import org.tapetape.v2.ui.media.FileActions
import org.tapetape.v2.ui.media.MediaItem
import org.tapetape.v2.ui.media.MediaViewerScreen
import org.tapetape.v2.ui.media.rememberStorageAccess
import org.tapetape.v2.ui.sameDay
import org.tapetape.v2.ui.theme.FailedRed
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import org.tapetape.v2.ui.theme.fixedSp
import kotlinx.coroutines.Dispatchers
import org.tapetape.v2.Prefs
import org.tapetape.v2.core.Delivery
import org.tapetape.v2.core.MediaQuality
import org.tapetape.v2.notify.Notifier
import org.tapetape.v2.ui.settings.ChatWallpaper
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val PAGE = 100

val QuickReactions = listOf("👍", "👎", "❤️", "😂", "🙁")

fun localizedError(context: Context, error: String?): String = when {
    error == null -> context.getString(R.string.error)
    error.contains("sender-not-allowed") -> context.getString(R.string.relay_error_not_accepted)
    error.contains("no-key") || error.contains("unknown-recipient") -> context.getString(R.string.relay_error_no_key)
    error.contains("too-large") -> context.getString(R.string.relay_error_too_large)
    error == "quota-exceeded" -> context.getString(R.string.v2_upload_quota_exceeded)
    error == "not-configured" -> context.getString(R.string.v2_upload_not_configured)
    error == "upload-failed" -> context.getString(R.string.v2_upload_failed)
    error.startsWith("http-") -> context.getString(R.string.error) + " (" + error + ")"
    else -> error
}

fun outgoingFile(context: Context, name: String) = File(context.cacheDir, "outgoing/${System.nanoTime()}/$name").apply { parentFile?.mkdirs() }

fun fileUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

fun importUri(context: Context, uri: Uri): Pair<File, String> {
    val own = uri.authority?.let { context.packageManager.resolveContentProvider(it, 0)?.packageName == context.packageName } == true
    if (uri.scheme != "content" || own) throw SecurityException("refused uri scheme=${uri.scheme} authority=${uri.authority}")
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "file"
    val out = outgoingFile(context, name)
    context.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
    return out to (context.contentResolver.getType(uri) ?: "application/octet-stream")
}

private data class SendPreview(val uri: Uri, val isVideo: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(chatId: Int, initialReply: Int? = null) {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val chat by coreQuery<Chat?>(null, chatId) { account.chat(chatId) }
    var limit by rememberSaveable { mutableIntStateOf(PAGE) }
    val messages by coreQuery(emptyList<Message>(), chatId, limit) { account.messages(chatId, null, limit) }
    var text by rememberSaveable { mutableStateOf<String?>(null) }
    var replyTo by remember { mutableStateOf(initialReply?.let { runCatching { account.message(it) }.getOrNull() }) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var emojiOpen by remember { mutableStateOf(false) }
    var attachOpen by remember { mutableStateOf(false) }
    var shopOpen by remember { mutableStateOf(false) }
    if (shopOpen) ShopEntry { shopOpen = false }
    var preview by remember { mutableStateOf<SendPreview?>(null) }
    var viewer by remember { mutableStateOf<Int?>(null) }
    var reactionsOf by remember { mutableStateOf<Message?>(null) }
    var pickEmojiFor by remember { mutableStateOf<Message?>(null) }
    var vcardOf by remember { mutableStateOf<Message?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hitIndex by remember { mutableIntStateOf(0) }
    var unreadMarker by remember { mutableStateOf<Int?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    val listState = rememberLazyListState()
    val byId = remember(messages) { messages.associateBy { it.id } }
    val hits by coreQuery(emptyList<Message>(), chatId, query, searching) {
        if (searching && query.isNotBlank()) account.searchMessages(chatId, query).sortedByDescending { it.id } else emptyList()
    }
    val currentHit = hits.getOrNull(hitIndex)?.id

    fun sendWith(what: String, block: suspend (Int?) -> Int) {
        val reply = replyTo?.id
        replyTo = null
        attachOpen = false
        scope.launch {
            runCatching { block(reply) }
                .onSuccess {
                    Logx.d("sent $what msg=$it chat=$chatId")
                    if (Prefs.inChatSounds) Notifier.play(context, false)
                }
                .onFailure {
                    Logx.e("send $what failed chat=$chatId", it)
                    toast(context, it.message ?: context.getString(R.string.error))
                }
        }
    }

    fun download(m: Message) {
        if (m.download == DownloadState.Downloading) return
        Logx.i("download msg=${m.id} state=${m.download}")
        runCatching { account.download(m.id) }.onFailure {
            Logx.w("download failed msg=${m.id}", it)
            toast(context, it.message ?: context.getString(R.string.download_failed))
        }
    }

    fun send(kind: MessageKind, path: String, caption: String? = null, durationMs: Int? = null) = sendWith(kind.name) { reply ->
        val max = account.maxAttachmentSize()
        val side = if (account.settings().mediaQuality == MediaQuality.Worse) 640 else 1280
        val file = if (kind == MessageKind.Image) withContext(Dispatchers.IO) { fitImage(path, max, outgoingFile(context, "image.jpg"), side) } else path
        if (File(file).length() > max) {
            Logx.w("attachment too large ${File(file).length()} > $max")
            throw CoreException(context.getString(R.string.relay_error_too_large))
        }
        account.sendMedia(chatId, kind, file, caption?.takeIf { it.isNotBlank() }, reply, durationMs)
    }

    fun sendUri(uri: Uri, kindFor: (String) -> MessageKind) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { importUri(context, uri) } }
                .onSuccess { (file, mime) -> send(kindFor(mime), file.path) }
                .onFailure {
                    Logx.w("attachment import failed", it)
                    toast(context, context.getString(R.string.error))
                }
        }
    }

    fun react(m: Message, emoji: String) {
        val next = if (m.reactions.any { it.mine && it.emoji == emoji }) "" else emoji
        scope.launch { runCatching { account.react(m.id, next) }.onFailure { toast(context, it.message.orEmpty()) } }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri).orEmpty()
        if (mime == "image/gif") sendUri(uri) { MessageKind.Gif } else preview = SendPreview(uri, mime.startsWith("video"))
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null) preview = SendPreview(fileUri(context, f), false)
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val f = cameraFile
        if (ok && f != null) preview = SendPreview(fileUri(context, f), true)
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendUri(uri) { if (it.startsWith("audio")) MessageKind.Audio else MessageKind.File }
    }

    LaunchedEffect(chatId) {
        if (text == null) text = withContext(Dispatchers.IO) { (account.chats(null) + account.archivedChats()).firstOrNull { it.id == chatId }?.draft.orEmpty() }
    }
    val request = chat?.isRequest
    LaunchedEffect(messages, request) {
        if (unreadMarker != null && messages.none { it.id == unreadMarker }) unreadMarker = null
        if (messages.any { it.state == MessageState.Incoming }) {
            if (unreadMarker == null) unreadMarker = messages.firstOrNull { it.state == MessageState.Incoming }?.id
            if (request == false) account.markRead(chatId)
        }
    }
    val latestText by rememberUpdatedState(text)
    val saveDraft = { latestText?.let { account.setDraft(chatId, it.takeIf { t -> t.isNotBlank() }) } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { saveDraft() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        Notifier.openChat = account.info().id to chatId
        Notifier.cancel(context, account.info().id, chatId)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { Notifier.openChat = null }
    DisposableEffect(chatId) {
        onDispose { saveDraft() }
    }

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 3
        }
    }
    var initialScrollDone by remember { mutableStateOf(false) }
    var newWhileUp by remember { mutableIntStateOf(0) }
    val lastId = messages.lastOrNull()?.id
    LaunchedEffect(lastId) {
        if (lastId == null) return@LaunchedEffect
        if (!initialScrollDone || atBottom || messages.last().outgoing) {
            withFrameNanos { }
            val marker = unreadMarker?.takeIf { !initialScrollDone }?.let { id -> messages.indexOfFirst { it.id == id } }
            listState.scrollToItem(if (marker != null) rowIndex(messages, marker, unreadMarker) else listState.layoutInfo.totalItemsCount - 1)
            initialScrollDone = true
            newWhileUp = 0
        } else {
            newWhileUp++
        }
    }
    LaunchedEffect(atBottom) { if (atBottom) newWhileUp = 0 }
    LaunchedEffect(listState) {
        var last = 0
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect { h ->
            if (h in 1 until last) listState.scrollBy((last - h).toFloat())
            last = h
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { first ->
            if (first == 0 && initialScrollDone && messages.size >= limit) {
                Logx.d("load older chat=$chatId limit=$limit")
                limit += PAGE
            }
        }
    }
    LaunchedEffect(currentHit) {
        val id = currentHit ?: return@LaunchedEffect
        var index = messages.indexOfFirst { it.id == id }
        while (index < 0 && messages.size >= limit) {
            limit += PAGE
            kotlinx.coroutines.delay(100)
            index = messages.indexOfFirst { it.id == id }
        }
        if (index >= 0) listState.animateScrollToItem(rowIndex(messages, index, unreadMarker))
    }

    BackHandler(selected.isNotEmpty() || emojiOpen || attachOpen || searching) {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            attachOpen -> attachOpen = false
            emojiOpen -> emojiOpen = false
            else -> {
                searching = false
                query = ""
            }
        }
    }

    val sel = messages.filter { it.id in selected }
    val canSend = chat?.canSend == true && chat?.isRequest == false
    val storage = rememberStorageAccess()
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Logx.i("camera permission granted=$granted")
        if (!granted) toast(context, context.getString(R.string.perm_explain_access_to_camera_denied))
    }
    fun withCamera(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) action()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    Column(Modifier.fillMaxSize().background(c.background).imePadding()) {
        when {
            selected.isNotEmpty() -> SelectionToolbar(
                sel, chat,
                onClose = { selected = emptySet() },
                canReply = canSend,
                onReply = {
                    replyTo = sel[0]
                    selected = emptySet()
                },
                onDelete = { dialog = "deleteMessages" },
                onCopy = {
                    copyToClipboard(context, sel.joinToString("\n") { it.text })
                    toast(context, context.getString(R.string.copied_to_clipboard))
                    selected = emptySet()
                },
                onForward = {
                    nav.push(Screen.Forward(sel.map { it.id }))
                    selected = emptySet()
                },
                onShare = {
                    FileActions.share(context, sel.mapNotNull { it.file }, sel.mapNotNull { m -> m.file?.let { m.mime ?: "*/*" } })
                    selected = emptySet()
                },
                onExport = { dialog = "export" },
                onResend = {
                    scope.launch { runCatching { account.resend(sel.map { it.id }) }.onFailure { toast(context, it.message.orEmpty()) } }
                    selected = emptySet()
                },
                onReplyPrivately = {
                    val m = sel[0]
                    selected = emptySet()
                    m.fromContact?.let { contact ->
                        runCatching { account.chatForContact(contact) }
                            .onSuccess {
                                Logx.i("reply privately msg=${m.id} -> chat=$it")
                                nav.push(Screen.Chat(it, replyTo = m.id))
                            }
                            .onFailure { toast(context, it.message.orEmpty()) }
                    }
                },
            )
            searching -> Column {
                SearchToolbar(query, {
                    query = it
                    hitIndex = 0
                }, onBack = {
                    searching = false
                    query = ""
                })
                if (query.isNotBlank()) SearchNavigator(hits.size, hitIndex, onUp = { if (hitIndex < hits.size - 1) hitIndex++ }, onDown = { if (hitIndex > 0) hitIndex-- })
            }
            else -> ChatToolbar(
                chat,
                onBack = { nav.pop() },
                onHeader = { chat?.let { if (!it.isSelf && !it.isDevice) nav.push(Screen.ChatProfile(chatId)) } },
                onSearch = { searching = true },
                onEphemeral = { dialog = "ephemeral" },
                onMute = { if (chat?.muted == true) account.unmute(chatId) else dialog = "mute" },
                onArchive = {
                    val archived = chat?.archived == true
                    account.setArchived(chatId, !archived)
                    if (!archived) nav.pop()
                },
                onAttach = { attachOpen = true },
                onClear = { dialog = "clear" },
                onDelete = { dialog = "deleteChat" },
                onQr = { nav.push(Screen.GroupQr(chatId)) },
                onLeave = { dialog = "leave" },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ChatWallpaper(Modifier.fillMaxSize())
            MessageList(
                messages, byId, chat?.isGroup == true, selected, currentHit, unreadMarker, listState,
                BubbleActions(
                    onClick = { m ->
                        when {
                            selected.isNotEmpty() -> if (m.kind != MessageKind.System) selected = if (m.id in selected) selected - m.id else selected + m.id
                            m.download != null -> download(m)
                            m.packet != null && m.state != MessageState.Failed -> openPacket(nav, m)
                            m.state == MessageState.Failed -> errorText = if (chat?.isGroup == true) groupFailures(context, account.messageDeliveries(m.id), m.error) else localizedError(context, m.error)
                            m.isMedia -> viewer = m.id
                            m.kind == MessageKind.File || m.kind == MessageKind.Audio -> m.file?.let { FileActions.open(context, it, m.mime ?: "*/*") }
                            m.kind == MessageKind.Vcard -> vcardOf = m
                        }
                    },
                    onLongClick = { m -> if (m.kind != MessageKind.System) selected = if (m.id in selected) selected - m.id else selected + m.id },
                    onReply = { if (canSend) replyTo = it },
                    onQuote = { id ->
                        val index = messages.indexOfFirst { it.id == id }
                        if (index < 0) toast(context, context.getString(R.string.ConversationFragment_quoted_message_not_found)) else scope.launch { listState.animateScrollToItem(rowIndex(messages, index, unreadMarker)) }
                    },
                    onReactions = { reactionsOf = it },
                    onReact = { m, e -> react(m, e) },
                    onDownload = { download(it) },
                ),
            )
            if (messages.isEmpty() && chat != null) EmptyChatHint(chat!!)
            androidx.compose.animation.AnimatedVisibility(!atBottom, Modifier.align(Alignment.BottomEnd).padding(12.dp), enter = fadeIn(), exit = fadeOut()) {
                ScrollDownButton(newWhileUp) { scope.launch { listState.animateScrollToItem(maxOf(0, listState.layoutInfo.totalItemsCount - 1)) } }
            }
            if (sel.size == 1 && canSend) ReactionBar(listState, sel[0]) { emoji ->
                selected = emptySet()
                if (emoji == null) pickEmojiFor = sel[0] else react(sel[0], emoji)
            }
            if (attachOpen) {
                Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { attachOpen = false })
                AttachSheet(
                    payment = chat?.let { ch -> if (ch.isGroup) AttachAction.RedPacket else if (ch.isSelf || ch.isDevice) null else AttachAction.Transfer },
                    onAction = { action ->
                        attachOpen = false
                        when (action) {
                            AttachAction.Camera -> withCamera {
                                val f = outgoingFile(context, "photo.jpg").also { cameraFile = it }
                                takePhoto.launch(fileUri(context, f))
                            }
                            AttachAction.Video -> withCamera {
                                val f = outgoingFile(context, "video.mp4").also { cameraFile = it }
                                takeVideo.launch(fileUri(context, f))
                            }
                            AttachAction.Gallery -> gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            AttachAction.Contact -> dialog = "contact"
                            AttachAction.File -> pickFile.launch(arrayOf("*/*"))
                            AttachAction.Transfer -> nav.push(Screen.SendTransfer(chatId))
                            AttachAction.RedPacket -> nav.push(Screen.SendRedPacket(chatId))
                            AttachAction.Sell -> shopOpen = true
                        }
                    },
                    onRecent = {
                        attachOpen = false
                        preview = SendPreview(it, false)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        if (chat?.isRequest == true) {
            RequestBar(
                onAccept = { account.acceptChat(chatId) },
                onBlock = { dialog = "block" },
            )
        } else if (!searching && canSend && !attachOpen) {
            replyTo?.let { ReplyStrip(it) { replyTo = null } }
            InputBar(
                text.orEmpty(),
                { text = it },
                emojiOpen,
                onEmojiToggle = {
                    emojiOpen = !emojiOpen
                    if (emojiOpen) keyboard?.hide() else keyboard?.show()
                },
                onSend = {
                    val body = text.orEmpty().trim()
                    if (body.isEmpty()) return@InputBar
                    val reply = replyTo?.id
                    text = ""
                    replyTo = null
                    account.setDraft(chatId, null)
                    scope.launch {
                        runCatching { account.sendText(chatId, body, reply) }
                            .onSuccess {
                                Logx.d("sent msg=$it chat=$chatId")
                                if (Prefs.inChatSounds) Notifier.play(context, false)
                            }
                            .onFailure {
                                Logx.e("send failed chat=$chatId", it)
                                toast(context, it.message ?: context.getString(R.string.error))
                            }
                    }
                },
                onAttach = {
                    keyboard?.hide()
                    emojiOpen = false
                    attachOpen = !attachOpen
                },
                onVoice = { path, ms -> send(MessageKind.Voice, path, durationMs = ms.toInt()) },
                onKeyboardMedia = { uri, mime -> sendUri(uri) { if (mime == "image/gif") MessageKind.Gif else MessageKind.Sticker } },
                focusKey = replyTo?.id,
            )
            if (emojiOpen) EmojiPanel { text = text.orEmpty() + it }
        }
    }

    preview?.let { p ->
        MediaSendScreen(p.uri, p.isVideo, onSend = { path, caption ->
            preview = null
            send(if (p.isVideo) MessageKind.Video else MessageKind.Image, path, caption)
        }, onCancel = { preview = null })
        BackHandler { preview = null }
    }
    viewer?.let { id ->
        val items = remember(messages) {
            messages.filter { it.isMedia && it.file != null }
                .map { MediaItem(it.id, it.file!!, it.mime ?: "image/jpeg", if (it.outgoing) context.getString(R.string.self) else it.senderName, it.time) }
        }
        val start = items.indexOfFirst { it.id == id }
        if (start >= 0) {
            MediaViewerScreen(items, start, onDelete = { account.deleteMessages(listOf(it.id)) }, onClose = { viewer = null })
            BackHandler { viewer = null }
        }
    }

    errorText?.let { TapeTapeDialog({ errorText = null }, stringResource(R.string.error), it, confirm = stringResource(R.string.ok) to { errorText = null }) }
    when (dialog) {
        "deleteChat" -> TapeTapeDialog(
            { dialog = null },
            message = stringResource(R.string.ask_delete_named_chat, chat?.name.orEmpty()),
            dismiss = stringResource(R.string.cancel) to { dialog = null },
            confirm = stringResource(R.string.delete) to {
                dialog = null
                Logx.i("delete chat $chatId")
                account.deleteChat(chatId)
                nav.pop()
            },
            confirmColor = FailedRed,
        )
        "clear" -> TapeTapeDialog(
            { dialog = null },
            message = pluralStringResource(R.plurals.ask_delete_messages_simple, messages.size, messages.size),
            dismiss = stringResource(R.string.cancel) to { dialog = null },
            confirm = stringResource(R.string.clear_chat) to {
                dialog = null
                Logx.i("clear chat $chatId")
                account.clearChat(chatId)
            },
            confirmColor = FailedRed,
        )
        "deleteMessages" -> TapeTapeDialog(
            { dialog = null },
            message = pluralStringResource(R.plurals.ask_delete_messages_simple, selected.size, selected.size),
            dismiss = stringResource(R.string.cancel) to { dialog = null },
            confirm = stringResource(R.string.delete) to {
                dialog = null
                Logx.i("delete ${selected.size} messages chat=$chatId")
                account.deleteMessages(selected.toList())
                selected = emptySet()
            },
            confirmColor = FailedRed,
        )
        "export" -> TapeTapeDialog(
            { dialog = null },
            message = stringResource(R.string.ask_export_attachment),
            dismiss = stringResource(R.string.cancel) to { dialog = null },
            confirm = stringResource(R.string.menu_export_attachments) to {
                dialog = null
                val files = sel.filter { it.file != null }
                selected = emptySet()
                storage {
                    scope.launch {
                        val ok = files.all { FileActions.export(context, it.file!!, it.fileName ?: File(it.file).name, it.mime ?: "application/octet-stream") }
                        toast(context, context.getString(if (ok) R.string.saved else R.string.error))
                    }
                }
            },
        )
        "leave" -> TapeTapeDialog(
            { dialog = null },
            message = stringResource(R.string.ask_leave_group),
            dismiss = stringResource(R.string.cancel) to { dialog = null },
            confirm = stringResource(R.string.menu_leave_group) to {
                dialog = null
                Logx.i("leave group $chatId")
                scope.launch { runCatching { account.leaveGroup(chatId) }.onFailure { toast(context, it.message.orEmpty()) } }
            },
            confirmColor = FailedRed,
        )
        "ephemeral" -> EphemeralDialog(chat?.ephemeralSeconds ?: 0, { dialog = null }) { seconds ->
            dialog = null
            Logx.i("disappearing chat=$chatId seconds=$seconds")
            scope.launch { runCatching { account.setDisappearing(chatId, seconds) }.onFailure { toast(context, it.message.orEmpty()) } }
        }
        "mute" -> MuteDialog({ dialog = null }) {
            dialog = null
            Logx.i("mute chat=$chatId until=$it")
            account.mute(chatId, it)
        }
        "contact" -> ContactPickerDialog({ dialog = null }) { contact ->
            dialog = null
            sendWith("vcard") { account.sendVcard(chatId, contact.id) }
        }
        "block" -> TapeTapeDialog(
            { dialog = null },
            message = stringResource(R.string.ask_block_contact),
            dismiss = stringResource(R.string.cancel) to { dialog = null },
            confirm = stringResource(R.string.block) to {
                dialog = null
                chat?.contactId?.let {
                    Logx.i("block contact $it from request")
                    account.blockContact(it)
                }
                nav.pop()
            },
            confirmColor = FailedRed,
        )
    }
    reactionsOf?.let { m -> ReactionsDialog(m.id, { reactionsOf = null }) { scope.launch { runCatching { account.react(m.id, "") } } } }
    pickEmojiFor?.let { m ->
        EmojiPickerDialog({ pickEmojiFor = null }) {
            pickEmojiFor = null
            react(m, it)
        }
    }
    vcardOf?.let { m ->
        val card = m.vcard ?: return@let
        TapeTapeDialog(
            { vcardOf = null },
            message = stringResource(R.string.ask_start_chat_with, card.name),
            dismiss = stringResource(R.string.cancel) to { vcardOf = null },
            confirm = stringResource(R.string.ok) to {
                vcardOf = null
                runCatching { account.createChat(card.account, card.name) }
                    .onSuccess {
                        Logx.i("chat from vcard -> $it")
                        nav.replace(Screen.Chat(it))
                    }
                    .onFailure { toast(context, it.message.orEmpty()) }
            },
        )
    }
}

private fun groupFailures(context: Context, deliveries: List<Delivery>, error: String?): String {
    val failed = deliveries.filter { it.state == MessageState.Failed }
    if (failed.isEmpty()) return localizedError(context, error)
    return failed.joinToString("\n\n") { it.name + ": " + localizedError(context, it.error) }
}

private fun rowIndex(messages: List<Message>, index: Int, marker: Int?): Int {
    var extra = 1
    for (i in 1..index) if (!sameDay(messages[i - 1].time, messages[i].time)) extra++
    if (marker != null && messages.indexOfFirst { it.id == marker } in 0..index) extra++
    return index + extra
}

@Composable
private fun EmptyChatHint(chat: Chat) {
    val text = when {
        chat.isSelf -> stringResource(R.string.saved_messages_explain)
        chat.isDevice -> stringResource(R.string.device_talk_explain)
        chat.isGroup -> stringResource(R.string.chat_new_group_hint)
        else -> stringResource(R.string.chat_new_one_to_one_hint, chat.name)
    }
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = Color.White,
            fontSize = 16.sp,
            textAlign = if (chat.isSelf) TextAlign.Start else TextAlign.Center,
            modifier = Modifier
                .background(Color(0x55444444), RoundedCornerShape(8.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ScrollDownButton(count: Int, onClick: () -> Unit) {
    val c = LocalTapeTapeColors.current
    Box {
        Box(
            Modifier
                .size(40.dp)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(c.inBubble)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.menu_scroll_to_bottom), tint = c.preview)
        }
        if (count > 0) {
            Text(
                count.toString(),
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(4.dp, (-4).dp)
                    .background(c.badge, CircleShape)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun ReactionBar(listState: LazyListState, m: Message, onPick: (String?) -> Unit) {
    val c = LocalTapeTapeColors.current
    val density = LocalDensity.current
    val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == m.id } ?: return
    val barHeight = with(density) { 52.dp.roundToPx() }
    val y = (item.offset - barHeight).coerceAtLeast(with(density) { 8.dp.roundToPx() })
    Row(
        Modifier
            .offset { IntOffset(0, y) }
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = if (m.outgoing) androidx.compose.foundation.layout.Arrangement.End else androidx.compose.foundation.layout.Arrangement.Start,
    ) {
        Row(
            Modifier
                .shadow(4.dp, RoundedCornerShape(26.dp))
                .background(if (c.dark) Color(0xFF2B2B2B) else Color.White, RoundedCornerShape(26.dp))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            (QuickReactions + "⋯").forEach { e ->
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (m.reactions.any { it.mine && it.emoji == e }) Color(0x332090EA) else Color.Transparent)
                        .clickable { onPick(if (e == "⋯") null else e) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(e, fontSize = if (e == "⋯") 22.sp else 28.sp, color = c.text, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SearchNavigator(count: Int, index: Int, onUp: () -> Unit, onDown: () -> Unit) {
    val c = LocalTapeTapeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.toolbar)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (count == 0) "0/0" else "${index + 1}/$count", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        ToolbarIcon(Icons.Default.KeyboardArrowUp, onUp)
        ToolbarIcon(Icons.Default.KeyboardArrowDown, onDown)
    }
}

@Composable
private fun SelectionToolbar(
    sel: List<Message>,
    chat: Chat?,
    canReply: Boolean,
    onClose: () -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onResend: () -> Unit,
    onReplyPrivately: () -> Unit,
) {
    val c = LocalTapeTapeColors.current
    val allFiles = sel.isNotEmpty() && sel.all { it.file != null }
    TapeTapeToolbar(sel.size.toString(), onNav = onClose, background = c.selectionToolbar) {
        if (sel.size == 1 && canReply) ToolbarIcon(R.drawable.ic_reply_white_24dp, onReply)
        ToolbarIcon(R.drawable.ic_delete_white_24dp, onDelete)
        if (sel.any { it.text.isNotBlank() }) ToolbarIcon(R.drawable.ic_content_copy_white_24dp, onCopy)
        if (sel.none { it.packet != null }) ToolbarIcon(R.drawable.ic_forward_white_24dp, onForward)
        val more = listOfNotNull(
            MenuItem(stringResource(R.string.menu_share), onClick = onShare).takeIf { allFiles },
            MenuItem(stringResource(R.string.menu_export_attachments), onClick = onExport).takeIf { allFiles },
            MenuItem(stringResource(R.string.reply_privately), onClick = onReplyPrivately).takeIf { chat?.isGroup == true && sel.size == 1 && sel[0].fromContact != null && sel[0].kind != MessageKind.System },
            MenuItem(stringResource(R.string.resend), onClick = onResend).takeIf { sel.all { it.outgoing && it.packet == null } },
        )
        if (more.isNotEmpty()) OverflowMenu(more)
    }
}

@Composable
private fun ChatToolbar(
    chat: Chat?,
    onBack: () -> Unit,
    onHeader: () -> Unit,
    onSearch: () -> Unit,
    onEphemeral: () -> Unit,
    onMute: () -> Unit,
    onArchive: () -> Unit,
    onAttach: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onQr: () -> Unit,
    onLeave: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    TapeTapeToolbar(
        title = null,
        onNav = onBack,
        titleContent = {
            Row(Modifier.weight(1f).clickable(onClick = onHeader), verticalAlignment = Alignment.CenterVertically) {
                if (chat != null) ChatAvatar(chat.name, chat.avatarPath, chat.isSelf, 46.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            chat?.let { chatTitle(it.name, it.isSelf, it.isDevice) }.orEmpty(),
                            color = Color.White,
                            fontSize = fixedSp(20f),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (chat?.verified == true || chat?.isSelf == true) {
                            Spacer(Modifier.width(6.dp))
                            Image(painterResource(R.drawable.ic_verified), null, Modifier.size(20.dp))
                        }
                        if (chat?.muted == true) {
                            Spacer(Modifier.width(6.dp))
                            Image(painterResource(R.drawable.ic_volume_off_white_18dp), null, Modifier.size(18.dp))
                        }
                    }
                    val subtitle = when {
                        chat == null -> null
                        chat.isSelf -> stringResource(R.string.chat_self_talk_subtitle)
                        chat.isDevice -> stringResource(R.string.device_talk_subtitle)
                        chat.isGroup -> pluralStringResource(R.plurals.n_members, chat.memberCount, chat.memberCount)
                        else -> null
                    }
                    if (subtitle != null) Text(subtitle, color = Color(0xCCFFFFFF), fontSize = fixedSp(12f), maxLines = 1)
                }
            }
        },
    ) {
        if (chat == null) return@TapeTapeToolbar
        Box {
            OverflowMenu(
                listOfNotNull(
                    MenuItem(stringResource(R.string.search), onClick = onSearch),
                    MenuItem(stringResource(R.string.ephemeral_messages), onClick = onEphemeral).takeIf { !chat.isSelf },
                    MenuItem(stringResource(if (chat.muted) R.string.menu_unmute else R.string.menu_mute), onClick = onMute).takeIf { !chat.isSelf },
                    MenuItem(stringResource(if (chat.archived) R.string.menu_unarchive_chat else R.string.menu_archive_chat), onClick = onArchive),
                    MenuItem(stringResource(R.string.qrshow_join_group_title), onClick = onQr).takeIf { chat.isGroup && chat.canSend },
                    MenuItem(stringResource(R.string.menu_more_options), submenu = true) { more = true },
                ),
            )
            TapeTapeMenu(
                more, { more = false },
                listOfNotNull(
                    MenuItem(stringResource(R.string.menu_add_attachment), onClick = onAttach).takeIf { chat.canSend },
                    MenuItem(stringResource(R.string.menu_leave_group), onClick = onLeave).takeIf { chat.isGroup && chat.canSend },
                    MenuItem(stringResource(R.string.clear_chat), FailedRed, onClick = onClear),
                    MenuItem(stringResource(R.string.menu_delete_chat), FailedRed, onClick = onDelete),
                ),
                header = stringResource(R.string.menu_more_options),
                offset = MenuOverlap,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageList(
    messages: List<Message>,
    byId: Map<Int, Message>,
    isGroup: Boolean,
    selected: Set<Int>,
    highlight: Int?,
    unreadMarker: Int?,
    listState: LazyListState,
    actions: BubbleActions,
) {
    val context = LocalContext.current
    val transfers by TapeTape.transfers.collectAsState()
    val days = remember(messages) {
        val out = mutableListOf<MutableList<Message>>()
        messages.forEach { m -> if (out.isEmpty() || !sameDay(out.last().last().time, m.time)) out += mutableListOf(m) else out.last() += m }
        out
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp)) {
        days.forEach { day ->
            stickyHeader(key = "d${day.first().id}") { DateHeader(dayHeader(context, day.first().time)) }
            day.forEach { m ->
                if (m.id == unreadMarker) item(key = "new") { NewMessagesMarker() }
                item(key = m.id) {
                    val quoted = m.replyTo?.let { id -> byId[id] ?: remember(id) { runCatching { TapeTape.account?.message(id) }.getOrNull() } }
                    MessageItem(m, quoted, isGroup, m.id in selected, m.id == highlight, actions, transfers[m.id])
                }
            }
        }
    }
}

@Composable
private fun NewMessagesMarker() {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Color(0x55000000)), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.new_messages), color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun ContactPickerDialog(onDismiss: () -> Unit, onPick: (Contact) -> Unit) {
    val account = TapeTape.account ?: return
    val contacts by coreQuery(emptyList<Contact>()) { account.contacts() }
    TapeTapeDialog(onDismiss, title = stringResource(R.string.contact), dismiss = stringResource(R.string.cancel) to onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            items(contacts, key = { it.id }) { ContactRow(it) { onPick(it) } }
        }
    }
}

@Composable
private fun RequestBar(onAccept: () -> Unit, onBlock: () -> Unit) {
    val c = LocalTapeTapeColors.current
    Row(Modifier.fillMaxWidth().background(c.inputBar).padding(vertical = 4.dp)) {
        Text(
            stringResource(R.string.block).uppercase(),
            color = FailedRed,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).clickable(onClick = onBlock).padding(vertical = 14.dp),
        )
        Text(
            stringResource(R.string.accept).uppercase(),
            color = c.dialogAccent,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).clickable(onClick = onAccept).padding(vertical = 14.dp),
        )
    }
}
