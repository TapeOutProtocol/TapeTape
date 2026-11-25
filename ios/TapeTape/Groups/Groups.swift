import SwiftUI
import TapeTapeCore

struct NewGroupView: View {
    let cloneFrom: UInt32?
    @EnvironmentObject var model: AppModel
    @State private var name = ""
    @State private var avatarPath: String?
    @State private var members: [UInt32] = []
    @State private var loaded = false
    @State private var showPicker = false
    @State private var addingMembers = false
    @State private var creating = false

    private var canCreate: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty && !creating }

    var body: some View {
        List {
            Section {
                HStack {
                    Text(L("group_name") + ":")
                    TextField(L("name_desktop"), text: $name).multilineTextAlignment(.trailing)
                }
                .frame(minHeight: 44)
                GroupImageRow(path: avatarPath) { showPicker = true }
            }
            Section {
                Button {
                    Keyboard.dismiss()
                    addingMembers = true
                } label: {
                    Text(L("group_add_members")).foregroundColor(.tapetapePrimary).frame(maxWidth: .infinity)
                }
            }
            Section(LPlural("n_members", members.count + 1)) {
                SelfMemberRow(showAddress: true)
                ForEach(members, id: \.self) { id in
                    if let contact = model.contact(id) {
                        ContactRow(contact: contact)
                            .swipeActions(edge: .trailing) {
                                Button(L("remove_desktop"), role: .destructive) { members.removeAll { $0 == id } }.tint(.red)
                            }
                    }
                }
                .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
            }
        }
        .listStyle(.insetGrouped)
        .environment(\.defaultMinListRowHeight, 44)
        .navigationTitle(L("menu_new_group"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(L("create")) { create() }.disabled(!canCreate)
            }
        }
        .avatarPicker(isPresented: $showPicker, hasAvatar: avatarPath != nil) { avatarPath = $0 }
        .fullScreenCover(isPresented: $addingMembers) {
            AddMembersView(initial: Set(members)) { selected in
                members = members.filter(selected.contains) + model.contacts.map(\.id).filter { selected.contains($0) && !members.contains($0) }
            }
        }
        .onAppear(perform: prefill)
    }

    private func prefill() {
        guard !loaded else { return }
        loaded = true
        guard let cloneFrom, let info = try? model.account?.groupInfo(chatId: cloneFrom) else { return }
        name = info.name
        avatarPath = info.avatarPath
        members = info.members.compactMap(\.contactId)
        Log.chat.info("new group prefilled from chat \(cloneFrom) with \(self.members.count) members")
    }

    private func create() {
        guard let account = model.account else { return }
        creating = true
        let groupName = name.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                let chatId = try await account.createGroup(name: groupName, avatarPath: avatarPath, members: members)
                Log.chat.info("group chat \(chatId) created with \(members.count) members")
                model.reloadChats()
                model.openChat(chatId)
            } catch {
                Log.chat.error("create group failed: \(coreMessage(error), privacy: .public)")
                model.alert = AlertItem(title: coreMessage(error), message: nil, confirm: nil, action: nil)
            }
            creating = false
        }
    }
}

struct GroupImageRow: View {
    let path: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Text(L("group_avatar")).foregroundColor(.primary)
                Spacer()
                if let path, let image = UIImage(contentsOfFile: path) {
                    Image(uiImage: image).resizable().scaledToFill().frame(width: 72, height: 72).clipShape(Circle())
                } else {
                    CameraPlaceholder(size: 72)
                }
            }
            .padding(.vertical, 8)
        }
    }
}

struct SelfMemberRow: View {
    let showAddress: Bool
    @EnvironmentObject var model: AppModel

    var body: some View {
        if let info = model.info {
            HStack(spacing: 12) {
                AvatarView(name: info.name, seed: info.address, path: info.avatarPath, size: 54)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 4) {
                        Text(L("self")).foregroundColor(.primary)
                        VerifiedBadge()
                    }
                    if showAddress {
                        Text(info.address).font(.subheadline).foregroundColor(.middleGray).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
        }
    }
}

struct AddMembersView: View {
    let initial: Set<UInt32>
    let onDone: (Set<UInt32>) -> Void
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var selected: Set<UInt32> = []
    @State private var search = ""

    private var filtered: [Contact] {
        let q = search.lowercased()
        guard !q.isEmpty else { return model.contacts }
        return model.contacts.filter { $0.name.lowercased().contains(q) || $0.address.lowercased().contains(q) }
    }

    var body: some View {
        NavigationStack {
            List(filtered) { contact in
                Button {
                    if selected.contains(contact.id) { selected.remove(contact.id) } else { selected.insert(contact.id) }
                } label: {
                    HStack {
                        ContactRow(contact: contact)
                        if selected.contains(contact.id) {
                            Image(systemName: "checkmark").foregroundColor(.blue)
                        }
                    }
                }
                .accessibilityAddTraits(selected.contains(contact.id) ? .isSelected : [])
                .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
            }
            .listStyle(.plain)
            .overlay {
                if !search.isEmpty && filtered.isEmpty { EmptyStatePill(text: L("search_no_result_for_x", search)) }
            }
            .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always), prompt: L("search"))
            .navigationTitle(L("group_add_members"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }.accessibilityLabel(L("cancel"))
                }
                ToolbarItem(placement: .confirmationAction) {
                    if #available(iOS 26.0, *) {
                        Button(role: .confirm) {
                            onDone(selected)
                            dismiss()
                        }
                        .tint(.blue)
                        .accessibilityLabel(L("done"))
                    } else {
                        Button(L("done")) {
                            onDone(selected)
                            dismiss()
                        }
                    }
                }
            }
            .onAppear { selected = initial }
        }
    }
}

struct GroupDetailsView: View {
    let chatId: UInt32
    @EnvironmentObject var model: AppModel
    @State private var chat: Chat?
    @State private var info: GroupInfo?
    @State private var mediaCount = 0
    @State private var editing = false
    @State private var addingMembers = false

    private var account: Account? { model.account }
    private var others: [GroupMember] { info?.members.filter { $0.contactId != nil } ?? [] }
    private var isMember: Bool { info?.isMember ?? false }

    var body: some View {
        List {
            Section {
                header
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
            }
            Section {
                Button { if mediaCount > 0 { model.push(.allMedia(chatId)) } } label: {
                    DetailRow(icon: "photo.on.rectangle", title: L("media"), value: mediaCount > 0 ? "\(mediaCount)" : L("none"))
                }
                Button { model.push(.disappearing(chatId)) } label: {
                    DetailRow(icon: "stopwatch", title: L("ephemeral_messages"), value: L((chat?.ephemeralSeconds ?? 0) > 0 ? "on" : "off"))
                }
            }
            Section(LPlural("n_members", info?.members.count ?? 0)) {
                if isMember {
                    Button { addingMembers = true } label: {
                        Text(L("group_add_members")).foregroundColor(.tapetapePrimary).frame(maxWidth: .infinity)
                    }
                    Button { model.push(.groupQR(chatId)) } label: {
                        Text(L("qrshow_join_group_title")).foregroundColor(.tapetapePrimary).frame(maxWidth: .infinity)
                    }
                }
                ForEach(others, id: \.address) { member in
                    Button { if let id = member.contactId { model.openContactProfile(id) } } label: {
                        MemberRow(member: member)
                    }
                    .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
                    .swipeActions(edge: .trailing) {
                        if isMember {
                            Button(L("remove_desktop"), role: .destructive) { confirmRemove(member) }.tint(.red)
                        }
                    }
                }
                if isMember { SelfMemberRow(showAddress: false) }
            }
            Section { actions }
        }
        .listStyle(.insetGrouped)
        .environment(\.defaultMinListRowHeight, 44)
        .navigationTitle(L("tab_group"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(L("global_menu_edit_desktop")) { editing = true }.disabled(chat?.canSend != true)
            }
        }
        .fullScreenCover(isPresented: $editing) {
            if let info { GroupEditView(info: info) { load() } }
        }
        .fullScreenCover(isPresented: $addingMembers) {
            AddMembersView(initial: Set(others.compactMap(\.contactId))) { applyMembers($0) }
        }
        .onAppear(perform: load)
        .onReceive(model.$chats) { _ in load() }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button(action: showAvatar) {
                ChatAvatar(name: chat?.name ?? "", seed: chat?.name ?? "", path: chat?.avatarPath, size: 54)
            }
            .buttonStyle(.plain)
            HStack(spacing: 4) {
                Text(chat?.name ?? "").font(.body.weight(.semibold)).lineLimit(1)
                if chat?.verified == true { VerifiedBadge() }
            }
            Spacer(minLength: 4)
            if let chat {
                CircleButton(system: "magnifyingglass", label: L("search_in_chat")) {
                    model.pendingChatSearch = chat.id
                    popToChat()
                }
                CircleButton(system: chat.muted ? "speaker.slash" : "speaker.wave.2", label: L(chat.muted ? "menu_unmute" : "menu_mute")) { toggleMute(chat) }
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
            ListAction(L("clone_chat"), icon: "plus.square.on.square") { model.push(.newGroup(clone: chat.id)) }
            if isMember {
                ListAction(L("menu_leave_group"), icon: "rectangle.portrait.and.arrow.right", destructive: true) { confirmLeave() }
            }
            ListAction(L("clear_chat"), icon: "eraser", destructive: true) { clear(chat) }
            ListAction(L("menu_delete_chat"), icon: "trash", destructive: true) { delete(chat) }
        }
    }

    private func load() {
        guard let account else { return }
        chat = try? account.chat(chatId: chatId)
        info = try? account.groupInfo(chatId: chatId)
        mediaCount = account.mediaMessages(chatId: chatId, kinds: [.image, .gif, .video, .file, .audio, .voice]).count
    }

    private func showAvatar() {
        guard let path = chat?.avatarPath else { return }
        MediaViewerController.present(urls: [URL(fileURLWithPath: path)], index: 0)
    }

    private func popToChat() {
        if case .chatDetails = model.chatsPath.last { model.chatsPath.removeLast() }
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

    private func groupTask(_ what: String, _ work: @escaping (Account) async throws -> Void) {
        guard let account else { return }
        Task {
            do {
                try await work(account)
                Log.chat.info("\(what, privacy: .public) done")
            } catch {
                Log.chat.error("\(what, privacy: .public) failed: \(coreMessage(error), privacy: .public)")
                model.alert = AlertItem(title: coreMessage(error), message: nil, confirm: nil, action: nil)
            }
            model.reloadChats()
            load()
        }
    }

    private func applyMembers(_ selected: Set<UInt32>) {
        let current = Set(others.compactMap(\.contactId))
        let added = Array(selected.subtracting(current))
        let removed = Array(current.subtracting(selected))
        groupTask("update members of chat \(chatId): +\(added.count) -\(removed.count)") { account in
            if !added.isEmpty { try await account.addGroupMembers(chatId: chatId, contactIds: added) }
            for id in removed { try await account.removeGroupMember(chatId: chatId, contactId: id) }
        }
    }

    private func confirmRemove(_ member: GroupMember) {
        guard let id = member.contactId else { return }
        ActionSheet.present(title: L("ask_remove_members", member.name), actions: [], destructive: (L("remove_desktop"), {
            groupTask("remove member \(id) from chat \(chatId)") { try await $0.removeGroupMember(chatId: chatId, contactId: id) }
        }))
    }

    private func confirmLeave() {
        ActionSheet.present(title: L("ask_leave_group"), actions: [], destructive: (L("menu_leave_group"), {
            groupTask("leave group \(chatId)") { try await $0.leaveGroup(chatId: chatId) }
        }))
    }

    private func clear(_ chat: Chat) {
        let count = account?.messages(chatId: chat.id, before: nil, limit: 100_000).count ?? 0
        ActionSheet.present(title: LPlural("ask_delete_messages", count), actions: [], destructive: (L("clear_chat"), {
            model.perform("clear chat \(chat.id)") { try account?.clearChat(chatId: chat.id) }
            load()
        }))
    }

    private func delete(_ chat: Chat) {
        ActionSheet.present(title: L("ask_delete_named_chat", chat.name), actions: [], destructive: (L("menu_delete_chat"), {
            model.perform("delete chat \(chat.id)") { try account?.deleteChat(chatId: chat.id) }
            model.chatsPath = []
        }))
    }
}

struct DetailRow: View {
    let icon: String
    let title: String
    let value: String

    var body: some View {
        HStack {
            Label {
                Text(title).foregroundColor(.primary)
            } icon: {
                Image(systemName: icon).font(.system(size: 17)).foregroundColor(.blue)
            }
            Spacer()
            Text(value).foregroundColor(.secondary)
            Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundColor(Color(.tertiaryLabel))
        }
    }
}

struct MemberRow: View {
    let member: GroupMember

    var body: some View {
        HStack(spacing: 12) {
            AvatarView(name: member.name, seed: member.address, path: member.avatarPath, size: 54)
            VStack(alignment: .leading, spacing: 2) {
                Text(member.name).foregroundColor(.primary).lineLimit(1)
                Text(member.address).font(.subheadline).foregroundColor(.middleGray).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
    }
}

struct GroupEditView: View {
    let info: GroupInfo
    let onSave: () -> Void
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var avatarPath: String?
    @State private var showPicker = false
    @State private var saving = false

    private var changed: Bool {
        name.trimmingCharacters(in: .whitespaces) != info.name || avatarPath != info.avatarPath
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Text(L("group_name") + ":")
                        TextField(L("name_desktop"), text: $name).multilineTextAlignment(.trailing)
                    }
                    .frame(minHeight: 44)
                    GroupImageRow(path: avatarPath) { showPicker = true }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(L("menu_edit_group"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }.accessibilityLabel(L("cancel"))
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("save_desktop")) { save() }
                        .disabled(!changed || saving || name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .avatarPicker(isPresented: $showPicker, hasAvatar: avatarPath != nil) { avatarPath = $0 }
            .onAppear {
                name = info.name
                avatarPath = info.avatarPath
            }
        }
    }

    private func save() {
        guard let account = model.account else { return }
        Keyboard.dismiss()
        saving = true
        let newName = name.trimmingCharacters(in: .whitespaces)
        let newAvatar = avatarPath
        Task {
            do {
                if newName != info.name { try await account.setGroupName(chatId: info.chatId, name: newName) }
                if newAvatar != info.avatarPath { try await account.setGroupAvatar(chatId: info.chatId, avatarPath: newAvatar) }
                Log.chat.info("group \(info.chatId) edited")
                model.reloadChats()
                onSave()
                dismiss()
            } catch {
                Log.chat.error("edit group failed: \(coreMessage(error), privacy: .public)")
                model.alert = AlertItem(title: coreMessage(error), message: nil, confirm: nil, action: nil)
            }
            saving = false
        }
    }
}
