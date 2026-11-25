import SwiftUI
import WebKit

// 广场 = 链上网站浏览器。输入 4246.0、1.2.0 这样的链上名字，打开存在链上的网站。
//
// 两个 WKWebView：
//   内核宿主（看不见）：加载 Resources/tapekit-host.html，里面是原样打包的 Tapekit 内核。来源是 App 私有的
//                       https://kernel.tapetape.invalid，网站碰不到它的缓存和状态。
//   浏览器（看得见）：  网站在 tape://<名字>/ 下打开；每个 tape:// 请求交给 TapeSchemeHandler →
//                       内核宿主读链、多家节点核对、SHA-256 校验 → 原样返回（带网关同款的 CSP：脚本只能来自链上）。
// 链外的 https 请求（接口、图片、钱包连接）由 WebKit 照常发出。

@MainActor
final class TapekitHost: NSObject, WKNavigationDelegate {
    static let shared = TapekitHost()
    static let origin = URL(string: "https://kernel.tapetape.invalid/")!
    /// 官方屏蔽名单。它不允许跨域读取，内核宿主网页自己取不到，由原生代码取来再交给内核
    static let blocklistURL = URL(string: "https://tapekit.org/.tape/blocklist.txt")!
    static let requestTimeout: UInt64 = 90

    let webView: WKWebView
    private var loaded = false
    private var broken = false
    private var waiting: [CheckedContinuation<Bool, Never>] = []
    private var blocklistAt = Date.distantPast

    private override init() {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 1, height: 1), configuration: config)
        webView.isHidden = true
        super.init()
        webView.navigationDelegate = self
        load()
    }

    private func load() {
        guard let url = Bundle.main.url(forResource: "tapekit-host", withExtension: "html"),
              let html = try? String(contentsOf: url, encoding: .utf8) else {
            Log.app.error("tapekit host missing from bundle")
            finish(ok: false)
            return
        }
        webView.loadHTMLString(html, baseURL: Self.origin)
    }

    private func finish(ok: Bool) {
        loaded = ok
        broken = !ok
        waiting.forEach { $0.resume(returning: ok) }
        waiting.removeAll()
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        Log.app.info("tapekit host ready")
        finish(ok: true)
        refreshBlocklist()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        Log.app.error("tapekit host failed: \(error.localizedDescription, privacy: .public)")
        finish(ok: false)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        Log.app.error("tapekit host process ended, reloading")
        loaded = false
        broken = false
        load()
    }

    /// 等内核宿主就绪；加载失败直接报错，不让请求一直挂着
    private func ready() async throws {
        if loaded { return }
        if broken { throw URLError(.resourceUnavailable) }
        let ok = await withCheckedContinuation { waiting.append($0) }
        if !ok { throw URLError(.resourceUnavailable) }
    }

    /// 调内核，最多等 requestTimeout 秒
    private func call(_ js: String, _ args: [String: Any]) async throws -> Any? {
        try await ready()
        let view = webView
        return try await withThrowingTaskGroup(of: Any?.self) { group in
            group.addTask { @MainActor in try await view.callAsyncJavaScript(js, arguments: args, contentWorld: .page) }
            group.addTask {
                try await Task.sleep(nanoseconds: Self.requestTimeout * 1_000_000_000)
                throw URLError(.timedOut)
            }
            let first = try await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    /// 取官方屏蔽名单交给内核（启动时一次，之后每 10 分钟最多一次）。取不到就继续用内置的
    func refreshBlocklist() {
        guard Date().timeIntervalSince(blocklistAt) > 600 else { return }
        blocklistAt = Date()
        Task {
            do {
                var req = URLRequest(url: Self.blocklistURL, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
                req.httpMethod = "GET"
                let (data, resp) = try await URLSession.shared.data(for: req)
                guard (resp as? HTTPURLResponse)?.statusCode == 200, data.count < 1_000_000,
                      let text = String(data: data, encoding: .utf8) else { return }
                _ = try await call("return tapekit.setBlocklist(text)", ["text": text])
                Log.app.info("tapekit blocklist updated")
            } catch {
                Log.app.error("tapekit blocklist fetch failed: \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    struct Reply {
        let status: Int
        let headers: [String: String]
        let body: Data

        /// 页面本身核对通过：网站文件状态正常、内核核对过哈希（提示页、错误页、未校验文件都不算）
        var verified: Bool {
            status == 200 && headers["x-tape-verified"] == "1" && headers["x-tape-status"] == "ok"
        }
    }

    func serve(_ url: URL, language: String) async throws -> Reply {
        refreshBlocklist()
        let out = try await call("return await tapekit.serve(url, lang)", ["url": url.absoluteString, "lang": language])
        guard let d = out as? [String: Any],
              let status = d["status"] as? NSNumber,
              let headers = d["headers"] as? [String: String],
              let b64 = d["body"] as? String,
              let body = Data(base64Encoded: b64) else {
            throw URLError(.cannotParseResponse)
        }
        return Reply(status: status.intValue, headers: headers, body: body)
    }

    /// 用户点「刷新」：这个网站下次请求重新读链（内核里限制 10 秒一次）
    func refresh(host: String) async {
        _ = try? await call("return tapekit.refresh(host)", ["host": host])
    }

    /// 地址栏输入 → 链上名字与路径；不认识返回内核的说明文字
    func normalize(_ text: String, language: String) async -> Result<(name: String, path: String), BrowserInputError> {
        do {
            let out = try await call("return await tapekit.normalize(text, lang)", ["text": text, "lang": language])
            guard let d = out as? [String: Any] else { return .failure(BrowserInputError(message: L("error"))) }
            if d["ok"] as? Bool == true, let name = d["name"] as? String {
                return .success((name, d["path"] as? String ?? ""))
            }
            return .failure(BrowserInputError(message: d["message"] as? String ?? L("error")))
        } catch {
            return .failure(BrowserInputError(message: error.localizedDescription))
        }
    }
}

struct BrowserInputError: Error {
    let message: String
}

enum TapeURL {
    static let scheme = "tape"
    /// 广场的起始页：链上网站 2.2.0（X Layer 0 号处理器的 #2）。改起始页只改这里；网站内容在链上换，App 不用发版
    static let home = URL(string: "tape://2.2.0.tape/")!

    static func site(_ name: String, path: String = "") -> URL? {
        let p = path.hasPrefix("/") ? String(path.dropFirst()) : path
        return URL(string: "\(scheme)://\(name)/\(p)")
    }

    /// 地址栏显示：tape://1.2.0.tape/a.html → 1.2.0/a.html；其他网址原样
    static func display(_ url: URL?) -> String {
        guard let url else { return "" }
        guard url.scheme == scheme, let host = url.host else { return url.absoluteString }
        var path = url.path
        if path == "/" { path = "" }
        if let q = url.query { path += "?" + q }
        return short(host) + path
    }

    static func short(_ host: String) -> String {
        host.hasSuffix(".tape") ? String(host.dropLast(5)) : host
    }

    static var language: String { Locale.preferredLanguages.first ?? "zh" }
}

/// tape:// 请求 → 内核宿主
final class TapeSchemeHandler: NSObject, WKURLSchemeHandler {
    private var stopped = Set<ObjectIdentifier>()
    private let lock = NSLock()
    /// 主页面（不是图片、脚本这类子资源）的核对结果：网址 → 是否核对通过。地址栏的绿勾、钱包标签按「已显示的那一页」的结果
    var onMainDocument: ((String, Bool) -> Void)?

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        guard let url = task.request.url else { return }
        let id = ObjectIdentifier(task)
        let isMain = task.request.mainDocumentURL == url
        Task { @MainActor in
            do {
                let r = try await TapekitHost.shared.serve(url, language: TapeURL.language)
                guard self.alive(id) else { return self.forget(id) }
                if isMain { self.onMainDocument?(url.absoluteString, r.verified) }
                // 头名写成标准大小写：HTTPURLResponse 只从 "Content-Type" 里取 MIME 和字符集，
                // 小写的 content-type 会让 WebKit 按 Latin-1 解码
                var headers: [String: String] = [:]
                for (k, v) in r.headers { headers[Self.canonical(k)] = v }
                headers["Content-Length"] = String(r.body.count)
                let response = HTTPURLResponse(url: url, statusCode: r.status, httpVersion: "HTTP/1.1", headerFields: headers)!
                task.didReceive(response)
                task.didReceive(r.body)
                task.didFinish()
            } catch {
                guard self.alive(id) else { return self.forget(id) }
                if isMain { self.onMainDocument?(url.absoluteString, false) }
                Log.app.error("tape request failed: \(error.localizedDescription, privacy: .public)")
                task.didFailWithError(error)
            }
            self.forget(id)
        }
    }

    static func canonical(_ name: String) -> String {
        name.split(separator: "-").map { $0.prefix(1).uppercased() + $0.dropFirst().lowercased() }.joined(separator: "-")
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {
        lock.lock(); stopped.insert(ObjectIdentifier(task)); lock.unlock()
    }

    private func alive(_ id: ObjectIdentifier) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return !stopped.contains(id)
    }

    private func forget(_ id: ObjectIdentifier) {
        lock.lock(); stopped.remove(id); lock.unlock()
    }
}

@MainActor
final class BrowserModel: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    @Published var address = ""
    @Published var editing = false
    @Published var showHome = false
    @Published var canGoBack = false
    @Published var canGoForward = false
    @Published var loading = false
    @Published var progress = 0.0
    @Published var error: String?
    /// 主机名 → 主页面是否核对通过（来自内核的响应头）
    private var verifiedURLs: [String: Bool] = [:]
    /// 已显示的那一页有没有核对通过（页面换了才更新，不按主机名缓存）
    @Published var committedVerified: Bool?
    /// webView.url：跳转刚开始就会变（钱包用它判断「换了网站」）
    @Published var currentURL: URL?
    /// 已经真正显示出来的页面地址：地址栏、绿盾、钱包菜单都按它（跳转还没完成时不让地址栏先变）
    @Published var committedURL: URL?

    let webView: WKWebView
    /// 网页钱包：连接、签名、发交易都在这里弹窗确认
    let wallet = DappWallet()
    private var observers: [NSKeyValueObservation] = []

    override init() {
        let config = WKWebViewConfiguration()
        let handler = TapeSchemeHandler()
        config.setURLSchemeHandler(handler, forURLScheme: TapeURL.scheme)
        config.allowsInlineMediaPlayback = true
        // 钱包：provider.js 在网页里；消息处理器和转发脚本在独立脚本世界里，网页、框架都碰不到
        wallet.installScripts(config.userContentController, fresh: false)
        config.userContentController.addScriptMessageHandler(wallet, contentWorld: DappWallet.world, name: DappWallet.bridgeName)
        webView = WKWebView(frame: .zero, configuration: config)
        webView.allowsBackForwardNavigationGestures = true
        super.init()
        webView.navigationDelegate = self
        webView.uiDelegate = self
        handler.onMainDocument = { [weak self] url, ok in self?.verifiedURLs[url] = ok }
        wallet.webView = webView
        wallet.onChange = { [weak self] in self?.objectWillChange.send() }
        // 钱包标签只认「当前已显示的那一页」的核对结果
        wallet.verified = { [weak self] origin in
            guard let self, DappWallet.origin(of: self.committedURL) == origin else { return nil }
            return self.committedVerified
        }
        observers = [
            webView.observe(\.url) { [weak self] v, _ in Task { @MainActor in self?.sync(v) } },
            webView.observe(\.canGoBack) { [weak self] v, _ in Task { @MainActor in self?.canGoBack = v.canGoBack } },
            webView.observe(\.canGoForward) { [weak self] v, _ in Task { @MainActor in self?.canGoForward = v.canGoForward } },
            webView.observe(\.isLoading) { [weak self] v, _ in Task { @MainActor in self?.loading = v.isLoading } },
            webView.observe(\.estimatedProgress) { [weak self] v, _ in Task { @MainActor in self?.progress = v.estimatedProgress } },
        ]
        _ = TapekitHost.shared
        open(TapeURL.home)
    }

    private func sync(_ v: WKWebView) {
        // 换了网站：上一个网站挂着的钱包请求作废
        if DappWallet.origin(of: v.url) != DappWallet.origin(of: currentURL) { wallet.cancelAll() }
        currentURL = v.url
    }

    /// 页面真正换了（或第一次显示）：更新地址栏
    private func commit(_ v: WKWebView) {
        committedURL = v.url
        committedVerified = v.url.flatMap { verifiedURLs[$0.absoluteString] }
        if !editing && !showHome { address = TapeURL.display(v.url) }
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        // 新页面真正显示了：上一页挂着的钱包请求作废，事件改用新页面的通道名
        wallet.cancelAll()
        wallet.pageCommitted()
        commit(webView)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        commit(webView)
    }

    /// 地址栏提交：链上名字 → tape://；带协议或像域名的 → 普通网页
    func submit(_ raw: String) {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        editing = false
        if let url = URL(string: text), let s = url.scheme?.lowercased(), ["http", "https"].contains(s) {
            open(url)
            return
        }
        if text.lowercased().hasPrefix("web+tape://") {
            submit(String(text.dropFirst(4)))
            return
        }
        // 有字母的点分名字（okx.com）当普通网址；纯数字的（4246.0、1.2.344）和 #ID@… 交给内核
        if text.range(of: #"^[^\s/]*[a-zA-Z][^\s/]*\.[a-zA-Z]{2,}(/.*)?$"#, options: .regularExpression) != nil,
           !text.lowercased().hasPrefix("tape://"), !text.lowercased().contains(".tape"),
           let url = URL(string: "https://" + text) {
            open(url)
            return
        }
        Task {
            switch await TapekitHost.shared.normalize(text, language: TapeURL.language) {
            case .success(let r):
                if let url = TapeURL.site(r.name, path: r.path) { open(url) }
            case .failure(let e):
                error = e.message
            }
        }
    }

    func open(_ url: URL) {
        showHome = false
        address = TapeURL.display(url)
        webView.load(URLRequest(url: url))
    }

    func reload() {
        if showHome { return }
        guard let url = webView.url, url.scheme == TapeURL.scheme, let host = url.host else {
            webView.reload()
            return
        }
        Task {
            await TapekitHost.shared.refresh(host: host)
            webView.reload()
        }
    }

    func goHome() {
        open(TapeURL.home)
    }

    func siteInfo() {
        guard let url = webView.url, url.scheme == TapeURL.scheme, let host = url.host,
              let info = URL(string: "\(TapeURL.scheme)://\(host)/.tape/status") else { return }
        webView.load(URLRequest(url: info))
    }

    var onTapeSite: Bool { !showHome && committedURL?.scheme == TapeURL.scheme }

    /// 当前页面的网站来源（菜单里「断开钱包连接」用）
    var currentOrigin: String? { DappWallet.origin(of: committedURL) }

    /// 地址栏的绿勾只在这一页核对通过时显示；提示页、错误页、未校验的文件都不显示
    var pageVerified: Bool {
        guard onTapeSite else { return false }
        return committedVerified == true
    }

    /// 网页弹窗的来源：写明是哪个网站弹的，免得冒充 App 自己的对话框
    private func alertOrigin(_ frame: WKFrameInfo) -> String {
        let o = frame.securityOrigin
        return o.host.isEmpty ? "\(o.protocol)://" : DappWallet.origin(of: o)
    }

    // 链上网站里的 web+tape:// 链接转成 tape://；其他特殊协议不在浏览器里开
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = action.request.url, let scheme = url.scheme?.lowercased() else { return decisionHandler(.cancel) }
        let mainFrame = action.targetFrame?.isMainFrame ?? true
        // web+tape:// 只接受主页面发起的、名字是链上数字名字的（框架不能借它把整页带走）
        if scheme == "web+tape" {
            decisionHandler(.cancel)
            let rest = String(url.absoluteString.dropFirst("web+tape://".count))
            let host = rest.split(whereSeparator: { "/?#".contains($0) }).first.map(String.init)?.lowercased() ?? ""
            if mainFrame, action.sourceFrame.isMainFrame,
               host.range(of: #"^[0-9]+(\.[0-9]+){1,2}(\.tape)?$"#, options: .regularExpression) != nil,
               let fixed = URL(string: "tape://" + rest) {
                open(fixed)
            }
            return
        }
        // 主页面不许跳到 data:/blob:（那样的页面没有可识别的来源，可以伪装成任何网站）
        if mainFrame, ["data", "blob"].contains(scheme) { return decisionHandler(.cancel) }
        // http(s)://xxx.tape 不是链上网站（链上网站只走 tape://）：改成 tape:// 打开，免得冒名
        if ["http", "https"].contains(scheme), let host = url.host?.lowercased(), host.hasSuffix(".tape") {
            decisionHandler(.cancel)
            if mainFrame, let fixed = URL(string: "tape://\(host)\(url.path)") { open(fixed) }
            return
        }
        let allow = ["tape", "https", "http", "about", "blob", "data"].contains(scheme)
        // 主页面要换新文档：下一页的钱包通道换一个新名字（不能拿来跨网站追踪）
        if allow, mainFrame { wallet.installScripts(webView.configuration.userContentController, fresh: true) }
        decisionHandler(allow ? .allow : .cancel)
    }

    // 跳转开始时不作废请求（跳到 204 之类不换页的地址时页面还在）；新页面显示出来（didCommit）时才作废

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        // window.open / target=_blank：只接受主页面发起的，在当前页打开；地址栏等页面真正换了才变
        if action.sourceFrame.isMainFrame, let url = action.request.url, let s = url.scheme?.lowercased(), ["https", "http", "tape"].contains(s) {
            showHome = false
            webView.load(URLRequest(url: url))
        }
        return nil
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        let e = error as NSError
        guard e.code != NSURLErrorCancelled else { return }
        Log.app.error("browser load failed: \(error.localizedDescription, privacy: .public)")
        self.error = error.localizedDescription
    }

    // 网页自己的弹窗：和钱包弹窗共用一个位置（钱包在用时、这一页弹过 3 次后直接当作取消），标明是网页内容
    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        wallet.pageDialog(origin: alertOrigin(frame), message: message, withCancel: false) { _ in completionHandler() }
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        wallet.pageDialog(origin: alertOrigin(frame), message: message, withCancel: true, done: completionHandler)
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
        completionHandler(nil)
    }
}

struct SquareView: View {
    @EnvironmentObject private var app: AppModel
    @StateObject private var model = BrowserModel()
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            bar
            BrowserWebView(model: model)
        }
        .background(Color(.systemGroupedBackground))
        .onAppear {
            model.wallet.account = app.account
            model.wallet.visible = true
        }
        // 离开广场：挂着的钱包请求作废
        .onDisappear {
            model.wallet.visible = false
            model.wallet.cancelAll()
        }
        .onChange(of: app.account == nil) { _ in model.wallet.account = app.account }
        .alert(L("error"), isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button(L("ok"), role: .cancel) {}
        } message: {
            Text(model.error ?? "")
        }
    }

    private var bar: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Button { model.webView.goBack() } label: { Image(systemName: "chevron.backward") }
                    .disabled(!model.canGoBack || model.showHome)
                Button { model.webView.goForward() } label: { Image(systemName: "chevron.forward") }
                    .disabled(!model.canGoForward || model.showHome)
                HStack(spacing: 6) {
                    Image(systemName: model.pageVerified ? "checkmark.shield.fill" : model.onTapeSite ? "exclamationmark.shield" : "magnifyingglass")
                        .foregroundColor(model.pageVerified ? Color(hex: 0x00C754) : .secondary)
                        .font(.system(size: 14))
                    if model.wallet.isConnected(model.currentOrigin) && !model.editing {
                        Image(systemName: "wallet.pass.fill")
                            .foregroundColor(.accentColor)
                            .font(.system(size: 13))
                            .accessibilityLabel(L("v2_wallet_connected"))
                    }
                    TextField(L("v2_browser_placeholder"), text: $model.address)
                        .focused($focused)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .submitLabel(.go)
                        .onSubmit { model.submit(model.address) }
                        .font(.system(size: 15))
                    if focused && !model.address.isEmpty {
                        Button { model.address = "" } label: { Image(systemName: "xmark.circle.fill").foregroundColor(.secondary) }
                    }
                }
                .padding(.horizontal, 10)
                .frame(height: 36)
                .background(Color(.tertiarySystemFill))
                .clipShape(RoundedRectangle(cornerRadius: 10))
                Menu {
                    Button { model.reload() } label: { Label(L("v2_browser_reload"), systemImage: "arrow.clockwise") }
                    if model.onTapeSite {
                        Button { model.siteInfo() } label: { Label(L("v2_browser_status"), systemImage: "info.circle") }
                    }
                    Button { model.goHome() } label: { Label(L("v2_browser_home"), systemImage: "house") }
                    Divider()
                    // 网络按网站分开记，只有连上钱包的网站才能切
                    if model.wallet.isConnected(model.currentOrigin), let o = model.currentOrigin {
                        Picker(L("v2_wallet_network_menu", WalletChain.name(model.wallet.chainId(for: o))), selection: Binding(get: { model.wallet.chainId(for: o) }, set: { model.wallet.setChain($0, for: o) })) {
                            ForEach(WalletChain.all, id: \.self) { Text(WalletChain.name($0)).tag($0) }
                        }
                        .pickerStyle(.menu)
                        Button(role: .destructive) { model.wallet.disconnect(o) } label: { Label(L("v2_wallet_disconnect"), systemImage: "link.badge.plus") }
                    }
                    // 这个网站被拒绝太多次、钱包请求暂时不弹：可以手动解除
                    if model.wallet.isCoolingDown(model.currentOrigin), let o = model.currentOrigin {
                        Button { model.wallet.clearCooldown(o) } label: { Label(L("v2_wallet_clear_cooldown"), systemImage: "lock.open") }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
            .font(.system(size: 18))
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .onChange(of: focused) { model.editing = $0 }
            ProgressView(value: model.loading ? max(model.progress, 0.05) : 0)
                .progressViewStyle(.linear)
                .frame(height: 2)
                .opacity(model.loading ? 1 : 0)
        }
        .background(Color(.systemBackground))
    }

}

private struct BrowserWebView: UIViewRepresentable {
    let model: BrowserModel

    func makeUIView(context: Context) -> UIView {
        let box = UIView()
        // 内核宿主挂在视图树里（看不见），免得不在窗口里的 WKWebView 被系统降速
        let host = TapekitHost.shared.webView
        host.removeFromSuperview()
        box.addSubview(host)
        model.webView.frame = box.bounds
        model.webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        box.addSubview(model.webView)
        return box
    }

    func updateUIView(_ view: UIView, context: Context) {}
}
