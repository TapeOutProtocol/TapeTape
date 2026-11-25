package org.tapetape.v2.ui.square

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.tapetape.v2.Logx
import org.tapetape.v2.R
import org.tapetape.v2.core.DappTx
import org.tapetape.v2.core.TapeTapeAccount
import java.security.SecureRandom

// 广场浏览器的网页钱包（原生一侧）。网页里注入的 provider.js 把 window.ethereum 的请求转到这里：
//   只读请求直接查链；连接、切换网络、签名、发交易都先弹窗问用户，用户点了确认才交给内核签名。
// 默认 X Layer，可以切到 BNB 链。网络和连接授权都按网站来源分开记，只在这次打开 App 期间有效。
//
// 安全规定（2026-11-24 起经过三轮、每轮五个角色的独立安全审计）：
// - 通道：不往网页里注入任何对象。页面显示出来后，原生用 postWebMessage 把一个消息端口只送给主页面（按页面来源投递，
//   带随机口令）；provider.js 只收原生送来的（没有 source、origin 为空），收下后网页其他脚本收不到。
//   框架、非 https 页面什么都拿不到；
// - 请求一开始就固定这次用的网络，弹窗显示的和最后签/发的一定是同一条链；
// - 钱包弹窗、报错提示、网页自己的 alert/confirm 共用一个位置：同一时间只有一个；
//   页面跳转、离开广场时挂着的请求全部作废（已经在广播的交易不打断）；
// - 确认键要等弹窗出现满 1 秒、屏幕上没有手指、而且 0.8 秒内没有任何触摸（按下、移动、抬起）才能点；
//   确认和取消的左右位置随机；授权类、盲签再确认一次；
// - 同一网站（按注册域名）连续被拒 3 次冷却，冷却时间逐级加长（30 秒、2 分钟、5 分钟），到期后重新计数；
//   报错提示 1 分钟内超过 2 次就不再弹；网页弹窗 10 秒内超过 3 次就暂停 1 分钟；弹窗没能显示出来不算用户拒绝；
// - 弹窗写清是不是核对过的链上网站；回给网页的错误都是通用说法。

object WalletChain {
    const val XLAYER = 196L
    const val BSC = 56L
    val all = listOf(XLAYER, BSC)
    fun name(id: Long) = if (id == BSC) "BNB Chain" else "X Layer"
    fun hex(id: Long) = "0x" + id.toString(16)
}

class WalletRpcError(val code: Int, message: String) : Exception(message) {
    companion object {
        fun rejected() = WalletRpcError(4001, "User rejected the request.")
        fun unauthorized() = WalletRpcError(4100, "The requested account and/or method has not been authorized by the user.")
        fun unsupported() = WalletRpcError(4200, "The requested method is not supported.")
        fun busy() = WalletRpcError(-32002, "Request already pending.")
        fun invalid() = WalletRpcError(-32602, "Invalid parameters.")
        fun internal() = WalletRpcError(-32603, "Internal error.")
        fun limited() = WalletRpcError(-32005, "Request limit exceeded.")
        fun disconnected() = WalletRpcError(4900, "The provider is disconnected from all chains.")
    }
}

/** 网站的「注册域名」：常见的公共后缀（托管平台、二级国家后缀）要多算一段 */
object SiteName {
    private val suffixes = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "com.hk", "com.tw",
        "com.au", "net.au", "org.au", "co.jp", "ne.jp", "or.jp", "co.kr", "com.br", "com.sg", "com.my", "co.in", "co.nz",
        "co.za", "com.mx", "com.tr", "com.vn", "co.id", "com.ph", "com.ar",
        "github.io", "gitlab.io", "vercel.app", "netlify.app", "pages.dev", "workers.dev", "web.app", "firebaseapp.com",
        "herokuapp.com", "glitch.me", "onrender.com", "fly.dev", "azurewebsites.net", "cloudfront.net", "appspot.com",
        "blogspot.com", "myshopify.com", "wordpress.com", "surge.sh", "ngrok.io", "ngrok-free.app", "trycloudflare.com",
        "amplifyapp.com", "replit.app", "repl.co", "deno.dev", "railway.app", "up.railway.app", "s3.amazonaws.com",
        "ipfs.dweb.link", "ipns.dweb.link", "4everland.app", "fleek.co", "on-fleek.app", "eth.limo", "eth.link",
    )

    fun clean(host: String) = host.lowercase().trimEnd('.')

    /** okx.com.evil.xyz → evil.xyz；evil.github.io → evil.github.io；结尾的点去掉 */
    fun registrable(rawHost: String): String {
        val host = clean(rawHost)
        if (host.all { it.isDigit() || it == '.' || it == ':' }) return host
        val parts = host.split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return host
        for (take in listOf(3, 2)) {
            if (parts.size > take && parts.takeLast(take).joinToString(".") in suffixes) return parts.takeLast(take + 1).joinToString(".")
        }
        if (parts.takeLast(2).joinToString(".") in suffixes || parts.takeLast(3).joinToString(".") in suffixes) return host
        return parts.takeLast(2).joinToString(".")
    }
}

/** 弹窗的结果：用户同意、用户拒绝、没能显示（不算用户拒绝） */
enum class Answer { YES, NO, NOT_SHOWN }

class DappWallet(private val context: Context, private val scope: CoroutineScope) {
    /**
     * 要用户回答的弹窗。cancel 为 null 时只有一个按钮（提示用）；danger 时确认键标红；
     * delay = 确认键要等手指停下；okFirst = 确认键排在左边（位置随机）；page = 网页自己的弹窗（换一种样式）
     */
    class Prompt(
        val title: String, val message: String, val ok: String, val cancel: String?,
        val danger: Boolean, val delay: Boolean, val okFirst: Boolean, val page: Boolean, val answer: CompletableDeferred<Answer>,
    )

    var prompt by mutableStateOf<Prompt?>(null)
        private set
    /** 每个网站各自的网络（没记过的是 X Layer） */
    private var chains by mutableStateOf(mapOf<String, Long>())
    private var connected by mutableStateOf(setOf<String>())
    /** 每个网站（按注册域名）连续被拒的次数、冷却截止时间、已经冷却过几轮 */
    private data class Reject(val count: Int, val until: Long, val level: Int)
    private var rejects by mutableStateOf(mapOf<String, Reject>())
    private val notices = mutableMapOf<String, List<Long>>()
    private val pageDialogs = mutableMapOf<String, List<Long>>()
    private val pageDialogMuted = mutableMapOf<String, Long>()
    var account: TapeTapeAccount? = null
    /** 当前已显示页面的链上核对结果（浏览器给的，按页面记）：true 通过，false 没通过，null 不是链上网站或还不知道 */
    var verified: (String) -> Boolean? = { null }
    private var webView: WebView? = null
    /** 正在走确认流程的请求属于哪一代页面；null = 空闲 */
    private var owner: Int? = null
    val busy get() = owner == generation || prompt != null
    /** 每次页面跳转加一：跳转前发起的请求，跳转后一律作废 */
    private var generation = 0
    private val jobs = mutableSetOf<Job>()
    /** 最后一次触摸活动（按下、移动、抬起，网页或弹窗上）的时间、当前按着的手指数：确认键要等手指停下来 */
    var lastTouch = 0L
    var activePointers = 0

    /** 这次启动的口令：provider.js 靠它认出原生送来的端口（另外还核对消息确实来自原生） */
    private val token = "c" + ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    /** 当前页面收到的端口（同一页可能送了两次，provider.js 只留第一个） */
    private class Channel(val port: WebMessagePortCompat, val origin: String, val generation: Int)
    private val channels = mutableListOf<Channel>()
    private val main = Handler(Looper.getMainLooper())

    private val supported get() = listOf(
        WebViewFeature.DOCUMENT_START_SCRIPT, WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL, WebViewFeature.POST_WEB_MESSAGE,
        WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK, WebViewFeature.WEB_MESSAGE_PORT_POST_MESSAGE, WebViewFeature.WEB_MESSAGE_PORT_CLOSE,
    ).all(WebViewFeature::isFeatureSupported)

    /** 装到浏览器上：页面开始加载前注入 provider.js（它自己只在 https 顶层页面生效）。系统 WebView 太旧就不装 */
    fun attach(view: WebView): Boolean {
        webView = view
        if (!supported) {
            Logx.w("webview too old for the wallet")
            return false
        }
        val js = context.assets.open("tapekit/provider.js").bufferedReader().use { it.readText() }
            .replace("__TT_CHANNEL__", token).replace("__TT_PLATFORM__", "android")
        // 来源规则写不出「所有 https」（系统只认 "*" 或具体域名），所以用 "*"；脚本自己查是不是 https 顶层页面
        return runCatching { WebViewCompat.addDocumentStartJavaScript(view, js, setOf("*")); true }
            .getOrElse { Logx.w("wallet attach failed: $it"); false }
    }

    /** 页面显示出来了（onPageCommitVisible / onPageFinished 各调一次）：给主页面送一个消息端口 */
    fun pageReady(url: String?) {
        val view = webView ?: return
        val origin = originOf(url) ?: return
        if (!supported || !allowed(origin) || originOf(view.url) != origin) return
        runCatching {
            val (mine, theirs) = WebViewCompat.createWebMessageChannel(view)
            val ch = Channel(mine, origin, generation)
            mine.setWebMessageCallback(main, object : WebMessagePortCompat.WebMessageCallbackCompat() {
                override fun onMessage(port: WebMessagePortCompat, message: WebMessageCompat?) {
                    onRequest(ch, message?.data ?: return)
                }
            })
            channels += ch
            WebViewCompat.postWebMessage(view, WebMessageCompat(token, arrayOf(theirs)), Uri.parse(origin))
        }.onFailure { Logx.w("wallet channel failed: $it") }
    }

    fun answer(p: Prompt, a: Answer) {
        // 只回答「自己这个弹窗」：旧弹窗的点击落不到新弹窗上
        if (prompt !== p) return
        prompt = null
        p.answer.complete(a)
    }

    /** 页面跳转、离开广场：挂着的请求全部作废，开着的弹窗关掉（当作拒绝），旧端口关掉 */
    fun cancelAll() {
        generation++
        owner = null
        prompt?.let { answer(it, Answer.NO) }
        jobs.toList().forEach { it.cancel() }
        jobs.clear()
        channels.forEach { runCatching { it.port.close() } }
        channels.clear()
    }

    fun isConnected(origin: String?) = origin != null && origin in connected

    fun chainId(origin: String?): Long = origin?.let { chains[it] } ?: WalletChain.XLAYER

    /** 这个网站正在冷却（钱包请求会被直接拒绝）：菜单里显示「解除钱包限制」 */
    fun isCoolingDown(origin: String?): Boolean =
        origin != null && (rejects[siteKey(origin)]?.until ?: 0L) > System.currentTimeMillis()

    fun clearCooldown(origin: String) {
        rejects = rejects - siteKey(origin)
        pageDialogMuted.remove(siteKey(origin))
    }

    fun disconnect(origin: String) {
        connected = connected - origin
        emit(origin, "accountsChanged", JSONArray())
    }

    /** 切换某个网站的网络（浏览器菜单或网页请求确认后），并通知这个网站 */
    fun setChain(id: Long, origin: String) {
        if (id !in WalletChain.all || id == chainId(origin)) return
        chains = chains + (origin to id)
        emit(origin, "chainChanged", WalletChain.hex(id))
    }

    /** 只发给当前打开的就是这个网站的页面（经私有端口，网页里没有可调用的入口） */
    private fun emit(origin: String, event: String, data: Any) {
        if (originOf(webView?.url) != origin) return
        val msg = JSONObject().put("event", event).put("data", data).toString()
        channels.filter { it.origin == origin && it.generation == generation }.forEach { runCatching { it.port.postMessage(WebMessageCompat(msg)) } }
    }

    /**
     * 网页自己的 alert/confirm：和钱包弹窗共用一个位置；钱包在用、已有弹窗，或这个网站 10 秒内已经弹过 3 次（之后静音 1 分钟）时直接当作取消。
     * 返回 false = 不显示、按取消处理
     */
    fun pageDialog(origin: String, message: String, withCancel: Boolean, done: (Boolean) -> Unit): Boolean {
        val key = siteKey(origin)
        val now = System.currentTimeMillis()
        if ((pageDialogMuted[key] ?: 0L) > now) return false
        val recent = (pageDialogs[key] ?: emptyList()).filter { now - it < 10_000 }
        if (recent.size >= 3) {
            pageDialogMuted[key] = now + 60_000
            return false
        }
        if (busy) return false
        pageDialogs[key] = recent + now
        val d = CompletableDeferred<Answer>()
        val gen = generation
        owner = gen
        prompt = Prompt(
            s(R.string.v2_browser_alert_from, display(origin)), s(R.string.v2_browser_page_content) + "\n\n" + pageText(message),
            context.getString(android.R.string.ok), if (withCancel) context.getString(android.R.string.cancel) else null,
            danger = false, delay = false, okFirst = false, page = true, answer = d,
        )
        scope.launch {
            val a = d.await()
            if (owner == gen) owner = null
            done(a == Answer.YES)
        }
        return true
    }

    private fun onRequest(ch: Channel, data: String) {
        val req = runCatching { JSONObject(data) }.getOrNull() ?: return
        val id = req.optString("id")
        fun send(result: String?, error: WalletRpcError?) {
            val out = JSONObject().put("id", id)
            if (error != null) out.put("error", JSONObject().put("code", error.code).put("message", error.message))
            else out.put("result", result ?: "null")
            runCatching { ch.port.postMessage(WebMessageCompat(out.toString())) }
        }
        // 端口只属于送出它时的那一页；页面换了、网址对不上，一律回通用错误
        if (ch.generation != generation || originOf(webView?.url) != ch.origin) return send(null, WalletRpcError.unauthorized())
        val method = req.optString("method")
        val params = req.optJSONArray("params") ?: JSONArray()
        val gen = generation
        val job = scope.launch {
            try {
                val r = handle(method, params, ch.origin)
                if (gen != generation) send(null, WalletRpcError.internal()) else send(r, null)
            } catch (e: CancellationException) {
                send(null, WalletRpcError.internal())
                throw e
            } catch (e: WalletRpcError) {
                send(null, e)
            } catch (e: Exception) {
                send(null, WalletRpcError.internal())
            }
        }
        jobs += job
        job.invokeOnCompletion { jobs -= job }
    }

    /** 需要弹窗的请求：同一时间只能有一个；在冷却中的网站直接拒绝 */
    private suspend fun <T> exclusive(origin: String, body: suspend () -> T): T {
        if (busy) throw WalletRpcError.busy()
        if (isCoolingDown(origin)) throw WalletRpcError.rejected()
        val gen = generation
        owner = gen
        try {
            return body()
        } finally {
            if (owner == gen) owner = null
        }
    }

    /** 记一次用户拒绝 / 同意：同一网站连续拒 3 次冷却，冷却时间逐级加长（30 秒、2 分钟、5 分钟），到期后重新计数 */
    private fun record(origin: String, rejected: Boolean) {
        val key = siteKey(origin)
        val now = System.currentTimeMillis()
        var r = rejects[key] ?: Reject(0, 0L, 0)
        if (r.until <= now && r.count >= 3) r = r.copy(count = 0)   // 上一轮冷却结束：重新计数
        if (!rejected) {
            rejects = rejects + (key to r.copy(count = 0, until = 0L))
            return
        }
        r = r.copy(count = r.count + 1)
        if (r.count >= 3) {
            val steps = listOf(30_000L, 120_000L, 300_000L)
            r = r.copy(until = now + steps[minOf(r.level, steps.size - 1)], level = r.level + 1)
        }
        rejects = rejects + (key to r)
    }

    /** 所有弹窗都从这里出：已有弹窗时不叠加 → NOT_SHOWN（不算用户拒绝） */
    private suspend fun show(title: String, message: String, ok: String, cancel: String?, danger: Boolean = false, delay: Boolean = true, shuffle: Boolean = true): Answer {
        if (prompt != null) return Answer.NOT_SHOWN
        val d = CompletableDeferred<Answer>()
        prompt = Prompt(title, message, ok, cancel, danger, delay, shuffle && kotlin.random.Random.nextBoolean(), false, d)
        return d.await()
    }

    /**
     * 只有「好」的提示框（报错）：也占同一个位置、等用户点掉；同一网站 1 分钟内超过 2 次就不再弹（只把错误回给网页）。
     * retry：位置被占时每秒再试一次，最多 30 次（交易失败的提示不能丢）
     */
    private suspend fun notice(origin: String, title: String, message: String, gen: Int?, retry: Boolean = false) {
        val key = siteKey(origin)
        val now = System.currentTimeMillis()
        val recent = (notices[key] ?: emptyList()).filter { now - it < 60_000 }
        if (!retry && recent.size >= 2) return
        notices[key] = recent + now
        repeat(if (retry) 30 else 1) {
            if (gen != null && gen != generation) return
            if (show(title, message, context.getString(android.R.string.ok), null, delay = false, shuffle = false) != Answer.NOT_SHOWN) return
            delay(1000)
        }
    }

    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    /** 弹窗第一行：「链上网站：文件与链上一致」/「链上网站，本页未核对」/「普通网站，内容未经核对」 */
    private fun siteLabel(origin: String): String {
        val host = Uri.parse(origin).host.orEmpty()
        if (!host.endsWith(".tape")) return s(R.string.v2_wallet_site_unverified)
        return if (verified(origin) == true) s(R.string.v2_wallet_site_verified) else s(R.string.v2_wallet_site_tape_unverified)
    }

    /** 返回结果的 JSON 文本（网页那边 JSON.parse） */
    private suspend fun handle(method: String, params: JSONArray, origin: String): String {
        val account = account ?: throw WalletRpcError.disconnected()
        val address = account.walletAddress()
        val site = display(origin)
        val label = siteLabel(origin)
        // 这次请求用的网络在这里固定下来：之后菜单里切网络不影响这一次
        val chain = chainId(origin)
        val gen = generation
        fun stillHere() { if (gen != generation) throw WalletRpcError.rejected() }
        /** 问用户；拒绝计入冷却；没能显示出来不算拒绝（回「已有请求在等待」） */
        suspend fun ask(title: String, text: String, ok: String, danger: Boolean = false): Boolean {
            stillHere()
            val a = show(title, text, ok, context.getString(android.R.string.cancel), danger)
            stillHere()
            if (a == Answer.NOT_SHOWN) throw WalletRpcError.busy()
            record(origin, a == Answer.NO)
            return a == Answer.YES
        }
        /** 授权类操作、盲签：再确认一次（按钮位置重新随机） */
        suspend fun askDanger(text: String): Boolean {
            delay(300)
            return ask(s(R.string.v2_wallet_danger_title), text, s(R.string.v2_wallet_danger_confirm), danger = true)
        }
        val connectedHere = origin in connected
        return when (method) {
            "eth_chainId" -> JSONObject.quote(WalletChain.hex(chain))
            "net_version" -> JSONObject.quote(chain.toString())
            "eth_accounts" -> if (connectedHere) JSONArray().put(address).toString() else "[]"
            "eth_requestAccounts", "wallet_requestPermissions" -> {
                if (!connectedHere) {
                    val ok = exclusive(origin) {
                        ask(s(R.string.v2_wallet_connect_title), s(R.string.v2_wallet_connect_msg, label, site, address, WalletChain.name(chain)), s(R.string.v2_wallet_connect))
                    }
                    if (!ok) throw WalletRpcError.rejected()
                    connected = connected + origin
                    Logx.d("wallet connected")
                }
                if (method == "eth_requestAccounts") JSONArray().put(address).toString()
                else JSONArray().put(JSONObject().put("parentCapability", "eth_accounts")).toString()
            }
            "wallet_getPermissions" -> if (connectedHere) JSONArray().put(JSONObject().put("parentCapability", "eth_accounts")).toString() else "[]"
            "wallet_revokePermissions" -> { disconnect(origin); "null" }
            "wallet_switchEthereumChain", "wallet_addEthereumChain" -> {
                if (!connectedHere) throw WalletRpcError.unauthorized()
                val id = parseChainId(params.optJSONObject(0)?.opt("chainId"))
                if (id == null || id !in WalletChain.all) throw WalletRpcError(4902, "Unrecognized chain ID.")
                if (id != chain) {
                    val ok = exclusive(origin) { ask(s(R.string.v2_wallet_switch_title), s(R.string.v2_wallet_switch_msg, label, site, WalletChain.name(id)), s(R.string.v2_wallet_switch)) }
                    if (!ok) throw WalletRpcError.rejected()
                    setChain(id, origin)
                }
                "null"
            }
            "personal_sign" -> {
                if (!connectedHere) throw WalletRpcError.unauthorized()
                val a = params.opt(0) as? String ?: throw WalletRpcError.invalid()
                if (params.length() > 1 && params.opt(1) !is String) throw WalletRpcError.invalid()
                val b = params.opt(1) as? String
                // 标准顺序是 [消息, 地址]；有的网页写反了。写了地址的，必须是这个钱包
                val raw = if (b != null && a.equals(address, ignoreCase = true)) b else {
                    if (b != null && !b.equals(address, ignoreCase = true)) throw WalletRpcError.invalid()
                    a
                }
                val m = account.dappMessage(raw)
                val ok = exclusive(origin) {
                    val shown = if (m.blind) s(R.string.v2_wallet_blind_warn) + "\n\n" + m.text else m.text
                    ask(s(R.string.v2_wallet_sign_title), s(R.string.v2_wallet_sign_msg, label, site, shown), s(R.string.v2_wallet_sign), m.blind) &&
                        (!m.blind || askDanger(s(R.string.v2_wallet_danger_msg, s(R.string.v2_wallet_danger_whoever))))
                }
                if (!ok) throw WalletRpcError.rejected()
                stillHere()
                JSONObject.quote(account.dappPersonalSign(m.hex))
            }
            "eth_signTypedData_v4", "eth_signTypedData_v3" -> {
                if (!connectedHere) throw WalletRpcError.unauthorized()
                if (!(params.opt(0) as? String).equals(address, ignoreCase = true)) throw WalletRpcError.invalid()
                val version = if (method == "eth_signTypedData_v3") 3 else 4
                val data = params.opt(1)?.let { if (it is String) it else it.toString() } ?: throw WalletRpcError.invalid()
                exclusive(origin) {
                    val summary = try {
                        account.dappTypedData(chain, data, version)
                    } catch (e: Exception) {
                        notice(origin, s(R.string.v2_wallet_sign_fail_title), errorText(e.message ?: "", typed = true), gen)
                        throw WalletRpcError.invalid()
                    }
                    // 危险警告排在所有网页可控的内容前面
                    val warn = if (summary.danger) s(R.string.v2_wallet_permit_warn) + "\n\n" else ""
                    val ok = ask(
                        s(R.string.v2_wallet_sign_title),
                        s(R.string.v2_wallet_typed_msg, label, site, warn, summary.domainName, summary.verifyingContract, WalletChain.name(chain), summary.primaryType, summary.fields),
                        s(R.string.v2_wallet_sign),
                        summary.danger,
                    ) && (!summary.danger || askDanger(s(R.string.v2_wallet_danger_msg, summary.counterparty ?: s(R.string.v2_wallet_danger_whoever))))
                    if (!ok) throw WalletRpcError.rejected()
                    stillHere()
                    JSONObject.quote(account.dappSignTypedData(chain, data, version))
                }
            }
            "eth_sendTransaction" -> {
                if (!connectedHere) throw WalletRpcError.unauthorized()
                val tx = params.optJSONObject(0) ?: throw WalletRpcError.invalid()
                val txJson = tx.toString()
                exclusive(origin) {
                    val check = try {
                        account.dappCheckTx(chain, origin, txJson)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw if (e.message == "rate-limited") WalletRpcError.limited() else WalletRpcError.internal()
                    }
                    stillHere()
                    check.error?.let {
                        notice(origin, s(R.string.v2_wallet_tx_fail_title), errorText(it, typed = false), gen)
                        throw WalletRpcError.internal()
                    }
                    val grants = check.action in setOf("approve", "increase-allowance", "approval-for-all", "permit2-approve", "nft-approve")
                    val dangerText = s(if (grants) R.string.v2_wallet_danger_msg else R.string.v2_wallet_danger_tx_msg, check.counterparty ?: check.to)
                    val ok = ask(s(R.string.v2_wallet_tx_title), s(R.string.v2_wallet_tx_msg, label, site, txLines(check)), s(R.string.v2_wallet_send), check.danger) &&
                        (!check.danger || askDanger(dangerText))
                    if (!ok) throw WalletRpcError.rejected()
                    stillHere()
                    // 进入发送：页面跳走也不打断（交易可能已经广播），结果照实告诉用户
                    withContext(NonCancellable) {
                        try {
                            JSONObject.quote(account.dappSendTx(chain, txJson, check.fee, check.ticket))
                        } catch (e: Exception) {
                            // 失败提示写明是哪个网站的交易；位置被占就等一会儿再弹
                            notice(origin, s(R.string.v2_wallet_tx_fail_title) + "（$site）", errorText(e.message ?: "", typed = false), null, retry = true)
                            throw WalletRpcError.internal()
                        }
                    }
                }
            }
            // eth_sign 是盲签任意哈希，钓鱼最常用；只签不发的交易、旧版签名格式也不支持
            "eth_sign", "eth_signTransaction", "eth_signTypedData" -> throw WalletRpcError.unsupported()
            else -> try {
                account.dappRpc(chain, origin, method, params.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val m = e.message ?: ""
                throw when {
                    m.startsWith("unsupported-method") -> WalletRpcError.unsupported()
                    m == "rate-limited" || m == "getlogs-range" -> WalletRpcError.limited()
                    else -> WalletRpcError.internal()
                }
            }
        }
    }

    /** 交易确认弹窗的正文（金额都是内核给的精确数字，不四舍五入） */
    private fun txLines(t: DappTx): String {
        val sep = s(R.string.v2_wallet_sep)
        val lines = mutableListOf("${s(R.string.v2_wallet_l_network)}$sep${t.chainName}")
        val tokenName = t.tokenSymbol?.let { if (t.tokenKnown) it else s(R.string.v2_wallet_self_claimed, it) } ?: s(R.string.v2_wallet_unknown_token)
        // 合约调用同时转出原生币：紧跟在操作后面写出来
        val paying = if (t.dataLen > 0 && t.value != "0") "⚠️ " + s(R.string.v2_wallet_also_pay, t.value, t.symbol) else null
        val action = t.action
        if (action != null) {
            lines += "${s(R.string.v2_wallet_l_action)}$sep${actionText(action)}"
            paying?.let { lines += it }
            t.token?.let {
                val key = when {
                    t.nft -> R.string.v2_wallet_l_nft
                    action == "approval-for-all" || action == "revoke-all" -> R.string.v2_wallet_l_asset_contract
                    else -> R.string.v2_wallet_l_token
                }
                lines += "${s(key)}$sep$tokenName（$it）"
            }
            val amount = t.tokenAmount
            when {
                t.nft && amount != null -> lines += "${s(R.string.v2_wallet_l_nft_id)}$sep#$amount"
                t.unlimited -> lines += "${s(R.string.v2_wallet_l_amount)}$sep${s(R.string.v2_wallet_unlimited)}"
                amount != null -> lines += "${s(R.string.v2_wallet_l_amount)}$sep$amount " + (if (t.rawAmount) s(R.string.v2_wallet_raw_amount) else tokenName)
            }
            t.expiry?.let { lines += "${s(R.string.v2_wallet_l_expiry)}$sep$it" }
            t.from?.let { lines += "${s(R.string.v2_wallet_l_from)}$sep$it" }
            t.counterparty?.let {
                val key = if (action in setOf("transfer", "transfer-from", "nft-transfer-from")) R.string.v2_wallet_l_recipient else R.string.v2_wallet_l_spender
                lines += "${s(key)}$sep$it"
            }
            lines += "${s(R.string.v2_wallet_l_contract)}$sep${t.to}"
            if (t.extraData) lines += s(R.string.v2_wallet_extra_data)
        } else if (t.dataLen > 0) {
            lines += "${s(R.string.v2_wallet_l_action)}$sep${s(R.string.v2_wallet_act_unknown, t.selector ?: "")}"
            paying?.let { lines += it }
            lines += "${s(R.string.v2_wallet_l_contract)}$sep${t.to}"
        } else {
            lines += "${s(if (t.toIsContract) R.string.v2_wallet_l_contract else R.string.v2_wallet_l_to)}$sep${t.to}"
        }
        if (t.dataLen == 0) lines += "${s(R.string.v2_wallet_l_value)}$sep${t.value} ${t.symbol}"
        lines += "${s(R.string.v2_wallet_l_fee)}$sep${t.fee} ${t.symbol}"
        return lines.joinToString("\n")
    }

    private fun actionText(a: String) = s(
        when (a) {
            "approve" -> R.string.v2_wallet_act_approve
            "revoke" -> R.string.v2_wallet_act_revoke
            "increase-allowance" -> R.string.v2_wallet_act_increase_allowance
            "transfer" -> R.string.v2_wallet_act_transfer
            "transfer-from" -> R.string.v2_wallet_act_transfer_from
            "approval-for-all" -> R.string.v2_wallet_act_approval_for_all
            "revoke-all" -> R.string.v2_wallet_act_revoke_all
            "nft-approve" -> R.string.v2_wallet_act_nft_approve
            "nft-transfer-from" -> R.string.v2_wallet_act_nft_transfer_from
            else -> R.string.v2_wallet_act_permit2_approve
        },
    )

    /** 内核的错误码 → 给用户看的话。只有发送阶段说不清时才说「可能已发出」 */
    private fun errorText(code: String, typed: Boolean): String = when {
        code == "insufficient-balance" -> s(R.string.v2_wallet_err_balance)
        code == "insufficient-gas" -> s(R.string.v2_wallet_err_gas)
        code == "no-contract-creation" -> s(R.string.v2_wallet_err_create)
        code == "chain-mismatch" -> s(R.string.v2_wallet_err_chain)
        code == "fee-changed" || code == "ticket-mismatch" -> s(R.string.v2_wallet_err_fee)
        code == "domain-mismatch" -> s(R.string.v2_wallet_err_domain)
        code == "domain-no-chain" -> s(R.string.v2_wallet_err_nochain)
        code == "typed-extra-fields" -> s(R.string.v2_wallet_err_extra)
        code == "nonce-mismatch" -> s(R.string.v2_wallet_err_nonce)
        code == "rate-limited" -> s(R.string.v2_wallet_err_rate)
        code == "maybe-sent" -> s(R.string.v2_wallet_err_unknown)
        code.startsWith("will-fail: ") -> s(R.string.v2_wallet_err_fail, code.removePrefix("will-fail: "))
        typed -> s(R.string.v2_wallet_err_typed)
        else -> s(R.string.v2_wallet_err_generic)
    }

    companion object {
        fun originOf(u: Uri): String = buildString {
            append(u.scheme?.lowercase()).append("://").append(u.host?.let(SiteName::clean))
            if (u.port > 0) append(":").append(u.port)
        }

        fun originOf(url: String?): String? = url?.let { Uri.parse(it) }?.takeIf { !it.host.isNullOrEmpty() }?.let(::originOf)

        /** 能用钱包的来源：https 且有主机名（链上网站在安卓上也是 https://<名字>.tape） */
        fun allowed(origin: String): Boolean = Uri.parse(origin).let { it.scheme == "https" && !it.host.isNullOrEmpty() }

        /** 冷却、限次按「网站」算：链上网站按名字；普通网站按注册域名，换子域名、端口绕不开 */
        fun siteKey(origin: String): String {
            val host = Uri.parse(origin).host?.let(SiteName::clean) ?: return origin
            return if (host.endsWith(".tape")) host else SiteName.registrable(host)
        }

        /** 给人看的网站名：链上网站显示短名字（2.2.0）；普通网站把注册域名放前面，带端口的写出端口 */
        fun display(origin: String): String {
            val u = Uri.parse(origin)
            val host = u.host?.let(SiteName::clean) ?: return origin
            if (host.endsWith(".tape")) return host.removeSuffix(".tape")
            val port = if (u.port > 0) ":${u.port}" else ""
            val main = SiteName.registrable(host)
            return if (main == host) host + port else "$main（$host$port）"
        }

        /** 网页弹窗正文：标出看不见的字符、去掉冒充 App 标签的「【」开头的行、最多 20 行、整体缩进 */
        fun pageText(s: String): String {
            val sb = StringBuilder()
            s.codePoints().forEach { c ->
                val hidden = (c < 0x20 && c != 0x0a && c != 0x09) || c in 0x7f..0x9f || c in 0x200b..0x200f || c in 0x2028..0x202e ||
                    c in 0x2060..0x206f || c == 0xfeff || c == 0x061c || c in 0xe000..0xf8ff
                if (hidden) sb.append("⟦U+%04X⟧".format(c)) else sb.appendCodePoint(c)
            }
            return sb.split('\n').filterNot { it.trim().startsWith("【") }.take(20).joinToString("\n") { "  $it" }
        }

        /** 网络编号只认 0x 十六进制（EIP-3326 的写法） */
        fun parseChainId(raw: Any?): Long? =
            (raw as? String)?.takeIf { it.startsWith("0x", true) }?.substring(2)?.toLongOrNull(16)
    }
}
