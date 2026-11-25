import Foundation
import CryptoKit

enum TimeFormat {
    private static func formatter(_ template: String) -> DateFormatter {
        let f = DateFormatter()
        f.setLocalizedDateFormatFromTemplate(template)
        return f
    }

    private static let hm = formatter("HH:mm")
    private static let weekdayHM = formatter("EEE HH:mm")
    private static let monthDayHM = formatter("MMM d HH:mm")
    private static let fullHM = formatter("MMM d yyyy HH:mm")
    private static let weekday = formatter("EEE")
    private static let monthDay = formatter("MMM d")
    private static let monthDayYear = formatter("MMM d yyyy")
    private static let separator: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .full
        f.timeStyle = .none
        return f
    }()

    static func clock(_ seconds: TimeInterval) -> String {
        let s = Int(seconds.rounded(.down))
        return String(format: "%02d:%02d", s / 60, s % 60)
    }

    static func short(_ seconds: TimeInterval) -> String {
        let s = Int(seconds.rounded())
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    static func size(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }

    static func bubble(_ ms: Int64, now: Date = Date()) -> String {
        let d = date(ms)
        let age = now.timeIntervalSince(d)
        let cal = Calendar.current
        if age < 60 { return L("now") }
        if age < 3600 { return LPlural("n_minutes", Int(age / 60)) }
        if cal.isDateInToday(d) { return hm.string(from: d) }
        if age < 6 * 86400 { return weekdayHM.string(from: d) }
        if cal.isDate(d, equalTo: now, toGranularity: .year) { return monthDayHM.string(from: d) }
        return fullHM.string(from: d)
    }

    static func chatList(_ ms: Int64, now: Date = Date()) -> String {
        guard ms > 0 else { return "" }
        let d = date(ms)
        let age = now.timeIntervalSince(d)
        if age < 60 { return L("now") }
        if age < 3600 { return LPlural("n_minutes", Int(age / 60)) }
        if age < 86400 { return LPlural("n_hours", Int(age / 3600)) }
        if age < 6 * 86400 { return weekday.string(from: d) }
        if Calendar.current.isDate(d, equalTo: now, toGranularity: .year) { return monthDay.string(from: d) }
        return monthDayYear.string(from: d)
    }

    static func daySeparator(_ ms: Int64) -> String {
        let d = date(ms)
        let cal = Calendar.current
        if cal.isDateInToday(d) { return L("today") }
        if cal.isDateInYesterday(d) { return L("yesterday") }
        return separator.string(from: d)
    }
}

enum Mnemonic {
    static let words: [String] = {
        let url = Bundle.main.url(forResource: "bip39-english", withExtension: "txt")!
        return try! String(contentsOf: url, encoding: .utf8).split(separator: "\n").map(String.init)
    }()

    static func normalize(_ input: String) -> String {
        input.lowercased().split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    static func isValid(_ phrase: String) -> Bool {
        let list = phrase.split(separator: " ").map(String.init)
        guard list.count == 12 || list.count == 24 else { return false }
        let index = Dictionary(uniqueKeysWithValues: words.enumerated().map { ($1, $0) })
        var bits: [UInt8] = []
        for w in list {
            guard let i = index[w] else { return false }
            bits += (0..<11).reversed().map { UInt8((i >> $0) & 1) }
        }
        let checksumBits = bits.count / 33
        let entropyBits = bits.count - checksumBits
        let entropy = stride(from: 0, to: entropyBits, by: 8).map { i in bits[i..<i + 8].reduce(UInt8(0)) { $0 << 1 | $1 } }
        let hash = Array(SHA256.hash(data: Data(entropy)))
        let expected = (0..<checksumBits).map { (hash[0] >> (7 - $0)) & 1 }
        return Array(bits[entropyBits...]) == expected
    }
}
