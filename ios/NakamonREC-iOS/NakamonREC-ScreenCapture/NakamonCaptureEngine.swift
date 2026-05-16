import ReplayKit
import VideoToolbox
import OSLog

class NakamonCaptureEngine: RPBroadcastSampleHandler {

    private let logger = Logger(subsystem: "com.android.NakamonREC-iOS", category: "CaptureEngine")

    private var lastProcessTime: TimeInterval = 0
    private let processInterval: TimeInterval = 0.5

    // バースト解析用
    private var isAnalyzing = false
    private let burstCount = 5
    private var currentBurstImages: [UIImage] = []

    // テンプレートキャッシュ
    private var vsLogo: UIImage?
    private var monsterTemplates: [UIImage] = []

    private var didCalibrate = false

    // テンプレ作成時のスクリーン基準幅 (Pixel 10 Pro: 1080)
    private let templateReferenceWidth: CGFloat = 1080

    override func broadcastStarted(withSetupInfo setupInfo: [String : NSObject]?) {
        logger.log("NakamonREC Engine: Starting...")
        loadTemplates()
    }

    private func loadTemplates() {
        if let path = Bundle.main.path(forResource: "VS_FM", ofType: "png", inDirectory: "templates") {
            vsLogo = UIImage(contentsOfFile: path)
            logger.log("✅ VS_FM.png loaded")
        } else {
            logger.error("❌ VS_FM.png NOT FOUND in Extension bundle")
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

        if let vs = vsLogo {
            let newSize = CGSize(width: vs.size.width * scale, height: vs.size.height * scale)
            vsLogo = resizeImage(vs, targetSize: newSize)
            logger.log("VS template resized to \(Int(newSize.width))x\(Int(newSize.height))")
        }

        monsterTemplates = monsterTemplates.map { tpl in
            let newSize = CGSize(width: tpl.size.width * scale, height: tpl.size.height * scale)
            return resizeImage(tpl, targetSize: newSize)
        }
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with sampleType: RPSampleBufferType) {
        if sampleType == .video {
            handleVideoSample(sampleBuffer)
        }
    }

    private func handleVideoSample(_ sampleBuffer: CMSampleBuffer) {
        let currentTime = CACurrentMediaTime()

        // 解析中なら画像を蓄積 (バースト撮影)
        if isAnalyzing {
            if currentBurstImages.count < burstCount {
                if let uiImage = sampleBufferToUIImage(sampleBuffer) {
                    currentBurstImages.append(uiImage)
                }
                if currentBurstImages.count == burstCount {
                    performDeepAnalysis()
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

        // 1. VSロゴの検知 (スキャン) — シーンはネイティブサイズのまま
        if let vs = vsLogo {
            let centerX = Int32(uiImage.size.width * 0.5)
            let centerY = Int32(uiImage.size.height * 0.23)

            let score = NakamonWrapper.performMatch(withScene: uiImage,
                                                  templateImg: vs,
                                                  centerX: centerX,
                                                  centerY: centerY,
                                                  verticalMargin: 500,
                                                  horizontalMargin: 200)

            if score > 0.4 {
                logger.log("✅ VS Logo Found! (Score: \(score, format: .fixed(precision: 3))). Starting burst...")
                isAnalyzing = true
                currentBurstImages.removeAll()
                currentBurstImages.append(uiImage)
            } else if score > 0.1 {
                logger.log("🔎 Scanning... VS Score: \(score, format: .fixed(precision: 3))")
            }
        }
    }

    private func performDeepAnalysis() {
        logger.log("👾 Performing Deep Analysis on \(self.currentBurstImages.count) frames...")

        var maxScore: Double = 0

        for frame in currentBurstImages {
            let score = NakamonWrapper.findBestMonsterMatch(frame, templates: monsterTemplates)
            if score > maxScore {
                maxScore = score
            }
        }

        if maxScore > 0.7 {
            logger.log("✅ Monster identified! Score: \(maxScore, format: .fixed(precision: 3))")
        } else {
            logger.log("❓ Monster unclear. Best Score: \(maxScore, format: .fixed(precision: 3))")
        }

        isAnalyzing = false
        currentBurstImages.removeAll()
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
