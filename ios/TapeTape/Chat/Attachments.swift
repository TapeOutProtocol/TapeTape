import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import AVFoundation
import TapeTapeCore

enum AttachmentKind: Equatable {
    case image, gif, sticker, video, file, audio, voice
}

struct Attachment: Identifiable, Equatable {
    let id = UUID()
    let url: URL
    let kind: AttachmentKind
    var duration: TimeInterval = 0

    var name: String { url.lastPathComponent }

    var size: Int64 {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
    }

    static func kind(for url: URL) -> AttachmentKind {
        guard let type = UTType(filenameExtension: url.pathExtension.lowercased()) else { return .file }
        if type.conforms(to: .gif) { return .gif }
        if type.conforms(to: .image) { return .image }
        if type.conforms(to: .movie) { return .video }
        if type.conforms(to: .audio) { return .audio }
        return .file
    }
}

enum Staging {
    static var limit = 174_080
    static var worseQuality = false

    static func directory() -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("outgoing/\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func image(_ image: UIImage) -> Attachment? {
        var data: Data?
        let sides: [CGFloat] = worseQuality ? [640, 540, 400] : [1280, 960, 720, 540, 400]
        outer: for side in sides {
            let scale = min(1, side / max(image.size.width, image.size.height))
            let size = CGSize(width: (image.size.width * scale).rounded(), height: (image.size.height * scale).rounded())
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            let resized = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
            for quality in [0.75, 0.6, 0.45, 0.3] as [CGFloat] {
                data = resized.jpegData(compressionQuality: quality)
                if let data, data.count <= limit {
                    Log.chat.info("image staged \(Int(size.width))x\(Int(size.height)) q\(quality) \(data.count) bytes")
                    break outer
                }
            }
        }
        guard let data else { return nil }
        let url = directory().appendingPathComponent("image.jpg")
        guard (try? data.write(to: url)) != nil else { return nil }
        return Attachment(url: url, kind: .image)
    }

    static func compressVideo(_ source: URL) async -> URL {
        let output = directory().appendingPathComponent("video.mp4")
        guard let export = AVAssetExportSession(asset: AVURLAsset(url: source), presetName: AVAssetExportPresetLowQuality) else { return source }
        export.outputURL = output
        export.outputFileType = .mp4
        export.shouldOptimizeForNetworkUse = true
        await export.export()
        guard export.status == .completed else {
            Log.chat.error("video compression failed: \(export.error?.localizedDescription ?? "", privacy: .public)")
            return source
        }
        let size = (try? FileManager.default.attributesOfItem(atPath: output.path)[.size] as? NSNumber)?.intValue ?? 0
        Log.chat.info("video compressed to \(size) bytes")
        return output
    }

    static func copy(_ source: URL) -> Attachment? {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let url = directory().appendingPathComponent(source.lastPathComponent)
        do {
            try FileManager.default.copyItem(at: source, to: url)
        } catch {
            Log.chat.error("stage file failed: \(error.localizedDescription, privacy: .public)")
            return nil
        }
        return Attachment(url: url, kind: Attachment.kind(for: url))
    }

    static func dropped(_ provider: NSItemProvider) async -> Attachment? {
        if provider.hasItemConformingToTypeIdentifier(UTType.gif.identifier), let url = await loadFile(provider, .gif) {
            return Attachment(url: url, kind: .gif)
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
            let data: Data? = await withCheckedContinuation { cont in
                provider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in cont.resume(returning: data) }
            }
            return data.flatMap(UIImage.init(data:)).flatMap(image)
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier), let url = await loadFile(provider, .movie) {
            return Attachment(url: await compressVideo(url), kind: .video)
        }
        if let url = await loadFile(provider, .item) {
            return Attachment(url: url, kind: Attachment.kind(for: url))
        }
        return nil
    }

    private static func loadFile(_ provider: NSItemProvider, _ type: UTType) async -> URL? {
        await withCheckedContinuation { cont in
            provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { source, _ in
                guard let source else { return cont.resume(returning: nil) }
                let url = directory().appendingPathComponent(source.lastPathComponent)
                cont.resume(returning: (try? FileManager.default.copyItem(at: source, to: url)) == nil ? nil : url)
            }
        }
    }

    static func picked(_ item: PhotosPickerItem) async -> Attachment? {
        let types = item.supportedContentTypes
        if types.contains(where: { $0.conforms(to: .movie) }) {
            guard let movie = try? await item.loadTransferable(type: PickedMovie.self) else { return nil }
            return Attachment(url: await compressVideo(movie.url), kind: .video)
        }
        guard let data = try? await item.loadTransferable(type: Data.self) else { return nil }
        if types.contains(where: { $0.conforms(to: .gif) }) {
            let url = directory().appendingPathComponent("image.gif")
            guard (try? data.write(to: url)) != nil else { return nil }
            return Attachment(url: url, kind: .gif)
        }
        guard let image = UIImage(data: data) else { return nil }
        return Staging.image(image)
    }
}

struct PickedMovie: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { SentTransferredFile($0.url) } importing: { received in
            let url = Staging.directory().appendingPathComponent(received.file.lastPathComponent)
            try FileManager.default.copyItem(at: received.file, to: url)
            return PickedMovie(url: url)
        }
    }
}

enum MediaThumbnail {
    static func image(for url: URL, kind: AttachmentKind) -> UIImage? {
        switch kind {
        case .image, .gif, .sticker:
            return UIImage(contentsOfFile: url.path)
        case .video:
            let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
            generator.appliesPreferredTrackTransform = true
            return (try? generator.copyCGImage(at: .zero, actualTime: nil)).map(UIImage.init(cgImage:))
        default:
            return nil
        }
    }
}

struct StagedAttachmentView: View {
    let attachment: Attachment
    let onOpen: () -> Void
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Button(action: onOpen) {
                switch attachment.kind {
                case .image, .gif, .sticker, .video:
                    ZStack {
                        if let image = MediaThumbnail.image(for: attachment.url, kind: attachment.kind) {
                            Image(uiImage: image).resizable().scaledToFill()
                        }
                        if attachment.kind == .video {
                            Image(systemName: "play.circle.fill").font(.title2).foregroundColor(.white)
                        }
                    }
                    .frame(width: 60, height: 60)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                default:
                    FileRow(name: attachment.name, size: attachment.size)
                }
            }
            .buttonStyle(.plain)
            Spacer()
            Button(action: onRemove) {
                Image(systemName: "xmark.circle.fill").font(.title3).foregroundColor(.secondary)
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 8)
    }
}

struct FileRow: View {
    let name: String
    let size: Int64

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "doc.fill")
                .font(.system(size: 28))
                .foregroundColor(.tapetapePrimary)
            VStack(alignment: .leading, spacing: 2) {
                Text(name).font(.subheadline.weight(.medium)).foregroundColor(.black).lineLimit(2)
                Text(TimeFormat.size(size)).font(.caption).foregroundColor(.secondary)
            }
        }
    }
}

struct ContactPickerSheet: View {
    let onPick: (Contact) -> Void
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""

    private var filtered: [Contact] {
        let q = search.lowercased()
        guard !q.isEmpty else { return model.contacts }
        return model.contacts.filter { $0.name.lowercased().contains(q) || $0.address.lowercased().contains(q) }
    }

    var body: some View {
        NavigationStack {
            List(filtered) { contact in
                Button {
                    onPick(contact)
                    dismiss()
                } label: { ContactRow(contact: contact) }
            }
            .listStyle(.plain)
            .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always), prompt: L("search"))
            .navigationTitle(L("contact"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("cancel")) { dismiss() }
                }
            }
        }
    }
}
