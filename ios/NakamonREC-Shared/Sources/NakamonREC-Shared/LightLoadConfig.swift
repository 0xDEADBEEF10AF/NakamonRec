import Foundation

/// 軽負荷モードの永続化設定。App Group UserDefaults に保存し、Host / Extension で共有する。
/// - `mode` : "normal" = 全モンスターをマッチング対象 / "light" = 指定モンスターのみ
/// - `lightMonsterIDs` : 軽負荷モード時のマッチング対象 ID 集合 (例: "id002", "id020", ...)
public enum LightLoadConfig {
    private static let suiteName = "group.com.android.NakamonREC-iOS"
    private static let modeKey = "lightLoad.mode"
    private static let monsterIDsKey = "lightLoad.monsterIDs"

    private static var defaults: UserDefaults? {
        UserDefaults(suiteName: suiteName)
    }

    /// Android `target_monsters.json` 由来のデフォルト軽負荷モンスター集合。
    /// iOS の monsters.json に存在する ID だけが有効。
    public static let defaultLightMonsterIDs: [String] = [
        "id002", "id020", "id021", "id025", "id034", "id041",
        "id046", "id047", "id050", "id058", "id059", "id070",
        "id072", "id076", "id077", "id082", "id083", "id087",
        "id088", "id094", "id095", "id100", "id101", "id102",
        "id105",
        "id10002", "id10003", "id10006", "id10008", "id10009",
        "id10011", "id10012", "id10013", "id10014", "id10015",
        "id10017"
    ]

    public enum Mode: String {
        case normal
        case light
    }

    /// 現在のモード (デフォルトは normal)
    public static var mode: Mode {
        get {
            let raw = defaults?.string(forKey: modeKey) ?? Mode.normal.rawValue
            return Mode(rawValue: raw) ?? .normal
        }
        set {
            defaults?.set(newValue.rawValue, forKey: modeKey)
        }
    }

    /// 現在の軽負荷モード対象 ID 集合 (未設定時はデフォルトを返す)
    public static var lightMonsterIDs: Set<String> {
        get {
            if let arr = defaults?.array(forKey: monsterIDsKey) as? [String] {
                return Set(arr)
            }
            return Set(defaultLightMonsterIDs)
        }
        set {
            defaults?.set(Array(newValue), forKey: monsterIDsKey)
        }
    }

    /// 1 体を軽負荷対象に追加
    public static func addMonster(_ id: String) {
        var set = lightMonsterIDs
        set.insert(id)
        lightMonsterIDs = set
    }

    /// 1 体を軽負荷対象から削除
    public static func removeMonster(_ id: String) {
        var set = lightMonsterIDs
        set.remove(id)
        lightMonsterIDs = set
    }

    /// 軽負荷対象をデフォルトに戻す
    public static func resetToDefault() {
        lightMonsterIDs = Set(defaultLightMonsterIDs)
    }

    /// 現モードで実際にマッチング対象とするモンスター ID 集合
    /// - 通常モード: monsters.json の全 ID
    /// - 軽負荷モード: lightMonsterIDs と monsters.json の積集合 (存在しない ID は除外)
    public static func effectiveMonsterIDs() -> Set<String> {
        switch mode {
        case .normal:
            return MonsterCatalog.allIDs
        case .light:
            return lightMonsterIDs.intersection(MonsterCatalog.allIDs)
        }
    }
}
