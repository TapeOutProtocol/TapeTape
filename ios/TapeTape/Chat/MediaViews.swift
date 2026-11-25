import SwiftUI
import AVFoundation
import ImageIO

@MainActor
final class AudioPlayback: NSObject, ObservableObject, AVAudioPlayerDelegate {
    static let shared = AudioPlayback()
    @Published private(set) var current: String?
    @Published private(set) var playing = false
    @Published private(set) var progress: Double = 0
    @Published var failed = false
    private var player: AVAudioPlayer?
    private var timer: Timer?

    func toggle(_ path: String) {
        if current == path, let player {
            if player.isPlaying { pause() } else { resume() }
            return
        }
        stop()
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback)
            try AVAudioSession.sharedInstance().setActive(true)
            let player = try AVAudioPlayer(contentsOf: URL(fileURLWithPath: path))
            player.delegate = self
            self.player = player
            current = path
            resume()
            Log.chat.info("audio playback started")
        } catch {
            Log.chat.error("audio playback failed: \(error.localizedDescription, privacy: .public)")
            failed = true
        }
    }

    func seek(_ path: String, to fraction: Double) {
        guard current == path, let player else { return }
        player.currentTime = fraction * player.duration
        progress = fraction
    }

    private func resume() {
        player?.play()
        playing = true
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let player = self.player, player.duration > 0 else { return }
                self.progress = player.currentTime / player.duration
            }
        }
    }

    private func pause() {
        player?.pause()
        playing = false
        timer?.invalidate()
    }

    func stop() {
        player?.stop()
        player = nil
        current = nil
        playing = false
        progress = 0
        timer?.invalidate()
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.stop() }
    }
}

enum ImageCache {
    private static let cache = NSCache<NSString, UIImage>()

    static func cached(_ key: String) -> UIImage? { cache.object(forKey: key as NSString) }

    static func load(_ path: String, maxPixel: CGFloat) async -> UIImage? {
        let key = "\(path)#\(Int(maxPixel))"
        if let image = cache.object(forKey: key as NSString) { return image }
        let image = await Task.detached(priority: .userInitiated) { () -> UIImage? in
            if path.lowercased().hasSuffix(".mp4") || path.lowercased().hasSuffix(".mov") || path.lowercased().hasSuffix(".m4v") {
                let generator = AVAssetImageGenerator(asset: AVURLAsset(url: URL(fileURLWithPath: path)))
                generator.appliesPreferredTrackTransform = true
                generator.maximumSize = CGSize(width: maxPixel, height: maxPixel)
                return (try? generator.copyCGImage(at: .zero, actualTime: nil)).map(UIImage.init(cgImage:))
            }
            guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil) else { return nil }
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: maxPixel
            ]
            return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary).map(UIImage.init(cgImage:))
        }.value
        if let image { cache.setObject(image, forKey: key as NSString) }
        return image
    }
}

struct FileImage: View {
    let path: String
    var maxPixel: CGFloat = 900
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Color(.systemGray5)
            }
        }
        .task(id: path) { image = await ImageCache.load(path, maxPixel: maxPixel) }
    }
}

struct AnimatedImage: UIViewRepresentable {
    let path: String

    func makeUIView(context: Context) -> UIImageView {
        let view = UIImageView()
        view.contentMode = .scaleAspectFill
        view.clipsToBounds = true
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        return view
    }

    func updateUIView(_ view: UIImageView, context: Context) {
        guard view.accessibilityIdentifier != path else { return }
        view.accessibilityIdentifier = path
        DispatchQueue.global(qos: .userInitiated).async {
            let image = Self.animated(path)
            DispatchQueue.main.async { view.image = image }
        }
    }

    static func animated(_ path: String) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil) else { return nil }
        let count = CGImageSourceGetCount(source)
        guard count > 1 else { return UIImage(contentsOfFile: path) }
        var frames: [UIImage] = []
        var total: Double = 0
        for i in 0..<count {
            guard let cg = CGImageSourceCreateImageAtIndex(source, i, nil) else { continue }
            frames.append(UIImage(cgImage: cg))
            let props = CGImageSourceCopyPropertiesAtIndex(source, i, nil) as? [CFString: Any]
            let gif = props?[kCGImagePropertyGIFDictionary] as? [CFString: Any]
            let webp = props?[kCGImagePropertyWebPDictionary] as? [CFString: Any]
            let delay = (gif?[kCGImagePropertyGIFUnclampedDelayTime] ?? gif?[kCGImagePropertyGIFDelayTime] ?? webp?[kCGImagePropertyWebPUnclampedDelayTime] ?? webp?[kCGImagePropertyWebPDelayTime]) as? Double ?? 0.1
            total += delay < 0.02 ? 0.1 : delay
        }
        return UIImage.animatedImage(with: frames, duration: total)
    }
}
