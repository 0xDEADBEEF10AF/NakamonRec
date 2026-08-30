import Foundation

/// Host (アプリ本体) と Broadcast Upload Extension の間で放送状態を共有するヘルパー。
/// App Group の UserDefaults を介して `isActive` (Extension が現在ブロードキャスト中か) を
/// 中継する。Host 側は polling で読み取り、REC/STOP のボタン表示を切り替える。
///
/// 停止操作は Host から `RPSystemBroadcastPickerView` を経由して iOS のシステム
/// シート (「ブロードキャストを停止」) で行うため、Host→Extension の通信は不要。
public enum BroadcastStatus {
    private static let suiteName = "group.com.android.NakamonREC-iOS"
    private static let activeKey = "broadcastActive"
    private static let heartbeatKey = "broadcastHeartbeat"
    private static let lastRecordKey = "lastRecordTimestamp"

    private static var defaults: UserDefaults? {
        UserDefaults(suiteName: suiteName)
    }

    public static var isActive: Bool {
        defaults?.bool(forKey: activeKey) ?? false
    }

    /// Extension が放送開始/終了時に呼ぶ
    public static func setActive(_ value: Bool) {
        defaults?.set(value, forKey: activeKey)
        if value { beat() }
    }

    /// Extension がフレーム処理中に数秒おきに呼ぶ生存通知。
    /// broadcastFinished を呼べずに Extension が死んだ場合 (メモリ上限での強制終了や
    /// 終了直前の書き込み欠落) に activeKey が true のまま残留するため、
    /// Host はこのハートビートの鮮度も見て実効状態を判定する。
    public static func beat() {
        defaults?.set(Date().timeIntervalSince1970, forKey: heartbeatKey)
    }

    /// Host が REC/STOP ボタン表示に使う実効状態。
    /// active フラグが立っていてもハートビートが staleAfter 秒以上途絶していれば
    /// 「Extension は死んでいる」とみなし、フラグを自動修復して false を返す。
    public static func isEffectivelyActive(staleAfter seconds: TimeInterval = 10) -> Bool {
        guard let d = defaults, d.bool(forKey: activeKey) else { return false }
        let last = d.double(forKey: heartbeatKey)
        if Date().timeIntervalSince1970 - last > seconds {
            d.set(false, forKey: activeKey)   // 残留フラグの自動修復
            return false
        }
        return true
    }

    /// Extension が新しい BattleRecord を保存したときの timestamp。
    /// Host 側 polling ループでこの値の変化を検出して履歴を再読込する。
    public static var lastRecordTimestamp: String {
        get { defaults?.string(forKey: lastRecordKey) ?? "" }
        set { defaults?.set(newValue, forKey: lastRecordKey) }
    }
}
