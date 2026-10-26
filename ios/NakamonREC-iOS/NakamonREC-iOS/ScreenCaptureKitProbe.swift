//
//  ScreenCaptureKitProbe.swift
//  NakamonREC-iOS
//
//  iOS 27 の ScreenCaptureKit を使った「アプリ本体プロセス内キャプチャ」の検証プロトタイプ。
//  デバッグメニューからのみ起動する。本番の解析エンジンにはまだ接続せず、以下だけを確認する:
//    1. システムピッカーで「画面全体」を選び、SCStream からフレームが届くか
//    2. 届くフレームのピクセルサイズ (校正値に直結。ReplayKit と同じフル解像度か)
//    3. アプリをバックグラウンドにして別アプリ (DQ ウォーク) を前面にしてもフレームが届き続けるか
//    4. その状態でローカル通知 (UNUserNotificationCenter) が出るか (Extension では不可能だった点)
//  ログはフライトレコーダー (BattleLogger) に書くので、デバッグメニュー「直近の解析ログ」で読める。
//

import Foundation
import UIKit
import OSLog
import Observation
import UserNotifications
import NakamonREC_Shared
#if canImport(ScreenCaptureKit)
import ScreenCaptureKit
#endif

#if canImport(ScreenCaptureKit)

nonisolated private let probeLogger = Logger(subsystem: "com.android.NakamonREC-iOS", category: "SCKProbe")

/// 検証用の通知 ID (同じ ID で上書きし、通知センターに溜めない)
nonisolated private enum ProbeNotificationID {
    static let started = "sck.probe.started"
    static let background = "sck.probe.background"
    static let stopped = "sck.probe.stopped"
    static let error = "sck.probe.error"
}

/// ピッカー監視・ストリーム制御・UI 状態を持つ本体 (MainActor)。
/// Combine は使わず Observation (@Observable) で SwiftUI に状態を流す。
@available(iOS 27.0, *)
@MainActor
@Observable
final class ScreenCaptureKitProbe: NSObject {
    static let shared = ScreenCaptureKitProbe()

    private(set) var isRunning = false
    private(set) var statusText = "未開始"
    private(set) var frameSummary = ""

    @ObservationIgnored private var stream: SCStream?
    @ObservationIgnored private var output: ProbeStreamOutput?
    @ObservationIgnored private var isObservingPicker = false

    private override init() {
        super.init()
    }

    // MARK: - 開始 / 停止

    func start() {
        guard !isRunning else { return }
        BattleLogger.rotate()
        BattleLogger.append("SCK検証: 開始 (iOS \(UIDevice.current.systemVersion))")
        statusText = "通知許可を確認中…"

        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, error in
            let msg = "SCK検証: 通知許可 granted=\(granted)" + (error.map { " error=\($0.localizedDescription)" } ?? "")
            BattleLogger.append(msg)
            probeLogger.log("\(msg)")
            Task { @MainActor in
                self.presentPicker()
            }
        }
    }

    func stop() {
        guard isRunning || stream != nil else {
            detachPicker()
            statusText = "未開始"
            return
        }
        statusText = "停止中…"
        let summary = output?.summaryLine() ?? ""
        stream?.stopCapture { [weak self] error in
            let line = "SCK検証: 停止 " + summary + (error.map { " error=\($0.localizedDescription)" } ?? "")
            BattleLogger.append(line)
            probeLogger.log("\(line)")
            Self.postNotification(id: ProbeNotificationID.stopped,
                                  title: "キャプチャ停止 (ScreenCaptureKit)",
                                  body: summary.isEmpty ? "フレームなし" : summary)
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.stream = nil
                self.output = nil
                self.isRunning = false
                self.statusText = "停止 " + summary
                self.detachPicker()
            }
        }
    }

    // MARK: - ピッカー

    private func presentPicker() {
        let picker = SCContentSharingPicker.shared
        // iOS では allowsChangingSelectedContent 等の macOS 向け設定は使えない (unavailable)。既定のまま使う
        picker.defaultConfiguration = SCContentSharingPickerConfiguration()
        if !isObservingPicker {
            picker.add(self)
            isObservingPicker = true
        }
        picker.isActive = true
        statusText = "ピッカー表示中 (「画面全体」を選んでください)"
        BattleLogger.append("SCK検証: ピッカー表示")
        picker.present()
    }

    private func detachPicker() {
        let picker = SCContentSharingPicker.shared
        picker.isActive = false
        if isObservingPicker {
            picker.remove(self)
            isObservingPicker = false
        }
    }

    // MARK: - ストリーム

    private func startStream(with filter: SCContentFilter) {
        let rect = filter.contentRect
        let scale = filter.pointPixelScale
        let widthPx = Int((rect.width * CGFloat(scale)).rounded())
        let heightPx = Int((rect.height * CGFloat(scale)).rounded())
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let native = scene?.screen.nativeBounds ?? .zero
        let screenPx = "\(Int(native.width))x\(Int(native.height))"
        BattleLogger.append(String(format: "SCK検証: filter rect=%.0fx%.0f pt scale=%.2f → %dx%d px / 画面 native=%@ style=%d",
                                   rect.width, rect.height, Double(scale), widthPx, heightPx, screenPx, filter.style.rawValue))

        // iOS では pixelFormat / minimumFrameInterval / queueDepth は unavailable。
        // フレーム間引きは受信側で行う (本番エンジンは元々 0.5 秒間隔で間引いている)。
        // 実際に届く px サイズと形式はログで確認する。
        let config = SCStreamConfiguration()
        config.width = widthPx
        config.height = heightPx
        config.capturesAudio = false

        let output = ProbeStreamOutput(onStatus: { [weak self] text in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.frameSummary = text
            }
        })
        let newStream = SCStream(filter: filter, configuration: config, delegate: output)
        do {
            try newStream.addStreamOutput(output, type: .screen, sampleHandlerQueue: output.queue)
        } catch {
            fail("addStreamOutput 失敗: \(error.localizedDescription)")
            return
        }
        self.stream = newStream
        self.output = output
        statusText = "ストリーム開始中…"
        newStream.startCapture { [weak self] error in
            Task { @MainActor [weak self] in
                guard let self else { return }
                if let error {
                    self.fail("startCapture 失敗: \(error.localizedDescription)")
                    return
                }
                self.isRunning = true
                self.statusText = "キャプチャ中 (\(widthPx)x\(heightPx))。ホームに戻って他アプリを開いてください"
                BattleLogger.append("SCK検証: startCapture 成功")
                Self.postNotification(id: ProbeNotificationID.started,
                                      title: "キャプチャ開始 (ScreenCaptureKit)",
                                      body: "\(widthPx)x\(heightPx) px。アプリ本体プロセスから送信した通知です")
            }
        }
    }

    private func fail(_ message: String) {
        BattleLogger.append("SCK検証: ❌ " + message)
        probeLogger.error("\(message)")
        statusText = "失敗: " + message
        isRunning = false
        stream = nil
        output = nil
        detachPicker()
        Self.postNotification(id: ProbeNotificationID.error, title: "キャプチャ失敗", body: message)
    }

    // MARK: - 通知

    nonisolated static func postNotification(id: String, title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if let error {
                BattleLogger.append("SCK検証: 通知失敗 \(id): \(error.localizedDescription)")
            } else {
                BattleLogger.append("SCK検証: 通知送信 \(id)")
            }
        }
    }
}

// MARK: - SCContentSharingPickerObserver

@available(iOS 27.0, *)
extension ScreenCaptureKitProbe: SCContentSharingPickerObserver {
    nonisolated func contentSharingPicker(_ picker: SCContentSharingPicker,
                                          didUpdateWith filter: SCContentFilter,
                                          for stream: SCStream?) {
        BattleLogger.append("SCK検証: ピッカー選択完了 (既存stream=\(stream == nil ? "なし" : "あり"))")
        Task { @MainActor in
            if stream == nil {
                self.startStream(with: filter)
            } else {
                // 既存ストリームの対象変更 (allowsChangingSelectedContent=false なので通常来ない)
                BattleLogger.append("SCK検証: 既存ストリームのフィルタ更新は無視")
            }
        }
    }

    nonisolated func contentSharingPicker(_ picker: SCContentSharingPicker, didCancelFor stream: SCStream?) {
        BattleLogger.append("SCK検証: ピッカーがキャンセルされました")
        Task { @MainActor in
            self.statusText = "キャンセル"
            self.detachPicker()
        }
    }

    nonisolated func contentSharingPickerStartDidFailWithError(_ error: any Error) {
        BattleLogger.append("SCK検証: ❌ ピッカー起動失敗 \(error.localizedDescription)")
        Task { @MainActor in
            self.fail("ピッカー起動失敗: \(error.localizedDescription)")
        }
    }
}

// MARK: - UNUserNotificationCenterDelegate (前面でもバナー表示)

@available(iOS 27.0, *)
extension ScreenCaptureKitProbe: UNUserNotificationCenterDelegate {
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }
}

// MARK: - ストリーム出力 (バックグラウンドキューで受ける)

/// フレーム受信カウンタ。SCStream のサンプルハンドラキュー上で動くので、
/// 型ごと nonisolated にしてプロジェクト既定の MainActor 分離から外す (状態は queue 上でのみ触る)。
@available(iOS 27.0, *)
nonisolated final class ProbeStreamOutput: NSObject, SCStreamOutput, SCStreamDelegate, @unchecked Sendable {
    nonisolated let queue = DispatchQueue(label: "com.android.NakamonREC-iOS.sck.probe")

    private let onStatus: @Sendable (String) -> Void
    // 以下は queue 上でのみ触る
    private var frameCount = 0
    private var idleCount = 0
    private var backgroundFrameCount = 0
    private var startTime: CFTimeInterval = 0
    private var lastReportTime: CFTimeInterval = 0
    private var lastSize = ""
    private var backgroundNotified = false
    // アプリ状態は通知で更新 (メインスレッド) し、queue から読む → ロックで保護
    private let stateLock = NSLock()
    private var _inBackground = false
    private var inBackground: Bool {
        get { stateLock.lock(); defer { stateLock.unlock() }; return _inBackground }
        set { stateLock.lock(); _inBackground = newValue; stateLock.unlock() }
    }
    private var observers: [NSObjectProtocol] = []

    init(onStatus: @escaping @Sendable (String) -> Void) {
        self.onStatus = onStatus
        super.init()
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.inBackground = true
            BattleLogger.append("SCK検証: アプリがバックグラウンドへ")
        })
        observers.append(nc.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.inBackground = false
            BattleLogger.append("SCK検証: アプリが前面へ復帰")
        })
    }

    deinit {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    /// 停止時の要約 1 行 (queue 外から呼ばれるので sync で読む)
    nonisolated func summaryLine() -> String {
        queue.sync {
            let elapsed = startTime > 0 ? CACurrentMediaTime() - startTime : 0
            return String(format: "frames=%d (bg=%d, idle=%d) size=%@ %.0f秒", frameCount, backgroundFrameCount, idleCount, lastSize, elapsed)
        }
    }

    // MARK: SCStreamOutput

    nonisolated func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, sampleBuffer.isValid else { return }
        let now = CACurrentMediaTime()
        if startTime == 0 { startTime = now; lastReportTime = now }

        // フレーム状態 (idle = 画面変化なしの通知のみ、画像は再利用)
        var status: SCFrameStatus = .complete
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
           let raw = attachments.first?[.status] as? Int,
           let s = SCFrameStatus(rawValue: raw) {
            status = s
        }
        guard status == .complete || status == .started else {
            idleCount += 1
            return
        }

        frameCount += 1
        let bg = inBackground
        if bg { backgroundFrameCount += 1 }

        if let pixelBuffer = sampleBuffer.imageBuffer {
            let w = CVPixelBufferGetWidth(pixelBuffer)
            let h = CVPixelBufferGetHeight(pixelBuffer)
            let size = "\(w)x\(h)"
            if size != lastSize {
                lastSize = size
                let fmt = CVPixelBufferGetPixelFormatType(pixelBuffer)
                BattleLogger.append("SCK検証: フレームサイズ \(size) px format=\(fourCC(fmt)) (frame #\(frameCount))")
            }
        }

        // バックグラウンドで 5 フレーム届いた時点で 1 回だけ通知 (Extension では出せなかった通知のデモ)
        if bg && backgroundFrameCount == 5 && !backgroundNotified {
            backgroundNotified = true
            BattleLogger.append("SCK検証: ✅ バックグラウンドでフレーム受信継続 (5 枚目)")
            ScreenCaptureKitProbe.postNotification(id: ProbeNotificationID.background,
                                                   title: "バックグラウンドでキャプチャ継続中",
                                                   body: "\(lastSize) px のフレームを受信しています。この通知はアプリ本体から送信されました")
        }

        // 10 秒ごとに統計を 1 行
        if now - lastReportTime >= 10 {
            let fps = Double(frameCount) / max(now - startTime, 0.001)
            let line = String(format: "SCK検証: frames=%d bg=%d idle=%d fps平均=%.1f state=%@", frameCount, backgroundFrameCount, idleCount, fps, bg ? "bg" : "fg")
            BattleLogger.append(line)
            lastReportTime = now
            onStatus(String(format: "受信 %d 枚 (bg %d) %@ %.1f fps", frameCount, backgroundFrameCount, lastSize, fps))
        } else if frameCount == 1 {
            onStatus("最初のフレーム受信 \(lastSize)")
        }
    }

    // MARK: SCStreamDelegate

    nonisolated func stream(_ stream: SCStream, didStopWithError error: any Error) {
        let line = "SCK検証: ❌ ストリームが停止 error=\(error.localizedDescription)"
        BattleLogger.append(line)
        probeLogger.error("\(line)")
        ScreenCaptureKitProbe.postNotification(id: ProbeNotificationID.error,
                                               title: "キャプチャが停止しました",
                                               body: error.localizedDescription)
        onStatus("ストリーム停止: \(error.localizedDescription)")
    }

    private func fourCC(_ v: OSType) -> String {
        let bytes = [UInt8((v >> 24) & 0xff), UInt8((v >> 16) & 0xff), UInt8((v >> 8) & 0xff), UInt8(v & 0xff)]
        return String(bytes: bytes, encoding: .ascii) ?? String(v)
    }
}

#endif
