import Foundation

/// monsters.json の 1 エントリ。Android 版の monster master と同じスキーマ。
public struct MonsterEntry: Codable, Hashable, Identifiable, Sendable {
    /// 正式名称 (例: "ベビーパンサー"、"暗黒神ラプソーン")。画面表示・レコード/CSV の書き出しはこちら (OUTPUT)
    public let name: String
    /// 略称 (例: "ラプソーン")。読み出し側 (CSV 取り込み等) で name と並んで照合する (INPUT)。
    /// 正式名称と違う呼び名で保存された過去データを読めるようにするためのもの。monsters.json で省略可能。
    /// 前提: 全モンスターの name と shortName を合わせて重複がないこと (DEBUG ビルドで起動時に検証)
    public let shortName: String?
    /// テンプレ画像のファイル名 (例: "id001.png")
    public let fileName: String
    /// 壁役モンスターか否か。パーティ構成ランキングでの表示順 (壁を左側) にだけ使用。
    /// 厳密な「壁役」ではなく「通常パーティの左端に置くのがセオリーのモンスター」も含む
    /// (例: ガチャコッコ、2026-10-30)。monsters.json で省略可能 (省略時 false 扱い)。
    public let isWall: Bool?

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
        let entries = (try? JSONDecoder().decode([MonsterEntry].self, from: data)) ?? []
        #if DEBUG
        // name / shortName を合わせた一意性 (略称での逆引きが曖昧にならないための前提)
        let allNames = entries.map(\.name) + entries.compactMap(\.shortName)
        let duplicates = Dictionary(grouping: allNames, by: { $0 }).filter { $0.value.count > 1 }.keys
        assert(duplicates.isEmpty, "monsters.json: name/shortName が重複しています: \(duplicates.sorted())")
        #endif
        return entries
    }()

    /// 全モンスターの ID 集合
    public static var allIDs: Set<String> {
        Set(all.map(\.id))
    }

    /// 指定 ID の名前 (見つからなければ ID をそのまま返す)
    public static func name(for id: String) -> String {
        all.first(where: { $0.id == id })?.name ?? id
    }

    /// 名前から ID への逆引き (見つからなければ nil)。CSV インポート時に使用。
    /// 正式名称 (name) と略称 (shortName) のどちらに一致してもよい (INPUT 側は両方で判定する仕様)
    public static func id(for name: String) -> String? {
        all.first(where: { $0.name == name || $0.shortName == name })?.id
    }

    /// 指定 ID が壁モンスターか
    public static func isWall(id: String) -> Bool {
        all.first(where: { $0.id == id })?.isWall == true
    }
}
