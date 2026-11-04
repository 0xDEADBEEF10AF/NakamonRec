//
//  AutoExportManager.swift
//  NakamonREC-iOS
//
//  自動エクスポート (2026-11-02 ビーフ設計、Android `AutoExportManager.kt` と同仕様)。
//  ON のあいだ、戦績ファイルが保存されるたびに「利用者が選んだフォルダ」へ、選択中のファイル名 (〇〇.json の 〇〇) で
//  〇〇.csv / 〇〇.html を上書きする。利用者がそのフォルダに iCloud Drive や Google ドライブの同期フォルダを選べば、
//  同期はそのアプリが行う。なかモンREC 自身はネットワーク通信を一切行わない (プライバシーポリシー 3 章を維持)。
//
//  iOS 27 以降 (ScreenCaptureKit のホスト内キャプチャ) 専用。REC 中もアプリ本体が動いているので、
//  BattleHistoryStore の保存フックからそのまま書き出せる。iOS 26 の Extension 経路は対象外 (設定項目も出さない)。
//  コントロールセンター長押しで始めた放送 (Extension 経路) も対象外。
//
//  - 格納先はフォルダ選択 (fileImporter / .folder) で得たセキュリティスコープ付き URL をブックマークで保持。
//    利用者が「設定 → プライバシー → ファイルとフォルダ」で許可を取り消せるため、書き込み失敗を前提にし、
//    失敗したら REC を止めずに OFF へ倒して理由を残す (次回メイン画面表示時に通知)。
//  - 連続する保存 (レコード追加 → GP レーティング取得 など) は 1 秒でまとめて 1 回だけ書く。
//

import Foundation
import NakamonREC_Shared

nonisolated enum AutoExportManager {

    enum Format: String, CaseIterable, Identifiable {
        case csv, html, both
        var id: String { rawValue }
        var label: String {
            switch self {
            case .csv:  return "CSV"
            case .html: return "HTML"
            case .both: return "CSV と HTML"
            }
        }
    }

    private static let defaults = UserDefaults.standard
    private static let keyEnabled = "autoExport.enabled"
    private static let keyFormat = "autoExport.format"
    private static let keyBookmark = "autoExport.folderBookmark"
    private static let keyFolderName = "autoExport.folderName"
    private static let keyLastExport = "autoExport.lastExportAt"
    private static let keyFailure = "autoExport.failureMessage"
    /// 連続保存をまとめる待ち時間
    private static let debounceSeconds: TimeInterval = 1.0

    // MARK: - 設定

    static var isEnabled: Bool {
        get { defaults.bool(forKey: keyEnabled) }
        set { defaults.set(newValue, forKey: keyEnabled) }
    }

    static var format: Format {
        get { Format(rawValue: defaults.string(forKey: keyFormat) ?? "") ?? .csv }
        set { defaults.set(newValue.rawValue, forKey: keyFormat) }
    }

    /// 格納先フォルダが設定済みか
    static var hasFolder: Bool { defaults.data(forKey: keyBookmark) != nil }

    /// 格納先フォルダの表示名 (選択時のフォルダ名)
    static var folderDisplayName: String? { defaults.string(forKey: keyFolderName) }

    /// 直近の書き出し時刻 (表示用)
    static var lastExportAt: String? { defaults.string(forKey: keyLastExport) }

    /// 失敗で OFF にしたときのメッセージを 1 回だけ取り出す
    static func consumeFailureMessage() -> String? {
        guard let msg = defaults.string(forKey: keyFailure) else { return nil }
        defaults.removeObject(forKey: keyFailure)
        return msg
    }

    /// フォルダ選択の結果を保存する (セキュリティスコープ付きブックマーク)。保存できたら true
    @discardableResult
    static func setFolder(_ url: URL) -> Bool {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        do {
            let bookmark = try url.bookmarkData(options: .minimalBookmark,
                                                includingResourceValuesForKeys: nil,
                                                relativeTo: nil)
            defaults.set(bookmark, forKey: keyBookmark)
            defaults.set(url.lastPathComponent, forKey: keyFolderName)
            return true
        } catch {
            NSLog("AutoExportManager: bookmark failed: \(error)")
            return false
        }
    }

    /// ブックマークからフォルダ URL を復元する。古くなっていれば作り直して保存し直す
    private static func resolveFolder() -> URL? {
        guard let bookmark = defaults.data(forKey: keyBookmark) else { return nil }
        var isStale = false
        guard let url = try? URL(resolvingBookmarkData: bookmark, bookmarkDataIsStale: &isStale) else {
            return nil
        }
        if isStale {
            let accessed = url.startAccessingSecurityScopedResource()
            if let fresh = try? url.bookmarkData(options: .minimalBookmark,
                                                 includingResourceValuesForKeys: nil,
                                                 relativeTo: nil) {
                defaults.set(fresh, forKey: keyBookmark)
            }
            if accessed { url.stopAccessingSecurityScopedResource() }
        }
        return url
    }

    // MARK: - 書き出し

    /// 書き出しキュー。当初は utility (低優先度) にしていたが、AirPlay ミラーリング (Reflector 4) 中の背景アプリでは
    /// 低優先度の仕事がいつまでも順番を回してもらえず、アプリを前面に出すまで書き出されなかった (配信者報告 2026-11-04)。
    /// 書き出し自体は数十 ms の軽い処理なので、通常操作相当 (userInitiated) で即座に流す
    private static let queue = DispatchQueue(label: "com.android.NakamonREC-iOS.autoexport", qos: .userInitiated)
    private static let pendingLock = NSLock()
    nonisolated(unsafe) private static var pending: DispatchWorkItem?

    /// アプリ起動時に 1 回呼ぶ (iOS 27 以降)。戦績の保存フックに自分を登録する
    static func install() {
        BattleHistoryStore.onSaved = { fileName in
            schedule(fileName: fileName)
        }
    }

    /// 保存のたびに呼ばれる。有効なら debounceSeconds 後に 1 回だけ書き出す (呼び出しスレッドは問わない)
    static func schedule(fileName: String) {
        guard isEnabled else { return }
        pendingLock.lock(); defer { pendingLock.unlock() }
        pending?.cancel()
        let scheduledAt = Date()
        // enforceQoS: 呼び出し元 (解析キュー) の QoS に引きずられず、常に userInitiated で実行する
        let item = DispatchWorkItem(qos: .userInitiated, flags: .enforceQoS) {
            _ = exportNow(fileName: fileName, scheduledAt: scheduledAt)
        }
        pending = item
        queue.asyncAfter(deadline: .now() + debounceSeconds, execute: item)
    }

    /// 今すぐ書き出す (呼び出しスレッドで IO を行う。メインスレッドから呼ばないこと)。
    /// 成功なら true。失敗したら自動エクスポートを OFF にし、理由を飛行記録と設定に残して false。
    /// `scheduledAt` を渡すと、予約から実際に開始するまでの待ち時間を飛行記録に残す (処理待ちか同期遅延かの切り分け用)
    @discardableResult
    static func exportNow(fileName: String, scheduledAt: Date? = nil) -> Bool {
        let startedAt = Date()
        guard let folder = resolveFolder() else {
            fail("格納先フォルダが未設定です")
            return false
        }
        let accessed = folder.startAccessingSecurityScopedResource()
        defer { if accessed { folder.stopAccessingSecurityScopedResource() } }

        let history = BattleHistoryStore.shared.load(fileName: fileName)
        let base = fileName.hasSuffix(".json") ? String(fileName.dropLast(5)) : fileName
        let fmt = format
        do {
            if fmt != .html {
                // 既存の CSV エクスポートと同じ内容 (BOM 付き UTF-8)
                var data = Data([0xEF, 0xBB, 0xBF])
                data.append(CSVSupport.encode(history).data(using: .utf8) ?? Data())
                try write(data, name: "\(base).csv", in: folder)
            }
            if fmt != .csv {
                let html = HtmlExport.render(history: history, baseName: base)
                try write(Data(html.utf8), name: "\(base).html", in: folder)
            }
            defaults.set(BattleTimestampFormatter.now(), forKey: keyLastExport)
            // 待ち = 予約 (保存直後) から開始まで (1 秒のまとめ待ちを含む)。これが数秒以上ならアプリ側の処理待ち、
            // 書き出しが済んでいるのに PC に出ないなら iCloud 側の同期遅延、と切り分けられる
            let waitMs = scheduledAt.map { Int(startedAt.timeIntervalSince($0) * 1000) }
            let writeMs = Int(Date().timeIntervalSince(startedAt) * 1000)
            let waitText = waitMs.map { " 待ち \($0) ms" } ?? ""
            BattleLogger.append("自動エクスポート: \(base) (\(fmt.label))\(waitText) 書き出し \(writeMs) ms")
            return true
        } catch {
            fail(error.localizedDescription)
            return false
        }
    }

    /// フォルダ内の同名ファイルを置き換える (ファイルプロバイダ経由でも正しく上書きされるよう NSFileCoordinator を使う)
    private static func write(_ data: Data, name: String, in folder: URL) throws {
        let dest = folder.appendingPathComponent(name)
        var coordinationError: NSError?
        var writeError: Error?
        NSFileCoordinator().coordinate(writingItemAt: dest, options: .forReplacing, error: &coordinationError) { url in
            do {
                try data.write(to: url, options: .atomic)
            } catch {
                writeError = error
            }
        }
        if let coordinationError { throw coordinationError }
        if let writeError { throw writeError }
    }

    private static func fail(_ message: String) {
        isEnabled = false
        defaults.set(message, forKey: keyFailure)
        BattleLogger.append("⚠️ 自動エクスポート失敗 → OFF にしました: \(message)")
    }
}
