import ReplayKit
import VideoToolbox

class SampleHandler: RPBroadcastSampleHandler {

    private var lastProcessTime: TimeInterval = 0
    private let processInterval: TimeInterval = 0.5

    // テンプレート画像のキャッシュ
    private var vsLogo: UIImage?
    private var monsterTemplates: [UIImage] = []

    override func broadcastStarted(withSetupInfo setupInfo: [String : NSObject]?) {
        print("NakamonREC: Broadcast Started")
        loadTemplates()
    }

    private func loadTemplates() {
        // VSロゴの読み込み
        if let path = Bundle.main.path(forResource: "VS_MG", ofType: "png", inDirectory: "templates") {
            vsLogo = UIImage(contentsOfFile: path)
        }

        // モンスターテンプレートの読み込み (代表して数体、または全件)
        // 本来は monsters.json を元にループしますが、まずはテスト用に id001 などをロード
        for i in 1...10 {
            let name = String(format: "id%03d", i)
            if let path = Bundle.main.path(forResource: name, ofType: "png", inDirectory: "templates") {
                if let img = UIImage(contentsOfFile: path) {
                    monsterTemplates.append(img)
                }
            }
        }
        print("NakamonREC: Loaded \(monsterTemplates.count) templates")
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

        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let context = CIContext()
        guard let cgImage = context.createCGImage(ciImage, from: ciImage.extent) else { return }
        let uiImage = UIImage(cgImage: cgImage)

        // --- 解析フェーズ ---

        // 1. VSロゴの検知テスト (座標は iPhone 13 mini の解像度 886x1918 に合わせる必要があります)
        if let vs = vsLogo {
            // Android版の座標 (1080x2400想定) を iOS版 (886x1918) にスケーリングして計算
            let centerX = Int(Double(uiImage.size.width) * 0.5)
            let centerY = Int(Double(uiImage.size.height) * 0.25) // VSロゴは大体この辺

            let score = NakamonWrapper.performMatch(withScene: uiImage,
                                                  templateImg: vs,
                                                  centerX: Int32(centerX),
                                                  centerY: Int32(centerY),
                                                  verticalMargin: 100,
                                                  horizontalMargin: 100)

            if score > 0.85 {
                print("✅ NakamonREC: VS Logo Detected! Score: \(score)")

                // 2. モンスターの識別テスト (一番上の敵など)
                // 本来は座標を特定して切り出しますが、まずは全体でテスト
                let monsterScore = NakamonWrapper.findBestMonsterMatch(uiImage, templates: monsterTemplates)
                print("👾 NakamonREC: Best Monster Match Score: \(monsterScore)")
            } else {
                print("🔎 NakamonREC: Scanning... (VS Score: \(score))")
            }
        }
    }
}
