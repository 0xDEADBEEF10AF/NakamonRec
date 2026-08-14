import Foundation

/// 勝率・出現率などのパーセント表示フォーマッタ。
/// %.1f で「100.0%」になる値 (>= 99.95) のときだけ小数点を省いて「100%」にする。
/// 4文字ぶんのフル幅表示が小画面 (iPhone SE3 の戦績カード等) で折り返すための策で、
/// 100% 以外は従来どおり小数第1位まで表示する。
public enum RateFormat {
    public static func percent(_ value: Double) -> String {
        if (value * 10).rounded() >= 1000 { return "100%" }
        return String(format: "%.1f%%", value)
    }
}
