import SwiftUI
import UIKit
import TapeTapeCore

enum Amount {
    private static func formatter(_ digits: Int) -> NumberFormatter {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US")
        f.numberStyle = .decimal
        f.usesGroupingSeparator = true
        f.minimumFractionDigits = 0
        f.maximumFractionDigits = digits
        f.roundingMode = .down
        return f
    }
    private static let value = formatter(5)
    private static let fee = formatter(8)

    static func show(_ amount: String) -> String {
        guard let d = Decimal(string: amount, locale: Locale(identifier: "en_US")) else { return amount }
        return value.string(from: d as NSDecimalNumber) ?? amount
    }

    static func showFee(_ amount: String) -> String {
        guard let d = Decimal(string: amount, locale: Locale(identifier: "en_US")) else { return amount }
        return fee.string(from: d as NSDecimalNumber) ?? amount
    }

    static func sanitize(_ input: String, decimals: Int) -> String {
        var out = ""
        var dot = false
        var fraction = 0
        for ch in input.replacingOccurrences(of: "。", with: ".") {
            if ch == "." {
                if dot { continue }
                dot = true
                out.append(out.isEmpty ? "0." : ".")
            } else if ch.isASCII, ch.isNumber {
                if dot {
                    if fraction >= decimals { continue }
                    fraction += 1
                }
                out.append(ch)
            }
        }
        return out
    }

    static func isPositive(_ amount: String) -> Bool {
        (Decimal(string: amount, locale: Locale(identifier: "en_US")) ?? 0) > 0
    }
}

enum WalletText {
    static func error(_ code: String, estimate: SendEstimate? = nil, packet: Bool = false) -> String {
        switch code {
        case "invalid-address": return L("withdraw_invalid_address")
        case "invalid-amount": return L("withdraw_invalid_amount")
        case "invalid-count": return L("red_packet_invalid_count")
        case "insufficient-balance": return L(packet ? "red_packet_insufficient_balance" : "withdraw_amount_exceeds_balance")
        case "insufficient-gas":
            if let estimate { return L("v2_insufficient_gas", estimate.feeSymbol, Amount.showFee(estimate.fee)) }
            return L("v2_insufficient_gas_short")
        case "amount-too-small": return L("v2_amount_too_small")
        case "fee-too-high": return L("v2_packet_fee_too_high")
        case "already-claimed": return L("red_packet_already_claimed")
        case "finished": return L("red_packet_fully_claimed")
        case "expired": return L("v2_packet_expired_error")
        case "refunded": return L("v2_packet_refunded_error")
        case "not-claimable": return L("v2_not_claimable")
        case "not-refundable": return L("v2_not_refundable")
        case "tx-failed": return L("v2_tx_failed")
        case "tx-timeout": return L("v2_tx_timeout")
        case "fee-changed": return L("v2_fee_changed")
        default: return code
        }
    }

    static func txStatus(_ status: TxStatus) -> String {
        switch status {
        case .pending: return L("v2_tx_pending")
        case .confirmed: return L("v2_tx_confirmed")
        case .failed: return L("v2_tx_failed_short")
        case .notFound: return L("v2_tx_not_found")
        }
    }
}

@MainActor
enum Alerts {
    @discardableResult
    static func show(_ title: String?, _ message: String? = nil, actions: [UIAlertAction]? = nil) -> UIAlertController {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        (actions ?? [UIAlertAction(title: L("ok"), style: .default)]).forEach(alert.addAction)
        TopController.current?.present(alert, animated: true)
        return alert
    }

    static func progress(_ title: String?, _ message: String?) -> UIAlertController {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        TopController.current?.present(alert, animated: true)
        return alert
    }

    static func dismiss(_ alert: UIAlertController) async {
        await withCheckedContinuation { done in
            if alert.presentingViewController == nil {
                done.resume()
            } else {
                alert.dismiss(animated: true) { done.resume() }
            }
        }
    }

    static func action(_ title: String, style: UIAlertAction.Style = .default, _ handler: @escaping () -> Void = {}) -> UIAlertAction {
        UIAlertAction(title: title, style: style) { _ in handler() }
    }
}

enum TokenSheet {
    @MainActor
    static func present(tokens: [Token], balances: [String: String]? = nil, current: String? = nil, pick: @escaping (Token) -> Void) {
        let sheet = UIAlertController(title: L("select_token"), message: nil, preferredStyle: .actionSheet)
        for token in tokens {
            let balance = balances.map { " (\(Amount.show($0[token.id] ?? "0")) \(token.symbol))" } ?? ""
            let action = UIAlertAction(title: token.symbol + balance, style: .default) { _ in pick(token) }
            if token.id == current { action.setValue(true, forKey: "checked") }
            sheet.addAction(action)
        }
        sheet.addAction(UIAlertAction(title: L("cancel"), style: .cancel))
        TopController.current?.present(sheet, animated: true)
    }
}

struct WalletSection: View {
    @EnvironmentObject var model: AppModel
    @State private var balances: [String: String?] = [:]
    @State private var loading = true

    private var tokens: [Token] { model.core.walletTokens() }

    private var chains: [(String, [Token])] {
        var order: [String] = []
        var groups: [String: [Token]] = [:]
        for t in tokens {
            if groups[t.chainName] == nil { order.append(t.chainName) }
            groups[t.chainName, default: []].append(t)
        }
        return order.map { ($0, groups[$0] ?? []) }
    }

    var body: some View {
        Group {
            ForEach(chains, id: \.0) { chain, list in
                VStack(spacing: 0) {
                    Text(chain).font(.caption.weight(.medium)).foregroundColor(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.bottom, 4)
                    ForEach(list, id: \.id) { token in
                        HStack {
                            Text(token.symbol)
                            Spacer()
                            Text(value(token)).fontWeight(.medium).monospacedDigit()
                        }
                        .frame(height: 34)
                        .accessibilityElement(children: .combine)
                    }
                }
                .padding(.vertical, 4)
            }
            HStack(spacing: 0) {
                Button { model.settingsPath.append(.receive) } label: {
                    Label(L("receive"), systemImage: "qrcode").frame(maxWidth: .infinity)
                }
                Divider().frame(height: 22)
                Button { model.settingsPath.append(.withdraw) } label: {
                    Label(L("withdraw"), systemImage: "arrow.up.right").frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderless)
            .font(.body.weight(.medium))
            .foregroundColor(.blue)
            .frame(height: 30)
        }
        .task(id: model.info?.id) { await load() }
    }

    private func value(_ token: Token) -> String {
        if loading && balances[token.id] == nil { return "..." }
        guard let amount = balances[token.id] ?? nil else { return "--" }
        return Amount.show(amount)
    }

    private func load() async {
        guard let account = model.account else { return }
        loading = true
        let result = await account.balances()
        guard !Task.isCancelled else { return }
        balances = Dictionary(uniqueKeysWithValues: result.map { ($0.tokenId, $0.amount) })
        loading = false
        Log.app.info("wallet balances loaded: \(result.filter { $0.amount != nil }.count)/\(result.count)")
    }
}

struct ReceiveView: View {
    @EnvironmentObject var model: AppModel
    @State private var copied = false

    private var address: String { model.account?.walletAddress() ?? "" }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                VStack(spacing: 16) {
                    if let image = QRCode.image(address) {
                        Image(uiImage: image).interpolation(.none).resizable().frame(width: 220, height: 220)
                    }
                    Text(L("v2_receive_networks"))
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(width: 280, height: 320)
                .background(RoundedRectangle(cornerRadius: 16).fill(Color.white).shadow(color: .black.opacity(0.1), radius: 8, y: 2))
                .padding(.top, 20)
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("receive_address")).font(.system(size: 14, weight: .medium)).foregroundColor(.secondary)
                    Text(address)
                        .font(.system(size: 14, design: .monospaced))
                        .multilineTextAlignment(.center)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity)
                        .padding(14)
                        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.systemGray5)))
                }
                Button(action: copy) {
                    Label(L(copied ? "copied" : "global_menu_edit_copy_desktop"), systemImage: copied ? "checkmark" : "doc.on.doc")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 28)
                        .frame(height: 50)
                        .background(RoundedRectangle(cornerRadius: 12).fill(copied ? Color.green : Color.blue))
                }
                Text(L("v2_receive_warning"))
                    .font(.system(size: 13))
                    .foregroundColor(.orange)
                    .multilineTextAlignment(.center)
                    .padding(14)
                    .frame(maxWidth: .infinity)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color.orange.opacity(0.1)))
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 20)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(L("receive"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private func copy() {
        UIPasteboard.general.string = address
        copied = true
        Log.app.info("wallet address copied")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copied = false }
    }
}

struct WithdrawView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var token: Token?
    @State private var address = ""
    @State private var amount = ""
    @State private var estimate: SendEstimate?
    @State private var balances: [String: String] = [:]
    @State private var txs: [SentTx] = []

    struct SentTx: Identifiable {
        let hash: String
        let chainId: UInt64
        var status: TxStatus
        /// 广场网页发起的交易（不是提现）
        var dapp = false
        var id: String { hash }
    }

    private var addressValid: Bool {
        address.trimmingCharacters(in: .whitespacesAndNewlines).range(of: "^0x[0-9a-fA-F]{40}$", options: .regularExpression) != nil
    }

    private var canConfirm: Bool { addressValid && !amount.isEmpty && estimate != nil }

    var body: some View {
        List {
            Section(L("withdraw_address")) {
                TextField(L("withdraw_address_placeholder"), text: $address, axis: .vertical)
                    .font(.custom("Menlo", size: 15))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .lineLimit(1...4)
                    .accessibilityIdentifier("withdraw-address")
            }
            Section {
                Button(action: pickToken) {
                    HStack {
                        Text(L("transfer_token")).foregroundColor(.primary)
                        Spacer()
                        Text(token.map { "\($0.symbol) · \($0.chainName)" } ?? "").foregroundColor(.secondary)
                        Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundColor(Color(.tertiaryLabel))
                    }
                }
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        TextField(L("withdraw_amount_placeholder"), text: $amount)
                            .keyboardType(.decimalPad)
                            .accessibilityIdentifier("withdraw-amount")
                            .onChange(of: amount) { value in
                                let clean = Amount.sanitize(value, decimals: Int(token?.decimals ?? 18))
                                if clean != value { amount = clean }
                            }
                        Button(L("withdraw_all")) { if let max = estimate?.max { amount = max } }
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.blue)
                            .buttonStyle(.borderless)
                    }
                    Text(available).font(.system(size: 12)).foregroundColor(.secondary)
                }
                .padding(.vertical, 4)
            }
            Section {
                HStack {
                    Text(L("withdraw_gas_fee")).foregroundColor(.secondary)
                    Spacer()
                    Text(estimate.map { "\(Amount.showFee($0.fee)) \($0.feeSymbol)" } ?? "...").foregroundColor(.secondary)
                }
                HStack {
                    Text(L("withdraw_receive_amount"))
                    Spacer()
                    Text(receiveText).font(.system(size: 16, weight: .semibold))
                }
            }
            if !txs.isEmpty {
                Section(L("v2_last_transaction")) {
                    ForEach(txs) { tx in
                        HStack {
                            if tx.dapp { Text(L("v2_wallet_dapp_tx")).font(.system(size: 13)).foregroundColor(.secondary) }
                            Text("\(tx.hash.prefix(10))…\(tx.hash.suffix(8))").font(.system(size: 14, design: .monospaced))
                            Spacer()
                            Text(WalletText.txStatus(tx.status)).foregroundColor(tx.status == .failed ? .red : .secondary)
                        }
                    }
                }
            }
            Section {
                Button(action: { confirm() }) {
                    Text(L("withdraw_confirm"))
                        .font(.headline)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 50)
                        .background(RoundedRectangle(cornerRadius: 12).fill(Color.blue.opacity(canConfirm ? 1 : 0.5)))
                }
                .disabled(!canConfirm)
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(L("withdraw"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .task {
            if token == nil { token = model.core.walletTokens().first }
            loadPending()
            await loadBalances()
        }
        .task(id: watching) { await poll() }
        .task(id: "\(token?.id ?? "")|\(address)|\(amount)") {
            try? await Task.sleep(nanoseconds: 400_000_000)
            await reestimate()
        }
    }

    private var available: String {
        guard let token else { return "" }
        let balance = estimate?.balance ?? balances[token.id]
        return "\(L("available_balance")): \(balance.map(Amount.show) ?? "...") \(token.symbol)"
    }

    private var receiveText: String {
        guard let estimate, Amount.isPositive(amount), estimate.error != "invalid-amount", let token else { return "--" }
        return "\(Amount.show(estimate.amount)) \(token.symbol)"
    }

    private func pickToken() {
        TokenSheet.present(tokens: model.core.walletTokens(), balances: balances, current: token?.id) { picked in
            guard picked.id != token?.id else { return }
            token = picked
            amount = ""
            estimate = nil
        }
    }

    private func loadBalances() async {
        guard let account = model.account else { return }
        let result = await account.balances()
        balances = Dictionary(uniqueKeysWithValues: result.compactMap { b in b.amount.map { (b.tokenId, $0) } })
    }

    private func reestimate() async {
        guard let account = model.account, let token else { return }
        do {
            let result = try await account.estimateSend(tokenId: token.id, to: address.trimmingCharacters(in: .whitespacesAndNewlines), amount: amount.isEmpty ? "0" : amount)
            guard !Task.isCancelled else { return }
            estimate = result
        } catch {
            guard !Task.isCancelled else { return }
            Log.app.error("estimate send failed: \(coreMessage(error), privacy: .public)")
        }
    }

    private var watching: String {
        txs.filter { $0.status == .pending }.map(\.hash).joined(separator: ",")
    }

    private func loadPending() {
        guard let account = model.account else { return }
        do {
            let pending = try account.pendingSends().sorted { $0.createdAt > $1.createdAt }
            Log.app.info("withdraw pending sends: \(pending.count)")
            // 广场网页发起的交易也列出来（标明「网页交易」），用户按报错提示来这里能找到它
            for tx in pending where !txs.contains(where: { $0.hash == tx.txHash }) {
                txs.append(SentTx(hash: tx.txHash, chainId: tx.chainId, status: .pending, dapp: tx.dapp))
            }
        } catch {
            Log.app.error("pending sends failed: \(coreMessage(error), privacy: .public)")
        }
    }

    private func confirm(notice: String? = nil) {
        guard let token, let account = model.account else { return }
        let to = address.trimmingCharacters(in: .whitespacesAndNewlines)
        let amount = amount
        Task {
            let fresh: SendEstimate
            do {
                fresh = try await account.estimateSend(tokenId: token.id, to: to, amount: amount)
            } catch {
                Log.app.error("withdraw estimate failed: \(coreMessage(error), privacy: .public)")
                Alerts.show(L("withdraw_failed"), coreMessage(error))
                return
            }
            estimate = fresh
            if let code = fresh.error {
                Log.app.notice("withdraw blocked: \(code, privacy: .public)")
                Alerts.show(nil, WalletText.error(code, estimate: fresh))
                return
            }
            let question = L("withdraw_confirm_message", Amount.show(fresh.amount), token.symbol, to)
            Alerts.show(L("withdraw_confirm_title"), notice.map { "\($0)\n\n\(question)" } ?? question, actions: [
                Alerts.action(L("cancel"), style: .cancel),
                Alerts.action(L("ok")) { send(token: token, to: to, amount: amount, fee: fresh.fee) },
            ])
        }
    }

    private func send(token: Token, to: String, amount: String, fee: String) {
        guard let account = model.account else { return }
        Task {
            let progress = Alerts.progress(L("processing"), L("withdraw_processing"))
            do {
                let hash = try await account.sendToken(tokenId: token.id, to: to, amount: amount, confirmedFee: fee)
                Log.app.info("withdraw broadcast on chain \(token.chainId)")
                await Alerts.dismiss(progress)
                txs.removeAll { $0.hash == hash }
                txs.insert(SentTx(hash: hash, chainId: token.chainId, status: .pending), at: 0)
                Alerts.show(L("v2_withdraw_submitted"), L("withdraw_success_message", Amount.show(amount), token.symbol, to), actions: [
                    Alerts.action(L("v2_copy_tx_hash")) {
                        UIPasteboard.general.string = hash
                        self.amount = ""
                        Task { await loadBalances() }
                    },
                    Alerts.action(L("ok")) { dismiss() },
                ])
            } catch {
                let code = coreMessage(error)
                Log.app.error("withdraw failed: \(code, privacy: .public)")
                await Alerts.dismiss(progress)
                if code == "fee-changed" {
                    confirm(notice: L("v2_fee_changed"))
                } else {
                    Alerts.show(L("withdraw_failed"), WalletText.error(code))
                }
            }
        }
    }

    private func poll() async {
        guard let account = model.account, !watching.isEmpty else { return }
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            var updates: [String: TxStatus] = [:]
            for tx in txs where tx.status == .pending {
                if let status = try? await account.txStatus(chainId: tx.chainId, txHash: tx.hash) { updates[tx.hash] = status }
            }
            let stillPending = Set(((try? account.pendingSends()) ?? []).map(\.txHash))
            for (hash, status) in updates {
                guard let i = txs.firstIndex(where: { $0.hash == hash }) else { continue }
                let shown: TxStatus = status == .notFound && stillPending.contains(hash) ? .pending : status
                if shown != .pending { Log.app.info("withdraw tx settled: \(WalletText.txStatus(shown), privacy: .public)") }
                txs[i].status = shown
            }
        }
    }
}
