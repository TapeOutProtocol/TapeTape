package org.tapetape.v2.ui.qr

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.Screen
import org.tapetape.v2.ui.common.ProgressDialog
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.copyToClipboard
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.parseInvite
import kotlinx.coroutines.launch

@Composable
fun ScanResult(text: String, onDone: () -> Unit) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val invite = remember(text) { parseInvite(text) }
    val proxy = remember(text) { if (invite == null) org.tapetape.v2.ui.proxy.parseProxyLink(text) else null }
    val copy = stringResource(R.string.global_menu_edit_copy_desktop) to {
        copyToClipboard(context, text)
        toast(context, context.getString(R.string.copied_to_clipboard))
        onDone()
    }

    when {
        error != null -> TapeTapeDialog(onDone, stringResource(R.string.error), error, confirm = stringResource(R.string.ok) to onDone)
        working -> ProgressDialog(stringResource(R.string.one_moment))
        invite != null -> TapeTapeDialog(
            onDone,
            message = if (invite.group != null) stringResource(R.string.qrscan_ask_join_group, invite.group) else stringResource(R.string.ask_start_chat_with, invite.name),
            dismiss = stringResource(R.string.cancel) to onDone,
            confirm = stringResource(R.string.ok) to {
                val account = TapeTape.account
                if (account != null) {
                    working = true
                    scope.launch {
                        runCatching { account.acceptInvite(invite.link) }
                            .onSuccess { chatId ->
                                Logx.i("invite accepted chat=$chatId group=${invite.group != null}")
                                working = false
                                onDone()
                                if (nav.top == Screen.Qr || nav.top == Screen.Contacts) nav.replace(Screen.Chat(chatId)) else nav.push(Screen.Chat(chatId))
                            }
                            .onFailure {
                                Logx.w("invite failed", it)
                                working = false
                                error = it.message ?: it.toString()
                            }
                    }
                }
            },
        )
        proxy != null -> org.tapetape.v2.ui.proxy.ProxyLinkDialog(text, proxy, onDone)
        text.startsWith("http://") || text.startsWith("https://") -> TapeTapeDialog(
            onDone,
            message = stringResource(R.string.qrscan_contains_url, text),
            neutral = copy,
            dismiss = stringResource(R.string.cancel) to onDone,
            confirm = stringResource(R.string.open) to {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text)))
                onDone()
            },
        )
        else -> TapeTapeDialog(
            onDone,
            message = stringResource(R.string.qrscan_contains_text, text),
            dismiss = stringResource(R.string.cancel) to onDone,
            confirm = copy,
        )
    }
}
