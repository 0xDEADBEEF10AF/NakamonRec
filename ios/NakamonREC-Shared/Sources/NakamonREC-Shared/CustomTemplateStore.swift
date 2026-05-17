import Foundation

/// カスタムテンプレートの種別。校正によって生成され、本番マッチング時に BASE を上書きする。
/// モンスター 127 種にはカスタムテンプレを作らない (Android 仕様と整合)。
public enum CustomTemplateKind: String, CaseIterable, Sendable {
    case select  // SELECT_custom.png (パーティ選択画面)
    case vs      // VS_custom.png    (対戦じゅんび画面)
    case win     // WIN_custom.png   (勝利画面)
    case lose    // LOSE_custom.png  (ざんねん画面)

    /// App Group 内に保存するファイル名
    public var fileName: String {
        switch self {
        case .select: return "SELECT_custom.png"
        case .vs:     return "VS_custom.png"
        case .win:    return "WIN_custom.png"
        case .lose:   return "LOSE_custom.png"
        }
    }
}

/// App Group 配下 `custom_templates/` にカスタムテンプレ PNG を保管する。
public enum CustomTemplateStore {
    private static let appGroupID = "group.com.android.NakamonREC-iOS"
    private static let dirName = "custom_templates"

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

    public static func url(for kind: CustomTemplateKind) -> URL? {
        directory?.appendingPathComponent(kind.fileName)
    }

    public static func exists(_ kind: CustomTemplateKind) -> Bool {
        guard let url = url(for: kind) else { return false }
        return FileManager.default.fileExists(atPath: url.path)
    }

    public static func read(_ kind: CustomTemplateKind) -> Data? {
        guard let url = url(for: kind),
              FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try? Data(contentsOf: url)
    }

    public static func save(_ data: Data, as kind: CustomTemplateKind) {
        guard let url = url(for: kind) else { return }
        try? data.write(to: url, options: .atomic)
    }

    public static func remove(_ kind: CustomTemplateKind) {
        guard let url = url(for: kind),
              FileManager.default.fileExists(atPath: url.path) else { return }
        try? FileManager.default.removeItem(at: url)
    }

    public static func removeAll() {
        for kind in CustomTemplateKind.allCases { remove(kind) }
    }
}
