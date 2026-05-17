import Foundation

/// App Group 共有ファイルに 1 戦分のテキストログを記録するフライトレコーダー
/// - Extension (Broadcast Upload) からは `append(_:)` で追記
/// - 新しい戦闘が始まる時は `rotate()` で前回ログをバックアップ後にリセット
/// - ホストアプリは `read()` でログを読み出して表示
public enum BattleLogger {
    private static let appGroupID = "group.com.android.NakamonREC-iOS"
    private static let logFileName = "latest_battle.log"
    private static let prevLogFileName = "previous_battle.log"

    private static let timestampFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        return f
    }()

    private static var containerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupID)
    }

    public static var latestLogURL: URL? {
        containerURL?.appendingPathComponent(logFileName)
    }

    public static var previousLogURL: URL? {
        containerURL?.appendingPathComponent(prevLogFileName)
    }

    /// 1 行追記 (タイムスタンプ自動付与)
    public static func append(_ message: String) {
        guard let url = latestLogURL else { return }
        let timestamp = timestampFormatter.string(from: Date())
        let line = "\(timestamp)  \(message)\n"
        guard let data = line.data(using: .utf8) else { return }

        if FileManager.default.fileExists(atPath: url.path) {
            if let handle = try? FileHandle(forWritingTo: url) {
                defer { try? handle.close() }
                try? handle.seekToEnd()
                try? handle.write(contentsOf: data)
            }
        } else {
            try? data.write(to: url, options: .atomic)
        }
    }

    /// 戦闘開始時に呼ぶ: 直前ログを previous へ退避し、最新ログを空にする
    public static func rotate() {
        guard let latest = latestLogURL, let prev = previousLogURL else { return }
        try? FileManager.default.removeItem(at: prev)
        if FileManager.default.fileExists(atPath: latest.path) {
            try? FileManager.default.moveItem(at: latest, to: prev)
        }
    }

    /// ホストアプリ表示用: 最新ログを読み出す
    public static func readLatest() -> String {
        guard let url = latestLogURL,
              let data = try? Data(contentsOf: url),
              let s = String(data: data, encoding: .utf8) else {
            return "(まだログがありません)"
        }
        return s
    }

    /// ホストアプリ表示用: 前回ログを読み出す
    public static func readPrevious() -> String? {
        guard let url = previousLogURL,
              let data = try? Data(contentsOf: url),
              let s = String(data: data, encoding: .utf8), !s.isEmpty else {
            return nil
        }
        return s
    }

    /// ログを全消去
    public static func clear() {
        if let latest = latestLogURL {
            try? FileManager.default.removeItem(at: latest)
        }
        if let prev = previousLogURL {
            try? FileManager.default.removeItem(at: prev)
        }
    }
}
