package org.tapetape.v2.ui.shop

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.tapetape.v2.BuildConfig
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.TapeTape
import org.tapetape.v2.core.Product
import org.tapetape.v2.core.ProductInput
import org.tapetape.v2.core.ShopInfo
import org.tapetape.v2.ui.LocalNav
import org.tapetape.v2.ui.Screen
import org.tapetape.v2.ui.common.ProgressDialog
import org.tapetape.v2.ui.common.TapeTapeDialog
import org.tapetape.v2.ui.common.TapeTapeToolbar
import org.tapetape.v2.ui.common.copyToClipboard
import org.tapetape.v2.ui.common.toast
import org.tapetape.v2.ui.theme.LocalTapeTapeColors
import org.tapetape.v2.ui.wallet.fmtAmount
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ShopEntry(onDone: () -> Unit) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<ShopInfo?>(null) }
    var opening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun open(url: String) {
        Logx.i("shop open web view")
        onDone()
        nav.push(Screen.Shop(url))
    }

    LaunchedEffect(Unit) {
        runCatching { account.shopInfo() }
            .onSuccess {
                Logx.i("shop info ready=${it.ready}")
                if (it.ready && it.appUrl != null) {
                    TapeTape.scope.launch { runCatching { account.shopOpen() }.onFailure { e -> Logx.w("shop refresh failed", e) } }
                    open(it.appUrl)
                } else info = it
            }
            .onFailure {
                Logx.w("shop info failed", it)
                error = it.message
            }
    }

    val i = info
    when {
        error != null -> TapeTapeDialog(
            onDone,
            title = stringResource(R.string.error),
            message = error,
            confirm = stringResource(R.string.ok) to onDone,
        )
        opening -> ProgressDialog(stringResource(R.string.shop_opening))
        i == null -> ProgressDialog(stringResource(R.string.one_moment), onCancel = onDone)
        else -> TapeTapeDialog(
            onDone,
            title = stringResource(R.string.shop_open_title),
            message = stringResource(R.string.shop_open_message, fmtAmount(i.openCost, 8), i.address, fmtAmount(i.balance, 8)),
            neutral = stringResource(R.string.shop_copy_address) to {
                copyToClipboard(context, i.address)
                toast(context, context.getString(R.string.copied_to_clipboard))
            },
            dismiss = stringResource(R.string.cancel) to onDone,
            confirm = stringResource(R.string.shop_open_button) to {
                val balance = i.balance.toBigDecimalOrNull()
                val cost = i.openCost.toBigDecimalOrNull()
                if (balance == null || cost == null || balance < cost) {
                    toast(context, context.getString(R.string.shop_insufficient_balance))
                } else {
                    opening = true
                    Logx.i("shop opening")
                    scope.launch {
                        runCatching { account.shopOpen() }
                            .onSuccess { r ->
                                Logx.i("shop opened ready=${r.ready}")
                                val url = r.appUrl
                                if (url != null) open(url) else {
                                    opening = false
                                    error = context.getString(R.string.error)
                                }
                            }
                            .onFailure {
                                Logx.w("shop open failed", it)
                                opening = false
                                error = it.message
                            }
                    }
                }
            },
        )
    }
}

private const val RPC_RETRIES = 4

/** 网关「节点不足，读不到链」页（中英文标题） */
private fun isRpcFailPage(title: String?) = title != null && (title.contains("节点不足") || title.contains("Not enough nodes"))

private fun copyPicks(context: android.content.Context, picked: Array<Uri>): Array<Uri> {
    val dir = java.io.File(context.cacheDir, "outgoing/shop").apply { mkdirs() }
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
    val stamp = System.currentTimeMillis()
    return picked.mapIndexedNotNull { i, uri ->
        runCatching {
            val type = context.contentResolver.getType(uri).orEmpty()
            val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "jpg"
            val out = java.io.File(dir, "pick-$stamp-$i.$ext")
            context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
            androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", out)
        }.onFailure { Logx.w("shop pick copy failed", it) }.getOrNull()
    }.toTypedArray()
}

private fun shopHosts(url: String): Set<String> {
    val host = Uri.parse(url).host ?: return emptySet()
    return setOf(host, host.replace("-open.", "."))
}

private fun Product.json() = JSONObject()
    .put("id", id)
    .put("description", description)
    .put("originalPrice", originalPrice)
    .put("discountPrice", discountPrice)
    .put("images", JSONArray(images))
    .put("createdAt", createdAt)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ShopScreen(url: String) {
    val nav = LocalNav.current
    val c = LocalTapeTapeColors.current
    val context = LocalContext.current
    val account = TapeTape.account ?: return
    val scope = rememberCoroutineScope()
    val hosts = remember(url) { shopHosts(url) }
    val sell = stringResource(R.string.sell)
    var title by remember { mutableStateOf(sell) }
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var rpcRetries by remember { mutableIntStateOf(0) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        // 一次选多张时，系统把结果放在 clipData 里；parseResult 只认单张（data.data），多张会被当成「没选」
        val picked: Array<Uri>? = if (r.resultCode != android.app.Activity.RESULT_OK) null else {
            val clip = r.data?.clipData
            when {
                clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                else -> WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
            }
        }
        Logx.i("shop file chooser picked ${picked?.size ?: 0}")
        val callback = fileCallback
        fileCallback = null
        if (picked.isNullOrEmpty() || callback == null) {
            callback?.onReceiveValue(picked)
        } else {
            // 网页是隔一会儿才读文件的；直接给系统相册的地址，第 2、3 张常被系统判成「文件变了」读不出来
            // （ERR_UPLOAD_FILE_CHANGED）。先复制到自己的缓存目录，再把副本交给网页。
            scope.launch {
                val copies = withContext(Dispatchers.IO) { copyPicks(context, picked) }
                callback.onReceiveValue(copies)
            }
        }
    }
    val currentScope by rememberUpdatedState(scope)

    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else nav.pop()
    }

    fun reply(id: Any?, result: Any?, error: String?) {
        val w = webView ?: return
        val idJs = if (id is String) JSONObject.quote(id) else id?.toString() ?: "null"
        val errorJs = error?.let { JSONObject.quote(it) } ?: "null"
        w.evaluateJavascript("window.tapetapeShopReply && window.tapetapeShopReply($idJs, ${result ?: "null"}, $errorJs)", null)
    }

    fun handle(origin: Uri, mainFrame: Boolean, raw: String) {
        if (!mainFrame || origin.scheme != "https" || origin.host !in hosts) {
            Logx.w("shop bridge ignored from ${origin.host} main=$mainFrame")
            return
        }
        val req = runCatching { JSONObject(raw) }.getOrNull() ?: return Logx.w("shop bridge bad request")
        val id = req.opt("id")
        val method = req.optString("method")
        val params = req.optJSONObject("params") ?: JSONObject()
        Logx.i("shop bridge $method")
        when (method) {
            "publish" -> currentScope.launch {
                val images = params.optJSONArray("images")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
                val input = ProductInput(params.optString("description"), params.optLong("originalPrice"), params.optLong("discountPrice"), images)
                runCatching { account.shopPublish(input) }
                    .onSuccess { reply(id, it.json(), null) }
                    .onFailure {
                        Logx.w("shop publish failed", it)
                        reply(id, null, it.message ?: context.getString(R.string.error))
                    }
            }
            "remove" -> currentScope.launch {
                runCatching { account.shopRemove(params.optString("id")) }
                    .onSuccess { reply(id, null, null) }
                    .onFailure {
                        Logx.w("shop remove failed", it)
                        reply(id, null, it.message ?: context.getString(R.string.error))
                    }
            }
            "share" -> {
                val text = listOf(params.optString("text"), params.optString("url")).filter { it.isNotBlank() }.joinToString("\n")
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, null))
                reply(id, null, null)
            }
            else -> Logx.w("shop bridge unknown method $method")
        }
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        TapeTapeToolbar(title, onNav = { nav.pop() })
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = c.dialogAccent)
        AndroidView(
                factory = { ctx ->
                    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                            WebViewCompat.addWebMessageListener(this, "TapeTapeShopAndroid", hosts.map { "https://$it" }.toSet()) { _, message, origin, mainFrame, _ ->
                                message.data?.let { handle(origin, mainFrame, it) }
                            }
                        } else {
                            Logx.w("shop bridge unavailable: no web message listener")
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (request.url.host in hosts) return false
                                Logx.d("shop external link")
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                view.title?.takeIf { it.isNotBlank() && !it.startsWith("http") }?.let { title = it }
                                // 网关偶尔读链失败（几家节点没凑齐一致结果），显示「节点不足」页；自动刷新几次，通常第二次就好
                                if (isRpcFailPage(view.title)) {
                                    if (rpcRetries < RPC_RETRIES) {
                                        rpcRetries++
                                        Logx.w("shop gateway rpc failure, retry $rpcRetries")
                                        loading = true
                                        view.postDelayed({ view.reload() }, 1200L * rpcRetries)
                                    }
                                } else {
                                    rpcRetries = 0
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView, t: String?) {
                                if (!t.isNullOrBlank() && !t.startsWith("http")) title = t
                            }

                            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                                fileCallback?.onReceiveValue(null)
                                fileCallback = callback
                                return runCatching { chooser.launch(params.createIntent()) }
                                    .onFailure {
                                        Logx.w("shop file chooser failed", it)
                                        fileCallback = null
                                    }.isSuccess
                            }
                        }
                        Logx.i("shop load")
                        loadUrl(url)
                        webView = this
                    }
                },
                onRelease = {
                    fileCallback?.onReceiveValue(null)
                    it.destroy()
                },
                modifier = Modifier.fillMaxSize().clipToBounds(),
            )
    }
}
