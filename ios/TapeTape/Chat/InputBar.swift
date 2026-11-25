import SwiftUI
import PhotosUI
import TapeTapeCore

final class PastingTextView: UITextView {
    var onPasteImage: ((UIImage) -> Void)?

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        if action == #selector(paste(_:)) && UIPasteboard.general.hasImages { return true }
        return super.canPerformAction(action, withSender: sender)
    }

    override func paste(_ sender: Any?) {
        if UIPasteboard.general.hasImages, !UIPasteboard.general.hasStrings, let image = UIPasteboard.general.image {
            onPasteImage?(image)
            return
        }
        super.paste(sender)
    }
}

struct ChatTextView: UIViewRepresentable {
    @Binding var text: String
    @Binding var height: CGFloat
    @Binding var focused: Bool
    let placeholder: String
    let onPasteImage: (UIImage) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> PastingTextView {
        let view = PastingTextView()
        view.delegate = context.coordinator
        view.font = .preferredFont(forTextStyle: .body)
        view.backgroundColor = .clear
        view.textContainerInset = UIEdgeInsets(top: 9, left: 8, bottom: 9, right: 8)
        view.textContainer.lineFragmentPadding = 4
        view.isScrollEnabled = false
        view.accessibilityLabel = placeholder
        view.onPasteImage = onPasteImage
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return view
    }

    func updateUIView(_ view: PastingTextView, context: Context) {
        context.coordinator.parent = self
        if view.text != text { view.text = text }
        view.onPasteImage = onPasteImage
        DispatchQueue.main.async {
            if focused && !view.isFirstResponder { view.becomeFirstResponder() }
            if !focused && view.isFirstResponder { view.resignFirstResponder() }
            context.coordinator.measure(view)
        }
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: ChatTextView

        init(_ parent: ChatTextView) { self.parent = parent }

        func textViewDidChange(_ view: UITextView) {
            parent.text = view.text
            measure(view)
        }

        func textViewDidBeginEditing(_ view: UITextView) {
            if !parent.focused { parent.focused = true }
        }

        func textViewDidEndEditing(_ view: UITextView) {
            if parent.focused { parent.focused = false }
        }

        func measure(_ view: UITextView) {
            guard view.bounds.width > 0 else { return }
            let lineHeight = view.font?.lineHeight ?? 20
            let maxHeight = lineHeight * 6 + view.textContainerInset.top + view.textContainerInset.bottom
            let fitting = view.sizeThatFits(CGSize(width: view.bounds.width, height: .greatestFiniteMagnitude)).height
            let height = min(fitting, maxHeight)
            view.isScrollEnabled = fitting > maxHeight
            if abs(parent.height - height) > 0.5 { parent.height = height }
        }
    }
}

struct InputBar: View {
    @ObservedObject var vm: ChatViewModel
    @Binding var focused: Bool
    @EnvironmentObject var model: AppModel
    @State private var height: CGFloat = 38
    @State private var showGallery = false
    @State private var showAttach = false
    @State private var pendingAttach: (() -> Void)?
    @State private var showFiles = false
    @State private var showVoice = false
    @State private var showPacket = false
    @State private var showContacts = false
    @State private var pickedItem: PhotosPickerItem?

    private var canSend: Bool {
        !vm.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || vm.attachment != nil || vm.stagedContact != nil
    }

    var body: some View {
        VStack(spacing: 0) {
            if let reply = vm.replyTo {
                HStack(alignment: .center, spacing: 8) {
                    QuoteBlock(message: reply)
                    Spacer()
                    Button { vm.replyTo = nil } label: {
                        Image(systemName: "xmark.circle.fill").font(.title3).foregroundColor(.secondary)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.top, 8)
            }
            if let attachment = vm.attachment {
                StagedAttachmentView(attachment: attachment, onOpen: { MediaViewerController.present(urls: [attachment.url], index: 0, editable: true) }, onRemove: { vm.attachment = nil })
            }
            if let contact = vm.stagedContact {
                HStack {
                    ContactRow(contact: contact)
                    Button { vm.stagedContact = nil } label: {
                        Image(systemName: "xmark.circle.fill").font(.title3).foregroundColor(.secondary)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.top, 8)
            }
            HStack(alignment: .bottom, spacing: 8) {
                attachMenu
                ChatTextView(text: $vm.text, height: $height, focused: $focused, placeholder: L("chat_input_placeholder")) { image in
                    vm.attachment = Staging.image(image)
                    Log.chat.info("pasted image staged")
                }
                .frame(height: height)
                .overlay(alignment: .leading) {
                    if vm.text.isEmpty {
                        Text(L("chat_input_placeholder")).foregroundColor(Color(.placeholderText)).padding(.leading, 12).allowsHitTesting(false)
                    }
                }
                .background(RoundedRectangle(cornerRadius: 13).fill(Color.inputFill))
                .overlay(RoundedRectangle(cornerRadius: 13).stroke(Color.tapetapeDisabled, lineWidth: 1))
                Button { showVoice = true } label: {
                    Image("ic_attach_voice_36pt").renderingMode(.template).resizable().scaledToFit()
                        .foregroundColor(.tapetapePrimary)
                        .frame(width: 36, height: 36)
                        .frame(width: 40, height: 40)
                }
                .accessibilityLabel(L("voice_message"))
                Button { vm.send() } label: {
                    Image("paper_plane")
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 20, height: 20)
                        .foregroundColor(.white)
                        .frame(width: 40, height: 40)
                        .background(Circle().fill(canSend ? Color.tapetapePrimary : Color.tapetapeDisabled))
                }
                .accessibilityLabel(L("menu_send"))
                .disabled(!canSend)
                .animation(.easeInOut(duration: 0.3), value: canSend)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 8)
        }
        .background(.bar)
        .photosPicker(isPresented: $showGallery, selection: $pickedItem, matching: .any(of: [.images, .videos]))
        .onChange(of: pickedItem) { item in
            guard let item else { return }
            pickedItem = nil
            Task {
                vm.attachment = await Staging.picked(item)
                Log.chat.info("gallery item staged: \(vm.attachment != nil)")
            }
        }
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.item]) { result in
            switch result {
            case .success(let url):
                vm.attachment = Staging.copy(url)
                Log.chat.info("file staged, \(vm.attachment?.size ?? 0) bytes")
            case .failure(let error):
                Log.chat.error("file import failed: \(error.localizedDescription, privacy: .public)")
            }
        }
        .sheet(isPresented: $showVoice) {
            VoiceRecorderSheet { vm.sendVoice($0) }
        }
        .sheet(isPresented: $showPacket) {
            if let chat = vm.chat { SendPacketView(chat: chat, red: chat.isGroup) }
        }
        .sheet(isPresented: $showContacts) {
            ContactPickerSheet { vm.stagedContact = $0 }
        }
    }

    private var attachItems: [AttachGridItem] {
        var items = [
            AttachGridItem(title: L("gallery"), icon: "photo.fill", color: Color(red: 0.55, green: 0.36, blue: 0.96)) { showGallery = true },
            AttachGridItem(title: L("files"), icon: "doc.fill", color: Color(red: 0.05, green: 0.65, blue: 0.91)) { showFiles = true },
            AttachGridItem(title: L("contact"), icon: "person.fill", color: Color(red: 0.39, green: 0.45, blue: 0.55)) { showContacts = true },
            AttachGridItem(title: L("voice_message"), icon: "mic.fill", color: Color(red: 0.94, green: 0.27, blue: 0.27)) { showVoice = true },
        ]
        if let chat = vm.chat, chat.isGroup {
            items.append(AttachGridItem(title: L("send_red_packet"), icon: "gift.fill", color: Color(red: 0.98, green: 0.45, blue: 0.09)) { showPacket = true })
        } else if let chat = vm.chat, !chat.isSelf, !chat.isDevice {
            items.append(AttachGridItem(title: L("send_transfer"), icon: "banknote.fill", color: Color(red: 0.15, green: 0.39, blue: 0.92)) { showPacket = true })
        }
        items.append(AttachGridItem(title: L("sell"), icon: "cart.fill", color: Color(red: 0.06, green: 0.73, blue: 0.51)) { ShopFlow.open(model: model) })
        return items
    }

    private var attachMenu: some View {
        Button { showAttach = true } label: {
            Image("ic_attach_add_36pt").renderingMode(.template).resizable().scaledToFit()
                .foregroundColor(.tapetapePrimary)
                .frame(width: 36, height: 36)
                .frame(width: 40, height: 40)
        }
        .sheet(isPresented: $showAttach, onDismiss: {
            // 菜单完全关掉之后再打开下一层（菜单还在时打开的选择器会被系统忽略）
            let action = pendingAttach
            pendingAttach = nil
            action?()
        }) {
            AttachGrid(items: attachItems) { item in
                pendingAttach = item.action
                showAttach = false
            }
            .presentationDetents([.height(attachItems.count > 4 ? 250 : 150)])
            .presentationDragIndicator(.visible)
        }
        .accessibilityLabel(L("menu_add_attachment"))
    }
}

struct AttachGridItem: Identifiable {
    let title: String
    let icon: String
    let color: Color
    let action: () -> Void
    var id: String { title }
}

/// 聊天的功能菜单：每行 4 个、大小一样的圆角方块（和安卓一致）
struct AttachGrid: View {
    let items: [AttachGridItem]
    let pick: (AttachGridItem) -> Void

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 18) {
            ForEach(items) { item in
                Button { pick(item) } label: {
                    VStack(spacing: 8) {
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(item.color)
                            .frame(width: 56, height: 56)
                            .overlay(Image(systemName: item.icon).font(.system(size: 24, weight: .medium)).foregroundColor(.white))
                        Text(item.title).font(.caption).foregroundColor(.secondary).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(item.title)
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 28)
        .frame(maxHeight: .infinity, alignment: .top)
    }
}
