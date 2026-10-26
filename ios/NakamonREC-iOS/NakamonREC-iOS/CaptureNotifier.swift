//
//  CaptureNotifier.swift
//  NakamonREC-iOS
//
//  iOS 27 の ScreenCaptureKit 経路 (アプリ本体内キャプチャ) で使うローカル通知。
//  Broadcast Upload Extension からは UserNotifications が使えなかったため iOS 版には無かった機能を、
//  Android (MediaCaptureService) と同じ条件・文言で提供する:
//    1. グランプリ記録通知 (A 方針 = 最小): 「🎖 GP記録 2208.1」/ 未取得なら「🎖 GP戦を検知 (レーティング未取得)」。1 戦 1 通知
//    2. REC 放置警告: 最後のバトル確定 (開始時刻を初期値) から 30 分で「REC 中ですよ！」、
//       5 分おきに判定、再警告は 30 分間隔。アクション「停止する」で REC を止める
//  通常戦の確定では通知しない (Android は前景サービス通知の文言更新のみで、iOS に相当物がない)。
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
    private static let grandPrixNotificationID = "nakamon.gp.record"

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
        }
    }

    func stop() {
        queue.async {
            self.idleTimer?.cancel()
            self.idleTimer = nil
        }
        // 停止時に放置警告が残っていれば消す (Android onDestroy と同じ)
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [Self.idleNotificationID])
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [Self.idleNotificationID])
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

    func engineDidFinalizeBattle(result: String) {
        queue.async {
            // 確定があった時点で「最近活動あり」とみなしタイマーをリセット
            self.lastConfirmedAt = Date()
            self.lastWarnedAt = .distantPast
        }
    }

    func engineDidRecordGrandPrix(rating: Double) {
        Self.post(id: Self.grandPrixNotificationID,
                  title: String(format: "🎖 GP記録 %.1f", rating),
                  body: "",
                  category: nil)
    }

    func engineDidRecordGrandPrixUnread() {
        Self.post(id: Self.grandPrixNotificationID,
                  title: "🎖 GP戦を検知 (レーティング未取得)",
                  body: "レーティングを読めませんでした。グランプリ集計から手動で補完できます。",
                  category: nil)
    }

    // MARK: - 送信

    private static func post(id: String, title: String, body: String, category: String?) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        if let category { content.categoryIdentifier = category }
        let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
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
