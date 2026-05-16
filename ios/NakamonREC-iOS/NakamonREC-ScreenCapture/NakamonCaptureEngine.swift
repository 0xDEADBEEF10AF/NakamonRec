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

    private var didLogFrameSize = false

    // --- 案A: 目標とする校正サイズ ---
    private let targetWidth: CGFloat = 1125
    private let targetHeight: CGFloat = 2436

    override func broadcastStarted(withSetupInfo setupInfo: [String : NSObject]?) {
        logger.log("NakamonREC Engine: Starting...")
        loadTemplates()
    }

    private func loadTemplates() {
        if let path = Bundle.main.path(forResource: "VS_FM", ofType: "png", inDirectory: "templates") {
            vsLogo = UIImage(contentsOfFile: path)
        }
        // とりあえず30体ロード
        for i in 1...30 {
            let name = String(format: "id%03d", i)
            if let path = Bundle.main.path(forResource: name, ofType: "png", inDirectory: "templates") {
                if let img = UIImage(contentsOfFile: path) {
                    monsterTemplates.append(img)
                }
            }
        }
        logger.log("Loaded \(self.monsterTemplates.count) templates")
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
                    // --- 案A: リサイズして蓄積 ---
                    let resized = resizeImage(uiImage, targetSize: CGSize(width: targetWidth, height: targetHeight))
                    currentBurstImages.append(resized)
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

        if !didLogFrameSize {
            didLogFrameSize = true
            let tplSize = vsLogo?.size ?? .zero
            logger.log("Original Frame: \(Int(uiImage.size.width))x\(Int(uiImage.size.height)), Target: \(Int(self.targetWidth))x\(Int(self.targetHeight))")
        }

        // --- 案A: 解析前にリサイズ (886 -> 1125) ---
        let targetScene = resizeImage(uiImage, targetSize: CGSize(width: targetWidth, height: targetHeight))

        // 1. VSロゴの検知 (スキャン)
        if let vs = vsLogo {
            // リサイズ後の 1125x2436 座標系で計算
            let centerX = Int32(targetScene.size.width * 0.5)
            let centerY = Int32(targetScene.size.height * 0.23)

            let score = NakamonWrapper.performMatch(withScene: targetScene,
                                                  templateImg: vs,
                                                  centerX: centerX,
                                                  centerY: centerY,
                                                  verticalMargin: 120,
                                                  horizontalMargin: 120)

            if score > 0.85 {
                logger.log("✅ VS Logo Found! (Score: \(score, format: .fixed(precision: 3))). Starting burst...")
                isAnalyzing = true
                currentBurstImages.removeAll()
                currentBurstImages.append(targetScene)
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

        logger.log("🎯 Result: Best Monster Score \(maxScore, format: .fixed(precision: 3))")

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
