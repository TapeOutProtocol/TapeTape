import SwiftUI
import TapeTapeCore

struct ContactListView: View {
    let isTab: Bool
    @EnvironmentObject var model: AppModel
    @State private var search = ""
    @State private var share: ShareItem?

    private var showMe: Bool {
        let q = search.lowercased()
        return q.isEmpty || L("self").lowercased().contains(q)
    }

    private var filtered: [Contact] {
        let q = search.lowercased()
        guard !q.isEmpty else { return model.contacts }
        return model.contacts.filter { $0.name.lowercased().contains(q) || $0.address.lowercased().contains(q) }
    }

    var body: some View {
        List {
            if search.isEmpty {
                Section {
                    // 左对齐加图标（居中排两行字数不同的文字，配上缩进的分隔线会显得歪）
                    ListAction(L("menu_new_contact"), icon: "person.badge.plus") { model.push(.qr) }
                    ListAction(L("menu_new_group"), icon: "person.2") { model.push(.newGroup(clone: nil)) }
                }
            }
            if !filtered.isEmpty || showMe {
                Section {
                    if showMe, let info = model.info {
                        Button { model.openSavedMessages() } label: {
                            HStack(spacing: 12) {
                                AvatarView(name: info.name, seed: info.address, path: info.avatarPath, size: 54)
                                Text(L("self")).foregroundColor(.primary)
                                VerifiedBadge()
                                Spacer(minLength: 0)
                            }
                            .contentShape(Rectangle())
                        }
                        .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
                    }
                    ForEach(filtered) { contact in
                        Button {
                            if model.isForwarding && isTab { model.tab = .chats }
                            model.startChat(withContact: contact)
                        } label: { ContactRow(contact: contact) }
                            .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
                            .swipeActions(edge: .trailing) {
                                Button { confirmDelete(contact) } label: { SwipeLabel(title: L("delete"), system: "trash") }
                                    .tint(.red)
                                Button { model.openContactProfile(contact.id) } label: { SwipeLabel(title: L("profile"), system: "person") }
                            }
                    }
                }
            }
            if search.isEmpty {
                Section {
                    Button {
                        guard let link = model.account?.inviteLink() else { return }
                        share = ShareItem(items: [L("invite_friends_text", link)])
                    } label: {
                        Text(L("invite_friends")).foregroundColor(.tapetapePrimary).frame(maxWidth: .infinity)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .environment(\.defaultMinListRowHeight, 44)
        .overlay {
            if !search.isEmpty && filtered.isEmpty && !showMe {
                EmptyStatePill(text: L("search_no_result_for_x", search))
            }
        }
        .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always), prompt: L("search"))
        .navigationTitle(L("tab_contact"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(isTab ? .automatic : .hidden, for: .tabBar)
        .sheet(item: $share) { ShareSheet(items: $0.items).presentationDetents([.medium, .large]) }
    }

    private func confirmDelete(_ contact: Contact) {
        ActionSheet.present(title: L("ask_delete_contact", contact.name), actions: [], destructive: (L("delete"), {
            model.perform("delete contact \(contact.id)") { try model.account?.deleteContact(contactId: contact.id) }
            model.reloadContacts()
        }))
    }
}

struct ContactRow: View {
    let contact: Contact

    var body: some View {
        HStack(spacing: 12) {
            AvatarView(name: contact.name, seed: contact.address, path: contact.avatarPath, size: 54)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(contact.name).foregroundColor(.primary).lineLimit(1)
                    if contact.verified { VerifiedBadge() }
                }
                if !contact.verified {
                    Text(contact.address).font(.subheadline).foregroundColor(.middleGray).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
    }
}
