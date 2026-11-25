package org.tapetape.v2.ui.profile

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.Prefs
import org.tapetape.v2.core.ChatSummary
import org.tapetape.v2.core.GroupInfo
import org.tapetape.v2.notify.Notifier
import org.tapetape.v2.ui.chatlist.ChatRow
import org.tapetape.v2.ui.group.GroupMembers
import org.tapetape.v2.ui.settings.ChoiceDialog
import org.tapetape.v2.ui.settings.rememberSoundPicker
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.Chat
import org.tapetape.v2.core.Contact
import org.tapetape.v2.core.Message
import org.tapetape.v2.core.MessageKind
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.Screen
import org.tapetape.v2.ui.bubbleTime
import org.tapetape.v2.ui.chat.MuteDialog
import org.tapetape.v2.ui.chat.formatSize
import org.tapetape.v2.ui.chatlist.SectionHeader
import org.tapetape.v2.ui.common.ChatAvatar
import org.tapetape.v2.ui.common.MenuItem
import org.tapetape.v2.ui.common.OverflowMenu
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.coreQuery
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.listTime
import org.tapetape.v2.ui.relayAddress
import org.tapetape.v2.ui.media.FileActions
import org.tapetape.v2.ui.media.MediaGrid
import org.tapetape.v2.ui.media.MediaItem
import org.tapetape.v2.ui.media.MediaViewerScreen
import org.tapetape.v2.ui.theme.Accent
import org.tapetape.v2.ui.theme.FailedRed
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import org.tapetape.v2.ui.theme.fixedSp
import org.tapetape.v2.ui.voice.AudioPlayer

private val GalleryKinds = listOf(MessageKind.Image, MessageKind.Gif, MessageKind.Video)
private val AudioKinds = listOf(MessageKind.Voice, MessageKind.Audio)
private val FileKinds = listOf(MessageKind.File)

@Composable
private fun Tabs(labels: List<Int>, tab: Int, onTab: (Int) -> Unit) {
    val c = LocalTapeTapeColors.current
    Row(Modifier.fillMaxWidth().background(c.toolbar).height(48.dp)) {
        labels.forEachIndexed { i, label ->
            Column(Modifier.height(48.dp).width(IntrinsicSize.Max).widthIn(min = 72.dp).clickable { onTab(i) }) {
                Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(label).uppercase(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Box(Modifier.fillMaxWidth().height(3.dp).background(if (tab == i) Color.White else Color.Transparent))
            }
        }
    }
}

@Composable
fun ChatProfileScreen(chatId: Int) {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val chat by coreQuery<Chat?>(null, chatId) { account.chat(chatId) }
    val contactId = chat?.contactId
    val contact by coreQuery<Contact?>(null, contactId) { contactId?.let { account.contact(it) } }
    val group by coreQuery<GroupInfo?>(null, chatId, chat?.isGroup) { if (chat?.isGroup == true) account.groupInfo(chatId) else null }
    val accountId = remember { account.info().id }
    val pickSound = rememberSoundPicker { value ->
        Logx.i("chat $chatId sound custom=${value != null}")
        Prefs.setChatSound(accountId, chatId, value)
        Notifier.refreshChannels(context)
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var encryption by remember { mutableStateOf<String?>(null) }
    val ch = chat ?: return

    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(
            title = null,
            onNav = { nav.pop() },
            titleContent = {
                ChatAvatar(ch.name, ch.avatarPath, ch.isSelf, 46.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(ch.name, color = Color.White, fontSize = fixedSp(20f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
        ) {
            val ct = contact
            OverflowMenu(
                listOfNotNull(
                    ct?.let { MenuItem(stringResource(R.string.menu_share)) { nav.push(Screen.ShareContact(it.id)) } },
                    ct?.let { MenuItem(stringResource(R.string.menu_edit_name)) { dialog = "name" } },
                    MenuItem(stringResource(R.string.global_menu_edit_desktop)) { nav.push(Screen.NewGroup(editChatId = chatId)) }.takeIf { ch.isGroup && ch.canSend },
                    MenuItem(stringResource(if (ch.muted) R.string.menu_unmute else R.string.menu_mute)) {
                        if (ch.muted) account.unmute(chatId) else dialog = "mute"
                    },
                    MenuItem(stringResource(R.string.pref_sound)) { pickSound(Prefs.chatSound(accountId, chatId) ?: Prefs.sound) },
                    MenuItem(stringResource(R.string.pref_vibrate)) { dialog = "vibrate" },
                    MenuItem(stringResource(R.string.profile_encryption)) {
                        runCatching { account.encryptionInfo(chatId) }
                            .onSuccess { encryption = it }
                            .onFailure { toast(context, it.message.orEmpty()) }
                    },
                    MenuItem(stringResource(R.string.clone_chat)) { nav.push(Screen.NewGroup(cloneFrom = chatId)) }.takeIf { ch.isGroup },
                    ct?.let {
                        MenuItem(stringResource(if (it.blocked) R.string.menu_unblock_contact else R.string.menu_block_contact), FailedRed) { dialog = "block" }
                    },
                ),
            )
        }
        Tabs(listOf(if (ch.isGroup) R.string.tab_group else R.string.tab_contact, R.string.tab_gallery, R.string.audio, R.string.files), tab) { tab = it }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> if (ch.isGroup) group?.let { GroupMembers(chatId, ch.canSend, it.members) } else ContactTab(ch, contact)
                1 -> MediaTab(listOf(chatId), GalleryKinds, R.string.tab_gallery_empty_hint)
                2 -> MediaTab(listOf(chatId), AudioKinds, R.string.tab_audio_empty_hint)
                else -> MediaTab(listOf(chatId), FileKinds, R.string.tab_docs_empty_hint)
            }
        }
    }

    encryption?.let {
        TapeTapeDialog({ encryption = null }, confirm = stringResource(R.string.ok) to { encryption = null }) {
            SelectionContainer { Text(it, color = c.text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        }
    }
    val ct = contact
    when (dialog) {
        "vibrate" -> ChoiceDialog(
            stringResource(R.string.pref_vibrate),
            listOf(stringResource(R.string.def), stringResource(R.string.on), stringResource(R.string.off)),
            Prefs.chatVibrate(accountId, chatId),
            { dialog = null },
        ) {
            dialog = null
            Logx.i("chat $chatId vibrate=$it")
            Prefs.setChatVibrate(accountId, chatId, it)
            Notifier.refreshChannels(context)
        }
        "mute" -> MuteDialog({ dialog = null }) {
            dialog = null
            account.mute(chatId, it)
        }
        "block" -> if (ct != null) TapeTapeDialog(
            { dialog = null },
            message = stringResource(if (ct.blocked) R.string.ask_unblock_contact else R.string.ask_block_contact),
            dismiss = stringResource(R.string.cancel) to { dialog = null },
            confirm = stringResource(if (ct.blocked) R.string.menu_unblock_contact else R.string.menu_block_contact) to {
                dialog = null
                Logx.i("block contact ${ct.id} blocked=${!ct.blocked}")
                if (ct.blocked) account.unblockContact(ct.id) else {
                    account.blockContact(ct.id)
                    nav.reset(Screen.ChatList)
                }
            },
            confirmColor = if (ct.blocked) null else FailedRed,
        )
        "name" -> if (ct != null) {
            var name by remember { mutableStateOf(ct.name) }
            TapeTapeDialog(
                { dialog = null },
                title = stringResource(R.string.menu_edit_name),
                dismiss = stringResource(R.string.cancel) to { dialog = null },
                confirm = stringResource(R.string.ok) to {
                    dialog = null
                    account.setContactName(ct.id, name.trim())
                },
            ) {
                BasicTextField(
                    name,
                    { name = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 18.sp, color = c.text),
                    cursorBrush = SolidColor(c.dialogAccent),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                )
                Box(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(1.dp).background(c.dialogAccent))
            }
        }
    }
}

@Composable
private fun ContactTab(chat: Chat, contact: Contact?) {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val shared by coreQuery(emptyList<ChatSummary>(), contact?.id) {
        val id = contact?.id ?: return@coreQuery emptyList()
        account.sharedChats(id)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeader(stringResource(R.string.info)) }
        if (contact != null) {
            item {
                val status = when {
                    contact.bio.isNotBlank() -> contact.bio
                    contact.lastSeen != null -> stringResource(R.string.last_seen_at, bubbleTime(context, contact.lastSeen))
                    else -> stringResource(R.string.last_seen_unknown)
                }
                Text(status, color = c.text, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
            }
            item {
                val full = relayAddress(contact.account)
                val short = full.substringBefore('@').let { a -> if (a.length > 14) a.take(6) + "…" + a.takeLast(4) else a }
                Text(
                    short,
                    color = c.hint,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("address", full))
                            toast(context, context.getString(R.string.copied_to_clipboard))
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        item {
            Text(
                stringResource(R.string.send_message),
                color = Accent,
                fontSize = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (nav.stack.getOrNull(nav.stack.size - 2) == Screen.Chat(chat.id)) nav.pop() else nav.push(Screen.Chat(chat.id))
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        if (shared.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.profile_shared_chats)) }
            items(shared, key = { it.id }) { ChatRow(it, selected = false, onClick = { nav.push(Screen.Chat(it.id)) }, onLongClick = {}) }
        }
    }
}

@Composable
private fun MediaTab(chatIds: List<Int>, kinds: List<MessageKind>, empty: Int) {
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val messages by coreQuery<List<Message>?>(null, chatIds, kinds) {
        chatIds.flatMap { account.mediaMessages(it, kinds) }.filter { it.file != null }.sortedByDescending { it.time }
    }
    var viewer by remember { mutableStateOf<Int?>(null) }
    val list = messages ?: return
    if (list.isEmpty()) {
        Text(
            stringResource(empty),
            color = c.hint,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxSize().padding(32.dp).padding(top = 120.dp),
        )
        return
    }
    val items = remember(list) {
        list.map { MediaItem(it.id, it.file!!, it.mime ?: "application/octet-stream", if (it.outgoing) context.getString(R.string.self) else it.senderName, it.time) }
    }
    if (kinds == GalleryKinds) {
        MediaGrid(items, onOpen = { viewer = it }, modifier = Modifier.fillMaxSize())
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { m ->
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (kinds == AudioKinds) {
                            AudioPlayer(m.file!!, m.durationMs, Modifier.weight(1f))
                        } else {
                            Row(
                                Modifier.weight(1f).clickable { FileActions.open(context, m.file!!, m.mime ?: "*/*") },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(44.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                                    Image(painterResource(R.drawable.ic_insert_drive_file_white_24dp), null, Modifier.size(24.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(m.fileName.orEmpty(), color = c.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(formatSize(m.size), color = c.preview, fontSize = 13.sp)
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(listTime(context, m.time), color = c.preview, fontSize = 12.sp)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                }
            }
        }
    }
    viewer?.let { start ->
        MediaViewerScreen(items, start, onDelete = { account.deleteMessages(listOf(it.id)) }, onClose = { viewer = null })
        androidx.activity.compose.BackHandler { viewer = null }
    }
}

@Composable
fun AllMediaScreen() {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val account = TapeTape.account ?: return
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val chatIds by coreQuery(emptyList<Int>()) { (account.chats(null) + account.archivedChats()).map { it.id } }
    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(stringResource(R.string.menu_all_media), onNav = { nav.pop() })
        Tabs(listOf(R.string.tab_gallery, R.string.audio, R.string.files), tab) { tab = it }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> MediaTab(chatIds, GalleryKinds, R.string.tab_all_media_empty_hint)
                1 -> MediaTab(chatIds, AudioKinds, R.string.tab_all_media_empty_hint)
                else -> MediaTab(chatIds, FileKinds, R.string.tab_all_media_empty_hint)
            }
        }
    }
}
