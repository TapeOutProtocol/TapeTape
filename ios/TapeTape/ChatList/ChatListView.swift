import SwiftUI
import TapeTapeCore

struct ChatListView: View {
    var archive = false
    @EnvironmentObject var model: AppModel
    @State private var search = ""
    @State private var results = SearchResults()
    @State private var searchTask: Task<Void, Never>?
    @State private var showSwitcher = false
    @State private var selection: Set<UInt32>?

    private var chats: [ChatSummary] { archive ? model.archivedChats : model.chats }
    private var forwarding: Bool { model.isForwarding && !archive }
    private var searching: Bool { !search.isEmpty }

    private var title: String {
        if archive { return L("chat_archived_label") }
        if forwarding { return L("forward_to") }
        switch model.connectivity {
        case .connected: return L("pref_chats")
        case .connecting: return L("connectivity_connecting")
        case .notConnected: return L("connectivity_not_connected")
        }
    }

    var body: some View {
        list
            .listStyle(.plain)
            .environment(\.defaultMinListRowHeight, 1)
            .overlay {
                if searching && results.isEmpty {
                    EmptyStatePill(text: L("search_no_result_for_x", search))
                } else if archive && chats.isEmpty {
                    EmptyStatePill(text: L("archive_empty_hint"))
                }
            }
            .background(BackButtonTitle(unread: model.totalUnread).frame(width: 0, height: 0))
            .background(CollapsedSearchBar().frame(width: 0, height: 0))
            .navigationTitle(archive ? L("chat_archived_label") : L("pref_chats"))
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarBackButtonHidden(selection != nil)
            .toolbar(selection != nil || archive ? .hidden : .automatic, for: .tabBar)
            .toolbar { toolbar }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if let selection { ChatSelectionBar(selection: selection, chats: chats) { self.selection = nil } }
            }
            .sheet(isPresented: $showSwitcher) { ProfileSwitcherView() }
            .onChange(of: search) { _ in debounceSearch() }
            .onChange(of: model.chats) { _ in prune() }
            .onChange(of: model.archivedChats) { _ in prune() }
    }

    @ViewBuilder
    private var list: some View {
        let content = List {
            if searching {
                searchSections
            } else {
                if !archive && !forwarding && selection == nil && !model.archivedChats.isEmpty {
                    Button { model.chatsPath.append(.archive) } label: {
                        ArchiveLinkRow(unread: model.archivedChats.reduce(0) { $0 + $1.unread })
                    }
                    .listRowInsets(EdgeInsets(top: 0, leading: 13.5, bottom: 0, trailing: 13.5))
                }
                ForEach(chats) { chat in
                    ChatListRow(chat: chat, archived: archive, selection: $selection, allowSelect: !forwarding)
                }
            }
        }
        if archive || selection != nil {
            content
        } else {
            content.searchable(text: $search, placement: .navigationBarDrawer(displayMode: .automatic), prompt: L("search"))
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if let selection {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { self.selection = nil } label: { Image(systemName: "xmark") }.accessibilityLabel(L("cancel"))
            }
            ToolbarItem(placement: .principal) {
                Text(LPlural("n_selected", selection.count)).font(.headline)
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(L("mark_as_read_short")) {
                    for chat in chats where selection.contains(chat.id) && chat.unread > 0 { model.account?.markRead(chatId: chat.id) }
                    Log.chat.info("marked \(selection.count) chats read")
                    self.selection = nil
                    model.reloadChats()
                }
                .disabled(!chats.contains { selection.contains($0.id) && $0.unread > 0 })
            }
        } else if archive {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(L("mark_as_read_short")) {
                    model.account?.markAllRead(archived: true)
                    model.reloadChats()
                }
                .disabled(!chats.contains { $0.unread > 0 })
            }
        } else if forwarding {
            ToolbarItem(placement: .navigationBarLeading) {
                Button(L("cancel")) { model.cancelForwarding() }
            }
            ToolbarItem(placement: .principal) {
                Text(title).font(.headline)
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { model.chatsPath.append(.newChat) } label: { Image(systemName: "square.and.pencil") }
                    .accessibilityLabel(L("menu_new_chat"))
            }
        } else {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { showSwitcher = true } label: {
                    if let info = model.info {
                        AvatarView(name: info.name, seed: info.address, path: info.avatarPath, size: 37)
                            .overlay(alignment: .topTrailing) {
                                let others = model.accounts.filter { $0.id != info.id && !$0.muted }.reduce(0) { $0 + Int($1.unread) }
                                if others > 0 {
                                    Text("\(others)")
                                        .font(.system(size: 11, weight: .semibold))
                                        .foregroundColor(.white)
                                        .padding(.horizontal, 5)
                                        .frame(minWidth: 18, minHeight: 18)
                                        .background(Capsule().fill(Color(hex: 0x3792FC)))
                                        .offset(x: 6, y: -4)
                                }
                            }
                    }
                }
                .accessibilityLabel(L("switch_account"))
            }
            ToolbarItem(placement: .principal) {
                Button { model.chatsPath.append(.connectivity) } label: {
                    Text(title).font(.headline).foregroundColor(.primary)
                }
                .accessibilityIdentifier("chats-title")
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                HStack {
                    if !model.proxy.proxies.isEmpty {
                        ProxyToolbarButton { model.chatsPath.append(.proxy) }
                    }
                    Button { model.chatsPath.append(.newChat) } label: { Image(systemName: "square.and.pencil") }
                        .accessibilityLabel(L("menu_new_chat"))
                }
            }
        }
    }

    @ViewBuilder
    private var searchSections: some View {
        if !results.chats.isEmpty {
            Section(LPlural("n_chats", results.chats.count)) {
                ForEach(results.chats) { ChatListRow(chat: $0, archived: $0.archived, selection: .constant(nil), allowSelect: false, bold: search) }
            }
        }
        if !results.contacts.isEmpty {
            Section(LPlural("n_contacts", results.contacts.count)) {
                ForEach(results.contacts) { contact in
                    Button { model.startChat(withContact: contact) } label: {
                        HStack(spacing: 10) {
                            AvatarView(name: contact.name, seed: contact.address, path: contact.avatarPath, size: 54)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(contact.name).font(.body.bold()).foregroundColor(.primary).lineLimit(1)
                                Text(contact.address).font(.subheadline).foregroundColor(.middleGray).lineLimit(1)
                            }
                        }
                        .frame(minHeight: 74.5)
                    }
                    .listRowInsets(EdgeInsets(top: 0, leading: 13.5, bottom: 0, trailing: 13.5))
                }
            }
        }
        if !results.messages.isEmpty {
            Section(results.messages.count >= 1000 ? L("n_messages", "1000+") : LPlural("n_messages", results.messages.count)) {
                ForEach(results.messages) { m in
                    Button {
                        if forwarding, let chat = model.chats.first(where: { $0.id == m.chatId }) { model.forward(to: chat) } else { model.openChat(m.chatId) }
                    } label: {
                        MessageResultRow(message: m, chat: model.chats.first { $0.id == m.chatId } ?? model.archivedChats.first { $0.id == m.chatId }, query: search)
                    }
                    .listRowInsets(EdgeInsets(top: 0, leading: 13.5, bottom: 0, trailing: 13.5))
                }
            }
        }
    }

    private func debounceSearch() {
        searchTask?.cancel()
        let q = search.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty, let account = model.account else {
            results = SearchResults()
            return
        }
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 100_000_000)
            guard !Task.isCancelled else { return }
            let lower = q.lowercased()
            var r = SearchResults()
            r.chats = account.chats(query: q)
            let chatContactIds = Set(r.chats.compactMap { model.existingChatContact($0.id) })
            r.contacts = model.contacts.filter { !chatContactIds.contains($0.id) && ($0.name.lowercased().contains(lower) || $0.address.lowercased().contains(lower)) }
            if q.count >= 2 { r.messages = Array(account.searchMessages(chatId: nil, query: q).reversed().prefix(1000)) }
            results = r
            Log.chat.debug("search: \(r.chats.count) chats, \(r.contacts.count) contacts, \(r.messages.count) messages")
        }
    }

    private func prune() {
        guard let current = selection else { return }
        let ids = Set(chats.map(\.id))
        let remaining = current.intersection(ids)
        selection = remaining.isEmpty ? nil : remaining
    }
}

struct SearchResults {
    var chats: [ChatSummary] = []
    var contacts: [Contact] = []
    var messages: [Message] = []
    var isEmpty: Bool { chats.isEmpty && contacts.isEmpty && messages.isEmpty }
}

struct MessageResultRow: View {
    let message: Message
    let chat: ChatSummary?
    let query: String
    @EnvironmentObject var model: AppModel

    var body: some View {
        HStack(spacing: 10) {
            if let chat {
                ChatAvatar(name: chat.name, seed: model.avatarSeed(for: chat), path: chat.avatarPath, size: 54, isSelf: chat.isSelf, isDevice: chat.isDevice)
            }
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(chat.map { model.chatName($0.name, isSelf: $0.isSelf, isDevice: $0.isDevice) } ?? "").font(.body.weight(.medium)).foregroundColor(.primary).lineLimit(1)
                    Spacer(minLength: 4)
                    Text(TimeFormat.chatList(message.time)).font(.subheadline).foregroundColor(.middleGray)
                }
                Text(Highlight.bold(message.text, query)).font(.subheadline).foregroundColor(.middleGray).lineLimit(1)
            }
        }
        .frame(minHeight: 74.5)
    }
}

enum Highlight {
    static func bold(_ text: String, _ query: String) -> AttributedString {
        var result = AttributedString(text)
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return result }
        var start = result.startIndex
        while let range = result[start...].range(of: q, options: .caseInsensitive) {
            result[range].inlinePresentationIntent = .stronglyEmphasized
            start = range.upperBound
        }
        return result
    }
}

struct ArchiveLinkRow: View {
    let unread: UInt32

    var body: some View {
        HStack {
            Text(L("chat_archived_label")).font(.body.weight(.medium)).foregroundColor(.primary)
            Spacer()
            if unread > 0 { UnreadBadge(count: unread, muted: true) }
        }
        .frame(height: 74.5 * 0.7)
        .contentShape(Rectangle())
    }
}

struct SwipeLabel: View {
    let title: String
    let system: String

    var body: some View {
        Label {
            Text(title)
        } icon: {
            Image(uiImage: Self.render(title: title, system: system))
        }
        .labelStyle(.iconOnly)
        .accessibilityLabel(title)
    }

    static func render(title: String, system: String) -> UIImage {
        let size = CGSize(width: 50, height: 34)
        let symbol = UIImage(systemName: system, withConfiguration: UIImage.SymbolConfiguration(pointSize: 16, weight: .regular))?
            .withTintColor(.white, renderingMode: .alwaysOriginal)
        let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 12), .foregroundColor: UIColor.white]
        let text = title as NSString
        let textSize = text.size(withAttributes: attrs)
        return UIGraphicsImageRenderer(size: size).image { _ in
            if let symbol {
                symbol.draw(at: CGPoint(x: (size.width - symbol.size.width) / 2, y: 0))
            }
            text.draw(at: CGPoint(x: (size.width - textSize.width) / 2, y: size.height - textSize.height), withAttributes: attrs)
        }.withRenderingMode(.alwaysOriginal)
    }
}

struct ChatListRow: View {
    let chat: ChatSummary
    var archived = false
    @Binding var selection: Set<UInt32>?
    var allowSelect = true
    var bold: String?
    @EnvironmentObject var model: AppModel
    @Environment(\.isSearching) private var isSearching

    private var selected: Bool { selection?.contains(chat.id) == true }

    var body: some View {
        HStack(spacing: 10) {
            if selection != nil {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundColor(selected ? .blue : Color(.systemGray3))
            }
            ChatRowContent(chat: chat, seed: model.avatarSeed(for: chat), bold: bold, showArchivedTag: bold != nil && chat.archived)
        }
        .contentShape(Rectangle())
        .onTapGesture { tap() }
        .onLongPressGesture(minimumDuration: 0.45) {
            guard allowSelect, !isSearching, selection == nil, bold == nil else { return }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            selection = [chat.id]
            Log.chat.info("chat list selection started")
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .listRowInsets(EdgeInsets(top: 0, leading: 13.5, bottom: 0, trailing: 13.5))
        .listRowBackground(selected ? Color(.systemGray5) : (chat.pinned ? Color.pinnedRow : Color.white))
        .swipeActions(edge: .leading, allowsFullSwipe: chat.unread > 0) {
            if selection == nil && bold == nil && !model.isForwarding {
                if chat.unread > 0 {
                    Button {
                        Log.chat.info("mark chat \(chat.id) read")
                        model.account?.markRead(chatId: chat.id)
                        model.reloadChats()
                    } label: { SwipeLabel(title: L("mark_as_read_short"), system: "checkmark.message") }
                    .tint(.blue)
                }
                Button {
                    model.perform("pin chat \(chat.id)") { try model.account?.setPinned(chatId: chat.id, pinned: !chat.pinned) }
                } label: { SwipeLabel(title: L(chat.pinned ? "unpin" : "pin"), system: chat.pinned ? "pin.slash" : "pin") }
                .tint(.green)
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if selection == nil && !model.isForwarding {
                Button {
                    model.perform("archive chat \(chat.id)") { try model.account?.setArchived(chatId: chat.id, archived: !chat.archived) }
                } label: {
                    SwipeLabel(title: L(chat.archived ? "unarchive" : "archive"), system: chat.archived ? "tray.and.arrow.up" : "tray.and.arrow.down")
                }
                .tint(Color(hex: 0xAAAAAA))
                Button { ChatActions.toggleMute(chat, model: model) } label: {
                    SwipeLabel(title: L(chat.muted ? "menu_unmute" : "mute"), system: chat.muted ? "speaker.wave.2" : "speaker.slash")
                }
                .tint(.orange)
                if bold == nil {
                    Button { ChatActions.delete(chat, model: model) } label: { SwipeLabel(title: L("delete"), system: "trash") }
                        .tint(.red)
                }
            }
        }
    }

    private func tap() {
        if selection != nil {
            var s = selection ?? []
            if selected { s.remove(chat.id) } else { s.insert(chat.id) }
            selection = s.isEmpty ? nil : s
        } else if model.isForwarding {
            model.forward(to: chat)
        } else {
            model.chatsPath.append(.chat(chat.id))
        }
    }
}

enum ChatActions {
    @MainActor
    static func delete(_ chat: ChatSummary, model: AppModel) {
        let name = model.chatName(chat.name, isSelf: chat.isSelf, isDevice: chat.isDevice)
        ActionSheet.present(title: L("ask_delete_named_chat", name), actions: [], destructive: (L("menu_delete_chat"), {
            model.perform("delete chat \(chat.id)") { try model.account?.deleteChat(chatId: chat.id) }
        }))
    }

    @MainActor
    static func toggleMute(_ chat: ChatSummary, model: AppModel) {
        if chat.muted {
            model.perform("unmute chat \(chat.id)") { try model.account?.unmute(chatId: chat.id) }
        } else {
            MuteDialog.present { until in
                model.perform("mute chat \(chat.id)") { try model.account?.mute(chatId: chat.id, until: until) }
            }
        }
    }
}

struct ChatSelectionBar: View {
    let selection: Set<UInt32>
    let chats: [ChatSummary]
    let done: () -> Void
    @EnvironmentObject var model: AppModel

    private var selected: [ChatSummary] { chats.filter { selection.contains($0.id) } }
    private var allArchived: Bool { selected.allSatisfy(\.archived) }
    private var allPinned: Bool { selected.allSatisfy(\.pinned) }
    private var allMuted: Bool { selected.allSatisfy(\.muted) }

    var body: some View {
        HStack(spacing: 24) {
            Button {
                let archive = !allArchived
                model.perform("archive \(selected.count) chats") {
                    for chat in selected { try model.account?.setArchived(chatId: chat.id, archived: archive) }
                }
                done()
            } label: {
                Label(L(allArchived ? "unarchive" : "archive"), systemImage: allArchived ? "tray.and.arrow.up" : "tray.and.arrow.down")
            }
            Spacer()
            Button {
                let targets = selected
                ActionSheet.present(title: LPlural("ask_delete_chat", targets.count), actions: [], destructive: (L("delete"), {
                    model.perform("delete \(targets.count) chats") {
                        for chat in targets { try model.account?.deleteChat(chatId: chat.id) }
                    }
                    done()
                }))
            } label: { Image(systemName: "trash").foregroundColor(.red) }
                .accessibilityLabel(L("delete"))
            Menu {
                Button {
                    let pin = !allPinned
                    model.perform("pin \(selected.count) chats") {
                        for chat in selected { try model.account?.setPinned(chatId: chat.id, pinned: pin) }
                    }
                    done()
                } label: { Label(L(allPinned ? "unpin" : "pin"), systemImage: allPinned ? "pin.slash" : "pin") }
                Button {
                    let targets = selected
                    if allMuted {
                        model.perform("unmute \(targets.count) chats") {
                            for chat in targets { try model.account?.unmute(chatId: chat.id) }
                        }
                        done()
                    } else {
                        MuteDialog.present { until in
                            model.perform("mute \(targets.count) chats") {
                                for chat in targets { try model.account?.mute(chatId: chat.id, until: until) }
                            }
                            done()
                        }
                    }
                } label: { Label(L(allMuted ? "menu_unmute" : "mute"), systemImage: allMuted ? "speaker.wave.2" : "speaker.slash") }
            } label: { Image(systemName: "ellipsis.circle") }
                .accessibilityLabel(L("menu_more_options"))
        }
        .foregroundColor(.tapetapePrimary)
        .padding(.horizontal, 20)
        .frame(height: 50)
        .background(.ultraThinMaterial)
    }
}

struct ChatRowContent: View {
    let chat: ChatSummary
    let seed: String
    var bold: String?
    var showArchivedTag = false
    @EnvironmentObject var model: AppModel

    private var preview: String {
        if let draft = chat.draft, !draft.isEmpty { return "\(L("draft")): \(draft)" }
        if chat.lastKind == .system, let system = model.systemPreviews[chat.id] { return system }
        let text = chat.lastText ?? ""
        var body = text
        if let kind = chat.lastKind, let label = MessagePreview.label(kind) {
            body = text.isEmpty ? label : "\(MessagePreview.emoji(kind)) \(text)"
        }
        if body.isEmpty { return L("chat_no_messages") }
        if chat.lastFromMe && !chat.isSelf { return "\(L("self")): \(body)" }
        if chat.isGroup, chat.lastKind != .system, let sender = chat.lastSender, !sender.isEmpty { return "\(sender): \(body)" }
        return body
    }

    private var name: String { model.chatName(chat.name, isSelf: chat.isSelf, isDevice: chat.isDevice) }

    var body: some View {
        HStack(spacing: 10) {
            ChatAvatar(name: chat.name, seed: seed, path: chat.avatarPath, size: 54, isSelf: chat.isSelf, isDevice: chat.isDevice)
                .overlay(alignment: .bottomTrailing) {
                    if !chat.isGroup, let seen = chat.lastSeen, Date().timeIntervalSince(TimeFormat.date(seen)) < 600 {
                        Circle().fill(Color(hex: 0x34C759)).frame(width: 14, height: 14).overlay(Circle().stroke(Color.white, lineWidth: 2))
                    }
                }
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 4) {
                    Text(bold.map { Highlight.bold(name, $0) } ?? AttributedString(name))
                        .font(.body.weight(chat.unread > 0 ? .bold : .medium))
                        .foregroundColor(.primary)
                        .lineLimit(1)
                    if chat.isSelf { VerifiedBadge() }
                    Spacer(minLength: 4)
                    if chat.muted {
                        Image(systemName: "speaker.slash.fill").font(.system(size: 16)).foregroundColor(.middleGray)
                    }
                    Text(TimeFormat.chatList(chat.lastTime)).font(.subheadline).foregroundColor(.middleGray)
                    if chat.pinned {
                        Image(systemName: "pin.fill").font(.subheadline).foregroundColor(.middleGray)
                    }
                }
                HStack(spacing: 4) {
                    Text(preview).font(.subheadline).foregroundColor(.middleGray).lineLimit(1)
                    Spacer(minLength: 4)
                    if chat.isRequest { Tag(text: L("chat_request_label")) }
                    if showArchivedTag { Tag(text: L("chat_archived_label")) }
                    if chat.unread > 0 && !chat.isRequest {
                        UnreadBadge(count: chat.unread, muted: chat.muted)
                    } else if chat.unread == 0, !chat.archived, chat.lastFromMe, let state = MessageStateUI(chat.lastState) {
                        DeliveryIcon(state: state, tint: state == .read ? .statusOut : .middleGray)
                    }
                }
            }
        }
        .frame(minHeight: 74.5)
        .contentShape(Rectangle())
    }
}

struct Tag: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 14))
            .foregroundColor(.middleGray)
            .padding(.horizontal, 3)
            .overlay(RoundedRectangle(cornerRadius: 2).stroke(Color.middleGray, lineWidth: 1))
    }
}

struct ArchiveView: View {
    var body: some View {
        ChatListView(archive: true)
    }
}

struct ProfileSwitcherView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var tagging: AccountInfo?
    @State private var tag = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(model.accounts) { acc in
                        Button {
                            if acc.id != model.info?.id { model.switchAccount(acc.id) }
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { dismiss() }
                        } label: {
                            ProfileRow(account: acc, subtitle: subtitle(acc), current: acc.id == model.info?.id)
                        }
                        .accessibilityIdentifier("profile-\(acc.name)")
                        .contextMenu { menu(acc) }
                    }
                }
                Section {
                    Button {
                        dismiss()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { model.addingProfile = true }
                    } label: {
                        Text(L("add_account")).foregroundColor(.tapetapePrimary).frame(maxWidth: .infinity)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(L("switch_account"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                }
            }
            .alert(L("profile_tag"), isPresented: Binding(get: { tagging != nil }, set: { if !$0 { tagging = nil } })) {
                TextField(L("profile_tag_hint"), text: $tag)
                Button(L("cancel"), role: .cancel) {}
                Button(L("ok")) {
                    guard let acc = tagging else { return }
                    model.setTag(acc.id, tag.trimmingCharacters(in: .whitespaces))
                }
            } message: {
                Text(L("profile_tag_explain"))
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func subtitle(_ acc: AccountInfo) -> String {
        var parts: [String] = []
        let state = acc.id == model.info?.id ? model.connectivity : ((try? model.core.account(id: acc.id))?.connectivity() ?? .notConnected)
        switch state {
        case .connecting: parts.append(L("connectivity_connecting"))
        case .notConnected: parts.append(L("connectivity_not_connected"))
        case .connected: break
        }
        if !acc.tag.isEmpty { parts.append(acc.tag) }
        return parts.joined(separator: " · ")
    }

    @ViewBuilder
    private func menu(_ acc: AccountInfo) -> some View {
        Button { model.setAccountMuted(acc.id, !acc.muted) } label: {
            Label(L(acc.muted ? "menu_unmute" : "menu_mute"), systemImage: acc.muted ? "speaker.wave.2" : "speaker.slash")
        }
        Button {
            tag = acc.tag
            tagging = acc
        } label: { Label(L("profile_tag"), systemImage: "tag") }
        Button { model.moveAccountToTop(acc.id) } label: { Label(L("move_to_top"), systemImage: "arrow.up") }
        Divider()
        Button(role: .destructive) { confirmDelete(acc) } label: { Label(L("delete"), systemImage: "trash") }
    }

    private func confirmDelete(_ acc: AccountInfo) {
        ActionSheet.present(title: L("delete_account_ask"), actions: [], destructive: (L("delete_account"), {
            let name = acc.name.isEmpty ? acc.address : "\(acc.name) (\(acc.address))"
            let alert = UIAlertController(title: name, message: L("delete_account_explain_with_name", name), preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: L("cancel"), style: .cancel))
            alert.addAction(UIAlertAction(title: L("delete"), style: .destructive) { _ in model.deleteAccount(acc.id) })
            TopController.current?.present(alert, animated: true)
        }))
    }
}

struct ProfileRow: View {
    let account: AccountInfo
    let subtitle: String
    let current: Bool

    var body: some View {
        HStack(spacing: 12) {
            AvatarView(name: account.name, seed: account.address, path: account.avatarPath, size: 37)
                .overlay(alignment: .topTrailing) {
                    if account.unread > 0 {
                        Text("\(account.unread)")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 5)
                            .frame(minWidth: 18, minHeight: 18)
                            .background(Capsule().fill(Color(hex: account.muted ? 0xB6B6BB : 0x3792FC)))
                            .offset(x: 6, y: -4)
                    }
                }
            if account.muted {
                Image(systemName: "speaker.slash.fill").font(.system(size: 16)).foregroundColor(Color(hex: 0x848BA7))
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(account.name.isEmpty ? account.address : account.name).font(.body.bold()).foregroundColor(.primary).lineLimit(1)
                if !subtitle.isEmpty {
                    Text(subtitle).font(.subheadline).foregroundColor(.secondary).lineLimit(1)
                }
            }
            Spacer()
            if current {
                Image(systemName: "checkmark").foregroundColor(.blue)
            }
        }
        .frame(minHeight: 54)
    }
}
