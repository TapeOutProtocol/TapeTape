package org.tapetape.v2.ui.chatlist

import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.common.ChatAvatar
import org.tapetape.v2.ui.common.chatTitle
import org.tapetape.v2.core.MessageKind
import org.tapetape.v2.core.Message
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.ui.chat.systemText
import org.tapetape.v2.core.AccountInfo
import org.tapetape.v2.core.CoreException
import org.tapetape.v2.core.TapeTapeAccount
import org.tapetape.v2.notify.Shortcuts
import org.tapetape.v2.ui.chat.fitImage
import org.tapetape.v2.ui.chat.outgoingFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.ChatSummary
import org.tapetape.v2.core.Connectivity
import org.tapetape.v2.core.Contact
import org.tapetape.v2.core.MessageState
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.Screen
import org.tapetape.v2.ui.common.Avatar
import org.tapetape.v2.ui.common.MenuItem
import org.tapetape.v2.ui.common.OverflowMenu
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.ToolbarIcon
import org.tapetape.v2.ui.proxy.ProxyToolbarIcon
import org.tapetape.v2.ui.common.coreQuery
import org.tapetape.v2.ui.contacts.ContactRow
import org.tapetape.v2.ui.contacts.openChatWithContact
import org.tapetape.v2.ui.contacts.SearchToolbar
import org.tapetape.v2.ui.listTime
import org.tapetape.v2.ui.theme.FailedRed
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import org.tapetape.v2.ui.theme.fixedSp

private enum class ListMode { Main, Archive, Forward }

private const val UNREAD_FILTER = "is:unread"

@Composable
fun ChatListScreen() = ChatListContent(ListMode.Main)

@Composable
fun ArchiveScreen() = ChatListContent(ListMode.Archive)

@Composable
fun ForwardScreen(msgIds: List<Int>) = ChatListContent(ListMode.Forward, msgIds)

@Composable
fun ShareContactScreen(contactId: Int) = ChatListContent(ListMode.Forward, shareContact = contactId)

@Composable
fun ShareScreen(share: Screen.Share) = ChatListContent(ListMode.Forward, share = share)

@Composable
private fun ChatListContent(mode: ListMode, forwardIds: List<Int> = emptyList(), shareContact: Int? = null, share: Screen.Share? = null) {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val scope = rememberCoroutineScope()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var muteFor by remember { mutableStateOf<List<Int>?>(null) }
    var startChat by remember { mutableStateOf<Contact?>(null) }
    var forwardTo by remember { mutableStateOf<ChatSummary?>(null) }
    var snackbar by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var switcher by remember { mutableStateOf(false) }

    val raw = query.takeIf { searching && it.isNotBlank() }
    val unreadOnly = raw?.contains(UNREAD_FILTER) == true
    val q = if (unreadOnly) raw!!.replace(UNREAD_FILTER, "").trim().ifEmpty { null } else raw
    val loaded by coreQuery<List<ChatSummary>?>(null, q, mode, unreadOnly) {
        if (mode == ListMode.Archive) account.archivedChats()
        else account.chats(q).filter { !unreadOnly || it.unread > 0 }
    }
    val chats = loaded.orEmpty()
    val archivedCount by coreQuery(0, mode, raw) { if (mode == ListMode.Main && raw == null) account.archivedChats().size else 0 }
    val contacts by coreQuery(emptyList<Contact>(), q, unreadOnly) {
        if (q == null || unreadOnly || mode == ListMode.Archive) emptyList() else account.contacts().filter { it.name.contains(q, true) || it.address.contains(q, true) }
    }
    val hits by coreQuery(emptyList<Message>(), q, unreadOnly) {
        if (q == null || unreadOnly || mode != ListMode.Main) emptyList() else account.searchMessages(null, q).reversed()
    }
    val chatNames = remember(chats) { chats.associate { it.id to it } }
    val info by coreQuery(account.info()) { account.info() }
    val others by coreQuery(emptyList<AccountInfo>()) { TapeTape.core.accounts().filter { it.id != info.id } }
    val otherUnread = others.filter { !it.muted }.sumOf { it.unread }
    val connectivity by coreQuery(Connectivity.Connecting) { account.connectivity() }
    if (mode == ListMode.Main && raw == null) LaunchedEffect(chats) { if (loaded != null) withContext(Dispatchers.Default) { Shortcuts.update(context, info.id, chats) } }
    if (share?.chatId != null) LaunchedEffect(loaded) { if (forwardTo == null) forwardTo = chats.firstOrNull { it.id == share.chatId } }
    val selectedChats = chats.filter { it.id in selected }

    fun archive(ids: List<Int>, archived: Boolean) {
        Logx.i("archive ${ids.size} chats archived=$archived")
        ids.forEach { account.setArchived(it, archived) }
        val text = context.resources.getQuantityString(if (archived) R.plurals.chat_archived else R.plurals.chat_unarchived, ids.size, ids.size)
        snackbar = text to { ids.forEach { account.setArchived(it, !archived) } }
    }

    fun forward(target: ChatSummary) {
        if (share != null) {
            scope.launch {
                runCatching { shareTo(context, account, target.id, share) }
                    .onSuccess {
                        Logx.i("shared ${share.files.size} files to chat=${target.id}")
                        nav.replace(Screen.Chat(target.id))
                    }
                    .onFailure {
                        Logx.w("share failed", it)
                        toast(context, it.message.orEmpty())
                    }
            }
            return
        }
        scope.launch {
            runCatching { if (shareContact != null) account.sendVcard(target.id, shareContact) else account.forward(forwardIds, target.id) }
                .onSuccess {
                    Logx.i("forwarded ${forwardIds.size} msgs contact=$shareContact to chat=${target.id}")
                    if (target.isSelf) {
                        toast(context, context.getString(R.string.saved))
                        nav.pop()
                    } else {
                        nav.replace(Screen.Chat(target.id))
                    }
                }
                .onFailure { toast(context, it.message.orEmpty()) }
        }
    }

    BackHandler(selected.isNotEmpty()) { selected = emptySet() }
    BackHandler(searching && selected.isEmpty()) {
        searching = false
        query = ""
    }
    LaunchedEffect(snackbar) {
        if (snackbar != null) {
            delay(3000)
            snackbar = null
        }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        when {
            selected.isNotEmpty() -> TapeTapeToolbar(
                title = selected.size.toString(),
                onNav = { selected = emptySet() },
                background = c.selectionToolbar,
            ) {
                val allPinned = selectedChats.all { it.pinned }
                val allMuted = selectedChats.all { it.muted }
                val ids = selectedChats.map { it.id }
                ToolbarIcon(if (allPinned) R.drawable.ic_unpin_white else R.drawable.ic_pin_white, {
                    Logx.i("pin ${ids.size} chats pinned=${!allPinned}")
                    ids.forEach { account.setPinned(it, !allPinned) }
                    selected = emptySet()
                })
                ToolbarIcon(if (mode == ListMode.Archive) R.drawable.ic_unarchive_white_24dp else R.drawable.ic_archive_white_24dp, {
                    archive(ids, mode != ListMode.Archive)
                    selected = emptySet()
                })
                ToolbarIcon(R.drawable.ic_delete_white_24dp, { confirmDelete = true })
                OverflowMenu(
                    listOf(
                        MenuItem(stringResource(if (allMuted) R.string.menu_unmute else R.string.menu_mute)) {
                            if (allMuted) {
                                ids.forEach { account.unmute(it) }
                                selected = emptySet()
                            } else {
                                muteFor = ids
                            }
                        },
                        MenuItem(stringResource(R.string.mark_as_read)) {
                            ids.forEach { account.markRead(it) }
                            selected = emptySet()
                        },
                        MenuItem(stringResource(R.string.menu_select_all)) { selected = chats.map { it.id }.toSet() },
                    ),
                )
            }
            searching -> SearchToolbar(query, { query = it }, onBack = {
                searching = false
                query = ""
            }, menu = listOf(MenuItem(stringResource(R.string.search_unread)) { if (UNREAD_FILTER !in query) query = "$UNREAD_FILTER $query".trim() }))
            mode == ListMode.Archive -> TapeTapeToolbar(stringResource(R.string.chat_archived_label), onNav = { nav.pop() }) {
                OverflowMenu(listOf(MenuItem(stringResource(R.string.mark_all_as_read)) { account.markAllRead(true) }))
            }
            mode == ListMode.Forward -> TapeTapeToolbar(stringResource(if (shareContact != null || share != null) R.string.chat_share_with_title else R.string.forward_to), onNav = { nav.pop() }) {
                ToolbarIcon(R.drawable.ic_search_white_24dp, { searching = true })
            }
            else -> TapeTapeToolbar(
                title = null,
                navIcon = null,
                titleContent = {
                    Row(Modifier.weight(1f).clickable { switcher = true }, verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Avatar(info.name, info.avatarPath, 35.dp)
                        if (otherUnread > 0) Box(Modifier.align(Alignment.TopEnd).offset(3.dp, (-3).dp).size(12.dp).background(FailedRed, CircleShape))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        when (connectivity) {
                            Connectivity.Connected -> if (others.isEmpty()) stringResource(R.string.app_name) else info.name.ifBlank { stringResource(R.string.app_name) }
                            Connectivity.Connecting -> stringResource(R.string.connectivity_connecting)
                            Connectivity.NotConnected -> stringResource(R.string.connectivity_not_connected)
                        },
                        color = Color.White,
                        fontSize = fixedSp(20f),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    }
                },
            ) {
                ProxyToolbarIcon()
                ToolbarIcon(R.drawable.ic_search_white_24dp, { searching = true })
                ToolbarIcon(R.drawable.ic_qr_code, { nav.push(Screen.Qr) })
                OverflowMenu(
                    listOf(
                        MenuItem(stringResource(R.string.invite_friends)) {
                            val text = context.getString(R.string.invite_friends_text, account.inviteLink())
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                        },
                        MenuItem(stringResource(R.string.menu_new_chat)) { nav.push(Screen.Contacts) },
                        MenuItem(stringResource(R.string.menu_all_media)) { nav.push(Screen.AllMedia) },
                        MenuItem(stringResource(R.string.menu_settings)) { nav.push(Screen.Settings) },
                    ),
                )
            }
        }

        val bottomBar = mode == ListMode.Main && !searching
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = if (bottomBar) 64.dp else 0.dp)) {
                if (archivedCount > 0) item(key = "archived") { ArchivedRow { nav.push(Screen.Archive) } }
                if (raw != null && chats.isNotEmpty()) item { SectionHeader(pluralStringResource(R.plurals.n_chats, chats.size, chats.size)) }
                items(chats, key = { "c${it.id}" }) { chat ->
                    ChatRow(
                        chat,
                        selected = chat.id in selected,
                        showArchived = mode == ListMode.Archive || (raw != null && chat.archived),
                        onClick = {
                            when {
                                selected.isNotEmpty() -> selected = selected.toggle(chat.id)
                                mode == ListMode.Forward -> if (chat.isSelf) forward(chat) else forwardTo = chat
                                else -> nav.push(Screen.Chat(chat.id))
                            }
                        },
                        onLongClick = { if (mode != ListMode.Forward) selected = selected.toggle(chat.id) },
                    )
                }
                if (contacts.isNotEmpty()) {
                    item { SectionHeader(pluralStringResource(R.plurals.n_contacts, contacts.size, contacts.size)) }
                    items(contacts, key = { "p${it.id}" }) { contact -> ContactRow(contact) { startChat = contact } }
                }
                if (hits.isNotEmpty()) {
                    item { SectionHeader(pluralStringResource(R.plurals.n_messages, hits.size, hits.size)) }
                    items(hits, key = { "m${it.id}" }) { m ->
                        MessageHitRow(m, chatNames[m.chatId]?.name ?: m.senderName, q.orEmpty()) { nav.push(Screen.Chat(m.chatId)) }
                    }
                }
            }
            if (loaded != null && chats.isEmpty() && contacts.isEmpty() && hits.isEmpty() && archivedCount == 0) {
                Text(
                    when {
                        raw != null -> stringResource(R.string.search_no_result_for_x, raw)
                        mode == ListMode.Archive -> stringResource(R.string.archive_empty_hint)
                        else -> stringResource(R.string.v2_chat_list_empty)
                    },
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    color = c.hint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp)
                        .padding(bottom = if (raw == null) 56.dp else 0.dp),
                )
            }
            if (bottomBar) BottomBar(Modifier.align(Alignment.BottomCenter))
            snackbar?.let { (text, undo) ->
                Snackbar(text, Modifier.align(Alignment.BottomCenter)) {
                    undo()
                    snackbar = null
                }
            }
        }
    }

    if (confirmDelete) {
        TapeTapeDialog(
            { confirmDelete = false },
            message = pluralStringResource(R.plurals.ask_delete_chat, selected.size, selected.size),
            dismiss = stringResource(R.string.cancel) to { confirmDelete = false },
            confirm = stringResource(R.string.delete) to {
                Logx.i("delete ${selected.size} chats")
                selected.forEach { account.deleteChat(it) }
                selected = emptySet()
                confirmDelete = false
            },
            confirmColor = FailedRed,
        )
    }
    muteFor?.let { ids ->
        org.tapetape.v2.ui.chat.MuteDialog({ muteFor = null }) { until ->
            Logx.i("mute ${ids.size} chats until=$until")
            ids.forEach { account.mute(it, until) }
            muteFor = null
            selected = emptySet()
        }
    }
    startChat?.let { contact ->
        TapeTapeDialog(
            { startChat = null },
            message = stringResource(R.string.ask_start_chat_with, contact.name),
            dismiss = stringResource(R.string.cancel) to { startChat = null },
            confirm = stringResource(R.string.ok) to {
                startChat = null
                openChatWithContact(account, contact)?.let { nav.push(Screen.Chat(it)) }
            },
        )
    }
    if (switcher) AccountSwitcher { switcher = false }
    forwardTo?.let { target ->
        TapeTapeDialog(
            { forwardTo = null },
            message = when {
                share == null -> stringResource(R.string.ask_forward, target.name)
                share.files.isEmpty() -> stringResource(R.string.ask_forward, target.name)
                else -> stringResource(R.string.ask_send_files_to_chat, share.files.size, target.name)
            },
            dismiss = stringResource(R.string.cancel) to { forwardTo = null },
            confirm = stringResource(R.string.ok) to {
                forwardTo = null
                forward(target)
            },
        )
    }
}

@Composable
private fun Snackbar(text: String, modifier: Modifier, onUndo: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0xFF323232))
            .padding(start = 24.dp, end = 8.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.undo).uppercase(),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onUndo).padding(16.dp),
        )
    }
}

@Composable
private fun ArchivedRow(onClick: () -> Unit) {
    val c = LocalTapeTapeColors.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().height(51.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_archive_outline_24dp), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(c.preview))
            Spacer(Modifier.width(26.dp))
            Text(stringResource(R.string.chat_archived_label), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.name)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
    }
}

@Composable
private fun MessageHitRow(m: Message, chatName: String, query: String, onClick: () -> Unit) {
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().height(71.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(chatName, null, 48.dp)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row {
                    Text(chatName, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(listTime(context, m.time), fontSize = 12.sp, color = c.preview)
                }
                Spacer(Modifier.height(5.dp))
                Text(org.tapetape.v2.ui.chat.highlighted(m.text, query), fontSize = 14.sp, color = c.preview, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
    }
}

private suspend fun shareTo(context: Context, account: TapeTapeAccount, chatId: Int, share: Screen.Share) {
    if (share.files.isEmpty()) {
        account.sendText(chatId, share.text.orEmpty(), null)
        return
    }
    val max = account.maxAttachmentSize()
    share.files.forEachIndexed { i, path ->
        val mime = share.mimes.getOrNull(i).orEmpty()
        val kind = when {
            mime == "image/gif" -> MessageKind.Gif
            mime.startsWith("image/") -> MessageKind.Image
            mime.startsWith("video/") -> MessageKind.Video
            mime.startsWith("audio/") -> MessageKind.Audio
            else -> MessageKind.File
        }
        val side = if (account.settings().mediaQuality == org.tapetape.v2.core.MediaQuality.Worse) 640 else 1280
        val file = if (kind == MessageKind.Image) withContext(Dispatchers.IO) { fitImage(path, max, outgoingFile(context, "image.jpg"), side) } else path
        if (java.io.File(file).length() > max) throw CoreException(context.getString(R.string.relay_error_too_large))
        account.sendMedia(chatId, kind, file, share.text.takeIf { i == 0 && !it.isNullOrBlank() }, null, null)
    }
}

private fun summaryPreview(context: Context, chat: ChatSummary): String? {
    val text = chat.lastText.orEmpty()
    val kind = chat.lastKind ?: return chat.lastText
    val placeholder = when (kind) {
        MessageKind.Image -> "📷 " + context.getString(R.string.image)
        MessageKind.Gif -> context.getString(R.string.gif)
        MessageKind.Sticker -> context.getString(R.string.sticker)
        MessageKind.Video -> "🎥 " + context.getString(R.string.video)
        MessageKind.Voice -> "🎤 " + context.getString(R.string.voice_message)
        MessageKind.Audio -> "🎵 " + context.getString(R.string.audio)
        MessageKind.File -> "📎 " + context.getString(R.string.file)
        MessageKind.Vcard -> "👤 " + context.getString(R.string.contact)
        else -> null
    }
    val body = when {
        placeholder == null -> text
        text.isNotBlank() && (kind == MessageKind.Image || kind == MessageKind.Video) -> placeholder.substringBefore(' ') + " " + text
        else -> placeholder
    }
    val prefix = when {
        !chat.isGroup || kind == MessageKind.System -> ""
        chat.lastFromMe -> context.getString(R.string.self) + ": "
        chat.lastSender != null -> chat.lastSender + ": "
        else -> ""
    }
    return prefix + body
}

@Composable
private fun OutlinedTag(text: String) {
    val c = LocalTapeTapeColors.current
    Text(
        text,
        fontSize = 12.sp,
        color = c.preview,
        modifier = Modifier
            .border(1.dp, c.preview, RoundedCornerShape(2.dp))
            .padding(horizontal = 4.dp),
    )
}

private fun Set<Int>.toggle(id: Int) = if (id in this) this - id else this + id

@Composable
fun SectionHeader(text: String) {
    val c = LocalTapeTapeColors.current
    Text(
        text,
        fontSize = 16.sp,
        color = c.preview,
        modifier = Modifier
            .fillMaxWidth()
            .background(c.pinned)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatRow(chat: ChatSummary, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, showArchived: Boolean = false) {
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val unread = chat.unread > 0
    val system by coreQuery(null as String?, chat.id, chat.lastTime, chat.lastKind) {
        if (chat.lastKind == MessageKind.System) TapeTape.account?.messages(chat.id, null, 1)?.lastOrNull()?.takeIf { it.system != null }?.let { systemText(context, it) } else null
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (chat.pinned) c.pinned else c.background)
            .background(if (selected) c.selected else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(71.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatAvatar(chat.name, chat.avatarPath, chat.isSelf, 48.dp)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (chat.muted) {
                            Image(painterResource(R.drawable.ic_volume_off_grey600_18dp), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(c.preview))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            chatTitle(chat.name, chat.isSelf, chat.isDevice),
                            fontSize = 16.sp,
                            fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                            color = c.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (chat.isSelf) {
                            Spacer(Modifier.width(6.dp))
                            Image(painterResource(R.drawable.ic_verified), null, Modifier.size(18.dp))
                        }
                    }
                    if (chat.pinned) {
                        Image(painterResource(R.drawable.ic_pinned_chatlist), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(c.preview))
                        Spacer(Modifier.width(4.dp))
                    }
                    if (chat.lastText != null || chat.lastKind != null) Text(listTime(context, chat.lastTime), fontSize = 12.sp, color = c.preview)
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val draft = chat.draft?.takeIf { it.isNotBlank() }
                    Text(
                        when {
                            draft != null -> stringResource(R.string.draft) + ": " + draft
                            else -> system ?: summaryPreview(context, chat) ?: stringResource(R.string.chat_no_messages)
                        },
                        fontSize = 14.sp,
                        fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                        color = if (unread) c.name else c.preview,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 16.dp),
                    )
                    when {
                        showArchived || chat.isRequest -> OutlinedTag(stringResource(if (chat.isRequest) R.string.chat_request_label else R.string.chat_archived_label))
                        unread -> Box(
                            Modifier
                                .sizeIn(minWidth = 24.dp, minHeight = 24.dp)
                                .clip(CircleShape)
                                .background(if (chat.muted) c.badgeMuted else c.badge)
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(chat.unread.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        chat.lastState != null -> DeliveryIcon(chat.lastState, c.outMeta, Modifier.size(20.dp))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
    }
}

@Composable
fun DeliveryIcon(state: MessageState, tint: Color, modifier: Modifier = Modifier) {
    when (state) {
        MessageState.Pending -> {
            val angle by rememberInfiniteTransition(label = "sending").animateFloat(
                0f, 360f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart), label = "angle",
            )
            Image(painterResource(R.drawable.ic_delivery_status_sending), null, modifier.rotate(angle), colorFilter = ColorFilter.tint(tint))
        }
        MessageState.Sent, MessageState.Delivered ->
            Image(painterResource(R.drawable.ic_delivery_status_sent), null, modifier, colorFilter = ColorFilter.tint(tint))
        MessageState.Read ->
            Image(painterResource(R.drawable.ic_delivery_status_read), null, modifier, colorFilter = ColorFilter.tint(tint))
        MessageState.Failed ->
            Image(painterResource(R.drawable.ic_delivery_status_failed), null, modifier)
        else -> {}
    }
}

@Composable
private fun BottomBar(modifier: Modifier) {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    Column(modifier.fillMaxWidth().background(c.background)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.divider))
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            BarButton(R.drawable.ic_tab_contacts, stringResource(R.string.tab_contact), Modifier.weight(1f)) { nav.push(Screen.Contacts) }
            BarButton(R.drawable.ic_tab_square, stringResource(R.string.square_title), Modifier.weight(1f)) { nav.push(Screen.Square) }
            BarButton(R.drawable.ic_tab_settings, stringResource(R.string.menu_settings), Modifier.weight(1f)) { nav.push(Screen.Settings) }
        }
    }
}

@Composable
private fun BarButton(icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalTapeTapeColors.current
    Column(
        modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(icon), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(c.tabIcon ?: c.preview))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, lineHeight = 13.sp, color = c.tabLabel)
    }
}
