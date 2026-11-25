package org.tapetape.v2.ui.square

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.ToolbarIcon
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// 广场 = 链上网站浏览器。输入 4246.0、1.2.0 这样的链上名字，打开存在链上的网站。
//
// 两个 WebView：
//   内核宿主（看不见）：加载 assets/tapekit/host.html，里面是原样打包的 Tapekit 内核，来源是 App 私有的
//                       https://kernel.tapetape.invalid，网站碰不到它的缓存和状态。
//   浏览器（看得见）：  网站在 https://<名字>/ 下打开（例如 https://1.2.0.tape/）。.tape 不是真实的顶级域名，
//                       这些请求全部在 shouldInterceptRequest 里截下，交给内核宿主读链、多家节点核对、SHA-256 校验，
//                       原样返回（带网关同款的 CSP：脚本只能来自链上）。链外的 https 请求照常走网络。

private const val TAPE_SUFFIX = ".tape"

/** 广场的起始页：链上网站 2.2.0（X Layer 0 号处理器的 #2）。改起始页只改这里；网站内容在链上换，App 不用发版 */
private const val HOME_URL = "https://2.2.0.tape/"

/** 内核宿主：看不见的 WebView 里跑 Tapekit 内核 */
@SuppressLint("SetJavaScriptEnabled", "StaticFieldLeak")
object TapekitHost {
    private const val ORIGIN = "https://kernel.tapetape.invalid/"
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, (String) -> Unit>()
    private val ready = CountDownLatch(1)
    private var webView: WebView? = null
    /** 官方屏蔽名单。它不允许跨域读取，内核宿主网页自己取不到，由原生代码取来再交给内核 */
    private const val BLOCKLIST_URL = "https://tapekit.org/.tape/blocklist.txt"
    @Volatile private var blocklistAt = 0L

    /** 在主线程调用（打开广场时）；只会建一次 */
    fun start(context: Context) {
        if (webView != null) return
        val html = context.assets.open("tapekit/host.html").bufferedReader().use { it.readText() }
        webView = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            settings.databaseEnabled = true
            addJavascriptInterface(Bridge(), "TapekitBridge")
            loadDataWithBaseURL(ORIGIN, html, "text/html", "utf-8", null)
        }
        // 链上网站不许装自己的 Service Worker：装上就能绕过逐文件核对、并且一直驻留。
        // 安卓下载 Service Worker 脚本不经过 shouldInterceptRequest，所以在这里对所有 .tape 请求明确拒绝
        ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                if (isTape(request.url.toString())) forbidden() else null
        })
        refreshBlocklist()
        Logx.d("tapekit host loading")
    }

    /** 取官方屏蔽名单交给内核（启动时一次，之后每 10 分钟最多一次）。取不到就继续用内置的 */
    private fun refreshBlocklist() {
        val now = System.currentTimeMillis()
        if (now - blocklistAt < 10 * 60_000) return
        blocklistAt = now
        Thread {
            runCatching {
                val c = URL(BLOCKLIST_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 15_000
                c.useCaches = false
                try {
                    if (c.responseCode != 200) return@runCatching
                    // 最多读 1 MB，超过就当异常丢掉（安卓 8 没有 readNBytes）
                    val out = java.io.ByteArrayOutputStream()
                    c.inputStream.use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (out.size() <= 1_000_000) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    if (out.size() > 1_000_000) return@runCatching
                    val bytes = out.toByteArray()
                    if (!ready.await(30, TimeUnit.SECONDS)) return@runCatching
                    val js = "tapekit.setBlocklist(" + JSONObject.quote(String(bytes, Charsets.UTF_8)) + ")"
                    main.post { webView?.evaluateJavascript(js, null) }
                    Logx.d("tapekit blocklist updated")
                } finally { c.disconnect() }
            }.onFailure { Logx.w("tapekit blocklist fetch failed", it) }
        }.start()
    }

    /** 用户点「刷新」：这个网站下次请求重新读链（内核里限制 10 秒一次） */
    fun refresh(host: String) {
        main.post { webView?.evaluateJavascript("tapekit.refresh(" + JSONObject.quote(host) + ")", null) }
    }

    private class Bridge {
        @JavascriptInterface fun ready() {
            Logx.d("tapekit host ready")
            ready.countDown()
        }

        @JavascriptInterface fun done(id: String, json: String) {
            pending.remove(id)?.invoke(json)
        }
    }

    /** 返回这次调用的编号；超时后调用方要 forget(编号)，免得等待表越积越多 */
    private fun call(fn: String, args: List<String>, onDone: (String) -> Unit): String {
        val id = UUID.randomUUID().toString()
        pending[id] = onDone
        val js = "tapekit.$fn(" + (listOf(id) + args).joinToString(",") { JSONObject.quote(it) } + ")"
        main.post { webView?.evaluateJavascript(js, null) }
        return id
    }

    class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        /** 页面本身核对通过：网站文件状态正常、内核核对过哈希（提示页、错误页、未校验文件都不算） */
        val verified get() = status == 200 && headers["x-tape-verified"] == "1" && headers["x-tape-status"] == "ok"
    }

    /** 在后台线程调用（shouldInterceptRequest 就在后台线程） */
    fun serveBlocking(url: String, language: String): Reply? {
        refreshBlocklist()
        if (!ready.await(30, TimeUnit.SECONDS)) return null
        val latch = CountDownLatch(1)
        var out: String? = null
        val id = call("serveTo", listOf(url, language)) { out = it; latch.countDown() }
        if (!latch.await(90, TimeUnit.SECONDS)) {
            pending.remove(id)
            return null
        }
        val o = JSONObject(out ?: return null)
        val h = o.getJSONObject("headers")
        val headers = h.keys().asSequence().associateWith { h.getString(it) }
        return Reply(o.getInt("status"), headers, android.util.Base64.decode(o.getString("body"), android.util.Base64.DEFAULT))
    }

    /** 地址栏输入 → (链上名字, 路径)；不认识返回内核的说明文字 */
    suspend fun normalize(text: String, language: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        runCatching {
            if (!ready.await(30, TimeUnit.SECONDS)) error("kernel not ready")
            val d = CompletableDeferred<String>()
            val id = call("normalizeTo", listOf(text, language)) { d.complete(it) }
            val o = try {
                JSONObject(withTimeout(60_000) { d.await() })
            } finally {
                pending.remove(id)
            }
            if (o.optBoolean("ok")) o.getString("name") to o.optString("path") else error(o.optString("message"))
        }
    }
}

private val language: String get() = Locale.getDefault().toLanguageTag()

/** https://1.2.0.tape/a.html → 1.2.0/a.html；其他网址原样 */
private fun display(url: String?): String {
    if (url.isNullOrEmpty()) return ""
    val u = Uri.parse(url)
    val host = u.host ?: return url
    // 只有 https 的 .tape 才是链上网站（http://x.tape 走的是真实网络，不能显示成链上名字）
    if (!host.endsWith(TAPE_SUFFIX) || u.scheme != "https") return url
    val path = (u.encodedPath ?: "").let { if (it == "/") "" else it } + (u.encodedQuery?.let { "?$it" } ?: "")
    return host.removeSuffix(TAPE_SUFFIX) + path
}

private fun siteUrl(name: String, path: String) = "https://$name/" + path.removePrefix("/")

private fun isTape(url: String?) = url != null && Uri.parse(url).let { it.scheme == "https" && it.host?.endsWith(TAPE_SUFFIX) == true }

private fun isTapeScheme(url: String) = Regex("^(?:web\\+)?tape:", RegexOption.IGNORE_CASE).containsMatchIn(url)

/**
 * tape:// 与 web+tape:// 链接 → https://<名字>.tape/。只认链上名字（4246.0、1.2.344，可带 .tape）；
 * 其他主机返回 null，不能把 tape://evil.com 变成去打开链外网站，也不能把 tape://4246.0 变成 IP 地址
 */
private fun fromTapeScheme(url: String): String? {
    val m = Regex("^(?:web\\+)?tape://([^/?#]+)(.*)$", RegexOption.IGNORE_CASE).find(url) ?: return null
    val host = m.groupValues[1].lowercase().removeSuffix(".")
    if (!Regex("^[0-9]+(\\.[0-9]+){1,2}(\\.tape)?$").matches(host)) return null
    val name = if (host.endsWith(TAPE_SUFFIX)) host else host + TAPE_SUFFIX
    return "https://" + name + m.groupValues[2].ifEmpty { "/" }
}

private val REASONS = mapOf(
    200 to "OK", 204 to "No Content", 400 to "Bad Request", 402 to "Payment Required", 404 to "Not Found",
    413 to "Payload Too Large", 451 to "Unavailable For Legal Reasons", 500 to "Internal Server Error", 502 to "Bad Gateway", 503 to "Service Unavailable",
)

private fun toResponse(r: TapekitHost.Reply): WebResourceResponse {
    val ct = r.headers["content-type"] ?: "application/octet-stream"
    val mime = ct.substringBefore(';').trim()
    val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1)?.trim()
    // WebResourceResponse 不接受 3xx；内核也不会发跳转，保险起见当错误
    val status = if (r.status in 300..399) 502 else r.status
    val headers = r.headers.filterKeys { it != "content-type" && it != "content-length" }
    return WebResourceResponse(mime, charset, status, REASONS[status] ?: "Status", headers, ByteArrayInputStream(r.body))
}

private fun forbidden() = WebResourceResponse(
    "text/plain", "utf-8", 403, "Forbidden", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)),
)

private fun failed() = WebResourceResponse(
    "text/plain", "utf-8", 504, "Gateway Timeout", mapOf("Cache-Control" to "no-store"),
    ByteArrayInputStream("Tapekit kernel did not answer".toByteArray()),
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SquareScreen() {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var showHome by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    /** 网页钱包：连接、签名、发交易都在这里弹窗确认 */
    val wallet = remember { DappWallet(context, scope) }
    wallet.account = org.tapetape.v2.TapeTape.account
    /** 主机名 → 主页面是否核对通过（来自内核的响应头） */
    /** 主页面网址 → 是否核对通过（来自内核的响应头）；committedVerified = 当前已显示那一页的结果（页面换了才更新，不按主机名缓存） */
    val verifiedUrls = remember { mutableStateMapOf<String, Boolean>() }
    var committedVerified by remember { mutableStateOf<Boolean?>(null) }
    wallet.verified = { origin -> if (DappWallet.originOf(currentUrl) == origin) committedVerified else null }
    // 离开广场：挂着的钱包请求作废
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { wallet.cancelAll() } }
    val onTape = !showHome && isTape(currentUrl)
    // 地址栏的绿勾只在这一页核对通过时显示；提示页、错误页、未校验的文件都不显示
    val pageVerified = onTape && committedVerified == true

    fun open(url: String) {
        showHome = false
        address = display(url)
        focus.clearFocus()
        webView?.loadUrl(url)
    }

    fun submit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        focus.clearFocus()
        val lower = text.lowercase()
        fromTapeScheme(text)?.let { open(it); return }
        if (lower.startsWith("https://") || lower.startsWith("http://")) { open(text); return }
        // 有字母的点分名字（okx.com）当普通网址；纯数字的（4246.0、1.2.344）和 #ID@… 交给内核
        if (Regex("^[^\\s/]*[a-zA-Z][^\\s/]*\\.[a-zA-Z]{2,}(/.*)?$").matches(text) && !lower.contains(TAPE_SUFFIX)) { open("https://$text"); return }
        scope.launch {
            TapekitHost.normalize(text, language)
                .onSuccess { (name, path) -> open(siteUrl(name, path)) }
                .onFailure { error = it.message ?: it.toString() }
        }
    }

    fun goHome() = open(HOME_URL)

    BackHandler {
        when {
            canGoBack -> webView?.goBack()
            else -> nav.pop()
        }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(
            title = null,
            onNav = { if (!showHome && canGoBack) webView?.goBack() else nav.pop() },
            titleContent = {
                // 主页：一键离开广场，回到消息列表（后退键只在网页历史里一页页退）
                ToolbarIcon(Icons.Filled.Home, { while (nav.pop()) Unit })
                Spacer(Modifier.width(4.dp))
                Row(
                    Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2C2C2E)).padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        when {
                            pageVerified -> Icons.Filled.CheckCircle
                            onTape -> Icons.Filled.Info
                            else -> Icons.Filled.Search
                        },
                        null,
                        tint = if (pageVerified) Color(0xFF00C754) else Color(0xFF8E8E93), modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (address.isEmpty()) Text(stringResource(R.string.v2_browser_placeholder), color = Color(0xFF8E8E93), fontSize = 15.sp, maxLines = 1)
                        BasicTextField(
                            value = address,
                            onValueChange = { address = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                            cursorBrush = SolidColor(Color.White),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onGo = { submit(address) }),
                            modifier = Modifier.fillMaxWidth().onFocusChanged {
                                editing = it.isFocused
                                if (!it.isFocused && !showHome) address = display(currentUrl)
                            },
                        )
                    }
                    if (!editing && wallet.isConnected(DappWallet.originOf(currentUrl))) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.AccountCircle, stringResource(R.string.v2_wallet_connected), tint = Color(0xFF0A84FF), modifier = Modifier.size(16.dp))
                    }
                }
            },
            actions = {
                Box {
                    ToolbarIcon(Icons.Filled.MoreVert, { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.v2_browser_reload)) },
                            leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                            enabled = !showHome,
                            onClick = {
                                menu = false
                                if (onTape) Uri.parse(currentUrl).host?.let { TapekitHost.refresh(it) }
                                webView?.reload()
                            },
                        )
                        if (onTape) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.v2_browser_status)) },
                                leadingIcon = { Icon(Icons.Filled.Info, null) },
                                onClick = {
                                    menu = false
                                    Uri.parse(currentUrl).host?.let { open("https://$it/.tape/status") }
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.v2_browser_home)) },
                            leadingIcon = { Icon(Icons.Filled.Home, null) },
                            onClick = { menu = false; goHome() },
                        )
                        HorizontalDivider()
                        // 钱包网络：按网站分开记，默认 X Layer，可切 BNB Chain；只有连上钱包的网站才能切
                        val origin = DappWallet.originOf(currentUrl)
                        if (origin != null && wallet.isConnected(origin)) {
                            WalletChain.all.forEach { id ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.v2_wallet_network_menu, WalletChain.name(id))) },
                                    leadingIcon = { if (wallet.chainId(origin) == id) Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF00C754)) else Spacer(Modifier.size(24.dp)) },
                                    onClick = { menu = false; wallet.setChain(id, origin) },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.v2_wallet_disconnect)) },
                                onClick = { menu = false; origin?.let(wallet::disconnect) },
                            )
                        }
                        // 这个网站被拒绝太多次、钱包请求暂时不弹：可以手动解除
                        if (origin != null && wallet.isCoolingDown(origin)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.v2_wallet_clear_cooldown)) },
                                onClick = { menu = false; wallet.clearCooldown(origin) },
                            )
                        }
                    }
                }
            },
        )
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (!showHome && progress in 0.01f..0.99f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize(), color = Color(0xFF00C754))
            }
        }
        Box(Modifier.fillMaxSize().clipToBounds()) {
            AndroidView(
                factory = { ctx ->
                    TapekitHost.start(ctx)
                    WebView(ctx).apply {
                        // 明确占满可用空间：AndroidView 默认给的是「随内容变高」，那种模式下网页里的 100vh、height:100% 都算成 0，
                        // 按屏幕高度排版的网站（比如 tapecode.ai）只剩头部、下面一片空白
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val url = request.url.toString()
                                // http://x.tape 不是链上网站（表单 POST 不经过 shouldOverrideUrlLoading，在这里一并拦下）
                                if (request.url.scheme == "http" && request.url.host?.lowercase()?.endsWith(TAPE_SUFFIX) == true) {
                                    return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                                }
                                if (!isTape(url)) return null   // 链外请求照常走网络
                                val r = TapekitHost.serveBlocking(url, language)
                                if (request.isForMainFrame) {
                                    val ok = r?.verified == true
                                    view.post { verifiedUrls[url] = ok }
                                }
                                return r?.let(::toResponse) ?: failed()
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                val scheme = request.url.scheme?.lowercase()
                                // 主页面不许跳到 data:/blob:（那样的页面没有可识别的来源，可以伪装成任何网站）
                                if (request.isForMainFrame && (scheme == "data" || scheme == "blob")) return true
                                // http://xxx.tape 不是链上网站：改成链上网站的 https 地址打开，免得冒名
                                if (scheme == "http" && request.url.host?.lowercase()?.endsWith(TAPE_SUFFIX) == true) {
                                    if (request.isForMainFrame) view.post { open(url.replaceFirst(Regex("^http:", RegexOption.IGNORE_CASE), "https:")) }
                                    return true
                                }
                                if (isTapeScheme(url)) {
                                    // 只接受主页面的 tape 链接（框架不能借它把整页带走）；不是链上名字的直接不开
                                    if (request.isForMainFrame) fromTapeScheme(url)?.let { view.post { open(it) } }
                                    return true
                                }
                                return request.url.scheme !in setOf("https", "http", "about", "blob", "data")
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                // 页面跳转：上一页挂着的钱包请求作废
                                wallet.cancelAll()
                            }

                            // 页面显示出来了：给主页面送钱包的私有消息端口（两处各送一次，provider.js 只收第一个）
                            override fun onPageCommitVisible(view: WebView, url: String?) { wallet.pageReady(url) }
                            override fun onPageFinished(view: WebView, url: String?) { wallet.pageReady(url) }

                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                                if (DappWallet.originOf(url) != DappWallet.originOf(currentUrl)) wallet.cancelAll()
                                canGoBack = view.canGoBack()
                                currentUrl = url
                                committedVerified = url?.let { verifiedUrls[it] }
                                if (!editing && !showHome) address = display(url)
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress / 100f }

                            // 网页自己的弹窗：和钱包弹窗共用一个位置，标题写明是哪个网站弹的；钱包在用时直接当作取消
                            private fun who(url: String?) = DappWallet.originOf(url) ?: "?"
                            override fun onJsAlert(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                                if (!wallet.pageDialog(who(url), message.orEmpty(), false) { result.confirm() }) result.cancel()
                                return true
                            }
                            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                                if (!wallet.pageDialog(who(url), message.orEmpty(), true) { if (it) result.confirm() else result.cancel() }) result.cancel()
                                return true
                            }
                            override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult): Boolean {
                                result.cancel()
                                return true
                            }
                            // 「离开本页？」不弹系统对话框（会和钱包弹窗叠在一起），直接允许离开
                            override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                                result.confirm()
                                return true
                            }
                        }
                        setDownloadListener { url, _, _, _, _ -> Logx.d("square download blocked $url") }
                        // 记下手指按网页的时间：钱包确认键要等手指停下来（防「连点游戏」）
                        @SuppressLint("ClickableViewAccessibility")
                        setOnTouchListener { _, e ->
                            // 按下、移动、抬起都算触摸活动；记下还按着的手指数
                            wallet.lastTouch = android.os.SystemClock.uptimeMillis()
                            wallet.activePointers = when (e.actionMasked) {
                                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> 0
                                android.view.MotionEvent.ACTION_POINTER_UP -> e.pointerCount - 1
                                else -> e.pointerCount
                            }
                            false
                        }
                        webView = this
                        wallet.attach(this)
                        address = display(HOME_URL)
                        loadUrl(HOME_URL)
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    wallet.prompt?.let { p -> WalletPromptDialog(p, wallet) }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = { TextButton({ error = null }) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(msg) },
        )
    }
}

/**
 * 钱包弹窗（也用来显示网页自己的 alert/confirm）。确认键要等弹窗出现满 1 秒、屏幕上没有手指、而且 0.8 秒内没有任何触摸才能点；
 * 确认、取消的左右位置随机（okFirst）。网页自己的弹窗（page）顶上加一条灰色的「网页」标签，和钱包弹窗一眼区分开
 */
@Composable
private fun WalletPromptDialog(p: DappWallet.Prompt, wallet: DappWallet) {
    val shownAt = remember(p) { android.os.SystemClock.uptimeMillis() }
    var armed by remember(p) { mutableStateOf(!p.delay) }
    androidx.compose.runtime.LaunchedEffect(p) {
        while (!armed) {
            kotlinx.coroutines.delay(150)
            val now = android.os.SystemClock.uptimeMillis()
            if (now - shownAt >= 1000 && wallet.activePointers == 0 && now - wallet.lastTouch >= 800) armed = true
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = { wallet.answer(p, Answer.NO) }) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(if (p.page) 8.dp else 24.dp),
            color = if (p.page) Color(0xFFF2F2F7) else androidx.compose.material3.AlertDialogDefaults.containerColor,
            modifier = Modifier.pointerInput(p) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        wallet.lastTouch = android.os.SystemClock.uptimeMillis()
                        wallet.activePointers = e.changes.count { it.pressed }
                    }
                }
            },
        ) {
            Column(Modifier.padding(if (p.page) 16.dp else 24.dp)) {
                if (p.page) {
                    Text(
                        stringResource(R.string.v2_browser_page_badge), fontSize = 12.sp, color = Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF8E8E93)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    p.title, fontSize = if (p.page) 16.sp else 22.sp,
                    color = if (p.page) Color(0xFF3A3A3C) else androidx.compose.material3.AlertDialogDefaults.titleContentColor,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    p.message, fontSize = 14.sp,
                    color = if (p.page) Color(0xFF3A3A3C) else androidx.compose.material3.AlertDialogDefaults.textContentColor,
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(20.dp))
                val okColor = if (p.danger && armed) Color(0xFFFF453A) else Color.Unspecified
                Row(Modifier.align(Alignment.End)) {
                    val cancel: @Composable () -> Unit = { p.cancel?.let { TextButton({ wallet.answer(p, Answer.NO) }) { Text(it) } } }
                    val ok: @Composable () -> Unit = { TextButton({ wallet.answer(p, Answer.YES) }, enabled = armed) { Text(p.ok, color = okColor) } }
                    if (p.okFirst) { ok(); cancel() } else { cancel(); ok() }
                }
            }
        }
    }
}
