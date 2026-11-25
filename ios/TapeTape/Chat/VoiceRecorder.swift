import SwiftUI
import AVFoundation

struct VoiceNote {
    let url: URL
    let duration: TimeInterval
}

enum VoiceEffect: CaseIterable {
    case original, robot

    var title: String { L(self == .original ? "voice_effect_original" : "voice_effect_robot") }
    var icon: String { self == .original ? "waveform" : "cpu" }
    var tint: Color { self == .original ? .gray : .green }
    var speed: Double { self == .original ? 1 : 1.1 }
}

enum VoiceEffectProcessor {
    static let settings: [String: Any] = [
        AVFormatIDKey: kAudioFormatMPEG4AAC,
        AVSampleRateKey: 22050,
        AVNumberOfChannelsKey: 1,
        AVEncoderBitRateKey: 24000
    ]

    static func render(_ input: URL, effect: VoiceEffect) throws -> URL {
        guard effect == .robot else { return input }
        let source = try AVAudioFile(forReading: input)
        let format = source.processingFormat
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        let pitch = AVAudioUnitTimePitch()
        pitch.pitch = -700
        pitch.rate = Float(effect.speed)
        engine.attach(player)
        engine.attach(pitch)
        engine.connect(player, to: pitch, format: format)
        engine.connect(pitch, to: engine.mainMixerNode, format: format)
        try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 4096)
        try engine.start()
        player.scheduleFile(source, at: nil)
        player.play()
        let output = FileManager.default.temporaryDirectory.appendingPathComponent("voice-\(UUID().uuidString).m4a")
        var settings = settings
        settings[AVSampleRateKey] = format.sampleRate
        settings[AVNumberOfChannelsKey] = format.channelCount
        let target = try AVAudioFile(forWriting: output, settings: settings, commonFormat: format.commonFormat, interleaved: format.isInterleaved)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: engine.manualRenderingFormat, frameCapacity: engine.manualRenderingMaximumFrameCount) else {
            throw CocoaError(.fileWriteUnknown)
        }
        let total = AVAudioFramePosition(Double(source.length) / effect.speed)
        while engine.manualRenderingSampleTime < total {
            let frames = AVAudioFrameCount(min(Int64(buffer.frameCapacity), total - engine.manualRenderingSampleTime))
            guard try engine.renderOffline(frames, to: buffer) == .success else { break }
            distort(buffer)
            try target.write(from: buffer)
        }
        player.stop()
        engine.stop()
        Log.chat.info("voice effect rendered, \(total) frames")
        return output
    }

    private static func distort(_ buffer: AVAudioPCMBuffer) {
        guard let channels = buffer.floatChannelData else { return }
        for c in 0..<Int(buffer.format.channelCount) {
            let samples = channels[c]
            for i in 0..<Int(buffer.frameLength) {
                samples[i] = tanhf(samples[i] * 1.2) * 0.5
            }
        }
    }
}

@MainActor
final class VoiceRecorderModel: ObservableObject {
    static let maxDuration: TimeInterval = 55
    @Published var elapsed: TimeInterval = 0
    @Published var recording = false
    @Published var denied = false
    @Published var level: CGFloat = 0
    @Published var note: VoiceNote?
    private var recorder: AVAudioRecorder?
    private var timer: Timer?
    private let url = FileManager.default.temporaryDirectory.appendingPathComponent("voice-\(UUID().uuidString).m4a")

    func start() {
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            Task { @MainActor in
                if granted { self.begin() } else { self.denied = true }
            }
        }
    }

    private func begin() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try session.setActive(true)
            let recorder = try AVAudioRecorder(url: url, settings: VoiceEffectProcessor.settings)
            recorder.isMeteringEnabled = true
            recorder.record()
            self.recorder = recorder
            recording = true
            UIApplication.shared.isIdleTimerDisabled = true
            timer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.tick() }
            }
            Log.chat.info("voice recording started")
        } catch {
            Log.chat.error("voice recording failed: \(error.localizedDescription, privacy: .public)")
            denied = true
        }
    }

    private func tick() {
        guard let recorder else { return }
        if recording {
            elapsed = recorder.currentTime
            recorder.updateMeters()
            level = CGFloat(max(0, (recorder.averagePower(forChannel: 0) + 50) / 50))
        }
        if elapsed >= Self.maxDuration { finish() }
    }

    func pause() {
        recorder?.pause()
        recording = false
        level = 0
    }

    func resume() {
        recorder?.record()
        recording = true
    }

    func finish() {
        guard let recorder else { return }
        if recording { elapsed = recorder.currentTime }
        stop()
        note = VoiceNote(url: url, duration: elapsed)
        Log.chat.info("voice recording finished, \(Int(self.elapsed))s")
    }

    func discard() {
        stop()
        recorder?.deleteRecording()
        Log.chat.info("voice recording discarded")
    }

    private func stop() {
        recorder?.stop()
        recording = false
        level = 0
        timer?.invalidate()
        timer = nil
        UIApplication.shared.isIdleTimerDisabled = false
    }
}

struct VoiceRecorderSheet: View {
    let onSend: (VoiceNote) -> Void
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = VoiceRecorderModel()

    var body: some View {
        Group {
            if let note = model.note {
                VoiceEffectView(note: note, onBack: { model.note = nil }, onCancel: close) { result in
                    onSend(result)
                    dismiss()
                }
                .presentationDetents([.height(500)])
                .presentationDragIndicator(.visible)
            } else {
                recorder
                    .presentationDetents([.medium])
            }
        }
        .sheetCornerRadius(20)
        .interactiveDismissDisabled(model.elapsed > 0)
        .onAppear { model.start() }
    }

    private var recorder: some View {
        VStack(spacing: 0) {
            HStack {
                RoundIconButton(system: "xmark", label: L("cancel"), action: close)
                Spacer()
                Text(model.elapsed > 0 ? TimeFormat.clock(model.elapsed) : L("voice_message"))
                    .font(.headline.monospacedDigit())
                Spacer()
                Button(L("menu_send")) { model.finish() }
                    .font(.body.weight(.medium))
                    .foregroundColor(.white)
                    .padding(.horizontal, 16)
                    .frame(height: 36)
                    .background(Capsule().fill(Color.blue))
                    .opacity(model.elapsed > 0 ? 1 : 0.4)
                    .disabled(model.elapsed <= 0)
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
            Spacer()
            if model.denied {
                Text(L("perm_explain_access_to_mic_denied"))
                    .multilineTextAlignment(.center)
                    .foregroundColor(.secondary)
                    .padding(.horizontal, 30)
            } else {
                Waveform(level: model.level, active: model.recording || model.elapsed > 0)
                    .frame(height: 120)
            }
            Spacer()
            HStack(spacing: 90) {
                RoundIconButton(system: "trash", size: 46, label: L("delete"), action: close)
                if model.recording {
                    RoundIconButton(system: "pause.fill", size: 46, label: L("menu_pause")) { model.pause() }
                } else {
                    RoundIconButton(system: "mic", size: 46, label: L("voice_message")) { model.resume() }
                        .disabled(model.denied || model.elapsed == 0)
                }
            }
            .padding(.bottom, 30)
        }
    }

    private func close() {
        model.discard()
        dismiss()
    }
}

struct RoundIconButton: View {
    let system: String
    var size: CGFloat = 36
    var label: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: system)
                .font(.system(size: size * 0.42, weight: .medium))
                .foregroundColor(.primary)
                .frame(width: size, height: size)
                .background(Circle().fill(Color(.systemGray6)))
        }
        .accessibilityLabel(label ?? system)
    }
}

struct Waveform: View {
    let level: CGFloat
    let active: Bool

    var body: some View {
        TimelineView(.animation(paused: !active)) { context in
            Canvas { ctx, size in
                let t = context.date.timeIntervalSinceReferenceDate
                let amplitude = max(0.04, level) * size.height / 2
                for (i, alpha) in [1.0, 0.5, 0.25].enumerated() {
                    var path = Path()
                    let phase = t * (4 + Double(i)) + Double(i)
                    let a = amplitude * CGFloat(1 - Double(i) * 0.3)
                    for x in stride(from: 0, through: size.width, by: 2) {
                        let rel: CGFloat = x / size.width
                        let envelope: CGFloat = sin(rel * CGFloat.pi)
                        let wave: CGFloat = sin(rel * CGFloat.pi * 4 + CGFloat(phase))
                        let y: CGFloat = size.height / 2 + wave * a * envelope
                        if x == 0 { path.move(to: CGPoint(x: x, y: y)) } else { path.addLine(to: CGPoint(x: x, y: y)) }
                    }
                    ctx.stroke(path, with: .color((active ? Color.red : Color.gray).opacity(alpha)), lineWidth: 2)
                }
            }
        }
    }
}

@MainActor
final class VoicePreviewModel: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published var effect: VoiceEffect = .original
    @Published var playing = false
    @Published var processing = false
    @Published var position: TimeInterval = 0
    @Published var length: TimeInterval = 0
    @Published var error: String?
    private var rendered: [VoiceEffect: URL] = [:]
    private var player: AVAudioPlayer?
    private var timer: Timer?
    let note: VoiceNote

    init(note: VoiceNote) {
        self.note = note
        rendered[.original] = note.url
    }

    func select(_ effect: VoiceEffect) {
        stop()
        self.effect = effect
        position = 0
    }

    func render(_ effect: VoiceEffect) async -> URL? {
        if let url = rendered[effect] { return url }
        processing = true
        defer { processing = false }
        let input = note.url
        do {
            let url = try await Task.detached { try VoiceEffectProcessor.render(input, effect: effect) }.value
            rendered[effect] = url
            return url
        } catch {
            Log.chat.error("voice effect failed: \(error.localizedDescription, privacy: .public)")
            self.error = error.localizedDescription
            return nil
        }
    }

    func toggle() {
        if playing { stop(); return }
        Task { [weak self] in
            guard let self, let url = await self.render(self.effect), let player = try? AVAudioPlayer(contentsOf: url) else { return }
            try? AVAudioSession.sharedInstance().setCategory(.playback)
            player.delegate = self
            player.currentTime = self.position
            player.play()
            self.player = player
            self.length = player.duration
            self.playing = true
            self.timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    guard let self, let player = self.player else { return }
                    self.position = player.currentTime
                }
            }
        }
    }

    func seek(_ value: TimeInterval) {
        position = value
        player?.currentTime = value
    }

    func stop() {
        player?.stop()
        player = nil
        playing = false
        timer?.invalidate()
        timer = nil
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            self.stop()
            self.position = 0
        }
    }
}

struct VoiceEffectView: View {
    let onBack: () -> Void
    let onCancel: () -> Void
    let onSend: (VoiceNote) -> Void
    @StateObject private var model: VoicePreviewModel
    @State private var sending = false

    init(note: VoiceNote, onBack: @escaping () -> Void, onCancel: @escaping () -> Void, onSend: @escaping (VoiceNote) -> Void) {
        self.onBack = onBack
        self.onCancel = onCancel
        self.onSend = onSend
        _model = StateObject(wrappedValue: VoicePreviewModel(note: note))
    }

    private var shownDuration: TimeInterval { model.note.duration / model.effect.speed }

    var body: some View {
        VStack(spacing: 16) {
            ZStack {
                Text(L("voice_effect_title")).font(.headline)
                HStack {
                    RoundIconButton(system: "chevron.left") {
                        model.stop()
                        onBack()
                    }
                    Spacer()
                }
            }
            .padding(.top, 20)
            VStack(spacing: 10) {
                Image(systemName: "waveform").font(.system(size: 26)).foregroundColor(.secondary)
                Text(TimeFormat.clock(shownDuration)).font(.system(size: 24, weight: .medium).monospacedDigit())
                Button { model.toggle() } label: {
                    HStack(spacing: 6) {
                        if model.processing {
                            ProgressView()
                        } else {
                            Image(systemName: model.playing ? "stop.circle.fill" : "play.circle.fill").font(.title3).foregroundColor(.blue)
                        }
                        Text(L(model.playing ? "voice_effect_stop" : "voice_effect_preview")).foregroundColor(.primary)
                    }
                }
                .disabled(model.processing)
                Slider(value: Binding(get: { model.position }, set: { model.seek($0) }), in: 0...max(model.length, shownDuration, 0.1))
                if model.playing || model.position > 0 {
                    Text("\(TimeFormat.short(model.position)) / \(TimeFormat.short(max(model.length, shownDuration)))")
                        .font(.system(size: 12).monospacedDigit())
                        .foregroundColor(.secondary)
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.systemGray6)))
            HStack(spacing: 12) {
                ForEach(VoiceEffect.allCases, id: \.self) { effect in
                    EffectTile(effect: effect, selected: model.effect == effect) { model.select(effect) }
                }
                Spacer()
            }
            Spacer()
            HStack(spacing: 12) {
                Button {
                    model.stop()
                    onCancel()
                } label: {
                    Text(L("cancel")).foregroundColor(.secondary).frame(maxWidth: .infinity, minHeight: 50)
                        .background(RoundedRectangle(cornerRadius: 12).fill(Color(.systemGray6)))
                }
                Button { send() } label: {
                    ZStack {
                        if sending { ProgressView().tint(.white) } else { Text(L("menu_send")).font(.body.weight(.semibold)) }
                    }
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity, minHeight: 50)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color.tapetapePrimary))
                }
                .disabled(sending)
            }
            .padding(.bottom, 20)
        }
        .padding(.horizontal, 20)
        .alert(L("error"), isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button(L("ok")) {}
        } message: {
            Text(model.error ?? "")
        }
    }

    private func send() {
        model.stop()
        sending = true
        Task {
            if let url = await model.render(model.effect) {
                onSend(VoiceNote(url: url, duration: shownDuration))
            }
            sending = false
        }
    }
}

struct EffectTile: View {
    let effect: VoiceEffect
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: effect.icon).font(.system(size: 24)).foregroundColor(effect.tint)
                Text(effect.title).font(.caption).foregroundColor(selected ? .tapetapePrimary : .secondary)
            }
            .frame(width: 72, height: 88)
            .background(RoundedRectangle(cornerRadius: 12).fill(Color(.systemGray6)))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(selected ? Color.tapetapePrimary : .clear, lineWidth: 1.5))
            .overlay(alignment: .topTrailing) {
                if selected {
                    Image(systemName: "checkmark.circle.fill").foregroundColor(.tapetapePrimary).padding(4)
                }
            }
        }
    }
}
