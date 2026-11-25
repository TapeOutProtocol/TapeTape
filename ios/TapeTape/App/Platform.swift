import UIKit
import BackgroundTasks
import UserNotifications
import TapeTapeCore

enum AppGroup {
    static let id = "group.org.tapetape.v2"

    static var dataDir: URL {
        let legacy = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("tapetape-v2")
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id) else {
            Log.app.error("app group container unavailable, using app data dir")
            return legacy
        }
        let dir = container.appendingPathComponent("tapetape-v2")
        if !FileManager.default.fileExists(atPath: dir.path), FileManager.default.fileExists(atPath: legacy.path) {
            do {
                try FileManager.default.moveItem(at: legacy, to: dir)
                Log.app.info("data dir moved into app group")
            } catch {
                Log.app.error("moving data dir failed: \(error.localizedDescription, privacy: .public)")
            }
        }
        return dir
    }

    static func protect(_ dir: URL) {
        var url = dir
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        let protection = FileProtectionType.completeUntilFirstUserAuthentication
        do {
            try url.setResourceValues(values)
            var changed = 0
            let items = [dir] + (FileManager.default.enumerator(at: dir, includingPropertiesForKeys: nil)?.compactMap { $0 as? URL } ?? [])
            for item in items {
                let current = try? FileManager.default.attributesOfItem(atPath: item.path)[.protectionKey] as? FileProtectionType
                guard current != protection else { continue }
                try FileManager.default.setAttributes([.protectionKey: protection], ofItemAtPath: item.path)
                changed += 1
            }
            if changed > 0 {
                Log.app.info("data dir excluded from backup, protection set on \(changed) items")
            } else {
                Log.app.debug("data dir already protected")
            }
        } catch {
            Log.app.error("protecting data dir failed: \(error.localizedDescription, privacy: .public)")
        }
    }
}

@MainActor
enum Notifier {
    static func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            Log.app.info("notification permission granted: \(granted)")
        }
    }

    static func shouldShow(_ n: TapeTapeCore.Notification, core: Core) -> Bool {
        if !n.muted { return true }
        guard n.mention, let settings = (try? core.account(id: n.accountId))?.settings() else { return false }
        return settings.mentions
    }

    static func post(_ n: TapeTapeCore.Notification, core: Core) {
        guard shouldShow(n, core: core) else {
            Log.app.debug("notification for chat \(n.chatId) suppressed")
            return
        }
        let content = UNMutableNotificationContent()
        let preview = previewText(n)
        if let emoji = n.reaction {
            content.title = n.isGroup ? n.chatName : n.senderName
            content.body = L("reaction_by_other", n.senderName, emoji, n.text)
        } else if n.isGroup {
            content.title = n.chatName
            content.body = "\(n.senderName): \(preview)"
            content.sound = .default
        } else {
            content.title = n.senderName
            content.body = preview
            content.sound = .default
        }
        content.threadIdentifier = "\(n.accountId)-\(n.chatId)"
        content.userInfo = ["accountId": n.accountId, "chatId": n.chatId]
        content.badge = NSNumber(value: totalUnread(core))
        let request = UNNotificationRequest(identifier: "\(n.accountId)-\(n.chatId)-\(n.msgId)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
        Log.app.info("notification posted for chat \(n.chatId) of profile \(n.accountId)")
    }

    static func previewText(_ n: TapeTapeCore.Notification) -> String {
        guard let label = MessagePreview.label(n.kind) else { return n.text }
        return n.text.isEmpty ? label : "\(MessagePreview.emoji(n.kind)) \(n.text)"
    }

    static func totalUnread(_ core: Core) -> Int {
        core.accounts().filter { !$0.muted }.reduce(0) { $0 + Int($1.unread) }
    }

    static func updateBadge(_ core: Core) {
        let total = totalUnread(core)
        if #available(iOS 17.0, *) {
            UNUserNotificationCenter.current().setBadgeCount(total)
        } else {
            UIApplication.shared.applicationIconBadgeNumber = total
        }
    }

    static func clear(accountId: UInt32, chatId: UInt32?) {
        UNUserNotificationCenter.current().getDeliveredNotifications { delivered in
            let ids = delivered.filter {
                let info = $0.request.content.userInfo
                return info["accountId"] as? UInt32 == accountId && (chatId == nil || info["chatId"] as? UInt32 == chatId)
            }.map(\.request.identifier)
            guard !ids.isEmpty else { return }
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
            Log.app.debug("cleared \(ids.count) notifications")
        }
    }
}

enum BackgroundFetch {
    static let taskId = "org.tapetape.v2.refresh"
    private static let wakeupsKey = "bgWakeups"

    static var wakeups: [Date] {
        (UserDefaults.standard.array(forKey: wakeupsKey) as? [Double] ?? []).map(Date.init(timeIntervalSince1970:))
    }

    static func register(core: Core) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            handle(task as! BGAppRefreshTask, core: core)
        }
    }

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
            Log.app.debug("background refresh scheduled")
        } catch {
            Log.app.notice("background refresh not scheduled: \(error.localizedDescription, privacy: .public)")
        }
    }

    private static func handle(_ task: BGAppRefreshTask, core: Core) {
        schedule()
        let times = (wakeups + [Date()]).suffix(10).map(\.timeIntervalSince1970)
        UserDefaults.standard.set(Array(times), forKey: wakeupsKey)
        let work = Task { @MainActor in
            let result = await core.fetchAll()
            Log.app.info("background fetch: \(result.newMessages) new messages")
            for n in result.notifications { Notifier.post(n, core: core) }
            Notifier.updateBadge(core)
            task.setTaskCompleted(success: true)
        }
        task.expirationHandler = {
            work.cancel()
            Log.app.notice("background fetch expired")
        }
    }
}

@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    lazy var model = AppModel()

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        BackgroundFetch.register(core: model.core)
        return true
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.badge, .list]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let accountId = info["accountId"] as? UInt32, let chatId = info["chatId"] as? UInt32 else { return }
        Log.app.info("notification tapped for chat \(chatId) of profile \(accountId)")
        model.openFromNotification(accountId: accountId, chatId: chatId)
    }
}
