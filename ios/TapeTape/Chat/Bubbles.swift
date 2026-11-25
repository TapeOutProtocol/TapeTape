import SwiftUI
import TapeTapeCore

struct BubbleActions {
    var reply: (Message) -> Void = { _ in }
    var quoteTap: (UInt32) -> Void = { _ in }
    var react: (Message, String) -> Void = { _, _ in }
    var pickReaction: (Message) -> Void = { _ in }
    var showReactions: (Message) -> Void = { _ in }
    var forward: (Message) -> Void = { _ in }
    var toggleSaved: (Message) -> Void = { _ in }
    var moreOptions: (Message) -> Void = { _ in }
    var openMedia: (Message) -> Void = { _ in }
    var toggleSelect: (UInt32) -> Void = { _ in }
    var goToOriginal: (Message) -> Void = { _ in }
    var command: (String) -> Void = { _ in }
    var replyPrivately: (Message) -> Void = { _ in }
    var openSender: (UInt32) -> Void = { _ in }
}

private struct BubbleWidthKey: EnvironmentKey {
    static let defaultValue: CGFloat = 340
}

extension EnvironmentValues {
    var bubbleMaxWidth: CGFloat {
        get { self[BubbleWidthKey.self] }
        set { self[BubbleWidthKey.self] = newValue }
    }
}

struct BubbleRow: View {
    let message: Message
    let quoted: Message?
    let canReply: Bool
    let selection: Set<UInt32>?
    var highlight: String = ""
    var isGroup = false
    let actions: BubbleActions
    @EnvironmentObject var model: AppModel
    @Environment(\.bubbleMaxWidth) private var maxWidth
    @State private var dragX: CGFloat = 0

    private var outgoing: Bool { message.outgoing }
    private var selecting: Bool { selection != nil }
    private var showSender: Bool { isGroup && !outgoing }
    private var senderSeed: String { model.contactAddress(message.fromContact) ?? message.senderName }

    var body: some View {
        if message.kind == .system {
            SystemPill(text: SystemText.text(for: message) { model.contact($0)?.name ?? "" }, icon: message.isPacketClaim ? "gift.fill" : nil)
                .padding(.horizontal, 30)
                .padding(.vertical, 3)
        } else {
            HStack(spacing: 0) {
                if selecting {
                    Image(systemName: selection?.contains(message.id) == true ? "checkmark.circle.fill" : "circle")
                        .font(.system(size: 22))
                        .foregroundColor(selection?.contains(message.id) == true ? .blue : Color(.systemGray3))
                        .padding(.leading, 12)
                }
                ZStack(alignment: .leading) {
                    Image(systemName: "arrowshape.turn.up.left.fill")
                        .foregroundColor(.tapetapePrimary)
                        .opacity(Double(min(dragX / 60, 1)))
                        .padding(.leading, 14)
                    HStack(alignment: .bottom, spacing: 8) {
                        if outgoing { goToOriginal }
                        if showSender { senderAvatar }
                        content
                        if !outgoing { goToOriginal }
                    }
                    .frame(maxWidth: .infinity, alignment: outgoing ? .trailing : .leading)
                    .padding(.leading, outgoing ? 50 : 6)
                    .padding(.trailing, outgoing ? 6 : 50)
                    .offset(x: dragX)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture { if selecting { actions.toggleSelect(message.id) } }
            .simultaneousGesture(selecting || !canReply ? nil : swipeToReply)
        }
    }

    private var swipeToReply: some Gesture {
        DragGesture(minimumDistance: 20)
            .onChanged { g in
                guard abs(g.translation.width) > abs(g.translation.height) * 2 else { return }
                dragX = min(max(0, g.translation.width), 80)
            }
            .onEnded { _ in
                if dragX >= 60 { actions.reply(message) }
                withAnimation(.easeOut(duration: 0.2)) { dragX = 0 }
            }
    }

    private var senderAvatar: some View {
        Button { if let id = message.fromContact { actions.openSender(id) } } label: {
            AvatarView(name: message.senderName, seed: senderSeed, path: model.contact(message.fromContact)?.avatarPath, size: 28)
        }
        .buttonStyle(.plain)
        .padding(.bottom, message.reactions.isEmpty ? 0 : 16)
        .accessibilityLabel(message.senderName)
    }

    @ViewBuilder
    private var senderLabel: some View {
        if showSender {
            Text(message.senderName)
                .font(.caption.weight(.bold))
                .foregroundColor(AvatarColor.color(for: senderSeed))
                .lineLimit(1)
        }
    }

    @ViewBuilder
    private var goToOriginal: some View {
        if message.originalId != nil {
            Button { actions.goToOriginal(message) } label: {
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(.tapetapePrimary)
                    .frame(width: 32, height: 32)
                    .background(Circle().fill(Color.white.opacity(0.9)))
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        let core = VStack(alignment: outgoing ? .trailing : .leading, spacing: 0) {
            if message.kind == .sticker {
                StickerContent(message: message)
            } else if let packet = message.packet {
                VStack(alignment: .leading, spacing: 4) {
                    senderLabel.padding(.leading, 4)
                    if packet.kind == .redPacket {
                        RedPacketBubble(message: message, packet: packet)
                    } else {
                        TransferBubble(message: message, packet: packet)
                    }
                }
            } else {
                bubble
            }
        }
        .padding(.bottom, message.reactions.isEmpty ? 0 : 16)
        .overlay(alignment: outgoing ? .bottomTrailing : .bottomLeading) {
            if !message.reactions.isEmpty {
                ReactionPills(reactions: message.reactions) { actions.showReactions(message) }
                    .padding(.horizontal, 10)
            }
        }
        if selecting {
            core.allowsHitTesting(false)
        } else {
            core
                .onTapGesture { tap() }
                .contextMenu { menu }
        }
    }

    private var bubble: some View {
        Group {
            if message.isMedia {
                MediaBubble(message: message, quoted: quoted, highlight: highlight, actions: actions, sender: showSender ? AnyView(senderLabel) : nil)
            } else {
                BubbleLayout {
                    senderLabel
                    if message.forwarded { ForwardedLabel() }
                    if let quoted {
                        QuoteBlock(message: quoted).onTapGesture { actions.quoteTap(quoted.id) }
                    }
                    switch message.kind {
                    case .file:
                        FileRow(name: message.fileName ?? L("file"), size: Int64(message.size ?? 0))
                    case .voice, .audio:
                        VoiceRow(message: message)
                    default:
                        EmptyView()
                    }
                    if message.download != nil {
                        DownloadLink(message: message)
                    }
                    switch message.kind {
                    case .vcard:
                        VcardRow(message: message)
                    default:
                        EmptyView()
                    }
                    if !message.text.isEmpty {
                        LinkText(text: message.text, highlight: highlight, onCommand: actions.command)
                    }
                    StatusLine(message: message)
                }
                .padding(.horizontal, 12)
                .padding(.top, 11)
                .padding(.bottom, 7)
                .background(BubbleShape(outgoing: outgoing).fill(outgoing ? Color.bubbleOut : Color.bubbleIn))
            }
        }
        .contentShape(BubbleShape(outgoing: outgoing))
    }

    private func tap() {
        if message.packet != nil {
            PacketFlow.tap(message, model: model)
            return
        }
        if message.state == .failed {
            Log.chat.info("show error for failed message \(message.id): \(message.error ?? "", privacy: .public)")
            model.alert = AlertItem(title: relayErrorText(message.error ?? ""), message: nil, confirm: nil, action: nil)
            return
        }
        if message.download != nil {
            startDownload()
            return
        }
        switch message.kind {
        case .image, .gif, .video, .file:
            actions.openMedia(message)
        case .vcard:
            guard let card = message.vcard else { return }
            model.alert = AlertItem(title: L("ask_start_chat_with", card.name), message: nil, confirm: L("ok")) { [model] in
                guard let account = model.account else { return }
                model.perform("chat from vcard") {
                    let chatId = try account.createChat(account: card.account, name: card.name)
                    model.reloadContacts()
                    model.openChat(chatId)
                }
            }
        default:
            break
        }
    }

    private func startDownload() {
        guard message.download != .downloading else { return }
        model.perform("download message \(message.id)") { try model.account?.download(msgId: message.id) }
    }

    @ViewBuilder
    private var menu: some View {
        if canReply {
            reactionRow
            Button { actions.reply(message) } label: { Label(L("reply_noun"), systemImage: "arrowshape.turn.up.left.fill") }
        }
        if showSender {
            Button { actions.replyPrivately(message) } label: { Label(L("reply_privately"), systemImage: "arrowshape.turn.up.left") }
        }
        if message.packet == nil {
            Button { actions.forward(message) } label: { Label(L("forward"), systemImage: "arrowshape.forward.fill") }
        }
        Button { actions.toggleSaved(message) } label: {
            Label(L(message.saved ? "unsave" : "save"), systemImage: message.saved ? "star.slash" : "star")
        }
        if let link = LinkText.firstLink(in: message.text) {
            Button { UIPasteboard.general.string = link } label: { Label(L("menu_copy_link_to_clipboard"), systemImage: "link") }
        }
        if !message.text.isEmpty {
            Button { UIPasteboard.general.string = message.text } label: {
                Label(L(message.hasFile ? "menu_copy_text_to_clipboard" : "global_menu_edit_copy_desktop"), systemImage: "doc.on.doc")
            }
        }
        if message.kind == .image, let path = message.filePath {
            Button { UIPasteboard.general.image = UIImage(contentsOfFile: path) } label: {
                Label(L("menu_copy_image_to_clipboard"), systemImage: "photo.on.rectangle")
            }
        }
        Divider()
        Button { actions.moreOptions(message) } label: { Label(L("menu_more_options"), systemImage: "checkmark.circle") }
    }

    @ViewBuilder
    private var reactionRow: some View {
        let mine = message.myReaction
        let quick = ["👍", "👎", "❤️"]
        let group = ControlGroup {
            ForEach(quick, id: \.self) { emoji in
                Button { actions.react(message, emoji) } label: { Text(mine == emoji ? "\(emoji)✓" : emoji) }
            }
            if let mine, !quick.contains(mine) {
                Button { actions.react(message, mine) } label: { Text("\(mine)✓") }
            } else {
                Button { actions.pickReaction(message) } label: { Text("•••") }
            }
        }
        if #available(iOS 17.0, *) {
            group.controlGroupStyle(.palette).menuActionDismissBehavior(.enabled)
        } else {
            group
        }
    }
}

struct DownloadLink: View {
    let message: Message
    @EnvironmentObject var model: AppModel

    private var title: String {
        switch message.download {
        case .downloading:
            if let t = model.transfers[message.id] { return "\(L("downloading")) \(t.percent)%" }
            return L("downloading")
        case .failed: return L("download_failed")
        default: return "\(L("download")) (\(ByteCountFormatter.string(fromByteCount: Int64(message.size ?? 0), countStyle: .file)))"
        }
    }

    var body: some View {
        Text(title)
            .font(.subheadline.weight(.medium))
            .foregroundColor(message.download == .failed ? .red : .blue)
            .accessibilityAddTraits(.isButton)
    }
}

struct ForwardedLabel: View {
    var body: some View {
        Text(L("forwarded_message")).font(.caption.weight(.medium)).foregroundColor(Color(hex: 0x999999))
    }
}

struct StatusLine: View {
    let message: Message
    var onMedia = false
    @EnvironmentObject var model: AppModel

    private var tint: Color { onMedia ? .white : (message.outgoing ? .statusOut : .statusIn) }

    var body: some View {
        HStack(spacing: 3) {
            if message.outgoing, message.state == .pending, let t = model.transfers[message.id] {
                Text("\(t.percent)%").font(.caption.monospacedDigit())
            }
            Text((message.saved ? "★ " : "") + TimeFormat.bubble(message.time)).font(.caption)
            Image("ic_lock").renderingMode(.template).resizable().scaledToFit().frame(width: 20, height: 20)
            if message.outgoing, let state = MessageStateUI(message.state) {
                DeliveryIcon(state: state, tint: tint, size: 18)
            }
        }
        .foregroundColor(tint)
        .padding(.horizontal, onMedia ? 6 : 0)
        .background {
            if onMedia { Capsule().fill(Color.black.opacity(0.45)) }
        }
    }
}

struct MediaBubble: View {
    let message: Message
    let quoted: Message?
    let highlight: String
    let actions: BubbleActions
    var sender: AnyView?
    @Environment(\.bubbleMaxWidth) private var maxWidth

    private var size: CGSize {
        let w = maxWidth
        guard let iw = message.width, let ih = message.height, iw > 0, ih > 0 else { return CGSize(width: w * 0.75, height: w * 0.75) }
        let ratio = CGFloat(ih) / CGFloat(iw)
        let width = min(w, max(120, CGFloat(iw)))
        return CGSize(width: width, height: min(max(width * ratio, 90), w * 1.3))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if message.forwarded || quoted != nil || sender != nil {
                VStack(alignment: .leading, spacing: 4) {
                    if let sender { sender }
                    if message.forwarded { ForwardedLabel() }
                    if let quoted { QuoteBlock(message: quoted).onTapGesture { actions.quoteTap(quoted.id) } }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            ZStack {
                if let path = message.filePath {
                    if message.kind == .gif {
                        AnimatedImage(path: path)
                    } else {
                        FileImage(path: path)
                    }
                } else {
                    Color(.systemGray5)
                }
                if message.download != nil {
                    DownloadLink(message: message)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Capsule().fill(Color.white.opacity(0.9)))
                } else if message.kind == .video {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 50))
                        .foregroundStyle(.white, Color.black.opacity(0.4))
                }
            }
            .frame(width: size.width, height: size.height)
            .clipped()
            .onDrag {
                guard let path = message.filePath else { return NSItemProvider() }
                return NSItemProvider(contentsOf: URL(fileURLWithPath: path)) ?? NSItemProvider()
            }
            .overlay(alignment: .bottomTrailing) {
                if message.text.isEmpty { StatusLine(message: message, onMedia: true).padding(6) }
            }
            if !message.text.isEmpty {
                BubbleLayout {
                    LinkText(text: message.text, highlight: highlight, onCommand: actions.command)
                    StatusLine(message: message)
                }
                .padding(.horizontal, 12)
                .padding(.top, 8)
                .padding(.bottom, 7)
                .frame(width: size.width, alignment: .leading)
            }
        }
        .frame(width: size.width)
        .background(message.outgoing ? Color.bubbleOut : Color.bubbleIn)
        .clipShape(BubbleShape(outgoing: message.outgoing))
    }
}

struct StickerContent: View {
    let message: Message
    @Environment(\.bubbleMaxWidth) private var maxWidth

    private var side: CGFloat {
        let natural = CGFloat(message.width ?? 160) / UIScreen.main.scale * 2
        return min(max(natural, 100), (maxWidth + 56) / 2)
    }

    var body: some View {
        VStack(alignment: .trailing, spacing: 2) {
            if let path = message.filePath {
                AnimatedImage(path: path)
                    .scaledToFit()
                    .frame(width: side, height: side)
            }
            StatusLine(message: message, onMedia: true)
        }
    }
}

struct VoiceRow: View {
    let message: Message
    @ObservedObject private var playback = AudioPlayback.shared
    @EnvironmentObject var model: AppModel

    private var active: Bool { playback.current == message.filePath && message.filePath != nil }

    var body: some View {
        HStack(spacing: 10) {
            Button {
                guard let path = message.filePath else { return }
                playback.toggle(path)
            } label: {
                Image(systemName: active && playback.playing ? "pause.circle.fill" : "play.circle.fill")
                    .font(.system(size: 40))
                    .foregroundColor(.blue)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L(active && playback.playing ? "menu_pause" : "menu_play"))
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.gray.opacity(0.3)).frame(height: 3)
                    Capsule().fill(Color.blue).frame(width: geo.size.width * (active ? playback.progress : 0), height: 3)
                }
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0).onEnded { g in
                    guard let path = message.filePath else { return }
                    playback.seek(path, to: min(max(0, g.location.x / geo.size.width), 1))
                })
            }
            .frame(width: 130, height: 40)
            Text(TimeFormat.short(Double(message.durationMs ?? 0) / 1000))
                .font(.subheadline.monospacedDigit())
                .foregroundColor(.black)
        }
        .onChange(of: playback.failed) { failed in
            guard failed else { return }
            playback.failed = false
            model.alert = AlertItem(title: L("cannot_play_audio_file"), message: nil, confirm: nil, action: nil)
        }
    }
}

struct VcardRow: View {
    let message: Message

    var body: some View {
        HStack(spacing: 10) {
            AvatarView(name: message.vcard?.name ?? "", seed: message.vcard?.address ?? "", path: nil, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(message.vcard?.name ?? "").font(.body.weight(.medium)).foregroundColor(.black).lineLimit(1)
                Text(message.vcard?.address ?? "").font(.caption).foregroundColor(.middleGray).lineLimit(1).truncationMode(.middle)
            }
        }
    }
}

struct ReactionPills: View {
    let reactions: [Reaction]
    let onTap: () -> Void

    private var shown: [Reaction] {
        reactions.count > 5 ? Array(reactions.prefix(4)) : reactions
    }

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 4) {
                ForEach(shown, id: \.emoji) { r in
                    Text(r.count > 1 ? " \(r.emoji) \(r.count) " : " \(r.emoji) ")
                        .font(.subheadline)
                        .foregroundColor(.black)
                        .padding(.vertical, 3)
                        .padding(.horizontal, 2)
                        .background(RoundedRectangle(cornerRadius: 10).fill(r.mine ? Color.bubbleOut : Color.white))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(hex: 0xDDDDDD), lineWidth: 1))
                }
                if reactions.count > 5 {
                    Text(" … ")
                        .font(.subheadline)
                        .foregroundColor(.black)
                        .padding(.vertical, 3)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Color.white))
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(hex: 0xDDDDDD), lineWidth: 1))
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("reactions"))
    }
}

enum SystemText {
    static func text(for message: Message, name: (UInt32) -> String = { _ in "" }) -> String {
        let mine = message.outgoing
        let by = message.senderName
        func pick(_ key: String, _ args: CVarArg...) -> String {
            mine ? L("\(key)_by_you", arguments: args) : L("\(key)_by_other", arguments: args + [by])
        }
        switch message.system {
        case .timerChanged(let seconds): return timer(message, seconds)
        case .groupCreated(let name): return L("v2_group_created", name)
        case .groupNameChanged(let name, let oldName):
            guard !oldName.isEmpty else { return pick("v2_group_name_changed_to", name) }
            return pick("group_name_changed", oldName, name)
        case .groupAvatarChanged: return pick("group_image_changed")
        case .memberAdded(let id, let name): return pick("add_member", id == nil ? L("self") : name)
        case .memberRemoved(let id, let name): return pick("remove_member", id == nil ? L("self") : name)
        case .memberLeft: return pick("group_left")
        case .packetClaimed(let claimer, let owner, _):
            switch (claimer, owner) {
            case (nil, nil): return L("v2_packet_claimed_own")
            case (nil, let owner?): return L("v2_packet_claimed_by_you", name(owner))
            case (let claimer?, nil): return L("v2_packet_claimed_yours", name(claimer))
            case (let claimer?, let owner?): return L("red_packet_claim_notification", name(claimer), name(owner))
            }
        default: return message.text
        }
    }

    private static func timer(_ message: Message, _ seconds: UInt32) -> String {
        let mine = message.outgoing
        let suffix = mine ? "by_you" : "by_other"
        let name = message.senderName
        func fixed(_ key: String) -> String { mine ? L("ephemeral_timer_\(key)_\(suffix)") : L("ephemeral_timer_\(key)_\(suffix)", name) }
        func counted(_ key: String, _ n: UInt32) -> String {
            mine ? L("ephemeral_timer_\(key)_\(suffix)", "\(n)") : L("ephemeral_timer_\(key)_\(suffix)", "\(n)", name)
        }
        switch seconds {
        case 0: return fixed("disabled")
        case 60: return fixed("1_minute")
        case 3600: return fixed("1_hour")
        case 86400: return fixed("1_day")
        case 604800: return fixed("1_week")
        case let s where s % 604800 == 0: return counted("weeks", s / 604800)
        case let s where s % 86400 == 0: return counted("days", s / 86400)
        case let s where s % 3600 == 0: return counted("hours", s / 3600)
        case let s where s % 60 == 0: return counted("minutes", s / 60)
        default: return counted("seconds", seconds)
        }
    }
}

struct LinkText: View {
    let text: String
    var highlight: String = ""
    var onCommand: (String) -> Void = { _ in }
    @EnvironmentObject var model: AppModel

    private static let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue | NSTextCheckingResult.CheckingType.phoneNumber.rawValue)

    static func firstLink(in text: String) -> String? {
        guard !text.isEmpty else { return nil }
        let ns = text as NSString
        return detector?.matches(in: text, range: NSRange(location: 0, length: ns.length)).first { $0.url != nil }?.url?.absoluteString
    }
    private static let command = try? NSRegularExpression(pattern: "(?<=^|\\s)/[a-zA-Z][a-zA-Z0-9_@.-]*")

    private var attributed: AttributedString {
        var result = AttributedString(text)
        let ns = text as NSString
        let full = NSRange(location: 0, length: ns.length)
        for match in Self.detector?.matches(in: text, range: full) ?? [] {
            guard let range = Range(match.range, in: result) else { continue }
            if let url = match.url {
                result[range].link = url
            } else if let phone = match.phoneNumber, let url = URL(string: "tel:\(phone.filter { $0.isNumber || $0 == "+" })") {
                result[range].link = url
            }
            result[range].underlineStyle = .single
        }
        for match in Self.command?.matches(in: text, range: full) ?? [] {
            guard let range = Range(match.range, in: result) else { continue }
            let cmd = ns.substring(with: match.range)
            result[range].link = URL(string: "tapetape-command:\(cmd.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "")")
            result[range].underlineStyle = .single
        }
        let q = highlight.trimmingCharacters(in: .whitespaces)
        if !q.isEmpty {
            var searchStart = result.startIndex
            while let range = result[searchStart...].range(of: q, options: .caseInsensitive) {
                result[range].font = .body.bold()
                searchStart = range.upperBound
            }
        }
        return result
    }

    var body: some View {
        Text(attributed)
            .font(EmojiScale.font(for: text))
            .foregroundColor(.black)
            .tint(.black)
            .environment(\.openURL, OpenURLAction { url in
                if url.scheme == "tapetape-command" {
                    let cmd = String(url.absoluteString.dropFirst("tapetape-command:".count)).removingPercentEncoding ?? ""
                    onCommand(cmd)
                    return .handled
                }
                if Invite(link: url.absoluteString) != nil || (try? model.core.parseProxy(link: url.absoluteString)) != nil {
                    model.handleScanned(url.absoluteString)
                    return .handled
                }
                Log.chat.info("open link in system")
                return .systemAction
            })
    }
}

struct QuoteBlock: View {
    let message: Message
    @EnvironmentObject var model: AppModel

    private var color: Color {
        if message.fromContact == nil { return AvatarColor.color(for: model.info?.address ?? "") }
        return AvatarColor.color(for: model.contactAddress(message.fromContact) ?? message.senderName)
    }

    private var sender: String {
        message.fromContact == nil ? (model.info?.name ?? L("self")) : message.senderName
    }

    private var summary: String {
        if !message.text.isEmpty { return message.text }
        return MessagePreview.label(message.kind) ?? ""
    }

    var body: some View {
        HStack(spacing: 8) {
            Rectangle().fill(color).frame(width: 3)
            VStack(alignment: .leading, spacing: 1) {
                Text(sender).font(.caption.weight(.semibold)).foregroundColor(color)
                Text(summary).font(.subheadline).foregroundColor(.black).lineLimit(3)
            }
            if [.image, .gif, .video, .sticker].contains(message.kind), let path = message.filePath {
                FileImage(path: path, maxPixel: 120).frame(width: 36, height: 36).clipShape(RoundedRectangle(cornerRadius: 4))
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(.bottom, 4)
    }
}

enum MessagePreview {
    static func emoji(_ kind: MessageKind) -> String {
        switch kind {
        case .image, .gif, .sticker: return "📷"
        case .video: return "🎥"
        case .voice: return "🎤"
        case .audio: return "🎵"
        case .file: return "📎"
        case .vcard: return "👤"
        default: return ""
        }
    }

    static func label(_ kind: MessageKind) -> String? {
        switch kind {
        case .image: return "📷 " + L("image")
        case .gif: return L("gif")
        case .sticker: return L("sticker")
        case .video: return "🎥 " + L("video")
        case .voice: return "🎤 " + L("voice_message")
        case .audio: return "🎵 " + L("audio")
        case .file: return "📎 " + L("file")
        case .vcard: return "👤 " + L("contact")
        default: return nil
        }
    }
}

struct BubbleLayout: Layout {
    var spacing: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let sizes = subviews.map { $0.sizeThatFits(ProposedViewSize(width: proposal.width, height: nil)) }
        let width = sizes.map(\.width).max() ?? 0
        let height = sizes.map(\.height).reduce(0, +) + spacing * CGFloat(max(0, sizes.count - 1))
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for (i, view) in subviews.enumerated() {
            let size = view.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
            let isLast = i == subviews.count - 1
            let x = isLast ? bounds.maxX - size.width : bounds.minX
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(width: isLast ? size.width : bounds.width, height: size.height))
            y += size.height + spacing
        }
    }
}

struct BubbleShape: Shape {
    let outgoing: Bool
    var radius: CGFloat = 16

    func path(in rect: CGRect) -> Path {
        let r = min(radius, rect.height / 2, rect.width / 2)
        let bl = outgoing ? r : 0
        let br = outgoing ? 0 : r
        var p = Path()
        p.move(to: CGPoint(x: rect.minX + r, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
        p.addArc(center: CGPoint(x: rect.maxX - r, y: rect.minY + r), radius: r, startAngle: .degrees(-90), endAngle: .degrees(0), clockwise: false)
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - br))
        if br > 0 {
            p.addArc(center: CGPoint(x: rect.maxX - br, y: rect.maxY - br), radius: br, startAngle: .degrees(0), endAngle: .degrees(90), clockwise: false)
        }
        p.addLine(to: CGPoint(x: rect.minX + bl, y: rect.maxY))
        if bl > 0 {
            p.addArc(center: CGPoint(x: rect.minX + bl, y: rect.maxY - bl), radius: bl, startAngle: .degrees(90), endAngle: .degrees(180), clockwise: false)
        }
        p.addLine(to: CGPoint(x: rect.minX, y: rect.minY + r))
        p.addArc(center: CGPoint(x: rect.minX + r, y: rect.minY + r), radius: r, startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false)
        p.closeSubpath()
        return p
    }
}

enum EmojiScale {
    static func font(for text: String) -> Font {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, trimmed.count <= 8, trimmed.allSatisfy(isEmoji) else { return .body }
        let base = UIFont.preferredFont(forTextStyle: .body).pointSize
        let factor: CGFloat
        switch trimmed.count {
        case 1...2: factor = 3
        case 3...4: factor = 2.5
        case 5...6: factor = 1.75
        default: factor = 1.35
        }
        return .system(size: base * factor)
    }

    private static func isEmoji(_ c: Character) -> Bool {
        guard let first = c.unicodeScalars.first else { return false }
        return first.properties.isEmojiPresentation || (c.unicodeScalars.count > 1 && first.properties.isEmoji)
    }
}
