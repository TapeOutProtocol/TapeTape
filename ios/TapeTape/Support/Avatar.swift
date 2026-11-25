import SwiftUI
import CryptoKit

enum AvatarColor {
    static func color(for seed: String) -> Color {
        let digest = Array(Insecure.SHA1.hash(data: Data(seed.lowercased().utf8)))
        let hue = Double(Int(digest[0]) + 256 * Int(digest[1])) / 65536 * 360
        let (r, g, b) = HSLuv.toRGB(h: hue, s: 100, l: 50)
        return Color(.sRGB, red: r, green: g, blue: b)
    }
}

enum HSLuv {
    private static let m: [[Double]] = [
        [3.240969941904521, -1.537383177570093, -0.498610760293],
        [-0.96924363628087, 1.87596750150772, 0.041555057407175],
        [0.055630079696993, -0.20397695888897, 1.056971514242878],
    ]
    private static let refU = 0.19783000664283
    private static let refV = 0.46831999493879
    private static let kappa = 903.2962962
    private static let epsilon = 0.0088564516

    static func toRGB(h: Double, s: Double, l: Double) -> (Double, Double, Double) {
        let c: Double
        if l > 99.9999999 || l < 0.00000001 { c = 0 } else { c = maxChroma(l: l, h: h) / 100 * s }
        let hrad = h / 360 * 2 * .pi
        let u = cos(hrad) * c
        let v = sin(hrad) * c
        guard l > 0 else { return (0, 0, 0) }
        let varU = u / (13 * l) + refU
        let varV = v / (13 * l) + refV
        let y = l <= 8 ? l / kappa : pow((l + 16) / 116, 3)
        let x = 0 - (9 * y * varU) / ((varU - 4) * varV - varU * varV)
        let z = (9 * y - 15 * varV * y - varV * x) / (3 * varV)
        let rgb = m.map { row in fromLinear(row[0] * x + row[1] * y + row[2] * z) }
        return (clamp(rgb[0]), clamp(rgb[1]), clamp(rgb[2]))
    }

    private static func clamp(_ v: Double) -> Double { min(1, max(0, v)) }

    private static func fromLinear(_ c: Double) -> Double {
        c <= 0.0031308 ? 12.92 * c : 1.055 * pow(c, 1 / 2.4) - 0.055
    }

    private static func maxChroma(l: Double, h: Double) -> Double {
        let hrad = h / 360 * 2 * .pi
        let sub1 = pow(l + 16, 3) / 1560896
        let sub2 = sub1 > epsilon ? sub1 : l / kappa
        var result = Double.greatestFiniteMagnitude
        for row in m {
            for t in [0.0, 1.0] {
                let top1 = (284517 * row[0] - 94839 * row[2]) * sub2
                let top2 = (838422 * row[2] + 769860 * row[1] + 731718 * row[0]) * l * sub2 - 769860 * t * l
                let bottom = (632260 * row[2] - 126452 * row[1]) * sub2 + 126452 * t
                let slope = top1 / bottom
                let intercept = top2 / bottom
                let length = intercept / (sin(hrad) - slope * cos(hrad))
                if length >= 0 { result = min(result, length) }
            }
        }
        return result
    }
}

struct AvatarView: View {
    let name: String
    let seed: String
    let path: String?
    let size: CGFloat

    var body: some View {
        Group {
            if let path, let image = UIImage(contentsOfFile: path) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                ZStack {
                    AvatarColor.color(for: seed)
                    Text(initial)
                        .font(.system(size: size * 0.6))
                        .foregroundColor(.white)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
    }

    private var initial: String {
        name.trimmingCharacters(in: .whitespaces).first.map { String($0).uppercased() } ?? "#"
    }
}

struct CameraPlaceholder: View {
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle().fill(Color.avatarPlaceholder)
            Image("camera").resizable().renderingMode(.template).foregroundColor(.white).scaledToFit().frame(width: size)
        }
        .frame(width: size, height: size)
    }
}

struct VerifiedBadge: View {
    var size: CGFloat = 17

    var body: some View {
        Image("verified").resizable().scaledToFit().frame(width: size, height: size)
    }
}

struct ChatAvatar: View {
    let name: String
    let seed: String
    let path: String?
    let size: CGFloat
    var isSelf = false
    var isDevice = false

    var body: some View {
        if isSelf {
            SpecialAvatar(color: Color(hex: 0x79E9D8), size: size) { SavedGlyph() }
        } else if isDevice {
            SpecialAvatar(color: Color(hex: 0xC5B1F5), size: size) { DeviceGlyph(dot: Color(hex: 0xC5B1F5)) }
        } else {
            AvatarView(name: name, seed: seed, path: path, size: size)
        }
    }
}

struct SpecialAvatar<Glyph: View>: View {
    let color: Color
    let size: CGFloat
    @ViewBuilder let glyph: () -> Glyph

    var body: some View {
        ZStack {
            Circle().fill(color)
            glyph().frame(width: size * 0.5, height: size * 0.5)
        }
        .frame(width: size, height: size)
    }
}

struct SavedGlyph: View {
    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            ctx.fill(Path(roundedRect: CGRect(x: w * 0.04, y: w * 0.04, width: w * 0.92, height: w * 0.92), cornerRadius: w * 0.12), with: .color(.white))
            ctx.fill(Path(CGRect(x: w * 0.2, y: w * 0.16, width: w * 0.6, height: w * 0.2)), with: .color(Color(hex: 0x79E9D8)))
            ctx.fill(Path(ellipseIn: CGRect(x: w * 0.36, y: w * 0.52, width: w * 0.28, height: w * 0.28)), with: .color(Color(hex: 0x79E9D8)))
        }
    }
}

struct DeviceGlyph: View {
    let dot: Color

    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            var bubble = Path(roundedRect: CGRect(x: 0, y: w * 0.1, width: w, height: w * 0.68), cornerRadius: w * 0.12)
            bubble.move(to: CGPoint(x: w * 0.42, y: w * 0.77))
            bubble.addLine(to: CGPoint(x: w * 0.5, y: w * 0.9))
            bubble.addLine(to: CGPoint(x: w * 0.58, y: w * 0.77))
            ctx.fill(bubble, with: .color(.white))
            for x in [0.25, 0.5, 0.75] {
                ctx.fill(Path(ellipseIn: CGRect(x: w * x - w * 0.07, y: w * 0.37, width: w * 0.14, height: w * 0.14)), with: .color(dot))
            }
        }
    }
}
