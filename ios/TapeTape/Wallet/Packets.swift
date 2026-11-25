import SwiftUI
import UIKit
import TapeTapeCore

extension Color {
    static let packetRed = Color(hex: 0xE07054)
    static let packetGold = Color(hex: 0xFDCB6E)
    static let packetOrange = Color(hex: 0xE69933)
    static let packetCoral = Color(hex: 0xFF664D)
    static let packetBeige = Color(hex: 0xF5E5D3)
    static let packetMuted = Color(hex: 0x998066)
    static let transferOrange = Color(hex: 0xFA9E38)
    static let transferBeige = Color(hex: 0xEDE3D6)
    static let transferGreen = Color(hex: 0x17C754)
    static let transferBlue = Color(hex: 0x33ADF2)
}

enum PacketCover: Identifiable {
    case claimRedPacket(Message)
    case claimTransfer(Message)
    case transferStatus(Message, PacketStatus)

    var id: String {
        switch self {
        case .claimRedPacket(let m): return "red-\(m.id)"
        case .claimTransfer(let m): return "claim-\(m.id)"
        case .transferStatus(let m, _): return "status-\(m.id)"
        }
    }
}

extension Packet {
    var claimedByMe: Bool { status.myAmount != nil }
}

enum PacketText {
    static func status(_ packet: Packet, outgoing: Bool) -> String? {
        switch packet.status.state {
        case .creating: return L("v2_packet_creating")
        case .failed: return L("v2_packet_failed")
        case .expired: return L("v2_packet_expired")
        case .refunded: return L("v2_packet_refunded")
        case .finished: return packet.claimedByMe ? L("red_packet_claimed") : L("red_packet_all_claimed")
        case .open:
            if !packet.mine && packet.status.checkedAt == 0 { return L("v2_packet_checking") }
            if packet.status.canClaim { return nil }
            return packet.claimedByMe ? L("red_packet_claimed") : nil
        }
    }

    static func transferStatus(_ packet: Packet, outgoing: Bool) -> String {
        switch packet.status.state {
        case .creating: return L("v2_packet_creating")
        case .failed: return L("v2_packet_failed")
        case .expired: return L("v2_packet_expired")
        case .refunded: return L("v2_packet_refunded")
        case .finished: return L("transfer_received")
        case .open:
            if !outgoing && packet.status.checkedAt == 0 { return L("v2_packet_checking") }
            return L(outgoing ? "transfer_waiting_receive" : "transfer_waiting_claim")
        }
    }

    static func time(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f.string(from: TimeFormat.date(ms))
    }
}

@MainActor
enum PacketRefresh {
    private static var inFlight: Set<UInt32> = []

    static func refresh(_ message: Message, account: Account?) {
        guard let account, let packet = message.packet, !inFlight.contains(message.id) else { return }
        switch packet.status.state {
        case .finished, .refunded, .failed: return
        default: break
        }
        let age = Date().timeIntervalSince1970 * 1000 - Double(packet.status.checkedAt)
        guard packet.status.state == .creating || age > 60_000 else { return }
        inFlight.insert(message.id)
        Task {
            do {
                let status = try await account.packetStatus(msgId: message.id)
                Log.chat.debug("packet \(message.id) refreshed: \(String(describing: status.state), privacy: .public)")
            } catch {
                Log.chat.notice("packet \(message.id) refresh failed: \(coreMessage(error), privacy: .public)")
            }
            inFlight.remove(message.id)
        }
    }
}

struct RedPacketBubble: View {
    let message: Message
    let packet: Packet
    @EnvironmentObject var model: AppModel

    private var active: Bool { packet.status.canClaim }

    private var subtitle: String {
        let native = model.core.walletTokens().first { $0.id == packet.tokenId }?.address == nil
        return native ? L("red_packet_title") : "\(L("red_packet_title")) · \(packet.symbol)"
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "gift.fill")
                .font(.system(size: 30))
                .foregroundColor(active ? .white : Color(hex: 0xB39980))
                .frame(width: 40, height: 40)
            VStack(alignment: .leading, spacing: 4) {
                Text(message.text.isEmpty ? L("red_packet_default_greeting") : message.text)
                    .font(.system(size: 16, weight: .semibold))
                    .lineLimit(1)
                Text(subtitle).font(.system(size: 12)).opacity(0.9).lineLimit(1)
            }
            .foregroundColor(active ? .white : .packetMuted)
            Spacer(minLength: 8)
            if let status = PacketText.status(packet, outgoing: message.outgoing) {
                Text(status).font(.system(size: 13)).foregroundColor(Color(hex: 0x999999)).fixedSize()
            }
        }
        .padding(.horizontal, 14)
        .frame(width: 320, alignment: .leading)
        .frame(minHeight: 80)
        .background(
            RoundedRectangle(cornerRadius: 12).fill(
                active
                    ? AnyShapeStyle(LinearGradient(colors: [.packetRed, .packetGold], startPoint: .topLeading, endPoint: .bottomTrailing))
                    : AnyShapeStyle(Color.packetBeige)
            )
        )
        .onAppear { PacketRefresh.refresh(message, account: model.account) }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("red-packet-\(message.id)")
    }
}

struct TransferBubble: View {
    let message: Message
    let packet: Packet
    @EnvironmentObject var model: AppModel

    private var pending: Bool { packet.status.state == .open }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Circle().fill(Color.white).frame(width: 50, height: 50).overlay(icon)
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(Amount.show(packet.amount)) \(packet.symbol)").font(.system(size: 20, weight: .medium)).lineLimit(1).minimumScaleFactor(0.6)
                    if !message.text.isEmpty {
                        Text(message.text).font(.system(size: 11)).opacity(0.7).lineLimit(1)
                    }
                }
                Spacer(minLength: 6)
                Text(PacketText.transferStatus(packet, outgoing: message.outgoing)).font(.system(size: 12)).fixedSize()
            }
            .padding(.horizontal, 12)
            .frame(maxHeight: .infinity)
            Rectangle().fill(Color.white.opacity(0.4)).frame(height: 0.5).padding(.horizontal, 12)
            Text(L("transfer_title"))
                .font(.system(size: 12))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 12)
                .padding(.vertical, 5)
        }
        .foregroundColor(pending ? .white : .packetMuted)
        .frame(width: 280, height: 95)
        .background(RoundedRectangle(cornerRadius: 8).fill(pending ? Color.transferOrange : Color.transferBeige))
        .onAppear { PacketRefresh.refresh(message, account: model.account) }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("transfer-\(message.id)")
    }

    @ViewBuilder
    private var icon: some View {
        switch packet.status.state {
        case .open: Image(systemName: "arrow.right").font(.system(size: 22, weight: .semibold)).foregroundColor(.transferOrange)
        case .finished: Image(systemName: "checkmark").font(.system(size: 22, weight: .semibold)).foregroundColor(Color(hex: 0x999999))
        case .creating: Image(systemName: "arrow.clockwise").font(.system(size: 20, weight: .semibold)).foregroundColor(Color(hex: 0x999999))
        default: Image(systemName: "arrow.uturn.left").font(.system(size: 20, weight: .semibold)).foregroundColor(Color(hex: 0x999999))
        }
    }
}

@MainActor
enum PacketFlow {
    static func tap(_ message: Message, model: AppModel) {
        guard let packet = message.packet, let account = model.account else { return }
        let red = packet.kind == .redPacket
        switch packet.status.state {
        case .creating:
            pendingAlert(message, red: red, model: model, text: L(red ? "v2_packet_on_chain" : "v2_transfer_on_chain"))
            return
        case .failed:
            Alerts.show(L("error"), message.error.map { WalletText.error($0) } ?? L("v2_packet_invalid"))
            return
        default:
            break
        }
        Task {
            do {
                let status = try await account.packetStatus(msgId: message.id)
                Log.chat.info("packet \(message.id) tapped, state \(String(describing: status.state), privacy: .public), claimable \(status.canClaim)")
                if red {
                    if status.canClaim {
                        model.packetCover = .claimRedPacket(message)
                    } else {
                        model.push(.packetDetail(message.id))
                    }
                } else if !message.outgoing && status.canClaim {
                    model.packetCover = .claimTransfer(message)
                } else {
                    model.packetCover = .transferStatus(message, status)
                }
            } catch {
                Log.chat.error("packet \(message.id) status failed: \(coreMessage(error), privacy: .public)")
                pendingAlert(message, red: red, model: model, text: L("v2_packet_query_failed"))
            }
        }
    }

    private static func pendingAlert(_ message: Message, red: Bool, model: AppModel, text: String) {
        Alerts.show(L("error"), text, actions: [
            Alerts.action(L(red ? "red_packet_view" : "transfer_view")) {
                if red {
                    model.push(.packetDetail(message.id))
                } else if let packet = message.packet {
                    model.packetCover = .transferStatus(message, packet.status)
                }
            },
            Alerts.action(L("cancel"), style: .cancel),
        ])
    }

    static func refund(_ msgId: UInt32, symbol: String, amount: String, model: AppModel, done: @escaping () -> Void) {
        guard let account = model.account else { return }
        Alerts.show(L("v2_refund"), L("v2_refund_confirm", Amount.show(amount), symbol), actions: [
            Alerts.action(L("cancel"), style: .cancel),
            Alerts.action(L("v2_refund")) {
                Task {
                    let progress = Alerts.progress(L("processing"), L("v2_refunding"))
                    do {
                        _ = try await account.refundPacket(msgId: msgId)
                        Log.chat.info("packet \(msgId) refunded")
                        await Alerts.dismiss(progress)
                        Alerts.show(L("v2_refund_success"), nil)
                        done()
                    } catch {
                        Log.chat.error("packet \(msgId) refund failed: \(coreMessage(error), privacy: .public)")
                        await Alerts.dismiss(progress)
                        Alerts.show(L("error"), WalletText.error(coreMessage(error)))
                    }
                }
            },
        ])
    }
}

struct RedPacketClaimView: View {
    let message: Message
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    private var senderName: String { message.outgoing ? (model.info?.name ?? "") : message.senderName }

    var body: some View {
        ZStack {
            Color.packetRed.ignoresSafeArea()
            VStack(spacing: 16) {
                AvatarView(name: senderName, seed: model.contactAddress(message.fromContact) ?? senderName, path: message.outgoing ? model.info?.avatarPath : model.contact(message.fromContact)?.avatarPath, size: 40)
                    .padding(.top, 80)
                Text(L("red_packet_from_sender", senderName)).font(.system(size: 18, weight: .semibold))
                Text(message.text).font(.system(size: 22, weight: .bold)).multilineTextAlignment(.center).padding(.horizontal, 32)
                Spacer()
                Button(action: claim) {
                    Text(L("red_packet_claim"))
                        .font(.system(size: 48, weight: .bold))
                        .foregroundColor(.packetRed)
                        .frame(width: 150, height: 150)
                        .background(Circle().fill(Color.packetGold))
                }
                .accessibilityIdentifier("open-red-packet")
                Spacer()
                Button {
                    dismiss()
                    model.push(.packetDetail(message.id))
                } label: {
                    Text(L("v2_view_claim_details")).font(.system(size: 14))
                }
                .padding(.bottom, 30)
            }
            .foregroundColor(.white)
        }
        .overlay(alignment: .topLeading) {
            Button { dismiss() } label: {
                Image(systemName: "xmark").font(.system(size: 17, weight: .semibold)).foregroundColor(.white).frame(width: 44, height: 44)
            }
            .accessibilityLabel(L("close"))
            .padding(.leading, 8)
        }
    }

    private func claim() {
        guard let account = model.account, let packet = message.packet else { return }
        Task {
            let progress = Alerts.progress(nil, L("red_packet_claiming"))
            do {
                let amount = try await account.claimPacket(msgId: message.id)
                Log.chat.info("red packet \(message.id) claimed")
                await Alerts.dismiss(progress)
                Alerts.show(L("red_packet_claim_success"), "\(Amount.show(amount)) \(packet.symbol)", actions: [
                    Alerts.action(L("ok")) {
                        dismiss()
                        model.push(.packetDetail(message.id))
                    },
                ])
            } catch {
                Log.chat.error("red packet \(message.id) claim failed: \(coreMessage(error), privacy: .public)")
                await Alerts.dismiss(progress)
                Alerts.show(L("error"), WalletText.error(coreMessage(error)))
            }
        }
    }
}

struct PacketDetailView: View {
    let msgId: UInt32
    var preview: (Message, PacketStatus)?
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var message: Message?
    @State private var status: PacketStatus?

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                CurvedHeader().fill(Color.packetRed).frame(height: 120)
                if let message, let packet = message.packet {
                    header(message, packet)
                    if let status { records(status, packet) }
                }
            }
        }
        .ignoresSafeArea(edges: .top)
        .overlay { if status == nil { ProgressView().controlSize(.large) } }
        .background(Color(.systemBackground))
        .navigationTitle(L("red_packet_detail"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .task { await load() }
    }

    @ViewBuilder
    private func header(_ message: Message, _ packet: Packet) -> some View {
        let sender = message.outgoing ? (model.info?.name ?? "") : message.senderName
        VStack(spacing: 8) {
            Text(L("red_packet_from_sender", sender)).font(.system(size: 17, weight: .medium))
            Text(message.text).font(.system(size: 16)).foregroundColor(.secondary).multilineTextAlignment(.center)
            if let mine = status?.myAmount {
                Text(Amount.show(mine)).font(.system(size: 56, weight: .bold)).foregroundColor(.packetGold)
                    + Text(" \(packet.symbol)").font(.system(size: 17, weight: .medium)).foregroundColor(.packetGold)
            }
            if let status {
                Text(L("red_packet_summary_simple", Int(status.claimedCount), Int(status.totalCount), Amount.show(status.claimedAmount), "\(Amount.show(packet.amount)) \(packet.symbol)"))
                    .font(.system(size: 13))
                    .foregroundColor(.secondary)
                if let state = stateLine(status) {
                    Text(state).font(.system(size: 13)).foregroundColor(.packetOrange)
                }
                if status.canRefund {
                    Button {
                        PacketFlow.refund(msgId, symbol: packet.symbol, amount: remaining(status, packet), model: model) { Task { await load() } }
                    } label: {
                        Text(L("v2_refund"))
                            .font(.system(size: 16, weight: .medium))
                            .foregroundColor(.white)
                            .frame(width: 160, height: 40)
                            .background(RoundedRectangle(cornerRadius: 8).fill(Color.packetCoral))
                    }
                    .padding(.top, 4)
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
    }

    private func records(_ status: PacketStatus, _ packet: Packet) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L("red_packet_claim_records"))
                .font(.footnote)
                .foregroundColor(.secondary)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            ForEach(Array(status.records.enumerated()), id: \.offset) { _, record in
                ClaimRecordRow(record: record, symbol: packet.symbol)
                Divider().padding(.leading, 72)
            }
            Text(L("v2_packet_refund_notice"))
                .font(.system(size: 11))
                .foregroundColor(Color(.tertiaryLabel))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
        }
    }

    private func stateLine(_ status: PacketStatus) -> String? {
        switch status.state {
        case .expired: return L("v2_packet_expired")
        case .refunded: return L("v2_packet_refunded")
        case .creating: return L("v2_packet_creating")
        case .failed: return L("v2_packet_invalid")
        default: return nil
        }
    }

    private func remaining(_ status: PacketStatus, _ packet: Packet) -> String {
        let total = Decimal(string: packet.amount) ?? 0
        let claimed = Decimal(string: status.claimedAmount) ?? 0
        return "\(total - claimed)"
    }

    private func load() async {
        if let preview {
            message = preview.0
            status = preview.1
            return
        }
        guard let account = model.account else { return }
        do {
            message = try account.message(msgId: msgId)
            status = message?.packet?.status
            status = try await account.packetStatus(msgId: msgId)
            message = try account.message(msgId: msgId)
        } catch {
            Log.chat.error("packet detail \(msgId) failed: \(coreMessage(error), privacy: .public)")
            Alerts.show(L("error"), WalletText.error(coreMessage(error)), actions: [
                Alerts.action(L("retry")) { Task { await load() } },
                Alerts.action(L("cancel"), style: .cancel) { dismiss() },
            ])
        }
    }
}

struct ClaimRecordRow: View {
    let record: ClaimRecord
    let symbol: String

    private var short: String {
        record.address.count > 10 ? "\(record.address.prefix(6))...\(record.address.suffix(4))" : record.address
    }

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(AvatarColor.color(for: record.address.lowercased()))
                .frame(width: 40, height: 40)
                .overlay(Text(String(record.address.dropFirst(2).prefix(2)).uppercased()).font(.system(size: 14, weight: .semibold)).foregroundColor(.white))
            HStack(spacing: 4) {
                Text(short).font(.system(size: 15, design: .monospaced))
                if record.me { Text(L("v2_me_paren")).font(.system(size: 15)).foregroundColor(.orange) }
            }
            Spacer()
            Text("\(Amount.show(record.amount)) \(symbol)").font(.system(size: 17, weight: .medium))
        }
        .padding(.horizontal, 16)
        .frame(height: 60)
    }
}

struct CurvedHeader: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: .zero)
        p.addLine(to: CGPoint(x: rect.maxX, y: 0))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - 30))
        p.addQuadCurve(to: CGPoint(x: 0, y: rect.maxY - 30), control: CGPoint(x: rect.midX, y: rect.maxY + 30))
        p.closeSubpath()
        return p
    }
}

struct TransferClaimView: View {
    let message: Message
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        if let packet = message.packet {
            TransferCard(message: message, packet: packet, amountSize: 56) {
                Circle().fill(Color.transferBlue).frame(width: 90, height: 90)
                    .overlay(Image(systemName: "clock").font(.system(size: 40)).foregroundColor(.white))
            } title: {
                Text(L("transfer_waiting_for_you")).font(.system(size: 17))
            } footer: {
                VStack(spacing: 14) {
                    Button(action: { claim(packet) }) {
                        Text(L("transfer_claim"))
                            .font(.system(size: 17, weight: .medium))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 50)
                            .background(RoundedRectangle(cornerRadius: 8).fill(Color.transferGreen))
                    }
                    .accessibilityIdentifier("claim-transfer")
                    Text(L("v2_transfer_refund_notice")).font(.system(size: 12)).foregroundColor(.secondary).multilineTextAlignment(.center)
                }
            } close: { dismiss() }
        }
    }

    private func claim(_ packet: Packet) {
        guard let account = model.account else { return }
        Task {
            let progress = Alerts.progress(nil, L("transfer_claiming"))
            do {
                let amount = try await account.claimPacket(msgId: message.id)
                Log.chat.info("transfer \(message.id) claimed")
                await Alerts.dismiss(progress)
                Alerts.show(L("transfer_claim_success"), "\(Amount.show(amount)) \(packet.symbol)", actions: [
                    Alerts.action(L("ok")) { dismiss() },
                ])
            } catch {
                Log.chat.error("transfer \(message.id) claim failed: \(coreMessage(error), privacy: .public)")
                await Alerts.dismiss(progress)
                Alerts.show(L("error"), WalletText.error(coreMessage(error)))
            }
        }
    }
}

struct TransferStatusView: View {
    let message: Message
    let status: PacketStatus
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        if let packet = message.packet {
            TransferCard(message: message, packet: packet, amountSize: 48) {
                let (icon, color) = look
                Circle().fill(color).frame(width: 90, height: 90)
                    .overlay(Image(systemName: icon).font(.system(size: 40, weight: .semibold)).foregroundColor(.white))
            } title: {
                Text(text).font(.system(size: 17)).multilineTextAlignment(.center)
            } footer: {
                if status.canRefund {
                    Button {
                        PacketFlow.refund(message.id, symbol: packet.symbol, amount: packet.amount, model: model) { dismiss() }
                    } label: {
                        Text(L("v2_refund"))
                            .font(.system(size: 17, weight: .medium))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 50)
                            .background(RoundedRectangle(cornerRadius: 8).fill(Color.transferOrange))
                    }
                }
            } close: { dismiss() }
        }
    }

    private var look: (String, Color) {
        if status.myAmount != nil { return ("checkmark", .transferGreen) }
        switch status.state {
        case .finished: return ("checkmark", Color(hex: 0x999999))
        case .refunded, .expired, .failed: return ("arrow.uturn.left", Color(hex: 0x999999))
        default: return message.outgoing ? ("arrow.right", .transferOrange) : ("clock", .transferBlue)
        }
    }

    private var text: String {
        if status.myAmount != nil { return L("transfer_status_claimed_by_me") }
        switch status.state {
        case .finished: return L("transfer_status_claimed_by_receiver")
        case .refunded: return L("v2_packet_refunded")
        case .expired: return L(message.outgoing ? "v2_transfer_expired_sender" : "v2_packet_expired")
        case .failed: return L("v2_packet_invalid")
        case .creating: return L("v2_packet_creating")
        case .open: return L(message.outgoing ? "transfer_status_sent_by_you" : "transfer_waiting_for_you")
        }
    }
}

struct TransferCard<Icon: View, Title: View, Footer: View>: View {
    let message: Message
    let packet: Packet
    let amountSize: CGFloat
    @ViewBuilder let icon: () -> Icon
    @ViewBuilder let title: () -> Title
    @ViewBuilder let footer: () -> Footer
    let close: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            HStack {
                Button(action: close) {
                    Image(systemName: "chevron.left").font(.system(size: 20, weight: .semibold)).foregroundColor(.primary).frame(width: 44, height: 44)
                }
                .accessibilityLabel(L("back"))
                Spacer()
            }
            icon().padding(.top, 20)
            title()
            Text("\(Amount.show(packet.amount)) \(packet.symbol)")
                .font(.system(size: amountSize, weight: .medium))
                .minimumScaleFactor(0.5)
                .lineLimit(1)
            Divider()
            VStack(spacing: 12) {
                if !message.text.isEmpty { row(L("transfer_note"), message.text) }
                row(L("transfer_time"), PacketText.time(message.time))
            }
            Spacer()
            footer()
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 24)
        .background(Color(.systemBackground).ignoresSafeArea())
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(label).foregroundColor(.secondary)
            Spacer()
            Text(value).multilineTextAlignment(.trailing)
        }
        .font(.system(size: 15))
    }
}

struct PacketForm<Rows: View>: View {
    let title: String
    let big: String
    let buttonTitle: String
    let buttonColor: Color
    let action: () -> Void
    @ViewBuilder let rows: () -> Rows
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                rows()
                    .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                Section {
                    VStack(spacing: 20) {
                        Text(big).font(.system(size: 48, weight: .medium)).lineLimit(1).minimumScaleFactor(0.4)
                        Button(action: action) {
                            Text(buttonTitle)
                                .font(.system(size: 18, weight: .medium))
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .frame(height: 50)
                                .background(RoundedRectangle(cornerRadius: 8).fill(buttonColor))
                        }
                        .buttonStyle(.plain)
                        .padding(.horizontal, 40)
                        .accessibilityIdentifier("packet-send")
                    }
                    .frame(maxWidth: .infinity, minHeight: 150)
                    .listRowBackground(Color.clear)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                        .accessibilityLabel(L("cancel"))
                }
            }
        }
    }
}

struct IconTile: View {
    let color: Color
    let content: String
    var bold = false

    var body: some View {
        Text(content)
            .font(.system(size: bold ? 14 : 13, weight: bold ? .bold : .regular))
            .foregroundColor(.white)
            .frame(width: 24, height: 24)
            .background(RoundedRectangle(cornerRadius: 4).fill(color))
    }
}

struct PacketTokenState {
    var token: Token?
    var balances: [String: String] = [:]

    var tokens: [Token] = []

    func balanceText(_ token: Token) -> String {
        balances[token.id].map { "\(token.symbol) (\(Amount.show($0)) \(token.symbol))" } ?? "\(token.symbol) (...)"
    }
}

struct SendPacketView: View {
    let chat: Chat
    let red: Bool
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var state = PacketTokenState()
    @State private var count = ""
    @State private var amount = ""
    @State private var text = ""

    private var symbol: String { state.token?.symbol ?? "" }

    var body: some View {
        PacketForm(
            title: L(red ? "send_red_packet" : "send_transfer"),
            big: "\(amount.isEmpty ? (red ? "0" : "0.00") : Amount.show(amount)) \(symbol)",
            buttonTitle: L(red ? "red_packet_send_button" : "transfer_send_button"),
            buttonColor: red ? .packetCoral : Color(hex: 0x00C754),
            action: submit
        ) {
            if red {
                Section {
                    tokenRow
                    HStack {
                        IconTile(color: .packetCoral, content: "🧧")
                        Text(L("red_packet_count")).lineLimit(1).layoutPriority(1)
                        TextField("", text: $count).keyboardType(.numberPad).multilineTextAlignment(.trailing).accessibilityIdentifier("packet-count")
                        Text(L("red_packet_count_unit")).foregroundColor(.secondary)
                    }
                    .frame(height: 50)
                    amountRow(tile: IconTile(color: .packetOrange, content: "拼", bold: true), label: L("red_packet_total_amount", symbol))
                    HStack {
                        TextField(L("red_packet_default_greeting"), text: $text)
                        Text("☺️")
                    }
                    .frame(height: 50)
                } header: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("red_packet_lucky") + " ▼").font(.system(size: 16)).foregroundColor(.packetOrange)
                        Text(L("red_packet_group_members", Int(chat.memberCount))).font(.system(size: 12)).foregroundColor(.secondary)
                    }
                    .textCase(nil)
                }
            } else {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L("transfer_to", chat.name)).font(.system(size: 16))
                        Text(model.contact(chat.contactId)?.address ?? "").font(.system(size: 14)).foregroundColor(.secondary)
                    }
                    .padding(.vertical, 4)
                }
                Section {
                    tokenRow
                    amountRow(tile: IconTile(color: Color(hex: 0x00C754), content: "💰"), label: L("transfer_amount"))
                    TextField(L("transfer_note_placeholder"), text: $text)
                        .frame(height: 50)
                        .onChange(of: text) { value in if value.count > 60 { text = String(value.prefix(60)) } }
                } header: {
                    Text(L("v2_transfer_note_hint")).font(.system(size: 12)).textCase(nil)
                }
            }
        }
        .task { await load() }
    }

    private var tokenRow: some View {
        Button {
            TokenSheet.present(tokens: state.tokens) { picked in
                guard picked.id != state.token?.id else { return }
                state.token = picked
                amount = ""
            }
        } label: {
            HStack {
                IconTile(color: .blue, content: "💎")
                Text(L("transfer_token")).foregroundColor(.primary)
                Spacer()
                Text(state.token.map(state.balanceText) ?? "").foregroundColor(.secondary)
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundColor(Color(.tertiaryLabel))
            }
            .frame(height: 50)
        }
    }

    private func amountRow(tile: IconTile, label: String) -> some View {
        HStack {
            tile
            Text(label).lineLimit(1).layoutPriority(1)
            TextField("0.0", text: $amount)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .accessibilityIdentifier("packet-amount")
                .onChange(of: amount) { value in
                    let clean = Amount.sanitize(value, decimals: 8)
                    if clean != value { amount = clean }
                }
            Text(symbol).foregroundColor(.secondary)
        }
        .frame(height: 50)
    }

    private func load() async {
        state.tokens = model.core.walletTokens().filter(\.packets)
        if state.token == nil { state.token = state.tokens.first }
        guard let account = model.account else { return }
        let result = await account.balances()
        state.balances = Dictionary(uniqueKeysWithValues: result.compactMap { b in b.amount.map { (b.tokenId, $0) } })
    }

    private func submit() {
        guard let token = state.token, let account = model.account else { return }
        guard Amount.isPositive(amount) else {
            Alerts.show(L("error"), L("withdraw_invalid_amount"))
            return
        }
        let n = red ? UInt32(count) ?? 0 : 1
        guard (1...100).contains(n) else {
            Alerts.show(L("error"), L("red_packet_invalid_count"))
            return
        }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let order = PacketOrder(account: account, chatId: chat.id, red: red, token: token, amount: amount, count: n,
                                text: red && trimmed.isEmpty ? L("red_packet_default_greeting") : trimmed)
        let dismiss = dismiss
        Task { await order.ask { dismiss() } }
    }
}

@MainActor
struct PacketOrder {
    let account: Account
    let chatId: UInt32
    let red: Bool
    let token: Token
    let amount: String
    let count: UInt32
    let text: String

    func ask(notice: String? = nil, onSend: @escaping () -> Void = {}) async {
        let estimate: SendEstimate
        do {
            estimate = red
                ? try await account.estimatePacket(tokenId: token.id, amount: amount, count: count)
                : try await account.estimateTransfer(chatId: chatId, tokenId: token.id, amount: amount)
        } catch {
            Log.chat.error("packet estimate failed: \(coreMessage(error), privacy: .public)")
            Alerts.show(L("error"), WalletText.error(coreMessage(error), packet: true))
            return
        }
        if let code = estimate.error {
            Log.chat.notice("packet blocked: \(code, privacy: .public)")
            Alerts.show(L("error"), WalletText.error(code, estimate: estimate, packet: true))
            return
        }
        let question = red
            ? L("red_packet_confirm_send", Amount.show(amount), token.symbol, Int(count))
            : L("transfer_confirm_send", Amount.show(amount), token.symbol)
        Alerts.show(nil, notice.map { "\($0)\n\n\(question)" } ?? question, actions: [
            Alerts.action(L("cancel"), style: .cancel),
            Alerts.action(L("ok")) {
                onSend()
                Task { await send(fee: estimate.fee) }
            },
        ])
    }

    private func send(fee: String) async {
        let kind = red ? "red packet" : "transfer"
        do {
            let msgId = red
                ? try await account.sendRedPacket(chatId: chatId, tokenId: token.id, amount: amount, count: count, greeting: text, confirmedFee: fee)
                : try await account.sendTransfer(chatId: chatId, tokenId: token.id, amount: amount, note: text, confirmedFee: fee)
            Log.chat.info("\(kind, privacy: .public) \(msgId) created on chain")
        } catch {
            let code = coreMessage(error)
            Log.chat.error("\(kind, privacy: .public) send failed: \(code, privacy: .public)")
            if code == "fee-changed" {
                await ask(notice: L("v2_fee_changed"))
            } else {
                Alerts.show(L("error"), WalletText.error(code, packet: true))
            }
        }
    }
}
