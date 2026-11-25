package org.tapetape.v2.ui.settings

import android.content.Context
import android.os.Environment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import org.tapetape.v2.Prefs
import org.tapetape.v2.core.AutoDownload
import org.tapetape.v2.core.MediaQuality
import org.tapetape.v2.core.Settings
import org.tapetape.v2.ui.common.DialogButton
import org.tapetape.v2.ui.common.ProgressDialog
import org.tapetape.v2.ui.common.SecondaryButton
import org.tapetape.v2.ui.media.FileActions
import org.tapetape.v2.ui.media.rememberStorageAccess
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.Contact
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.Screen
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.coreQuery
import org.tapetape.v2.ui.contacts.ContactRow
import org.tapetape.v2.ui.theme.LocalTapeTapeColors

private val DeleteAfter = listOf<Pair<Int?, Int>>(
    null to R.string.never,
    3600 to R.string.autodel_after_1_hour,
    86400 to R.string.autodel_after_1_day,
    604800 to R.string.autodel_after_1_week,
    3024000 to R.string.after_5_weeks,
    31536000 to R.string.autodel_after_1_year,
)

private val AutoDownloadLabels = listOf(null, "160 KiB", "640 KiB", "5 MiB", "25 MiB")

@Composable
fun ChatsSettingsScreen() {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(account.settings()) }
    var enterSends by remember { mutableStateOf(Prefs.enterSends) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var deleteChoice by remember { mutableStateOf<Int?>(null) }
    var deleteCount by remember { mutableIntStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val auth = rememberDeviceAuth(stringResource(R.string.pref_backup))
    val storage = rememberStorageAccess()

    fun update(next: Settings) {
        settings = next
        account.setSettings(next)
        Logx.i("settings updated $next")
    }

    fun export(ids: List<Int>) {
        exporting = true
        scope.launch {
            val dir = File(context.cacheDir, "backup").apply { deleteRecursively(); mkdirs() }
            val written = runCatching {
                ids.map { id ->
                    val path = TapeTape.core.account(id).exportBackup(dir.path, null)
                    val name = File(path).name
                    if (!FileActions.export(context, path, name, "application/octet-stream")) throw IllegalStateException("copy failed")
                    File(path).delete()
                    name
                }
            }
            exporting = false
            written.onSuccess {
                Logx.i("backup exported ${it.size}")
                result = context.getString(R.string.pref_backup_written_to_x, it.joinToString { n -> Environment.DIRECTORY_DOWNLOADS + "/" + n })
            }.onFailure {
                Logx.w("backup export failed", it)
                result = context.getString(R.string.export_aborted) + "\n\n" + (it.message ?: "")
            }
        }
    }

    val deleteLabel = stringResource(DeleteAfter.firstOrNull { it.first == settings.deleteDeviceAfter }?.second ?: R.string.never)
    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(stringResource(R.string.pref_chats_and_media), onNav = { nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            PrefRow(stringResource(R.string.pref_blocked_contacts), onClick = { nav.push(Screen.Blocked) })
            SwitchRow(stringResource(R.string.pref_read_receipts), stringResource(R.string.pref_read_receipts_explain), settings.readReceipts) {
                update(settings.copy(readReceipts = it))
            }
            SwitchRow(stringResource(R.string.pref_enter_sends), stringResource(R.string.pref_enter_sends_explain), enterSends) {
                enterSends = it
                Prefs.enterSends = it
            }
            PrefRow(
                stringResource(R.string.pref_outgoing_media_quality),
                stringResource(if (settings.mediaQuality == MediaQuality.Balanced) R.string.pref_outgoing_balanced else R.string.pref_outgoing_worse),
                onClick = { dialog = "quality" },
            )
            PrefRow(
                stringResource(R.string.auto_download_messages),
                autoDownloadLabel(context, settings.autoDownload.ordinal),
                onClick = { dialog = "download" },
            )
            PrefSection(stringResource(R.string.delete_old_messages))
            PrefRow(stringResource(R.string.autodel_device_title), deleteLabel, onClick = { dialog = "delete" })
            PrefRow(stringResource(R.string.autodel_server_title), stringResource(R.string.autodel_at_once))
            PrefSection(stringResource(R.string.pref_backup))
            PrefRow(stringResource(R.string.export_backup_desktop), onClick = { auth { storage { dialog = "export" } } })
            PrefRow(stringResource(R.string.wallet_export_mnemonic), stringResource(R.string.wallet_export_mnemonic_summary), onClick = { auth { dialog = "mnemonic" } })
        }
    }

    when (dialog) {
        "quality" -> ChoiceDialog(
            stringResource(R.string.pref_outgoing_media_quality),
            listOf(stringResource(R.string.pref_outgoing_balanced), stringResource(R.string.pref_outgoing_worse)),
            settings.mediaQuality.ordinal,
            { dialog = null },
        ) {
            dialog = null
            update(settings.copy(mediaQuality = MediaQuality.entries[it]))
        }
        "download" -> ChoiceDialog(
            stringResource(R.string.auto_download_messages),
            AutoDownload.entries.map { autoDownloadLabel(context, it.ordinal) },
            settings.autoDownload.ordinal,
            { dialog = null },
        ) {
            dialog = null
            update(settings.copy(autoDownload = AutoDownload.entries[it]))
        }
        "delete" -> ChoiceDialog(
            stringResource(R.string.autodel_device_title),
            DeleteAfter.map { stringResource(it.second) },
            DeleteAfter.indexOfFirst { it.first == settings.deleteDeviceAfter },
            { dialog = null },
        ) { i ->
            val seconds = DeleteAfter[i].first
            if (seconds == null) {
                dialog = null
                update(settings.copy(deleteDeviceAfter = null))
            } else {
                deleteChoice = i
                deleteCount = account.countMessagesOlderThan(seconds)
                dialog = "deleteConfirm"
            }
        }
        "deleteConfirm" -> {
            val choice = DeleteAfter[deleteChoice ?: 0]
            var understood by remember { mutableStateOf(false) }
            TapeTapeDialog(
                { dialog = null },
                message = stringResource(R.string.autodel_device_ask, deleteCount, stringResource(choice.second)),
                dismiss = stringResource(R.string.cancel) to { dialog = null },
                confirm = stringResource(R.string.ok) to {
                    if (understood) {
                        dialog = null
                        update(settings.copy(deleteDeviceAfter = choice.first))
                    }
                },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { understood = !understood }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(understood, { understood = it }, colors = CheckboxDefaults.colors(checkedColor = c.dialogAccent, uncheckedColor = c.hint))
                    Text(stringResource(R.string.autodel_confirm), color = c.text, fontSize = 16.sp)
                }
            }
        }
        "export" -> {
            val accounts = remember { TapeTape.core.accounts() }
            val me = remember { account.info() }
            TapeTapeDialog(
                { dialog = null },
                title = stringResource(R.string.pref_backup),
                message = stringResource(R.string.pref_backup_export_explain),
            ) {
                Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
                    SecondaryButton(stringResource(R.string.pref_backup_export_x, org.tapetape.v2.ui.relayAddress(me.account)), Modifier.fillMaxWidth()) {
                        dialog = null
                        export(listOf(me.id))
                    }
                    if (accounts.size > 1) {
                        Spacer(Modifier.height(12.dp))
                        SecondaryButton(stringResource(R.string.pref_backup_export_all, accounts.size), Modifier.fillMaxWidth()) {
                            dialog = null
                            export(accounts.map { it.id })
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        DialogButton(stringResource(R.string.cancel), c.dialogAccent) { dialog = null }
                    }
                }
            }
        }
        "mnemonic" -> MnemonicDialog { dialog = null }
    }
    if (exporting) ProgressDialog(stringResource(R.string.one_moment))
    result?.let { TapeTapeDialog({ result = null }, message = it, confirm = stringResource(R.string.ok) to { result = null }) }
}

private fun autoDownloadLabel(context: Context, index: Int): String =
    AutoDownloadLabels[index]?.let { context.getString(R.string.up_to_x, it) } ?: context.getString(R.string.pref_show_emails_all)

@Composable
fun BlockedContactsScreen() {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val account = TapeTape.account ?: return
    val blocked by coreQuery(emptyList<Contact>()) { account.blockedContacts() }
    var ask by remember { mutableStateOf<Contact?>(null) }
    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(stringResource(R.string.pref_blocked_contacts), onNav = { nav.pop() })
        if (blocked.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.blocked_empty_hint), fontSize = 18.sp, color = c.hint)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(blocked, key = { it.id }) { ContactRow(it) { ask = it } }
            }
        }
    }
    ask?.let { contact ->
        TapeTapeDialog(
            onDismiss = { ask = null },
            message = stringResource(R.string.ask_unblock_contact),
            confirm = stringResource(R.string.menu_unblock_contact) to {
                account.unblockContact(contact.id)
                Logx.i("unblock contact ${contact.id}")
                ask = null
            },
            dismiss = stringResource(R.string.cancel) to { ask = null },
        )
    }
}
