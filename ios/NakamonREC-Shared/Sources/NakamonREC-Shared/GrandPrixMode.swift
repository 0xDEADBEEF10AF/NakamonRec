import Foundation

/// グランプリ記録モードの ON/OFF (App Group で Host アプリと Extension が共有)。
///
/// B 方針 (2026-08-23): 大会用 VS 画面のカスタムテンプレを校正した状態 = グランプリモード ON。
/// Phase 3 の校正で、インポート画像が大会用 VS と判定されたら true、通常 VS なら false にする。
/// Extension のキャプチャは、この値が true のときだけ WIN/LOSE 後にレーティング画面を待って読む。
/// false (既定=全既存ユーザー) の間はキャプチャ挙動は一切変わらない。
public enum GrandPrixMode {
    private static let appGroupID = "group.com.android.NakamonREC-iOS"
    private static let key = "grandPrixModeEnabled"

    public static var isEnabled: Bool {
        get { UserDefaults(suiteName: appGroupID)?.bool(forKey: key) ?? false }
        set { UserDefaults(suiteName: appGroupID)?.set(newValue, forKey: key) }
    }

    /// レーティングパネル行の学習済み縦オフセット (1080 基準 px、nil = 未学習)。
    /// RatingPanelReader がアンカー探索で見つけた端末固有のずれを保存し、次回以降は探索なしで
    /// 固定バンドをこの量だけずらして読む (ユーザー操作なしの自動校正)。
    /// Android GrandPrixMode.learnedRowOffsetY と対応。
    private static let rowOffsetKey = "grandPrixRowOffsetY"
    public static var learnedRowOffsetY: Int? {
        get {
            guard let d = UserDefaults(suiteName: appGroupID), d.object(forKey: rowOffsetKey) != nil else { return nil }
            return d.integer(forKey: rowOffsetKey)
        }
        set {
            guard let d = UserDefaults(suiteName: appGroupID) else { return }
            if let v = newValue { d.set(v, forKey: rowOffsetKey) } else { d.removeObject(forKey: rowOffsetKey) }
        }
    }
}
