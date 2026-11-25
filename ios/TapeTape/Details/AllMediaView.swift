import SwiftUI
import TapeTapeCore

struct AllMediaView: View {
    let chatId: UInt32
    @EnvironmentObject var model: AppModel
    @State private var page = 2
    @State private var items: [Message] = []
    @State private var share: ShareItem?
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: 3)

    private var kinds: [MessageKind] {
        switch page {
        case 0: return [.file]
        case 1: return [.audio, .voice]
        default: return [.image, .gif, .video]
        }
    }

    private var emptyHint: String {
        switch page {
        case 0: return L("tab_docs_empty_hint")
        case 1: return L("tab_audio_empty_hint")
        default: return L("tab_gallery_empty_hint")
        }
    }

    var body: some View {
        Group {
            if items.isEmpty {
                EmptyStatePill(text: emptyHint).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if page == 2 {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 2) {
                        ForEach(items.reversed()) { m in
                            Button { open(m) } label: {
                                ZStack {
                                    if let path = m.filePath { FileImage(path: path, maxPixel: 300) }
                                    if m.kind == .video {
                                        Image(systemName: "play.circle.fill").font(.title).foregroundStyle(.white, Color.black.opacity(0.4))
                                    }
                                }
                                .aspectRatio(1, contentMode: .fill)
                                .clipped()
                            }
                            .contextMenu { menu(m, sharable: false) }
                        }
                    }
                }
            } else {
                List(items.reversed()) { m in
                    Button { open(m) } label: {
                        if page == 0 {
                            FileRow(name: m.fileName ?? L("file"), size: Int64(m.size ?? 0))
                        } else {
                            VoiceRow(message: m)
                        }
                    }
                    .contextMenu { menu(m, sharable: true) }
                }
                .listStyle(.plain)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker("", selection: $page) {
                    Text(L("files")).tag(0)
                    Text(L("audio")).tag(1)
                    Text(L("gallery")).tag(2)
                }
                .pickerStyle(.segmented)
                .fixedSize()
            }
        }
        .sheet(item: $share) { ShareSheet(items: $0.items).presentationDetents([.medium, .large]) }
        .onAppear { load() }
        .onChange(of: page) { _ in load() }
    }

    @ViewBuilder
    private func menu(_ m: Message, sharable: Bool) -> some View {
        Button { model.openChat(m.chatId) } label: { Label(L("show_in_chat"), systemImage: "text.bubble") }
        if sharable, let path = m.filePath {
            Button { share = ShareItem(items: [URL(fileURLWithPath: path)]) } label: { Label(L("menu_share"), systemImage: "square.and.arrow.up") }
        }
        Button(role: .destructive) {
            model.account?.deleteMessages(msgIds: [m.id])
            Log.chat.info("deleted media message \(m.id)")
            load()
        } label: { Label(L("delete"), systemImage: "trash") }
    }

    private func open(_ m: Message) {
        if m.kind == .voice || m.kind == .audio { return }
        let urls = items.reversed().compactMap { $0.filePath.map(URL.init(fileURLWithPath:)) }
        let index = items.reversed().firstIndex { $0.id == m.id } ?? 0
        MediaViewerController.present(urls: urls, index: index)
    }

    private func load() {
        items = model.account?.mediaMessages(chatId: chatId, kinds: kinds) ?? []
    }
}
