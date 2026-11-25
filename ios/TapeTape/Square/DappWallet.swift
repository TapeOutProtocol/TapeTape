import Foundation
import TapeTapeCore
import UIKit
import WebKit

// 广场浏览器的网页钱包（原生一侧）。网页里注入的 tapetape-provider.js 把 window.ethereum 的请求转到这里：
//   只读请求直接查链；连接、切换网络、签名、发交易都先弹窗问用户，用户点了确认才交给内核签名。
// 默认 X Layer，可以切到 BNB 链。网络和连接授权都按网站来源分开记，只在这次打开 App 期间有效。
//
// 安全规定（2026-11-24 三轮五角色审计，见 browser/钱包连接安全审计/复审/三审-20261124.md）：
// - 通道：消息处理器放在独立的脚本世界里（网页和框架都看不到），主页面里只有一段转发脚本，用随机名字的事件和
//   provider.js 对话；名字每次主页面跳转换一次（不能拿来跨网站追踪）；
// - 请求一开始就固定这次用的网络，弹窗显示的和最后签/发的一定是同一条链；
// - 钱包弹窗、报错提示、网页自己的 alert/confirm 共用一个位置：同一时间只有一个；广场不在屏幕上时一律不弹；
// - 确认键要等弹窗出现满 1 秒、屏幕上没有手指、而且 0.8 秒内没有任何触摸（按下、移动、抬起）才能点；
//   确认和取消的左右位置随机（连点游戏没法预先瞄准）；授权类、盲签再确认一次；
// - 同一网站（按注册域名）连续被拒 3 次冷却，冷却时间逐级加长（30 秒、2 分钟、5 分钟），到期后重新计数；
//   报错提示 1 分钟内超过 2 次就不再弹；网页弹窗 10 秒内超过 3 次就暂停 1 分钟；弹窗没能显示出来不算用户拒绝；
// - 弹窗写清是不是核对过的链上网站；回给网页的错误都是通用说法，不带 App 特有的文字。

struct WalletRpcError: Error {
    let code: Int
    let message: String
    static func rejected() -> WalletRpcError { WalletRpcError(code: 4001, message: "User rejected the request.") }
    static func unauthorized() -> WalletRpcError { WalletRpcError(code: 4100, message: "The requested account and/or method has not been authorized by the user.") }
    static func unsupported() -> WalletRpcError { WalletRpcError(code: 4200, message: "The requested method is not supported.") }
    static func busy() -> WalletRpcError { WalletRpcError(code: -32002, message: "Request already pending.") }
    static func invalid() -> WalletRpcError { WalletRpcError(code: -32602, message: "Invalid parameters.") }
    static func internalError() -> WalletRpcError { WalletRpcError(code: -32603, message: "Internal error.") }
    static func limited() -> WalletRpcError { WalletRpcError(code: -32005, message: "Request limit exceeded.") }
    static func disconnected() -> WalletRpcError { WalletRpcError(code: 4900, message: "The provider is disconnected from all chains.") }
}

enum WalletChain {
    static let xlayer: UInt64 = 196
    static let bsc: UInt64 = 56
    static let all: [UInt64] = [xlayer, bsc]
    static func name(_ id: UInt64) -> String { id == bsc ? "BNB Chain" : "X Layer" }
    static func hex(_ id: UInt64) -> String { "0x" + String(id, radix: 16) }
}

/// 网站的「注册域名」：常见的公共后缀（谁都能注册子域名的托管平台、二级国家后缀）要多算一段
enum SiteName {
    /// 公共后缀里需要多算一段的那些（不全，但覆盖常见的托管平台和二级国家后缀）
    static let suffixes: Set<String> = [
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "com.hk", "com.tw",
        "com.au", "net.au", "org.au", "co.jp", "ne.jp", "or.jp", "co.kr", "com.br", "com.sg", "com.my", "co.in", "co.nz",
        "co.za", "com.mx", "com.tr", "com.vn", "co.id", "com.ph", "com.ar",
        "github.io", "gitlab.io", "vercel.app", "netlify.app", "pages.dev", "workers.dev", "web.app", "firebaseapp.com",
        "herokuapp.com", "glitch.me", "onrender.com", "fly.dev", "azurewebsites.net", "cloudfront.net", "appspot.com",
        "blogspot.com", "myshopify.com", "wordpress.com", "surge.sh", "ngrok.io", "ngrok-free.app", "trycloudflare.com",
        "amplifyapp.com", "replit.app", "repl.co", "deno.dev", "railway.app", "up.railway.app", "s3.amazonaws.com",
        "ipfs.dweb.link", "ipns.dweb.link", "4everland.app", "fleek.co", "on-fleek.app", "eth.limo", "eth.link",
    ]

    /// 注册域名：okx.com.evil.xyz → evil.xyz；evil.github.io → evil.github.io；结尾的点去掉
    static func registrable(_ rawHost: String) -> String {
        var host = rawHost.lowercased()
        while host.hasSuffix(".") { host.removeLast() }
        if host.allSatisfy({ $0.isNumber || $0 == "." || $0 == ":" }) { return host }
        let parts = host.split(separator: ".").map(String.init)
        guard parts.count > 2 else { return host }
        for take in [3, 2] where parts.count > take {
            let suffix = parts.suffix(take).joined(separator: ".")
            if suffixes.contains(suffix) { return parts.suffix(take + 1).joined(separator: ".") }
        }
        if suffixes.contains(parts.suffix(2).joined(separator: ".")) || suffixes.contains(parts.suffix(3).joined(separator: ".")) { return host }
        return parts.suffix(2).joined(separator: ".")
    }
}

/// 记屏幕上的触摸（装在主窗口上，不拦截、不识别任何手势）：最后一次触摸活动的时间、当前按着的手指数。
/// 一直保持「可能」状态，所以第二根、第三根手指的按下、移动、抬起都看得到
final class TouchWatcher: UIGestureRecognizer, UIGestureRecognizerDelegate {
    static var last = Date.distantPast
    static var active = 0

    @MainActor static func install() {
        guard let window = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first,
              !(window.gestureRecognizers ?? []).contains(where: { $0 is TouchWatcher }) else { return }
        let g = TouchWatcher(target: nil, action: nil)
        g.cancelsTouchesInView = false
        g.delaysTouchesBegan = false
        g.delaysTouchesEnded = false
        g.delegate = g
        window.addGestureRecognizer(g)
    }

    private func update(_ event: UIEvent) {
        Self.last = Date()
        Self.active = event.allTouches?.filter { $0.phase != .ended && $0.phase != .cancelled }.count ?? 0
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) { update(event) }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) { update(event) }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) { update(event) }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) { update(event) }
    override func reset() { Self.active = 0 }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
    func gestureRecognizer(_ g: UIGestureRecognizer, shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool { false }
}

/// 弹窗的结果：用户同意、用户拒绝、没能显示（不算用户拒绝）
enum Answer { case yes, no, notShown }

/// 只让 continuation 被 resume 一次（用户点按钮、页面跳转取消、弹不出来，谁先到算谁）
private final class Prompt {
    let alert: UIAlertController
    private var continuation: CheckedContinuation<Answer, Never>?
    init(_ alert: UIAlertController, _ c: CheckedContinuation<Answer, Never>) { self.alert = alert; continuation = c }
    var done: Bool { continuation == nil }
    func finish(_ a: Answer) {
        continuation?.resume(returning: a)
        continuation = nil
    }
}

@MainActor
final class DappWallet: NSObject, WKScriptMessageHandlerWithReply {
    weak var webView: WKWebView?
    var account: Account?
    /// 当前已显示页面的链上核对结果（浏览器给的，按页面记）：true 通过，false 没通过，nil 不是链上网站或还不知道
    var verified: (String) -> Bool? = { _ in nil }
    var onChange: () -> Void = {}
    /// 广场在屏幕上（切到别的标签时为 false，这时一律不弹窗）
    var visible = false

    private var chains: [String: UInt64] = [:]
    private var connected = Set<String>()
    /// 每个网站（按注册域名）连续被拒的次数、冷却截止时间、已经冷却过几轮
    private var rejects: [String: (count: Int, until: Date, level: Int)] = [:]
    /// 每个网站最近的报错提示、网页弹窗时间
    private var notices: [String: [Date]] = [:]
    private var pageDialogs: [String: [Date]] = [:]
    private var pageDialogMuted: [String: Date] = [:]
    private var prompt: Prompt?
    /// 正在走确认流程（包括弹窗前的估算）的请求属于哪一代页面；nil = 空闲
    private var owner: Int?
    var busy: Bool { owner == generation || prompt != nil }
    /// 每次页面跳转加一：跳转前发起的请求，跳转后一律作废
    private var generation = 0
    private var tasks: [Int: Task<Void, Never>] = [:]
    private var taskSeq = 0

    static let bridgeName = "walletBridge"
    /// 消息处理器和转发脚本所在的脚本世界：网页、框架都碰不到
    static let world = WKContentWorld.world(name: "tapetape-wallet")
    /// 当前显示的页面用的通道名；下一个页面要用的（已经注入、还没显示）
    private var channel = DappWallet.newChannel()
    private var nextChannel: String?

    static func newChannel() -> String { "c" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased() }

    /// 把两段脚本按通道名装进去（替换掉旧的）。主页面每次跳转前调用：下一个页面用新名字
    func installScripts(_ controller: WKUserContentController, fresh: Bool) {
        let ch = fresh ? Self.newChannel() : channel
        if fresh { nextChannel = ch }
        controller.removeAllUserScripts()
        if let p = Self.providerScript(ch) { controller.addUserScript(p) }
        controller.addUserScript(Self.bridgeScript(ch))
    }

    /// 新页面显示出来了：之后发给网页的事件用它的通道名
    func pageCommitted() {
        if let n = nextChannel { channel = n }
        nextChannel = nil
    }

    static func providerScript(_ channel: String) -> WKUserScript? {
        guard let url = Bundle.main.url(forResource: "tapetape-provider", withExtension: "js"),
              let js = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        let source = js.replacingOccurrences(of: "__TT_CHANNEL__", with: channel).replacingOccurrences(of: "__TT_PLATFORM__", with: "ios")
        return WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: true, in: .page)
    }

    /// 独立脚本世界里的转发脚本：收 provider.js 的请求事件 → 交给原生；原生的回复 → 发回事件
    static func bridgeScript(_ channel: String) -> WKUserScript {
        let js = """
        (function () {
          if (window.top !== window) return;
          if (location.protocol !== 'https:' && location.protocol !== 'tape:') return;
          var CH = '\(channel)';
          var h = window.webkit.messageHandlers.\(bridgeName);
          function reply(o) { window.dispatchEvent(new CustomEvent(CH + ':res', { detail: JSON.stringify(o) })); }
          window.addEventListener(CH + ':req', function (e) {
            var m; try { m = JSON.parse(e.detail); } catch (_) { return; }
            if (!m || typeof m.method !== 'string') return;
            h.postMessage({ method: m.method, params: Array.isArray(m.params) ? m.params : [] }).then(
              function (r) { reply({ id: m.id, result: r }); },
              function (err) { reply({ id: m.id, error: String((err && err.message) || err) }); });
          });
        })();
        """
        return WKUserScript(source: js, injectionTime: .atDocumentStart, forMainFrameOnly: true, in: world)
    }

    static func origin(of o: WKSecurityOrigin) -> String {
        let host = o.host.lowercased()
        return o.port > 0 ? "\(o.protocol.lowercased())://\(host):\(o.port)" : "\(o.protocol.lowercased())://\(host)"
    }

    static func origin(of url: URL?) -> String? {
        guard let url, let scheme = url.scheme?.lowercased(), let host = url.host?.lowercased(), !host.isEmpty else { return nil }
        if let port = url.port { return "\(scheme)://\(host):\(port)" }
        return "\(scheme)://\(host)"
    }

    /// 能用钱包的来源：https 或 tape://，而且有主机名（about:、data:、http:、file: 都不行）
    static func allowed(_ origin: String) -> Bool {
        guard let url = URL(string: origin), let host = url.host, !host.isEmpty else { return false }
        return url.scheme == "https" || url.scheme == TapeURL.scheme
    }

    /// 给人看的网站名：链上网站显示短名字（2.2.0）；普通网站把注册域名放前面（okx.com.evil.xyz → evil.xyz（okx.com.evil.xyz）），带端口的写出端口
    static func display(_ origin: String) -> String {
        guard let url = URL(string: origin), let rawHost = url.host else { return origin }
        if url.scheme == TapeURL.scheme { return TapeURL.short(rawHost) }
        var host = rawHost.lowercased()
        while host.hasSuffix(".") { host.removeLast() }
        let port = url.port.map { ":\($0)" } ?? ""
        let main = SiteName.registrable(host)
        return main == host ? host + port : "\(main)（\(host)\(port)）"
    }

    /// 冷却、限次按「网站」算：链上网站按名字；普通网站按注册域名，换子域名、端口绕不开
    static func siteKey(_ origin: String) -> String {
        guard let url = URL(string: origin), let host = url.host?.lowercased() else { return origin }
        return url.scheme == TapeURL.scheme ? host : SiteName.registrable(host)
    }

    /// 弹窗第一行：「链上网站：文件与链上一致」/「链上网站，本页未核对」/「普通网站，内容未经核对」
    private func siteLabel(_ origin: String) -> String {
        guard let url = URL(string: origin), url.scheme == TapeURL.scheme else { return L("v2_wallet_site_unverified") }
        return verified(origin) == true ? L("v2_wallet_site_verified") : L("v2_wallet_site_tape_unverified")
    }

    func isConnected(_ origin: String?) -> Bool { origin.map(connected.contains) ?? false }

    func chainId(for origin: String?) -> UInt64 { origin.flatMap { chains[$0] } ?? WalletChain.xlayer }

    /// 这个网站正在冷却（钱包请求会被直接拒绝）：菜单里显示「解除钱包限制」
    func isCoolingDown(_ origin: String?) -> Bool {
        guard let origin, let r = rejects[Self.siteKey(origin)] else { return false }
        return r.until > Date()
    }

    func clearCooldown(_ origin: String) {
        rejects[Self.siteKey(origin)] = nil
        pageDialogMuted[Self.siteKey(origin)] = nil
        onChange()
    }

    func disconnect(_ origin: String) {
        connected.remove(origin)
        emit(to: origin, "accountsChanged", "[]")
        onChange()
    }

    /// 切换某个网站的网络（浏览器菜单或网页请求确认后），并通知这个网站
    func setChain(_ id: UInt64, for origin: String) {
        guard WalletChain.all.contains(id), id != chainId(for: origin) else { return }
        chains[origin] = id
        emit(to: origin, "chainChanged", "\"\(WalletChain.hex(id))\"")
        onChange()
    }

    /// 只发给当前打开的就是这个网站的页面（经独立脚本世界发事件，网页里没有可调用的入口）
    private func emit(to origin: String, _ event: String, _ json: String) {
        guard let webView, Self.origin(of: webView.url) == origin else { return }
        let detail = "{\"event\":\"\(event)\",\"data\":\(json)}"
        let js = "window.dispatchEvent(new CustomEvent('\(channel):evt', { detail: \(Self.jsString(detail)) }))"
        webView.evaluateJavaScript(js, in: nil, in: Self.world, completionHandler: nil)
    }

    private static func jsString(_ s: String) -> String {
        (try? String(data: JSONSerialization.data(withJSONObject: [s]), encoding: .utf8)).map { String($0.dropFirst().dropLast()) } ?? "\"\""
    }

    /// 页面真正换了（新页面显示出来）、离开广场：挂着的请求全部作废，开着的弹窗关掉（当作拒绝）
    func cancelAll() {
        generation += 1
        owner = nil
        tasks.values.forEach { $0.cancel() }
        tasks.removeAll()
        if let p = prompt {
            prompt = nil
            p.alert.presentingViewController?.dismiss(animated: true)
            p.finish(.no)
        }
    }

    // MARK: 网页自己的 alert / confirm

    /// 网页弹窗正文：标出看不见的字符、去掉冒充 App 标签的「【」开头的行、最多 20 行
    static func pageText(_ s: String) -> String {
        let hidden: (Unicode.Scalar) -> Bool = { u in
            let v = u.value
            return (v < 0x20 && v != 0x0a && v != 0x09) || (0x7f...0x9f).contains(v) || (0x200b...0x200f).contains(v) || (0x2028...0x202e).contains(v)
                || (0x2060...0x206f).contains(v) || v == 0xfeff || v == 0x061c || (0xe000...0xf8ff).contains(v)
        }
        var out = ""
        for u in s.unicodeScalars { if hidden(u) { out += String(format: "⟦U+%04X⟧", u.value) } else { out.unicodeScalars.append(u) } }
        let lines = out.split(separator: "\n", omittingEmptySubsequences: false)
            .filter { !$0.trimmingCharacters(in: .whitespaces).hasPrefix("【") }
            .prefix(20)
        return lines.map { "  " + $0 }.joined(separator: "\n")
    }

    /// 和钱包弹窗共用一个位置；钱包在用、已有弹窗、广场不在屏幕上，或这个网站 10 秒内已经弹过 3 次（之后静音 1 分钟）时不显示（当作取消）。
    /// 标题写明哪个网站弹的，正文第一行标「网页内容，不是 TapeTape 的提示」
    func pageDialog(origin: String, message: String, withCancel: Bool, done: @escaping (Bool) -> Void) {
        let key = Self.siteKey(origin)
        let now = Date()
        if let until = pageDialogMuted[key], until > now { return done(false) }
        let recent = (pageDialogs[key] ?? []).filter { now.timeIntervalSince($0) < 10 }
        if recent.count >= 3 {
            pageDialogMuted[key] = now.addingTimeInterval(60)
            return done(false)
        }
        guard !busy, visible else { return done(false) }
        pageDialogs[key] = recent + [now]
        owner = generation
        let gen = generation
        Task { @MainActor in
            let a = await self.present(title: L("v2_browser_alert_from", Self.display(origin)),
                                       message: L("v2_browser_page_content") + "\n\n" + Self.pageText(message),
                                       ok: L("ok"), cancel: withCancel ? L("cancel") : nil, delay: false, shuffle: false)
            if self.owner == gen { self.owner = nil }
            done(a == .yes)
        }
    }

    // MARK: 网页来的请求

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage, replyHandler: @escaping (Any?, String?) -> Void) {
        let origin = Self.origin(of: message.frameInfo.securityOrigin)
        // 转发脚本只在主页面里；这里再查一遍，不对的一律回通用错误
        guard message.frameInfo.isMainFrame, Self.allowed(origin), Self.origin(of: webView?.url) == origin else {
            return replyHandler(nil, "4100|\(WalletRpcError.unauthorized().message)")
        }
        let body = message.body as? [String: Any] ?? [:]
        let method = body["method"] as? String ?? ""
        let params = body["params"] as? [Any] ?? []
        let gen = generation
        taskSeq += 1
        let id = taskSeq
        tasks[id] = Task { @MainActor in
            defer { self.tasks[id] = nil }
            do {
                let r = try await self.handle(method, params, origin)
                // 处理期间页面已经跳走了：结果不回给新页面
                guard gen == self.generation else { return replyHandler(nil, "-32603|\(WalletRpcError.internalError().message)") }
                replyHandler(r, nil)
            } catch let e as WalletRpcError {
                replyHandler(nil, "\(e.code)|\(e.message)")
            } catch {
                replyHandler(nil, "-32603|\(WalletRpcError.internalError().message)")
            }
        }
    }

    /// 需要弹窗的请求：同一时间只能有一个；在冷却中的网站直接拒绝
    private func exclusive<T>(_ origin: String, _ body: () async throws -> T) async throws -> T {
        guard !busy else { throw WalletRpcError.busy() }
        if isCoolingDown(origin) { throw WalletRpcError.rejected() }
        let gen = generation
        owner = gen
        defer { if owner == gen { owner = nil } }
        return try await body()
    }

    /// 记一次用户拒绝 / 同意：同一网站连续拒 3 次冷却，冷却时间逐级加长（30 秒、2 分钟、5 分钟），到期后重新计数
    private func record(_ origin: String, rejected: Bool) {
        let key = Self.siteKey(origin)
        let now = Date()
        var r = rejects[key] ?? (0, .distantPast, 0)
        if r.until <= now && r.count >= 3 { r.count = 0 }   // 上一轮冷却结束：重新计数
        guard rejected else { rejects[key] = (0, .distantPast, r.level); return }
        r.count += 1
        if r.count >= 3 {
            let steps: [TimeInterval] = [30, 120, 300]
            r.until = now.addingTimeInterval(steps[min(r.level, steps.count - 1)])
            r.level += 1
        }
        rejects[key] = r
        onChange()
    }

    /// 返回结果的 JSON 文本（网页那边 JSON.parse）
    private func handle(_ method: String, _ params: [Any], _ origin: String) async throws -> String {
        guard let account else { throw WalletRpcError.disconnected() }
        let address = account.walletAddress()
        let site = Self.display(origin)
        let label = siteLabel(origin)
        // 这次请求用的网络在这里固定下来：之后菜单里切网络不影响这一次
        let chain = chainId(for: origin)
        let gen = generation
        func stillHere() throws { if gen != generation { throw WalletRpcError.rejected() } }
        /// 问用户；拒绝计入冷却；没能显示出来不算拒绝（回「已有请求在等待」）
        func ask(_ title: String, _ text: String, _ ok: String, danger: Bool = false) async throws -> Bool {
            guard gen == generation else { throw WalletRpcError.rejected() }
            let a = await present(title: title, message: text, ok: ok, cancel: L("cancel"), danger: danger, delay: true, shuffle: true)
            try stillHere()
            if a == .notShown { throw WalletRpcError.busy() }
            record(origin, rejected: a == .no)
            return a == .yes
        }
        /// 授权类操作、盲签：再确认一次（按钮位置重新随机）
        func askDanger(_ text: String) async throws -> Bool {
            try? await Task.sleep(nanoseconds: 300_000_000)
            return try await ask(L("v2_wallet_danger_title"), text, L("v2_wallet_danger_confirm"), danger: true)
        }

        switch method {
        case "eth_chainId":
            return json(WalletChain.hex(chain))
        case "net_version":
            return json(String(chain))
        case "eth_accounts":
            return json(connected.contains(origin) ? [address] : [])
        case "eth_requestAccounts", "wallet_requestPermissions":
            if !connected.contains(origin) {
                let ok = try await exclusive(origin) {
                    try await ask(L("v2_wallet_connect_title"),
                                  L("v2_wallet_connect_msg", label, site, address, WalletChain.name(chain)),
                                  L("v2_wallet_connect"))
                }
                guard ok else { throw WalletRpcError.rejected() }
                connected.insert(origin)
                onChange()
                Log.app.info("wallet connected to \(origin, privacy: .private)")
            }
            return method == "eth_requestAccounts" ? json([address]) : json([["parentCapability": "eth_accounts"]])
        case "wallet_getPermissions":
            return json(connected.contains(origin) ? [["parentCapability": "eth_accounts"]] : [])
        case "wallet_revokePermissions":
            disconnect(origin)
            return "null"
        case "wallet_switchEthereumChain", "wallet_addEthereumChain":
            guard connected.contains(origin) else { throw WalletRpcError.unauthorized() }
            let raw = (params.first as? [String: Any])?["chainId"]
            guard let id = Self.chainId(raw), WalletChain.all.contains(id) else {
                throw WalletRpcError(code: 4902, message: "Unrecognized chain ID.")
            }
            if id != chain {
                let ok = try await exclusive(origin) {
                    try await ask(L("v2_wallet_switch_title"), L("v2_wallet_switch_msg", label, site, WalletChain.name(id)), L("v2_wallet_switch"))
                }
                guard ok else { throw WalletRpcError.rejected() }
                setChain(id, for: origin)
            }
            return "null"
        case "personal_sign":
            guard connected.contains(origin) else { throw WalletRpcError.unauthorized() }
            // 标准顺序是 [消息, 地址]；有的网页写反了。两个参数都必须是字符串；写了地址的，必须是这个钱包
            guard let first = params.first as? String else { throw WalletRpcError.invalid() }
            let second = params.count > 1 ? params[1] as? String : nil
            if params.count > 1 && second == nil { throw WalletRpcError.invalid() }
            let raw: String
            if let second, first.lowercased() == address.lowercased() {
                raw = second
            } else {
                if let second, second.lowercased() != address.lowercased() { throw WalletRpcError.invalid() }
                raw = first
            }
            let m = account.dappMessage(raw: raw)
            let ok = try await exclusive(origin) { () async throws -> Bool in
                let shown = m.blind ? L("v2_wallet_blind_warn") + "\n\n" + m.text : m.text
                guard try await ask(L("v2_wallet_sign_title"), L("v2_wallet_sign_msg", label, site, shown), L("v2_wallet_sign"), danger: m.blind) else { return false }
                if m.blind { return try await askDanger(L("v2_wallet_danger_msg", L("v2_wallet_danger_whoever"))) }
                return true
            }
            guard ok else { throw WalletRpcError.rejected() }
            try stillHere()
            return json(try account.dappPersonalSign(messageHex: m.hex))
        case "eth_signTypedData_v4", "eth_signTypedData_v3":
            guard connected.contains(origin) else { throw WalletRpcError.unauthorized() }
            guard params.count >= 2, (params[0] as? String)?.lowercased() == address.lowercased() else { throw WalletRpcError.invalid() }
            let version: UInt32 = method == "eth_signTypedData_v3" ? 3 : 4
            let data: String
            if let s = params[1] as? String { data = s } else { data = String(data: try JSONSerialization.data(withJSONObject: params[1]), encoding: .utf8) ?? "" }
            return try await exclusive(origin) {
                let summary: DappTypedData
                do {
                    summary = try account.dappTypedData(chainId: chain, json: data, version: version)
                } catch {
                    await notice(origin, L("v2_wallet_sign_fail_title"), Self.errorText(coreMessage(error), typed: true), gen: gen)
                    throw WalletRpcError.invalid()
                }
                // 危险警告排在所有网页可控的内容前面
                let warn = summary.danger ? L("v2_wallet_permit_warn") + "\n\n" : ""
                let text = L("v2_wallet_typed_msg", label, site, warn, summary.domainName, summary.verifyingContract,
                             WalletChain.name(chain), summary.primaryType, summary.fields)
                guard try await ask(L("v2_wallet_sign_title"), text, L("v2_wallet_sign"), danger: summary.danger) else { throw WalletRpcError.rejected() }
                if summary.danger {
                    guard try await askDanger(L("v2_wallet_danger_msg", summary.counterparty ?? L("v2_wallet_danger_whoever"))) else { throw WalletRpcError.rejected() }
                }
                try stillHere()
                return json(try account.dappSignTypedData(chainId: chain, json: data, version: version))
            }
        case "eth_sendTransaction":
            guard connected.contains(origin) else { throw WalletRpcError.unauthorized() }
            guard let tx = params.first as? [String: Any] else { throw WalletRpcError.invalid() }
            let txJson = String(data: try JSONSerialization.data(withJSONObject: tx), encoding: .utf8) ?? "{}"
            return try await exclusive(origin) {
                let check: DappTx
                do {
                    check = try await account.dappCheckTx(chainId: chain, origin: origin, txJson: txJson)
                } catch {
                    if coreMessage(error) == "rate-limited" { throw WalletRpcError.limited() }
                    throw WalletRpcError.internalError()
                }
                try stillHere()
                if let e = check.error {
                    await notice(origin, L("v2_wallet_tx_fail_title"), Self.errorText(e, typed: false), gen: gen)
                    throw WalletRpcError.internalError()
                }
                guard try await ask(L("v2_wallet_tx_title"), L("v2_wallet_tx_msg", label, site, Self.txLines(check)), L("v2_wallet_send"), danger: check.danger) else {
                    throw WalletRpcError.rejected()
                }
                if check.danger {
                    let grants = ["approve", "increase-allowance", "approval-for-all", "permit2-approve", "nft-approve"].contains(check.action ?? "")
                    let text = grants ? L("v2_wallet_danger_msg", check.counterparty ?? check.to) : L("v2_wallet_danger_tx_msg", check.counterparty ?? check.to)
                    guard try await askDanger(text) else { throw WalletRpcError.rejected() }
                }
                try stillHere()
                // 进入发送：放进独立任务里，页面跳走也不打断（交易可能已经广播），结果照实告诉用户
                let account = account
                let result = await Task.detached { () -> Result<String, Error> in
                    do { return .success(try await account.dappSendTx(chainId: chain, txJson: txJson, confirmedFee: check.fee, ticketIn: check.ticket)) }
                    catch { return .failure(error) }
                }.value
                switch result {
                case .success(let hash):
                    return json(hash)
                case .failure(let error):
                    // 失败提示写明是哪个网站的交易；位置被占就等一会儿再弹
                    await notice(origin, L("v2_wallet_tx_fail_title") + "（\(site)）", Self.errorText(coreMessage(error), typed: false), gen: nil, retry: true)
                    throw WalletRpcError.internalError()
                }
            }
        case "eth_sign", "eth_signTransaction", "eth_signTypedData":
            // eth_sign 是盲签任意哈希，钓鱼最常用；只签不发的交易、旧版签名格式也不支持
            throw WalletRpcError.unsupported()
        default:
            let p = String(data: try JSONSerialization.data(withJSONObject: params), encoding: .utf8) ?? "[]"
            do {
                return try await account.dappRpc(chainId: chain, origin: origin, method: method, paramsJson: p)
            } catch {
                let m = coreMessage(error)
                if m.hasPrefix("unsupported-method") { throw WalletRpcError.unsupported() }
                if m == "rate-limited" || m == "getlogs-range" { throw WalletRpcError.limited() }
                throw WalletRpcError.internalError()
            }
        }
    }

    /// 交易确认弹窗的正文（金额都是内核给的精确数字，不四舍五入）
    static func txLines(_ t: DappTx) -> String {
        let sep = L("v2_wallet_sep")
        var lines = ["\(L("v2_wallet_l_network"))\(sep)\(t.chainName)"]
        let tokenName = t.tokenSymbol.map { t.tokenKnown ? $0 : L("v2_wallet_self_claimed", $0) } ?? L("v2_wallet_unknown_token")
        // 合约调用同时转出原生币：紧跟在操作后面写出来
        let paying = t.dataLen > 0 && t.value != "0" ? "⚠️ " + L("v2_wallet_also_pay", t.value, t.symbol) : nil
        if let action = t.action {
            lines.append("\(L("v2_wallet_l_action"))\(sep)\(L("v2_wallet_act_" + action.replacingOccurrences(of: "-", with: "_")))")
            if let paying { lines.append(paying) }
            if let token = t.token {
                let key = t.nft ? "v2_wallet_l_nft" : action == "approval-for-all" || action == "revoke-all" ? "v2_wallet_l_asset_contract" : "v2_wallet_l_token"
                lines.append("\(L(key))\(sep)\(tokenName)（\(token)）")
            }
            if t.nft, let id = t.tokenAmount {
                lines.append("\(L("v2_wallet_l_nft_id"))\(sep)#\(id)")
            } else if t.unlimited {
                lines.append("\(L("v2_wallet_l_amount"))\(sep)\(L("v2_wallet_unlimited"))")
            } else if let a = t.tokenAmount {
                lines.append("\(L("v2_wallet_l_amount"))\(sep)\(a) \(t.rawAmount ? L("v2_wallet_raw_amount") : tokenName)")
            }
            if let exp = t.expiry { lines.append("\(L("v2_wallet_l_expiry"))\(sep)\(exp)") }
            if let from = t.from { lines.append("\(L("v2_wallet_l_from"))\(sep)\(from)") }
            if let c = t.counterparty {
                let key = ["transfer", "transfer-from", "nft-transfer-from"].contains(action) ? "v2_wallet_l_recipient" : "v2_wallet_l_spender"
                lines.append("\(L(key))\(sep)\(c)")
            }
            lines.append("\(L("v2_wallet_l_contract"))\(sep)\(t.to)")
            if t.extraData { lines.append(L("v2_wallet_extra_data")) }
        } else if t.dataLen > 0 {
            lines.append("\(L("v2_wallet_l_action"))\(sep)\(L("v2_wallet_act_unknown", t.selector ?? ""))")
            if let paying { lines.append(paying) }
            lines.append("\(L("v2_wallet_l_contract"))\(sep)\(t.to)")
        } else {
            lines.append("\(L(t.toIsContract ? "v2_wallet_l_contract" : "v2_wallet_l_to"))\(sep)\(t.to)")
        }
        if t.dataLen == 0 { lines.append("\(L("v2_wallet_l_value"))\(sep)\(t.value) \(t.symbol)") }
        lines.append("\(L("v2_wallet_l_fee"))\(sep)\(t.fee) \(t.symbol)")
        return lines.joined(separator: "\n")
    }

    private func json(_ v: Any) -> String {
        if let s = v as? String { return Self.jsString(s) }
        return (try? String(data: JSONSerialization.data(withJSONObject: v), encoding: .utf8)) ?? "null"
    }

    /// 网络编号只认 0x 十六进制（EIP-3326 的写法）
    static func chainId(_ raw: Any?) -> UInt64? {
        guard let s = raw as? String, s.lowercased().hasPrefix("0x") else { return nil }
        return UInt64(s.dropFirst(2), radix: 16)
    }

    /// 内核的错误码 → 给用户看的话。只有发送阶段说不清时才说「可能已发出」
    static func errorText(_ code: String, typed: Bool) -> String {
        switch code {
        case "insufficient-balance": return L("v2_wallet_err_balance")
        case "insufficient-gas": return L("v2_wallet_err_gas")
        case "no-contract-creation": return L("v2_wallet_err_create")
        case "chain-mismatch": return L("v2_wallet_err_chain")
        case "fee-changed", "ticket-mismatch": return L("v2_wallet_err_fee")
        case "domain-mismatch": return L("v2_wallet_err_domain")
        case "domain-no-chain": return L("v2_wallet_err_nochain")
        case "typed-extra-fields": return L("v2_wallet_err_extra")
        case "nonce-mismatch": return L("v2_wallet_err_nonce")
        case "rate-limited": return L("v2_wallet_err_rate")
        case "maybe-sent": return L("v2_wallet_err_unknown")
        default:
            if code.hasPrefix("will-fail: ") { return L("v2_wallet_err_fail", String(code.dropFirst(11))) }
            return typed ? L("v2_wallet_err_typed") : L("v2_wallet_err_generic")
        }
    }

    // MARK: 弹窗

    /// 只有「好」的提示框（报错）：也占同一个位置、等用户点掉；同一网站 1 分钟内超过 2 次就不再弹（只把错误回给网页）。
    /// retry：位置被占时每秒再试一次，最多 30 次（交易失败的提示不能丢）
    private func notice(_ origin: String, _ title: String, _ message: String, gen: Int?, retry: Bool = false) async {
        let key = Self.siteKey(origin)
        let now = Date()
        let recent = (notices[key] ?? []).filter { now.timeIntervalSince($0) < 60 }
        if !retry && recent.count >= 2 { return }
        notices[key] = recent + [now]
        for _ in 0..<(retry ? 30 : 1) {
            if let gen, gen != generation { return }
            if await present(title: title, message: message, ok: L("ok"), cancel: nil, delay: false, shuffle: false) != .notShown { return }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
    }

    /// 所有弹窗都从这里出：同一时间只有一个；广场不在屏幕上、上面已有别的弹窗、弹不出来 → notShown（不算用户拒绝）。
    /// delay：确认键要等手指停下；shuffle：确认、取消的左右位置随机
    private func present(title: String, message: String, ok: String, cancel: String?, danger: Bool = false, delay: Bool, shuffle: Bool) async -> Answer {
        TouchWatcher.install()
        guard visible, prompt == nil, let top = TopController.current, !(top is UIAlertController), !top.isBeingDismissed else { return .notShown }
        return await withCheckedContinuation { c in
            let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
            let p = Prompt(alert, c)
            let confirm = UIAlertAction(title: ok, style: danger ? .destructive : .default) { [weak self] _ in self?.close(p, .yes) }
            confirm.isEnabled = !delay
            var actions = [confirm]
            if let cancel {
                // 随机排位时取消键也用普通样式（系统会把「取消」样式固定放在一边）
                let style: UIAlertAction.Style = shuffle ? .default : .cancel
                actions.append(UIAlertAction(title: cancel, style: style) { [weak self] _ in self?.close(p, .no) })
                if !shuffle || Bool.random() { actions.reverse() }
            }
            actions.forEach(alert.addAction)
            prompt = p
            top.present(alert, animated: true)
            // 1 秒后还没真正显示出来（转场冲突等）：不当作用户拒绝
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
                if alert.presentingViewController == nil, !p.done { self?.close(p, .notShown) }
            }
            if delay { Self.arm(confirm, shownAt: Date(), prompt: p) }
        }
    }

    private func close(_ p: Prompt, _ a: Answer) {
        if prompt === p { prompt = nil }
        p.finish(a)
    }

    /// 弹窗出现满 1 秒、屏幕上没有手指、而且 0.8 秒内没有任何触摸，才让确认键能点
    private static func arm(_ action: UIAlertAction, shownAt: Date, prompt: Prompt) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
            guard !prompt.done else { return }
            let now = Date()
            if now.timeIntervalSince(shownAt) >= 1, TouchWatcher.active == 0, now.timeIntervalSince(TouchWatcher.last) >= 0.8 {
                action.isEnabled = true
            } else {
                arm(action, shownAt: shownAt, prompt: prompt)
            }
        }
    }
}
