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

    private static var defaults: UserDefaults? {
        UserDefaults(suiteName: suiteName)
    }

    public static var isActive: Bool {
        defaults?.bool(forKey: activeKey) ?? false
    }

    /// Extension が放送開始/終了時に呼ぶ
    public static func setActive(_ value: Bool) {
        defaults?.set(value, forKey: activeKey)
    }
}
