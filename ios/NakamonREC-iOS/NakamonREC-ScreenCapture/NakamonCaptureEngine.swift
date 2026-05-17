import ReplayKit
import VideoToolbox
import OSLog
import NakamonREC_Shared

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
    private var selectLogo: UIImage?
    private var monsterTemplates: [UIImage] = []
    private var monsterNames: [String] = []   // monsterTemplates と同じ index で対応する名前 "id001" 等

    // モンスター 8 スロットの位置 (1080×2364 リファレンスの比率)。Android DataModels.kt 由来
    // 0..3 = myParty (画面下)、4..7 = enemy (画面上)
    private struct SlotConfig {
        let centerXRatio: Double
        let centerYRatio: Double
        let isEnemy: Bool
    }
    private let slotConfigs: [SlotConfig] = [
        // myParty: y = 1635/2364 = 0.692
        SlotConfig(centerXRatio: 196.0/1080.0, centerYRatio: 1635.0/2364.0, isEnemy: false),
        SlotConfig(centerXRatio: 391.0/1080.0, centerYRatio: 1635.0/2364.0, isEnemy: false),
        SlotConfig(centerXRatio: 585.0/1080.0, centerYRatio: 1635.0/2364.0, isEnemy: false),
        SlotConfig(centerXRatio: 780.0/1080.0, centerYRatio: 1635.0/2364.0, isEnemy: false),
        // enemy: y = 915/2364 = 0.387
        SlotConfig(centerXRatio: 201.0/1080.0, centerYRatio: 915.0/2364.0, isEnemy: true),
        SlotConfig(centerXRatio: 396.0/1080.0, centerYRatio: 915.0/2364.0, isEnemy: true),
        SlotConfig(centerXRatio: 590.0/1080.0, centerYRatio: 915.0/2364.0, isEnemy: true),
        SlotConfig(centerXRatio: 785.0/1080.0, centerYRatio: 915.0/2364.0, isEnemy: true)
    ]
    // 各スロット ROI のサイズ比率
    // テンプレ 80x130 + 十分な padding。2.5x → 2.0x に縮小して sweet spot 探索中。
    // 2.5x では識別 OK (Score 0.96+) だが 23 秒。1.0x では未検出。
    private let slotROIWidthRatio: Double = 280.0 / 1080.0     // ≈ 0.259
    private let slotROIHeightRatio: Double = 380.0 / 2364.0    // ≈ 0.161

    private var didCalibrate = false

    // 直近検知済みのパーティインデックス (1〜3)。再ログを抑制するため
    private var lastDetectedPartyIndex: Int = -1
    // 直近のパーティ選択 3 box ぶんスコア (BattleRecord 用)
    private var lastPartySelectScores: [Double] = []

    // 現在進行中の戦闘の集計中データ。WIN/LOSE 確定 + モンスター解析完了で finalize → JSON 保存
    private struct PendingBattle {
        var startedAt: Date
        var vsScore: Double
        var partyIndex: Int
        var partySelectScores: [Double]
        // モンスター解析が非同期で後から入る
        var myParty: [String]? = nil
        var enemyParty: [String]? = nil
        var myPartyScores: [Double]? = nil
        var enemyPartyScores: [Double]? = nil
        // 戦闘終了情報
        var result: String? = nil          // "WIN" / "LOSE"
        var resultScore: Double? = nil
    }
    private var pending: PendingBattle?
    private let pendingLock = NSLock()

    // テンプレ作成時のスクリーン基準幅 (Pixel 10 Pro: 1080)
    private let templateReferenceWidth: CGFloat = 1080

    override func broadcastStarted(withSetupInfo setupInfo: [String : NSObject]?) {
        logger.log("NakamonREC Engine: Starting...")
        BattleLogger.rotate()
        BattleLogger.append("Extension起動")
        loadTemplates()
    }

    private func loadTemplates() {
        if let path = Bundle.main.path(forResource: "VS_FM", ofType: "png", inDirectory: "templates") {
            vsLogo = UIImage(contentsOfFile: path)
            logger.log("✅ VS_FM.png loaded")
        } else {
            logger.error("❌ VS_FM.png NOT FOUND in Extension bundle")
            BattleLogger.append("❌ VS_FM.png ロード失敗")
        }
        if let path = Bundle.main.path(forResource: "WIN", ofType: "png", inDirectory: "templates"),
           let img = UIImage(contentsOfFile: path) {
            winLogo = img
            logger.log("✅ WIN.png loaded")
        } else {
            logger.error("❌ WIN.png NOT FOUND")
            BattleLogger.append("❌ WIN.png ロード失敗")
        }
        if let path = Bundle.main.path(forResource: "LOSE", ofType: "png", inDirectory: "templates"),
           let img = UIImage(contentsOfFile: path) {
            loseLogo = img
            logger.log("✅ LOSE.png loaded")
        } else {
            logger.error("❌ LOSE.png NOT FOUND")
            BattleLogger.append("❌ LOSE.png ロード失敗")
        }
        if let path = Bundle.main.path(forResource: "SELECT", ofType: "png", inDirectory: "templates"),
           let img = UIImage(contentsOfFile: path) {
            selectLogo = img
            logger.log("✅ SELECT.png loaded")
        } else {
            logger.error("❌ SELECT.png NOT FOUND")
            BattleLogger.append("❌ SELECT.png ロード失敗")
        }
        for i in 1...30 {
            let name = String(format: "id%03d", i)
            if let path = Bundle.main.path(forResource: name, ofType: "png", inDirectory: "templates"),
               let img = UIImage(contentsOfFile: path) {
                monsterTemplates.append(img)
                monsterNames.append(name)
            }
        }
        logger.log("Loaded \(self.monsterTemplates.count) monster templates")
        BattleLogger.append("テンプレート読み込み完了 (モンスター\(monsterTemplates.count)体)")
    }

    /// 初回フレーム到着時、フレーム幅に合わせて全テンプレートを1回だけリサイズする
    private func calibrateTemplates(forFrameWidth frameWidth: CGFloat) {
        let scale = frameWidth / templateReferenceWidth
        logger.log("Calibrating templates: frameWidth=\(Int(frameWidth)), scale=\(scale, format: .fixed(precision: 3))")
        BattleLogger.append(String(format: "校正完了 frame幅=%d scale=%.3f", Int(frameWidth), scale))

        vsLogo = vsLogo.map { resizeImage($0, scale: scale) }
        winLogo = winLogo.map { resizeImage($0, scale: scale) }
        loseLogo = loseLogo.map { resizeImage($0, scale: scale) }
        selectLogo = selectLogo.map { resizeImage($0, scale: scale) }
        monsterTemplates = monsterTemplates.map { resizeImage($0, scale: scale) }

        // C++ 側にもモンスターテンプレを cv::Mat で 1 回だけ変換してキャッシュ
        NakamonWrapper.cacheMonsterTemplates(monsterTemplates)
        BattleLogger.append("モンスターテンプレ cv::Mat キャッシュ完了")
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
            scanForPartySelect(uiImage)
            scanForVS(uiImage)
        } else {
            checkBattleEnd(uiImage)
        }
    }

    // MARK: - Party Selection (戦闘開始前のパーティ選択画面)

    /// Android `partySelectBoxes` (1080×2364 基準で x=850、y=1030/1430/1830) を見て
    /// SELECT.png がどのスロットにあるかを検知する。
    /// 直近検知済みインデックスと異なる時だけログ出力 (連続出力抑制)。
    private func scanForPartySelect(_ scene: UIImage) {
        guard let select = selectLogo else { return }

        // Android partySelectBoxes 基準: centerX=0.787、centerY=0.436/0.605/0.774
        let partyCentersY: [Double] = [0.436, 0.605, 0.774]
        let centerXRatio: Double = 0.787
        // Android: ROI_PAD_PARTY_H=30, ROI_PAD_PARTY_V=100
        let hMargin: Int32 = 30
        let vMargin: Int32 = 100
        let w = scene.size.width
        let h = scene.size.height
        let cx = Int32(w * centerXRatio)

        var bestScore: Double = 0
        var bestIndex: Int = -1
        var allScores: [Double] = []
        for (i, cy) in partyCentersY.enumerated() {
            let score = NakamonWrapper.performMatch(withScene: scene,
                                                  templateImg: select,
                                                  centerX: cx,
                                                  centerY: Int32(h * cy),
                                                  verticalMargin: vMargin,
                                                  horizontalMargin: hMargin)
            allScores.append(score)
            if score > bestScore {
                bestScore = score
                bestIndex = i
            }
        }
        lastPartySelectScores = allScores

        if bestScore >= 0.7 && bestIndex != lastDetectedPartyIndex {
            lastDetectedPartyIndex = bestIndex
            let partyNumber = bestIndex + 1
            logger.log("Party P[\(partyNumber)] selected (Score: \(bestScore, format: .fixed(precision: 3)))")
            BattleLogger.append(String(format: "パーティ選択検知 P[%d] Score %.3f", partyNumber, bestScore))
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
            BattleLogger.rotate()
            if lastDetectedPartyIndex >= 0 {
                BattleLogger.append(String(format: "パーティ P[%d] で戦闘開始", lastDetectedPartyIndex + 1))
            }
            BattleLogger.append(String(format: "VS検知 Score %.3f → バースト開始", score))
            isBattleInProgress = true
            isAnalyzing = true
            currentBurstImages.removeAll()
            currentBurstImages.append(scene)

            // BattleRecord 用に進行中バトルを初期化
            pendingLock.lock()
            pending = PendingBattle(startedAt: Date(),
                                    vsScore: score,
                                    partyIndex: lastDetectedPartyIndex,
                                    partySelectScores: lastPartySelectScores)
            pendingLock.unlock()

            // 次の戦闘でも検知ログが出るようリセット
            lastDetectedPartyIndex = -1
        } else if score > 0.1 {
            logger.log("🔎 Scanning... VS Score: \(score, format: .fixed(precision: 3))")
        }
    }

    // MARK: - Monster Identification (バースト解析)

    /// バックグラウンドキューで実行される重い解析処理
    /// 8 スロット (myParty 0..3 + enemy 4..7) × 30 テンプレート × 5 フレームを per-slot 逐次で識別
    private func performDeepAnalysis(frames: [UIImage]) {
        let startedAt = Date()
        logger.log("👾 Performing Deep Analysis on \(frames.count) frames (8 slots)...")
        BattleLogger.append("モンスター解析開始 (\(frames.count)枚 × 8スロット)")

        // スロットごとの最良結果 (5 フレーム横断で最高スコアを残す)
        struct SlotBest { var score: Double = 0; var index: Int = -1 }
        var perSlot = Array(repeating: SlotBest(), count: slotConfigs.count)

        for frame in frames {
            let w = frame.size.width
            let h = frame.size.height
            let rw = Int32(w * slotROIWidthRatio)
            let rh = Int32(h * slotROIHeightRatio)

            for (slotIdx, config) in slotConfigs.enumerated() {
                let cx = Int32(w * config.centerXRatio)
                let cy = Int32(h * config.centerYRatio)
                let result = NakamonWrapper.bestMonster(inRegion: frame,
                                                        centerX: cx,
                                                        centerY: cy,
                                                        width: rw,
                                                        height: rh)
                if result.score > perSlot[slotIdx].score {
                    perSlot[slotIdx].score = result.score
                    perSlot[slotIdx].index = result.index
                }
            }
        }

        let elapsed = Date().timeIntervalSince(startedAt)
        logger.log("👾 Deep analysis done in \(elapsed, format: .fixed(precision: 2))s")
        BattleLogger.append(String(format: "モンスター解析完了 (%.2fs)", elapsed))

        // 結果を 8 行で書き出す + pending に格納
        var myParty: [String] = []
        var enemyParty: [String] = []
        var myPartyScores: [Double] = []
        var enemyPartyScores: [Double] = []
        for (slotIdx, best) in perSlot.enumerated() {
            let config = slotConfigs[slotIdx]
            let side = config.isEnemy ? "敵"  : "味方"
            let withinSide = config.isEnemy ? slotIdx - 4 : slotIdx
            // スコアが 0.7 未満の場合は識別失敗扱い ("?") で保存
            let name: String
            if best.index >= 0 && best.index < monsterNames.count && best.score >= 0.7 {
                name = monsterNames[best.index]
            } else {
                name = "?"
            }
            if config.isEnemy {
                enemyParty.append(name)
                enemyPartyScores.append(best.score)
            } else {
                myParty.append(name)
                myPartyScores.append(best.score)
            }
            let marker = best.score >= 0.7 ? "✅" : "❓"
            BattleLogger.append(String(format: "%@ %@[%d] %@ Score %.3f",
                                       marker, side, withinSide, name, best.score))
        }

        // pending に格納し、戦闘終了が既に来ていれば finalize
        pendingLock.lock()
        pending?.myParty = myParty
        pending?.enemyParty = enemyParty
        pending?.myPartyScores = myPartyScores
        pending?.enemyPartyScores = enemyPartyScores
        let readyToFinalize = pending?.result != nil && pending?.myParty != nil
        pendingLock.unlock()
        if readyToFinalize {
            finalizePendingBattle()
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
                BattleLogger.append(String(format: "🏆 勝利検知 Score %.3f", score))
                isBattleInProgress = false
                recordBattleResult(result: "WIN", score: score)
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
                BattleLogger.append(String(format: "💀 敗北検知 Score %.3f", score))
                isBattleInProgress = false
                recordBattleResult(result: "LOSE", score: score)
            }
        }
    }

    // MARK: - BattleRecord finalization

    private func recordBattleResult(result: String, score: Double) {
        pendingLock.lock()
        pending?.result = result
        pending?.resultScore = score
        let readyToFinalize = pending?.myParty != nil
        pendingLock.unlock()
        if readyToFinalize {
            finalizePendingBattle()
        }
    }

    /// pending を BattleRecord に変換して JSON に永続化。完了後 pending をクリア
    private func finalizePendingBattle() {
        pendingLock.lock()
        guard let p = pending,
              let myParty = p.myParty,
              let enemyParty = p.enemyParty,
              let result = p.result,
              let resultScore = p.resultScore else {
            pendingLock.unlock()
            return
        }
        pending = nil
        pendingLock.unlock()

        let record = BattleRecord(
            timestamp: BattleTimestampFormatter.formatter.string(from: p.startedAt),
            result: result,
            partyIndex: p.partyIndex,
            myParty: myParty,
            enemyParty: enemyParty,
            vsScore: p.vsScore,
            myPartyScores: p.myPartyScores,
            enemyPartyScores: p.enemyPartyScores,
            resultScore: resultScore,
            partySelectScores: p.partySelectScores
        )
        BattleHistoryStore.shared.append(record)
        BattleLogger.append(String(format: "📝 戦績記録: %@ P[%d] 味方=%@ vs 敵=%@",
                                   result,
                                   p.partyIndex + 1,
                                   myParty.joined(separator: ","),
                                   enemyParty.joined(separator: ",")))
    }

    // MARK: - Helpers

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
        BattleLogger.append("ブロードキャスト一時停止")
    }

    override func broadcastResumed() {
        logger.log("NakamonREC: Broadcast Resumed")
        BattleLogger.append("ブロードキャスト再開")
    }

    override func broadcastFinished() {
        logger.log("NakamonREC: Broadcast Finished")
        BattleLogger.append("ブロードキャスト終了")
        // Android 同様、戦闘終了 (WIN/LOSE) を検知していない進行中バトルは破棄
        pendingLock.lock()
        if pending != nil {
            BattleLogger.append("⚠️ 進行中バトルを破棄 (WIN/LOSE 未検知のためレコード化せず)")
            pending = nil
        }
        pendingLock.unlock()
        isBattleInProgress = false
        isAnalyzing = false
        currentBurstImages.removeAll()
    }
}
