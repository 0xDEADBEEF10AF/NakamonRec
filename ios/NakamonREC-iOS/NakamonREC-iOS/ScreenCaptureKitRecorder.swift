//
//  ScreenCaptureKitRecorder.swift
//  NakamonREC-iOS
//
//  iOS 27 以降の REC 経路。Broadcast Upload Extension (別プロセス) の代わりに、ホストアプリ自身が
//  ScreenCaptureKit の SCStream で画面全体を受け取り、共有の解析エンジン NakamonCaptureEngine を駆動する。
//
//  利点 (Extension 経路との違い):
//    - ローカル通知 / Live Activity が使える (プロセス境界がない)
//    - Extension の ~50MB メモリ上限がない
//    - STOP はシステムシートを経由せず即座に止められる
//  状態共有は従来どおり BroadcastStatus (App Group UserDefaults + ハートビート) を使うので、
//  メイン画面の REC/STOP 表示や戦績の再読込ロジックは経路によらず無変更で動く。
//

import UIKit
import OSLog
import Observation
import NakamonREC_Shared
#if canImport(ScreenCaptureKit)
import ScreenCaptureKit
#endif

#if canImport(ScreenCaptureKit)

nonisolated private let recorderLogger = Logger(subsystem: "com.android.NakamonREC-iOS", category: "SCKRecorder")

@available(iOS 27.0, *)
@MainActor
@Observable
final class ScreenCaptureKitRecorder: NSObject {
    static let shared = ScreenCaptureKitRecorder()

    /// SCStream が動いている (= この経路で REC 中)。Extension 経路の放送中は false のまま
    private(set) var isRunning = false
    /// ピッカー表示中〜startCapture 完了まで true。二重タップ防止
    private(set) var isStarting = false

    @ObservationIgnored private var stream: SCStream?
    @ObservationIgnored private var output: EngineStreamOutput?
    @ObservationIgnored private var isObservingPicker = false
    @ObservationIgnored private var terminateObserver: NSObjectProtocol?

    private override init() {
        super.init()
        // アプリ終了 (スワイプ終了など) 時にレコードを取りこぼさないよう finish を通す
        terminateObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willTerminateNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in self?.output?.engine.finish() }
        }
    }

    // MARK: - 開始 / 停止 (REC ボタンから呼ばれる)

    func start() {
        guard !isRunning, !isStarting else { return }
        isStarting = true
        let picker = SCContentSharingPicker.shared
        picker.defaultConfiguration = SCContentSharingPickerConfiguration()
        if !isObservingPicker {
            picker.add(self)
            isObservingPicker = true
        }
        picker.isActive = true
        recorderLogger.log("REC tapped → present SCContentSharingPicker")
        picker.present()
    }

    func stop() {
        guard let stream, let output else {
            detachPicker()
            isRunning = false
            isStarting = false
            return
        }
        recorderLogger.log("STOP tapped → stopCapture")
        stream.stopCapture { [weak self] error in
            if let error {
                recorderLogger.error("stopCapture error: \(error.localizedDescription)")
            }
            // finish は Extension の broadcastFinished 相当: ハートビート停止・active=false・
            // 読めなかった GP 待機の未取得レコード化・進行中バトル破棄
            output.engine.finish()
            Task { @MainActor [weak self] in
                self?.tearDown()
            }
        }
    }

    // MARK: - 内部

    private func startStream(with filter: SCContentFilter) {
        let scale = CGFloat(filter.pointPixelScale)
        let config = SCStreamConfiguration()
        config.width = Int((filter.contentRect.width * scale).rounded())
        config.height = Int((filter.contentRect.height * scale).rounded())
        config.capturesAudio = false

        let output = EngineStreamOutput()
        let newStream = SCStream(filter: filter, configuration: config, delegate: output)
        do {
            try newStream.addStreamOutput(output, type: .screen, sampleHandlerQueue: output.queue)
        } catch {
            recorderLogger.error("addStreamOutput failed: \(error.localizedDescription)")
            BattleLogger.append("❌ SCK addStreamOutput 失敗: \(error.localizedDescription)")
            tearDown()
            return
        }
        self.stream = newStream
        self.output = output
        let widthPx = config.width
        let heightPx = config.height

        // 順序が重要: エンジンの start() (テンプレ読み込みを含む) を「フレームが届くキュー上で」先に完了させ、
        // そのあとで startCapture する。startCapture 直後からフレームは流れ始めるため、逆順にすると
        // 読み込み途中のテンプレ配列で初回校正・キャッシュが走り、後半のモンスターが認識不能になる
        // (2026-10-26 実機で発生: 読み込み体数が 111/88 と揺れ、Top-K 候補が若い id だけになった)。
        // Extension 経路では ReplayKit が broadcastStarted → processSampleBuffer の順序を保証している。
        output.queue.async {
            // Extension の broadcastStarted 相当 (ログ rotate・active=true・ハートビート・テンプレ読込)
            output.engine.start()
            BattleLogger.append(String(format: "SCK ストリーム開始 要求=%dx%d px (scale %.2f)", widthPx, heightPx, Double(scale)))
            newStream.startCapture { [weak self] error in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    if let error {
                        recorderLogger.error("startCapture failed: \(error.localizedDescription)")
                        BattleLogger.append("❌ SCK startCapture 失敗: \(error.localizedDescription)")
                        output.engine.finish()
                        self.tearDown()
                        return
                    }
                    self.isRunning = true
                    self.isStarting = false
                    recorderLogger.log("SCStream started \(widthPx)x\(heightPx)")
                }
            }
        }
    }

    /// ストリーム・ピッカー・フラグを初期状態へ (engine.finish は呼び出し側で済ませておく)
    private func tearDown() {
        stream = nil
        output = nil
        isRunning = false
        isStarting = false
        detachPicker()
    }

    private func detachPicker() {
        let picker = SCContentSharingPicker.shared
        picker.isActive = false
        if isObservingPicker {
            picker.remove(self)
            isObservingPicker = false
        }
    }

    /// ストリームがシステム側の理由で止まったとき (output から呼ばれる)
    fileprivate func streamDidStop(error: any Error) {
        BattleLogger.append("⚠️ SCK ストリームが停止: \(error.localizedDescription)")
        output?.engine.finish()
        tearDown()
    }
}

// MARK: - SCContentSharingPickerObserver

@available(iOS 27.0, *)
extension ScreenCaptureKitRecorder: SCContentSharingPickerObserver {
    nonisolated func contentSharingPicker(_ picker: SCContentSharingPicker,
                                          didUpdateWith filter: SCContentFilter,
                                          for stream: SCStream?) {
        Task { @MainActor in
            // 既存ストリームへのフィルタ更新 (対象の変更) は扱わない。新規開始のみ
            guard stream == nil, self.stream == nil else { return }
            self.startStream(with: filter)
        }
    }

    nonisolated func contentSharingPicker(_ picker: SCContentSharingPicker, didCancelFor stream: SCStream?) {
        recorderLogger.log("picker cancelled")
        Task { @MainActor in
            self.isStarting = false
            if self.stream == nil { self.detachPicker() }
        }
    }

    nonisolated func contentSharingPickerStartDidFailWithError(_ error: any Error) {
        recorderLogger.error("picker failed: \(error.localizedDescription)")
        BattleLogger.append("❌ SCK ピッカー起動失敗: \(error.localizedDescription)")
        Task { @MainActor in
            self.tearDown()
        }
    }
}

// MARK: - SCStream → エンジン

/// SCStream のサンプルをそのまま解析エンジンへ渡す出力先。サンプルハンドラキュー上で動くので
/// 型ごと nonisolated にしてホストターゲットの既定 MainActor 分離から外す。
@available(iOS 27.0, *)
nonisolated final class EngineStreamOutput: NSObject, SCStreamOutput, SCStreamDelegate, @unchecked Sendable {
    let queue = DispatchQueue(label: "com.android.NakamonREC-iOS.sck.frames", qos: .userInitiated)
    let engine = NakamonCaptureEngine(source: .screenCaptureKit)

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, sampleBuffer.isValid else { return }
        // iOS の SCK は約 40 fps で連続配信する。間引き (0.5 秒) はエンジン側で行う
        engine.process(sampleBuffer)
    }

    func stream(_ stream: SCStream, didStopWithError error: any Error) {
        recorderLogger.error("stream stopped: \(error.localizedDescription)")
        Task { @MainActor in
            ScreenCaptureKitRecorder.shared.streamDidStop(error: error)
        }
    }
}

#endif
