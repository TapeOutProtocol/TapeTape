package org.tapetape.v2.ui.contacts

import androidx.compose.foundation.Image
import org.tapetape.v2.ui.theme.FailedRed
import org.tapetape.v2.ui.common.MenuItem
import org.tapetape.v2.ui.common.OverflowMenu
import org.tapetape.v2.ui.common.TapeTapeToolbar
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.Contact
import org.tapetape.v2.core.TapeTapeAccount
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.Screen
import org.tapetape.v2.ui.common.Avatar
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.ToolbarIcon
import org.tapetape.v2.ui.common.coreQuery
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import org.tapetape.v2.ui.theme.fixedSp

fun openChatWithContact(account: TapeTapeAccount, contact: Contact): Int? =
    runCatching { account.chatForContact(contact.id) }.onFailure { Logx.w("no chat for contact ${contact.id}", it) }.getOrNull()

@Composable
fun SearchToolbar(query: String, onQuery: (String) -> Unit, onBack: () -> Unit, autoFocus: Boolean = true, menu: List<MenuItem> = emptyList()) {
    val c = LocalTapeTapeColors.current
    val inverted = !c.dark
    val bg = if (inverted) Color.White else c.toolbar
    val fg = if (inverted) Color(0xFF5F6368) else Color.White
    val focus = remember { FocusRequester() }
    Column(Modifier.fillMaxWidth().background(c.toolbar)) {
        Row(
            Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .height(56.dp)
                .background(bg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(4.dp))
            ToolbarIcon(R.drawable.ic_arrow_back_white_24dp, onBack, tint = fg)
            Spacer(Modifier.width(20.dp))
            BasicTextField(
                query,
                onQuery,
                singleLine = true,
                textStyle = TextStyle(fontSize = fixedSp(20f), color = if (inverted) Color.Black else Color.White),
                cursorBrush = SolidColor(if (inverted) Color(0xFF42A5F5) else Color.White),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text(stringResource(R.string.search), fontSize = fixedSp(20f), color = fg.copy(alpha = 0.7f))
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) ToolbarIcon(R.drawable.ic_close_white_24dp, { onQuery("") }, tint = fg)
            if (menu.isNotEmpty()) OverflowMenu(menu, tint = fg)
            Spacer(Modifier.width(4.dp))
        }
    }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactRow(contact: Contact, selected: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = LocalTapeTapeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(if (selected) c.selected else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 24.dp, end = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(contact.name, contact.avatarPath, 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(contact.name, fontSize = 16.sp, color = c.actionText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (contact.verified) {
                    Spacer(Modifier.width(6.dp))
                    Image(painterResource(R.drawable.ic_verified), null, Modifier.size(18.dp))
                }
            }
            Text(contact.address, fontSize = 14.sp, color = c.preview, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ActionRow(icon: Int, label: String, onClick: () -> Unit) {
    val c = LocalTapeTapeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clickable(onClick = onClick)
            .padding(start = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.actionCircle), contentAlignment = Alignment.Center) {
            Image(painterResource(icon), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White))
        }
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = c.actionText)
    }
}

@Composable
fun ContactsScreen() {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val account = TapeTape.account ?: return
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Contact?>(null) }
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val contacts by coreQuery(emptyList<Contact>()) { account.contacts() }
    val shown = contacts.filter { query.isBlank() || it.name.contains(query, true) || it.address.contains(query, true) }
    val selectedContacts = contacts.filter { it.id in selected }
    fun toggle(id: Int) { selected = if (id in selected) selected - id else selected + id }
    BackHandler(selected.isNotEmpty()) { selected = emptySet() }

    Column(Modifier.fillMaxSize().background(c.background)) {
        if (selected.isEmpty()) ContactsToolbar(query, { query = it }) { nav.pop() }
        else TapeTapeToolbar(selected.size.toString(), navIcon = R.drawable.ic_close_white_24dp, onNav = { selected = emptySet() }) {
            ToolbarIcon(R.drawable.ic_delete_white_24dp, { confirmDelete = true })
            OverflowMenu(
                listOfNotNull(
                    selectedContacts.singleOrNull()?.let { ct ->
                        MenuItem(stringResource(R.string.menu_view_profile)) {
                            selected = emptySet()
                            openChatWithContact(account, ct)?.let { nav.push(Screen.ChatProfile(it)) }
                        }
                    },
                    MenuItem(stringResource(R.string.delete_contact)) { confirmDelete = true },
                    MenuItem(stringResource(R.string.menu_select_all)) { selected = shown.map { it.id }.toSet() },
                ),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (query.isBlank() && selected.isEmpty()) item {
                Spacer(Modifier.height(4.dp))
                ActionRow(R.drawable.ic_qr_code, stringResource(R.string.menu_new_contact)) { nav.push(Screen.Qr) }
                ActionRow(R.drawable.ic_add_white_24dp, stringResource(R.string.menu_new_group)) { nav.push(Screen.NewGroup()) }
            }
            items(shown, key = { it.id }) { contact ->
                ContactRow(contact, contact.id in selected, onLongClick = { toggle(contact.id) }) {
                    if (selected.isNotEmpty()) toggle(contact.id) else confirm = contact
                }
            }
        }
    }

    if (confirmDelete && selectedContacts.isNotEmpty()) {
        TapeTapeDialog(
            { confirmDelete = false },
            message = selectedContacts.singleOrNull()?.let { stringResource(R.string.ask_delete_contact, it.name) } ?: stringResource(R.string.ask_delete_contacts),
            dismiss = stringResource(R.string.cancel) to { confirmDelete = false },
            confirm = stringResource(R.string.delete) to {
                confirmDelete = false
                Logx.i("delete ${selectedContacts.size} contacts")
                selectedContacts.forEach { ct -> runCatching { account.deleteContact(ct.id) }.onFailure { Logx.w("delete contact ${ct.id} failed", it) } }
                selected = emptySet()
            },
            confirmColor = FailedRed,
        )
    }

    confirm?.let { contact ->
        TapeTapeDialog(
            { confirm = null },
            message = stringResource(R.string.ask_start_chat_with, contact.name),
            dismiss = stringResource(R.string.cancel) to { confirm = null },
            confirm = stringResource(R.string.ok) to {
                confirm = null
                openChatWithContact(account, contact)?.let { nav.replace(Screen.Chat(it)) }
            },
        )
    }
}

@Composable
private fun ContactsToolbar(query: String, onQuery: (String) -> Unit, onBack: () -> Unit) {
    val c = LocalTapeTapeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.toolbar)
            .statusBarsPadding()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(4.dp))
        ToolbarIcon(R.drawable.ic_arrow_back_white_24dp, onBack)
        Spacer(Modifier.width(4.dp))
        BasicTextField(
            query,
            onQuery,
            singleLine = true,
            textStyle = TextStyle(fontSize = fixedSp(20f), color = Color.White),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier.weight(1f).padding(end = 16.dp),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text(stringResource(R.string.search), fontSize = fixedSp(20f), color = Color.White.copy(alpha = 0.6f))
                    inner()
                }
            },
        )
    }
}
