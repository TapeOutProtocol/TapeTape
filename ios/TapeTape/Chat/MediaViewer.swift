import SwiftUI
import QuickLook

final class MediaViewerController: QLPreviewController, QLPreviewControllerDataSource, QLPreviewControllerDelegate {
    private let urls: [URL]
    private let editable: Bool

    init(urls: [URL], index: Int, editable: Bool) {
        self.urls = urls
        self.editable = editable
        super.init(nibName: nil, bundle: nil)
        dataSource = self
        delegate = self
        currentPreviewItemIndex = index
    }

    required init?(coder: NSCoder) { fatalError() }

    func numberOfPreviewItems(in controller: QLPreviewController) -> Int { urls.count }

    func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { urls[index] as NSURL }

    func previewController(_ controller: QLPreviewController, editingModeFor previewItem: QLPreviewItem) -> QLPreviewItemEditingMode {
        editable ? .updateContents : .disabled
    }

    static func present(urls: [URL], index: Int, editable: Bool = false) {
        guard !urls.isEmpty else { return }
        Log.chat.info("open media viewer with \(urls.count) items at \(index)")
        TopController.current?.present(MediaViewerController(urls: urls, index: index, editable: editable), animated: true)
    }
}
