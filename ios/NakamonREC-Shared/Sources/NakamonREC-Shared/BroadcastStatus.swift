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

    /// Extension のタイマーが 2 秒おきに呼ぶ生存通知。
    /// 生存時刻に加えて active フラグも毎回 true に再主張する — Host 側が万一
    /// 誤修復 (false 化) しても、Extension が生きている限り 2 秒以内に STOP 表示へ
    /// 復帰する。どちらの方向に誤っても必ず実際の生死に収束させるための対称性。
    public static func beat() {
        defaults?.set(true, forKey: activeKey)
        defaults?.set(Date().timeIntervalSince1970, forKey: heartbeatKey)
    }

    /// Host が REC/STOP ボタン表示に使う実効状態。
    /// active フラグが立っていてもハートビートが staleAfter 秒以上途絶していれば
    /// 「Extension は死んでいる」とみなし、フラグを自動修復して false を返す。
    /// しきい値 30 秒 = ビート 15 回ぶん。配信中の Extension はサスペンドされないため、
    /// 生きているのに 15 連続で途絶することは実質なく、誤って REC 表示へ戻ることはない。
    /// (残留 STOP の自然治癒が 30 秒になるが、旧来は永久残留だったので許容)
    public static func isEffectivelyActive(staleAfter seconds: TimeInterval = 30) -> Bool {
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
