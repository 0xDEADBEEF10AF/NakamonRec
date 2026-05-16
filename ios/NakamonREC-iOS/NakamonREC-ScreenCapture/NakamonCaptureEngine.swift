import ReplayKit
import VideoToolbox
import OSLog
import UserNotifications

class NakamonCaptureEngine: RPBroadcastSampleHandler {

    private let logger = Logger(subsystem: "com.android.NakamonREC-iOS", category: "CaptureEngine")

    private var lastProcessTime: TimeInterval = 0
    private let processInterval: TimeInterval = 0.5

    // 状態管理
    private var isAnalyzing = false
    private var isBattleInProgress = false

    // テンプレート
    private var vsLogo: UIImage?
    private var winLogo: UIImage?
    private var loseLogo: UIImage?

    private let targetWidth: CGFloat = 1125
    private let targetHeight: CGFloat = 2436

    override func broadcastStarted(withSetupInfo setupInfo: [String : NSObject]?) {
        logger.log("NakamonREC Engine: Starting...")
        loadTemplates()
        sendLocalNotification(title: "NakamonREC 起動", body: "バトルの監視を開始しました。")
    }

    private func loadTemplates() {
        if let path = Bundle.main.path(forResource: "VS_FM", ofType: "png", inDirectory: "templates") {
            vsLogo = UIImage(contentsOfFile: path)
        }
        if let path = Bundle.main.path(forResource: "WIN", ofType: "png", inDirectory: "templates") {
            winLogo = UIImage(contentsOfFile: path)
        }
        if let path = Bundle.main.path(forResource: "LOSE", ofType: "png", inDirectory: "templates") {
            loseLogo = UIImage(contentsOfFile: path)
        }
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleType: RPSampleBufferType) {
        if sampleType == .video {
            handleVideoSample(sampleBuffer)
        }
    }

    private func handleVideoSample(_ sampleBuffer: CMSampleBuffer) {
        let currentTime = CACurrentMediaTime()
        guard currentTime - lastProcessTime >= processInterval else { return }
        lastProcessTime = currentTime

        guard let uiImage = sampleBufferToUIImage(sampleBuffer) else { return }
        let targetScene = resizeImage(uiImage, targetSize: CGSize(width: targetWidth, height: targetHeight))

        if !isBattleInProgress {
            // --- 戦闘開始の監視 (VSロゴ) ---
            if let vs = vsLogo {
                let score = NakamonWrapper.performMatch(withScene: targetScene,
                                                      templateImg: vs,
                                                      centerX: Int32(targetWidth * 0.5),
                                                      centerY: Int32(targetHeight * 0.23),
                                                      verticalMargin: 120,
                                                      horizontalMargin: 120)

                if score > 0.85 {
                    isBattleInProgress = true
                    logger.log("✅ Battle Started!")
                    sendLocalNotification(title: "バトル開始！", body: "対戦相手を検知しました。解析中...")
                }
            }
        } else {
            // --- 戦闘終了の監視 (WIN/LOSEロゴ) ---
            checkBattleEnd(targetScene)
        }
    }

    private func checkBattleEnd(_ scene: UIImage) {
        // WIN判定
        if let win = winLogo {
            let score = NakamonWrapper.performMatch(withScene: scene,
                                                  templateImg: win,
                                                  centerX: Int32(targetWidth * 0.5),
                                                  centerY: Int32(targetHeight * 0.25),
                                                  verticalMargin: 150,
                                                  horizontalMargin: 150)
            if score > 0.8 {
                isBattleInProgress = false
                logger.log("🏆 Battle Won!")
                sendLocalNotification(title: "バトル終了", body: "勝利しました！")
                return
            }
        }

        // LOSE判定
        if let lose = loseLogo {
            let score = NakamonWrapper.performMatch(withScene: scene,
                                                  templateImg: lose,
                                                  centerX: Int32(targetWidth * 0.5),
                                                  centerY: Int32(targetHeight * 0.25),
                                                  verticalMargin: 150,
                                                  horizontalMargin: 150)
            if score > 0.8 {
                isBattleInProgress = false
                logger.log("💀 Battle Lost...")
                sendLocalNotification(title: "バトル終了", body: "敗北しました。")
            }
        }
    }

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

    private func resizeImage(_ image: UIImage, targetSize: CGSize) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
    }
}
