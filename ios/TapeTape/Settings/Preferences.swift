import SwiftUI
import LocalAuthentication
import PhotosUI
import OSLog
import UserNotifications
import UniformTypeIdentifiers
import TapeTapeCore

struct SettingsLink: View {
    let route: Route
    let icon: String
    let title: String
    var value: String?

    var body: some View {
        NavigationLink(value: route) {
            HStack {
                Label {
                    Text(title)
                } icon: {
                    Image(systemName: icon).foregroundColor(Color(.darkGray))
                }
                Spacer()
                if let value { Text(value).foregroundColor(.secondary) }
            }
        }
    }
}

struct CheckRow: View {
    let title: String
    let checked: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundColor(.primary)
                Spacer()
                if checked { Image(systemName: "checkmark").foregroundColor(.blue) }
            }
        }
        .accessibilityAddTraits(checked ? .isSelected : [])
    }
}

enum DeleteOldOption {
    static let device: [(UInt32?, String)] = [
        (nil, "never"), (3600, "autodel_after_1_hour"), (86400, "autodel_after_1_day"),
        (604800, "autodel_after_1_week"), (3_024_000, "after_5_weeks"), (31_536_000, "autodel_after_1_year")
    ]

    static func label(_ seconds: UInt32?) -> String {
        L(device.first { $0.0 == seconds }?.1 ?? "never")
    }
}

extension AutoDownload {
    static let all: [AutoDownload] = [.all, .kib160, .kib640, .mib5, .mib25]

    var label: String {
        switch self {
        case .all: return L("pref_show_emails_all")
        case .kib160: return L("up_to_x_most_worse_quality_images", "160 KiB")
        case .kib640: return L("up_to_x_most_balanced_quality_images", "640 KiB")
        case .mib5: return L("up_to_x", "5 MiB")
        case .mib25: return L("up_to_x", "25 MiB")
        }
    }
}

extension MediaQuality {
    var label: String { L(self == .balanced ? "pref_outgoing_balanced" : "pref_outgoing_worse") }
}

struct ChatsMediaView: View {
    @EnvironmentObject var model: AppModel
    @State private var exporting = false

    var body: some View {
        List {
            if let settings = model.settings {
                Section {
                    NavigationLink(L("pref_blocked_contacts"), value: Route.blocked)
                    ValueLink(route: .mediaQuality, title: L("pref_outgoing_media_quality"), value: settings.mediaQuality.label)
                    ValueLink(route: .autoDownload, title: L("auto_download_messages"), value: settings.autoDownload.label)
                    ValueLink(route: .deleteOld, title: L("delete_old_messages"), value: L(settings.deleteDeviceAfter == nil ? "never" : "on"))
                    Toggle(L("pref_read_receipts"), isOn: Binding(get: { settings.readReceipts }, set: { value in model.updateSettings { $0.readReceipts = value } }))
                        .tint(.green)
                } footer: {
                    Text(L("pref_read_receipts_explain"))
                }
            }
            Section {
                Button(L("export_backup_desktop")) { BackupExport.start(model: model, exporting: $exporting) }
                    .foregroundColor(.tapetapePrimary)
                    .frame(maxWidth: .infinity)
            } footer: {
                Text(L("pref_backup_explain"))
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(L("pref_chats_and_media"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .overlay { if exporting { ProgressOverlay(text: L("one_moment")) } }
    }
}

struct ValueLink: View {
    let route: Route
    let title: String
    let value: String

    var body: some View {
        NavigationLink(value: route) {
            HStack(spacing: 12) {
                Text(title).layoutPriority(1)
                Spacer()
                Text(value).foregroundColor(.secondary).lineLimit(1)
            }
        }
    }
}

@MainActor
enum BackupExport {
    static func start(model: AppModel, exporting: Binding<Bool>) {
        DeviceAuth.run(reason: L("export_backup_desktop")) {
            ActionSheet.present(title: L("pref_backup_export_explain"), actions: [(L("pref_backup_export_start_button"), { run(model: model, exporting: exporting) })])
        }
    }

    private static func run(model: AppModel, exporting: Binding<Bool>) {
        guard let account = model.account else { return }
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        exporting.wrappedValue = true
        Task { @MainActor in
            do {
                let path = try await account.exportBackup(destDir: dir.path, passphrase: nil)
                Log.app.info("backup exported to \((path as NSString).lastPathComponent, privacy: .public)")
                model.alert = AlertItem(title: L("backup_successful"), message: L("backup_successful_explain_ios"), confirm: nil, action: nil)
            } catch {
                Log.app.error("backup export failed: \(coreMessage(error), privacy: .public)")
                model.alert = AlertItem(title: L("error"), message: coreMessage(error), confirm: nil, action: nil)
            }
            exporting.wrappedValue = false
        }
    }
}

enum DeviceAuth {
    static func run(reason: String, _ onSuccess: @escaping () -> Void) {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            Log.app.info("device has no passcode, auth skipped")
            onSuccess()
            return
        }
        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { ok, error in
            DispatchQueue.main.async {
                if ok {
                    onSuccess()
                } else {
                    Log.app.info("device auth cancelled: \(error?.localizedDescription ?? "", privacy: .public)")
                }
            }
        }
    }
}

struct MediaQualityView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        List {
            ForEach([MediaQuality.balanced, .worse], id: \.self) { quality in
                CheckRow(title: quality.label, checked: model.settings?.mediaQuality == quality) {
                    model.updateSettings { $0.mediaQuality = quality }
                    Staging.worseQuality = quality == .worse
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(L("pref_outgoing_media_quality"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct AutoDownloadView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        List {
            ForEach(AutoDownload.all, id: \.self) { option in
                CheckRow(title: option.label, checked: model.settings?.autoDownload == option) {
                    model.updateSettings { $0.autoDownload = option }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(L("auto_download_messages"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct DeleteOldView: View {
    @EnvironmentObject var model: AppModel
    @State private var choosing = false

    var body: some View {
        List {
            Section(L("autodel_device_title")) {
                Button { choosing = true } label: {
                    HStack {
                        Text(DeleteOldOption.label(model.settings?.deleteDeviceAfter)).foregroundColor(.primary)
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundColor(Color(.tertiaryLabel))
                    }
                }
                .accessibilityIdentifier("delete-device-row")
            }
            Section {
                Text(L("autodel_at_once"))
            } header: {
                Text(L("autodel_server_title"))
            } footer: {
                Text(L("autodel_server_enabled_hint"))
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(L("delete_old_messages"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $choosing) { DeleteOldOptionsView() }
    }
}

struct DeleteOldOptionsView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var selected: UInt32?

    var body: some View {
        NavigationStack {
            List {
                ForEach(DeleteOldOption.device, id: \.1) { option in
                    CheckRow(title: L(option.1), checked: selected == option.0) { selected = option.0 }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(L("autodel_device_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button(L("ok")) { apply() } }
            }
            .onAppear { selected = model.settings?.deleteDeviceAfter }
        }
    }

    private func apply() {
        guard let seconds = selected else {
            model.updateSettings { $0.deleteDeviceAfter = nil }
            dismiss()
            return
        }
        let count = model.account?.countMessagesOlderThan(seconds: seconds) ?? 0
        Log.app.info("delete old messages after \(seconds)s would remove \(count) messages")
        ActionSheet.present(title: L("autodel_device_ask", Int(count), DeleteOldOption.label(seconds)), actions: [], destructive: (L("autodel_confirm"), {
            model.updateSettings { $0.deleteDeviceAfter = seconds }
            dismiss()
        }))
    }
}

struct NotificationsView: View {
    @EnvironmentObject var model: AppModel

    private var enabled: Bool { model.info?.muted != true }

    var body: some View {
        List {
            Section {
                Toggle(L("pref_notifications"), isOn: Binding(get: { enabled }, set: { model.setNotifications($0) }))
                    .tint(.green)
                Toggle(L("pref_mention_notifications"), isOn: Binding(get: { model.settings?.mentions ?? true }, set: { value in model.updateSettings { $0.mentions = value } }))
                    .tint(.green)
                    .disabled(!enabled)
            } footer: {
                Text(L("pref_mention_notifications_explain"))
            }
            Section {
                Button(L("system_settings")) {
                    if let url = URL(string: UIApplication.openNotificationSettingsURLString) { UIApplication.shared.open(url) }
                }
                .foregroundColor(.tapetapePrimary)
            } footer: {
                Text(L("system_settings_notify_explain_ios"))
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(L("pref_notifications"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
    }
}

@MainActor
final class Wallpaper: ObservableObject {
    static let shared = Wallpaper()
    @Published private(set) var image: UIImage?

    private let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("wallpaper.jpg")

    init() {
        image = UIImage(contentsOfFile: url.path)
    }

    func set(_ new: UIImage?) {
        if let new, let data = new.jpegData(compressionQuality: 0.85) {
            try? data.write(to: url)
            image = UIImage(data: data)
            Log.app.info("custom wallpaper set")
        } else {
            try? FileManager.default.removeItem(at: url)
            image = nil
            Log.app.info("default wallpaper restored")
        }
    }
}

struct WallpaperBackground: View {
    @ObservedObject private var wallpaper = Wallpaper.shared

    var body: some View {
        Group {
            if let image = wallpaper.image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image("background_light").resizable().scaledToFill()
            }
        }
        .ignoresSafeArea()
    }
}

struct WallpaperView: View {
    @State private var item: PhotosPickerItem?
    @State private var picking = false

    var body: some View {
        WallpaperBackground()
            .blur(radius: 2)
            .clipped()
            .safeAreaInset(edge: .bottom, spacing: 0) {
                HStack(spacing: 12) {
                    Button { Wallpaper.shared.set(nil) } label: {
                        Text(L("pref_background_btn_default")).frame(maxWidth: .infinity)
                    }
                    Button { picking = true } label: {
                        Text(L("pref_background_btn_gallery")).frame(maxWidth: .infinity)
                    }
                }
                .foregroundColor(Color(.darkGray))
                .padding(.horizontal, 16)
                .frame(height: 56)
                .background(.ultraThinMaterial)
            }
            .navigationTitle(L("pref_background"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar(.hidden, for: .tabBar)
            .photosPicker(isPresented: $picking, selection: $item, matching: .images)
            .onChange(of: item) { item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                        Wallpaper.shared.set(image)
                    }
                    self.item = nil
                }
            }
    }
}

struct AdvancedView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        List {
            Section {
                NavigationLink(L("pref_view_log"), value: Route.log)
            }
            Section(L("pref_encryption")) {
                Button(L("v2_export_recovery_phrase")) { showRecoveryPhrase() }
                    .foregroundColor(.primary)
            }
            Section(L("pref_server")) {
                ValueLink(route: .proxy, title: L("proxy_settings"), value: model.proxy.enabled ? L("on") : "")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(L("menu_advanced"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
    }

    private func showRecoveryPhrase() {
        guard let account = model.account else { return }
        DeviceAuth.run(reason: L("v2_export_recovery_phrase")) {
            Log.app.info("recovery phrase shown")
            let phrase = account.mnemonic()
            let message = L("v2_recovery_phrase_message", phrase, account.walletAddress(), account.info().address)
            let alert = UIAlertController(title: L("v2_export_recovery_phrase"), message: message, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: L("v2_copy_recovery_phrase"), style: .default) { _ in
                UIPasteboard.general.setItems([[UTType.plainText.identifier: phrase]], options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(60)])
                Log.app.info("recovery phrase copied, expires in 60s")
            })
            alert.addAction(UIAlertAction(title: L("close"), style: .cancel))
            TopController.current?.present(alert, animated: true)
        }
    }
}

struct LogView: View {
    @State private var lines: [String] = []
    @State private var share: ShareItem?

    private var text: String { lines.joined(separator: "\n") }

    var body: some View {
        ScrollView {
            Text(text)
                .font(.system(size: 11, design: .monospaced))
                .frame(maxWidth: .infinity, alignment: .leading)
                .textSelection(.enabled)
                .padding(12)
        }
        .navigationTitle(L("pref_log_header"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button { share = ShareItem(items: [text]) } label: { Image(systemName: "square.and.arrow.up") }
                    .accessibilityLabel(L("menu_share"))
            }
        }
        .sheet(item: $share) { ShareSheet(items: $0.items) }
        .task { lines = await Self.load() }
    }

    static func load() async -> [String] {
        var result = [
            "This log may contain sensitive information. If you want to post it publicly you may examine and edit it beforehand.",
            "",
            "iOS \(UIDevice.current.systemVersion), TapeTape \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")",
            "Background refresh: \(UIApplication.shared.backgroundRefreshStatus == .available ? "available" : "unavailable")",
            ""
        ]
        do {
            let store = try OSLogStore(scope: .currentProcessIdentifier)
            let start = store.position(timeIntervalSinceLatestBoot: 0)
            let formatter = DateFormatter()
            formatter.dateFormat = "HH:mm:ss.SSS"
            for case let entry as OSLogEntryLog in try store.getEntries(at: start, matching: NSPredicate(format: "subsystem == %@", "org.tapetape.v2")) where entry.level.rawValue >= OSLogEntryLog.Level.info.rawValue {
                result.append("\(formatter.string(from: entry.date)) [\(entry.category)] \(entry.composedMessage)")
            }
        } catch {
            result.append("log unavailable: \(error.localizedDescription)")
        }
        return result
    }
}

struct ConnectivityView: View {
    @EnvironmentObject var model: AppModel
    @State private var notificationLine = ("", Color.gray)

    private var stateText: String {
        switch model.connectivity {
        case .connected: return L("connectivity_connected")
        case .connecting: return L("connectivity_connecting")
        case .notConnected: return L("connectivity_not_connected")
        }
    }

    private var stateColor: Color {
        switch model.connectivity {
        case .connected: return .green
        case .connecting: return .yellow
        case .notConnected: return .red
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                Text(L("incoming_messages")).font(.system(size: 17, weight: .bold)).padding(.bottom, 4)
                StatusDotRow(color: stateColor, label: model.info?.node ?? "", text: stateText)
                StatusDotRow(color: notificationLine.1, label: L("pref_notifications"), text: notificationLine.0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
        }
        .background(Color.white)
        .navigationTitle(L("connectivity"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .task { await refresh() }
        .onChange(of: model.connectivity) { _ in Task { await refresh() } }
        .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange)) { _ in Task { await refresh() } }
    }

    private func refresh() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        let wakeups = BackgroundFetch.wakeups
        if model.info?.muted == true {
            notificationLine = (L("notifications_disabled_in_app"), .gray)
        } else if settings.authorizationStatus == .denied {
            notificationLine = (L("disabled_in_system_settings"), .gray)
        } else if UIApplication.shared.backgroundRefreshStatus != .available {
            notificationLine = (L("bg_app_refresh_disabled"), .gray)
        } else if model.connectivity == .notConnected {
            notificationLine = (L("connectivity_not_connected"), .red)
        } else if ProcessInfo.processInfo.isLowPowerModeEnabled {
            notificationLine = (L("connectivity_low_power_mode"), .gray)
        } else if wakeups.isEmpty {
            notificationLine = (L("connectivity_connected"), .yellow)
        } else {
            let formatter = DateFormatter()
            formatter.timeStyle = .short
            var text = L("last_check_at", wakeups.suffix(3).map { formatter.string(from: $0) }.joined(separator: ", "))
            if wakeups.count > 1, let first = wakeups.first, let last = wakeups.last {
                let minutes = Int(last.timeIntervalSince(first) / 60) / (wakeups.count - 1)
                text += "\n" + (minutes >= 120 ? L("notifications_avg_hours", minutes / 60) : L("notifications_avg_minutes", minutes))
            }
            notificationLine = (text, .yellow)
        }
    }
}

struct StatusDotRow: View {
    let color: Color
    let label: String
    let text: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Circle().fill(color).frame(width: 10, height: 10)
            (Text(label + ": ").fontWeight(.semibold) + Text(text)).font(.system(size: 13))
        }
    }
}
