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
    /// 複合書き込み (load→変更→save) を直列化するロック。
    /// 戦闘終了の BattleRecord 追記 (解析キュー) と グランプリ記録追記 (キャプチャスレッド) が
    /// 同時に走り得るため、read-modify-write の取りこぼしを防ぐ。
    private let ioLock = NSLock()

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
        ioLock.lock(); defer { ioLock.unlock() }
        var history = loadActive()
        history.records.append(record)
        history.recomputeTotals()
        saveActive(history)
    }

    /// アクティブな履歴をクリア
    public func clearActive() {
        ioLock.lock(); defer { ioLock.unlock() }
        saveActive(BattleHistory())
    }

    // MARK: - Edit helpers

    /// id (timestamp) で 1 件を更新
    public func updateRecord(_ updated: BattleRecord) {
        ioLock.lock(); defer { ioLock.unlock() }
        var history = loadActive()
        if let idx = history.records.firstIndex(where: { $0.id == updated.id }) {
            history.records[idx] = updated
            history.recomputeTotals()
            saveActive(history)
        }
    }

    /// id (timestamp) で 1 件を削除
    public func deleteRecord(id: String) {
        ioLock.lock(); defer { ioLock.unlock() }
        var history = loadActive()
        history.records.removeAll { $0.id == id }
        history.recomputeTotals()
        saveActive(history)
    }

    /// 指定 id の次の位置に新規レコードを挿入
    public func insertRecord(_ new: BattleRecord, afterId: String) {
        ioLock.lock(); defer { ioLock.unlock() }
        var history = loadActive()
        if let idx = history.records.firstIndex(where: { $0.id == afterId }) {
            history.records.insert(new, at: idx + 1)
        } else {
            history.records.append(new)
        }
        history.recomputeTotals()
        saveActive(history)
    }

    // MARK: - Grand Prix records

    /// アクティブなファイルのグランプリ記録一覧 (未記録なら空)
    public func loadGrandPrixRecords() -> [GrandPrixRecord] {
        loadActive().grandPrixRecords ?? []
    }

    /// グランプリ記録を 1 件追記 (通常戦績とは別系列。totals には影響しない)
    public func appendGrandPrix(_ record: GrandPrixRecord) {
        ioLock.lock(); defer { ioLock.unlock() }
        var history = loadActive()
        var gp = history.grandPrixRecords ?? []
        gp.append(record)
        history.grandPrixRecords = gp
        saveActive(history)
    }

    /// id (timestamp) でグランプリ記録を 1 件更新 (手入力での訂正用)
    public func updateGrandPrix(_ updated: GrandPrixRecord) {
        updateGrandPrix(id: updated.id, with: updated)
    }

    /// 旧 id を指定して 1 件更新 (日時 = id 自体を編集するケース用)
    public func updateGrandPrix(id oldId: String, with updated: GrandPrixRecord) {
        ioLock.lock(); defer { ioLock.unlock() }
        var history = loadActive()
        guard var gp = history.grandPrixRecords,
              let idx = gp.firstIndex(where: { $0.id == oldId }) else { return }
        gp[idx] = updated
        history.grandPrixRecords = gp
        saveActive(history)
    }

    /// id (timestamp) でグランプリ記録を 1 件削除
    public func deleteGrandPrix(id: String) {
        ioLock.lock(); defer { ioLock.unlock() }
        var history = loadActive()
        guard var gp = history.grandPrixRecords else { return }
        gp.removeAll { $0.id == id }
        history.grandPrixRecords = gp
        saveActive(history)
    }

    // MARK: - File management

    /// 新しい空の JSON ファイルを作成する。重複名は失敗 (false)
    @discardableResult
    public func createFile(name: String) -> Bool {
        let safe = sanitizeFileName(name)
        guard !safe.isEmpty,
              let url = url(forFileName: safe),
              !fileManager.fileExists(atPath: url.path) else {
            return false
        }
        save(BattleHistory(), fileName: safe)
        return true
    }

    /// 既存ファイルをリネーム。新名重複は失敗 (false)
    @discardableResult
    public func renameFile(from oldName: String, to newName: String) -> Bool {
        let newSafe = sanitizeFileName(newName)
        guard !newSafe.isEmpty,
              newSafe != oldName,
              let src = url(forFileName: oldName),
              let dst = url(forFileName: newSafe),
              fileManager.fileExists(atPath: src.path),
              !fileManager.fileExists(atPath: dst.path) else {
            return false
        }
        do {
            try fileManager.moveItem(at: src, to: dst)
            // アクティブだった場合は追従
            if activeFileName == oldName {
                activeFileName = newSafe
            }
            return true
        } catch {
            NSLog("BattleHistoryStore: rename failed \(oldName) → \(newSafe): \(error)")
            return false
        }
    }

    /// ファイルを削除。最後の 1 件は削除させない (空保護)
    @discardableResult
    public func deleteFile(name: String) -> Bool {
        guard let url = url(forFileName: name),
              fileManager.fileExists(atPath: url.path) else {
            return false
        }
        // 最後の 1 ファイルは消させない
        if availableFiles().count <= 1 {
            return false
        }
        do {
            try fileManager.removeItem(at: url)
            // アクティブだった場合は別のファイルに切替
            if activeFileName == name, let next = availableFiles().first {
                activeFileName = next
            }
            return true
        } catch {
            NSLog("BattleHistoryStore: delete failed \(name): \(error)")
            return false
        }
    }

    /// ファイル名から不正文字を除去し ".json" を保証する
    private func sanitizeFileName(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let illegal: Set<Character> = ["/", "\\", ":", "?", "*", "<", ">", "|", "\""]
        let stripped = String(trimmed.filter { !illegal.contains($0) })
        if stripped.isEmpty { return "" }
        return stripped.hasSuffix(".json") ? stripped : "\(stripped).json"
    }
}
