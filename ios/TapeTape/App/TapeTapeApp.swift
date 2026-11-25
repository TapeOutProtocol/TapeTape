import SwiftUI
import TapeTapeCore

@main
struct TapeTapeApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(delegate.model)
                .tint(.tapetapePrimary)
                .preferredColorScheme(.light)
                .onOpenURL { delegate.model.openLink($0) }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    if let url = activity.webpageURL { delegate.model.openLink(url) }
                }
        }
        .onChange(of: scenePhase) { delegate.model.scenePhaseChanged($0) }
    }
}

struct RootView: View {
    @EnvironmentObject var model: AppModel
    #if DEBUG
    private static let gallery = CommandLine.arguments.contains("-TapeTapePacketGallery")
    #else
    private static let gallery = false
    #endif

    var body: some View {
        Group {
            if Self.gallery {
                #if DEBUG
                PacketGallery()
                #endif
            } else if model.account == nil {
                OnboardingFlow(isAdding: false)
            } else {
                MainTabView()
                    .fullScreenCover(isPresented: $model.addingProfile) {
                        OnboardingFlow(isAdding: true)
                    }
            }
        }
        .fullScreenCover(item: $model.packetCover) { cover in
            switch cover {
            case .claimRedPacket(let m): RedPacketClaimView(message: m)
            case .claimTransfer(let m): TransferClaimView(message: m)
            case .transferStatus(let m, let status): TransferStatusView(message: m, status: status)
            }
        }
        .appAlert($model.alert)
    }
}

struct MainTabView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        TabView(selection: $model.tab) {
            NavigationStack(path: $model.chatsPath) {
                ChatListView()
                    .navigationDestination(for: Route.self) { RouteView(route: $0) }
            }
            .tabItem { TabLabel(title: L("pref_chats"), image: UIImage(systemName: "message")) }
            .badge(model.totalUnread)
            .tag(MainTab.chats)

            NavigationStack(path: $model.contactPath) {
                ContactListView(isTab: true)
                    .navigationDestination(for: Route.self) { RouteView(route: $0) }
            }
            .tabItem { TabLabel(title: L("tab_contact"), image: UIImage(systemName: "person")) }
            .tag(MainTab.contact)

            SquareView()
                .tabItem { TabLabel(title: L("square_title"), image: UIImage(systemName: "safari")) }
                .tag(MainTab.square)

            NavigationStack(path: $model.settingsPath) {
                SettingsView()
                    .navigationDestination(for: Route.self) { RouteView(route: $0) }
            }
            .tabItem { TabLabel(title: L("menu_settings"), image: UIImage(systemName: "gear")) }
            .tag(MainTab.settings)
        }
    }
}

struct RouteView: View {
    let route: Route
    @EnvironmentObject var model: AppModel

    var body: some View {
        switch route {
        case .chat(let id): ChatView(chatId: id, account: model.account, draft: model.chats.first { $0.id == id }?.draft).id(id)
        case .newChat: ContactListView(isTab: false)
        case .qr: QRView()
        case .profile: ProfileEditView()
        case .archive: ArchiveView()
        case .chatDetails(let id):
            if (try? model.account?.chat(chatId: id))?.isGroup == true {
                GroupDetailsView(chatId: id)
            } else {
                ProfileView(chatId: id, contactId: nil)
            }
        case .contactProfile(let id): ProfileView(chatId: nil, contactId: id)
        case .allMedia(let id): AllMediaView(chatId: id)
        case .disappearing(let id): DisappearingView(chatId: id)
        case .chatsMedia: ChatsMediaView()
        case .blocked: BlockedContactsView()
        case .newGroup(let clone): NewGroupView(cloneFrom: clone)
        case .groupQR(let id): QRView(groupId: id)
        case .notifications: NotificationsView()
        case .wallpaper: WallpaperView()
        case .advanced: AdvancedView()
        case .mediaQuality: MediaQualityView()
        case .autoDownload: AutoDownloadView()
        case .deleteOld: DeleteOldView()
        case .log: LogView()
        case .connectivity: ConnectivityView()
        case .proxy: ProxyView()
        case .receive: ReceiveView()
        case .withdraw: WithdrawView()
        case .packetDetail(let id): PacketDetailView(msgId: id)
        case .shop(let url): ShopView(url: url)
        }
    }
}

struct TabLabel: View {
    let title: String
    let image: UIImage?

    var body: some View {
        Label {
            Text(title)
        } icon: {
            if let image { Image(uiImage: image.withRenderingMode(.alwaysTemplate)) }
        }
    }
}

struct BackButtonTitle: UIViewControllerRepresentable {
    let unread: Int
    var title = L("pref_chats")

    func makeUIViewController(context: Context) -> Controller {
        let controller = Controller()
        controller.title = title
        return controller
    }

    func updateUIViewController(_ controller: Controller, context: Context) {
        guard controller.unread != unread else { return }
        controller.unread = unread
        controller.apply()
    }

    final class Controller: UIViewController {
        var unread = -1

        override func didMove(toParent parent: UIViewController?) {
            super.didMove(toParent: parent)
            apply()
        }

        func apply() {
            guard let item = parent?.navigationItem else { return }
            if unread > 0 {
                let name = unread > 50 ? "circle.fill" : "\(unread).circle.fill"
                item.backBarButtonItem = UIBarButtonItem(image: UIImage(systemName: name), style: .plain, target: nil, action: nil)
            } else {
                item.backBarButtonItem = UIBarButtonItem(title: title, style: .plain, target: nil, action: nil)
            }
        }
    }
}
