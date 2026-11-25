package org.tapetape.v2.ui.settings

import org.tapetape.v2.ui.wallet.WalletCard
import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import org.tapetape.v2.Prefs
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.ThemeMode
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.Screen
import org.tapetape.v2.ui.common.AvatarChooser
import org.tapetape.v2.ui.common.AvatarPicker
import org.tapetape.v2.ui.common.ProgressDialog
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.ToolbarIcon
import org.tapetape.v2.ui.common.UnderlineField
import org.tapetape.v2.ui.common.coreQuery
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import kotlinx.coroutines.launch

@Composable
private fun themeLabel(mode: ThemeMode) = stringResource(
    when (mode) {
        ThemeMode.System -> R.string.pref_system_default
        ThemeMode.Light -> R.string.pref_light_theme
        ThemeMode.Dark -> R.string.pref_dark_theme
    },
)

@Composable
fun SettingsScreen() {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val account = TapeTape.account ?: return
    val info by coreQuery(account.info()) { account.info() }
    val theme by TapeTape.theme.collectAsState()
    val context = LocalContext.current
    var askSystem by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Logx.i("notification permission granted=$granted")
        if (granted) nav.push(Screen.NotificationSettings) else openNotificationSettings(context)
    }
    if (askSystem) {
        TapeTapeDialog(
            { askSystem = false },
            title = stringResource(R.string.notifications_disabled),
            message = stringResource(R.string.perm_explain_access_to_notifications_denied),
            dismiss = stringResource(R.string.cancel) to { askSystem = false },
            confirm = stringResource(R.string.perm_continue) to {
                askSystem = false
                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings(context)
            },
        )
    }

    val pageBg = if (c.dark) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color(0xFFF2F3F5)
    val cardBg = if (c.dark) androidx.compose.ui.graphics.Color(0xFF1C1C1E) else androidx.compose.ui.graphics.Color.White
    Column(Modifier.fillMaxSize().background(pageBg)) {
        TapeTapeToolbar(stringResource(R.string.menu_settings), onNav = { nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Spacer(Modifier.height(12.dp))
            SettingsCard(cardBg) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { nav.push(Screen.Profile) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarPicker(info.avatarPath, 56.dp) { nav.push(Screen.Profile) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            info.name.ifBlank { stringResource(R.string.pref_your_name) },
                            fontSize = 18.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            color = c.text,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(shortAddress(info.address), fontSize = 13.sp, color = c.hint)
                    }
                    Image(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(c.hint))
                }
            }
            SectionTitle(stringResource(R.string.wallet_assets))
            WalletCard(cardBg)
            SectionTitle(stringResource(R.string.v2_settings_general))
            val settings by coreQuery(account.settings()) { account.settings() }
            val prefs by Prefs.changes.collectAsState()
            val systemOn = remember(prefs, info) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            SettingsCard(cardBg) {
                SettingsRow(
                    R.drawable.ic_forum_white_24dp,
                    stringResource(R.string.pref_chats_and_media),
                    stringResource(R.string.pref_read_receipts) + " " + stringResource(if (settings.readReceipts) R.string.on else R.string.off),
                ) { nav.push(Screen.ChatsSettings) }
                CardDivider()
                SettingsRow(
                    R.drawable.ic_notifications_white_24dp,
                    stringResource(R.string.pref_notifications),
                    when {
                        !systemOn -> stringResource(R.string.disabled_in_system_settings)
                        info.muted -> stringResource(R.string.off)
                        else -> stringResource(R.string.on)
                    },
                ) {
                    if (systemOn) nav.push(Screen.NotificationSettings) else askSystem = true
                }
                CardDivider()
                SettingsRow(
                    R.drawable.ic_brightness_6_white_24dp,
                    stringResource(R.string.pref_appearance),
                    themeLabel(theme) + ", " + stringResource(R.string.pref_background) + " " + wallpaperLabel(info.id, prefs),
                ) { nav.push(Screen.Appearance) }
            }
        }
    }
}

fun shortAddress(address: String): String = if (address.length > 14) address.take(6) + "…" + address.takeLast(4) else address

@Composable
fun SettingsCard(bg: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(bg),
    ) { content() }
}

@Composable
fun SectionTitle(text: String) {
    val c = LocalTapeTapeColors.current
    Text(text, fontSize = 13.sp, color = c.hint, modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 8.dp))
}

@Composable
fun CardDivider(start: androidx.compose.ui.unit.Dp = 52.dp) {
    val c = LocalTapeTapeColors.current
    Box(Modifier.padding(start = start).fillMaxWidth().height(0.5.dp).background(c.divider))
}

private fun openNotificationSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
}

@Composable
private fun wallpaperLabel(accountId: Int, revision: Int): String {
    val custom = remember(accountId, revision) { Prefs.wallpaper(accountId) != null }
    return stringResource(if (custom) R.string.pref_background_custom_image else R.string.def)
}

@Composable
private fun SettingsRow(icon: Int?, title: String, summary: String?, onClick: () -> Unit) {
    val c = LocalTapeTapeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(end = 14.dp)
            .height(60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
            if (icon != null) Image(painterResource(icon), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(c.text))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = c.text)
            if (summary != null) Text(summary, fontSize = 13.sp, color = c.hint, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        Image(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(c.hint))
    }
}

@Composable
fun AppearanceScreen() {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val theme by TapeTape.theme.collectAsState()
    var choose by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(stringResource(R.string.pref_appearance), onNav = { nav.pop() })
        PrefRow(stringResource(R.string.pref_theme), themeLabel(theme), onClick = { choose = true })
        val prefs by Prefs.changes.collectAsState()
        PrefRow(stringResource(R.string.pref_background), wallpaperLabel(TapeTape.accountId.value ?: 0, prefs), onClick = { nav.push(Screen.Wallpaper) })
    }

    if (choose) {
        TapeTapeDialog(
            { choose = false },
            title = stringResource(R.string.pref_theme),
            dismiss = stringResource(R.string.cancel) to { choose = false },
        ) {
            ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            Logx.d("theme $mode")
                            TapeTape.setTheme(mode)
                            choose = false
                        }
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = mode == theme,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = c.dialogAccent, unselectedColor = c.hint),
                        modifier = Modifier.padding(12.dp),
                    )
                    Spacer(Modifier.width(20.dp))
                    Text(themeLabel(mode), fontSize = 18.sp, color = c.text)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun ProfileScreen() {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val scope = rememberCoroutineScope()
    val initial = remember { account.info() }
    var name by remember { mutableStateOf(initial.name) }
    var avatar by remember { mutableStateOf(initial.avatarPath) }
    var bio by remember { mutableStateOf(initial.bio) }
    var chooser by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(stringResource(R.string.pref_profile_info_headline), navIcon = R.drawable.ic_close_white_24dp, onNav = { nav.pop() }) {
            ToolbarIcon(R.drawable.ic_check_white_24dp, {
                if (name.isBlank()) {
                    toast(context, context.getString(R.string.please_enter_name))
                } else {
                    saving = true
                    scope.launch {
                        runCatching {
                            account.setProfile(name.trim(), avatar)
                            if (bio.trim() != initial.bio) account.setBio(bio.trim())
                        }
                            .onSuccess {
                                Logx.i("profile saved")
                                nav.pop()
                            }
                            .onFailure {
                                Logx.e("profile save failed", it)
                                error = it.message ?: it.toString()
                            }
                        saving = false
                    }
                }
            })
        }
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                AvatarPicker(avatar, 64.dp) { chooser = true }
                Spacer(Modifier.width(14.dp))
                UnderlineField(
                    name, { name = it }, stringResource(R.string.pref_your_name),
                    Modifier.weight(1f).padding(end = 4.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    label = stringResource(R.string.pref_your_name),
                )
            }
            Text(stringResource(R.string.relay_address), fontSize = 13.sp, color = c.hint, modifier = Modifier.padding(top = 24.dp))
            Text(org.tapetape.v2.ui.relayAddress(initial.account).ifBlank { initial.address }, fontSize = 16.sp, color = c.text, modifier = Modifier.padding(top = 6.dp))
            UnderlineField(
                bio, { if (it.lines().size <= 3) bio = it }, stringResource(R.string.pref_default_status_label),
                Modifier.fillMaxWidth().padding(top = 28.dp),
                singleLine = false,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                label = stringResource(R.string.pref_default_status_label),
            )
            Text(
                stringResource(R.string.pref_who_can_see_profile_explain),
                fontSize = 14.sp,
                color = c.hint,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 32.dp),
            )
        }
    }

    if (chooser) AvatarChooser(avatar != null, { chooser = false }) { avatar = it }
    if (saving) ProgressDialog(stringResource(R.string.one_moment))
    error?.let { TapeTapeDialog({ error = null }, stringResource(R.string.error), it, confirm = stringResource(R.string.ok) to { error = null }) }
}
