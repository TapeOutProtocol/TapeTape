package org.tapetape.v2.core

data class AccountInfo(
    val id: Int,
    val address: String,
    val account: String,
    val name: String,
    val avatarPath: String?,
    val node: String,
    val bio: String = "",
    val muted: Boolean = false,
    val tag: String = "",
    val unread: Int = 0,
)

data class Contact(
    val id: Int,
    val account: String,
    val address: String,
    val name: String,
    val avatarPath: String?,
    val verified: Boolean,
    val bio: String = "",
    val lastSeen: Long? = null,
    val blocked: Boolean = false,
)

data class ChatSummary(
    val id: Int,
    val name: String,
    val avatarPath: String?,
    val isGroup: Boolean,
    val lastText: String?,
    val lastTime: Long,
    val lastState: MessageState?,
    val unread: Int,
    val pinned: Boolean,
    val muted: Boolean,
    val draft: String?,
    val archived: Boolean = false,
    val isSelf: Boolean = false,
    val isDevice: Boolean = false,
    val isRequest: Boolean = false,
    val lastKind: MessageKind? = null,
    val lastFromMe: Boolean = false,
    val lastSeen: Long? = null,
    val lastSender: String? = null,
)

data class Chat(
    val id: Int,
    val name: String,
    val avatarPath: String?,
    val isGroup: Boolean,
    val contactId: Int?,
    val verified: Boolean,
    val isSelf: Boolean = false,
    val isDevice: Boolean = false,
    val isRequest: Boolean = false,
    val canSend: Boolean = true,
    val archived: Boolean = false,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val mutedUntil: Long? = null,
    val ephemeralSeconds: Int = 0,
    val blocked: Boolean = false,
    val memberCount: Int = 0,
)

data class Message(
    val id: Int,
    val chatId: Int,
    val fromContact: Int?,
    val senderName: String,
    val text: String,
    val time: Long,
    val state: MessageState,
    val error: String?,
    val replyTo: Int?,
    val kind: MessageKind,
    val file: String? = null,
    val fileName: String? = null,
    val mime: String? = null,
    val size: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val vcard: Vcard? = null,
    val reactions: List<Reaction> = emptyList(),
    val forwarded: Boolean = false,
    val saved: Boolean = false,
    val originalId: Int? = null,
    val expiresAt: Long? = null,
    val system: SystemMsg? = null,
    val download: DownloadState? = null,
    val packet: Packet? = null,
) {
    val timerChanged get() = (system as? SystemMsg.TimerChanged)?.seconds
    val outgoing get() = fromContact == null && kind != MessageKind.System && state != MessageState.Incoming && state != MessageState.Seen
    val isMedia get() = kind == MessageKind.Image || kind == MessageKind.Gif || kind == MessageKind.Video
}

data class Token(val id: String, val chainId: Long, val chainName: String, val symbol: String, val address: String?, val decimals: Int, val packets: Boolean)

data class Balance(val tokenId: String, val amount: String?)

data class SendEstimate(val amount: String, val fee: String, val feeSymbol: String, val balance: String, val max: String, val error: String?)

/** 广场网页钱包：网页要发的交易的确认信息 */
data class DappTx(
    val chainId: Long, val chainName: String, val to: String, val toIsContract: Boolean, val value: String, val symbol: String,
    val dataLen: Int, val selector: String?,
    /** 识别出的操作（approve / transfer / approval-for-all …），没识别出来为 null */
    val action: String?, val counterparty: String?, val from: String?, val token: String?,
    val tokenAmount: String?, val tokenSymbol: String?, val tokenKnown: Boolean, val unlimited: Boolean,
    /** Permit2 授权的到期时间（UTC） */
    val expiry: String?,
    /** NFT（tokenAmount 是编号）；rawAmount = 读不到小数位；extraData = 调用数据比需要的多 */
    val nft: Boolean, val rawAmount: Boolean, val extraData: Boolean, val danger: Boolean,
    val gas: String, val fee: String, val balance: String,
    /** 发送时原样交回 dappSendTx：绑定这次确认的链号、交易内容和手续费 */
    val ticket: String,
    val error: String?,
)

/** 广场网页钱包：EIP-712 签名请求的摘要 */
data class DappTypedData(
    val domainName: String, val verifyingContract: String, val chainId: String, val primaryType: String,
    /** 按类型定义列出的实际签名字段 */
    val fields: String,
    /** 可能让对方直接动用资产的签名（Permit、授权转账、代执行、挂单…） */
    val danger: Boolean,
    /** 签名里的被授权方 / 收款方 */
    val counterparty: String?,
)

/** personal_sign 的预览：hex 是实际签的字节；blind = 看不懂的数据（很可能是哈希） */
data class DappMessage(val hex: String, val text: String, val blind: Boolean)

enum class TxStatus { Pending, Confirmed, Failed, NotFound }

/** dapp = 广场网页发起的合约调用（amount 只是附带的原生币，不是提现） */
data class WalletTx(val chainId: Long, val tokenId: String, val to: String, val amount: String, val txHash: String, val createdAt: Long, val dapp: Boolean = false)

enum class PacketKind { RedPacket, Transfer }

enum class PacketState { Creating, Open, Finished, Expired, Refunded, Failed }

data class ClaimRecord(val address: String, val amount: String, val contactId: Int?, val me: Boolean)

data class PacketStatus(
    val state: PacketState,
    val claimedCount: Int,
    val totalCount: Int,
    val claimedAmount: String,
    val myAmount: String?,
    val canClaim: Boolean,
    val canRefund: Boolean,
    val expiresAt: Long?,
    val records: List<ClaimRecord>,
    val checkedAt: Long,
)

data class Packet(
    val kind: PacketKind,
    val packetId: String,
    val chainId: Long,
    val tokenId: String?,
    val symbol: String,
    val amount: String,
    val count: Int,
    val text: String,
    val tx: String,
    val creator: String,
    val mine: Boolean,
    val status: PacketStatus,
)

data class ShopInfo(val address: String, val balance: String, val openCost: String, val ready: Boolean, val name: String?, val appUrl: String?, val shareUrl: String?)

data class ProductInput(val description: String, val originalPrice: Long, val discountPrice: Long, val images: List<String>)

data class Product(val id: String, val description: String, val originalPrice: Long, val discountPrice: Long, val images: List<String>, val createdAt: Long)

data class Reaction(val emoji: String, val count: Int, val mine: Boolean)

data class ReactionBy(val contactId: Int?, val name: String, val emoji: String)

data class Vcard(val name: String, val account: String, val address: String)

enum class MessageKind { Text, System, Image, Gif, Sticker, Video, Voice, Audio, File, Vcard, RedPacket, Transfer }

enum class DownloadState { Available, Downloading, Failed }

enum class MessageState { Pending, Sent, Delivered, Read, Failed, Incoming, Seen }

enum class Connectivity { NotConnected, Connecting, Connected }

enum class ProxyKind { Http, Https, Socks5, Shadowsocks }

data class ProxyInfo(val url: String, val kind: ProxyKind, val host: String, val port: Int)

data class ProxySettings(val enabled: Boolean, val active: String?, val proxies: List<ProxyInfo>)

sealed interface SystemMsg {
    data class TimerChanged(val seconds: Int) : SystemMsg
    data class GroupCreated(val name: String) : SystemMsg
    data class GroupNameChanged(val name: String, val oldName: String) : SystemMsg
    data object GroupAvatarChanged : SystemMsg
    data class MemberAdded(val contactId: Int?, val name: String) : SystemMsg
    data class MemberRemoved(val contactId: Int?, val name: String) : SystemMsg
    data object MemberLeft : SystemMsg
    data class PacketClaimed(val claimer: Int?, val owner: Int?, val packetMsgId: Int?) : SystemMsg
}

data class GroupMember(val contactId: Int?, val name: String, val account: String, val address: String, val avatarPath: String?, val admin: Boolean)

data class GroupInfo(val chatId: Int, val name: String, val avatarPath: String?, val members: List<GroupMember>, val isMember: Boolean, val isAdmin: Boolean)

data class Delivery(val contactId: Int, val name: String, val state: MessageState, val error: String?)

enum class MediaQuality { Balanced, Worse }

enum class AutoDownload { All, Kib160, Kib640, Mib5, Mib25 }

data class Settings(
    val readReceipts: Boolean = true,
    val mediaQuality: MediaQuality = MediaQuality.Balanced,
    val autoDownload: AutoDownload = AutoDownload.All,
    val deleteDeviceAfter: Int? = null,
    val mentions: Boolean = true,
)

data class Notification(
    val accountId: Int,
    val chatId: Int,
    val msgId: Int,
    val chatName: String,
    val senderName: String,
    val isGroup: Boolean,
    val kind: MessageKind,
    val text: String,
    val reaction: String?,
    val muted: Boolean,
    val mention: Boolean,
)

data class FetchResult(val newMessages: Int, val notifications: List<Notification>)

enum class BackupProtection { Passphrase, RecoveryPhrase }

sealed interface CoreEvent {
    data object ChatListChanged : CoreEvent
    data class MessagesChanged(val chatId: Int) : CoreEvent
    data class IncomingMessage(val chatId: Int, val msgId: Int) : CoreEvent
    data class MessageStateChanged(val chatId: Int, val msgId: Int) : CoreEvent
    data class IncomingReaction(val chatId: Int, val msgId: Int) : CoreEvent
    data object ContactsChanged : CoreEvent
    data object ConnectivityChanged : CoreEvent
    data class Error(val message: String) : CoreEvent
    data class Notify(val notification: Notification) : CoreEvent
    data class TransferProgress(val msgId: Int, val done: Long, val total: Long) : CoreEvent
}

class CoreException(message: String) : Exception(message)

typealias EventSink = (accountId: Int, event: CoreEvent) -> Unit

interface TapeTapeCore {
    fun accounts(): List<AccountInfo>
    fun selectedAccount(): Int?
    fun selectAccount(id: Int)
    suspend fun nodes(): List<String>
    fun generateMnemonic(): String
    suspend fun addAccount(mnemonic: String, node: String?): Int
    fun removeAccount(id: Int)
    fun account(id: Int): TapeTapeAccount
    fun addressForMnemonic(mnemonic: String): String
    fun walletTokens(): List<Token>
    fun moveAccountToTop(id: Int)
    fun backupProtection(path: String): BackupProtection
    suspend fun importBackup(path: String, secret: String): Int
    suspend fun fetchAll(): FetchResult
    fun proxySettings(): ProxySettings
    fun parseProxy(link: String): ProxyInfo
    fun addProxy(link: String): ProxySettings
    fun selectProxy(url: String): ProxySettings
    fun removeProxy(url: String): ProxySettings
    fun setProxyEnabled(enabled: Boolean): ProxySettings
}

interface TapeTapeAccount {
    fun info(): AccountInfo
    suspend fun setProfile(name: String, avatarPath: String?)
    /** 在「设备消息」里写一条本机生成的消息（新建账号的欢迎语） */
    fun addDeviceMessage(text: String)
    fun start()
    fun stop()
    fun connectivity(): Connectivity

    fun chats(query: String?): List<ChatSummary>
    fun chat(chatId: Int): Chat
    fun messages(chatId: Int, before: Int?, limit: Int): List<Message>
    suspend fun sendText(chatId: Int, text: String, replyTo: Int?): Int
    fun markRead(chatId: Int)
    fun setDraft(chatId: Int, text: String?)

    fun inviteLink(): String
    fun resetInvite()
    suspend fun acceptInvite(link: String): Int
    fun contacts(): List<Contact>

    suspend fun setBio(bio: String)

    fun message(msgId: Int): Message
    suspend fun sendMedia(chatId: Int, kind: MessageKind, path: String, text: String?, replyTo: Int?, durationMs: Int?): Int
    suspend fun sendVcard(chatId: Int, contactId: Int): Int
    suspend fun react(msgId: Int, emoji: String)
    fun messageReactions(msgId: Int): List<ReactionBy>
    suspend fun forward(msgIds: List<Int>, toChatId: Int): List<Int>
    fun deleteMessages(msgIds: List<Int>)
    suspend fun resend(msgIds: List<Int>)
    fun setSaved(msgId: Int, saved: Boolean)
    fun savedMessagesChat(): Int

    fun setPinned(chatId: Int, pinned: Boolean)
    fun mute(chatId: Int, until: Long?)
    fun unmute(chatId: Int)
    fun setArchived(chatId: Int, archived: Boolean)
    fun deleteChat(chatId: Int)
    fun clearChat(chatId: Int)
    fun archivedChats(): List<ChatSummary>
    fun markAllRead(archived: Boolean)
    fun acceptChat(chatId: Int)
    suspend fun setDisappearing(chatId: Int, seconds: Int)
    fun searchMessages(chatId: Int?, query: String): List<Message>
    fun mediaMessages(chatId: Int, kinds: List<MessageKind>): List<Message>
    fun encryptionInfo(chatId: Int): String

    fun contact(contactId: Int): Contact
    fun createChat(account: String, name: String): Int
    fun chatForContact(contactId: Int): Int
    fun setContactName(contactId: Int, name: String)
    fun blockContact(contactId: Int)
    fun unblockContact(contactId: Int)
    fun blockedContacts(): List<Contact>

    suspend fun createGroup(name: String, avatarPath: String?, members: List<Int>): Int
    fun groupInfo(chatId: Int): GroupInfo
    fun sharedChats(contactId: Int): List<ChatSummary>
    fun deleteContact(contactId: Int)
    fun maxAttachmentSize(): Long
    fun setFilesBackend(url: String)
    fun download(msgId: Int)
    suspend fun setGroupName(chatId: Int, name: String)
    suspend fun setGroupAvatar(chatId: Int, avatarPath: String?)
    suspend fun addGroupMembers(chatId: Int, contactIds: List<Int>)
    suspend fun removeGroupMember(chatId: Int, contactId: Int)
    suspend fun leaveGroup(chatId: Int)
    fun groupInviteLink(chatId: Int): String
    fun resetGroupInvite(chatId: Int)
    fun messageDeliveries(msgId: Int): List<Delivery>

    fun settings(): Settings
    fun setSettings(settings: Settings)
    fun countMessagesOlderThan(seconds: Int): Int
    fun setAccountMuted(muted: Boolean)
    fun setAccountTag(tag: String)

    fun mnemonic(): String
    suspend fun exportBackup(destDir: String, passphrase: String?): String
    suspend fun fetchOnce(): FetchResult
    suspend fun sendPending()

    fun walletAddress(): String
    suspend fun balances(): List<Balance>
    suspend fun estimateSend(tokenId: String, to: String, amount: String): SendEstimate
    suspend fun sendToken(tokenId: String, to: String, amount: String, confirmedFee: String?): String

    // 广场网页钱包（见 core 的 dapp.rs）
    suspend fun dappRpc(chainId: Long, origin: String, method: String, paramsJson: String): String
    suspend fun dappCheckTx(chainId: Long, origin: String, txJson: String): DappTx
    suspend fun dappSendTx(chainId: Long, txJson: String, confirmedFee: String, ticket: String): String
    fun dappMessage(raw: String): DappMessage
    fun dappPersonalSign(messageHex: String): String
    fun dappTypedData(chainId: Long, json: String, version: Int): DappTypedData
    fun dappSignTypedData(chainId: Long, json: String, version: Int): String
    fun pendingSends(): List<WalletTx>
    suspend fun txStatus(chainId: Long, txHash: String): TxStatus
    suspend fun estimatePacket(tokenId: String, amount: String, count: Int): SendEstimate
    suspend fun estimateTransfer(chatId: Int, tokenId: String, amount: String): SendEstimate
    suspend fun sendRedPacket(chatId: Int, tokenId: String, amount: String, count: Int, greeting: String, confirmedFee: String?): Int
    suspend fun sendTransfer(chatId: Int, tokenId: String, amount: String, note: String, confirmedFee: String?): Int
    suspend fun packetStatus(msgId: Int): PacketStatus
    suspend fun claimPacket(msgId: Int): String
    suspend fun refundPacket(msgId: Int): String
    suspend fun shopInfo(): ShopInfo
    suspend fun shopOpen(): ShopInfo
    suspend fun shopPublish(input: ProductInput): Product
    suspend fun shopRemove(productId: String)
}
