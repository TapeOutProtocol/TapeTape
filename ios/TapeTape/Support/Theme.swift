import SwiftUI
import TapeTapeCore

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xff) / 255, green: Double((hex >> 8) & 0xff) / 255, blue: Double(hex & 0xff) / 255, opacity: alpha)
    }

    static let tapetapePrimary = Color(hex: 0x555555)
    static let tapetapeDisabled = Color(hex: 0xE6E6E6)
    static let bubbleOut = Color(hex: 0xDCF8C6)
    static let bubbleIn = Color.white
    static let statusOut = Color(hex: 0x70B15C)
    static let statusIn = Color(hex: 0x808080)
    static let systemPill = Color(hex: 0x444444, alpha: 0x65 / 255.0)
    static let middleGray = Color(hex: 0x848BA7)
    static let unreadBadge = Color(hex: 0x3792FC)
    static let unreadMuted = Color(hex: 0xB6B6BB)
    static let pinnedRow = Color(hex: 0xF2F2F6)
    static let inputFill = Color(hex: 0xC8C8C8, alpha: 0.25)
    static let placeholderGray = Color(hex: 0x8C8C8C)
    static let avatarPlaceholder = Color(hex: 0xAAAAAA)
}

struct SystemPill: View {
    let text: String
    var bold = false
    var icon: String?

    var body: some View {
        ((icon.map { Text(Image(systemName: $0)) + Text(" ") } ?? Text("")) + Text(text))
            .font(.subheadline.weight(bold ? .bold : .medium))
            .foregroundColor(.white)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 16).fill(Color.systemPill))
    }
}

struct EmptyStatePill: View {
    let text: String

    var body: some View {
        Text(text)
            .foregroundColor(.white)
            .multilineTextAlignment(.center)
            .padding(15)
            .background(RoundedRectangle(cornerRadius: 16).fill(Color.systemPill))
            .padding(.horizontal, 30)
    }
}

struct UnreadBadge: View {
    let count: UInt32
    var muted = false

    var body: some View {
        Text("\(count)")
            .font(.system(size: 12))
            .foregroundColor(.white)
            .padding(.horizontal, 6)
            .frame(minWidth: 20, minHeight: 20)
            .background(Capsule().fill(muted ? Color.unreadMuted : Color.unreadBadge))
    }
}

struct DeliveryIcon: View {
    let state: MessageStateUI
    var tint: Color
    var size: CGFloat = 20

    var body: some View {
        switch state {
        case .pending:
            Image("ic_hourglass_empty_white_36pt").renderingMode(.template).resizable().frame(width: size, height: size).foregroundColor(tint)
        case .sent:
            Image("ic_done_36pt").renderingMode(.template).resizable().frame(width: size, height: size).foregroundColor(tint)
        case .read:
            Image("ic_done_all_36pt").renderingMode(.template).resizable().frame(width: size, height: size).foregroundColor(tint)
        case .failed:
            Image("ic_error_36pt").renderingMode(.template).resizable().frame(width: size, height: size).foregroundColor(.red)
        }
    }
}

enum MessageStateUI {
    case pending, sent, read, failed

    init?(_ state: MessageState?) {
        switch state {
        case .pending: self = .pending
        case .sent, .delivered: self = .sent
        case .read: self = .read
        case .failed: self = .failed
        default: return nil
        }
    }
}

extension View {
    @ViewBuilder
    func sheetCornerRadius(_ radius: CGFloat) -> some View {
        if #available(iOS 16.4, *) {
            presentationCornerRadius(radius)
        } else {
            self
        }
    }
}

/// 列表里的操作行：左边图标、文字左对齐（和「媒体」「消息定时销毁」那种行一致）。危险操作用红色
struct ListAction: View {
    let title: String
    let icon: String
    var destructive = false
    let action: () -> Void

    init(_ title: String, icon: String, destructive: Bool = false, action: @escaping () -> Void) {
        self.title = title
        self.icon = icon
        self.destructive = destructive
        self.action = action
    }

    var body: some View {
        Button(role: destructive ? .destructive : nil, action: action) {
            Label {
                Text(title).foregroundColor(destructive ? .red : .primary)
            } icon: {
                Image(systemName: icon).font(.system(size: 17)).foregroundColor(destructive ? .red : .blue)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
    }
}
