import SwiftUI
import TapeTapeCore

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @State private var share: ShareItem?

    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    }

    var body: some View {
        List {
            Section {
                if let info = model.info {
                    NavigationLink(value: Route.profile) {
                        HStack(spacing: 14) {
                            AvatarView(name: info.name, seed: info.address, path: info.avatarPath, size: 56)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(info.name.isEmpty ? L("pref_your_name") : info.name).font(.title3.weight(.semibold)).lineLimit(1)
                                Text(shortAddress(info.address)).font(.footnote).foregroundColor(.secondary).lineLimit(1)
                            }
                        }
                    }
                    .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
                }
            }
            Section {
                WalletSection()
            } header: {
                Text(L("wallet_assets"))
            }
            Section {
                SettingsLink(route: .chatsMedia, icon: "message", title: L("pref_chats_and_media"))
                SettingsLink(route: .notifications, icon: "bell", title: L("pref_notifications"), value: L(model.info?.muted == true ? "off" : "on"))
                SettingsLink(route: .wallpaper, icon: "photo", title: L("pref_background"))
                SettingsLink(route: .advanced, icon: "chevron.left.forwardslash.chevron.right", title: L("menu_advanced"))
            } header: {
                Text(L("v2_settings_general"))
            }
            Section {
                Button {
                    guard let link = model.account?.inviteLink() else { return }
                    share = ShareItem(items: [L("invite_friends_text", link)])
                } label: {
                    Label {
                        Text(L("invite_friends")).foregroundColor(.primary)
                    } icon: {
                        Image(systemName: "heart").foregroundColor(.blue)
                    }
                }
            } footer: {
                Text("TapeTape v\(version)")
            }
        }
        .listStyle(.insetGrouped)
        .environment(\.defaultMinListRowHeight, 44)
        .navigationTitle(L("menu_settings"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.refreshInfo() }
        .sheet(item: $share) { ShareSheet(items: $0.items).presentationDetents([.medium, .large]) }
    }
}

/// 0x755d…df3b
func shortAddress(_ address: String) -> String {
    let a = address.split(separator: "@").first.map(String.init) ?? address
    return a.count > 14 ? "\(a.prefix(6))…\(a.suffix(4))" : a
}

struct ProfileEditView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var avatarPath: String?
    @State private var bio = ""
    @State private var loaded = false
    @State private var showPicker = false
    @State private var saving = false

    private var canSave: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && !saving
    }

    var body: some View {
        List {
            Section {
                HStack {
                    Text(L("pref_your_name") + ":")
                    TextField(L("please_enter_name"), text: $name).multilineTextAlignment(.trailing)
                }
                .frame(minHeight: 44)
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("email_address") + ":")
                    Text((model.info?.address ?? "").map(String.init).joined(separator: "\u{200B}")).textSelection(.enabled)
                }
                .padding(.vertical, 8)
                Button { showPicker = true } label: {
                    HStack {
                        Text(L("pref_profile_photo")).foregroundColor(.primary)
                        Spacer()
                        if let avatarPath, let image = UIImage(contentsOfFile: avatarPath) {
                            Image(uiImage: image).resizable().scaledToFill().frame(width: 72, height: 72).clipShape(Circle())
                        } else {
                            CameraPlaceholder(size: 72)
                        }
                    }
                    .padding(.vertical, 8)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text(L("pref_default_status_label"))
                    TextField("", text: $bio, axis: .vertical).lineLimit(2...6)
                }
                .padding(.vertical, 6)
            } footer: {
                Text(L("pref_who_can_see_profile_explain"))
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(L("pref_profile_info_headline"))
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button { dismiss() } label: { Image(systemName: "xmark") }
            }
            ToolbarItem(placement: .confirmationAction) {
                if #available(iOS 26.0, *) {
                    Button(role: .confirm) { save() }.disabled(!canSave).tint(.blue)
                } else {
                    Button(L("done")) { save() }.disabled(!canSave)
                }
            }
        }
        .avatarPicker(isPresented: $showPicker, hasAvatar: avatarPath != nil) { avatarPath = $0 }
        .onAppear {
            guard !loaded, let info = model.info else { return }
            loaded = true
            name = info.name
            avatarPath = info.avatarPath
            bio = info.bio
        }
    }

    private func save() {
        guard let account = model.account else { return }
        saving = true
        Task {
            do {
                try await account.setProfile(name: name.trimmingCharacters(in: .whitespaces), avatarPath: avatarPath)
                let newBio = bio.trimmingCharacters(in: .whitespacesAndNewlines)
                if newBio != model.info?.bio { try await account.setBio(bio: newBio) }
                Log.app.info("profile updated")
                model.refreshInfo()
                dismiss()
            } catch {
                Log.app.error("profile update failed: \(coreMessage(error), privacy: .public)")
                model.alert = AlertItem(title: L("error"), message: coreMessage(error), confirm: nil, action: nil)
            }
            saving = false
        }
    }
}

struct BlockedContactsView: View {
    @EnvironmentObject var model: AppModel
    @State private var contacts: [Contact] = []

    var body: some View {
        List(contacts) { contact in
            Button {
                ActionSheet.present(title: L("ask_unblock_contact"), actions: [], destructive: (L("menu_unblock_contact"), { unblock(contact) }))
            } label: {
                ContactRow(contact: contact)
            }
        }
        .listStyle(.plain)
        .overlay {
            if contacts.isEmpty { EmptyStatePill(text: L("blocked_empty_hint")) }
        }
        .navigationTitle(L("pref_blocked_contacts"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: load)
    }

    private func load() {
        contacts = model.account?.blockedContacts() ?? []
    }

    private func unblock(_ contact: Contact) {
        model.perform("unblock contact \(contact.id)") { try model.account?.unblockContact(contactId: contact.id) }
        model.reloadContacts()
        load()
    }
}
