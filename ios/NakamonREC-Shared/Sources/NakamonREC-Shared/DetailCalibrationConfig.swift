import Foundation

/// 詳細校正の永続化設定。VS画面のカスタム auto-cal で「8 スロット各々のモンスター ID 指定」を保存する。
///
/// - スロット順: 味方 0..3, 敵 0..3 (battlePrepMonsterROIs と同じ順)
/// - ID は MonsterCatalog の id 形式 (例 "id018")。nil は「未指定」
/// - 詳細校正は CalibrationView でユーザーがシートを開いて「校正開始」を押した時のみ実行されるため、
///   ON/OFF のフラグは保持しない (Android のフロー同等)
public enum DetailCalibrationConfig {
    private static let suiteName = "group.com.android.NakamonREC-iOS"
    private static let slotsKey = "detailCalibration.slotIds"

    public static let slotCount = 8

    private static var defaults: UserDefaults? {
        UserDefaults(suiteName: suiteName)
    }

    /// 8 スロットの monster ID 配列 (nil = 未指定)
    /// 配列長は常に 8 を保証 (内部で coerce)
    public static var slotIds: [String?] {
        get {
            guard let arr = defaults?.array(forKey: slotsKey) as? [String] else {
                return Array(repeating: nil, count: slotCount)
            }
            // 配列の "" は nil として扱う
            var result: [String?] = Array(repeating: nil, count: slotCount)
            for (i, v) in arr.prefix(slotCount).enumerated() {
                result[i] = v.isEmpty ? nil : v
            }
            return result
        }
        set {
            // nil は空文字に変換して保存 (UserDefaults の Array<String> 制約への対応)
            var padded = newValue
            while padded.count < slotCount { padded.append(nil) }
            let stringArr = padded.prefix(slotCount).map { $0 ?? "" }
            defaults?.set(Array(stringArr), forKey: slotsKey)
        }
    }

    /// 1 スロットだけ更新
    public static func setSlotId(_ id: String?, at index: Int) {
        guard (0..<slotCount).contains(index) else { return }
        var arr = slotIds
        arr[index] = id
        slotIds = arr
    }

    /// 全 8 スロットが指定済みか (auto-cal 実行可能か判定するヘルパー)
    public static var allSlotsAssigned: Bool {
        slotIds.allSatisfy { $0 != nil }
    }

    public static func resetSlots() {
        slotIds = Array(repeating: nil, count: slotCount)
    }
}
