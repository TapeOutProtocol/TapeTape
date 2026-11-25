import UIKit
import Social
import UniformTypeIdentifiers
import TapeTapeCore

final class NoopListener: EventListener, @unchecked Sendable {
    func onEvent(accountId: UInt32, event: Event) {}
}

final class ShareViewController: SLComposeServiceViewController {
    private var core: Core?
    private var account: Account?
    private var chats: [ChatSummary] = []
    private var target: (id: UInt32, name: String)?
    private lazy var targetItem: SLComposeSheetConfigurationItem? = {
        let item = SLComposeSheetConfigurationItem()
        item?.title = L("forward_to")
        item?.tapHandler = { [weak self] in self?.pickChat() }
        return item
    }()

    override func viewDidLoad() {
        super.viewDidLoad()
        placeholder = L("chat_input_placeholder")
        navigationController?.navigationBar.topItem?.rightBarButtonItem?.title = L("menu_send")
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.org.tapetape.v2") else {
            Log.app.error("share: app group unavailable")
            return
        }
        do {
            let core = try Core(dataDir: container.appendingPathComponent("tapetape-v2").path, listener: NoopListener())
            self.core = core
            guard let id = core.selectedAccount() else {
                Log.app.notice("share: no configured profile")
                cancel()
                return
            }
            let account = try core.account(id: id)
            self.account = account
            chats = account.chats(query: nil)
            let saved = try account.savedMessagesChat()
            target = (saved, L("saved_messages"))
            targetItem?.value = target?.name
            reloadConfigurationItems()
            Log.app.info("share: profile \(id) ready with \(self.chats.count) chats")
        } catch {
            Log.app.error("share: core init failed: \(coreMessage(error), privacy: .public)")
            cancel()
        }
    }

    override func isContentValid() -> Bool {
        account != nil && target != nil
    }

    override func configurationItems() -> [Any]! {
        targetItem.map { [$0] } ?? []
    }

    private func pickChat() {
        let picker = ChatPickerController(chats: chats) { [weak self] chat in
            guard let self else { return }
            self.target = (chat.id, chat.isSelf ? L("saved_messages") : chat.name)
            self.targetItem?.value = self.target?.name
            self.popConfigurationViewController()
        }
        pushConfigurationViewController(picker)
    }

    override func didSelectPost() {
        guard let account, let target else { return cancel() }
        let text = contentText.trimmingCharacters(in: .whitespacesAndNewlines)
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        let limit = Int(account.maxAttachmentSize())
        let progress = UIAlertController(title: L("one_moment"), message: nil, preferredStyle: .alert)
        present(progress, animated: true)
        Task { @MainActor in
            do {
                var caption: String? = text.isEmpty ? nil : text
                var sentText = false
                for provider in providers {
                    if let media = try await SharedMedia.load(provider, limit: limit) {
                        _ = try await account.sendMedia(chatId: target.id, kind: media.kind, path: media.url.path, text: caption, replyTo: nil, durationMs: nil)
                        caption = nil
                    } else if let url = try await SharedMedia.url(provider) {
                        _ = try await account.sendText(chatId: target.id, text: [caption, url].compactMap { $0 }.joined(separator: "\n"), replyTo: nil)
                        caption = nil
                        sentText = true
                    }
                }
                if let caption, !sentText {
                    _ = try await account.sendText(chatId: target.id, text: caption, replyTo: nil)
                }
                try await account.sendPending()
                Log.app.info("share: sent to chat \(target.id)")
                progress.dismiss(animated: true) { self.extensionContext?.completeRequest(returningItems: nil) }
            } catch {
                Log.app.error("share: send failed: \(coreMessage(error), privacy: .public)")
                progress.dismiss(animated: true) {
                    let alert = UIAlertController(title: coreMessage(error), message: nil, preferredStyle: .alert)
                    alert.addAction(UIAlertAction(title: L("ok"), style: .default) { _ in self.cancel() })
                    self.present(alert, animated: true)
                }
            }
        }
    }
}

enum SharedMedia {
    struct Item {
        let url: URL
        let kind: MediaKind
    }

    static func load(_ provider: NSItemProvider, limit: Int) async throws -> Item? {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
            let data = try await loadData(provider, type: .image)
            guard let image = UIImage(data: data), let jpeg = compress(image, limit: limit) else { throw CoreError.Failed(message: L("relay_too_large")) }
            let url = dir.appendingPathComponent("image.jpg")
            try jpeg.write(to: url)
            return Item(url: url, kind: .image)
        }
        let kinds: [(UTType, MediaKind)] = [(.movie, .video), (.audio, .audio), (.data, .file)]
        for (type, kind) in kinds where provider.hasItemConformingToTypeIdentifier(type.identifier) {
            if type == .data && (provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) || provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier)) { return nil }
            let url = try await loadFile(provider, type: type, into: dir)
            let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.intValue ?? 0
            guard size <= limit else { throw CoreError.Failed(message: L("relay_too_large")) }
            return Item(url: url, kind: kind)
        }
        return nil
    }

    static func url(_ provider: NSItemProvider) async throws -> String? {
        if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
            let item = try await provider.loadItem(forTypeIdentifier: UTType.url.identifier)
            return (item as? URL)?.absoluteString
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            return try await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier) as? String
        }
        return nil
    }

    private static func loadData(_ provider: NSItemProvider, type: UTType) async throws -> Data {
        try await withCheckedThrowingContinuation { cont in
            provider.loadDataRepresentation(forTypeIdentifier: type.identifier) { data, error in
                if let data { cont.resume(returning: data) } else { cont.resume(throwing: error ?? CoreError.Failed(message: "no data")) }
            }
        }
    }

    private static func loadFile(_ provider: NSItemProvider, type: UTType, into dir: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { cont in
            provider.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, error in
                guard let url else { return cont.resume(throwing: error ?? CoreError.Failed(message: "no file")) }
                let copy = dir.appendingPathComponent(url.lastPathComponent)
                do {
                    try FileManager.default.copyItem(at: url, to: copy)
                    cont.resume(returning: copy)
                } catch {
                    cont.resume(throwing: error)
                }
            }
        }
    }

    static func compress(_ image: UIImage, limit: Int) -> Data? {
        for side in [1280, 960, 720, 540, 400] as [CGFloat] {
            let scale = min(1, side / max(image.size.width, image.size.height))
            let size = CGSize(width: (image.size.width * scale).rounded(), height: (image.size.height * scale).rounded())
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            let resized = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
            for quality in [0.75, 0.6, 0.45, 0.3] as [CGFloat] {
                if let data = resized.jpegData(compressionQuality: quality), data.count <= limit { return data }
            }
        }
        return nil
    }
}

final class ChatPickerController: UITableViewController, UISearchResultsUpdating {
    private let all: [ChatSummary]
    private var shown: [ChatSummary]
    private let onPick: (ChatSummary) -> Void

    init(chats: [ChatSummary], onPick: @escaping (ChatSummary) -> Void) {
        all = chats
        shown = chats
        self.onPick = onPick
        super.init(style: .plain)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = L("forward_to")
        let search = UISearchController(searchResultsController: nil)
        search.searchResultsUpdater = self
        search.obscuresBackgroundDuringPresentation = false
        navigationItem.searchController = search
        navigationItem.preferredSearchBarPlacement = .stacked
        navigationItem.hidesSearchBarWhenScrolling = false
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "chat")
    }

    func updateSearchResults(for controller: UISearchController) {
        let q = controller.searchBar.text?.lowercased() ?? ""
        shown = q.isEmpty ? all : all.filter { $0.name.lowercased().contains(q) }
        tableView.reloadData()
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { shown.count }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "chat", for: indexPath)
        let chat = shown[indexPath.row]
        var content = cell.defaultContentConfiguration()
        content.text = chat.isSelf ? L("saved_messages") : chat.isDevice ? L("device_talk") : chat.name
        cell.contentConfiguration = content
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        onPick(shown[indexPath.row])
    }
}
