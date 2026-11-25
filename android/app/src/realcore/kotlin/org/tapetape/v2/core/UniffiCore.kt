package org.tapetape.v2.core

import uniffi.tapetape_core.Event
import uniffi.tapetape_core.Token as RToken
import uniffi.tapetape_core.Balance as RBalance
import uniffi.tapetape_core.SendEstimate as RSendEstimate
import uniffi.tapetape_core.PacketStatus as RPacketStatus
import uniffi.tapetape_core.Packet as RPacket
import uniffi.tapetape_core.ShopInfo as RShopInfo
import uniffi.tapetape_core.ProductInput as RProductInput
import uniffi.tapetape_core.Product as RProduct
import uniffi.tapetape_core.ProxyInfo as RProxyInfo
import uniffi.tapetape_core.ProxySettings as RProxySettings
import uniffi.tapetape_core.EventListener
import uniffi.tapetape_core.MediaKind
import uniffi.tapetape_core.SystemMessage
import uniffi.tapetape_core.Notification as RNotification
import uniffi.tapetape_core.FetchResult as RFetchResult
import uniffi.tapetape_core.Settings as RSettings
import uniffi.tapetape_core.MediaQuality as RMediaQuality
import uniffi.tapetape_core.AutoDownload as RAutoDownload
import uniffi.tapetape_core.BackupProtection as RBackupProtection
import uniffi.tapetape_core.Account as RAccount
import uniffi.tapetape_core.AccountInfo as RAccountInfo
import uniffi.tapetape_core.Chat as RChat
import uniffi.tapetape_core.ChatSummary as RChatSummary
import uniffi.tapetape_core.Contact as RContact
import uniffi.tapetape_core.Core as RCore
import uniffi.tapetape_core.CoreException as RCoreException
import uniffi.tapetape_core.Connectivity as RConnectivity
import uniffi.tapetape_core.Message as RMessage
import uniffi.tapetape_core.MessageKind as RMessageKind
import uniffi.tapetape_core.MessageState as RMessageState

object CoreFactory {
    const val REAL = true
    fun create(dataDir: String, sink: EventSink): TapeTapeCore = UniffiCore(dataDir, sink)
}

private inline fun <T> call(block: () -> T): T = try {
    block()
} catch (e: RCoreException) {
    throw CoreException(e.message.orEmpty())
}

private fun RAccountInfo.map() = AccountInfo(id.toInt(), address, account, name, avatarPath, node, bio, muted, tag, unread.toInt())

private fun RMessageState.map() = MessageState.entries[ordinal]

private fun RMessageKind.map() = MessageKind.entries[ordinal]

private fun MessageKind.raw() = RMessageKind.entries[ordinal]

private fun RContact.map() = Contact(id.toInt(), account, address, name, avatarPath, verified, bio, lastSeen, blocked)

private fun RChatSummary.map() = ChatSummary(
    id.toInt(), name, avatarPath, isGroup, lastText, lastTime, lastState?.map(), unread.toInt(), pinned, muted, draft,
    archived, isSelf, isDevice, isRequest, lastKind?.map(), lastFromMe, lastSeen, lastSender,
)

private fun RChat.map() = Chat(
    id.toInt(), name, avatarPath, isGroup, contactId?.toInt(), verified, isSelf, isDevice, isRequest, canSend,
    archived, pinned, muted, mutedUntil, ephemeralSeconds.toInt(), blocked, memberCount.toInt(),
)

private fun RMessage.map() = Message(
    id.toInt(), chatId.toInt(), fromContact?.toInt(), senderName, text, time, state.map(), error, replyTo?.toInt(), kind.map(),
    filePath, fileName, mime, size?.toLong() ?: 0, width?.toInt() ?: 0, height?.toInt() ?: 0, durationMs?.toLong() ?: 0,
    vcard?.let { Vcard(it.name, it.account, it.address) },
    reactions.map { Reaction(it.emoji, it.count.toInt(), it.mine) },
    forwarded, saved, originalId?.toInt(), expiresAt,
    system?.map(),
    download?.let { DownloadState.entries[it.ordinal] },
    packet?.map(),
)

private fun RToken.map() = Token(id, chainId.toLong(), chainName, symbol, address, decimals.toInt(), packets)

private fun RBalance.map() = Balance(tokenId, amount)

private fun RSendEstimate.map() = SendEstimate(amount, fee, feeSymbol, balance, max, error)

private fun RPacketStatus.map() = PacketStatus(
    PacketState.entries[state.ordinal], claimedCount.toInt(), totalCount.toInt(), claimedAmount, myAmount, canClaim, canRefund, expiresAt,
    records.map { ClaimRecord(it.address, it.amount, it.contactId?.toInt(), it.me) }, checkedAt,
)

private fun RPacket.map() = Packet(
    PacketKind.entries[kind.ordinal], packetId, chainId.toLong(), tokenId, symbol, amount, count.toInt(), text, tx, creator, mine, status.map(),
)

private fun RShopInfo.map() = ShopInfo(address, balance, openCost, ready, name, appUrl, shareUrl)

private fun RProxyInfo.map() = ProxyInfo(url, ProxyKind.entries[kind.ordinal], host, port.toInt())

private fun RProxySettings.map() = ProxySettings(enabled, active, proxies.map { it.map() })

private fun RProduct.map() = Product(id, description, originalPrice.toLong(), discountPrice.toLong(), images, createdAt)

private fun SystemMessage.map(): SystemMsg? = when (this) {
    is SystemMessage.TimerChanged -> SystemMsg.TimerChanged(seconds.toInt())
    is SystemMessage.GroupCreated -> SystemMsg.GroupCreated(name)
    is SystemMessage.GroupNameChanged -> SystemMsg.GroupNameChanged(name, oldName)
    SystemMessage.GroupAvatarChanged -> SystemMsg.GroupAvatarChanged
    is SystemMessage.MemberAdded -> SystemMsg.MemberAdded(contactId?.toInt(), name)
    is SystemMessage.MemberRemoved -> SystemMsg.MemberRemoved(contactId?.toInt(), name)
    SystemMessage.MemberLeft -> SystemMsg.MemberLeft
    is SystemMessage.PacketClaimed -> SystemMsg.PacketClaimed(claimer?.toInt(), owner?.toInt(), packetMsgId?.toInt())
}

private fun RNotification.map() = Notification(
    accountId.toInt(), chatId.toInt(), msgId.toInt(), chatName, senderName, isGroup, kind.map(), text, reaction, muted, mention,
)

private fun RFetchResult.map() = FetchResult(newMessages.toInt(), notifications.map { it.map() })

private fun RSettings.map() = Settings(
    readReceipts, MediaQuality.entries[mediaQuality.ordinal], AutoDownload.entries[autoDownload.ordinal], deleteDeviceAfter?.toInt(), mentions,
)

private fun Settings.raw() = RSettings(
    readReceipts, RMediaQuality.entries[mediaQuality.ordinal], RAutoDownload.entries[autoDownload.ordinal], deleteDeviceAfter?.toUInt(), mentions,
)

private fun Event.map(): CoreEvent? = when (this) {
    is Event.ChatListChanged -> CoreEvent.ChatListChanged
    is Event.MessagesChanged -> CoreEvent.MessagesChanged(chatId.toInt())
    is Event.IncomingMessage -> CoreEvent.IncomingMessage(chatId.toInt(), msgId.toInt())
    is Event.MessageStateChanged -> CoreEvent.MessageStateChanged(chatId.toInt(), msgId.toInt())
    is Event.IncomingReaction -> CoreEvent.IncomingReaction(chatId.toInt(), msgId.toInt())
    is Event.ContactsChanged -> CoreEvent.ContactsChanged
    is Event.ConnectivityChanged -> CoreEvent.ConnectivityChanged
    is Event.Error -> CoreEvent.Error(message)
    is Event.Notification -> CoreEvent.Notify(notification.map())
    is Event.TransferProgress -> CoreEvent.TransferProgress(msgId.toInt(), done.toLong(), total.toLong())
}

private fun List<Int>.raw() = map { it.toUInt() }

private class UniffiCore(dataDir: String, sink: EventSink) : TapeTapeCore {
    private val core = RCore(dataDir, object : EventListener {
        override fun onEvent(accountId: UInt, event: Event) {
            event.map()?.let { sink(accountId.toInt(), it) }
        }
    })
    private val accounts = mutableMapOf<Int, UniffiAccount>()

    override fun accounts() = core.accounts().map { it.map() }
    override fun selectedAccount() = core.selectedAccount()?.toInt()
    override fun selectAccount(id: Int) = call { core.selectAccount(id.toUInt()) }
    override suspend fun nodes() = call { core.nodes() }
    override fun generateMnemonic() = core.generateMnemonic()
    override suspend fun addAccount(mnemonic: String, node: String?) = call { core.addAccount(mnemonic, node).toInt() }

    override fun removeAccount(id: Int) = call {
        synchronized(accounts) { accounts.remove(id)?.close() }
        core.removeAccount(id.toUInt())
    }

    override fun account(id: Int): TapeTapeAccount = synchronized(accounts) {
        accounts.getOrPut(id) { UniffiAccount(call { core.account(id.toUInt()) }) }
    }

    override fun addressForMnemonic(mnemonic: String) = call { core.addressForMnemonic(mnemonic) }
    override fun walletTokens() = core.walletTokens().map { it.map() }
    override fun moveAccountToTop(id: Int) = call { core.moveAccountToTop(id.toUInt()) }
    override fun backupProtection(path: String) = call { BackupProtection.entries[core.backupProtection(path).ordinal] }
    override suspend fun importBackup(path: String, secret: String) = call { core.importBackup(path, secret).toInt() }
    override suspend fun fetchAll() = core.fetchAll().map()
    override fun proxySettings() = core.proxySettings().map()
    override fun parseProxy(link: String) = call { core.parseProxy(link).map() }
    override fun addProxy(link: String) = call { core.addProxy(link).map() }
    override fun selectProxy(url: String) = call { core.selectProxy(url).map() }
    override fun removeProxy(url: String) = call { core.removeProxy(url).map() }
    override fun setProxyEnabled(enabled: Boolean) = call { core.setProxyEnabled(enabled).map() }
}

private class UniffiAccount(private val a: RAccount) : TapeTapeAccount {
    fun close() = a.close()

    override fun info() = a.info().map()
    override suspend fun setProfile(name: String, avatarPath: String?) = call { a.setProfile(name, avatarPath) }
    override fun addDeviceMessage(text: String) { call { a.addDeviceMessage(text) } }
    override suspend fun setBio(bio: String) = call { a.setBio(bio) }
    override fun start() = a.start()
    override fun stop() = a.stop()

    override fun connectivity() = when (a.connectivity()) {
        RConnectivity.NOT_CONNECTED -> Connectivity.NotConnected
        RConnectivity.CONNECTING -> Connectivity.Connecting
        RConnectivity.CONNECTED -> Connectivity.Connected
    }

    override fun chats(query: String?) = a.chats(query).map { it.map() }
    override fun archivedChats() = a.archivedChats().map { it.map() }
    override fun chat(chatId: Int) = call { a.chat(chatId.toUInt()).map() }
    override fun messages(chatId: Int, before: Int?, limit: Int) = a.messages(chatId.toUInt(), before?.toUInt(), limit.toUInt()).map { it.map() }
    override fun message(msgId: Int) = call { a.message(msgId.toUInt()).map() }
    override suspend fun sendText(chatId: Int, text: String, replyTo: Int?) = call { a.sendText(chatId.toUInt(), text, replyTo?.toUInt()).toInt() }

    override suspend fun sendMedia(chatId: Int, kind: MessageKind, path: String, text: String?, replyTo: Int?, durationMs: Int?) = call {
        a.sendMedia(chatId.toUInt(), MediaKind.valueOf(kind.raw().name), path, text, replyTo?.toUInt(), durationMs?.toUInt()).toInt()
    }

    override suspend fun sendVcard(chatId: Int, contactId: Int) = call { a.sendVcard(chatId.toUInt(), contactId.toUInt()).toInt() }
    override suspend fun react(msgId: Int, emoji: String) = call { a.react(msgId.toUInt(), emoji) }
    override fun messageReactions(msgId: Int) = a.messageReactions(msgId.toUInt()).map { ReactionBy(it.contactId?.toInt(), it.name, it.emoji) }
    override suspend fun forward(msgIds: List<Int>, toChatId: Int) = call { a.forward(msgIds.raw(), toChatId.toUInt()).map { it.toInt() } }
    override fun deleteMessages(msgIds: List<Int>) = a.deleteMessages(msgIds.raw())
    override suspend fun resend(msgIds: List<Int>) = call { a.resend(msgIds.raw()) }
    override fun setSaved(msgId: Int, saved: Boolean) = call { a.setSaved(msgId.toUInt(), saved) }
    override fun savedMessagesChat() = call { a.savedMessagesChat().toInt() }
    override fun markRead(chatId: Int) = a.markRead(chatId.toUInt())
    override fun setDraft(chatId: Int, text: String?) = a.setDraft(chatId.toUInt(), text)

    override fun setPinned(chatId: Int, pinned: Boolean) = call { a.setPinned(chatId.toUInt(), pinned) }
    override fun mute(chatId: Int, until: Long?) = call { a.mute(chatId.toUInt(), until) }
    override fun unmute(chatId: Int) = call { a.unmute(chatId.toUInt()) }
    override fun setArchived(chatId: Int, archived: Boolean) = call { a.setArchived(chatId.toUInt(), archived) }
    override fun deleteChat(chatId: Int) = call { a.deleteChat(chatId.toUInt()) }
    override fun clearChat(chatId: Int) = call { a.clearChat(chatId.toUInt()) }
    override fun markAllRead(archived: Boolean) = a.markAllRead(archived)
    override fun acceptChat(chatId: Int) = call { a.acceptChat(chatId.toUInt()) }
    override suspend fun setDisappearing(chatId: Int, seconds: Int) = call { a.setDisappearing(chatId.toUInt(), seconds.toUInt()) }
    override fun searchMessages(chatId: Int?, query: String) = a.searchMessages(chatId?.toUInt(), query).map { it.map() }
    override fun mediaMessages(chatId: Int, kinds: List<MessageKind>) = a.mediaMessages(chatId.toUInt(), kinds.map { it.raw() }).map { it.map() }
    override fun encryptionInfo(chatId: Int) = call { a.encryptionInfo(chatId.toUInt()) }

    override fun inviteLink() = a.inviteLink()
    override fun resetInvite() = a.resetInvite()
    override suspend fun acceptInvite(link: String) = call { a.acceptInvite(link).toInt() }
    override fun contacts() = a.contacts().map { it.map() }
    override fun contact(contactId: Int) = call { a.contact(contactId.toUInt()).map() }
    override fun createChat(account: String, name: String) = call { a.createChat(account, name).toInt() }
    override fun chatForContact(contactId: Int) = call { a.chatForContact(contactId.toUInt()).toInt() }
    override fun setContactName(contactId: Int, name: String) = call { a.setContactName(contactId.toUInt(), name) }
    override fun blockContact(contactId: Int) = call { a.blockContact(contactId.toUInt()) }
    override fun unblockContact(contactId: Int) = call { a.unblockContact(contactId.toUInt()) }
    override fun blockedContacts() = a.blockedContacts().map { it.map() }

    override suspend fun createGroup(name: String, avatarPath: String?, members: List<Int>) = call { a.createGroup(name, avatarPath, members.raw()).toInt() }
    override fun groupInfo(chatId: Int) = call {
        a.groupInfo(chatId.toUInt()).let { g ->
            GroupInfo(
                g.chatId.toInt(), g.name, g.avatarPath,
                g.members.map { GroupMember(it.contactId?.toInt(), it.name, it.account, it.address, it.avatarPath, it.admin) },
                g.isMember, g.isAdmin,
            )
        }
    }
    override fun sharedChats(contactId: Int) = a.sharedChats(contactId.toUInt()).map { it.map() }
    override fun deleteContact(contactId: Int) = call { a.deleteContact(contactId.toUInt()) }
    override fun maxAttachmentSize() = a.maxAttachmentSize().toLong()
    override fun setFilesBackend(url: String) = call { a.setRelayFilesBackend(url) }
    override fun download(msgId: Int) = call { a.download(msgId.toUInt()) }
    override suspend fun setGroupName(chatId: Int, name: String) = call { a.setGroupName(chatId.toUInt(), name) }
    override suspend fun setGroupAvatar(chatId: Int, avatarPath: String?) = call { a.setGroupAvatar(chatId.toUInt(), avatarPath) }
    override suspend fun addGroupMembers(chatId: Int, contactIds: List<Int>) = call { a.addGroupMembers(chatId.toUInt(), contactIds.raw()) }
    override suspend fun removeGroupMember(chatId: Int, contactId: Int) = call { a.removeGroupMember(chatId.toUInt(), contactId.toUInt()) }
    override suspend fun leaveGroup(chatId: Int) = call { a.leaveGroup(chatId.toUInt()) }
    override fun groupInviteLink(chatId: Int) = call { a.groupInviteLink(chatId.toUInt()) }
    override fun resetGroupInvite(chatId: Int) = call { a.resetGroupInvite(chatId.toUInt()) }
    override fun messageDeliveries(msgId: Int) = a.messageDeliveries(msgId.toUInt()).map { Delivery(it.contactId.toInt(), it.name, it.state.map(), it.error) }

    override fun settings() = a.settings().map()
    override fun setSettings(settings: Settings) = a.setSettings(settings.raw())
    override fun countMessagesOlderThan(seconds: Int) = a.countMessagesOlderThan(seconds.toUInt()).toInt()
    override fun setAccountMuted(muted: Boolean) = a.setAccountMuted(muted)
    override fun setAccountTag(tag: String) = a.setAccountTag(tag)

    override fun mnemonic() = a.mnemonic()
    override suspend fun exportBackup(destDir: String, passphrase: String?) = call { a.exportBackup(destDir, passphrase) }
    override suspend fun fetchOnce() = call { a.fetchOnce().map() }
    override suspend fun sendPending() = call { a.sendPending() }

    override fun walletAddress() = a.walletAddress()
    override suspend fun balances() = a.balances().map { it.map() }
    override suspend fun estimateSend(tokenId: String, to: String, amount: String) = call { a.estimateSend(tokenId, to, amount).map() }
    override suspend fun sendToken(tokenId: String, to: String, amount: String, confirmedFee: String?) = call { a.sendToken(tokenId, to, amount, confirmedFee) }
    override suspend fun dappRpc(chainId: Long, origin: String, method: String, paramsJson: String) = call { a.dappRpc(chainId.toULong(), origin, method, paramsJson) }
    override suspend fun dappCheckTx(chainId: Long, origin: String, txJson: String) = call {
        a.dappCheckTx(chainId.toULong(), origin, txJson).let {
            DappTx(
                it.chainId.toLong(), it.chainName, it.to, it.toIsContract, it.value, it.symbol, it.dataLen.toInt(), it.selector,
                it.action, it.counterparty, it.from, it.token, it.tokenAmount, it.tokenSymbol, it.tokenKnown, it.unlimited, it.expiry,
                it.nft, it.rawAmount, it.extraData, it.danger, it.gas, it.fee, it.balance, it.ticket, it.error,
            )
        }
    }
    override suspend fun dappSendTx(chainId: Long, txJson: String, confirmedFee: String, ticket: String) = call { a.dappSendTx(chainId.toULong(), txJson, confirmedFee, ticket) }
    override fun dappMessage(raw: String) = a.dappMessage(raw).let { DappMessage(it.hex, it.text, it.blind) }
    override fun dappPersonalSign(messageHex: String) = call { a.dappPersonalSign(messageHex) }
    override fun dappTypedData(chainId: Long, json: String, version: Int) = call {
        a.dappTypedData(chainId.toULong(), json, version.toUInt()).let { DappTypedData(it.domainName, it.verifyingContract, it.chainId, it.primaryType, it.fields, it.danger, it.counterparty) }
    }
    override fun dappSignTypedData(chainId: Long, json: String, version: Int) = call { a.dappSignTypedData(chainId.toULong(), json, version.toUInt()) }
    override fun pendingSends() = call { a.pendingSends().map { WalletTx(it.chainId.toLong(), it.tokenId, it.to, it.amount, it.txHash, it.createdAt, it.dapp) } }
    override suspend fun txStatus(chainId: Long, txHash: String) = call { TxStatus.entries[a.txStatus(chainId.toULong(), txHash).ordinal] }
    override suspend fun estimatePacket(tokenId: String, amount: String, count: Int) = call { a.estimatePacket(tokenId, amount, count.toUInt()).map() }
    override suspend fun estimateTransfer(chatId: Int, tokenId: String, amount: String) = call { a.estimateTransfer(chatId.toUInt(), tokenId, amount).map() }
    override suspend fun sendRedPacket(chatId: Int, tokenId: String, amount: String, count: Int, greeting: String, confirmedFee: String?) =
        call { a.sendRedPacket(chatId.toUInt(), tokenId, amount, count.toUInt(), greeting, confirmedFee).toInt() }
    override suspend fun sendTransfer(chatId: Int, tokenId: String, amount: String, note: String, confirmedFee: String?) =
        call { a.sendTransfer(chatId.toUInt(), tokenId, amount, note, confirmedFee).toInt() }
    override suspend fun packetStatus(msgId: Int) = call { a.packetStatus(msgId.toUInt()).map() }
    override suspend fun claimPacket(msgId: Int) = call { a.claimPacket(msgId.toUInt()) }
    override suspend fun refundPacket(msgId: Int) = call { a.refundPacket(msgId.toUInt()) }
    override suspend fun shopInfo() = call { a.shopInfo().map() }
    override suspend fun shopOpen() = call { a.shopOpen().map() }
    override suspend fun shopPublish(input: ProductInput) =
        call { a.shopPublish(RProductInput(input.description, input.originalPrice.toULong(), input.discountPrice.toULong(), input.images)).map() }
    override suspend fun shopRemove(productId: String) = call { a.shopRemove(productId) }
}
