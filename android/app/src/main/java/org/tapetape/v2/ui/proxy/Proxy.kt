package org.tapetape.v2.ui.proxy

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.Connectivity
import org.tapetape.v2.core.ProxyInfo
import org.tapetape.v2.core.ProxyKind
import org.tapetape.v2.core.ProxySettings
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.Screen
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.ToolbarIcon
import org.tapetape.v2.ui.common.UnderlineField
import org.tapetape.v2.ui.common.coreQuery
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.qr.qrBitmap
import org.tapetape.v2.ui.settings.PrefSection
import org.tapetape.v2.ui.settings.SwitchRow
import org.tapetape.v2.ui.theme.Accent
import org.tapetape.v2.ui.theme.LocalTapeTapeColors

fun parseProxyLink(text: String): ProxyInfo? = runCatching { TapeTape.core.parseProxy(text) }.getOrNull()

fun isTappableProxyLink(url: String) =
    (!url.startsWith("http", true) || url.startsWith("https://t.me/socks", true)) && parseProxyLink(url) != null

private fun kindLabel(kind: ProxyKind) = when (kind) {
    ProxyKind.Http -> "HTTP"
    ProxyKind.Https -> "HTTPS"
    ProxyKind.Socks5 -> "SOCKS5"
    ProxyKind.Shadowsocks -> "Shadowsocks"
}

fun updateProxy(context: Context, action: String, block: () -> ProxySettings): Boolean =
    runCatching(block)
        .onSuccess {
            TapeTape.proxy.value = it
            Logx.i("proxy $action enabled=${it.enabled} count=${it.proxies.size}")
        }
        .onFailure {
            Logx.w("proxy $action failed: ${it.message}")
            toast(context, if (it.message == "invalid-proxy") context.getString(R.string.proxy_invalid) else it.message ?: it.toString())
        }
        .isSuccess

@Composable
fun ProxyToolbarIcon() {
    val nav = LocalNav.current
    val p by TapeTape.proxy.collectAsState()
    if (p.proxies.isEmpty()) return
    ToolbarIcon(if (p.enabled) R.drawable.ic_proxy_enabled_24 else R.drawable.ic_proxy_disabled_24, { nav.push(Screen.Proxy) })
}

@Composable
fun ProxyLinkDialog(link: String, info: ProxyInfo, onDone: () -> Unit) {
    val context = LocalContext.current
    val web = link.startsWith("http://", true) || link.startsWith("https://", true)
    TapeTapeDialog(
        onDone,
        message = stringResource(R.string.proxy_use_proxy_confirm, info.host),
        neutral = if (web) stringResource(R.string.open) to {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            onDone()
        } else null,
        dismiss = stringResource(R.string.cancel) to onDone,
        confirm = stringResource(R.string.proxy_use_proxy) to {
            updateProxy(context, "add from link") { TapeTape.core.addProxy(link) }
            onDone()
        },
    )
}

@Composable
fun ProxyScreen() {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val p by TapeTape.proxy.collectAsState()
    val connectivity by coreQuery<Connectivity?>(null, p) { TapeTape.account?.connectivity() }
    var adding by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<ProxyInfo?>(null) }
    var deleting by remember { mutableStateOf<ProxyInfo?>(null) }

    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(stringResource(R.string.proxy_settings), onNav = { nav.pop() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SwitchRow(stringResource(R.string.proxy_use_proxy), checked = p.enabled, enabled = p.proxies.isNotEmpty()) { on ->
                updateProxy(context, "enable=$on") { TapeTape.core.setProxyEnabled(on) }
            }
            if (p.proxies.isNotEmpty()) {
                PrefSection(stringResource(R.string.proxy_list_header))
                p.proxies.forEach { info ->
                    val active = p.enabled && info.url == p.active
                    val status = connectivity.takeIf { active }?.let {
                        stringResource(
                            when (it) {
                                Connectivity.Connected -> R.string.connectivity_connected
                                Connectivity.Connecting -> R.string.connectivity_connecting
                                Connectivity.NotConnected -> R.string.connectivity_not_connected
                            },
                        )
                    }
                    ProxyRow(
                        info, active, status,
                        onSelect = { if (!active) updateProxy(context, "select") { TapeTape.core.selectProxy(info.url) } },
                        onShare = { sharing = info },
                        onDelete = { deleting = info },
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            Text(
                stringResource(R.string.proxy_add),
                fontSize = 16.sp,
                color = Accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { adding = true }
                    .padding(horizontal = 16.dp, vertical = 17.dp),
            )
        }
    }

    if (adding) AddProxyDialog { adding = false }
    sharing?.let { ShareProxyDialog(it) { sharing = null } }
    deleting?.let { info ->
        TapeTapeDialog(
            { deleting = null },
            title = stringResource(R.string.proxy_delete),
            message = stringResource(R.string.proxy_delete_explain, info.host),
            dismiss = stringResource(R.string.cancel) to { deleting = null },
            confirm = stringResource(R.string.delete) to {
                updateProxy(context, "remove") { TapeTape.core.removeProxy(info.url) }
                deleting = null
            },
            confirmColor = Color(0xFFE53935),
        )
    }
}

@Composable
private fun ProxyRow(info: ProxyInfo, active: Boolean, status: String?, onSelect: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val c = LocalTapeTapeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (active) Image(painterResource(R.drawable.ic_check_circle), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(Accent))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(info.host, fontSize = 16.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(kindLabel(info.kind), status).joinToString(" · "),
                fontSize = 14.sp, color = c.preview, maxLines = 1,
            )
        }
        RowIcon(R.drawable.ic_share_white_24dp, c.hint, onShare)
        RowIcon(R.drawable.ic_delete_white_24dp, c.hint, onDelete)
    }
}

@Composable
private fun RowIcon(res: Int, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Image(painterResource(res), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(tint))
    }
}

@Composable
private fun AddProxyDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    var link by remember { mutableStateOf("") }
    TapeTapeDialog(
        onDone,
        title = stringResource(R.string.proxy_add),
        message = stringResource(R.string.proxy_add_explain),
        dismiss = stringResource(R.string.cancel) to onDone,
        confirm = stringResource(R.string.proxy_use_proxy) to {
            if (updateProxy(context, "add") { TapeTape.core.addProxy(link) }) onDone()
        },
    ) {
        UnderlineField(link, { link = it.trim() }, stringResource(R.string.proxy_add_url_hint), Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp))
    }
}

@Composable
private fun ShareProxyDialog(info: ProxyInfo, onDone: () -> Unit) {
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val qr = remember(info.url) { qrBitmap(info.url).asImageBitmap() }
    TapeTapeDialog(
        onDone,
        title = info.host,
        dismiss = stringResource(R.string.ok) to onDone,
        confirm = stringResource(R.string.proxy_share_link) to {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, info.url), null))
            onDone()
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.background(Color.White).padding(12.dp)) {
                Image(qr, null, Modifier.size(220.dp))
            }
            Text(
                stringResource(R.string.proxy_share_explain),
                fontSize = 14.sp, color = c.dialogText, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
