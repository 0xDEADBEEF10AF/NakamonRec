import Foundation

/// App Store 上の最新バージョン情報 (iTunes Lookup API 由来)
public struct AppStoreUpdateInfo: Sendable {
    public let version: String
    public let storeURL: URL

    public init(version: String, storeURL: URL) {
        self.version = version
        self.storeURL = storeURL
    }
}

/// App Store の最新バージョンを iTunes Lookup API で取得する更新チェッカー。
/// GitHub Releases チェックはストア一本化 (2026-08-15, 案B) で廃止した。
/// Android 側は Play In-App Update API で同等の機能を提供する。
public enum AppStoreUpdateChecker {
    /// 最新バージョンとストアページ URL を取得する。
    /// 注意: Lookup API にはキャッシュがあり、審査承認直後は反映まで最大1日程度
    /// 遅れることがある (更新通知用途では許容)。失敗時は nil。
    public static func fetch() async -> AppStoreUpdateInfo? {
        guard let bundleID = Bundle.main.bundleIdentifier,
              let url = URL(string: "https://itunes.apple.com/lookup?bundleId=\(bundleID)&country=jp") else {
            return nil
        }
        guard let (data, _) = try? await URLSession.shared.data(from: url),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let results = obj["results"] as? [[String: Any]],
              let first = results.first,
              let version = first["version"] as? String,
              let track = first["trackViewUrl"] as? String,
              let storeURL = URL(string: track) else {
            return nil
        }
        return AppStoreUpdateInfo(version: version, storeURL: storeURL)
    }

    /// "26.8.2" 形式の数値バージョン比較 (Android MainActivity.isNewerVersion と同一規則)
    public static func isNewer(_ latest: String, than current: String) -> Bool {
        let l = latest.split(separator: ".").map { Int($0) ?? 0 }
        let c = current.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(l.count, c.count) {
            let a = i < l.count ? l[i] : 0
            let b = i < c.count ? c[i] : 0
            if a != b { return a > b }
        }
        return false
    }
}
