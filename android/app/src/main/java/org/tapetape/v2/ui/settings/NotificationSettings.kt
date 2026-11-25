package org.tapetape.v2.ui.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.tapetape.v2.Logx
import org.tapetape.v2.Prefs
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.notify.KeepAliveService
import org.tapetape.v2.notify.LedColors
import org.tapetape.v2.notify.Notifier
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.ToolbarIcon
import org.tapetape.v2.ui.common.coreQuery
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

fun soundLabel(context: Context, value: String?): String = when {
    value == null -> RingtoneManager.getRingtone(context, Settings.System.DEFAULT_NOTIFICATION_URI)?.getTitle(context) ?: context.getString(R.string.def)
    value.isEmpty() -> context.getString(R.string.pref_silent)
    else -> RingtoneManager.getRingtone(context, Uri.parse(value))?.getTitle(context) ?: value
}

@Composable
fun rememberSoundPicker(onPick: (String?) -> Unit): (String?) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        @Suppress("DEPRECATION")
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        val default = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        onPick(
            when {
                uri == null -> ""
                uri == default || Settings.System.DEFAULT_NOTIFICATION_URI == uri -> null
                else -> uri.toString()
            },
        )
    }
    return remember(launcher) {
        { current ->
            val existing = when {
                current == null -> Settings.System.DEFAULT_NOTIFICATION_URI
                current.isEmpty() -> null
                else -> Uri.parse(current)
            }
            launcher.launch(
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing),
            )
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
fun NotificationSettingsScreen() {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val info by coreQuery(account.info()) { account.info() }
    var settings by remember { mutableStateOf(account.settings()) }
    val revision by Prefs.changes.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed++ }
    val ignoringBattery = remember(resumed) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
    val enabled = !info.muted

    fun channels(block: () -> Unit) {
        block()
        Notifier.refreshChannels(context)
    }
    val pickSound = rememberSoundPicker { value ->
        Logx.i("notification sound set custom=${value != null}")
        channels { Prefs.sound = value }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(stringResource(R.string.pref_notifications), onNav = { nav.pop() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SwitchRow(stringResource(R.string.pref_notifications), checked = enabled) {
                Logx.i("account notifications enabled=$it")
                account.setAccountMuted(!it)
            }
            SwitchRow(stringResource(R.string.pref_mention_notifications), stringResource(R.string.pref_mention_notifications_explain), settings.mentions, enabled) {
                settings = settings.copy(mentions = it)
                account.setSettings(settings)
            }
            val sound = remember(revision) { soundLabel(context, Prefs.sound) }
            PrefRow(stringResource(R.string.pref_sound), sound, enabled, onClick = { pickSound(Prefs.sound) })
            SwitchRow(stringResource(R.string.pref_vibrate), checked = remember(revision) { Prefs.vibrate }, enabled = enabled) { v -> channels { Prefs.vibrate = v } }
            PrefRow(stringResource(R.string.pref_led_color), stringResource(LedColors[Prefs.led].first), enabled, onClick = { dialog = "led" })
            val showLabels = listOf(R.string.notify_name_and_message, R.string.notify_name_only, R.string.notify_no_name_or_message)
            PrefRow(stringResource(R.string.pref_notifications_show), stringResource(showLabels[Prefs.show]), enabled, onClick = { dialog = "show" })
            val priorityLabels = listOf(R.string.def, R.string.notify_priority_high, R.string.notify_priority_max)
            PrefRow(stringResource(R.string.pref_notifications_priority), stringResource(priorityLabels[Prefs.priority]), enabled, onClick = { dialog = "priority" })
            SwitchRow(stringResource(R.string.pref_in_chat_sounds), checked = remember(revision) { Prefs.inChatSounds }) { Prefs.inChatSounds = it }
            PrefSection(stringResource(R.string.pref_instant_delivery))
            SwitchRow(stringResource(R.string.pref_background_notifications), stringResource(R.string.pref_background_notifications_explain), ignoringBattery) {
                val intent = if (it) Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
                else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                runCatching { context.startActivity(intent) }.onFailure { Logx.w("battery settings failed", it) }
            }
            SwitchRow(stringResource(R.string.pref_reliable_service), stringResource(R.string.pref_reliable_service_explain), remember(revision) { Prefs.forceBackground }) {
                Prefs.forceBackground = it
                Logx.i("force background=$it")
                if (it) KeepAliveService.start(context) else KeepAliveService.stop(context)
            }
        }
    }

    when (dialog) {
        "led" -> ChoiceDialog(stringResource(R.string.pref_led_color), LedColors.map { stringResource(it.first) }, Prefs.led, { dialog = null }) {
            dialog = null
            channels { Prefs.led = it }
        }
        "show" -> ChoiceDialog(
            stringResource(R.string.pref_notifications_show),
            listOf(R.string.notify_name_and_message, R.string.notify_name_only, R.string.notify_no_name_or_message).map { stringResource(it) },
            Prefs.show,
            { dialog = null },
        ) {
            dialog = null
            Prefs.show = it
        }
        "priority" -> ChoiceDialog(
            stringResource(R.string.pref_notifications_priority),
            listOf(R.string.def, R.string.notify_priority_high, R.string.notify_priority_max).map { stringResource(it) },
            Prefs.priority,
            { dialog = null },
        ) {
            dialog = null
            channels { Prefs.priority = it }
        }
    }
}

@Composable
fun WallpaperImage(path: String?, modifier: Modifier) {
    val c = LocalTapeTapeColors.current
    val bitmap = remember(path) { path?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
    if (bitmap != null) Image(bitmap, null, modifier, contentScale = ContentScale.Crop)
    else Image(painterResource(c.wallpaper), null, modifier, contentScale = ContentScale.Crop)
}

@Composable
fun ChatWallpaper(modifier: Modifier) {
    val revision by Prefs.changes.collectAsState()
    val account by TapeTape.accountId.collectAsState()
    val path = remember(revision, account) { account?.let { Prefs.wallpaper(it) } }
    WallpaperImage(path, modifier)
}

@Composable
fun WallpaperScreen() {
    val nav = LocalNav.current
    val context = LocalContext.current
    val accountId = TapeTape.accountId.value ?: return
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(Prefs.wallpaper(accountId)) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            path = withContext(Dispatchers.IO) {
                runCatching {
                    val out = File(context.filesDir, "wallpaper/$accountId-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
                    context.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
                    org.tapetape.v2.ui.chat.fitImage(out.path, 2_000_000, File(out.parentFile, "s-" + out.name), 1920).also { if (it != out.path) out.delete() }
                }.onFailure { Logx.w("wallpaper import failed", it) }.getOrNull()
            } ?: path
        }
    }
    Column(Modifier.fillMaxSize()) {
        TapeTapeToolbar(stringResource(R.string.pref_background), navIcon = R.drawable.ic_close_white_24dp, onNav = { nav.pop() }) {
            ToolbarIcon(R.drawable.ic_check_white_24dp, {
                val old = Prefs.wallpaper(accountId)
                if (old != null && old != path) File(old).delete()
                Prefs.setWallpaper(accountId, path)
                Logx.i("wallpaper set custom=${path != null}")
                nav.pop()
            })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            WallpaperImage(path, Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                WallpaperButton(stringResource(R.string.pref_background_btn_default)) { path = null }
                Spacer(Modifier.height(30.dp))
                WallpaperButton(stringResource(R.string.pref_background_btn_gallery)) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
        }
    }
}

@Composable
private fun WallpaperButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        modifier = Modifier
            .width(200.dp)
            .background(Color(0xFFAAAAAA))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
