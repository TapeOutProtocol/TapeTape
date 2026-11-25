package org.tapetape.v2.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import org.tapetape.v2.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom

object CoreFactory {
    const val REAL = false
    fun create(dataDir: String, sink: EventSink): TapeTapeCore = FakeCore(File(dataDir, "fake"), sink)
}

private val WORDS = ("abandon ability able about above absent absorb abstract absurd abuse access accident account accuse " +
    "achieve acid acoustic acquire across act action actor actress actual adapt add addict address adjust admit adult " +
    "advance advice aerobic affair afford afraid again age agent agree ahead aim air airport aisle alarm album alcohol " +
    "alert alien all alley allow almost alone alpha already also alter always amateur amazing among amount amused").split(" ")

private val random = SecureRandom()

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

class FakeCore(internal val dir: File, private val sink: EventSink) : TapeTapeCore {
    private val accounts = linkedMapOf<Int, FakeAccount>()
    private var selected: Int? = null
    private var nextId = 1
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized
    override fun accounts() = accounts.values.map { it.info() }

    @Synchronized
    override fun selectedAccount() = selected

    @Synchronized
    override fun selectAccount(id: Int) {
        if (id !in accounts) throw CoreException("Unknown account")
        selected = id
    }

    override suspend fun nodes(): List<String> {
        delay(500)
        return (1..5).map { "relay$it.taperelay.com" }
    }

    private val generated = mutableSetOf<String>()

    @Synchronized
    override fun generateMnemonic() = List(12) { WORDS[random.nextInt(WORDS.size)] }.joinToString(" ").also { generated += it }

    override suspend fun addAccount(mnemonic: String, node: String?): Int {
        val words = mnemonic.trim().split(Regex("\\s+"))
        if (words.size != 12) throw CoreException("Invalid mnemonic")
        delay(800)
        val address = "0x" + hex(sha256(words.joinToString(" "))).take(40)
        val host = node ?: "relay${(address.takeLast(2).toInt(16) % 5) + 1}.taperelay.com"
        synchronized(this) {
            val id = nextId++
            val acc = FakeAccount(this, id, address, host).apply { setMnemonic(words.joinToString(" ")) }
            if (words.joinToString(" ") !in generated) {
                acc.seedDemo()
                acc.seedPackets()
            }
            accounts[id] = acc
            if (selected == null) selected = id
            return id
        }
    }

    @Synchronized
    override fun removeAccount(id: Int) {
        accounts.remove(id)
        if (selected == id) selected = accounts.keys.firstOrNull()
    }

    @Synchronized
    override fun account(id: Int): TapeTapeAccount = accounts[id] ?: throw CoreException("Unknown account")

    override fun addressForMnemonic(mnemonic: String): String {
        val words = mnemonic.trim().split(Regex("\\s+"))
        if (words.size != 12 && words.size != 24) throw CoreException("Invalid mnemonic")
        return "0x" + hex(sha256(words.joinToString(" "))).take(40)
    }

    override fun walletTokens() = FAKE_TOKENS

    @Synchronized
    override fun moveAccountToTop(id: Int) {
        val acc = accounts.remove(id) ?: return
        val rest = accounts.toMap()
        accounts.clear()
        accounts[id] = acc
        accounts.putAll(rest)
    }

    private var proxies = ProxySettings(false, null, emptyList())

    @Synchronized
    override fun proxySettings() = proxies

    override fun parseProxy(link: String): ProxyInfo {
        val text = link.trim()
        val uri = Uri.parse(text)
        val scheme = uri.scheme?.lowercase()
        fun socks(host: String?, port: String?) = host?.takeIf { it.isNotBlank() }
            ?.let { ProxyInfo("socks5://$it:${port ?: "1080"}", ProxyKind.Socks5, it, port?.toIntOrNull() ?: 1080) }
        val info = when {
            scheme == "socks5" || scheme == "socks5h" -> socks(uri.host, uri.port.takeIf { it > 0 }?.toString())
            scheme == "tg" && uri.host == "socks" -> socks(uri.getQueryParameter("server"), uri.getQueryParameter("port"))
            scheme == "https" && uri.host == "t.me" && uri.path == "/socks" -> socks(uri.getQueryParameter("server"), uri.getQueryParameter("port"))
            (scheme == "http" || scheme == "https") && uri.path.orEmpty().trim('/').isEmpty() && uri.query == null && uri.host != null -> {
                val port = uri.port.takeIf { it > 0 } ?: if (scheme == "http") 80 else 443
                ProxyInfo("$scheme://${uri.host}:$port", if (scheme == "http") ProxyKind.Http else ProxyKind.Https, uri.host!!, port)
            }
            scheme == "ss" -> {
                val body = text.substringAfter("://").substringBefore("#")
                val plain = if ('@' in body) body else runCatching { String(android.util.Base64.decode(body, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)) }.getOrDefault("")
                val hostPort = plain.substringAfterLast('@', "").substringBefore('/').substringBefore('?')
                val host = hostPort.substringBeforeLast(':')
                val port = hostPort.substringAfterLast(':').toIntOrNull()
                if (host.isNotBlank() && port != null) ProxyInfo(text, ProxyKind.Shadowsocks, host, port) else null
            }
            else -> null
        }
        return info ?: throw CoreException("invalid-proxy")
    }

    @Synchronized
    private fun saveProxies(p: ProxySettings): ProxySettings {
        proxies = p.copy(enabled = p.enabled && p.active != null)
        Logx.d("fake proxy enabled=${proxies.enabled} active=${proxies.active != null} count=${proxies.proxies.size}")
        return proxies
    }

    @Synchronized
    override fun addProxy(link: String): ProxySettings {
        val info = parseProxy(link)
        return saveProxies(ProxySettings(true, info.url, listOf(info) + proxies.proxies.filter { it.url != info.url }))
    }

    @Synchronized
    override fun selectProxy(url: String): ProxySettings {
        if (proxies.proxies.none { it.url == url }) throw CoreException("Proxy not found")
        return saveProxies(proxies.copy(enabled = true, active = url))
    }

    @Synchronized
    override fun removeProxy(url: String): ProxySettings {
        val rest = proxies.proxies.filter { it.url != url }
        return if (proxies.active == url) saveProxies(ProxySettings(false, null, rest)) else saveProxies(proxies.copy(proxies = rest))
    }

    @Synchronized
    override fun setProxyEnabled(enabled: Boolean): ProxySettings {
        val active = proxies.active ?: if (enabled) proxies.proxies.firstOrNull()?.url else null
        return saveProxies(proxies.copy(enabled = enabled, active = active))
    }

    override fun backupProtection(path: String) = BackupProtection.RecoveryPhrase

    override suspend fun importBackup(path: String, secret: String): Int {
        if (!File(path).exists()) throw CoreException("Wrong passphrase or damaged backup")
        val id = addAccount(secret, null)
        synchronized(this) { if (selected == id && accounts.size > 1) selected = accounts.keys.first() }
        return id
    }

    override suspend fun fetchAll(): FetchResult {
        val list = synchronized(this) { accounts.values.toList() }
        return list.map { it.fetchOnce() }.let { r -> FetchResult(r.sumOf { it.newMessages }, r.flatMap { it.notifications }) }
    }

    internal fun emit(accountId: Int, event: CoreEvent) = sink(accountId, event)
}

private class FakeChat(
    val id: Int,
    var name: String,
    val isGroup: Boolean,
    val contactId: Int?,
    var pinned: Boolean = false,
    var muted: Boolean = false,
    var mutedUntil: Long? = null,
    var draft: String? = null,
    var archived: Boolean = false,
    var ephemeral: Int = 0,
    val isSelf: Boolean = false,
    var isRequest: Boolean = false,
    val members: MutableList<Int> = mutableListOf(),
    var avatar: String? = null,
    var isMember: Boolean = true,
    var sound: Boolean = true,
) {
    val isMuted get() = muted && (mutedUntil == null || mutedUntil!! > System.currentTimeMillis())
}

private const val LARGE = 170L * 1024

private val FAKE_TOKENS = listOf(
    Token("196:OKB", 196, "X Layer", "OKB", null, 18, false),
    Token("196:BEM", 196, "X Layer", "BEM", "0x0000000000000000000000000000000000000b3m", 8, false),
    Token("56:BNB", 56, "BNB Smart Chain", "BNB", null, 18, true),
    Token("56:USDT", 56, "BNB Smart Chain", "USDT", "0x55d398326f99059fF775485246999027B3197955", 18, true),
    Token("56:USDC", 56, "BNB Smart Chain", "USDC", "0x8AC76a51cc950d9822D68b83fE1Ad97B32Cd580d", 18, true),
    Token("56:USD1", 56, "BNB Smart Chain", "USD1", "0x8d0d000ee44948fc98c9b98a4fa4921476f08b0d", 18, true),
)

private const val PACKET_TTL = 7 * 86_400_000L

private class FakeAccount(
    private val core: FakeCore,
    private val id: Int,
    val address: String,
    private val node: String,
) : TapeTapeAccount {
    private var name = ""
    private var avatar: String? = null
    private var bio = ""
    private var token = hex(ByteArray(16).also { random.nextBytes(it) })
    private var connectivity = Connectivity.NotConnected
    private val contacts = linkedMapOf<Int, Contact>()
    private val deletedContacts = mutableSetOf<Int>()
    private val remoteFiles = mutableMapOf<Int, String>()
    private val chats = linkedMapOf<Int, FakeChat>()
    private val messages = mutableListOf<Message>()
    private var nextContact = 10
    private var nextChat = 10
    private var nextMsg = 1
    private var muted = false
    private var tag = ""
    private var settings = Settings()
    private var mnemonic = ""

    private val account get() = "key:$address@$node:4246"

    private fun emit(e: CoreEvent) = core.emit(id, e)

    fun seedDemo() {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val tester = addContact("RelayTester", "0x2c679020bb2123b1c3c7b3f645b734660d5b8a11", "relay2.taperelay.com")
        val alice = addContact("Alice", "0x9f1e4c2d7a3b5e6f8091a2b3c4d5e6f708192a3b", "relay3.taperelay.com")
        val t = newChat(tester.name, false, tester.id)
        val a = newChat(alice.name, false, alice.id).apply { pinned = true }
        addMsg(t.id, tester.id, tester.name, "Hi! Scan worked, we are connected.", now - 3 * day, MessageState.Seen)
        addMsg(t.id, null, "", "Great, text from Android", now - 3 * day + 60_000, MessageState.Read)
        val q = addMsg(t.id, tester.id, tester.name, "xfinal text iOS to Android", now - 2 * day, MessageState.Seen)
        addMsg(t.id, null, "", "xfinal text Android to iOS", now - 2 * day + 120_000, MessageState.Read, replyTo = q.id)
        addMsg(t.id, null, "", "This one did not go through", now - day, MessageState.Failed, error = "sender-not-allowed")
        addMsg(t.id, tester.id, tester.name, "Are you there?", now - 3_600_000, MessageState.Incoming)
        addMsg(a.id, alice.id, alice.name, "See you tomorrow", now - 7_200_000, MessageState.Incoming)
        addMsg(a.id, alice.id, alice.name, "Bring the keys 🔑", now - 7_000_000, MessageState.Incoming)
        val g = newChat("Relay Group", true, null).apply { muted = true; members += listOf(tester.id, alice.id) }
        addMsg(g.id, tester.id, tester.name, "Group hello from iOS", now - 4 * day, MessageState.Seen)
        val img = demoImage("Android to iOS", 0xFF2E8B57.toInt(), 0xFFF5D90A.toInt())
        addMsg(t.id, tester.id, tester.name, "", now - 3 * day + 120_000, MessageState.Seen, kind = MessageKind.Image).let {
            replace(it.copy(file = img.path, fileName = img.name, mime = "image/jpeg", size = img.length(), width = 800, height = 900, reactions = listOf(Reaction("👍", 1, true))))
        }
        val voice = demoWav("voice.wav", 4)
        addMsg(t.id, tester.id, tester.name, "", now - 3 * day + 180_000, MessageState.Seen, kind = MessageKind.Voice).let {
            replace(it.copy(file = voice.path, fileName = voice.name, mime = "audio/wav", size = voice.length(), durationMs = 4000))
        }
        val doc = File(core.dir, "notes.txt").apply { parentFile?.mkdirs(); writeText("TapeTape v2 demo file\n") }
        addMsg(t.id, null, "", "", now - 3 * day + 240_000, MessageState.Read, kind = MessageKind.File).let {
            replace(it.copy(file = doc.path, fileName = doc.name, mime = "text/plain", size = doc.length()))
        }
        addMsg(t.id, tester.id, tester.name, "", now - 2 * day - 60_000, MessageState.Seen, kind = MessageKind.Vcard).let {
            replace(it.copy(vcard = Vcard("Bob", "key:0x5b0b5b0b5b0b5b0b5b0b5b0b5b0b5b0b5b0b5b0b@relay4.taperelay.com:4246", "0x5b0b5b0b5b0b5b0b5b0b5b0b5b0b5b0b5b0b5b0b@relay4.taperelay.com")))
        }
        newChat("Saved Messages", false, null, isSelf = true)
        val archived = addContact("Old Friend", "0x77aa00bb11cc22dd33ee44ff5566778899aabbcc", "relay1.taperelay.com")
        val o = newChat(archived.name, false, archived.id).apply { this.archived = true }
        addMsg(o.id, archived.id, archived.name, "Long time no see", now - 20 * day, MessageState.Seen)
        val stranger = addContact("Stranger", "0x1234567890abcdef1234567890abcdef12345678", "relay5.taperelay.com")
        val r = newChat(stranger.name, false, stranger.id).apply { isRequest = true }
        addMsg(r.id, stranger.id, stranger.name, "Hello, can we talk?", now - 5 * 3_600_000, MessageState.Incoming)
    }

    private fun replace(m: Message) {
        val i = messages.indexOfFirst { it.id == m.id }
        messages[i] = m
    }

    private fun demoImage(label: String, top: Int, bottom: Int): File {
        val bmp = Bitmap.createBitmap(800, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, 900f, top, bottom, Shader.TileMode.CLAMP) })
        canvas.drawText(label, 400f, 470f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 72f; textAlign = Paint.Align.CENTER })
        val f = File(core.dir, "demo-${label.hashCode()}.jpg").apply { parentFile?.mkdirs() }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return f
    }

    private fun demoWav(name: String, seconds: Int): File {
        val rate = 22050
        val n = rate * seconds
        val f = File(core.dir, name).apply { parentFile?.mkdirs() }
        RandomAccessFile(f, "rw").use { out ->
            out.setLength(0)
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(n * 2)
            out.write(header.array())
            val pcm = java.nio.ByteBuffer.allocate(n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) pcm.putShort((Math.sin(2 * Math.PI * 330 * i / rate) * 6000 * (1 - i.toDouble() / n)).toInt().toShort())
            out.write(pcm.array())
        }
        return f
    }

    private fun addContact(name: String, address: String, host: String): Contact {
        val c = Contact(nextContact++, "key:$address@$host:4246", "$address@$host", name, null, true)
        contacts[c.id] = c
        return c
    }

    private fun newChat(name: String, group: Boolean, contactId: Int?, isSelf: Boolean = false): FakeChat {
        val c = FakeChat(nextChat++, name, group, contactId, isSelf = isSelf)
        chats[c.id] = c
        return c
    }

    private fun addMsg(
        chatId: Int, from: Int?, sender: String, text: String, time: Long, state: MessageState,
        error: String? = null, replyTo: Int? = null, kind: MessageKind = MessageKind.Text,
    ): Message {
        val m = Message(nextMsg++, chatId, from, sender, text, time, state, error, replyTo, kind)
        messages += m
        return m
    }


    @Synchronized
    override fun info() = AccountInfo(id, address, account, name, avatar, node, bio, muted, tag, chats.values.filter { !it.isMuted }.sumOf { c -> messages.count { it.chatId == c.id && it.state == MessageState.Incoming } })

    override fun addDeviceMessage(text: String) {
        // 演示数据里没有「设备消息」会话，不记录
    }

    override suspend fun setProfile(name: String, avatarPath: String?) {
        synchronized(this) {
            this.name = name
            avatar = avatarPath
        }
        emit(CoreEvent.ChatListChanged)
    }

    override suspend fun setBio(bio: String) {
        synchronized(this) { this.bio = bio }
    }

    override fun start() {
        if (connectivity != Connectivity.NotConnected) return
        connectivity = Connectivity.Connecting
        emit(CoreEvent.ConnectivityChanged)
        core.scope.launch {
            delay(1200)
            connectivity = Connectivity.Connected
            emit(CoreEvent.ConnectivityChanged)
        }
    }

    override fun stop() {
        connectivity = Connectivity.NotConnected
        emit(CoreEvent.ConnectivityChanged)
    }

    override fun connectivity() = connectivity

    private fun visible(c: FakeChat) = c.contactId == null || contacts[c.contactId]?.blocked != true

    private fun list(archived: Boolean, query: String?) = chats.values
        .filter { it.archived == archived && visible(it) && (query.isNullOrBlank() || it.name.contains(query, ignoreCase = true)) }
        .map { summary(it) }
        .sortedWith(compareByDescending<ChatSummary> { it.pinned }.thenByDescending { it.lastTime })

    @Synchronized
    override fun chats(query: String?): List<ChatSummary> =
        if (query.isNullOrBlank()) list(false, null) else list(false, query) + list(true, query)

    @Synchronized
    override fun archivedChats() = list(true, null)

    private fun summary(c: FakeChat): ChatSummary {
        val last = messages.lastOrNull { it.chatId == c.id }
        val unread = messages.count { it.chatId == c.id && it.state == MessageState.Incoming }
        val text = last?.text
        return ChatSummary(
            c.id, c.name, c.avatar ?: contacts[c.contactId]?.avatarPath, c.isGroup,
            text, last?.time ?: 0, last?.state?.takeIf { last.fromContact == null },
            unread, c.pinned, c.isMuted, c.draft, c.archived, c.isSelf, false, c.isRequest,
            last?.kind, last != null && last.fromContact == null, null,
            last?.takeIf { c.isGroup && it.fromContact != null }?.senderName,
        )
    }

    @Synchronized
    override fun chat(chatId: Int): Chat {
        val c = chats[chatId] ?: throw CoreException("Chat not found")
        return Chat(
            c.id, c.name, c.avatar ?: contacts[c.contactId]?.avatarPath, c.isGroup, c.contactId, c.contactId != null,
            c.isSelf, false, c.isRequest, !c.isGroup || c.isMember, c.archived, c.pinned, c.isMuted, c.mutedUntil, c.ephemeral,
            contacts[c.contactId]?.blocked == true, if (c.isGroup) c.members.size + 1 else 0,
        )
    }

    @Synchronized
    override fun messages(chatId: Int, before: Int?, limit: Int) = messages
        .filter { it.chatId == chatId && (before == null || it.id < before) }
        .takeLast(limit)

    @Synchronized
    override fun message(msgId: Int) = messages.firstOrNull { it.id == msgId } ?: throw CoreException("Message not found")

    override suspend fun sendText(chatId: Int, text: String, replyTo: Int?): Int {
        val m = synchronized(this) {
            chats[chatId]?.isRequest = false
            addMsg(chatId, null, name, text, System.currentTimeMillis(), MessageState.Pending, replyTo = replyTo)
        }
        deliver(m, text)
        return m.id
    }

    override suspend fun sendMedia(chatId: Int, kind: MessageKind, path: String, text: String?, replyTo: Int?, durationMs: Int?): Int {
        val src = File(path)
        val m = synchronized(this) {
            chats[chatId] ?: throw CoreException("Chat not found")
            val copy = File(core.dir, "blobs/${nextMsg}-${src.name}").apply { parentFile?.mkdirs() }
            src.copyTo(copy, overwrite = true)
            val (w, h) = imageSize(copy.path)
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(copy.extension.lowercase()) ?: "application/octet-stream"
            addMsg(chatId, null, name, text.orEmpty(), System.currentTimeMillis(), MessageState.Pending, replyTo = replyTo, kind = kind).also {
                replace(it.copy(file = copy.path, fileName = src.name, mime = mime, size = copy.length(), width = w, height = h, durationMs = durationMs?.toLong() ?: 0))
            }
        }
        if (m.size > maxAttachmentSize()) {
            setState(m.id, MessageState.Failed, "too-large")
            return m.id
        }
        deliver(m, m.text)
        return m.id
    }

    override suspend fun sendVcard(chatId: Int, contactId: Int): Int {
        val m = synchronized(this) {
            val c = contacts[contactId] ?: throw CoreException("Contact not found")
            addMsg(chatId, null, name, "", System.currentTimeMillis(), MessageState.Pending, kind = MessageKind.Vcard).also {
                replace(it.copy(vcard = Vcard(c.name, c.account, c.address)))
            }
        }
        deliver(m, "")
        return m.id
    }

    private fun imageSize(path: String): Pair<Int, Int> {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, o)
        return o.outWidth.coerceAtLeast(0) to o.outHeight.coerceAtLeast(0)
    }

    override suspend fun forward(msgIds: List<Int>, toChatId: Int): List<Int> {
        val sent = synchronized(this) {
            msgIds.mapNotNull { id -> messages.firstOrNull { it.id == id } }.map { src ->
                addMsg(toChatId, null, name, src.text, System.currentTimeMillis(), MessageState.Pending, kind = src.kind).also {
                    replace(src.copy(id = it.id, chatId = toChatId, fromContact = null, senderName = name, time = it.time, state = MessageState.Pending, error = null, replyTo = null, forwarded = true, reactions = emptyList(), saved = false))
                }
            }
        }
        sent.forEach { deliver(it, it.text) }
        return sent.map { it.id }
    }

    override suspend fun resend(msgIds: List<Int>) {
        msgIds.forEach { id ->
            setState(id, MessageState.Pending, null)
            core.scope.launch {
                delay(800)
                setState(id, MessageState.Delivered, null)
            }
        }
    }

    override fun deleteMessages(msgIds: List<Int>) {
        val chatIds = synchronized(this) {
            val ids = messages.filter { it.id in msgIds }.map { it.chatId }.toSet()
            messages.removeAll { it.id in msgIds }
            ids
        }
        chatIds.forEach { emit(CoreEvent.MessagesChanged(it)) }
        emit(CoreEvent.ChatListChanged)
    }

    private val reactionsBy = mutableMapOf<Int, MutableMap<Int?, String>>()

    override suspend fun react(msgId: Int, emoji: String) {
        val chatId = synchronized(this) {
            val m = messages.firstOrNull { it.id == msgId } ?: throw CoreException("Message not found")
            val by = reactionsBy.getOrPut(msgId) { m.reactions.filter { !it.mine }.associate { -1 to it.emoji }.toMutableMap<Int?, String>() }
            if (emoji.isEmpty()) by.remove(null) else by[null] = emoji
            val next = by.entries.groupBy { it.value }.map { (e, list) -> Reaction(e, list.size, list.any { it.key == null }) }
            replace(m.copy(reactions = next))
            m.chatId
        }
        emit(CoreEvent.MessagesChanged(chatId))
    }

    @Synchronized
    override fun messageReactions(msgId: Int): List<ReactionBy> {
        val m = messages.firstOrNull { it.id == msgId } ?: return emptyList()
        val by = reactionsBy[msgId] ?: return m.reactions.map { ReactionBy(if (it.mine) null else -1, if (it.mine) name else "RelayTester", it.emoji) }
        return by.map { (k, e) -> ReactionBy(k, if (k == null) name else "RelayTester", e) }
    }

    override fun setSaved(msgId: Int, saved: Boolean) {
        val saveChat = savedMessagesChat()
        synchronized(this) {
            val m = messages.firstOrNull { it.id == msgId } ?: throw CoreException("Message not found")
            replace(m.copy(saved = saved))
            if (saved) {
                val copy = addMsg(saveChat, m.fromContact, m.senderName, m.text, System.currentTimeMillis(), MessageState.Delivered, kind = m.kind)
                replace(m.copy(id = copy.id, chatId = saveChat, time = copy.time, replyTo = null, reactions = emptyList(), saved = true, originalId = m.id))
            } else {
                messages.removeAll { it.originalId == msgId }
            }
            emit(CoreEvent.MessagesChanged(m.chatId))
        }
        emit(CoreEvent.MessagesChanged(saveChat))
        emit(CoreEvent.ChatListChanged)
    }

    @Synchronized
    override fun savedMessagesChat() = (chats.values.firstOrNull { it.isSelf } ?: newChat("Saved Messages", false, null, isSelf = true)).id

    @Synchronized
    override fun searchMessages(chatId: Int?, query: String) = messages.filter {
        (chatId == null || it.chatId == chatId) && it.kind != MessageKind.System && it.text.contains(query, ignoreCase = true)
    }

    @Synchronized
    override fun mediaMessages(chatId: Int, kinds: List<MessageKind>) = messages.filter { it.chatId == chatId && it.kind in kinds }

    override fun encryptionInfo(chatId: Int) = "End-to-end encryption available.\n\nFingerprint (fake):\n" + hex(sha256("$address/$chatId")).chunked(4).joinToString(" ")

    private fun updateChat(chatId: Int, block: FakeChat.() -> Unit) {
        synchronized(this) { chats[chatId]?.block() ?: throw CoreException("Chat not found") }
        emit(CoreEvent.ChatListChanged)
        emit(CoreEvent.MessagesChanged(chatId))
    }

    override fun setPinned(chatId: Int, pinned: Boolean) = updateChat(chatId) { this.pinned = pinned }

    override fun mute(chatId: Int, until: Long?) = updateChat(chatId) {
        muted = true
        mutedUntil = until
    }

    override fun unmute(chatId: Int) = updateChat(chatId) {
        muted = false
        mutedUntil = null
    }

    override fun setArchived(chatId: Int, archived: Boolean) = updateChat(chatId) { this.archived = archived }

    override fun acceptChat(chatId: Int) = updateChat(chatId) { isRequest = false }

    override suspend fun setDisappearing(chatId: Int, seconds: Int) {
        synchronized(this) {
            chats[chatId]?.ephemeral = seconds
            addMsg(chatId, null, name, "", System.currentTimeMillis(), MessageState.Delivered, kind = MessageKind.System).also {
                replace(it.copy(system = SystemMsg.TimerChanged(seconds)))
            }
        }
        emit(CoreEvent.MessagesChanged(chatId))
    }

    override fun clearChat(chatId: Int) {
        synchronized(this) { messages.removeAll { it.chatId == chatId } }
        emit(CoreEvent.MessagesChanged(chatId))
        emit(CoreEvent.ChatListChanged)
    }

    override fun deleteChat(chatId: Int) {
        synchronized(this) {
            chats.remove(chatId)
            messages.removeAll { it.chatId == chatId }
        }
        emit(CoreEvent.ChatListChanged)
    }

    override fun markAllRead(archived: Boolean) {
        val ids = synchronized(this) { chats.values.filter { it.archived == archived }.map { it.id } }
        ids.forEach { markRead(it) }
    }

    private fun deliver(sent: Message, text: String) {
        val m = synchronized(this) { messages.firstOrNull { it.id == sent.id } } ?: sent
        val chatId = m.chatId
        emit(CoreEvent.MessagesChanged(chatId))
        emit(CoreEvent.ChatListChanged)
        if (synchronized(this) { chats[chatId]?.isSelf } == true) {
            setState(m.id, MessageState.Delivered, null)
            return
        }
        core.scope.launch {
            if (m.file != null && m.size > LARGE) transfer(m.id, m.size)
            delay(700)
            setState(m.id, MessageState.Sent, null)
            delay(1200)
            if (text.contains("fail", ignoreCase = true)) setState(m.id, MessageState.Failed, "sender-not-allowed")
            else setState(m.id, MessageState.Delivered, null)
            val chat = synchronized(this@FakeAccount) { chats[chatId] }
            if (chat?.contactId != null && contacts[chat.contactId]?.name == "RelayTester") {
                delay(1500)
                setState(m.id, MessageState.Read, null)
                val c = contacts[chat.contactId]!!
                val r = synchronized(this@FakeAccount) {
                    addMsg(chatId, c.id, c.name, "Got it: $text", System.currentTimeMillis(), MessageState.Incoming).also { r ->
                        if (m.file != null && m.size > LARGE) {
                            remoteFiles[r.id] = m.file
                            replace(r.copy(kind = m.kind, fileName = m.fileName, mime = m.mime, size = m.size, width = m.width, height = m.height, durationMs = m.durationMs, download = DownloadState.Available))
                        }
                    }
                }
                emit(CoreEvent.IncomingMessage(chatId, r.id))
                emit(CoreEvent.MessagesChanged(chatId))
                emit(CoreEvent.ChatListChanged)
            }
        }
        Logx.d("fake send chat=$chatId msg=${m.id}")
    }

    private fun setState(msgId: Int, state: MessageState, error: String?) {
        val chatId = synchronized(this) {
            val i = messages.indexOfFirst { it.id == msgId }
            if (i < 0) return
            messages[i] = messages[i].copy(state = state, error = error)
            messages[i].chatId
        }
        emit(CoreEvent.MessageStateChanged(chatId, msgId))
        emit(CoreEvent.ChatListChanged)
    }

    override fun markRead(chatId: Int) {
        val changed = synchronized(this) {
            var n = 0
            messages.replaceAll { if (it.chatId == chatId && it.state == MessageState.Incoming) { n++; it.copy(state = MessageState.Seen) } else it }
            n > 0
        }
        if (changed) emit(CoreEvent.ChatListChanged)
    }

    override fun setDraft(chatId: Int, text: String?) {
        synchronized(this) { chats[chatId]?.draft = text }
        emit(CoreEvent.ChatListChanged)
    }

    @Synchronized
    override fun inviteLink() =
        "https://i.tapetape.org/#a=${Uri.encode(account)}&n=${Uri.encode(name)}&t=$token"

    @Synchronized
    override fun resetInvite() {
        token = hex(ByteArray(16).also { random.nextBytes(it) })
    }

    override suspend fun acceptInvite(link: String): Int {
        val params = Uri.parse(link).fragment.orEmpty().split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i) to Uri.decode(it.substring(i + 1)) else null
        }.toMap()
        val acc = params["a"] ?: throw CoreException("Invalid invite link")
        if (acc == account) throw CoreException("This is your own invite link")
        delay(600)
        return createChat(acc, params["n"].orEmpty())
    }

    override fun createChat(account: String, name: String): Int {
        val chatId = synchronized(this) {
            val contact = contacts.values.firstOrNull { it.account == account } ?: run {
                val addr = account.removePrefix("key:").substringBefore(":4246")
                Contact(nextContact++, account, addr, name.ifBlank { addr.take(10) }, null, true).also { contacts[it.id] = it }
            }
            deletedContacts -= contact.id
            chats.values.firstOrNull { it.contactId == contact.id }?.id ?: newChat(contact.name, false, contact.id).id
        }
        emit(CoreEvent.ContactsChanged)
        emit(CoreEvent.ChatListChanged)
        return chatId
    }

    @Synchronized
    override fun contacts() = contacts.values.filter { !it.blocked && it.id !in deletedContacts }

    override fun deleteContact(contactId: Int) {
        synchronized(this) { deletedContacts += contactId }
        emit(CoreEvent.ContactsChanged)
    }

    @Synchronized
    override fun sharedChats(contactId: Int) = chats.values.filter { it.isGroup && contactId in it.members }
        .map { summary(it) }
        .sortedWith(compareByDescending<ChatSummary> { it.pinned }.thenByDescending { it.lastTime })

    override fun maxAttachmentSize() = 25L * 1024 * 1024

    override fun setFilesBackend(url: String) {}

    override fun download(msgId: Int) {
        val m = synchronized(this) { messages.firstOrNull { it.id == msgId } } ?: throw CoreException("Message not found")
        val path = remoteFiles[msgId] ?: return
        replace(m.copy(download = DownloadState.Downloading))
        emit(CoreEvent.MessagesChanged(m.chatId))
        core.scope.launch {
            transfer(msgId, m.size)
            val now = synchronized(this@FakeAccount) { messages.first { it.id == msgId } }
            replace(now.copy(file = path, download = null))
            emit(CoreEvent.MessagesChanged(m.chatId))
        }
    }

    private suspend fun transfer(msgId: Int, total: Long) {
        for (i in 1..5) {
            delay(400)
            emit(CoreEvent.TransferProgress(msgId, total * i / 5, total))
        }
    }
    @Synchronized
    override fun contact(contactId: Int) = contacts[contactId] ?: throw CoreException("Contact not found")

    override fun chatForContact(contactId: Int): Int {
        val c = contact(contactId)
        return createChat(c.account, c.name)
    }

    override fun setContactName(contactId: Int, name: String) {
        synchronized(this) {
            val c = contacts[contactId] ?: throw CoreException("Contact not found")
            contacts[contactId] = c.copy(name = name.ifBlank { c.name })
            chats.values.filter { it.contactId == contactId }.forEach { it.name = contacts[contactId]!!.name }
        }
        emit(CoreEvent.ContactsChanged)
        emit(CoreEvent.ChatListChanged)
    }

    private fun setBlocked(contactId: Int, blocked: Boolean) {
        synchronized(this) { contacts[contactId]?.let { contacts[contactId] = it.copy(blocked = blocked) } }
        emit(CoreEvent.ContactsChanged)
        emit(CoreEvent.ChatListChanged)
    }

    override fun blockContact(contactId: Int) = setBlocked(contactId, true)

    override fun unblockContact(contactId: Int) = setBlocked(contactId, false)

    @Synchronized
    override fun blockedContacts() = contacts.values.filter { it.blocked }

    private fun system(chatId: Int, from: Int?, sys: SystemMsg) {
        val sender = from?.let { contacts[it]?.name } ?: name
        addMsg(chatId, from, sender, "", System.currentTimeMillis(), MessageState.Delivered, kind = MessageKind.System).also { replace(it.copy(system = sys)) }
    }

    private fun groupChanged(chatId: Int) {
        emit(CoreEvent.MessagesChanged(chatId))
        emit(CoreEvent.ChatListChanged)
    }

    override suspend fun createGroup(name: String, avatarPath: String?, members: List<Int>): Int {
        val id = synchronized(this) {
            val g = newChat(name, true, null).apply { this.members += members; avatar = avatarPath }
            system(g.id, null, SystemMsg.GroupCreated(name))
            g.id
        }
        groupChanged(id)
        return id
    }

    @Synchronized
    override fun groupInfo(chatId: Int): GroupInfo {
        val c = chats[chatId]?.takeIf { it.isGroup } ?: throw CoreException("Not a group")
        val me = GroupMember(null, name, account, "$address@$node", avatar, true)
        val others = c.members.mapNotNull { contacts[it] }.map { GroupMember(it.id, it.name, it.account, it.address, it.avatarPath, true) }
        return GroupInfo(c.id, c.name, c.avatar, if (c.isMember) listOf(me) + others else others, c.isMember, c.isMember)
    }

    override suspend fun setGroupName(chatId: Int, name: String) {
        synchronized(this) {
            val old = chats[chatId]?.name.orEmpty()
            chats[chatId]?.name = name
            system(chatId, null, SystemMsg.GroupNameChanged(name, old))
        }
        groupChanged(chatId)
    }

    override suspend fun setGroupAvatar(chatId: Int, avatarPath: String?) {
        synchronized(this) {
            chats[chatId]?.avatar = avatarPath?.let { p -> File(core.dir, "blobs/group-$chatId-${System.nanoTime()}.jpg").also { File(p).copyTo(it, true) }.path }
            system(chatId, null, SystemMsg.GroupAvatarChanged)
        }
        groupChanged(chatId)
    }

    override suspend fun addGroupMembers(chatId: Int, contactIds: List<Int>) {
        synchronized(this) {
            val c = chats[chatId] ?: throw CoreException("Chat not found")
            contactIds.filter { it !in c.members }.forEach {
                c.members += it
                system(chatId, null, SystemMsg.MemberAdded(it, contacts[it]?.name.orEmpty()))
            }
        }
        groupChanged(chatId)
    }

    override suspend fun removeGroupMember(chatId: Int, contactId: Int) {
        synchronized(this) {
            chats[chatId]?.members?.remove(contactId)
            system(chatId, null, SystemMsg.MemberRemoved(contactId, contacts[contactId]?.name.orEmpty()))
        }
        groupChanged(chatId)
    }

    override suspend fun leaveGroup(chatId: Int) {
        synchronized(this) {
            chats[chatId]?.isMember = false
            system(chatId, null, SystemMsg.MemberLeft)
        }
        groupChanged(chatId)
    }

    @Synchronized
    override fun groupInviteLink(chatId: Int) = "https://i.tapetape.org/#g=${Uri.encode(chats[chatId]?.name.orEmpty())}&a=${Uri.encode(account)}&t=$token$chatId"

    override fun resetGroupInvite(chatId: Int) = resetInvite()

    @Synchronized
    override fun messageDeliveries(msgId: Int): List<Delivery> {
        val m = messages.firstOrNull { it.id == msgId } ?: return emptyList()
        val c = chats[m.chatId] ?: return emptyList()
        val ids = if (c.isGroup) c.members else listOfNotNull(c.contactId)
        return ids.mapNotNull { contacts[it] }.map { Delivery(it.id, it.name, m.state, m.error) }
    }

    @Synchronized
    override fun settings() = settings

    @Synchronized
    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    @Synchronized
    override fun countMessagesOlderThan(seconds: Int) = messages.count { it.time < System.currentTimeMillis() - seconds * 1000L }

    override fun setAccountMuted(muted: Boolean) {
        synchronized(this) { this.muted = muted }
        emit(CoreEvent.ChatListChanged)
    }

    override fun setAccountTag(tag: String) {
        synchronized(this) { this.tag = tag }
        emit(CoreEvent.ChatListChanged)
    }

    fun setMnemonic(m: String) {
        mnemonic = m
    }

    override fun mnemonic() = mnemonic

    override suspend fun exportBackup(destDir: String, passphrase: String?): String {
        delay(800)
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val f = File(destDir, "tapetape-backup-$address-$day.tapetapebak").apply { parentFile?.mkdirs(); writeText("TAPETAP1 fake $address") }
        return f.path
    }

    override suspend fun fetchOnce() = FetchResult(0, emptyList())

    override suspend fun sendPending() {}

    private val fakeBalances = mutableMapOf("196:OKB" to BigDecimal("0.1234"), "196:BEM" to BigDecimal("1500"), "56:BNB" to BigDecimal("0.05"), "56:USDT" to BigDecimal("25.5"), "56:USDC" to BigDecimal.ZERO)
    private val txChecks = mutableMapOf<String, Int>()
    private val pending = mutableListOf<WalletTx>()
    private var shopReady = false

    override fun walletAddress() = address

    override suspend fun balances(): List<Balance> {
        delay(600)
        return synchronized(this) { FAKE_TOKENS.map { Balance(it.id, fakeBalances[it.id]?.toPlainString()) } }
    }

    private fun estimate(tokenId: String, amount: String, count: Int = 1, to: String? = null): SendEstimate {
        val token = FAKE_TOKENS.firstOrNull { it.id == tokenId } ?: throw CoreException("unknown token")
        val native = FAKE_TOKENS.first { it.chainId == token.chainId && it.address == null }
        val fee = if (token.chainId == 56L) BigDecimal("0.00001260") else BigDecimal("0.00000420")
        val balance = synchronized(this) { fakeBalances[tokenId] ?: BigDecimal.ZERO }
        val nativeBalance = synchronized(this) { fakeBalances[native.id] ?: BigDecimal.ZERO }
        val max = if (token.address == null) (balance - fee).max(BigDecimal.ZERO) else balance
        val value = amount.toBigDecimalOrNull()
        val error = when {
            to != null && !Regex("^0x[0-9a-fA-F]{40}$").matches(to) -> "invalid-address"
            value == null || value.signum() <= 0 -> "invalid-amount"
            count < 1 -> "invalid-count"
            value > balance -> "insufficient-balance"
            fee + (if (token.address == null) value else BigDecimal.ZERO) > nativeBalance -> "insufficient-gas"
            else -> null
        }
        return SendEstimate(value?.toPlainString() ?: "0", fee.toPlainString(), native.symbol, balance.toPlainString(), max.toPlainString(), error)
    }

    override suspend fun estimateSend(tokenId: String, to: String, amount: String): SendEstimate {
        delay(400)
        return estimate(tokenId, amount, to = to)
    }

    private fun checkFee(e: SendEstimate, confirmedFee: String?) {
        e.error?.let { throw CoreException(it) }
        val seen = confirmedFee?.let { it.toBigDecimalOrNull() ?: throw CoreException("invalid-fee") } ?: return
        if (BigDecimal(e.fee) > seen * BigDecimal("1.5")) throw CoreException("fee-changed")
    }

    // 演示版：网页钱包只给固定的假结果，不签任何东西
    override suspend fun dappRpc(chainId: Long, origin: String, method: String, paramsJson: String) = if (method == "eth_chainId") "\"0x${chainId.toString(16)}\"" else "null"
    override suspend fun dappCheckTx(chainId: Long, origin: String, txJson: String) = DappTx(chainId, "X Layer", "0x0000000000000000000000000000000000000000", false, "0", "OKB", 0, null, null, null, null, null, null, null, false, false, null, false, false, false, false, "21000", "0.000001", "0", "", "insufficient-gas")
    override suspend fun dappSendTx(chainId: Long, txJson: String, confirmedFee: String, ticket: String): String = throw IllegalStateException("demo")
    override fun dappMessage(raw: String) = DappMessage("0x" + raw.toByteArray().joinToString("") { "%02x".format(it) }, raw, false)
    override fun dappPersonalSign(messageHex: String): String = throw IllegalStateException("demo")
    override fun dappTypedData(chainId: Long, json: String, version: Int) = DappTypedData("demo", "", chainId.toString(), "Demo", json, false, null)
    override fun dappSignTypedData(chainId: Long, json: String, version: Int): String = throw IllegalStateException("demo")

    override suspend fun sendToken(tokenId: String, to: String, amount: String, confirmedFee: String?): String {
        checkFee(estimate(tokenId, amount, to = to), confirmedFee)
        delay(800)
        val hash = "0x" + hex(ByteArray(32).also { random.nextBytes(it) })
        val token = FAKE_TOKENS.first { it.id == tokenId }
        synchronized(this) {
            fakeBalances[tokenId] = (fakeBalances[tokenId] ?: BigDecimal.ZERO) - BigDecimal(amount)
            pending += WalletTx(token.chainId, tokenId, to, amount, hash, System.currentTimeMillis())
        }
        return hash
    }

    override fun pendingSends(): List<WalletTx> = synchronized(this) { pending.toList() }

    override suspend fun txStatus(chainId: Long, txHash: String): TxStatus {
        delay(300)
        val n = synchronized(this) { (txChecks[txHash] ?: 0).also { txChecks[txHash] = it + 1 } }
        val status = if (n < 4) TxStatus.Pending else TxStatus.Confirmed
        if (status == TxStatus.Confirmed) synchronized(this) { pending.removeAll { it.txHash == txHash } }
        return status
    }

    override suspend fun estimatePacket(tokenId: String, amount: String, count: Int): SendEstimate {
        delay(400)
        return estimate(tokenId, amount, count)
    }

    override suspend fun estimateTransfer(chatId: Int, tokenId: String, amount: String): SendEstimate {
        delay(400)
        return estimate(tokenId, amount, 1)
    }

    private fun packetStatus(state: PacketState, total: Int, records: List<ClaimRecord> = emptyList(), mine: Boolean = false, kind: PacketKind = PacketKind.RedPacket, created: Long = System.currentTimeMillis()): PacketStatus {
        val sum = records.fold(BigDecimal.ZERO) { a, r -> a + BigDecimal(r.amount) }
        val my = records.firstOrNull { it.me }?.amount
        return PacketStatus(
            state, records.size, total, sum.toPlainString(), my,
            canClaim = state == PacketState.Open && my == null && !(mine && kind == PacketKind.Transfer),
            canRefund = state == PacketState.Expired && mine,
            expiresAt = created + PACKET_TTL, records = records, checkedAt = System.currentTimeMillis(),
        )
    }

    private fun addPacket(chatId: Int, from: Int?, sender: String, kind: PacketKind, tokenId: String, amount: String, count: Int, text: String, state: PacketState, created: Long, records: List<ClaimRecord> = emptyList()): Message {
        val token = FAKE_TOKENS.first { it.id == tokenId }
        val mine = from == null
        val packet = Packet(
            kind, hex(ByteArray(8).also { random.nextBytes(it) }), token.chainId, tokenId, token.symbol, amount, count, text,
            "0x" + hex(ByteArray(32).also { random.nextBytes(it) }), if (mine) address else contacts[from]?.address?.substringBefore("@") ?: "", mine,
            packetStatus(state, count, records, mine, kind, created),
        )
        val body = if (kind == PacketKind.RedPacket) "🧧 " + text.ifBlank { "$amount ${token.symbol}" } else "💰 $amount ${token.symbol}"
        val msgKind = if (kind == PacketKind.RedPacket) MessageKind.RedPacket else MessageKind.Transfer
        val msgState = when {
            !mine -> MessageState.Seen
            state == PacketState.Creating -> MessageState.Pending
            state == PacketState.Failed -> MessageState.Failed
            else -> MessageState.Delivered
        }
        return addMsg(chatId, from, sender, body, created, msgState, kind = msgKind).also { replace(it.copy(packet = packet)) }
    }

    fun seedPackets() {
        val now = System.currentTimeMillis()
        val tester = contacts.values.first { it.name == "RelayTester" }
        val t = chats.values.first { it.contactId == tester.id }
        val g = chats.values.first { it.name == "Relay Group" }
        addPacket(t.id, tester.id, tester.name, PacketKind.Transfer, "56:USDT", "5", 1, "Lunch money", PacketState.Open, now - 3_600_000)
        addPacket(t.id, null, "", PacketKind.Transfer, "56:BNB", "0.01", 1, "", PacketState.Finished, now - 2 * 86_400_000L,
            listOf(ClaimRecord(tester.address.substringBefore("@"), "0.01", tester.id, false)))
        addPacket(g.id, tester.id, tester.name, PacketKind.RedPacket, "56:USDT", "10", 3, "Happy weekend!", PacketState.Open, now - 1_800_000,
            listOf(ClaimRecord("0x9f1e4c2d7a3b5e6f8091a2b3c4d5e6f708192a3b", "4.2", contacts.values.firstOrNull { it.name == "Alice" }?.id, false)))
        addPacket(g.id, null, "", PacketKind.RedPacket, "56:BNB", "0.02", 2, "", PacketState.Expired, now - 8 * 86_400_000L,
            listOf(ClaimRecord(tester.address.substringBefore("@"), "0.012", tester.id, false)))
        messages.sortBy { it.time }
    }

    private fun updatePacket(msgId: Int, change: (Packet) -> Packet): Message {
        val m = synchronized(this) {
            val old = messages.first { it.id == msgId }
            old.copy(packet = change(old.packet!!)).also { replace(it) }
        }
        emit(CoreEvent.MessagesChanged(m.chatId))
        emit(CoreEvent.ChatListChanged)
        return m
    }

    private suspend fun sendPacket(chatId: Int, kind: PacketKind, tokenId: String, amount: String, count: Int, text: String, confirmedFee: String?): Int {
        checkFee(estimate(tokenId, amount, count), confirmedFee)
        val m = synchronized(this) { addPacket(chatId, null, "", kind, tokenId, amount, count, text, PacketState.Creating, System.currentTimeMillis()) }
        emit(CoreEvent.MessagesChanged(chatId))
        emit(CoreEvent.ChatListChanged)
        delay(2500)
        synchronized(this) { fakeBalances[tokenId] = (fakeBalances[tokenId] ?: BigDecimal.ZERO) - BigDecimal(amount) }
        updatePacket(m.id) { it.copy(status = it.status.copy(state = PacketState.Open, canClaim = kind == PacketKind.RedPacket)) }
        setState(m.id, MessageState.Delivered, null)
        return m.id
    }

    override suspend fun sendRedPacket(chatId: Int, tokenId: String, amount: String, count: Int, greeting: String, confirmedFee: String?) =
        sendPacket(chatId, PacketKind.RedPacket, tokenId, amount, count, greeting, confirmedFee)

    override suspend fun sendTransfer(chatId: Int, tokenId: String, amount: String, note: String, confirmedFee: String?) =
        sendPacket(chatId, PacketKind.Transfer, tokenId, amount, 1, note, confirmedFee)

    override suspend fun packetStatus(msgId: Int): PacketStatus {
        delay(500)
        val m = synchronized(this) { messages.firstOrNull { it.id == msgId } } ?: throw CoreException("Message not found")
        val p = m.packet ?: throw CoreException("Not a packet")
        return updatePacket(msgId) { it.copy(status = p.status.copy(checkedAt = System.currentTimeMillis())) }.packet!!.status
    }

    override suspend fun claimPacket(msgId: Int): String {
        val m = synchronized(this) { messages.first { it.id == msgId } }
        val p = m.packet ?: throw CoreException("Not a packet")
        if (!p.status.canClaim) throw CoreException(if (p.status.myAmount != null) "already-claimed" else "not-claimable")
        delay(2500)
        val left = BigDecimal(p.amount) - BigDecimal(p.status.claimedAmount)
        val remaining = p.count - p.status.claimedCount
        val got = if (remaining <= 1) left else left.divide(BigDecimal(remaining), 5, java.math.RoundingMode.DOWN)
        val records = p.status.records + ClaimRecord(address, got.toPlainString(), null, true)
        val finished = records.size >= p.count
        updatePacket(msgId) { it.copy(status = packetStatus(if (finished) PacketState.Finished else PacketState.Open, p.count, records, p.mine, p.kind, m.time)) }
        synchronized(this) { fakeBalances[p.tokenId!!] = (fakeBalances[p.tokenId] ?: BigDecimal.ZERO) + got }
        if (p.kind == PacketKind.RedPacket) {
            synchronized(this) { system(m.chatId, null, SystemMsg.PacketClaimed(null, m.fromContact, msgId)) }
            emit(CoreEvent.MessagesChanged(m.chatId))
        }
        return got.toPlainString()
    }

    override suspend fun refundPacket(msgId: Int): String {
        val p = synchronized(this) { messages.first { it.id == msgId }.packet } ?: throw CoreException("Not a packet")
        if (!p.status.canRefund) throw CoreException("not-refundable")
        delay(2000)
        updatePacket(msgId) { it.copy(status = it.status.copy(state = PacketState.Refunded, canRefund = false)) }
        return "0x" + hex(ByteArray(32).also { random.nextBytes(it) })
    }

    override suspend fun shopInfo(): ShopInfo {
        delay(800)
        return synchronized(this) {
            if (shopReady) ShopInfo(address, (fakeBalances["196:OKB"] ?: BigDecimal.ZERO).toPlainString(), "0", true, "123.2.265.tape", "https://123-2-265-open.tapekit.org/", "https://123-2-265.tapekit.org/")
            else ShopInfo(address, (fakeBalances["196:OKB"] ?: BigDecimal.ZERO).toPlainString(), "0.0835", false, null, null, null)
        }
    }

    override suspend fun shopOpen(): ShopInfo {
        val info = shopInfo()
        if (!info.ready && BigDecimal(info.balance) < BigDecimal(info.openCost)) throw CoreException("insufficient-balance")
        delay(3000)
        synchronized(this) { shopReady = true }
        return shopInfo()
    }

    override suspend fun shopPublish(input: ProductInput): Product {
        delay(1500)
        val id = hex(ByteArray(4).also { random.nextBytes(it) })
        return Product(id, input.description, input.originalPrice, input.discountPrice, input.images.indices.map { "/img/$id-$it.jpg" }, System.currentTimeMillis() / 1000)
    }

    override suspend fun shopRemove(productId: String) {
        delay(800)
    }
}
