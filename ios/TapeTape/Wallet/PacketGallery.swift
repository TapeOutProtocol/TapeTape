#if DEBUG
import SwiftUI
import TapeTapeCore

struct PacketGallery: View {
    @EnvironmentObject var model: AppModel
    @State private var detail: (Message, PacketStatus)?
    @State private var showDetail = false

    private static let now = Int64(Date().timeIntervalSince1970 * 1000)

    static func status(_ state: PacketState, claimed: UInt32 = 0, total: UInt32 = 5, mine: String? = nil, canClaim: Bool = false, canRefund: Bool = false) -> PacketStatus {
        let records = (0..<claimed).map { i in
            ClaimRecord(address: "0x\(String(format: "%02x", i * 37 + 11))a1b2c3d4e5f60718293a4b5c6d7e8f90\(String(format: "%04x", i))", amount: i == 0 && mine != nil ? mine! : "0.\(i + 1)2", contactId: nil, me: i == 0 && mine != nil)
        }
        return PacketStatus(state: state, claimedCount: claimed, totalCount: total, claimedAmount: claimed == 0 ? "0" : "0.\(claimed)4", myAmount: mine, canClaim: canClaim, canRefund: canRefund, expiresAt: now + 7 * 86_400_000, records: records, checkedAt: now)
    }

    static func message(_ id: UInt32, red: Bool, outgoing: Bool, status: PacketStatus, text: String) -> Message {
        let packet = Packet(kind: red ? .redPacket : .transfer, packetId: "p\(id)", chainId: 56, tokenId: "56:USDT", symbol: "USDT", amount: red ? "1.5" : "2.75", count: red ? status.totalCount : 1, text: text, tx: "0x", creator: "0x", mine: outgoing, status: status)
        return Message(id: id, chatId: 0, fromContact: outgoing ? nil : 99, senderName: outgoing ? "" : "Bob", text: text, time: now, state: outgoing ? .delivered : .incoming, error: nil, replyTo: nil, kind: red ? .redPacket : .transfer, filePath: nil, fileName: nil, mime: nil, size: nil, width: nil, height: nil, durationMs: nil, vcard: nil, reactions: [], forwarded: false, saved: false, originalId: nil, expiresAt: nil, system: nil, packet: packet, download: nil)
    }

    private var reds: [Message] {
        let g = L("red_packet_default_greeting")
        return [
            Self.message(9001, red: true, outgoing: false, status: Self.status(.open, claimed: 2, canClaim: true), text: g),
            Self.message(9002, red: true, outgoing: false, status: Self.status(.open, claimed: 3, mine: "0.31"), text: g),
            Self.message(9003, red: true, outgoing: true, status: Self.status(.finished, claimed: 5), text: g),
            Self.message(9004, red: true, outgoing: true, status: Self.status(.creating), text: g),
            Self.message(9005, red: true, outgoing: true, status: Self.status(.expired, claimed: 2, canRefund: true), text: g),
            Self.message(9006, red: true, outgoing: true, status: Self.status(.refunded, claimed: 2), text: g),
            Self.message(9007, red: true, outgoing: true, status: Self.status(.failed), text: g),
        ]
    }

    private var transfers: [Message] {
        [
            Self.message(9101, red: false, outgoing: false, status: Self.status(.open, total: 1, canClaim: true), text: "Lunch"),
            Self.message(9102, red: false, outgoing: true, status: Self.status(.open, total: 1), text: "Lunch"),
            Self.message(9103, red: false, outgoing: false, status: Self.status(.finished, claimed: 1, total: 1, mine: "2.75"), text: ""),
            Self.message(9104, red: false, outgoing: true, status: Self.status(.expired, total: 1, canRefund: true), text: "Rent"),
            Self.message(9105, red: false, outgoing: true, status: Self.status(.creating, total: 1), text: ""),
        ]
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(reds) { m in
                        RedPacketBubble(message: m, packet: m.packet!)
                            .onTapGesture { open(m) }
                    }
                    ForEach(transfers) { m in
                        TransferBubble(message: m, packet: m.packet!)
                            .onTapGesture { open(m) }
                    }
                    SystemPill(text: L("red_packet_claim_notification", "Carol", "Bob"), icon: "gift.fill")
                    SystemPill(text: L("v2_packet_claimed_by_you", "Bob"), icon: "gift.fill")
                }
                .padding()
            }
            .background(WallpaperBackground())
            .navigationTitle("Packet Gallery")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(isPresented: $showDetail) {
                if let detail { PacketDetailView(msgId: detail.0.id, preview: detail) }
            }
        }
    }

    private func open(_ m: Message) {
        guard let packet = m.packet else { return }
        if packet.kind == .redPacket {
            if packet.status.canClaim {
                model.packetCover = .claimRedPacket(m)
            } else {
                detail = (m, packet.status)
                showDetail = true
            }
        } else if !m.outgoing && packet.status.canClaim {
            model.packetCover = .claimTransfer(m)
        } else {
            model.packetCover = .transferStatus(m, packet.status)
        }
    }
}
#endif
