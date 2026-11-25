package org.tapetape.v2

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.NavHost
import org.tapetape.v2.ui.Navigator
import org.tapetape.v2.ui.Screen
import org.tapetape.v2.ui.chat.ChatScreen
import org.tapetape.v2.ui.chatlist.ChatListScreen
import org.tapetape.v2.ui.chatlist.ArchiveScreen
import org.tapetape.v2.ui.chatlist.ForwardScreen
import org.tapetape.v2.ui.chatlist.ShareContactScreen
import org.tapetape.v2.ui.profile.ChatProfileScreen
import org.tapetape.v2.ui.profile.AllMediaScreen
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.contacts.ContactsScreen
import org.tapetape.v2.ui.onboarding.CreateProfileScreen
import org.tapetape.v2.ui.onboarding.ImportProfileScreen
import org.tapetape.v2.ui.onboarding.WelcomeScreen
import org.tapetape.v2.ui.qr.QrScreen
import org.tapetape.v2.ui.qr.ScanResult
import org.tapetape.v2.ui.settings.AppearanceScreen
import org.tapetape.v2.ui.settings.BlockedContactsScreen
import org.tapetape.v2.ui.settings.ChatsSettingsScreen
import org.tapetape.v2.ui.settings.ProfileScreen
import org.tapetape.v2.ui.settings.SettingsScreen
import org.tapetape.v2.ui.square.SquareScreen
import org.tapetape.v2.ui.theme.TapeTapeTheme
import org.tapetape.v2.ui.theme.isDark
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import org.tapetape.v2.notify.KeepAliveService
import org.tapetape.v2.notify.Notifier
import org.tapetape.v2.notify.Shortcuts
import org.tapetape.v2.ui.INVITE_HOSTS
import org.tapetape.v2.ui.PROXY_SCHEMES
import org.tapetape.v2.ui.chat.importUri
import org.tapetape.v2.ui.chatlist.ShareScreen
import org.tapetape.v2.ui.group.GroupQrScreen
import org.tapetape.v2.ui.group.NewGroupScreen
import org.tapetape.v2.ui.settings.ConnectivityScreen
import org.tapetape.v2.ui.settings.NotificationSettingsScreen
import org.tapetape.v2.ui.settings.WallpaperScreen
import android.view.View
import android.view.ViewTreeObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.BLACK))
        super.onCreate(savedInstanceState)
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!TapeTape.ready.value) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        lifecycleScope.launch {
            TapeTape.awaitReady()
            handleIntent(intent)
            if (Prefs.forceBackground) KeepAliveService.start(this@MainActivity)
            setContent {
                val mode by TapeTape.theme.collectAsState()
                TapeTapeTheme(isDark(mode)) { Root() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lifecycleScope.launch {
            TapeTape.awaitReady()
            handleIntent(intent)
        }
    }

    private suspend fun handleIntent(intent: Intent?) {
        intent ?: return
        val account = intent.getIntExtra(Notifier.EXTRA_ACCOUNT, -1)
        val chat = intent.getIntExtra(Notifier.EXTRA_CHAT, -1)
        when {
            intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE -> receiveShare(intent)
            account >= 0 && chat >= 0 -> {
                Logx.i("open chat acc=$account chat=$chat")
                if (TapeTape.accountId.value != account) TapeTape.activate(account)
                TapeTape.pendingChat.value = chat
            }
            intent.action == Intent.ACTION_VIEW && (intent.data?.host in INVITE_HOSTS || intent.data?.scheme in PROXY_SCHEMES) -> {
                Logx.i("link opened scheme=${intent.data?.scheme}")
                TapeTape.pendingLink.value = intent.data.toString()
            }
        }
    }

    private fun receiveShare(intent: Intent) {
        @Suppress("DEPRECATION")
        val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val target = Shortcuts.parse(intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID))
        Logx.i("share received files=${uris.size} text=${text != null} direct=${target != null}")
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { importUri(this@MainActivity, it) }.onFailure { e -> Logx.w("share import failed", e) }.getOrNull() } }
            if (files.size < uris.size) toast(this@MainActivity, getString(R.string.error))
            if (files.isEmpty() && text.isNullOrBlank()) return@launch
            if (target != null && TapeTape.accountId.value != target.first) TapeTape.activate(target.first)
            TapeTape.pendingShare.value = Screen.Share(text, files.map { it.first.path }, files.map { it.second }, target?.second)
        }
    }
}

@Composable
private fun Root() {
    val accountId by TapeTape.accountId.collectAsState()
    val pendingLink by TapeTape.pendingLink.collectAsState()
    val pendingChat by TapeTape.pendingChat.collectAsState()
    val pendingShare by TapeTape.pendingShare.collectAsState()
    val nav = rememberSaveable(saver = Navigator.Saver) { Navigator(listOf(if (accountId == null) Screen.Welcome else Screen.ChatList)) }
    val context = LocalContext.current
    var lastAccount by remember { mutableStateOf(accountId) }
    LaunchedEffect(accountId) {
        when {
            accountId == null -> if (nav.stack.first() != Screen.Welcome) nav.reset(Screen.Welcome)
            accountId != lastAccount || nav.stack.first() != Screen.ChatList -> nav.reset(Screen.ChatList)
        }
        lastAccount = accountId
    }
    LaunchedEffect(pendingChat, accountId) {
        val chat = pendingChat ?: return@LaunchedEffect
        if (accountId == null) return@LaunchedEffect
        TapeTape.pendingChat.value = null
        nav.reset(Screen.ChatList)
        nav.push(Screen.Chat(chat))
    }
    LaunchedEffect(pendingShare, accountId) {
        val share = pendingShare ?: return@LaunchedEffect
        if (accountId == null) return@LaunchedEffect
        TapeTape.pendingShare.value = null
        nav.reset(Screen.ChatList)
        nav.push(share)
    }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Logx.i("notification permission granted=$it") }
    LaunchedEffect(accountId != null) {
        if (accountId != null && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(Unit) { TapeTape.errors.collect { toast(context, it) } }

    CompositionLocalProvider(LocalNav provides nav) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
                .navigationBarsPadding(),
        ) {
            key(accountId) {
            NavHost(nav) { screen ->
                when (screen) {
                    Screen.Welcome -> WelcomeScreen()
                    Screen.AddProfile -> WelcomeScreen(adding = true)
                    Screen.CreateProfile -> CreateProfileScreen()
                    Screen.ImportProfile -> ImportProfileScreen()
                    Screen.ChatList -> ChatListScreen()
                    is Screen.Chat -> ChatScreen(screen.id, screen.replyTo)
                    Screen.Contacts -> ContactsScreen()
                    Screen.Qr -> QrScreen()
                    Screen.Square -> SquareScreen()
                    Screen.Settings -> SettingsScreen()
                    Screen.Profile -> ProfileScreen()
                    Screen.Appearance -> AppearanceScreen()
                    Screen.ChatsSettings -> ChatsSettingsScreen()
                    Screen.Blocked -> BlockedContactsScreen()
                    Screen.Archive -> ArchiveScreen()
                    is Screen.Forward -> ForwardScreen(screen.msgIds)
                    is Screen.ShareContact -> ShareContactScreen(screen.contactId)
                    is Screen.ChatProfile -> ChatProfileScreen(screen.chatId)
                    Screen.AllMedia -> AllMediaScreen()
                    is Screen.NewGroup -> NewGroupScreen(screen.editChatId, screen.cloneFrom)
                    is Screen.GroupQr -> GroupQrScreen(screen.chatId)
                    Screen.NotificationSettings -> NotificationSettingsScreen()
                    Screen.Wallpaper -> WallpaperScreen()
                    Screen.Connectivity -> ConnectivityScreen()
                    Screen.Proxy -> org.tapetape.v2.ui.proxy.ProxyScreen()
                    Screen.Log -> org.tapetape.v2.ui.settings.LogScreen()
                    is Screen.Share -> ShareScreen(screen)
                    Screen.Receive -> org.tapetape.v2.ui.wallet.ReceiveScreen()
                    Screen.Withdraw -> org.tapetape.v2.ui.wallet.WithdrawScreen()
                    is Screen.SendTransfer -> org.tapetape.v2.ui.packets.SendTransferScreen(screen.chatId)
                    is Screen.SendRedPacket -> org.tapetape.v2.ui.packets.SendRedPacketScreen(screen.chatId)
                    is Screen.TransferInfo -> org.tapetape.v2.ui.packets.TransferInfoScreen(screen.msgId)
                    is Screen.RedPacketClaim -> org.tapetape.v2.ui.packets.RedPacketClaimScreen(screen.msgId)
                    is Screen.RedPacketDetail -> org.tapetape.v2.ui.packets.RedPacketDetailScreen(screen.msgId)
                    is Screen.Shop -> org.tapetape.v2.ui.shop.ShopScreen(screen.url)
                }
            }
            }
        }
        val link = pendingLink
        if (link != null && (accountId != null || org.tapetape.v2.ui.proxy.parseProxyLink(link) != null)) ScanResult(link) { TapeTape.pendingLink.value = null }
    }
}
