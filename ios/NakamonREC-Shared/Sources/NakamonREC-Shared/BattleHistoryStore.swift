import Foundation

/// 戦績 JSON ファイルの読み書き
/// Android `BattleDataManager` と同じファイル形式 (.json) を扱う
public final class BattleHistoryStore: @unchecked Sendable {
    public static let appGroupID = "group.com.android.NakamonREC-iOS"

    /// 現在アクティブな JSON ファイル名を保持する UserDefaults キー
    private static let activeFileKey = "activeBattleHistoryFile"
    private static let defaultFileName = "default.json"

    public static let shared = BattleHistoryStore()

    private let fileManager = FileManager.default
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    private init() {
        encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        decoder = JSONDecoder()
    }

    // MARK: - Paths

    /// App Group コンテナ内の戦績ディレクトリ (`battle_history/`)
    private var historyDir: URL? {
        guard let container = fileManager
            .containerURL(forSecurityApplicationGroupIdentifier: Self.appGroupID) else {
            return nil
        }
        let dir = container.appendingPathComponent("battle_history", isDirectory: true)
        if !fileManager.fileExists(atPath: dir.path) {
            try? fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    /// 指定ファイル名の URL (App Group 内 battle_history/<name>)
    public func url(forFileName name: String) -> URL? {
        historyDir?.appendingPathComponent(name)
    }

    /// 利用可能な JSON ファイル一覧 (拡張子 .json のみ)
    public func availableFiles() -> [String] {
        guard let dir = historyDir,
              let names = try? fileManager.contentsOfDirectory(atPath: dir.path) else {
            return []
        }
        return names.filter { $0.hasSuffix(".json") }.sorted()
    }

    /// 現在アクティブなファイル名 (存在しなければ default.json を作成して返す)
    public var activeFileName: String {
        get {
            let defaults = UserDefaults(suiteName: Self.appGroupID)
            if let name = defaults?.string(forKey: Self.activeFileKey), !name.isEmpty {
                return name
            }
            return Self.defaultFileName
        }
        set {
            let defaults = UserDefaults(suiteName: Self.appGroupID)
            defaults?.set(newValue, forKey: Self.activeFileKey)
        }
    }

    // MARK: - Read

    /// アクティブな JSON を読み込む (ファイルがなければ空の BattleHistory を返す)
    public func loadActive() -> BattleHistory {
        load(fileName: activeFileName)
    }

    public func load(fileName: String) -> BattleHistory {
        guard let url = url(forFileName: fileName),
              fileManager.fileExists(atPath: url.path),
              let data = try? Data(contentsOf: url) else {
            return BattleHistory()
        }
        if let history = try? decoder.decode(BattleHistory.self, from: data) {
            return history
        }
        return BattleHistory()
    }

    // MARK: - Write

    /// アクティブな JSON に上書き保存
    public func saveActive(_ history: BattleHistory) {
        save(history, fileName: activeFileName)
    }

    public func save(_ history: BattleHistory, fileName: String) {
        guard let url = url(forFileName: fileName) else { return }
        do {
            let data = try encoder.encode(history)
            try data.write(to: url, options: .atomic)
        } catch {
            NSLog("BattleHistoryStore: save failed for \(fileName): \(error)")
        }
    }

    /// アクティブな履歴に 1 件追記し、totals を再計算して保存
    public func append(_ record: BattleRecord) {
        var history = loadActive()
        history.records.append(record)
        history.recomputeTotals()
        saveActive(history)
    }

    /// アクティブな履歴をクリア
    public func clearActive() {
        saveActive(BattleHistory())
    }
}
