import SwiftUI
import Combine
import TapeTapeCore

extension ChatSummary: @retroactive Identifiable {}
extension Contact: @retroactive Identifiable {}
extension Message: @retroactive Identifiable {}
extension AccountInfo: @retroactive Identifiable {}

enum MainTab: String {
    case chats, contact, square, settings
}

enum Route: Hashable {
    case chat(UInt32)
    case newChat
    case qr
    case profile
    case archive
    case chatDetails(UInt32)
    case contactProfile(UInt32)
    case allMedia(UInt32)
    case disappearing(UInt32)
    case chatsMedia
    case blocked
    case newGroup(clone: UInt32?)
    case groupQR(UInt32)
    case notifications
    case wallpaper
    case advanced
    case mediaQuality
    case autoDownload
    case deleteOld
    case log
    case connectivity
    case proxy
    case receive
    case withdraw
    case packetDetail(UInt32)
    case shop(URL)
}

struct Transfer: Equatable {
    let done: UInt64
    let total: UInt64
    var percent: Int { total > 0 ? Int(done * 100 / total) : 0 }
}

final class EventBridge: EventListener, @unchecked Sendable {
    weak var model: AppModel?

    func onEvent(accountId: UInt32, event: Event) {
        Task { @MainActor [weak model] in model?.handle(accountId: accountId, event: event) }
    }
}

@MainActor
final class AppModel: ObservableObject {
    let core: Core
    @Published private(set) var account: Account?
    @Published private(set) var info: AccountInfo?
    @Published private(set) var accounts: [AccountInfo] = []
    @Published private(set) var chats: [ChatSummary] = []
    private(set) var systemPreviews: [UInt32: String] = [:]
    @Published private(set) var archivedChats: [ChatSummary] = []
    @Published var forwarding: [UInt32]?
    @Published var forwardingContact: UInt32?
    @Published var pendingChatSearch: UInt32?
    var pendingReply: (chatId: UInt32, msgId: UInt32)?
    @Published private(set) var contacts: [Contact] = []
    @Published private(set) var connectivity: Connectivity = .notConnected
    @Published var tab: MainTab = MainTab(rawValue: UserDefaults.standard.string(forKey: "lastTab") ?? "") ?? .chats {
        didSet { UserDefaults.standard.set(tab.rawValue, forKey: "lastTab") }
    }
    @Published var chatsPath: [Route] = [] {
        didSet {
            guard let id = info?.id else { return }
            let open = chatsPath.compactMap { if case .chat(let chatId) = $0 { return chatId } else { return nil } }.first
            UserDefaults.standard.set(open, forKey: "lastChat.\(id)")
        }
    }
    @Published var contactPath: [Route] = []
    @Published var settingsPath: [Route] = []
    @Published var addingProfile = false
    @Published var alert: AlertItem?
    @Published var packetCover: PacketCover?
    @Published private(set) var transfers: [UInt32: Transfer] = [:]
    @Published private(set) var settings: Settings?
    @Published private(set) var proxy = ProxySettings(enabled: false, active: nil, proxies: [])
    let dataDir: URL
    let chatEvents = PassthroughSubject<UInt32, Never>()
    private var chatContacts: [UInt32: UInt32] = [:]

    init() {
        let bridge = EventBridge()
        let dir = AppGroup.dataDir
        dataDir = dir
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-TapeTapeReset") {
            try? FileManager.default.removeItem(at: dir)
            UserDefaults.standard.removeObject(forKey: "lastTab")
        }
        #endif
        do {
            core = try Core(dataDir: dir.path, listener: bridge)
        } catch {
            fatalError("core init failed: \(error)")
        }
        AppGroup.protect(dir)
        bridge.model = self
        proxy = core.proxySettings()
        Log.app.info("core started, data dir ready")
        loadSelectedAccount()
        restoreLastChat()
    }

    private func restoreLastChat() {
        guard tab == .chats, let id = info?.id,
              let chatId = UserDefaults.standard.object(forKey: "lastChat.\(id)") as? UInt32,
              (try? account?.chat(chatId: chatId)) != nil else { return }
        Log.app.info("restoring last open chat \(chatId)")
        chatsPath = [.chat(chatId)]
    }

    var totalUnread: Int {
        chats.filter { !$0.muted }.reduce(0) { $0 + Int($1.unread) }
    }

    func loadSelectedAccount() {
        accounts = core.accounts()
        guard let id = core.selectedAccount(), let acc = try? core.account(id: id) else {
            account = nil
            info = nil
            Log.app.info("no configured profile")
            return
        }
        for other in accounts where other.id != id {
            (try? core.account(id: other.id))?.start()
        }
        applyFilesBackend()
        account = acc
        info = acc.info()
        chatContacts = [:]
        acc.start()
        _ = try? acc.savedMessagesChat()
        Staging.limit = Int(acc.maxAttachmentSize())
        settings = acc.settings()
        Staging.worseQuality = settings?.mediaQuality == .worse
        connectivity = acc.connectivity()
        reloadChats()
        reloadContacts()
        Log.app.info("profile \(id) selected, \(self.chats.count) chats")
        Notifier.requestPermission()
    }

    private static let filesBackend: String? = {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-TapeTapeFilesBackend"), i + 1 < args.count { return args[i + 1] }
        #endif
        let url = (Bundle.main.object(forInfoDictionaryKey: "TapeTapeFilesBackend") as? String ?? "").trimmingCharacters(in: .whitespaces)
        return url.isEmpty ? nil : url
    }()

    private func applyFilesBackend() {
        guard let url = Self.filesBackend else { return }
        for info in accounts {
            guard let acc = try? core.account(id: info.id), acc.relayFilesBackend() != url else { continue }
            do {
                try acc.setRelayFilesBackend(url: url)
                Log.app.info("relay files backend set for profile \(info.id)")
            } catch {
                Log.app.error("set relay files backend failed: \(coreMessage(error), privacy: .public)")
            }
        }
    }

    func switchAccount(_ id: UInt32) {
        do {
            try core.selectAccount(id: id)
            chatsPath = []
            contactPath = []
            settingsPath = []
            forwarding = nil
            loadSelectedAccount()
        } catch {
            Log.app.error("select account failed: \(coreMessage(error), privacy: .public)")
        }
    }

    func handle(accountId: UInt32, event: Event) {
        if case .notification(let n) = event {
            if !(n.accountId == info?.id && UIApplication.shared.applicationState == .active && chatsPath.last == .chat(n.chatId)) {
                Notifier.post(n, core: core)
            }
        }
        guard accountId == info?.id else {
            switch event {
            case .chatListChanged, .incomingMessage, .messagesChanged:
                accounts = core.accounts()
            default:
                break
            }
            return
        }
        switch event {
        case .chatListChanged:
            reloadChats()
        case .messagesChanged(let chatId), .incomingMessage(let chatId, _), .messageStateChanged(let chatId, _), .incomingReaction(let chatId, _):
            reloadChats()
            chatEvents.send(chatId)
        case .contactsChanged:
            reloadContacts()
            chatContacts = [:]
            reloadChats()
        case .connectivityChanged:
            connectivity = account?.connectivity() ?? .notConnected
            Log.app.debug("connectivity \(String(describing: self.connectivity), privacy: .public)")
        case .error(let message):
            Log.app.error("core error: \(message, privacy: .public)")
        case .notification:
            break
        case .transferProgress(let msgId, let done, let total):
            Log.chat.debug("transfer \(msgId) \(done)/\(total)")
            transfers[msgId] = done >= total ? nil : Transfer(done: done, total: total)
        }
    }

    func reloadChats() {
        guard let account else { return }
        chats = account.chats(query: nil)
        Notifier.updateBadge(core)
        archivedChats = account.archivedChats()
        systemPreviews = [:]
        for c in chats + archivedChats where c.lastKind == .system {
            if let last = account.messages(chatId: c.id, before: nil, limit: 1).last {
                systemPreviews[c.id] = SystemText.text(for: last) { [contacts] id in contacts.first { $0.id == id }?.name ?? "" }
            }
        }
        for c in chats + archivedChats where !c.isGroup && chatContacts[c.id] == nil {
            chatContacts[c.id] = try? account.chat(chatId: c.id).contactId
        }
    }

    func reloadContacts() {
        contacts = account?.contacts() ?? []
    }

    func refreshInfo() {
        info = account?.info()
        accounts = core.accounts()
    }

    func updateSettings(_ change: (inout Settings) -> Void) {
        guard let account, var new = settings else { return }
        change(&new)
        account.setSettings(settings: new)
        settings = account.settings()
        Log.app.info("settings updated")
    }

    func updateProxy(_ what: String, _ change: (Core) throws -> ProxySettings) {
        do {
            proxy = try change(core)
            Log.app.info("\(what, privacy: .public): \(self.proxy.proxies.count) proxies, enabled \(self.proxy.enabled)")
        } catch {
            Log.app.error("\(what, privacy: .public) failed: \(coreMessage(error), privacy: .public)")
            alert = AlertItem(title: L("proxy_invalid"), message: coreMessage(error), confirm: nil, action: nil)
        }
    }

    func setNotifications(_ enabled: Bool) {
        guard let account, let id = info?.id else { return }
        account.setAccountMuted(muted: !enabled)
        refreshInfo()
        Log.app.info("profile \(id) notifications \(enabled ? "on" : "off", privacy: .public)")
        if !enabled { Notifier.clear(accountId: id, chatId: nil) }
    }

    func setAccountMuted(_ id: UInt32, _ muted: Bool) {
        guard let acc = try? core.account(id: id) else { return }
        acc.setAccountMuted(muted: muted)
        if muted { Notifier.clear(accountId: id, chatId: nil) }
        Log.app.info("profile \(id) muted \(muted)")
        refreshInfo()
    }

    func setTag(_ id: UInt32, _ tag: String) {
        (try? core.account(id: id))?.setAccountTag(tag: tag)
        Log.app.info("profile \(id) tag updated")
        refreshInfo()
    }

    func moveAccountToTop(_ id: UInt32) {
        do {
            try core.moveAccountToTop(id: id)
            Log.app.info("profile \(id) moved to top")
        } catch {
            Log.app.error("move profile failed: \(coreMessage(error), privacy: .public)")
        }
        refreshInfo()
    }

    func deleteAccount(_ id: UInt32) {
        do {
            (try? core.account(id: id))?.stop()
            try core.removeAccount(id: id)
            Notifier.clear(accountId: id, chatId: nil)
            Log.app.info("profile \(id) deleted")
        } catch {
            Log.app.error("delete profile failed: \(coreMessage(error), privacy: .public)")
            alert = AlertItem(title: coreMessage(error), message: nil, confirm: nil, action: nil)
            return
        }
        if id == info?.id, let next = core.accounts().first {
            switchAccount(next.id)
        } else {
            loadSelectedAccount()
        }
    }

    func avatarSeed(for chat: ChatSummary) -> String {
        guard !chat.isGroup, let cid = chatContacts[chat.id], let contact = contacts.first(where: { $0.id == cid }) else { return chat.name }
        return contact.address
    }

    func contactAddress(_ contactId: UInt32?) -> String? {
        contacts.first { $0.id == contactId }?.address
    }

    func contact(_ contactId: UInt32?) -> Contact? {
        contacts.first { $0.id == contactId }
    }

    func push(_ route: Route) {
        switch tab {
        case .contact: contactPath.append(route)
        case .settings: settingsPath.append(route)
        default: chatsPath.append(route)
        }
    }

    func perform(_ what: String, _ action: () throws -> Void) {
        do {
            try action()
            Log.chat.info("\(what, privacy: .public) done")
        } catch {
            Log.chat.error("\(what, privacy: .public) failed: \(coreMessage(error), privacy: .public)")
            alert = AlertItem(title: coreMessage(error), message: nil, confirm: nil, action: nil)
        }
        reloadChats()
    }

    func chatName(_ name: String, isSelf: Bool, isDevice: Bool) -> String {
        if isSelf { return L("saved_messages") }
        if isDevice { return L("device_talk") }
        return name
    }

    func existingChatContact(_ chatId: UInt32) -> UInt32? {
        chatContacts[chatId] ?? nil
    }

    func existingChat(forContact contactId: UInt32) -> UInt32? {
        chatContacts.first { $0.value == contactId }?.key
    }

    func startChat(withContact contact: Contact) {
        if let chatId = existingChat(forContact: contact.id) {
            openChat(chatId)
            return
        }
        alert = AlertItem(title: L("ask_start_chat_with", contact.name), message: nil, confirm: L("start_chat")) { [weak self] in
            guard let self, let account = self.account else { return }
            self.perform("create chat for contact \(contact.id)") {
                let chatId = try account.chatForContact(contactId: contact.id)
                self.reloadChats()
                self.openChat(chatId)
            }
        }
    }

    func openSavedMessages() {
        guard let account else { return }
        perform("open saved messages") { openChat(try account.savedMessagesChat()) }
    }

    var isForwarding: Bool { forwarding != nil || forwardingContact != nil }

    func startForwarding(_ ids: [UInt32]) {
        Log.chat.info("forward mode with \(ids.count) messages")
        forwarding = ids
        tab = .chats
        chatsPath = []
    }

    func startForwardingContact(_ contactId: UInt32) {
        Log.chat.info("forward mode with contact card")
        forwardingContact = contactId
        tab = .chats
        chatsPath = []
    }

    func cancelForwarding() {
        forwarding = nil
        forwardingContact = nil
    }

    func openFromNotification(accountId: UInt32, chatId: UInt32) {
        if accountId != info?.id { switchAccount(accountId) }
        tab = .chats
        openChat(chatId)
    }

    func openLink(_ url: URL) {
        Log.invite.info("open link with host \(url.host ?? "", privacy: .public)")
        guard account != nil else { return }
        handleScanned(url.absoluteString)
    }

    private var foregrounded = true

    func scenePhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .active:
            guard !foregrounded else { return }
            foregrounded = true
            for acc in core.accounts() { (try? core.account(id: acc.id))?.start() }
            Log.app.info("foreground, accounts started")
            reloadChats()
            refreshInfo()
        case .background:
            guard foregrounded else { return }
            foregrounded = false
            for acc in core.accounts() { (try? core.account(id: acc.id))?.stop() }
            BackgroundFetch.schedule()
            Notifier.updateBadge(core)
            Log.app.info("background, accounts stopped")
        default:
            break
        }
    }

    func openContactProfile(_ contactId: UInt32) {
        push(.contactProfile(contactId))
    }

    func forward(to chat: ChatSummary) {
        guard let account, isForwarding else { return }
        let ids = forwarding ?? []
        let card = forwardingContact
        let send = { [weak self] in
            guard let self else { return }
            self.cancelForwarding()
            Task {
                do {
                    if let card {
                        _ = try await account.sendVcard(chatId: chat.id, contactId: card)
                    } else {
                        _ = try await account.forward(msgIds: ids, toChatId: chat.id)
                    }
                    Log.chat.info("forwarded to chat \(chat.id)")
                } catch {
                    Log.chat.error("forward failed: \(coreMessage(error), privacy: .public)")
                    self.alert = AlertItem(title: coreMessage(error), message: nil, confirm: nil, action: nil)
                }
                self.openChat(chat.id)
            }
        }
        if chat.isSelf {
            send()
        } else {
            alert = AlertItem(title: L("ask_forward", chatName(chat.name, isSelf: chat.isSelf, isDevice: chat.isDevice)), message: nil, confirm: L("menu_forward"), action: send)
        }
    }

    func openChat(_ chatId: UInt32) {
        Log.chat.info("open chat \(chatId)")
        if tab == .chats {
            chatsPath = [.chat(chatId)]
            return
        }
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { contactPath = [] }
        DispatchQueue.main.async {
            self.tab = .chats
            self.chatsPath = [.chat(chatId)]
        }
    }


    func handleScanned(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        Log.invite.info("handle scanned text, length \(trimmed.count)")
        if let invite = Invite(link: trimmed) {
            let title = invite.group.map { L("qrscan_ask_join_group", $0) } ?? L("ask_start_chat_with", invite.name)
            alert = AlertItem(title: title, message: nil, confirm: L("ok")) { [weak self] in
                self?.acceptInvite(trimmed)
            }
        } else if let info = try? core.parseProxy(link: trimmed) {
            ProxyPrompt.present(info, link: trimmed, model: self)
        } else if let url = URL(string: trimmed), url.scheme?.hasPrefix("http") == true {
            alert = AlertItem(title: L("qrscan_contains_url", trimmed), message: nil, confirm: L("open")) {
                UIApplication.shared.open(url)
            }
        } else {
            alert = AlertItem(title: L("qrscan_contains_text", trimmed), message: nil, confirm: nil, action: nil)
        }
    }

    func acceptInvite(_ link: String) {
        guard let account else { return }
        Task {
            do {
                let chatId = try await account.acceptInvite(link: link)
                Log.invite.info("invite accepted, chat \(chatId)")
                reloadContacts()
                reloadChats()
                openChat(chatId)
            } catch {
                Log.invite.error("accept invite failed: \(coreMessage(error), privacy: .public)")
                alert = AlertItem(title: L("error"), message: coreMessage(error), confirm: nil, action: nil)
            }
        }
    }
}

struct AlertItem: Identifiable {
    let id = UUID()
    let title: String
    let message: String?
    let confirm: String?
    let action: (() -> Void)?
}

struct Invite {
    static let hosts: Set<String> = ["i.tapetape.org"]
    let name: String
    let group: String?

    init?(link: String) {
        guard let url = URL(string: link), let host = url.host, Self.hosts.contains(host), let fragment = url.fragment else { return nil }
        var params: [String: String] = [:]
        for pair in fragment.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
            if kv.count == 2 { params[kv[0]] = kv[1].removingPercentEncoding ?? kv[1] }
        }
        guard let account = params["a"] else { return nil }
        name = params["n"].flatMap { $0.isEmpty ? nil : $0 } ?? account
        group = params["g"] == nil ? nil : params["gn"] ?? ""
    }
}

extension View {
    func appAlert(_ item: Binding<AlertItem?>) -> some View {
        alert(item.wrappedValue?.title ?? "", isPresented: Binding(get: { item.wrappedValue != nil }, set: { if !$0 { item.wrappedValue = nil } }), presenting: item.wrappedValue) { a in
            if let confirm = a.confirm {
                Button(L("cancel"), role: .cancel) {}
                Button(confirm) { a.action?() }
            } else {
                Button(L("ok"), role: .cancel) {}
            }
        } message: { a in
            if let message = a.message { Text(message) }
        }
    }
}
