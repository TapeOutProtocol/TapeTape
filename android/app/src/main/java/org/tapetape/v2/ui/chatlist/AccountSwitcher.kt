package org.tapetape.v2.ui.chatlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.AccountInfo
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.Screen
import org.tapetape.v2.ui.common.Avatar
import org.tapetape.v2.ui.common.MenuItem
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.TapeTapeMenu
import org.tapetape.v2.ui.common.UnderlineField
import org.tapetape.v2.ui.common.coreQuery
import org.tapetape.v2.ui.relayAddress
import org.tapetape.v2.ui.theme.FailedRed
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import java.io.File

@Composable
fun AccountSwitcher(onDismiss: () -> Unit) {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val accounts by coreQuery(TapeTape.core.accounts()) { TapeTape.core.accounts() }
    val current = TapeTape.accountId.value
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var tagFor by remember { mutableStateOf<AccountInfo?>(null) }
    var deleteFor by remember { mutableStateOf<AccountInfo?>(null) }

    TapeTapeDialog(
        onDismiss,
        title = stringResource(R.string.switch_account),
        neutral = stringResource(R.string.connectivity).uppercase() to {
            onDismiss()
            nav.push(Screen.Connectivity)
        },
        dismiss = stringResource(R.string.cancel).uppercase() to onDismiss,
    ) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            accounts.forEach { a ->
                Box {
                    AccountRow(
                        a, a.id == current,
                        onClick = {
                            onDismiss()
                            if (a.id != current) {
                                Logx.i("switch account ${a.id}")
                                TapeTape.scope.launch { TapeTape.activate(a.id) }
                            }
                        },
                        onLongClick = { menuFor = a.id },
                    )
                    TapeTapeMenu(
                        menuFor == a.id, { menuFor = null },
                        listOf(
                            MenuItem(stringResource(if (a.muted) R.string.menu_unmute else R.string.menu_mute)) {
                                Logx.i("account ${a.id} muted=${!a.muted}")
                                TapeTape.core.account(a.id).setAccountMuted(!a.muted)
                            },
                            MenuItem(stringResource(R.string.profile_tag)) { tagFor = a },
                            MenuItem(stringResource(R.string.move_to_top)) {
                                Logx.i("account ${a.id} moved to top")
                                TapeTape.core.moveAccountToTop(a.id)
                                TapeTape.touch()
                            },
                            MenuItem(stringResource(R.string.delete), FailedRed) { deleteFor = a },
                        ),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = {
                        onDismiss()
                        nav.push(Screen.AddProfile)
                    })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).background(c.actionCircle, CircleShape), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_add_white_24dp), null, Modifier.size(22.dp))
                }
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.add_account), color = c.preview, fontSize = 16.sp)
            }
        }
    }

    tagFor?.let { a ->
        var tag by remember { mutableStateOf(a.tag) }
        TapeTapeDialog(
            { tagFor = null },
            title = stringResource(R.string.profile_tag),
            message = stringResource(R.string.profile_tag_explain),
            dismiss = stringResource(R.string.cancel) to { tagFor = null },
            confirm = stringResource(R.string.ok) to {
                tagFor = null
                Logx.i("account ${a.id} tag set")
                TapeTape.core.account(a.id).setAccountTag(tag.trim())
            },
        ) {
            UnderlineField(tag, { tag = it }, stringResource(R.string.profile_tag_hint), Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp))
        }
    }
    deleteFor?.let { a ->
        val size = remember(a.id) { dirSize(File(TapeTape.dataDir(), "accounts/${a.id}")) }
        TapeTapeDialog(
            { deleteFor = null },
            title = stringResource(R.string.delete_account),
            dismiss = stringResource(R.string.cancel) to { deleteFor = null },
            confirm = stringResource(R.string.delete) to {
                deleteFor = null
                TapeTape.scope.launch {
                    TapeTape.removeAccount(a.id)
                    if (TapeTape.accountId.value == null) nav.reset(Screen.Welcome)
                }
            },
            confirmColor = FailedRed,
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(a.name, a.avatarPath, 48.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text(relayAddress(a.account), color = c.preview, fontSize = 13.sp)
                        if (size > 0) Text(android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, size), color = c.preview, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.delete_account_explain_with_name, a.name), color = c.dialogText, fontSize = 16.sp, lineHeight = 19.sp)
            }
        }
    }
}

private fun dirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountRow(a: AccountInfo, current: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = LocalTapeTapeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (current) c.selected else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(a.name, a.avatarPath, 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                a.name.ifBlank { relayAddress(a.account) },
                color = c.text,
                fontSize = 16.sp,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(a.tag.ifBlank { relayAddress(a.account) }, color = c.preview, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (a.muted) {
            Image(painterResource(R.drawable.ic_volume_off_white_18dp), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(c.preview))
            Spacer(Modifier.width(6.dp))
        }
        if (a.unread > 0) {
            Text(
                a.unread.toString(),
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(if (a.muted) c.badgeMuted else c.badge, CircleShape)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
    }
}
