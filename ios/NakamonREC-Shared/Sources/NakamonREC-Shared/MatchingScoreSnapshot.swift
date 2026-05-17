import Foundation

/// 最新 1 戦のマッチングスコア詳細スナップショット (Android `マッチングスコア詳細画面` 相当)
/// - 画像ファイル群 + メタ JSON を App Group 直下 `matching_score_latest/` に置く
/// - 容量節約のため最新 1 戦ぶんのみ保持。新しい VS 検知時に古い snapshot をクリア
public enum MatchingScoreSnapshot {
    private static let appGroupID = "group.com.android.NakamonREC-iOS"
    private static let dirName = "matching_score_latest"

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

    public static func path(forFile name: String) -> String? {
        directory?.appendingPathComponent(name).path
    }

    public static func url(forFile name: String) -> URL? {
        directory?.appendingPathComponent(name)
    }

    /// 全ファイルを削除 (新しい戦闘開始時に呼ぶ)
    public static func clearAll() {
        guard let dir = directory else { return }
        if let contents = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) {
            for f in contents { try? FileManager.default.removeItem(at: f) }
        }
    }

    /// 戦闘終了時の保存対象 (vs / slots / result) のみクリア。p* (パーティ) は保持
    public static func clearBattleArtifacts() {
        let keep: Set<String> = ["p0.png", "p1.png", "p2.png"]
        guard let dir = directory,
              let contents = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else {
            return
        }
        for f in contents where !keep.contains(f.lastPathComponent) {
            try? FileManager.default.removeItem(at: f)
        }
    }

    // MARK: - Metadata

    /// metadata.json の中身。各 score と最新戦の timestamp を保持
    public struct Metadata: Codable {
        public var battleTimestamp: String   // BattleRecord.timestamp と一致させる
        public var vsScore: Double?
        public var partyScores: [Double]?    // [p0, p1, p2]
        public var myPartyScores: [Double]?  // [0,1,2,3]
        public var enemyPartyScores: [Double]?  // [4,5,6,7]
        public var myPartyNames: [String]?
        public var enemyPartyNames: [String]?
        public var resultLabel: String?      // "WIN" or "LOSE"
        public var resultScore: Double?

        public init(battleTimestamp: String) {
            self.battleTimestamp = battleTimestamp
        }
    }

    private static let metadataFileName = "metadata.json"

    public static func loadMetadata() -> Metadata? {
        guard let url = url(forFile: metadataFileName),
              let data = try? Data(contentsOf: url),
              let meta = try? JSONDecoder().decode(Metadata.self, from: data) else {
            return nil
        }
        return meta
    }

    public static func saveMetadata(_ meta: Metadata) {
        guard let url = url(forFile: metadataFileName) else { return }
        let encoder = JSONEncoder()
        encoder.outputFormatting = .prettyPrinted
        if let data = try? encoder.encode(meta) {
            try? data.write(to: url, options: .atomic)
        }
    }

    public static func updateMetadata(_ mutator: (inout Metadata) -> Void) {
        var meta = loadMetadata() ?? Metadata(battleTimestamp: BattleTimestampFormatter.now())
        mutator(&meta)
        saveMetadata(meta)
    }
}
