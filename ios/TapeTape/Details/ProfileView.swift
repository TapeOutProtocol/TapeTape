import SwiftUI
import TapeTapeCore

enum MuteDialog {
    static func present(apply: @escaping (Int64?) -> Void) {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let hour: Int64 = 3_600_000
        ActionSheet.present(title: L("menu_mute"), actions: [
            (L("mute_for_one_hour"), { apply(now + hour) }),
            (L("mute_for_two_hours"), { apply(now + 2 * hour) }),
            (L("mute_for_one_day"), { apply(now + 24 * hour) }),
            (L("mute_for_seven_days"), { apply(now + 7 * 24 * hour) }),
            (L("mute_forever"), { apply(nil) })
        ])
    }
}

struct ProfileView: View {
    @State private var copied = false
    let chatId: UInt32?
    let contactId: UInt32?
    @EnvironmentObject var model: AppModel
    @State private var chat: Chat?
    @State private var contact: Contact?
    @State private var mediaCount = 0
    @State private var editing = false
    @State private var share: ShareItem?
    @State private var sharedChats: [ChatSummary] = []

    private var account: Account? { model.account }
    private var isSpecial: Bool { chat?.isSelf == true || chat?.isDevice == true }

    private var title: String {
        isSpecial ? L("profile") : L("contact")
    }

    private var name: String {
        if let chat { return model.chatName(chat.name, isSelf: chat.isSelf, isDevice: chat.isDevice) }
        return contact?.name ?? ""
    }

    private var addressLine: String {
        if chat?.isDevice == true { return L("device_talk_subtitle") }
        if chat?.isSelf == true { return model.info?.address ?? "" }
        return contact?.address ?? ""
    }

    var body: some View {
        List {
            Section {
                header
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
            }
            Section {
                Button { openMedia() } label: {
                    row(icon: "photo.on.rectangle", title: L("media"), value: mediaCount > 0 ? "\(mediaCount)" : L("none"), chevron: mediaCount > 0)
                }
                .disabled(mediaCount == 0)
                if let chat, !chat.isDevice {
                    Button { model.chatsPath.append(.disappearing(chat.id)) } label: {
                        row(icon: "timer", title: L("ephemeral_messages"), value: L(chat.ephemeralSeconds > 0 ? "on" : "off"), chevron: true)
                    }
                }
                if !addressLine.isEmpty, chat?.isDevice != true {
                    Button {
                        UIPasteboard.general.string = addressLine
                        copied = true
                    } label: {
                        HStack {
                            Label { Text(shortAddress(addressLine)).foregroundColor(.secondary) } icon: { Image(systemName: "number").foregroundColor(Color(.darkGray)) }
                            Spacer()
                            Image(systemName: copied ? "checkmark" : "doc.on.doc").foregroundColor(.secondary)
                        }
                    }
                    .accessibilityLabel(L("menu_copy_to_clipboard"))
                }
                if !isSpecial {
                    ListAction(L("send_message"), icon: "bubble.left") { sendMessage() }
                    if let contact {
                        ListAction(L("menu_share"), icon: "square.and.arrow.up") { model.startForwardingContact(contact.id) }
                    }
                }
            } footer: {
                if let seen = contact?.lastSeen, !isSpecial {
                    Text(L("last_seen_at", TimeFormat.bubble(seen)))
                }
            }
            if let bio = contact?.bio, !bio.isEmpty, !isSpecial {
                Section(L("pref_default_status_label")) {
                    LinkText(text: bio)
                }
            }
            if !sharedChats.isEmpty, !isSpecial {
                Section(L("profile_shared_chats")) {
                    ForEach(sharedChats) { shared in
                        Button { model.openChat(shared.id) } label: {
                            ChatRowContent(chat: shared, seed: shared.name)
                        }
                    }
                }
            }
            Section { actions }
        }
        .listStyle(.insetGrouped)
        .environment(\.defaultMinListRowHeight, 44)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            if !isSpecial, contact != nil {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(L("global_menu_edit_desktop")) { editing = true }
                }
            }
        }
        .sheet(isPresented: $editing) {
            if let contact { EditNameView(contact: contact) { load() } }
        }
        .sheet(item: $share) { ShareSheet(items: $0.items) }
        .onAppear { load() }
        .onReceive(model.$chats) { _ in load() }
    }

    private var header: some View {
        HStack(spacing: 12) {
            ChatAvatar(name: name, seed: contact?.address ?? name, path: chat?.avatarPath ?? contact?.avatarPath, size: 54, isSelf: chat?.isSelf == true, isDevice: chat?.isDevice == true)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(name).font(.body.weight(.semibold)).lineLimit(1)
                    if chat?.isSelf == true || contact?.verified == true { VerifiedBadge() }
                }
            }
            Spacer(minLength: 4)
            if let chat {
                CircleButton(system: "magnifyingglass", label: L("search_in_chat")) {
                    model.pendingChatSearch = chat.id
                    popToChat()
                }
                if !chat.isSelf {
                    CircleButton(system: chat.muted ? "speaker.slash" : "speaker.wave.2", label: L(chat.muted ? "menu_unmute" : "menu_mute")) { toggleMute(chat) }
                }
            }
        }
        .padding(.vertical, 4)
    }

    @ViewBuilder
    private var actions: some View {
        if let chat {
            ListAction(L(chat.archived ? "menu_unarchive_chat" : "menu_archive_chat"), icon: chat.archived ? "tray.and.arrow.up" : "archivebox") {
                model.perform("archive chat \(chat.id)") { try account?.setArchived(chatId: chat.id, archived: !chat.archived) }
                if !chat.archived { model.chatsPath = [] } else { load() }
            }
        }
        if let chat, !isSpecial {
            ListAction(L("encryption_info_title_desktop"), icon: "lock") {
                let info = (try? account?.encryptionInfo(chatId: chat.id)) ?? ""
                model.alert = AlertItem(title: L("encryption_info_title_desktop"), message: info, confirm: nil, action: nil)
            }
        }
        if let contact, !isSpecial {
            ListAction(L(contact.blocked ? "menu_unblock_contact" : "menu_block_contact"), icon: "hand.raised", destructive: !contact.blocked) { toggleBlock(contact) }
        }
        if let chat {
            ListAction(L("clear_chat"), icon: "eraser", destructive: true) { clear(chat) }
            ListAction(L("menu_delete_chat"), icon: "trash", destructive: true) { delete(chat) }
        }
    }

    private func row(icon: String, title: String, value: String, chevron: Bool) -> some View {
        HStack {
            Label {
                Text(title).foregroundColor(.primary)
            } icon: {
                Image(systemName: icon).font(.system(size: 17)).foregroundColor(.blue)
            }
            Spacer()
            Text(value).foregroundColor(.secondary)
            if chevron { Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundColor(Color(.tertiaryLabel)) }
        }
    }

    private func load() {
        guard let account else { return }
        let resolvedChat = chatId ?? contactId.flatMap { model.existingChat(forContact: $0) }
        chat = resolvedChat.flatMap { try? account.chat(chatId: $0) }
        let cid = contactId ?? chat?.contactId
        contact = cid.flatMap { try? account.contact(contactId: $0) }
        sharedChats = cid.map { account.sharedChats(contactId: $0) } ?? []
        if let chat {
            mediaCount = account.mediaMessages(chatId: chat.id, kinds: [.image, .gif, .video, .file, .audio, .voice]).count
        }
    }

    private func openMedia() {
        guard let chat else { return }
        model.chatsPath.append(.allMedia(chat.id))
    }

    private func popToChat() {
        if case .chatDetails = model.chatsPath.last { model.chatsPath.removeLast() }
    }

    private func sendMessage() {
        guard let contact else { return }
        model.perform("send message to contact \(contact.id)") {
            guard let account else { return }
            model.openChat(try account.chatForContact(contactId: contact.id))
        }
    }

    private func toggleMute(_ chat: Chat) {
        if chat.muted {
            model.perform("unmute chat \(chat.id)") { try account?.unmute(chatId: chat.id) }
            load()
            return
        }
        MuteDialog.present { until in
            model.perform("mute chat \(chat.id)") { try account?.mute(chatId: chat.id, until: until) }
            popToChat()
        }
    }

    private func toggleBlock(_ contact: Contact) {
        let unblock = contact.blocked
        model.alert = AlertItem(title: L(unblock ? "ask_unblock_contact" : "ask_block_contact"), message: nil, confirm: L(unblock ? "menu_unblock_contact" : "menu_block_contact")) {
            model.perform("block contact \(contact.id)") {
                if unblock { try account?.unblockContact(contactId: contact.id) } else { try account?.blockContact(contactId: contact.id) }
            }
            model.reloadContacts()
            if unblock { load() } else { model.chatsPath = [] }
        }
    }

    private func clear(_ chat: Chat) {
        let count = account?.messages(chatId: chat.id, before: nil, limit: 100_000).count ?? 0
        ActionSheet.present(title: LPlural("ask_delete_messages", count), actions: [], destructive: (L("clear_chat"), {
            model.perform("clear chat \(chat.id)") { try account?.clearChat(chatId: chat.id) }
            load()
        }))
    }

    private func delete(_ chat: Chat) {
        ActionSheet.present(title: L("ask_delete_named_chat", name), actions: [], destructive: (L("menu_delete_chat"), {
            model.perform("delete chat \(chat.id)") { try account?.deleteChat(chatId: chat.id) }
            model.chatsPath = []
        }))
    }
}

struct CircleButton: View {
    let system: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: system)
                .font(.system(size: 16))
                .foregroundColor(.blue)
                .frame(width: 40, height: 40)
                .overlay(Circle().stroke(Color(.systemGray4), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

struct EditNameView: View {
    let contact: Contact
    let onSave: () -> Void
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField(L("edit_name_placeholder", contact.name), text: $name)
                } footer: {
                    Text(L("edit_name_explain", contact.name))
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(L("menu_edit_name"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("save")) {
                        model.perform("rename contact \(contact.id)") {
                            try model.account?.setContactName(contactId: contact.id, name: name.trimmingCharacters(in: .whitespaces))
                        }
                        model.reloadContacts()
                        onSave()
                        dismiss()
                    }
                }
            }
            .onAppear { name = contact.name }
        }
        .presentationDetents([.medium])
    }
}

struct DisappearingView: View {
    let chatId: UInt32
    @EnvironmentObject var model: AppModel
    @State private var selected: UInt32 = 0

    private let options: [(UInt32, String)] = [
        (0, "off"), (60, "after_1_minute"), (300, "after_5_minutes"), (1800, "after_30_minutes"),
        (3600, "autodel_after_1_hour"), (86400, "autodel_after_1_day"), (604800, "autodel_after_1_week"), (3_024_000, "after_5_weeks")
    ]

    var body: some View {
        List {
            Section {
                ForEach(options, id: \.0) { option in
                    Button { selected = option.0 } label: {
                        HStack {
                            Text(L(option.1)).foregroundColor(.primary)
                            Spacer()
                            if selected == option.0 { Image(systemName: "checkmark").foregroundColor(.blue) }
                        }
                    }
                }
            } footer: {
                Text(L("ephemeral_messages_hint"))
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(L("ephemeral_messages"))
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button(L("cancel")) { model.chatsPath.removeLast() }
            }
            ToolbarItem(placement: .confirmationAction) {
                if #available(iOS 26.0, *) {
                    Button(L("ok"), role: .confirm) { apply() }.tint(.blue)
                } else {
                    Button(L("ok")) { apply() }
                }
            }
        }
        .onAppear { selected = (try? model.account?.chat(chatId: chatId))?.ephemeralSeconds ?? 0 }
    }

    private func apply() {
        guard let account = model.account else { return }
        let seconds = selected
        Task {
            do {
                try await account.setDisappearing(chatId: chatId, seconds: seconds)
                Log.chat.info("disappearing timer for chat \(chatId) set to \(seconds)")
            } catch {
                model.alert = AlertItem(title: coreMessage(error), message: nil, confirm: nil, action: nil)
            }
            if let index = model.chatsPath.lastIndex(of: .chat(chatId)) {
                model.chatsPath = Array(model.chatsPath.prefix(through: index))
            }
        }
    }
}
