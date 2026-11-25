import SwiftUI
import TapeTapeCore

extension AttachmentKind {
    var media: MediaKind {
        switch self {
        case .image: return .image
        case .gif: return .gif
        case .sticker: return .sticker
        case .video: return .video
        case .file: return .file
        case .audio: return .audio
        case .voice: return .voice
        }
    }
}

extension Message {
    var outgoing: Bool { fromContact == nil }
    var isPacketClaim: Bool {
        if case .packetClaimed = system { return true }
        return false
    }
    var isMedia: Bool { [.image, .gif, .video, .sticker].contains(kind) }
    var hasFile: Bool { filePath != nil && kind != .text && kind != .system && kind != .vcard }
    var myReaction: String? { reactions.first { $0.mine }?.emoji }
}

@MainActor
final class ChatViewModel: ObservableObject {
    let chatId: UInt32
    let account: Account?
    @Published var chat: Chat?
    @Published var messages: [Message] = []
    @Published var text = ""
    @Published var replyTo: Message?
    @Published var attachment: Attachment?
    @Published var stagedContact: Contact?
    @Published var selection: Set<UInt32>?
    @Published var unreadMarker: UInt32?
    @Published var unreadCount = 0
    @Published var searching = false
    @Published var query = ""
    @Published var matches: [UInt32] = []
    @Published var matchIndex = 0
    @Published var errorText: String?
    private var extraQuotes: [UInt32: Message] = [:]

    init(chatId: UInt32, account: Account?, draft: String?) {
        self.chatId = chatId
        self.account = account
        text = draft ?? ""
        reload(initial: true)
    }

    func reload(initial: Bool = false) {
        guard let account else { return }
        chat = try? account.chat(chatId: chatId)
        messages = account.messages(chatId: chatId, before: nil, limit: 1000)
        let unread = messages.filter { $0.state == .incoming }
        if initial, let first = unread.first {
            unreadMarker = first.id
            unreadCount = unread.count
        }
        if let selection {
            let ids = Set(messages.map(\.id))
            self.selection = selection.intersection(ids)
        }
    }

    func markSeen() {
        guard let account, chat?.isRequest != true, messages.contains(where: { $0.state == .incoming }) else { return }
        account.markRead(chatId: chatId)
        Notifier.clear(accountId: account.info().id, chatId: chatId)
        Log.chat.debug("chat \(self.chatId) marked read")
    }

    func quoted(_ id: UInt32?) -> Message? {
        guard let id else { return nil }
        if let m = messages.first(where: { $0.id == id }) { return m }
        if let m = extraQuotes[id] { return m }
        let m = try? account?.message(msgId: id)
        extraQuotes[id] = m
        return m
    }

    func mediaURLs() -> [(id: UInt32, url: URL)] {
        messages.filter { [.image, .gif, .video].contains($0.kind) }.compactMap { m in m.filePath.map { (m.id, URL(fileURLWithPath: $0)) } }
    }

    func send() {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let account, !body.isEmpty || attachment != nil || stagedContact != nil else { return }
        let reply = replyTo?.id
        let attachment = attachment
        let contact = stagedContact
        text = ""
        replyTo = nil
        self.attachment = nil
        stagedContact = nil
        account.setDraft(chatId: chatId, text: nil)
        Task {
            do {
                if let contact {
                    let id = try await account.sendVcard(chatId: chatId, contactId: contact.id)
                    Log.chat.info("queued vcard \(id) in chat \(self.chatId)")
                }
                if let attachment {
                    let duration = attachment.duration > 0 ? UInt32(attachment.duration * 1000) : nil
                    let id = try await account.sendMedia(chatId: chatId, kind: attachment.kind.media, path: attachment.url.path, text: body.isEmpty ? nil : body, replyTo: reply, durationMs: duration)
                    Log.chat.info("queued \(String(describing: attachment.kind), privacy: .public) \(id) in chat \(self.chatId)")
                } else if !body.isEmpty {
                    let id = try await account.sendText(chatId: chatId, text: body, replyTo: reply)
                    Log.chat.info("queued message \(id) in chat \(self.chatId)")
                }
            } catch {
                fail("send", error)
            }
            reload()
        }
    }

    func sendVoice(_ note: VoiceNote) {
        guard let account else { return }
        let reply = replyTo?.id
        replyTo = nil
        Task {
            do {
                let id = try await account.sendMedia(chatId: chatId, kind: .voice, path: note.url.path, text: nil, replyTo: reply, durationMs: UInt32(note.duration * 1000))
                Log.chat.info("queued voice \(id) in chat \(self.chatId)")
            } catch {
                fail("send voice", error)
            }
            reload()
        }
    }

    func react(_ message: Message, _ emoji: String) {
        guard let account else { return }
        let value = message.myReaction == emoji ? "" : emoji
        Task {
            do {
                try await account.react(msgId: message.id, emoji: value)
                Log.chat.info("reaction on \(message.id) set")
            } catch {
                fail("react", error)
            }
            reload()
        }
    }

    func toggleSaved(_ message: Message) {
        do {
            try account?.setSaved(msgId: message.id, saved: !message.saved)
            Log.chat.info("message \(message.id) saved=\(!message.saved)")
        } catch {
            fail("save", error)
        }
        reload()
    }

    func toggleSelection(_ id: UInt32) {
        guard var selection else { return }
        if selection.contains(id) { selection.remove(id) } else { selection.insert(id) }
        self.selection = selection
    }

    var selectedMessages: [Message] {
        guard let selection else { return [] }
        return messages.filter { selection.contains($0.id) }
    }

    func copySelected(myName: String) {
        let text = selectedMessages.filter { !$0.text.isEmpty }.map { m in
            "\(m.outgoing ? myName : m.senderName):\n\(m.text)"
        }.joined(separator: "\n\n")
        UIPasteboard.general.string = text
        selection = nil
    }

    func deleteSelected() {
        let ids = selectedMessages.map(\.id)
        account?.deleteMessages(msgIds: ids)
        Log.chat.info("deleted \(ids.count) messages locally")
        selection = nil
        reload()
    }

    func resendSelected() {
        guard let account else { return }
        let ids = selectedMessages.map(\.id)
        selection = nil
        Task {
            do {
                try await account.resend(msgIds: ids)
                Log.chat.info("resend \(ids.count) messages")
            } catch {
                fail("resend", error)
            }
            reload()
        }
    }

    func runSearch() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard let account, !q.isEmpty else {
            matches = []
            return
        }
        matches = account.searchMessages(chatId: chatId, query: q).map(\.id)
        matchIndex = max(0, matches.count - 1)
        Log.chat.debug("in-chat search found \(self.matches.count)")
    }

    func stepMatch(_ delta: Int) {
        guard !matches.isEmpty else { return }
        matchIndex = (matchIndex + delta + matches.count) % matches.count
    }

    func accept() {
        do {
            try account?.acceptChat(chatId: chatId)
            Log.chat.info("chat \(self.chatId) accepted")
        } catch {
            fail("accept", error)
        }
        reload()
    }

    func saveDraft() {
        let draft = text.trimmingCharacters(in: .whitespacesAndNewlines)
        account?.setDraft(chatId: chatId, text: draft.isEmpty ? nil : text)
    }

    private func fail(_ what: String, _ error: Error) {
        Log.chat.error("\(what, privacy: .public) failed: \(coreMessage(error), privacy: .public)")
        errorText = coreMessage(error)
    }
}
