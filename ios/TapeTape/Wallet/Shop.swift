import SwiftUI
import WebKit
import TapeTapeCore

@MainActor
enum ShopFlow {
    static func open(model: AppModel) {
        guard let account = model.account else { return }
        Task {
            let progress = Alerts.progress(nil, L("loading"))
            do {
                let info = try await account.shopInfo()
                await Alerts.dismiss(progress)
                Log.app.info("shop info: ready \(info.ready)")
                if info.ready, let url = info.appUrl.flatMap(URL.init(string:)) {
                    model.push(.shop(url))
                    Task {
                        do {
                            _ = try await account.shopOpen()
                            Log.app.info("shop files synced")
                        } catch {
                            Log.app.notice("shop sync failed: \(coreMessage(error), privacy: .public)")
                        }
                    }
                } else {
                    askToOpen(info, account: account, model: model)
                }
            } catch {
                Log.app.error("shop info failed: \(coreMessage(error), privacy: .public)")
                await Alerts.dismiss(progress)
                Alerts.show(L("error"), coreMessage(error))
            }
        }
    }

    private static func askToOpen(_ info: ShopInfo, account: Account, model: AppModel) {
        let affordable = (Decimal(string: info.balance) ?? 0) >= (Decimal(string: info.openCost) ?? 0)
        let open = Alerts.action(L("shop_open_button")) { start(account: account, model: model) }
        open.isEnabled = affordable
        Alerts.show(L("shop_open_title"), L("shop_open_message", info.openCost, info.address, info.balance), actions: [
            Alerts.action(L("shop_copy_address")) {
                UIPasteboard.general.string = info.address
                Alerts.show(nil, L("copied_to_clipboard"))
            },
            open,
            Alerts.action(L("cancel"), style: .cancel),
        ])
    }

    private static func start(account: Account, model: AppModel) {
        Task {
            let progress = Alerts.progress(nil, L("shop_opening"))
            do {
                let info = try await account.shopOpen()
                await Alerts.dismiss(progress)
                guard let url = info.appUrl.flatMap(URL.init(string:)) else {
                    Log.app.error("shop opened without url")
                    Alerts.show(L("error"), nil)
                    return
                }
                Log.app.info("shop opened")
                model.push(.shop(url))
            } catch {
                Log.app.error("shop open failed: \(coreMessage(error), privacy: .public)")
                await Alerts.dismiss(progress)
                Alerts.show(L("error"), coreMessage(error))
            }
        }
    }
}

struct ShopView: View {
    let url: URL
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @StateObject private var state = ShopWebState()

    var body: some View {
        ShopWebView(url: url, state: state, model: model)
            .overlay { if !state.loaded { ProgressView().controlSize(.large) } }
            .background(Color.white)
            .navigationTitle(state.title.isEmpty ? L("sell") : state.title)
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .tabBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        if let web = state.webView, web.canGoBack { web.goBack() } else { dismiss() }
                    } label: {
                        HStack(spacing: 2) {
                            Image(systemName: "chevron.left")
                            Text(L("v2_back"))
                        }
                    }
                    .accessibilityIdentifier("shop-back")
                }
            }
    }
}

@MainActor
final class ShopWebState: ObservableObject {
    @Published var title = ""
    @Published var loaded = false
    weak var webView: WKWebView?
}

struct ShopWebView: UIViewRepresentable {
    let url: URL
    let state: ShopWebState
    let model: AppModel

    func makeCoordinator() -> Coordinator { Coordinator(url: url, state: state, model: model) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.userContentController.add(context.coordinator, name: "tapetapeShop")
        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        web.uiDelegate = context.coordinator
        context.coordinator.titleObservation = web.observe(\.title) { web, _ in
            Task { @MainActor in state.title = web.title ?? "" }
        }
        state.webView = web
        web.load(URLRequest(url: url))
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {}

    static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) {
        web.configuration.userContentController.removeScriptMessageHandler(forName: "tapetapeShop")
    }

    @MainActor
    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate {
        let hosts: Set<String>
        let state: ShopWebState
        let model: AppModel
        var titleObservation: NSKeyValueObservation?
        var rpcRetries = 0

        init(url: URL, state: ShopWebState, model: AppModel) {
            let host = url.host ?? ""
            hosts = [host, host.replacingOccurrences(of: "-open.", with: ".")]
            self.state = state
            self.model = model
        }

        private func trusted(_ url: URL?) -> Bool {
            guard let url, url.scheme == "https", let host = url.host else { return false }
            return hosts.contains(host)
        }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url else { return decisionHandler(.cancel) }
            if url.scheme == "mailto" {
                openChat(url)
                return decisionHandler(.cancel)
            }
            if trusted(url) || url.scheme == "about" || action.targetFrame?.isMainFrame == false && trusted(webView.url) {
                return decisionHandler(.allow)
            }
            Log.app.info("shop link opened outside: \(url.host ?? "", privacy: .public)")
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
        }

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            if let url = action.request.url { UIApplication.shared.open(url) }
            return nil
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            // 网关偶尔读链失败（几家节点没凑齐一致结果），显示「节点不足」页；自动刷新几次，通常第二次就好
            let title = webView.title ?? ""
            if title.contains("节点不足") || title.contains("Not enough nodes") {
                if rpcRetries < 4 {
                    rpcRetries += 1
                    Log.app.info("shop gateway rpc failure, retry \(self.rpcRetries)")
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.2 * Double(rpcRetries)) { [weak webView] in webView?.reload() }
                    return
                }
            } else {
                rpcRetries = 0
            }
            state.loaded = true
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            Log.app.error("shop load failed: \(error.localizedDescription, privacy: .public)")
            state.loaded = true
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            Log.app.error("shop load failed: \(error.localizedDescription, privacy: .public)")
            state.loaded = true
        }

        private func openChat(_ url: URL) {
            let address = url.absoluteString.dropFirst("mailto:".count).split(separator: "?").first.map(String.init) ?? ""
            guard let account = model.account, !address.isEmpty else { return }
            model.perform("chat from shop mailto") {
                let chatId = try account.createChat(account: address, name: "")
                model.openChat(chatId)
            }
        }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            let origin = message.frameInfo.securityOrigin
            guard message.frameInfo.isMainFrame, origin.protocol == "https", origin.port == 0 || origin.port == 443,
                  hosts.contains(origin.host), let web = message.webView else {
                Log.app.warning("shop bridge call rejected from \(origin.host, privacy: .public)")
                return
            }
            guard let body = message.body as? String,
                  let json = try? JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any],
                  let id = json["id"] as? Int,
                  let method = json["method"] as? String else {
                Log.app.warning("shop bridge call malformed")
                return
            }
            let params = json["params"] as? [String: Any] ?? [:]
            Log.app.info("shop bridge \(method, privacy: .public)")
            switch method {
            case "publish": publish(id, params, web)
            case "remove": remove(id, params, web)
            case "share": share(id, params, web)
            default: Log.app.warning("shop bridge unknown method \(method, privacy: .public)")
            }
        }

        private func publish(_ id: Int, _ p: [String: Any], _ web: WKWebView) {
            guard let account = model.account else { return }
            let input = ProductInput(
                description: p["description"] as? String ?? "",
                originalPrice: (p["originalPrice"] as? NSNumber)?.uint64Value ?? 0,
                discountPrice: (p["discountPrice"] as? NSNumber)?.uint64Value ?? 0,
                images: p["images"] as? [String] ?? []
            )
            Task {
                do {
                    let product = try await account.shopPublish(input: input)
                    reply(id, [
                        "id": product.id, "description": product.description,
                        "originalPrice": product.originalPrice, "discountPrice": product.discountPrice,
                        "images": product.images, "createdAt": product.createdAt,
                    ], nil, web)
                } catch {
                    Log.app.error("shop publish failed: \(coreMessage(error), privacy: .public)")
                    reply(id, nil, coreMessage(error), web)
                }
            }
        }

        private func remove(_ id: Int, _ p: [String: Any], _ web: WKWebView) {
            guard let account = model.account, let productId = p["id"] as? String else { return reply(id, nil, "invalid", web) }
            Task {
                do {
                    try await account.shopRemove(productId: productId)
                    reply(id, nil, nil, web)
                } catch {
                    Log.app.error("shop remove failed: \(coreMessage(error), privacy: .public)")
                    reply(id, nil, coreMessage(error), web)
                }
            }
        }

        private func share(_ id: Int, _ p: [String: Any], _ web: WKWebView) {
            let text = [p["text"] as? String, p["url"] as? String].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: "\n")
            let sheet = UIActivityViewController(activityItems: [text], applicationActivities: nil)
            sheet.popoverPresentationController?.sourceView = web
            TopController.current?.present(sheet, animated: true)
            reply(id, nil, nil, web)
        }

        private func reply(_ id: Int, _ result: Any?, _ error: String?, _ web: WKWebView) {
            let resultJSON = result.flatMap { try? JSONSerialization.data(withJSONObject: $0, options: [.fragmentsAllowed]) }.flatMap { String(data: $0, encoding: .utf8) } ?? "null"
            let errorJSON = error.flatMap { try? JSONSerialization.data(withJSONObject: $0, options: [.fragmentsAllowed]) }.flatMap { String(data: $0, encoding: .utf8) } ?? "null"
            web.evaluateJavaScript("window.tapetapeShopReply(\(id), \(resultJSON), \(errorJSON))")
        }
    }
}
