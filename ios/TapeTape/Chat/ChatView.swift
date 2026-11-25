import SwiftUI
import UniformTypeIdentifiers
import TapeTapeCore

enum ChatRow: Identifiable {
    case day(Int64)
    case unread(Int)
    case message(Message)

    var id: String {
        switch self {
        case .day(let t): return "d\(t)"
        case .unread: return "unread"
        case .message(let m): return "m\(m.id)"
        }
    }
}

struct ChatView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var vm: ChatViewModel
    @State private var inputFocused = false
    @State private var lastVisible = true
    @State private var pickerFor: Message?
    @State private var reactionsFor: Message?
    @State private var share: ShareItem?
    @State private var searchTask: Task<Void, Never>?

    init(chatId: UInt32, account: Account?, draft: String?) {
        _vm = StateObject(wrappedValue: ChatViewModel(chatId: chatId, account: account, draft: draft))
    }

    private var chatTitle: String {
        guard let chat = vm.chat else { return "" }
        return model.chatName(chat.name, isSelf: chat.isSelf, isDevice: chat.isDevice)
    }

    private var subtitle: String? {
        guard let chat = vm.chat else { return nil }
        if chat.isSelf { return L("chat_self_talk_subtitle") }
        if chat.isDevice { return L("device_talk_subtitle") }
        if chat.isGroup { return LPlural("n_members", Int(chat.memberCount)) }
        return nil
    }

    private var emptyHint: String {
        guard let chat = vm.chat else { return L("chat_no_messages") }
        if chat.isSelf { return L("saved_messages_explain") }
        if chat.isDevice { return L("device_talk_explain") }
        if chat.isGroup { return L("chat_new_group_hint") }
        return L("chat_new_one_to_one_hint", chat.name)
    }

    private var rows: [ChatRow] {
        var result: [ChatRow] = []
        var lastDay: DateComponents?
        for m in vm.messages {
            let day = Calendar.current.dateComponents([.year, .month, .day], from: TimeFormat.date(m.time))
            if day != lastDay {
                result.append(.day(m.time))
                lastDay = day
            }
            if m.id == vm.unreadMarker { result.append(.unread(vm.unreadCount)) }
            result.append(.message(m))
        }
        return result
    }

    private var highlight: String { vm.searching ? vm.query : "" }

    var body: some View {
        ScrollViewReader { proxy in
            GeometryReader { geo in
                ScrollView {
                    LazyVStack(spacing: 3) {
                        if vm.messages.isEmpty {
                            SystemPill(text: emptyHint).padding(.horizontal, 30)
                        }
                        ForEach(rows) { row in
                            switch row {
                            case .day(let t):
                                SystemPill(text: TimeFormat.daySeparator(t), bold: true).padding(.vertical, 6)
                            case .unread(let count):
                                SystemPill(text: LPlural("chat_n_new_messages", count)).padding(.vertical, 6).id("unread")
                            case .message(let m):
                                BubbleRow(message: m, quoted: vm.quoted(m.replyTo), canReply: vm.chat?.canSend ?? false, selection: vm.selection, highlight: highlight, isGroup: vm.chat?.isGroup ?? false, actions: actions(proxy))
                                    .id("m\(m.id)")
                                    .padding(.vertical, vm.matches.indices.contains(vm.matchIndex) && vm.matches[vm.matchIndex] == m.id && vm.searching ? 4 : 0)
                                    .onAppear {
                                        guard m.id == vm.messages.last?.id else { return }
                                        lastVisible = true
                                        vm.markSeen()
                                    }
                                    .onDisappear { if m.id == vm.messages.last?.id { lastVisible = false } }
                            }
                        }
                    }
                    .padding(.vertical, 10)
                    .frame(minHeight: geo.size.height, alignment: .bottom)
                }
                .scrollDismissesKeyboard(.interactively)
                .environment(\.bubbleMaxWidth, geo.size.width - 56 - (vm.selection == nil ? 0 : 34))
            }
            .overlay(alignment: .bottomTrailing) {
                if !lastVisible && vm.selection == nil {
                    Button { scrollToBottom(proxy) } label: {
                        Image(systemName: "chevron.down")
                            .foregroundColor(.tapetapePrimary)
                            .frame(width: 40, height: 40)
                            .background(Circle().fill(Color.white))
                            .overlay(Circle().stroke(Color.tapetapeDisabled, lineWidth: 1))
                    }
                    .accessibilityLabel(L("menu_scroll_to_bottom"))
                    .padding(.trailing, 10)
                    .padding(.bottom, 12)
                }
            }
            .task {
                try? await Task.sleep(nanoseconds: 50_000_000)
                if vm.unreadMarker != nil {
                    proxy.scrollTo("unread", anchor: .top)
                } else {
                    scrollToBottom(proxy, animated: false)
                }
            }
            .onChange(of: vm.messages.last?.id) { _ in
                scrollToBottom(proxy)
                if lastVisible { vm.markSeen() }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification)) { _ in
                if lastVisible { scrollToBottom(proxy) }
            }
            .onChange(of: vm.matches) { _ in scrollToMatch(proxy) }
            .onChange(of: vm.matchIndex) { _ in scrollToMatch(proxy) }
        }
        .background {
            WallpaperBackground()
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { bottomBar }
        .onDrop(of: [.image, .movie, .url, .plainText, .item], isTargeted: nil) { providers in
            guard vm.chat?.canSend == true, vm.selection == nil, let provider = providers.first else { return false }
            handleDrop(provider)
            return true
        }
        .background(BackButtonTitle(unread: 0, title: L("chat")).frame(width: 0, height: 0))
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(vm.selection != nil || vm.searching)
        .toolbar(.hidden, for: .tabBar)
        .toolbar { toolbar }
        .sheet(item: $pickerFor) { m in
            EmojiPickerSheet(current: m.myReaction) { vm.react(m, $0) }
        }
        .sheet(item: $reactionsFor) { m in
            ReactionListSheet(messageId: m.id)
        }
        .sheet(item: $share) { ShareSheet(items: $0.items).presentationDetents([.medium, .large]) }
        .onAppear {
            Log.chat.info("chat \(vm.chatId) shown with \(vm.messages.count) messages")
            consumePendingSearch()
            consumePendingReply()
        }
        .onDisappear {
            vm.saveDraft()
            AudioPlayback.shared.stop()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .background { vm.saveDraft() }
            if phase == .active && lastVisible { vm.markSeen() }
        }
        .onChange(of: model.pendingChatSearch) { _ in consumePendingSearch() }
        .onChange(of: vm.errorText) { text in
            guard let text else { return }
            vm.errorText = nil
            model.alert = AlertItem(title: text, message: nil, confirm: nil, action: nil)
        }
        .onReceive(model.chatEvents.filter { [chatId = vm.chatId] in $0 == chatId }) { _ in vm.reload() }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if let selection = vm.selection {
            ToolbarItem(placement: .navigationBarLeading) {
                Button { vm.selection = nil } label: { Image(systemName: "xmark") }
                    .accessibilityLabel(L("cancel"))
            }
            ToolbarItem(placement: .principal) {
                Text(LPlural("n_selected", selection.count)).font(.headline)
            }
        } else if vm.searching {
            ToolbarItem(placement: .principal) {
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass").foregroundColor(.secondary)
                    AutoFocusTextField(placeholder: L("search"), text: $vm.query)
                        .onChange(of: vm.query) { _ in debounceSearch() }
                }
                .padding(.horizontal, 10)
                .frame(height: 36)
                .background(Capsule().fill(Color(.systemGray6)))
                .frame(width: 260)
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(L("cancel")) {
                    vm.searching = false
                    vm.query = ""
                    vm.matches = []
                }
            }
        } else {
            ToolbarItem(placement: .principal) {
                Button { openDetails() } label: {
                    VStack(spacing: 0) {
                        HStack(spacing: 4) {
                            Text(chatTitle).font(.system(size: 16, weight: .semibold)).foregroundColor(.primary).lineLimit(1)
                            if vm.chat?.verified == true || vm.chat?.isSelf == true { VerifiedBadge(size: 14.4) }
                        }
                        if let subtitle {
                            Text(subtitle).font(.system(size: 12)).foregroundColor(.secondary).lineLimit(1)
                        }
                    }
                    .fixedSize()
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                if let chat = vm.chat {
                    HStack(spacing: 4) {
                        if chat.muted { Image(systemName: "speaker.slash.fill").font(.system(size: 13)).foregroundColor(.middleGray) }
                        if chat.ephemeralSeconds > 0 { Image(systemName: "stopwatch").font(.system(size: 13)).foregroundColor(.middleGray) }
                        Button { openDetails() } label: {
                            ChatAvatar(name: chat.name, seed: model.contactAddress(chat.contactId) ?? chat.name, path: chat.avatarPath, size: 37, isSelf: chat.isSelf, isDevice: chat.isDevice)
                        }
                        .accessibilityLabel(L("menu_view_profile"))
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var bottomBar: some View {
        if vm.selection != nil {
            SelectionBar(vm: vm, share: { share = $0 }, myName: model.info?.name ?? L("self"))
        } else if vm.searching {
            HStack(spacing: 24) {
                Button { vm.stepMatch(-1) } label: { Image(systemName: "chevron.up") }
                Button { vm.stepMatch(1) } label: { Image(systemName: "chevron.down") }
                Spacer()
                Text(vm.matches.isEmpty ? "0/0" : "\(vm.matchIndex + 1)/\(vm.matches.count)").foregroundColor(.secondary)
            }
            .font(.title3)
            .disabled(vm.matches.isEmpty)
            .padding(.horizontal, 20)
            .frame(height: 50)
            .background(.bar)
        } else if let chat = vm.chat, chat.isRequest {
            HStack {
                Button(L("accept")) { vm.accept() }.frame(maxWidth: .infinity)
                Button(L(chat.isGroup ? "delete" : "block"), role: .destructive) { blockOrDelete(chat) }.frame(maxWidth: .infinity)
            }
            .font(.body.weight(.semibold))
            .frame(height: 50)
            .background(.bar)
        } else if vm.chat?.canSend ?? false {
            InputBar(vm: vm, focused: $inputFocused)
        }
    }

    private func actions(_ proxy: ScrollViewProxy) -> BubbleActions {
        BubbleActions(
            reply: { vm.replyTo = $0; inputFocused = true },
            quoteTap: { id in withAnimation { proxy.scrollTo("m\(id)", anchor: .center) } },
            react: { vm.react($0, $1) },
            pickReaction: { pickerFor = $0 },
            showReactions: { reactionsFor = $0 },
            forward: { model.startForwarding([$0.id]) },
            toggleSaved: { vm.toggleSaved($0) },
            moreOptions: { vm.selection = [$0.id] },
            openMedia: { openMedia($0) },
            toggleSelect: { id in
                vm.toggleSelection(id)
                if vm.selection?.isEmpty == true { vm.selection = nil }
            },
            goToOriginal: { m in
                guard let original = m.originalId, let msg = try? vm.account?.message(msgId: original) else { return }
                model.openChat(msg.chatId)
            },
            command: { cmd in if vm.text.isEmpty { vm.text = cmd } },
            replyPrivately: { replyPrivately($0) },
            openSender: { model.openContactProfile($0) }
        )
    }

    private func openMedia(_ m: Message) {
        guard let path = m.filePath else { return }
        if m.kind == .file {
            MediaViewerController.present(urls: [URL(fileURLWithPath: path)], index: 0)
            return
        }
        let items = vm.mediaURLs()
        MediaViewerController.present(urls: items.map(\.url), index: items.firstIndex { $0.id == m.id } ?? 0)
    }

    private func openDetails() {
        guard vm.selection == nil else { return }
        Keyboard.dismiss()
        model.chatsPath.append(.chatDetails(vm.chatId))
    }

    private func blockOrDelete(_ chat: Chat) {
        if chat.isGroup {
            model.perform("delete request chat") { try vm.account?.deleteChat(chatId: chat.id) }
        } else if let contactId = chat.contactId {
            model.perform("block request contact") { try vm.account?.blockContact(contactId: contactId) }
        }
        model.chatsPath = []
    }

    private func handleDrop(_ provider: NSItemProvider) {
        if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) && !provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                guard let url else { return }
                Task { @MainActor in insertDropped(url.absoluteString) }
            }
        } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) && !provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            _ = provider.loadObject(ofClass: String.self) { text, _ in
                guard let text else { return }
                Task { @MainActor in insertDropped(text) }
            }
        } else {
            Task {
                guard let attachment = await Staging.dropped(provider) else {
                    Log.chat.error("drop could not be staged")
                    return
                }
                vm.attachment = attachment
                Log.chat.info("dropped \(String(describing: attachment.kind), privacy: .public) staged")
            }
        }
    }

    private func insertDropped(_ text: String) {
        vm.text = vm.text.isEmpty ? text : vm.text + " " + text
        Log.chat.info("dropped text inserted")
    }

    private func replyPrivately(_ m: Message) {
        guard let sender = m.fromContact, let account = vm.account else { return }
        model.perform("reply privately to message \(m.id)") {
            let chatId = try account.chatForContact(contactId: sender)
            model.pendingReply = (chatId, m.id)
            model.reloadChats()
            model.openChat(chatId)
        }
    }

    private func consumePendingReply() {
        guard let pending = model.pendingReply, pending.chatId == vm.chatId else { return }
        model.pendingReply = nil
        vm.replyTo = try? vm.account?.message(msgId: pending.msgId)
        inputFocused = true
    }

    private func consumePendingSearch() {
        guard model.pendingChatSearch == vm.chatId else { return }
        model.pendingChatSearch = nil
        vm.searching = true
    }

    private func debounceSearch() {
        searchTask?.cancel()
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 250_000_000)
            guard !Task.isCancelled else { return }
            vm.runSearch()
        }
    }

    private func scrollToMatch(_ proxy: ScrollViewProxy) {
        guard vm.matches.indices.contains(vm.matchIndex) else { return }
        withAnimation { proxy.scrollTo("m\(vm.matches[vm.matchIndex])", anchor: .center) }
    }

    private func scrollToBottom(_ proxy: ScrollViewProxy, animated: Bool = true) {
        guard let last = vm.messages.last else { return }
        if animated {
            withAnimation { proxy.scrollTo("m\(last.id)", anchor: .bottom) }
        } else {
            proxy.scrollTo("m\(last.id)", anchor: .bottom)
        }
    }
}

struct SelectionBar: View {
    @ObservedObject var vm: ChatViewModel
    let share: (ShareItem) -> Void
    let myName: String
    @EnvironmentObject var model: AppModel

    private var selected: [Message] { vm.selectedMessages }
    private var empty: Bool { selected.isEmpty }

    var body: some View {
        HStack {
            Button { model.startForwarding(selected.map(\.id)) } label: { Image(systemName: "arrowshape.forward.fill") }
                .accessibilityLabel(L("forward"))
            Spacer()
            Button { vm.copySelected(myName: myName) } label: { Image(systemName: "doc.on.doc") }
                .accessibilityLabel(L("global_menu_edit_copy_desktop"))
            Spacer()
            Button { confirmDelete() } label: { Image(systemName: "trash").foregroundColor(empty ? nil : .red) }
                .accessibilityLabel(L("delete"))
            Spacer()
            Menu {
                if !empty && selected.allSatisfy(\.outgoing) {
                    Button { vm.resendSelected() } label: { Label(L("resend"), systemImage: "paperplane") }
                }
                if selected.count == 1, let m = selected.first, m.hasFile, let path = m.filePath {
                    Button { share(ShareItem(items: [URL(fileURLWithPath: path)])) } label: { Label(L("menu_share"), systemImage: "square.and.arrow.up") }
                }
            } label: { Image(systemName: "ellipsis.circle") }
                .accessibilityLabel(L("menu_more_options"))
        }
        .font(.title3)
        .foregroundColor(.tapetapePrimary)
        .disabled(empty)
        .padding(.horizontal, 28)
        .frame(height: 50)
        .background(.bar)
    }

    private func confirmDelete() {
        ActionSheet.present(title: LPlural("ask_delete_messages", selected.count), actions: [], destructive: (L("delete"), { vm.deleteSelected() }))
    }
}
