import Foundation

/// 校正用にユーザーがインポートしたスクショを保管。App Group `calibration_screenshots/` 下に画面別 PNG。
public enum CalibrationScreenshotStore {
    private static let appGroupID = "group.com.android.NakamonREC-iOS"
    private static let dirName = "calibration_screenshots"

    public static var directory: URL? {
        guard let container = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroupID) else {
            return nil
        }
        let dir = container.appendingPathComponent(dirName, isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    public static func url(for screen: CalibrationScreen) -> URL? {
        directory?.appendingPathComponent("\(screen.rawValue).png")
    }

    public static func exists(_ screen: CalibrationScreen) -> Bool {
        guard let url = url(for: screen) else { return false }
        return FileManager.default.fileExists(atPath: url.path)
    }

    public static func read(_ screen: CalibrationScreen) -> Data? {
        guard let url = url(for: screen),
              FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try? Data(contentsOf: url)
    }

    public static func save(_ data: Data, for screen: CalibrationScreen) {
        guard let url = url(for: screen) else { return }
        try? data.write(to: url, options: .atomic)
    }

    public static func remove(_ screen: CalibrationScreen) {
        guard let url = url(for: screen),
              FileManager.default.fileExists(atPath: url.path) else { return }
        try? FileManager.default.removeItem(at: url)
    }
}
