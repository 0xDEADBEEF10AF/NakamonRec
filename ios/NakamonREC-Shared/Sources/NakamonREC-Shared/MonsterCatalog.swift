import Foundation

/// monsters.json の 1 エントリ。Android 版の monster master と同じスキーマ。
public struct MonsterEntry: Codable, Hashable, Identifiable, Sendable {
    /// 日本語名 (例: "ベビーパンサー")
    public let name: String
    /// テンプレ画像のファイル名 (例: "id001.png")
    public let fileName: String

    /// テンプレ ID (例: "id001")。fileName から .png を取り除いたもの
    public var id: String {
        fileName.hasSuffix(".png") ? String(fileName.dropLast(4)) : fileName
    }
}

/// シェアパッケージにバンドルされた monsters.json をロードして提供するカタログ。
/// - Host / Extension の両方から `Bundle.module` 経由で参照できる
/// - 戦闘解析・ピッカー UI・軽負荷モード設定 等の共通データ源
public enum MonsterCatalog {
    /// 全モンスターエントリ。読み込みに失敗した場合は空配列
    public static let all: [MonsterEntry] = {
        guard let url = Bundle.module.url(forResource: "monsters", withExtension: "json"),
              let data = try? Data(contentsOf: url) else {
            return []
        }
        return (try? JSONDecoder().decode([MonsterEntry].self, from: data)) ?? []
    }()

    /// 全モンスターの ID 集合
    public static var allIDs: Set<String> {
        Set(all.map(\.id))
    }

    /// 指定 ID の名前 (見つからなければ ID をそのまま返す)
    public static func name(for id: String) -> String {
        all.first(where: { $0.id == id })?.name ?? id
    }

    /// 日本語名から ID への逆引き (見つからなければ nil)。CSV インポート時に使用。
    public static func id(for name: String) -> String? {
        all.first(where: { $0.name == name })?.id
    }
}
