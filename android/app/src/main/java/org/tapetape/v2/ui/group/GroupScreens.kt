package org.tapetape.v2.ui.group

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.Contact
import org.tapetape.v2.core.GroupMember
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.Screen
import org.tapetape.v2.ui.relayAddress
import org.tapetape.v2.ui.common.Avatar
import org.tapetape.v2.ui.common.AvatarChooser
import org.tapetape.v2.ui.common.MenuItem
import org.tapetape.v2.ui.common.OverflowMenu
import org.tapetape.v2.ui.common.ProgressDialog
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.ToolbarIcon
import org.tapetape.v2.ui.common.UnderlineField
import org.tapetape.v2.ui.common.copyToClipboard
import org.tapetape.v2.ui.common.coreQuery
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.contacts.ActionRow
import org.tapetape.v2.ui.contacts.SearchToolbar
import org.tapetape.v2.ui.qr.InviteCard
import org.tapetape.v2.ui.qr.LinkBox
import org.tapetape.v2.ui.theme.FailedRed
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import kotlinx.coroutines.launch

@Composable
private fun GroupAvatarPicker(path: String?, onClick: () -> Unit) {
    val c = LocalTapeTapeColors.current
    val bitmap = remember(path) { path?.let { android.graphics.BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    val mod = Modifier.size(64.dp).clip(CircleShape).clickable(onClick = onClick)
    if (bitmap != null) {
        Image(bitmap, null, mod, contentScale = ContentScale.Crop)
    } else {
        Box(mod.background(c.avatarPlaceholder), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_group_white_24dp), null, Modifier.size(28.dp))
        }
    }
}

@Composable
fun MemberRow(name: String, address: String, avatar: String?, verified: Boolean = false, onClick: () -> Unit = {}, onLongClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val c = LocalTapeTapeColors.current
    @OptIn(ExperimentalFoundationApi::class)
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, avatar, 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 16.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (verified) {
                    Spacer(Modifier.width(6.dp))
                    Image(painterResource(R.drawable.ic_verified), null, Modifier.size(18.dp))
                }
            }
            Text(address, fontSize = 14.sp, color = c.preview, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
fun NewGroupScreen(editChatId: Int?, cloneFrom: Int?) {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val scope = rememberCoroutineScope()
    val source = remember { (editChatId ?: cloneFrom)?.let { runCatching { account.groupInfo(it) }.getOrNull() } }
    val me = remember { account.info() }
    var name by rememberSaveable { mutableStateOf(source?.name.orEmpty()) }
    var avatar by rememberSaveable { mutableStateOf(source?.avatarPath) }
    var members by remember { mutableStateOf(if (editChatId == null) source?.members.orEmpty().mapNotNull { it.contactId }.toSet() else emptySet()) }
    var chooser by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val contacts by coreQuery(emptyList<Contact>()) { account.contacts() }
    val byId = contacts.associateBy { it.id }

    fun save() {
        if (name.isBlank()) {
            toast(context, context.getString(R.string.group_please_enter_group_name))
            return
        }
        working = true
        scope.launch {
            runCatching {
                if (editChatId != null) {
                    if (name.trim() != source?.name) account.setGroupName(editChatId, name.trim())
                    if (avatar != source?.avatarPath) account.setGroupAvatar(editChatId, avatar)
                    editChatId
                } else {
                    account.createGroup(name.trim(), avatar, members.toList())
                }
            }.onSuccess { id ->
                Logx.i(if (editChatId != null) "group $id edited" else "group $id created members=${members.size}")
                if (editChatId != null) nav.pop() else nav.replace(Screen.Chat(id))
            }.onFailure {
                Logx.w("group save failed", it)
                toast(context, groupError(context, it.message))
            }
            working = false
        }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(
            stringResource(if (editChatId != null) R.string.menu_edit_group else R.string.menu_new_group),
            navIcon = R.drawable.ic_close_white_24dp,
            onNav = { nav.pop() },
        ) {
            ToolbarIcon(R.drawable.ic_check_white_24dp, { save() })
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    GroupAvatarPicker(avatar) { chooser = true }
                    Spacer(Modifier.width(18.dp))
                    UnderlineField(
                        name, { name = it }, stringResource(R.string.group_name), Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                }
            }
            if (editChatId == null) {
                item { ActionRow(R.drawable.ic_add_white_24dp, stringResource(R.string.group_add_members)) { picking = true } }
                item { MemberRow(stringResource(R.string.self), relayAddress(me.account), me.avatarPath, verified = true) }
                items(members.mapNotNull { byId[it] }, key = { it.id }) { ct ->
                    MemberRow(ct.name, relayAddress(ct.account), ct.avatarPath, ct.verified, onClick = { members = members - ct.id }) {
                        Image(
                            painterResource(R.drawable.ic_close_white_24dp),
                            stringResource(R.string.remove_desktop),
                            Modifier.size(24.dp).clickable { members = members - ct.id },
                            colorFilter = ColorFilter.tint(c.hint),
                        )
                    }
                }
            }
        }
    }

    if (chooser) AvatarChooser(avatar != null, { chooser = false }) { avatar = it }
    if (working) ProgressDialog(stringResource(R.string.one_moment))
    if (picking) {
        MemberPicker(emptySet(), members, onCancel = { picking = false }) {
            members = it
            picking = false
        }
    }
}

@Composable
fun MemberPicker(exclude: Set<Int>, initial: Set<Int>, onCancel: () -> Unit, onDone: (Set<Int>) -> Unit) {
    val c = LocalTapeTapeColors.current
    val account = TapeTape.account ?: return
    val contacts by coreQuery(emptyList<Contact>()) { account.contacts().filter { it.id !in exclude } }
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val shown = contacts.filter { query.isBlank() || it.name.contains(query, true) || it.address.contains(query, true) }
    Dialog(onCancel, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    Column(Modifier.fillMaxSize().background(c.background)) {
        if (searching) {
            SearchToolbar(query, { query = it }, onBack = {
                searching = false
                query = ""
            })
        } else {
            TapeTapeToolbar(stringResource(R.string.group_add_members), onNav = onCancel) {
                ToolbarIcon(R.drawable.ic_search_white_24dp, { searching = true })
                ToolbarIcon(R.drawable.ic_check_white_24dp, { onDone(selected) })
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { ct ->
                val toggle = { selected = if (ct.id in selected) selected - ct.id else selected + ct.id }
                MemberRow(ct.name, relayAddress(ct.account), ct.avatarPath, ct.verified, onClick = toggle) {
                    Checkbox(
                        ct.id in selected,
                        { toggle() },
                        colors = CheckboxDefaults.colors(checkedColor = c.dialogAccent, uncheckedColor = c.hint),
                    )
                }
            }
        }
    }
    }
}

@Composable
fun GroupQrScreen(chatId: Int) {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    var resets by remember { mutableIntStateOf(0) }
    var showLink by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val chat by coreQuery(runCatching { account.chat(chatId) }.getOrNull(), chatId) { account.chat(chatId) }
    val link by coreQuery("", resets) { runCatching { account.groupInviteLink(chatId) }.onFailure { Logx.w("group link failed", it) }.getOrDefault("") }
    val ch = chat ?: return

    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(stringResource(R.string.qrshow_join_group_title), onNav = { nav.pop() }) {
            OverflowMenu(listOf(MenuItem(stringResource(R.string.withdraw_qr_code)) { confirmReset = true }))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            InviteCard(ch.name, ch.avatarPath, link, stringResource(R.string.qrshow_join_group_hint, ch.name), onLink = { showLink = true }, onScan = null)
        }
    }

    if (showLink) {
        TapeTapeDialog(
            { showLink = false },
            message = stringResource(R.string.share_invite_link_explain),
            neutral = stringResource(R.string.menu_copy_to_clipboard) to {
                copyToClipboard(context, link)
                toast(context, context.getString(R.string.copied_to_clipboard))
                showLink = false
            },
            dismiss = stringResource(R.string.cancel) to { showLink = false },
            confirm = stringResource(R.string.menu_share) to {
                showLink = false
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null))
            },
        ) {
            LinkBox(link)
        }
    }
    if (confirmReset) {
        TapeTapeDialog(
            { confirmReset = false },
            message = stringResource(R.string.withdraw_verifygroup_explain, ch.name),
            dismiss = stringResource(R.string.cancel) to { confirmReset = false },
            confirm = stringResource(R.string.reset) to {
                confirmReset = false
                account.resetGroupInvite(chatId)
                resets++
                Logx.i("group $chatId invite reset")
            },
        )
    }
}

@Composable
fun GroupMembers(chatId: Int, canSend: Boolean, members: List<GroupMember>) {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<GroupMember?>(null) }
    val ordered = members.filter { it.contactId != null } + members.filter { it.contactId == null }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                context.resources.getQuantityString(R.plurals.n_members, members.size, members.size),
                color = c.preview,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().background(c.pinned).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (canSend) {
            item { ActionRow(R.drawable.ic_add_white_24dp, stringResource(R.string.group_add_members)) { picking = true } }
            item { ActionRow(R.drawable.ic_qr_code, stringResource(R.string.qrshow_join_group_title)) { nav.push(Screen.GroupQr(chatId)) } }
        }
        items(ordered, key = { it.contactId ?: -1 }) { m ->
            val self = m.contactId == null
            MemberRow(
                if (self) stringResource(R.string.self) else m.name,
                relayAddress(m.account),
                m.avatarPath,
                verified = self,
                onClick = {
                    m.contactId?.let { id ->
                        runCatching { account.chatForContact(id) }.onSuccess { nav.push(Screen.ChatProfile(it)) }
                    }
                },
                onLongClick = if (!self && canSend) ({ remove = m }) else null,
            )
        }
    }

    if (picking) {
        MemberPicker(members.mapNotNull { it.contactId }.toSet(), emptySet(), onCancel = { picking = false }) { ids ->
            picking = false
            if (ids.isNotEmpty()) scope.launch {
                runCatching { account.addGroupMembers(chatId, ids.toList()) }
                    .onSuccess { Logx.i("group $chatId added ${ids.size} members") }
                    .onFailure { toast(context, groupError(context, it.message)) }
            }
        }
    }
    remove?.let { m ->
        TapeTapeDialog(
            { remove = null },
            message = stringResource(R.string.ask_remove_members, m.name),
            dismiss = stringResource(R.string.cancel) to { remove = null },
            confirm = stringResource(R.string.remove_desktop) to {
                remove = null
                scope.launch {
                    runCatching { account.removeGroupMember(chatId, m.contactId!!) }
                        .onSuccess { Logx.i("group $chatId removed member ${m.contactId}") }
                        .onFailure { toast(context, groupError(context, it.message)) }
                }
            },
            confirmColor = FailedRed,
        )
    }
}

private fun groupError(context: android.content.Context, message: String?): String = when {
    message == null -> context.getString(R.string.error)
    message.contains("group-full") -> context.getString(R.string.v2_group_full)
    else -> message
}
