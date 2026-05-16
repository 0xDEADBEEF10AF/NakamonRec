import ReplayKit
import VideoToolbox
import OSLog
import UserNotifications

class NakamonCaptureEngine: RPBroadcastSampleHandler {

    private let logger = Logger(subsystem: "com.android.NakamonREC-iOS", category: "CaptureEngine")

    private var lastProcessTime: TimeInterval = 0
    private let processInterval: TimeInterval = 0.5

    // バースト解析用
    private var isAnalyzing = false
    private let burstCount = 5
    private var currentBurstImages: [UIImage] = []
    private let analysisQueue = DispatchQueue(label: "com.android.NakamonREC-iOS.analysis", qos: .userInitiated)

    // 戦闘状態
    private var isBattleInProgress = false

    // テンプレートキャッシュ
    private var vsLogo: UIImage?
    private var winLogo: UIImage?
    private var loseLogo: UIImage?
    private var monsterTemplates: [UIImage] = []

    private var didCalibrate = false

    // テンプレ作成時のスクリーン基準幅 (Pixel 10 Pro: 1080)
    private let templateReferenceWidth: CGFloat = 1080

    override func broadcastStarted(withSetupInfo setupInfo: [String : NSObject]?) {
        logger.log("NakamonREC Engine: Starting...")
        loadTemplates()
        sendLocalNotification(title: "NakamonREC 起動", body: "バトルの監視を開始しました。")
    }

    private func loadTemplates() {
        if let path = Bundle.main.path(forResource: "VS_FM", ofType: "png", inDirectory: "templates") {
            vsLogo = UIImage(contentsOfFile: path)
            logger.log("✅ VS_FM.png loaded")
        } else {
            logger.error("❌ VS_FM.png NOT FOUND in Extension bundle")
        }
        if let path = Bundle.main.path(forResource: "WIN", ofType: "png", inDirectory: "templates"),
           let img = UIImage(contentsOfFile: path) {
            winLogo = img
            logger.log("✅ WIN.png loaded")
        } else {
            logger.error("❌ WIN.png NOT FOUND")
        }
        if let path = Bundle.main.path(forResource: "LOSE", ofType: "png", inDirectory: "templates"),
           let img = UIImage(contentsOfFile: path) {
            loseLogo = img
            logger.log("✅ LOSE.png loaded")
        } else {
            logger.error("❌ LOSE.png NOT FOUND")
        }
        for i in 1...30 {
            let name = String(format: "id%03d", i)
            if let path = Bundle.main.path(forResource: name, ofType: "png", inDirectory: "templates"),
               let img = UIImage(contentsOfFile: path) {
                monsterTemplates.append(img)
            }
        }
        logger.log("Loaded \(self.monsterTemplates.count) monster templates")
    }

    /// 初回フレーム到着時、フレーム幅に合わせて全テンプレートを1回だけリサイズする
    private func calibrateTemplates(forFrameWidth frameWidth: CGFloat) {
        let scale = frameWidth / templateReferenceWidth
        logger.log("Calibrating templates: frameWidth=\(Int(frameWidth)), scale=\(scale, format: .fixed(precision: 3))")

        vsLogo = vsLogo.map { resizeImage($0, scale: scale) }
        winLogo = winLogo.map { resizeImage($0, scale: scale) }
        loseLogo = loseLogo.map { resizeImage($0, scale: scale) }
        monsterTemplates = monsterTemplates.map { resizeImage($0, scale: scale) }
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleType: RPSampleBufferType) {
        if sampleType == .video {
            handleVideoSample(sampleBuffer)
        }
    }

    private func handleVideoSample(_ sampleBuffer: CMSampleBuffer) {
        let currentTime = CACurrentMediaTime()

        // バースト撮影中: モンスター解析のために画像蓄積
        if isAnalyzing {
            if currentBurstImages.count < burstCount {
                if let uiImage = sampleBufferToUIImage(sampleBuffer) {
                    currentBurstImages.append(uiImage)
                }
                if currentBurstImages.count == burstCount {
                    // 5枚揃った時点で解析を非同期へ逃がし、即座に WIN/LOSE 監視へ復帰
                    let snapshot = currentBurstImages
                    currentBurstImages.removeAll()
                    isAnalyzing = false
                    analysisQueue.async { [weak self] in
                        self?.performDeepAnalysis(frames: snapshot)
                    }
                }
            }
            return
        }

        // インターバル制限
        guard currentTime - lastProcessTime >= processInterval else { return }
        lastProcessTime = currentTime

        guard let uiImage = sampleBufferToUIImage(sampleBuffer) else { return }

        // 初回のみ、フレーム幅に合わせて全テンプレートをリサイズ
        if !didCalibrate {
            didCalibrate = true
            logger.log("Frame size: \(Int(uiImage.size.width))x\(Int(uiImage.size.height))")
            calibrateTemplates(forFrameWidth: uiImage.size.width)
        }

        if !isBattleInProgress {
            scanForVS(uiImage)
        } else {
            checkBattleEnd(uiImage)
        }
    }

    // MARK: - VS Logo (戦闘開始)

    private func scanForVS(_ scene: UIImage) {
        guard let vs = vsLogo else { return }
        let score = NakamonWrapper.performMatch(withScene: scene,
                                              templateImg: vs,
                                              centerX: Int32(scene.size.width * 0.5),
                                              centerY: Int32(scene.size.height * 0.23),
                                              verticalMargin: 500,
                                              horizontalMargin: 200)
        if score > 0.4 {
            logger.log("✅ VS Logo Found! (Score: \(score, format: .fixed(precision: 3))). Starting burst...")
            isBattleInProgress = true
            isAnalyzing = true
            currentBurstImages.removeAll()
            currentBurstImages.append(scene)
            sendLocalNotification(title: "バトル開始！", body: "対戦相手を検知しました。解析中...")
        } else if score > 0.1 {
            logger.log("🔎 Scanning... VS Score: \(score, format: .fixed(precision: 3))")
        }
    }

    // MARK: - Monster Identification (バースト解析)

    /// バックグラウンドキューで実行される重い解析処理
    private func performDeepAnalysis(frames: [UIImage]) {
        logger.log("👾 Performing Deep Analysis on \(frames.count) frames...")
        let templates = monsterTemplates // calibrate 後は不変なのでスレッド間で安全に参照可

        var maxScore: Double = 0
        for frame in frames {
            let score = NakamonWrapper.findBestMonsterMatch(frame, templates: templates)
            if score > maxScore {
                maxScore = score
            }
        }

        if maxScore > 0.7 {
            logger.log("✅ Monster identified! Score: \(maxScore, format: .fixed(precision: 3))")
        } else {
            logger.log("❓ Monster unclear. Best Score: \(maxScore, format: .fixed(precision: 3))")
        }
    }

    // MARK: - WIN / LOSE (戦闘終了)

    private func checkBattleEnd(_ scene: UIImage) {
        if let win = winLogo {
            let score = NakamonWrapper.performMatch(withScene: scene,
                                                  templateImg: win,
                                                  centerX: Int32(scene.size.width * 0.5),
                                                  centerY: Int32(scene.size.height * 0.25),
                                                  verticalMargin: 500,
                                                  horizontalMargin: 200)
            if score > 0.4 {
                logger.log("🏆 Battle Won! (Score: \(score, format: .fixed(precision: 3)))")
                isBattleInProgress = false
                sendLocalNotification(title: "バトル終了", body: "勝利しました！")
                return
            }
        }

        if let lose = loseLogo {
            let score = NakamonWrapper.performMatch(withScene: scene,
                                                  templateImg: lose,
                                                  centerX: Int32(scene.size.width * 0.5),
                                                  centerY: Int32(scene.size.height * 0.25),
                                                  verticalMargin: 500,
                                                  horizontalMargin: 200)
            if score > 0.4 {
                logger.log("💀 Battle Lost... (Score: \(score, format: .fixed(precision: 3)))")
                isBattleInProgress = false
                sendLocalNotification(title: "バトル終了", body: "敗北しました。")
            }
        }
    }

    // MARK: - Helpers

    private func sendLocalNotification(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default

        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                self.logger.error("Failed to send notification: \(error.localizedDescription)")
            }
        }
    }

    private func sampleBufferToUIImage(_ sampleBuffer: CMSampleBuffer) -> UIImage? {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return nil }
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let context = CIContext()
        guard let cgImage = context.createCGImage(ciImage, from: ciImage.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }

    private func resizeImage(_ image: UIImage, scale: CGFloat) -> UIImage {
        let newSize = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        return resizeImage(image, targetSize: newSize)
    }

    private func resizeImage(_ image: UIImage, targetSize: CGSize) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
    }

    override func broadcastPaused() {
        logger.log("NakamonREC: Broadcast Paused")
    }

    override func broadcastResumed() {
        logger.log("NakamonREC: Broadcast Resumed")
    }

    override func broadcastFinished() {
        logger.log("NakamonREC: Broadcast Finished")
    }
}
