import SwiftUI
import UIKit
import UniformTypeIdentifiers
import TapeTapeCore

enum SetupMode: Hashable {
    case create
    case importing(String)
}

struct OnboardingFlow: View {
    let isAdding: Bool
    @State private var path: [SetupMode] = []

    var body: some View {
        NavigationStack(path: $path) {
            WelcomeView(isAdding: isAdding, path: $path)
                .navigationDestination(for: SetupMode.self) { ProfileSetupView(mode: $0, isAdding: isAdding) }
        }
    }
}

struct WelcomeView: View {
    let isAdding: Bool
    @Binding var path: [SetupMode]
    @EnvironmentObject var model: AppModel
    @State private var showImport = false
    @State private var showInvalid = false
    @State private var input = ""
    @State private var pickingBackup = false
    @State private var backup: (path: String, protection: BackupProtection)?
    @State private var secret = ""
    @State private var restoring = false
    @State private var restoreError: String?

    var body: some View {
        VStack(spacing: 15) {
            Spacer()
            Button {
                path.append(.create)
            } label: {
                Text(L("onboarding_create_instant_account"))
                    .foregroundColor(.tapetapePrimary)
                    .padding(.vertical, 8)
                    .padding(.horizontal, 15)
                    .background(RoundedRectangle(cornerRadius: 5).fill(Color.white))
            }
            Button {
                ActionSheet.present(title: L("onboarding_alternative_logins"), actions: [
                    (L("import_backup_title"), { pickingBackup = true }),
                    (L("import_mnemonic"), { showImport = true })
                ])
            } label: {
                Text(L("onboarding_alternative_logins")).foregroundColor(.white)
            }
        }
        .padding(.bottom, 133)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background {
            ZStack {
                Color.black
                Image("splash").resizable().scaledToFill()
            }
            .ignoresSafeArea()
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(isAdding ? L("add_account") : L("welcome_desktop")).font(.headline).foregroundColor(.white)
            }
            if isAdding {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("cancel")) { model.addingProfile = false }
                }
            }
        }
        .alert(L("import_mnemonic"), isPresented: $showImport) {
            TextField("", text: $input)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button(L("cancel"), role: .cancel) { input = "" }
            Button(L("next")) { submitImport() }
        } message: {
            Text(L("import_mnemonic_hint"))
        }
        .alert(L("import_mnemonic_invalid"), isPresented: $showInvalid) {
            Button(L("ok"), role: .cancel) {}
        }
        .fileImporter(isPresented: $pickingBackup, allowedContentTypes: [.data]) { result in
            if case .success(let url) = result { inspectBackup(url) }
        }
        .alert(L("import_backup_title"), isPresented: Binding(get: { backup != nil && !restoring }, set: { if !$0 { backup = nil } })) {
            if backup?.protection == .passphrase {
                SecureField("", text: $secret)
            } else {
                TextField("", text: $secret)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            Button(L("cancel"), role: .cancel) { secret = "" }
            Button(L("ok")) { restore() }
        } message: {
            Text(L(backup?.protection == .passphrase ? "v2_backup_passphrase" : "import_mnemonic_hint"))
        }
        .alert(L("import_backup_title"), isPresented: Binding(get: { restoreError != nil }, set: { if !$0 { restoreError = nil } })) {
            Button(L("ok"), role: .cancel) {}
        } message: {
            Text(restoreError ?? "")
        }
        .overlay { if restoring { ProgressOverlay(text: L("v2_restoring_backup")) } }
    }

    private func inspectBackup(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let copy = FileManager.default.temporaryDirectory.appendingPathComponent(url.lastPathComponent)
        do {
            try? FileManager.default.removeItem(at: copy)
            try FileManager.default.copyItem(at: url, to: copy)
            let protection = try model.core.backupProtection(path: copy.path)
            Log.onboarding.info("backup picked, protection \(String(describing: protection), privacy: .public)")
            backup = (copy.path, protection)
        } catch {
            Log.onboarding.error("backup unreadable: \(coreMessage(error), privacy: .public)")
            restoreError = coreMessage(error)
        }
    }

    private func restore() {
        guard let backup else { return }
        let value = backup.protection == .recoveryPhrase ? Mnemonic.normalize(secret) : secret
        secret = ""
        restoring = true
        Task {
            do {
                let id = try await model.core.importBackup(path: backup.path, secret: value)
                AppGroup.protect(model.dataDir)
                try model.core.selectAccount(id: id)
                Log.onboarding.info("backup restored as profile \(id)")
                try? FileManager.default.removeItem(atPath: backup.path)
                self.backup = nil
                restoring = false
                model.chatsPath = []
                model.tab = .chats
                model.loadSelectedAccount()
                model.addingProfile = false
            } catch {
                Log.onboarding.error("backup restore failed: \(coreMessage(error), privacy: .public)")
                self.backup = nil
                restoring = false
                restoreError = coreMessage(error)
            }
        }
    }

    private func submitImport() {
        let phrase = Mnemonic.normalize(input)
        input = ""
        guard Mnemonic.isValid(phrase) else {
            Log.onboarding.notice("import mnemonic rejected: invalid word count or checksum")
            showInvalid = true
            return
        }
        Log.onboarding.info("import mnemonic accepted")
        path.append(.importing(phrase))
    }
}

struct ProfileSetupView: View {
    let mode: SetupMode
    let isAdding: Bool
    @EnvironmentObject var model: AppModel
    @State private var name = ""
    @State private var avatarPath: String?
    @State private var nodes: [String]?
    @State private var node: String?
    @State private var busy: String?
    @State private var pendingMnemonic: String?
    @State private var failure: String?
    @State private var showAvatarPicker = false
    @State private var showProxy = false
    @FocusState private var nameFocused: Bool

    private var isImport: Bool {
        if case .importing = mode { return true }
        return false
    }

    private var canSubmit: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && nodes != nil && busy == nil
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                Button { showAvatarPicker = true } label: {
                    if let avatarPath, let image = UIImage(contentsOfFile: avatarPath) {
                        Image(uiImage: image).resizable().scaledToFill().frame(width: 100, height: 100).clipShape(Circle())
                    } else {
                        CameraPlaceholder(size: 100)
                    }
                }
                .padding(.top, 16)
                TextField(L("pref_your_name"), text: $name)
                    .multilineTextAlignment(.center)
                    .focused($nameFocused)
                    .padding(.vertical, 6)
                    .frame(minHeight: 34)
                    .background(RoundedRectangle(cornerRadius: 5).fill(Color(.secondarySystemBackground)))
                    .overlay(RoundedRectangle(cornerRadius: 5).stroke(Color(.systemGray4), lineWidth: 0.5))
                    .padding(.top, 16)
                nodeRow.padding(.top, 16)
                Text(L("set_name_and_avatar_explain"))
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 16)
                Button(action: submit) {
                    Text(isImport ? L("import_mnemonic") : L("instant_onboarding_create"))
                        .foregroundColor(.white)
                        .padding(.vertical, 8)
                        .padding(.horizontal, 15)
                        .background(RoundedRectangle(cornerRadius: 5).fill(canSubmit ? Color.tapetapePrimary : Color.gray.opacity(0.3)))
                }
                .disabled(!canSubmit)
                .padding(.top, 32)
            }
            .padding(.horizontal, 16)
        }
        .navigationBarTitleDisplayMode(.inline)
        .navigationTitle(L("pref_profile_info_headline"))
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                if model.proxy.proxies.isEmpty {
                    Menu {
                        Button { showProxy = true } label: { Label(L("proxy_use_proxy"), systemImage: "shield") }
                    } label: { Image(systemName: "ellipsis.circle") }
                } else {
                    ProxyToolbarButton { showProxy = true }
                }
            }
        }
        .sheet(isPresented: $showProxy) {
            NavigationStack {
                ProxyView()
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button { showProxy = false } label: { Image(systemName: "xmark") }
                        }
                    }
            }
        }
        .overlay { if let busy { ProgressOverlay(text: busy) } }
        .avatarPicker(isPresented: $showAvatarPicker, hasAvatar: avatarPath != nil) { avatarPath = $0 }
        .task {
            nameFocused = true
            await loadNodes()
        }
        .alert(L("v2_mnemonic_title"), isPresented: Binding(get: { pendingMnemonic != nil }, set: { if !$0 { pendingMnemonic = nil } }), presenting: pendingMnemonic) { mnemonic in
            Button(L("v2_mnemonic_copy_continue")) {
                UIPasteboard.general.setItems([[UTType.utf8PlainText.identifier: mnemonic]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(120)])
                create(mnemonic: mnemonic)
            }
            Button(L("cancel"), role: .cancel) {
                Log.onboarding.info("mnemonic backup cancelled")
            }
        } message: { mnemonic in
            Text(L("v2_mnemonic_message", mnemonic))
        }
        .alert(L("v2_create_failed"), isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } }), presenting: failure) { _ in
            Button(L("ok"), role: .cancel) {}
        } message: { Text($0) }
    }

    private var nodeRow: some View {
        Menu {
            Picker(selection: $node) {
                Text(L("automatic")).tag(String?.none)
                ForEach(nodes ?? [], id: \.self) { Text($0).tag(String?.some($0)) }
            } label: { EmptyView() }
        } label: {
            HStack(spacing: 12) {
                Text(L("pref_server")).foregroundColor(.primary)
                if nodes == nil {
                    Text(L("loading")).foregroundColor(.secondary)
                    ProgressView()
                } else {
                    Text(node ?? L("automatic")).foregroundColor(.primary).lineLimit(1)
                }
                Spacer()
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(.tapetapePrimary)
            }
            .frame(minHeight: 36)
        }
        .disabled(nodes == nil)
    }

    private func loadNodes() async {
        do {
            nodes = try await model.core.nodes().sorted()
            Log.onboarding.info("loaded \(nodes?.count ?? 0) nodes")
        } catch {
            Log.onboarding.error("node list failed, using fallback: \(coreMessage(error), privacy: .public)")
            nodes = (1...5).map { "relay\($0).taperelay.com" }
        }
    }

    private func submit() {
        nameFocused = false
        switch mode {
        case .create:
            busy = L("v2_generating_mnemonic")
            Task {
                let mnemonic = model.core.generateMnemonic()
                busy = nil
                Log.onboarding.info("mnemonic generated, awaiting backup confirmation")
                pendingMnemonic = mnemonic
            }
        case .importing(let mnemonic):
            create(mnemonic: mnemonic)
        }
    }

    private func create(mnemonic: String) {
        busy = isImport ? L("loading") : L("add_account")
        let profileName = name.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                Log.onboarding.info("adding profile, node \(node ?? "automatic", privacy: .public)")
                let id = try await model.core.addAccount(mnemonic: mnemonic, node: node)
                try model.core.selectAccount(id: id)
                let account = try model.core.account(id: id)
                try await account.setProfile(name: profileName, avatarPath: avatarPath)
                _ = try? account.addDeviceMessage(text: L("device_talk_welcome_message2"))
                Log.onboarding.info("profile \(id) created on \(account.info().node, privacy: .public)")
                busy = nil
                model.chatsPath = []
                model.tab = .chats
                model.loadSelectedAccount()
                model.addingProfile = false
            } catch {
                Log.onboarding.error("add profile failed: \(coreMessage(error), privacy: .public)")
                busy = nil
                failure = coreMessage(error)
            }
        }
    }
}
