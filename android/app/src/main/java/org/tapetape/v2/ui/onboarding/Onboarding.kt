package org.tapetape.v2.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.CoreException
import org.tapetape.v2.ui.settings.SecureWindow
import org.tapetape.v2.core.BackupProtection
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.Screen
import org.tapetape.v2.ui.common.AvatarChooser
import org.tapetape.v2.ui.common.AvatarPicker
import org.tapetape.v2.ui.common.MenuItem
import org.tapetape.v2.ui.common.OverflowMenu
import org.tapetape.v2.ui.proxy.ProxyToolbarIcon
import org.tapetape.v2.ui.settings.SwitchRow
import androidx.compose.runtime.collectAsState
import org.tapetape.v2.ui.common.PrimaryButton
import org.tapetape.v2.ui.common.ProgressDialog
import org.tapetape.v2.ui.common.SecondaryButton
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.TapeTapeMenu
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.ToolbarIcon
import org.tapetape.v2.ui.common.UnderlineField
import org.tapetape.v2.ui.common.copyToClipboard
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.theme.Accent
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private object Draft {
    var name by mutableStateOf("")
    var avatar by mutableStateOf<String?>(null)
    var node by mutableStateOf<String?>(null)
}

private const val NODE_SITE = "www.taperelay.com"

private suspend fun createAccount(mnemonic: String, welcome: String) {
    val id = TapeTape.core.addAccount(mnemonic, Draft.node)
    Logx.i("account $id added node=${Draft.node ?: "auto"}")
    if (Draft.name.isNotBlank() || Draft.avatar != null) TapeTape.core.account(id).setProfile(Draft.name.trim(), Draft.avatar)
    // 和 iOS 一样：新账号的「设备消息」里放一条欢迎语
    runCatching { TapeTape.core.account(id).addDeviceMessage(welcome) }
        .onFailure { Logx.w("welcome message failed", it) }
    withContext(Dispatchers.Main) {
        Draft.name = ""
        Draft.avatar = null
    }
    TapeTape.activate(id)
}

@Composable
fun WelcomeScreen(adding: Boolean = false) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var haveProfile by remember { mutableStateOf(false) }
    var backup by remember { mutableStateOf<Pair<String, BackupProtection>?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val file = withContext(Dispatchers.IO) { org.tapetape.v2.ui.chat.importUri(context, uri).first }
                file.path to TapeTape.core.backupProtection(file.path)
            }.onSuccess {
                Logx.i("backup picked protection=${it.second}")
                backup = it
            }.onFailure {
                Logx.w("backup open failed", it)
                error = it.message ?: it.toString()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Image(painterResource(R.drawable.splash), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (adding) {
            TapeTapeToolbar(stringResource(R.string.add_account), onNav = { nav.pop() }, background = Color.Transparent)
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 136.dp)
                .width(228.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .clickable { nav.push(Screen.CreateProfile) },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.onboarding_create_instant_account).uppercase(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(BorderStroke(1.dp, Color.Black), RoundedCornerShape(8.dp))
                    .clickable { haveProfile = true },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.onboarding_alternative_logins).uppercase(), color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    if (haveProfile) {
        TapeTapeDialog(
            { haveProfile = false },
            title = stringResource(R.string.onboarding_alternative_logins),
            dismiss = stringResource(R.string.cancel) to { haveProfile = false },
        ) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                SecondaryButton(stringResource(R.string.wallet_import_mnemonic), Modifier.fillMaxWidth()) {
                    haveProfile = false
                    nav.push(Screen.ImportProfile)
                }
                Spacer(Modifier.height(12.dp))
                SecondaryButton(stringResource(R.string.import_backup_title), Modifier.fillMaxWidth()) {
                    haveProfile = false
                    picker.launch(arrayOf("*/*"))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    backup?.let { (path, protection) ->
        var secret by remember { mutableStateOf("") }
        TapeTapeDialog(
            { backup = null },
            title = stringResource(R.string.import_backup_title),
            secure = true,
            dismiss = stringResource(R.string.cancel) to { backup = null },
            confirm = stringResource(R.string.ok) to {
                val value = if (protection == BackupProtection.RecoveryPhrase) normalizeMnemonic(secret) else secret
                backup = null
                working = true
                scope.launch {
                    runCatching { TapeTape.core.importBackup(path, value) }
                        .onSuccess { id ->
                            Logx.i("backup restored as account $id")
                            java.io.File(path).delete()
                            TapeTape.activate(id)
                        }
                        .onFailure {
                            Logx.w("backup restore failed", it)
                            error = it.message ?: it.toString()
                        }
                    working = false
                }
            },
        ) {
            UnderlineField(
                secret, { secret = it },
                stringResource(if (protection == BackupProtection.RecoveryPhrase) R.string.wallet_mnemonic_input_hint else R.string.password),
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp),
                singleLine = protection == BackupProtection.Passphrase,
                minLines = if (protection == BackupProtection.RecoveryPhrase) 3 else 1,
                keyboardOptions = KeyboardOptions(keyboardType = if (protection == BackupProtection.Passphrase) KeyboardType.Password else KeyboardType.Text),
            )
        }
    }
    if (working) ProgressDialog(stringResource(R.string.one_moment))
    error?.let { TapeTapeDialog({ error = null }, stringResource(R.string.error), it, confirm = stringResource(R.string.ok) to { error = null }) }
}

@Composable
private fun ServerRow() {
    val c = LocalTapeTapeColors.current
    var nodes by remember { mutableStateOf<List<String>?>(null) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        nodes = try {
            TapeTape.core.nodes()
        } catch (e: CoreException) {
            Logx.w("node list failed", e)
            (1..5).map { n -> "relay$n.taperelay.com" }
        }
    }
    val automatic = stringResource(R.string.automatic)
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.pref_server), fontSize = 16.sp, color = c.text, modifier = Modifier.width(65.dp))
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = nodes != null) { open = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (nodes == null) stringResource(R.string.loading) else Draft.node ?: automatic,
                    fontSize = 16.sp,
                    color = c.text,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Default.ArrowDropDown, null, tint = c.text)
                Spacer(Modifier.width(12.dp))
            }
            TapeTapeMenu(open, { open = false }, listOf(MenuItem(automatic) { Draft.node = null }) + nodes.orEmpty().map { MenuItem(it) { Draft.node = it } }, offset = DpOffset((-8).dp, (-36).dp))
        }
    }
}

@Composable
fun CreateProfileScreen() {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chooser by remember { mutableStateOf(false) }
    var moreOptions by remember { mutableStateOf(false) }
    var generating by remember { mutableStateOf(false) }
    var mnemonic by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(stringResource(R.string.onboarding_create_instant_account), onNav = { nav.pop() }) {
            ProxyToolbarIcon()
            OverflowMenu(listOf(MenuItem(stringResource(R.string.proxy_settings)) { nav.push(Screen.Proxy) }, MenuItem(stringResource(R.string.pref_view_log)) { nav.push(Screen.Log) }))
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                AvatarPicker(Draft.avatar, 64.dp) { chooser = true }
                Spacer(Modifier.width(14.dp))
                UnderlineField(
                    Draft.name, { Draft.name = it }, stringResource(R.string.pref_your_name),
                    Modifier.weight(1f).padding(end = 4.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
            }
            Spacer(Modifier.height(1.dp))
            ServerRow()
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.set_name_and_avatar_explain), fontSize = 14.sp, color = c.hint)
            Text(
                stringResource(R.string.instant_onboarding_agree_instance, NODE_SITE),
                fontSize = 16.sp,
                color = Accent,
                modifier = Modifier
                    .padding(top = 20.dp)
                    .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://$NODE_SITE"))) },
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(stringResource(R.string.instant_onboarding_create), Modifier.fillMaxWidth()) {
                if (Draft.name.isBlank()) {
                    toast(context, context.getString(R.string.please_enter_name))
                } else {
                    generating = true
                    scope.launch {
                        mnemonic = withContext(Dispatchers.Default) { TapeTape.core.generateMnemonic() }
                        generating = false
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            SecondaryButton(stringResource(R.string.menu_more_options), Modifier.fillMaxWidth()) { moreOptions = true }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (chooser) AvatarChooser(Draft.avatar != null, { chooser = false }) { Draft.avatar = it }
    if (generating) ProgressDialog(stringResource(R.string.wallet_generating_mnemonic))
    if (working) ProgressDialog(stringResource(R.string.one_moment))
    error?.let { TapeTapeDialog({ error = null }, stringResource(R.string.error), it, confirm = stringResource(R.string.ok) to { error = null }) }
    if (moreOptions) {
        TapeTapeDialog(
            { moreOptions = false },
            title = stringResource(R.string.menu_more_options),
            dismiss = stringResource(R.string.cancel) to { moreOptions = false },
        ) {
            SecondaryButton(
                stringResource(R.string.onboarding_alternative_logins),
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                moreOptions = false
                nav.push(Screen.ImportProfile)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    mnemonic?.let { words ->
        TapeTapeDialog(
            onDismiss = {},
            title = stringResource(R.string.wallet_backup_title),
            message = stringResource(R.string.wallet_backup_message, words),
            cancelable = false,
            secure = true,
            dismiss = stringResource(R.string.cancel) to { mnemonic = null },
            confirm = stringResource(R.string.wallet_copy_and_login) to {
                copyToClipboard(context, words, sensitive = true)
                toast(context, context.getString(R.string.wallet_mnemonic_copied))
                mnemonic = null
                working = true
                scope.launch {
                    runCatching { createAccount(words, context.getString(R.string.v2_device_welcome)) }.onFailure {
                        Logx.e("create account failed", it)
                        error = (it as? CoreException)?.message ?: it.toString()
                    }
                    working = false
                }
            },
        )
    }
}

private fun normalizeMnemonic(text: String) =
    text.lowercase().replace(Regex("[^a-z]+"), " ").trim()

@Composable
fun ImportProfileScreen() {
    SecureWindow()
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmAddress by remember { mutableStateOf<Pair<String, String>?>(null) }

    val submit = {
        val words = normalizeMnemonic(text)
        when {
            words.isEmpty() -> toast(context, context.getString(R.string.wallet_enter_valid_mnemonic))
            words.split(" ").size != 12 -> invalid = true
            else -> runCatching { TapeTape.core.addressForMnemonic(words) }
                .onSuccess { confirmAddress = words to it }
                .onFailure { error = context.getString(R.string.wallet_derive_failed, it.message.orEmpty()) }
        }
    }

    val import = { words: String ->
        working = true
        scope.launch {
            runCatching { createAccount(words, context.getString(R.string.v2_device_welcome)) }
                .onSuccess { toast(context, context.getString(R.string.wallet_import_success)) }
                .onFailure {
                    Logx.e("import account failed", it)
                    error = context.getString(R.string.wallet_import_failed, (it as? CoreException)?.message ?: it.toString())
                }
            working = false
        }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(
            stringResource(R.string.onboarding_alternative_logins),
            navIcon = R.drawable.ic_close_white_24dp,
            onNav = { nav.pop() },
        ) {
            ToolbarIcon(R.drawable.ic_check_white_24dp, { submit() })
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            UnderlineField(
                text, { text = it }, stringResource(R.string.wallet_mnemonic_input_hint),
                Modifier.padding(horizontal = 4.dp),
                singleLine = false,
                minLines = 3,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            Text(
                stringResource(R.string.wallet_mnemonic_paste_hint),
                fontSize = 14.sp,
                color = c.hint,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
            ServerRow()
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                Text(
                    stringResource(R.string.wallet_import_mnemonic),
                    fontSize = 18.sp,
                    color = Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { submit() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            Text(
                stringResource(R.string.login_no_servers_hint),
                fontSize = 14.sp,
                color = c.hint,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 32.dp),
            )
            val proxy by TapeTape.proxy.collectAsState()
            Spacer(Modifier.height(16.dp))
            SwitchRow(stringResource(R.string.proxy_use_proxy), checked = proxy.enabled, inset = 4.dp) { nav.push(Screen.Proxy) }
            Text(
                stringResource(R.string.pref_view_log),
                fontSize = 16.sp,
                color = Accent,
                modifier = Modifier.clickable { nav.push(Screen.Log) }.padding(horizontal = 4.dp, vertical = 12.dp),
            )
        }
    }

    if (working) ProgressDialog(stringResource(R.string.one_moment))
    confirmAddress?.let { (words, address) ->
        TapeTapeDialog(
            { confirmAddress = null },
            stringResource(R.string.wallet_confirm_address_title),
            stringResource(R.string.wallet_confirm_address_message) + "\n\n" + address + "\n\n" + stringResource(R.string.wallet_confirm_address_question),
            confirm = stringResource(R.string.wallet_confirm_continue) to {
                confirmAddress = null
                import(words)
            },
            dismiss = stringResource(R.string.cancel) to { confirmAddress = null },
            secure = true,
        )
    }
    if (invalid) {
        TapeTapeDialog(
            { invalid = false },
            stringResource(R.string.wallet_validation_failed),
            stringResource(R.string.wallet_invalid_mnemonic),
            confirm = stringResource(R.string.ok) to { invalid = false },
        )
    }
    error?.let { TapeTapeDialog({ error = null }, stringResource(R.string.error), it, confirm = stringResource(R.string.ok) to { error = null }) }
}
