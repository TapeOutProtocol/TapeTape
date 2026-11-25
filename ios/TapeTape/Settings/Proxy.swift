import SwiftUI
import TapeTapeCore

extension ProxyInfo: @retroactive Identifiable {
    public var id: String { url }
}

extension ProxyKind {
    var label: String {
        switch self {
        case .http: return "HTTP"
        case .https: return "HTTPS"
        case .socks5: return "SOCKS5"
        case .shadowsocks: return "Shadowsocks"
        }
    }
}

struct ProxyView: View {
    @EnvironmentObject var model: AppModel
    @State private var adding = false
    @State private var link = ""
    @State private var sharing: ProxyInfo?

    private var settings: ProxySettings { model.proxy }

    var body: some View {
        List {
            Section {
                Toggle(L("proxy_use_proxy"), isOn: Binding(get: { settings.enabled }, set: { on in model.updateProxy("proxy enabled \(on)") { try $0.setProxyEnabled(enabled: on) } }))
                    .tint(.green)
                    .disabled(settings.proxies.isEmpty)
            }
            if !settings.proxies.isEmpty {
                Section(L("proxy_list_header")) {
                    ForEach(settings.proxies) { proxy in
                        Button { model.updateProxy("select proxy") { try $0.selectProxy(url: proxy.url) } } label: {
                            ProxyRow(proxy: proxy, active: settings.enabled && settings.active == proxy.url, state: model.connectivity)
                        }
                        .swipeActions(edge: .leading) {
                            Button { sharing = proxy } label: { SwipeLabel(title: L("menu_share"), system: "square.and.arrow.up") }
                                .tint(.green)
                        }
                        .swipeActions(edge: .trailing) {
                            Button { confirmDelete(proxy) } label: { SwipeLabel(title: L("delete"), system: "trash") }
                                .tint(.red)
                        }
                        .contextMenu {
                            Button { sharing = proxy } label: { Label(L("menu_share"), systemImage: "square.and.arrow.up") }
                            Divider()
                            Button(role: .destructive) { confirmDelete(proxy) } label: { Label(L("delete"), systemImage: "trash") }
                        }
                    }
                }
            }
            Section {
                Button {
                    link = ""
                    adding = true
                } label: {
                    Text(L("proxy_add")).foregroundColor(.tapetapePrimary).frame(maxWidth: .infinity)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(L("proxy_settings"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .alert(L("proxy_add"), isPresented: $adding) {
            TextField(L("proxy_add_url_hint"), text: $link)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button(L("cancel"), role: .cancel) {}
            Button(L("proxy_use_proxy")) { add() }
        } message: {
            Text(L("proxy_add_explain"))
        }
        .sheet(item: $sharing) { ProxyShareSheet(proxy: $0) }
    }

    private func add() {
        let value = link.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            _ = try model.core.parseProxy(link: value)
        } catch {
            Log.app.notice("proxy link rejected: \(coreMessage(error), privacy: .public)")
            model.alert = AlertItem(title: L("proxy_invalid"), message: nil, confirm: nil, action: nil)
            return
        }
        model.updateProxy("add proxy") { try $0.addProxy(link: value) }
    }

    private func confirmDelete(_ proxy: ProxyInfo) {
        ActionSheet.present(title: L("proxy_delete_explain", proxy.host), actions: [], destructive: (L("proxy_delete"), {
            model.updateProxy("remove proxy") { try $0.removeProxy(url: proxy.url) }
        }))
    }
}

struct ProxyRow: View {
    let proxy: ProxyInfo
    let active: Bool
    let state: Connectivity

    private var subtitle: String {
        guard active else { return proxy.kind.label }
        switch state {
        case .connected: return L("connectivity_connected")
        case .connecting: return L("connectivity_connecting")
        case .notConnected: return L("connectivity_not_connected")
        }
    }

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(proxy.host).foregroundColor(.primary).lineLimit(1)
                Text(subtitle).font(.subheadline).foregroundColor(.secondary)
            }
            Spacer()
            if active { Image(systemName: "checkmark").foregroundColor(.blue) }
        }
        .contentShape(Rectangle())
    }
}

struct ProxyShareSheet: View {
    let proxy: ProxyInfo
    @State private var share: ShareItem?

    var body: some View {
        VStack(spacing: 20) {
            if let image = QRCode.image(proxy.url) {
                Image(uiImage: image).interpolation(.none).resizable().frame(width: 220, height: 220)
            }
            Text(L("proxy_share_explain"))
                .font(.subheadline)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
            Button(L("proxy_share_link")) { share = ShareItem(items: [proxy.url]) }
                .foregroundColor(.tapetapePrimary)
        }
        .presentationDetents([.medium])
        .sheet(item: $share) { ShareSheet(items: $0.items) }
    }
}

struct ProxyToolbarButton: View {
    let action: () -> Void
    @EnvironmentObject var model: AppModel

    var body: some View {
        Button(action: action) {
            Image(systemName: model.proxy.enabled ? "checkmark.shield" : "shield")
        }
        .accessibilityLabel(L("proxy_settings"))
    }
}

enum ProxyPrompt {
    @MainActor
    static func present(_ info: ProxyInfo, link: String, model: AppModel) {
        let alert = UIAlertController(title: L("proxy_use_proxy_confirm", info.host), message: nil, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: L("cancel"), style: .cancel))
        alert.addAction(UIAlertAction(title: L("proxy_use_proxy"), style: .default) { _ in
            model.updateProxy("add proxy from link") { try $0.addProxy(link: link) }
        })
        if let url = URL(string: link), url.scheme?.hasPrefix("http") == true {
            alert.addAction(UIAlertAction(title: L("open"), style: .default) { _ in UIApplication.shared.open(url) })
        }
        TopController.current?.present(alert, animated: true)
    }
}
