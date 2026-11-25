import SwiftUI
import AVFoundation
import TapeTapeCore

struct QRView: View {
    var groupId: UInt32? = nil
    @EnvironmentObject var model: AppModel
    @State private var page = 0
    @State private var link = ""
    @State private var share: ShareItem?
    @State private var showReset = false

    var body: some View {
        if let groupId {
            GroupQRView(chatId: groupId)
        } else {
            contactQR
        }
    }

    private var contactQR: some View {
        TabView(selection: $page) {
            InviteCard(link: link, hint: L("qrshow_join_contact_hint", model.info?.name ?? ""), share: { share = ShareItem(items: [URL(string: link) ?? link]) }) {
                if let info = model.info {
                    AvatarView(name: info.name, seed: info.address, path: info.avatarPath, size: 48)
                }
            }
                .tag(0)
            ScannerPage(active: page == 1)
                .tag(1)
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .ignoresSafeArea(edges: page == 1 ? .all : [])
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker("", selection: $page) {
                    Text(L("qrshow_title")).tag(0)
                    Text(L("qrscan_title")).tag(1)
                }
                .pickerStyle(.segmented)
                .fixedSize()
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    if page == 0 {
                        Button { share = ShareItem(items: [URL(string: link) ?? link]) } label: { Label(L("menu_share"), systemImage: "square.and.arrow.up") }
                        Button { UIPasteboard.general.string = link } label: { Label(L("menu_copy_to_clipboard"), systemImage: "doc.on.doc") }
                    }
                    Button { paste() } label: { Label(L("paste_from_clipboard"), systemImage: "doc.on.clipboard") }
                    if page == 0 {
                        Divider()
                        Button(role: .destructive) { showReset = true } label: { Label(L("withdraw_qr_code"), systemImage: "trash") }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .onAppear { link = model.account?.inviteLink() ?? "" }
        .sheet(item: $share) { ShareSheet(items: $0.items).presentationDetents([.medium, .large]) }
        .alert(L("withdraw_qr_code"), isPresented: $showReset) {
            Button(L("cancel"), role: .cancel) {}
            Button(L("reset"), role: .destructive) {
                model.account?.resetInvite()
                link = model.account?.inviteLink() ?? ""
                Log.invite.info("invite token reset")
            }
        } message: {
            Text(L("withdraw_verifycontact_explain"))
        }
    }

    private func paste() {
        guard let text = UIPasteboard.general.string, !text.isEmpty else { return }
        model.handleScanned(text)
    }
}

struct GroupQRView: View {
    let chatId: UInt32
    @EnvironmentObject var model: AppModel
    @State private var link = ""
    @State private var chat: Chat?
    @State private var share: ShareItem?
    @State private var showReset = false

    var body: some View {
        InviteCard(link: link, hint: L("qrshow_join_group_hint", chat?.name ?? ""), share: shareLink) {
            if let chat {
                ChatAvatar(name: chat.name, seed: chat.name, path: chat.avatarPath, size: 48, isSelf: false, isDevice: false)
            }
        }
        .navigationTitle(L("qrshow_join_group_title"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button(action: shareLink) { Label(L("menu_share"), systemImage: "square.and.arrow.up") }
                    Button { UIPasteboard.general.string = link } label: { Label(L("menu_copy_to_clipboard"), systemImage: "doc.on.doc") }
                    Divider()
                    Button(role: .destructive) { showReset = true } label: { Label(L("withdraw_qr_code"), systemImage: "trash") }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .onAppear(perform: load)
        .sheet(item: $share) { ShareSheet(items: $0.items).presentationDetents([.medium, .large]) }
        .alert(L("withdraw_qr_code"), isPresented: $showReset) {
            Button(L("cancel"), role: .cancel) {}
            Button(L("reset"), role: .destructive) {
                model.perform("reset group invite \(chatId)") { try model.account?.resetGroupInvite(chatId: chatId) }
                load()
            }
        } message: {
            Text(L("withdraw_verifygroup_explain", chat?.name ?? ""))
        }
    }

    private func shareLink() {
        share = ShareItem(items: [URL(string: link) ?? link])
    }

    private func load() {
        guard let account = model.account else { return }
        chat = try? account.chat(chatId: chatId)
        do {
            link = try account.groupInviteLink(chatId: chatId)
        } catch {
            Log.invite.error("group invite link failed: \(coreMessage(error), privacy: .public)")
        }
    }
}

struct InviteCard<Avatar: View>: View {
    let link: String
    let hint: String
    let share: () -> Void
    @ViewBuilder let avatar: () -> Avatar

    var body: some View {
        VStack(spacing: 24) {
            VStack(spacing: 8) {
                ZStack {
                    if let image = QRCode.image(link) {
                        Image(uiImage: image).interpolation(.none).resizable().frame(width: 202, height: 202)
                    }
                    avatar()
                        .padding(4)
                        .background(Circle().fill(Color.white))
                }
                Text(hint)
                    .font(.system(size: 14))
                    .foregroundColor(.black)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 202)
            }
            .padding(.horizontal, 28)
            .padding(.top, 36)
            .padding(.bottom, 52)
            .background(RoundedRectangle(cornerRadius: 24).fill(Color(hex: 0xF1F1F1)))
            .overlay(RoundedRectangle(cornerRadius: 24).stroke(Color(hex: 0xC4C4C4), lineWidth: 1))
            Button(L("share_invite_link"), action: share)
                .foregroundColor(.gray)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.bottom, 60)
    }
}

struct ScannerPage: View {
    let active: Bool
    @EnvironmentObject var model: AppModel
    @State private var authorized = false
    @State private var showDenied = false
    @State private var scanned = false
    private let hasCamera = AVCaptureDevice.default(for: .video) != nil

    var body: some View {
        ZStack {
            Color.black
            if authorized && hasCamera && !scanned {
                CameraPreview { code in
                    scanned = true
                    Log.invite.info("qr code scanned")
                    model.handleScanned(code)
                }
            }
            VStack {
                if authorized && !hasCamera {
                    Spacer()
                    Text(L("chat_camera_unavailable")).foregroundColor(.white)
                    Spacer()
                } else {
                    Text(L("qrscan_hint"))
                        .font(.title2)
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .padding(.top, 130)
                        .padding(.horizontal, 20)
                    Spacer()
                }
            }
        }
        .onChange(of: active) { isActive in
            if isActive {
                scanned = false
                checkPermission()
            }
        }
        .alert(L("perm_required_title"), isPresented: $showDenied) {
            Button(L("open_settings")) {
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
            Button(L("cancel"), role: .destructive) {}
        } message: {
            Text(L("perm_ios_explain_access_to_camera_denied"))
        }
    }

    private func checkPermission() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            authorized = true
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    authorized = granted
                    if !granted { showDenied = true }
                }
            }
        default:
            Log.invite.notice("camera permission denied")
            showDenied = true
        }
    }
}

struct CameraPreview: UIViewRepresentable {
    let onCode: (String) -> Void

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.onCode = onCode
        view.start()
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {}

    static func dismantleUIView(_ view: PreviewView, coordinator: ()) {
        view.stop()
    }

    final class PreviewView: UIView, AVCaptureMetadataOutputObjectsDelegate {
        let session = AVCaptureSession()
        var onCode: ((String) -> Void)?
        private var done = false

        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

        func start() {
            guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else { return }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else { return }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
            let layer = self.layer as! AVCaptureVideoPreviewLayer
            layer.session = session
            layer.videoGravity = .resizeAspectFill
            DispatchQueue.global(qos: .userInitiated).async { [session] in session.startRunning() }
        }

        func stop() {
            DispatchQueue.global(qos: .userInitiated).async { [session] in session.stopRunning() }
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard !done, let code = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue else { return }
            done = true
            stop()
            onCode?(code)
        }
    }
}
