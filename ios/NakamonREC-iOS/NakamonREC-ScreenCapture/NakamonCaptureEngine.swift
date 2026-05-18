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
    // VS は BASE 2 種 (FM=フレンドマッチング, MG=大会用) + カスタムテンプレ対応。
    // カスタムが存在すれば BASE を完全に置き換える。
    private var vsLogos: [UIImage] = []
    private var winLogo: UIImage?
    private var loseLogo: UIImage?
    private var selectLogo: UIImage?
    private var monsterTemplates: [UIImage] = []
    private var monsterNames: [String] = []   // monsterTemplates と同じ index で対応する名前 "id001" 等
    /// 校正設定 (broadcastStarted 時に読み込み)
    private var calibrationConfig: CalibrationConfig = CalibrationDefaults.defaultConfig

    // モンスター 8 スロットの位置 + ROI サイズは CalibrationConfig.battlePrepMonsterROIs を使う。
    // インデックス 0..3 = myParty (画面下)、4..7 = enemy (画面上)
    private func isEnemySlot(_ slotIdx: Int) -> Bool { slotIdx >= 4 }

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

    /// pixelBuffer → CGImage 変換用 CIContext。毎フレーム alloc しないよう使い回す
    /// (Extension の 50MB メモリ制限対策: alloc/dealloc を減らして fragmentation を避ける)
    private let ciContext = CIContext(options: nil)

    override func broadcastStarted(withSetupInfo setupInfo: [String : NSObject]?) {
        logger.log("NakamonREC Engine: Starting...")
        BattleLogger.rotate()
        BattleLogger.append("Extension起動")
        BroadcastStatus.setActive(true)
        loadTemplates()
    }

    private func loadTemplates() {
        // 校正設定をロード (ROI 位置)
        calibrationConfig = CalibrationStore.load()

        // VS: カスタム (もし校正済なら) + BASE FM + BASE MG を全部ロード。
        // ランタイムは全てを試して max スコアを採用するので、普段用 VS_FM で校正済みでも
        // 大会用 VS_MG が映ったときに BASE MG にフォールバックして検出できる
        // (Android `BattleAnalyzer.isVsDetected()` と同じ多段検出パターン)。
        var vsList: [UIImage] = []
        if let custom = loadCustomTemplate(.vs) {
            vsList.append(custom)
            logger.log("✅ VS custom テンプレをロード")
            BattleLogger.append("✅ VS custom テンプレを使用 (FM/MG にもフォールバック)")
        }
        if let fm = loadBaseTemplate("VS_FM") {
            vsList.append(fm)
            logger.log("✅ VS_FM.png loaded")
        } else {
            BattleLogger.append("❌ VS_FM.png ロード失敗")
        }
        if let mg = loadBaseTemplate("VS_MG") {
            vsList.append(mg)
            logger.log("✅ VS_MG.png loaded")
        } else {
            BattleLogger.append("⚠️ VS_MG.png 未配置 (大会用)")
        }
        vsLogos = vsList

        // SELECT (カスタム優先)
        if let custom = loadCustomTemplate(.select) {
            selectLogo = custom
            logger.log("✅ SELECT custom テンプレを使用")
            BattleLogger.append("✅ SELECT custom テンプレを使用")
        } else if let base = loadBaseTemplate("SELECT") {
            selectLogo = base
            logger.log("✅ SELECT.png loaded")
        } else {
            logger.error("❌ SELECT.png NOT FOUND")
            BattleLogger.append("❌ SELECT.png ロード失敗")
        }

        // WIN (カスタム優先)
        if let custom = loadCustomTemplate(.win) {
            winLogo = custom
            logger.log("✅ WIN custom テンプレを使用")
            BattleLogger.append("✅ WIN custom テンプレを使用")
        } else if let base = loadBaseTemplate("WIN") {
            winLogo = base
            logger.log("✅ WIN.png loaded")
        } else {
            BattleLogger.append("❌ WIN.png ロード失敗")
        }

        // LOSE (カスタム優先)
        if let custom = loadCustomTemplate(.lose) {
            loseLogo = custom
            logger.log("✅ LOSE custom テンプレを使用")
            BattleLogger.append("✅ LOSE custom テンプレを使用")
        } else if let base = loadBaseTemplate("LOSE") {
            loseLogo = base
            logger.log("✅ LOSE.png loaded")
        } else {
            BattleLogger.append("❌ LOSE.png ロード失敗")
        }
        // monsters.json (シェアパッケージ) と LightLoadConfig からマッチング対象 ID 集合を決定
        let effectiveIDs = LightLoadConfig.effectiveMonsterIDs()
        let modeLabel = LightLoadConfig.mode == .light ? "軽負荷" : "通常"
        for entry in MonsterCatalog.all where effectiveIDs.contains(entry.id) {
            if let path = Bundle.main.path(forResource: entry.id, ofType: "png", inDirectory: "templates"),
               let img = UIImage(contentsOfFile: path) {
                monsterTemplates.append(img)
                monsterNames.append(entry.id)
            }
        }
        logger.log("Loaded \(self.monsterTemplates.count) monster templates (\(modeLabel))")
        BattleLogger.append("テンプレート読み込み完了 (\(modeLabel)モード, モンスター\(monsterTemplates.count)体)")
    }

    /// templates フォルダから BASE テンプレを読み込むヘルパー
    private func loadBaseTemplate(_ name: String) -> UIImage? {
        guard let path = Bundle.main.path(forResource: name, ofType: "png", inDirectory: "templates") else {
            return nil
        }
        return UIImage(contentsOfFile: path)
    }

    /// カスタムテンプレを App Group から読み込むヘルパー
    private func loadCustomTemplate(_ kind: CustomTemplateKind) -> UIImage? {
        guard let data = CustomTemplateStore.read(kind) else { return nil }
        return UIImage(data: data)
    }

    /// 初回フレーム到着時、フレーム幅に合わせて全テンプレートを1回だけリサイズする
    private func calibrateTemplates(forFrameWidth frameWidth: CGFloat) {
        let scale = frameWidth / templateReferenceWidth
        logger.log("Calibrating templates: frameWidth=\(Int(frameWidth)), scale=\(scale, format: .fixed(precision: 3))")
        BattleLogger.append(String(format: "校正完了 frame幅=%d scale=%.3f", Int(frameWidth), scale))

        vsLogos = vsLogos.map { resizeImage($0, scale: scale) }
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
        let rois = calibrationConfig.partySelectROIs
        let w = scene.size.width
        let h = scene.size.height

        var bestScore: Double = 0
        var bestIndex: Int = -1
        var allScores: [Double] = []
        for (i, roi) in rois.enumerated() {
            let cx = Int32(w * roi.centerXRatio)
            let cy = Int32(h * roi.centerYRatio)
            let hMargin = Int32(w * roi.searchHMarginRatio)
            let vMargin = Int32(h * roi.searchVMarginRatio)
            let score = NakamonWrapper.performMatch(withScene: scene,
                                                  templateImg: select,
                                                  centerX: cx,
                                                  centerY: cy,
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

            // マッチングスコア詳細用に p0/p1/p2 の ROI を保存し直す
            saveMatchingScorePartySnapshots(scene: scene, select: select,
                                            rois: rois, scores: allScores)
        }
    }

    /// パーティ選択 3 box ぶんの ROI を p0/p1/p2.png として書き出し、metadata の partyScores を更新する
    private func saveMatchingScorePartySnapshots(scene: UIImage,
                                                 select: UIImage,
                                                 rois: [CalibrationROI],
                                                 scores: [Double]) {
        let w = scene.size.width
        let h = scene.size.height
        for (i, roi) in rois.enumerated() {
            let path = MatchingScoreSnapshot.path(forFile: "p\(i).png")
            let cx = Int32(w * roi.centerXRatio)
            let cy = Int32(h * roi.centerYRatio)
            let hMargin = Int32(w * roi.searchHMarginRatio)
            let vMargin = Int32(h * roi.searchVMarginRatio)
            _ = NakamonWrapper.performMatchAndSave(withScene: scene,
                                                   templateImg: select,
                                                   centerX: cx,
                                                   centerY: cy,
                                                   verticalMargin: vMargin,
                                                   horizontalMargin: hMargin,
                                                   savePath: path)
        }
        MatchingScoreSnapshot.updateMetadata { meta in
            meta.partyScores = scores
        }
    }

    // MARK: - VS Logo (戦闘開始)

    private func scanForVS(_ scene: UIImage) {
        guard !vsLogos.isEmpty else { return }
        let roi = calibrationConfig.battlePrepVSROI
        let cx = Int32(scene.size.width * roi.centerXRatio)
        let cy = Int32(scene.size.height * roi.centerYRatio)
        let hMargin = Int32(scene.size.width * roi.searchHMarginRatio)
        let vMargin = Int32(scene.size.height * roi.searchVMarginRatio)
        // VS_FM + VS_MG (or 単一カスタム) のうち最高スコアを採用
        var score: Double = 0
        var bestVS: UIImage = vsLogos[0]
        for vs in vsLogos {
            let s = NakamonWrapper.performMatch(withScene: scene,
                                              templateImg: vs,
                                              centerX: cx,
                                              centerY: cy,
                                              verticalMargin: vMargin,
                                              horizontalMargin: hMargin)
            if s > score { score = s; bestVS = vs }
        }
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

            let startedAt = Date()

            // BattleRecord 用に進行中バトルを初期化
            pendingLock.lock()
            pending = PendingBattle(startedAt: startedAt,
                                    vsScore: score,
                                    partyIndex: lastDetectedPartyIndex,
                                    partySelectScores: lastPartySelectScores)
            pendingLock.unlock()

            // マッチングスコア詳細: 古い vs/slot/result サムネをクリアし、新しい vs.png を保存
            MatchingScoreSnapshot.clearBattleArtifacts()
            let vsPath = MatchingScoreSnapshot.path(forFile: "vs.png")
            _ = NakamonWrapper.performMatchAndSave(withScene: scene,
                                                   templateImg: bestVS,
                                                   centerX: cx,
                                                   centerY: cy,
                                                   verticalMargin: vMargin,
                                                   horizontalMargin: hMargin,
                                                   savePath: vsPath)
            let ts = BattleTimestampFormatter.formatter.string(from: startedAt)
            MatchingScoreSnapshot.updateMetadata { meta in
                meta.battleTimestamp = ts
                meta.vsScore = score
                meta.partyScores = self.lastPartySelectScores.isEmpty ? meta.partyScores : self.lastPartySelectScores
                // 戦闘ごとにリセット
                meta.myPartyScores = nil
                meta.enemyPartyScores = nil
                meta.myPartyNames = nil
                meta.enemyPartyNames = nil
                meta.resultLabel = nil
                meta.resultScore = nil
            }

            // 次の戦闘でも検知ログが出るようリセット
            lastDetectedPartyIndex = -1
        } else if score > 0.1 {
            logger.log("🔎 Scanning... VS Score: \(score, format: .fixed(precision: 3))")
        }
    }

    // MARK: - Monster Identification (バースト解析)

    /// バックグラウンドキューで実行される重い解析処理
    /// 8 スロット (myParty 0..3 + enemy 4..7) × 30 テンプレート × 5 フレームを per-slot 逐次で識別
    /// メモリ対策: フレームを 1 枚ずつ pop して処理し、autoreleasepool で中間バッファを即解放
    private func performDeepAnalysis(frames inputFrames: [UIImage]) {
        let startedAt = Date()
        logger.log("👾 Performing Deep Analysis on \(inputFrames.count) frames (8 slots)...")
        BattleLogger.append("モンスター解析開始 (\(inputFrames.count)枚 × 8スロット)")

        // スロットごとの最良結果 (5 フレーム横断で最高スコアを残す)
        struct SlotBest { var score: Double = 0; var index: Int = -1 }
        let slotROIs = calibrationConfig.battlePrepMonsterROIs
        var perSlot = Array(repeating: SlotBest(), count: slotROIs.count)

        var frames = inputFrames
        while !frames.isEmpty {
            autoreleasepool {
                let frame = frames.removeFirst()
                let w = frame.size.width
                let h = frame.size.height
                for (slotIdx, roi) in slotROIs.enumerated() {
                    let cx = Int32(w * roi.centerXRatio)
                    let cy = Int32(h * roi.centerYRatio)
                    let rw = Int32(w * (roi.widthRatio + 2 * roi.searchHMarginRatio))
                    let rh = Int32(h * (roi.heightRatio + 2 * roi.searchVMarginRatio))
                    let result = NakamonWrapper.bestMonster(inRegion: frame,
                                                            centerX: cx,
                                                            centerY: cy,
                                                            width: rw,
                                                            height: rh)
                    if result.score > perSlot[slotIdx].score {
                        perSlot[slotIdx].score = result.score
                        perSlot[slotIdx].index = result.index
                        // 新ベスト → このフレームの ROI を slot_<i>.png として上書き保存
                        // (最終的に最高スコアフレームのスナップショットが残る)
                        if let path = MatchingScoreSnapshot.path(forFile: "slot_\(slotIdx).png") {
                            saveSlotSnapshot(frame: frame,
                                             centerX: Int(cx), centerY: Int(cy),
                                             width: Int(rw), height: Int(rh),
                                             toPath: path)
                        }
                    }
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
            let isEnemy = isEnemySlot(slotIdx)
            let side = isEnemy ? "敵"  : "味方"
            let withinSide = isEnemy ? slotIdx - 4 : slotIdx
            // スコアが 0.7 未満の場合は識別失敗扱い ("?") で保存
            let name: String
            if best.index >= 0 && best.index < monsterNames.count && best.score >= 0.7 {
                name = monsterNames[best.index]
            } else {
                name = "?"
            }
            if isEnemy {
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

        // マッチングスコア詳細メタデータ更新
        MatchingScoreSnapshot.updateMetadata { meta in
            meta.myPartyNames = myParty
            meta.enemyPartyNames = enemyParty
            meta.myPartyScores = myPartyScores
            meta.enemyPartyScores = enemyPartyScores
        }

        if readyToFinalize {
            finalizePendingBattle()
        }
    }

    // MARK: - WIN / LOSE (戦闘終了)

    private func checkBattleEnd(_ scene: UIImage) {
        if let win = winLogo {
            let roi = calibrationConfig.winROI
            let cx = Int32(scene.size.width * roi.centerXRatio)
            let cy = Int32(scene.size.height * roi.centerYRatio)
            let hMargin = Int32(scene.size.width * roi.searchHMarginRatio)
            let vMargin = Int32(scene.size.height * roi.searchVMarginRatio)
            let score = NakamonWrapper.performMatch(withScene: scene,
                                                  templateImg: win,
                                                  centerX: cx,
                                                  centerY: cy,
                                                  verticalMargin: vMargin,
                                                  horizontalMargin: hMargin)
            if score > 0.4 {
                logger.log("🏆 Battle Won! (Score: \(score, format: .fixed(precision: 3)))")
                BattleLogger.append(String(format: "🏆 勝利検知 Score %.3f", score))
                isBattleInProgress = false
                saveResultSnapshot(scene: scene, template: win, label: "WIN", score: score,
                                   cx: cx, cy: cy, hMargin: hMargin, vMargin: vMargin)
                recordBattleResult(result: "WIN", score: score)
                return
            }
        }

        if let lose = loseLogo {
            let roi = calibrationConfig.loseROI
            let cx = Int32(scene.size.width * roi.centerXRatio)
            let cy = Int32(scene.size.height * roi.centerYRatio)
            let hMargin = Int32(scene.size.width * roi.searchHMarginRatio)
            let vMargin = Int32(scene.size.height * roi.searchVMarginRatio)
            let score = NakamonWrapper.performMatch(withScene: scene,
                                                  templateImg: lose,
                                                  centerX: cx,
                                                  centerY: cy,
                                                  verticalMargin: vMargin,
                                                  horizontalMargin: hMargin)
            if score > 0.4 {
                logger.log("💀 Battle Lost... (Score: \(score, format: .fixed(precision: 3)))")
                BattleLogger.append(String(format: "💀 敗北検知 Score %.3f", score))
                isBattleInProgress = false
                saveResultSnapshot(scene: scene, template: lose, label: "LOSE", score: score,
                                   cx: cx, cy: cy, hMargin: hMargin, vMargin: vMargin)
                recordBattleResult(result: "LOSE", score: score)
            }
        }
    }

    /// マッチングスコア詳細用に WIN/LOSE 検知時の ROI を result.png として保存し metadata 更新
    private func saveResultSnapshot(scene: UIImage, template: UIImage, label: String, score: Double,
                                    cx: Int32, cy: Int32, hMargin: Int32, vMargin: Int32) {
        let path = MatchingScoreSnapshot.path(forFile: "result.png")
        _ = NakamonWrapper.performMatchAndSave(withScene: scene,
                                               templateImg: template,
                                               centerX: cx,
                                               centerY: cy,
                                               verticalMargin: vMargin,
                                               horizontalMargin: hMargin,
                                               savePath: path)
        MatchingScoreSnapshot.updateMetadata { meta in
            meta.resultLabel = label
            meta.resultScore = score
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
        // Host メイン画面のサマリ即時更新用シグナル
        BroadcastStatus.lastRecordTimestamp = record.timestamp
        BattleLogger.append(String(format: "📝 戦績記録: %@ P[%d] 味方=%@ vs 敵=%@",
                                   result,
                                   p.partyIndex + 1,
                                   myParty.joined(separator: ","),
                                   enemyParty.joined(separator: ",")))
    }

    // MARK: - Helpers

    /// frame の指定 ROI を切り出して PNG として書き出す。
    /// NakamonWrapper.bestMonsterAndSave と同じ crop ロジックを Swift で再現することで
    /// matchTemplate の二度実行を回避する (新ベスト発見時の保存だけにコストを集中)
    private func saveSlotSnapshot(frame: UIImage,
                                  centerX: Int, centerY: Int,
                                  width: Int, height: Int,
                                  toPath path: String) {
        guard let cgImage = frame.cgImage else { return }
        let imgW = cgImage.width
        let imgH = cgImage.height
        let w = min(width, imgW)
        let h = min(height, imgH)
        let left = max(0, min(centerX - w / 2, imgW - w))
        let top  = max(0, min(centerY - h / 2, imgH - h))
        let rect = CGRect(x: left, y: top, width: w, height: h)
        autoreleasepool {
            guard let cropped = cgImage.cropping(to: rect) else { return }
            if let data = UIImage(cgImage: cropped).pngData() {
                try? data.write(to: URL(fileURLWithPath: path), options: .atomic)
            }
        }
    }

    private func sampleBufferToUIImage(_ sampleBuffer: CMSampleBuffer) -> UIImage? {
        // autoreleasepool で中間 CIImage/CGImage を即解放し、Extension 50MB 制限への蓄積を防ぐ
        autoreleasepool {
            guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return nil }
            let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
            guard let cgImage = ciContext.createCGImage(ciImage, from: ciImage.extent) else {
                return nil
            }
            return UIImage(cgImage: cgImage)
        }
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
        BroadcastStatus.setActive(false)
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
