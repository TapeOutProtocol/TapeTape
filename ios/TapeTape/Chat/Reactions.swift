import SwiftUI
import TapeTapeCore

enum EmojiData {
    struct Group: Identifiable {
        let id: String
        let icon: String
        let emojis: [String]
    }

    private static let icons: [String: String] = [
        "Smileys & People": "face.smiling",
        "Animals & Nature": "pawprint",
        "Food & Drink": "fork.knife",
        "Activity": "soccerball",
        "Travel & Places": "car",
        "Objects": "lightbulb",
        "Symbols": "number",
        "Flags": "flag"
    ]
    private static let order = ["Smileys & People", "Animals & Nature", "Food & Drink", "Activity", "Travel & Places", "Objects", "Symbols", "Flags"]

    static let groups: [Group] = {
        guard let url = Bundle.main.url(forResource: "emoji", withExtension: "txt"), let content = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        var map: [String: [String]] = [:]
        for line in content.split(separator: "\n") {
            let parts = line.split(separator: "|", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { continue }
            map[parts[0]] = parts[1].split(separator: " ").map(String.init)
        }
        return order.compactMap { name in map[name].map { Group(id: name, icon: icons[name] ?? "circle", emojis: $0) } }
    }()

    private static let recentKey = "recentReactions"

    static var recent: [String] {
        UserDefaults.standard.stringArray(forKey: recentKey) ?? ["👍", "👎", "❤️", "😂", "🙁"]
    }

    static func use(_ emoji: String) {
        var list = recent.filter { $0 != emoji }
        list.insert(emoji, at: 0)
        UserDefaults.standard.set(Array(list.prefix(16)), forKey: recentKey)
    }
}

struct EmojiPickerSheet: View {
    let current: String?
    let onPick: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    private let columns = Array(repeating: GridItem(.flexible(), spacing: 4), count: 8)

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8, pinnedViews: []) {
                        section(id: "recent", title: L("v2_frequently_used").uppercased(), emojis: EmojiData.recent)
                        ForEach(EmojiData.groups) { group in
                            section(id: group.id, title: L("v2_emoji_\(group.id)").uppercased(), emojis: group.emojis)
                        }
                    }
                    .padding(.horizontal, 12)
                }
                .safeAreaInset(edge: .bottom) {
                    HStack {
                        Button { withAnimation { proxy.scrollTo("recent", anchor: .top) } } label: { Image(systemName: "clock") }
                        ForEach(EmojiData.groups) { group in
                            Spacer()
                            Button { withAnimation { proxy.scrollTo(group.id, anchor: .top) } } label: { Image(systemName: group.icon) }
                        }
                    }
                    .foregroundColor(.secondary)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                    .background(.bar)
                }
            }
            .navigationTitle(L("react"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .sheetCornerRadius(20)
    }

    private func section(id: String, title: String, emojis: [String]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption.weight(.medium)).foregroundColor(.secondary).padding(.top, 10)
            LazyVGrid(columns: columns, spacing: 4) {
                ForEach(emojis, id: \.self) { emoji in
                    Button {
                        EmojiData.use(emoji)
                        onPick(emoji)
                        dismiss()
                    } label: {
                        Text(emoji)
                            .font(.system(size: 30))
                            .frame(width: 40, height: 40)
                            .background(RoundedRectangle(cornerRadius: 8).fill(emoji == current ? Color(.systemGray5) : .clear))
                    }
                }
            }
        }
        .id(id)
    }
}

struct ReactionListSheet: View {
    let messageId: UInt32
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss

    private var rows: [(key: String, contactId: UInt32?, name: String, emojis: String)] {
        let list = model.account?.messageReactions(msgId: messageId) ?? []
        var grouped: [String: (UInt32?, String, [String])] = [:]
        var order: [String] = []
        for r in list {
            let key = r.contactId.map { "\($0)" } ?? "me"
            if grouped[key] == nil { order.append(key); grouped[key] = (r.contactId, r.contactId == nil ? L("self") : r.name, []) }
            grouped[key]?.2.append(r.emoji)
        }
        return order.compactMap { key in grouped[key].map { (key, $0.0, $0.1, $0.2.joined(separator: ",")) } }
    }

    var body: some View {
        NavigationStack {
            List(rows, id: \.key) { row in
                Button {
                    guard let id = row.contactId else { return }
                    dismiss()
                    model.openContactProfile(id)
                } label: {
                    Text("\(row.name): \(row.emojis)").foregroundColor(.primary)
                }
            }
            .listStyle(.plain)
            .navigationTitle(L("reactions"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
