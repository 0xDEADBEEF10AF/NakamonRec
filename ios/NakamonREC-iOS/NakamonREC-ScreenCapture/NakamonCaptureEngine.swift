import ReplayKit
import VideoToolbox

class NakamonCaptureEngine: RPBroadcastSampleHandler {

    private var lastProcessTime: TimeInterval = 0
    private let processInterval: TimeInterval = 0.5

    // バースト解析用
    private var isAnalyzing = false
    private let burstCount = 5
    private var currentBurstImages: [UIImage] = []

    // テンプレートキャッシュ
    private var vsLogo: UIImage?
    private var monsterTemplates: [UIImage] = []

    override func broadcastStarted(withSetupInfo setupInfo: [String : NSObject]?) {
        print("NakamonREC Engine: Starting...")
        loadTemplates()
    }

    private func loadTemplates() {
        if let path = Bundle.main.path(forResource: "VS_MG", ofType: "png", inDirectory: "templates") {
            vsLogo = UIImage(contentsOfFile: path)
        }
        // とりあえず10体ロード（実際には全件必要）
        for i in 1...30 {
            let name = String(format: "id%03d", i)
            if let path = Bundle.main.path(forResource: name, ofType: "png", inDirectory: "templates") {
                if let img = UIImage(contentsOfFile: path) {
                    monsterTemplates.append(img)
                }
            }
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

        // 1. VSロゴの検知 (スキャン)
        if let vs = vsLogo {
            // iPhone 13 mini の縦長比率に合わせた座標 (中央上部)
            let centerX = Int32(uiImage.size.width * 0.5)
            let centerY = Int32(uiImage.size.height * 0.23) // VSロゴの位置調整

            let score = NakamonWrapper.performMatch(withScene: uiImage,
                                                  templateImg: vs,
                                                  centerX: centerX,
                                                  centerY: centerY,
                                                  verticalMargin: 120,
                                                  horizontalMargin: 120)

            if score > 0.85 {
                print("✅ VS Logo Found! (Score: \(score)). Starting burst capture...")
                isAnalyzing = true
                currentBurstImages.removeAll()
                currentBurstImages.append(uiImage)
            }
        }
    }

    private func performDeepAnalysis() {
        print("👾 Performing Deep Analysis on \(currentBurstImages.count) frames...")

        var bestMonsterName = "Unknown"
        var maxScore: Double = 0

        // 5枚の画像それぞれに対してマッチングを行い、最高スコアを採用 (Androidと同じロジック)
        for frame in currentBurstImages {
            let score = NakamonWrapper.findBestMonsterMatch(frame, templates: monsterTemplates)
            if score > maxScore {
                maxScore = score
                // 本来はインデックスからモンスター名を特定
            }
        }

        print("🎯 Result: Best Score \(maxScore)")

        // 解析完了。次のチャンスを待つ
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
}
