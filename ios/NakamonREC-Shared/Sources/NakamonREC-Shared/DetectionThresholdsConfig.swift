import Foundation

/// VS / WIN / LOSE 検知の閾値ユーザー設定。App Group UserDefaults に保存し、Host / Extension で共有する。
/// 範囲は 0.4〜0.8 に制限。範囲外を coerceIn して安全な値だけが Extension 側に渡る。
/// 値の選び方:
/// - 上げると誤検知が減るが、本来の検知も逃しやすくなる
/// - 下げると検知が早くなるが、画面遷移中などで誤検知のリスクが上がる
public enum DetectionThresholdsConfig {
    private static let suiteName = "group.com.android.NakamonREC-iOS"

    private static let vsKey = "detectionThreshold.vs"
    private static let winKey = "detectionThreshold.win"
    private static let loseKey = "detectionThreshold.lose"

    public static let defaultVS: Double = 0.5
    public static let defaultWin: Double = 0.4
    public static let defaultLose: Double = 0.4
    public static let minimum: Double = 0.4
    public static let maximum: Double = 0.8

    private static var defaults: UserDefaults? {
        UserDefaults(suiteName: suiteName)
    }

    public static var vsThreshold: Double {
        get { read(key: vsKey, fallback: defaultVS) }
        set { write(key: vsKey, value: newValue) }
    }

    public static var winThreshold: Double {
        get { read(key: winKey, fallback: defaultWin) }
        set { write(key: winKey, value: newValue) }
    }

    public static var loseThreshold: Double {
        get { read(key: loseKey, fallback: defaultLose) }
        set { write(key: loseKey, value: newValue) }
    }

    /// デフォルトと異なるカスタム値が 1 つでも設定されているか
    public static var isCustomized: Bool {
        vsThreshold != defaultVS || winThreshold != defaultWin || loseThreshold != defaultLose
    }

    public static func resetToDefaults() {
        vsThreshold = defaultVS
        winThreshold = defaultWin
        loseThreshold = defaultLose
    }

    private static func read(key: String, fallback: Double) -> Double {
        guard let d = defaults, d.object(forKey: key) != nil else { return fallback }
        let raw = d.double(forKey: key)
        if raw <= 0 { return fallback }
        return min(maximum, max(minimum, raw))
    }

    private static func write(key: String, value: Double) {
        let clamped = min(maximum, max(minimum, value))
        defaults?.set(clamped, forKey: key)
    }
}
