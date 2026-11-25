import SwiftUI
import UIKit
import PhotosUI
import CoreImage.CIFilterBuiltins

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct ShareItem: Identifiable {
    let id = UUID()
    let items: [Any]
}

enum QRCode {
    static func image(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "H"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

struct CameraPicker: UIViewControllerRepresentable {
    let onImage: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.allowsEditing = true
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = (info[.editedImage] ?? info[.originalImage]) as? UIImage { parent.onImage(image) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}

enum AvatarFile {
    static func save(_ image: UIImage) -> String? {
        let side = min(image.size.width, image.size.height)
        let crop = CGRect(x: (image.size.width - side) / 2, y: (image.size.height - side) / 2, width: side, height: side)
        let target = CGSize(width: 512, height: 512)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let scaled = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            let scale = target.width / side
            image.draw(in: CGRect(x: -crop.minX * scale, y: -crop.minY * scale, width: image.size.width * scale, height: image.size.height * scale))
        }
        guard let data = scaled.jpegData(compressionQuality: 0.85) else { return nil }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("avatar-\(UUID().uuidString).jpg")
        do {
            try data.write(to: url)
            return url.path
        } catch {
            Log.app.error("avatar save failed: \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }
}

struct AvatarPickerModifier: ViewModifier {
    @Binding var isPresented: Bool
    let hasAvatar: Bool
    let onPick: (String?) -> Void
    @State private var showCamera = false
    @State private var showGallery = false
    @State private var galleryItem: PhotosPickerItem?

    func body(content: Content) -> some View {
        content
            .confirmationDialog(L("pref_profile_photo"), isPresented: $isPresented, titleVisibility: .visible) {
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    Button(L("camera")) { showCamera = true }
                }
                Button(L("gallery")) { showGallery = true }
                if hasAvatar {
                    Button(L("delete"), role: .destructive) { onPick(nil) }
                }
                Button(L("cancel"), role: .cancel) {}
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker { image in onPick(AvatarFile.save(image)) }.ignoresSafeArea()
            }
            .photosPicker(isPresented: $showGallery, selection: $galleryItem, matching: .images)
            .onChange(of: galleryItem) { item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                        onPick(AvatarFile.save(image))
                    }
                    galleryItem = nil
                }
            }
    }
}

extension View {
    func avatarPicker(isPresented: Binding<Bool>, hasAvatar: Bool, onPick: @escaping (String?) -> Void) -> some View {
        modifier(AvatarPickerModifier(isPresented: isPresented, hasAvatar: hasAvatar, onPick: onPick))
    }
}

struct ProgressOverlay: View {
    let text: String

    var body: some View {
        ZStack {
            Color.black.opacity(0.2).ignoresSafeArea()
            VStack(spacing: 14) {
                Text(text).font(.headline)
                ProgressView()
            }
            .padding(24)
            .frame(minWidth: 220)
            .background(RoundedRectangle(cornerRadius: 24).fill(Color(.systemBackground)))
            .shadow(radius: 20)
        }
    }
}

enum TopController {
    static var current: UIViewController? {
        guard let root = UIApplication.shared.connectedScenes.compactMap({ ($0 as? UIWindowScene)?.keyWindow }).first?.rootViewController else { return nil }
        var top = root
        while let next = top.presentedViewController { top = next }
        return top
    }
}

enum ActionSheet {
    static func present(title: String?, message: String? = nil, actions: [(String, () -> Void)], destructive: (String, () -> Void)? = nil) {
        let sheet = UIAlertController(title: title, message: message, preferredStyle: .actionSheet)
        for (label, action) in actions {
            sheet.addAction(UIAlertAction(title: label, style: .default) { _ in action() })
        }
        if let destructive {
            sheet.addAction(UIAlertAction(title: destructive.0, style: .destructive) { _ in destructive.1() })
        }
        sheet.addAction(UIAlertAction(title: L("cancel"), style: .cancel))
        guard let top = TopController.current else { return }
        if UIDevice.current.userInterfaceIdiom == .pad, let popover = sheet.popoverPresentationController {
            popover.sourceView = top.view
            popover.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
            popover.permittedArrowDirections = []
        }
        top.present(sheet, animated: true)
    }
}

struct CollapsedSearchBar: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> Controller { Controller() }
    func updateUIViewController(_ controller: Controller, context: Context) {}

    final class Controller: UIViewController {
        private var collapsed = false

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            guard !collapsed, let host = parent, let bar = host.navigationItem.searchController?.searchBar,
                  let scroll = Self.scrollView(in: host.view) else { return }
            collapsed = true
            let offset = CGPoint(x: 0, y: -scroll.adjustedContentInset.top + bar.frame.height)
            scroll.setContentOffset(offset, animated: false)
            Log.app.debug("search bar collapsed by \(bar.frame.height)")
        }

        static func scrollView(in view: UIView) -> UIScrollView? {
            if let scroll = view as? UIScrollView { return scroll }
            for sub in view.subviews {
                if let found = scrollView(in: sub) { return found }
            }
            return nil
        }
    }
}

struct AutoFocusTextField: UIViewRepresentable {
    let placeholder: String
    @Binding var text: String

    func makeUIView(context: Context) -> Field {
        let field = Field()
        field.placeholder = placeholder
        field.returnKeyType = .search
        field.keyboardAppearance = .light
        field.clearButtonMode = .whileEditing
        field.delegate = context.coordinator
        field.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .editingChanged)
        field.setContentHuggingPriority(.defaultLow, for: .horizontal)
        return field
    }

    func updateUIView(_ field: Field, context: Context) {
        if field.text != text { field.text = text }
    }

    func makeCoordinator() -> Coordinator { Coordinator(text: $text) }

    final class Field: UITextField {
        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard window != nil else { return }
            DispatchQueue.main.async { [weak self] in
                let focused = self?.becomeFirstResponder() ?? false
                Log.chat.debug("search field focused: \(focused)")
            }
        }
    }

    final class Coordinator: NSObject, UITextFieldDelegate {
        let text: Binding<String>
        init(text: Binding<String>) { self.text = text }

        @objc func changed(_ field: UITextField) { text.wrappedValue = field.text ?? "" }

        func textFieldShouldReturn(_ field: UITextField) -> Bool {
            field.resignFirstResponder()
            return true
        }
    }
}

enum Keyboard {
    static func dismiss() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}
