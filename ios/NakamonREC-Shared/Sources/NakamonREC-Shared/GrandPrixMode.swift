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
}
