//
//  CaptureNotifier.swift
//  NakamonREC-iOS
//
//  iOS 27 の ScreenCaptureKit 経路 (アプリ本体内キャプチャ) で使うローカル通知。
//  Broadcast Upload Extension からは UserNotifications が使えなかったため iOS 版には無かった機能を、
//  Android (MediaCaptureService) と同じ条件・文言で提供する:
//    1. 状態通知 (Android の前景サービス通知に相当。1 つの ID を上書きし続けるので通知センターには常に最新 1 件):
//       タイトル = 「待機中 (ファイル名)」「戦闘開始 (P1)」「戦闘終了」「🎖 GP記録 2208.1」「🎖 GP戦を検知 (レーティング未取得)」
//       本文 = 「12W - 8L (60.0%)」(アクティブな戦績ファイルの通算)
//    2. REC 放置警告 (別 ID): 最後のバトル確定 (開始時刻を初期値) から 30 分で「REC 中ですよ！」、
//       5 分おきに判定、再警告は 30 分間隔。アクション「停止する」で REC を止める
//

import Foundation
import UIKit
@preconcurrency import UserNotifications   // UNUserNotificationCenter 等は Sendable 注釈なし
import OSLog
import NakamonREC_Shared

nonisolated private let notifierLogger = Logger(subsystem: "com.android.NakamonREC-iOS", category: "CaptureNotifier")

/// エンジンの配信スレッドから呼ばれるため nonisolated。状態は専用シリアルキュー上でのみ触る
nonisolated final class CaptureNotifier: NSObject, CaptureEngineEvents, @unchecked Sendable {

    // Android MediaCaptureService と同じ定数
    static let idleWarningThreshold: TimeInterval = 30 * 60
    static let idleCheckInterval: TimeInterval = 5 * 60

    static let idleCategoryID = "nakamon.rec.idle"
    static let stopActionID = "nakamon.rec.stop"
    private static let idleNotificationID = "nakamon.rec.idle.warning"
    /// 状態通知 (待機中 / 戦闘開始 / 戦闘終了 / GP 記録) は同じ ID で上書きする
    private static let statusNotificationID = "nakamon.rec.status"

    /// 「停止する」アクションが押されたときに呼ぶ (MainActor へ hop して REC 停止)
    private let onStopRequested: @Sendable () -> Void

    private let queue = DispatchQueue(label: "com.android.NakamonREC-iOS.notifier")
    private var idleTimer: DispatchSourceTimer?
    private var lastConfirmedAt: Date = .distantPast
    private var lastWarnedAt: Date = .distantPast

    init(onStopRequested: @escaping @Sendable () -> Void) {
        self.onStopRequested = onStopRequested
        super.init()
    }

    // MARK: - 許可とカテゴリ登録 (REC 開始時にホストから呼ぶ)

    /// 通知許可を求め (初回のみダイアログ)、「停止する」アクション付きカテゴリを登録する。
    /// 許可されなくても REC 自体は続行する (通知が出ないだけ)
    static func requestAuthorizationIfNeeded() {
        let center = UNUserNotificationCenter.current()
        let stop = UNNotificationAction(identifier: stopActionID, title: "停止する", options: [.destructive])
        let category = UNNotificationCategory(identifier: idleCategoryID, actions: [stop], intentIdentifiers: [])
        center.setNotificationCategories([category])
        center.getNotificationSettings { settings in
            guard settings.authorizationStatus == .notDetermined else { return }
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, error in
                notifierLogger.log("notification authorization granted=\(granted) error=\(error?.localizedDescription ?? "-")")
                BattleLogger.append("通知許可: \(granted ? "許可" : "拒否")")
            }
        }
    }

    // MARK: - ライフサイクル (エンジンの start/finish と対で呼ぶ)

    func start() {
        queue.async { [weak self] in
            guard let self else { return }
            self.lastConfirmedAt = Date()   // Android と同じく起動時刻を初期値にして 30 分後から監視
            self.lastWarnedAt = .distantPast
            let timer = DispatchSource.makeTimerSource(queue: self.queue)
            timer.schedule(deadline: .now() + Self.idleCheckInterval, repeating: Self.idleCheckInterval)
            timer.setEventHandler { [weak self] in self?.checkIdle() }
            timer.resume()
            self.idleTimer = timer
            // Android の起動時「待機中 (ファイル名)」
            let file = BattleHistoryStore.shared.activeFileName
            let name = file.hasSuffix(".json") ? String(file.dropLast(5)) : file
            Self.postStatus(title: "待機中 (\(name))")
        }
    }

    func stop() {
        queue.async {
            self.idleTimer?.cancel()
            self.idleTimer = nil
        }
        // 停止時に放置警告と状態通知を消す (Android は前景通知ごと消える)
        let ids = [Self.idleNotificationID, Self.statusNotificationID]
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ids)
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ids)
    }

    /// 状態通知: タイトル = 状態、本文 = アクティブファイルの通算 W-L (Android buildMyNotification と同形)
    private static func postStatus(title: String) {
        let history = BattleHistoryStore.shared.loadActive()
        let win = history.totalWins, lose = history.totalLosses
        let total = win + lose
        let rate = total > 0 ? Double(win) / Double(total) * 100 : 0
        post(id: statusNotificationID, title: title, body: "\(win)W - \(lose)L (\(RateFormat.percent(rate)))", category: nil)
    }

    private func checkIdle() {
        let now = Date()
        guard now.timeIntervalSince(lastConfirmedAt) > Self.idleWarningThreshold,
              now.timeIntervalSince(lastWarnedAt) > Self.idleWarningThreshold else { return }
        lastWarnedAt = now
        let minutes = Int(Self.idleWarningThreshold / 60)
        BattleLogger.append("⚠ REC 放置警告通知を表示 (\(minutes) 分バトル確定なし)")
        Self.post(id: Self.idleNotificationID,
                  title: "REC 中ですよ！",
                  body: "最後のバトル確定から\(minutes)分以上経過しています。",
                  category: Self.idleCategoryID)
    }

    // MARK: - CaptureEngineEvents

    func engineDidStartBattle(partyIndex: Int) {
        let party = partyIndex >= 0 ? "P\(partyIndex + 1)" : "?"
        queue.async { Self.postStatus(title: "戦闘開始 (\(party))") }
    }

    func engineDidFinalizeBattle(result: String) {
        queue.async {
            // 確定があった時点で「最近活動あり」とみなしタイマーをリセット
            self.lastConfirmedAt = Date()
            self.lastWarnedAt = .distantPast
            Self.postStatus(title: "戦闘終了")
        }
    }

    func engineDidRecordGrandPrix(rating: Double) {
        queue.async { Self.postStatus(title: String(format: "🎖 GP記録 %.1f", rating)) }
    }

    func engineDidRecordGrandPrixUnread() {
        queue.async { Self.postStatus(title: "🎖 GP戦を検知 (レーティング未取得)") }
    }

    // MARK: - デバッグメニュー用プレビュー (見た目確認。実際の記録とは無関係)

    /// プレビューは 3 秒後に出す (REC 中でなければ前面表示デリゲートが無いので、ホームに戻って確認してもらう)
    private static let previewDelay: TimeInterval = 3

    static func previewBattleStart() {
        post(id: statusNotificationID + ".preview", title: "戦闘開始 (P1)", body: "12W - 8L (60.0%)",
             category: nil, delay: previewDelay)
    }

    static func previewBattleEnd() {
        post(id: statusNotificationID + ".preview", title: "戦闘終了", body: "13W - 8L (61.9%)",
             category: nil, delay: previewDelay)
    }

    static func previewGrandPrixRecorded() {
        post(id: statusNotificationID + ".preview", title: String(format: "🎖 GP記録 %.1f", 2208.1), body: "13W - 8L (61.9%)",
             category: nil, delay: previewDelay)
    }

    static func previewGrandPrixUnread() {
        post(id: statusNotificationID + ".preview", title: "🎖 GP戦を検知 (レーティング未取得)",
             body: "13W - 8L (61.9%)", category: nil, delay: previewDelay)
    }

    static func previewIdleWarning() {
        let minutes = Int(idleWarningThreshold / 60)
        post(id: idleNotificationID + ".preview", title: "REC 中ですよ！",
             body: "最後のバトル確定から\(minutes)分以上経過しています。", category: idleCategoryID, delay: previewDelay)
    }

    // MARK: - 送信

    private static func post(id: String, title: String, body: String, category: String?, delay: TimeInterval = 0) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        if let category { content.categoryIdentifier = category }
        let trigger = delay > 0 ? UNTimeIntervalNotificationTrigger(timeInterval: delay, repeats: false) : nil
        let request = UNNotificationRequest(identifier: id, content: content, trigger: trigger)
        UNUserNotificationCenter.current().add(request) { error in
            if let error {
                notifierLogger.error("notification add failed: \(error.localizedDescription)")
                BattleLogger.append("通知失敗: \(error.localizedDescription)")
            }
        }
    }
}

// MARK: - UNUserNotificationCenterDelegate (前面表示 + 「停止する」アクション)

extension CaptureNotifier: UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        // アプリが前面でもバナーを出す (REC 中にアプリへ戻っているケース)
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        if response.actionIdentifier == Self.stopActionID {
            BattleLogger.append("通知アクション「停止する」→ REC 停止")
            onStopRequested()
        }
        completionHandler()
    }
}
