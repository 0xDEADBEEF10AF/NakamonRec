import SwiftUI
import NakamonREC_Shared

/// 校正画面 (1 つの画面ぶん)
/// インポート済みスクショに緑枠 ROI を重ね、自動校正でカスタムテンプレを生成する。
/// ドラッグ操作は廃止: 校正は基本的に自動校正ボタン経由で行う。
struct CalibrationView: View {
    let screen: CalibrationScreen
    @Environment(\.dismiss) private var dismiss

    @State private var screenshotImage: UIImage? = nil
    @State private var rois: [CalibrationROI] = []
    @State private var scores: [Double] = []
    @State private var hasCustomTemplate: Bool = false
    @State private var isAutoCalibrating: Bool = false
    @State private var statusMessage: String? = nil

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let image = screenshotImage {
                GeometryReader { geo in
                    let layout = computeLayout(geometry: geo.size, image: image)
                    ZStack(alignment: .topLeading) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(width: geo.size.width, height: geo.size.height)

                        ForEach(rois.indices, id: \.self) { idx in
                            roiOverlay(idx: idx, layout: layout)
                        }
                    }
                    .frame(width: geo.size.width, height: geo.size.height)
                }
                .ignoresSafeArea()
            } else {
                ProgressView("読み込み中…")
                    .tint(.white)
                    .foregroundStyle(.white)
            }

            // Template ラベル (右上)
            VStack {
                HStack {
                    Text(screen.title)
                        .font(.caption.bold())
                        .foregroundStyle(.white)
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(Color.black.opacity(0.6))
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                    Spacer()
                    Text("Template: \(hasCustomTemplate ? "CUSTOM" : "BASE")")
                        .font(.caption.bold())
                        .foregroundStyle(hasCustomTemplate ? Color.recCoral : .gray)
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(Color.black.opacity(0.6))
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .padding(.horizontal, 12)
                .padding(.top, 8)
                Spacer()
            }

            // 進行中インジケータ
            if isAutoCalibrating {
                Color.black.opacity(0.5).ignoresSafeArea()
                VStack(spacing: 12) {
                    ProgressView().tint(.white).scaleEffect(1.5)
                    Text("自動校正中…")
                        .font(.callout)
                        .foregroundStyle(.white)
                }
            }
        }
        .safeAreaInset(edge: .bottom) { bottomBar }
        .onAppear(perform: load)
        .alert("お知らせ", isPresented: Binding(
            get: { statusMessage != nil },
            set: { if !$0 { statusMessage = nil } })) {
            Button("OK") { statusMessage = nil }
        } message: {
            Text(statusMessage ?? "")
        }
    }

    // MARK: - Bottom bar

    private var bottomBar: some View {
        HStack(spacing: 6) {
            Button("自動校正") { runAutoCalibration() }
                .buttonStyle(.bordered)
                .tint(Color.recCoral)
            Button("デフォルト") { resetToDefault() }
                .buttonStyle(.bordered)
                .tint(.gray)
            Spacer()
            Button("戻る") { dismiss() }
                .buttonStyle(.bordered)
                .tint(.gray)
            Button("この位置で決定") { saveAndDismiss() }
                .buttonStyle(.borderedProminent)
                .tint(Color.recCoral)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color.black.opacity(0.85))
        .disabled(isAutoCalibrating)
    }

    // MARK: - ROI overlay (display only, no drag)

    private func roiOverlay(idx: Int, layout: Layout) -> some View {
        let roi = rois[idx]
        let center = layout.point(forRatio: CGPoint(x: roi.centerXRatio, y: roi.centerYRatio))
        let templateSize = layout.size(forRatio: CGSize(width: roi.widthRatio, height: roi.heightRatio))
        let searchSize = layout.size(forRatio: CGSize(
            width: roi.widthRatio + 2 * roi.searchHMarginRatio,
            height: roi.heightRatio + 2 * roi.searchVMarginRatio
        ))
        return ZStack {
            // 薄緑塗り (探索範囲)
            Rectangle()
                .fill(Color.green.opacity(0.18))
                .frame(width: searchSize.width, height: searchSize.height)
                .position(center)
            // 緑枠 (ROI 本体)
            Rectangle()
                .stroke(Color.green, lineWidth: 2)
                .frame(width: templateSize.width, height: templateSize.height)
                .position(center)
            // ラベル
            Text("\(roiLabel(idx)): \(scoreString(idx))")
                .font(.caption2.bold().monospacedDigit())
                .foregroundStyle(.white)
                .padding(.horizontal, 5).padding(.vertical, 2)
                .background(Color.black.opacity(0.7))
                .clipShape(RoundedRectangle(cornerRadius: 4))
                .position(x: center.x + searchSize.width / 2 + 30, y: center.y)
        }
    }

    private func roiLabel(_ idx: Int) -> String {
        switch screen {
        case .partySelect: return "P\(idx + 1)"
        case .battlePrep:  return idx == 0 ? "VS" : "M\(idx)"
        case .win:         return "WIN"
        case .lose:        return "LOSE"
        }
    }

    private func scoreString(_ idx: Int) -> String {
        guard idx < scores.count else { return "—" }
        let s = scores[idx]
        return s < 0 ? "—" : String(format: "%.3f", s)
    }

    // MARK: - Layout helper

    private struct Layout {
        let imageRect: CGRect

        func point(forRatio r: CGPoint) -> CGPoint {
            CGPoint(x: imageRect.minX + imageRect.width * r.x,
                    y: imageRect.minY + imageRect.height * r.y)
        }
        func size(forRatio s: CGSize) -> CGSize {
            CGSize(width: imageRect.width * s.width,
                   height: imageRect.height * s.height)
        }
    }

    private func computeLayout(geometry: CGSize, image: UIImage) -> Layout {
        let imgAspect = image.size.width / image.size.height
        let containerAspect = geometry.width / geometry.height
        var rect: CGRect
        if imgAspect > containerAspect {
            let w = geometry.width
            let h = w / imgAspect
            rect = CGRect(x: 0, y: (geometry.height - h) / 2, width: w, height: h)
        } else {
            let h = geometry.height
            let w = h * imgAspect
            rect = CGRect(x: (geometry.width - w) / 2, y: 0, width: w, height: h)
        }
        return Layout(imageRect: rect)
    }

    // MARK: - Load / save / score

    private func load() {
        if let data = CalibrationScreenshotStore.read(screen),
           let img = UIImage(data: data) {
            screenshotImage = img
        }
        rois = roisFromConfig()
        hasCustomTemplate = CustomTemplateStore.exists(customKind)
        recomputeScores()
    }

    private var customKind: CustomTemplateKind {
        switch screen {
        case .partySelect: return .select
        case .battlePrep:  return .vs
        case .win:         return .win
        case .lose:        return .lose
        }
    }

    private func roisFromConfig() -> [CalibrationROI] {
        let cfg = CalibrationStore.load()
        switch screen {
        case .partySelect: return cfg.partySelectROIs
        case .battlePrep:  return [cfg.battlePrepVSROI] + cfg.battlePrepMonsterROIs
        case .win:         return [cfg.winROI]
        case .lose:        return [cfg.loseROI]
        }
    }

    private func resetToDefault() {
        switch screen {
        case .partySelect: rois = CalibrationDefaults.partySelectROIs
        case .battlePrep:  rois = [CalibrationDefaults.battlePrepVSROI] + CalibrationDefaults.battlePrepMonsterROIs
        case .win:         rois = [CalibrationDefaults.winROI]
        case .lose:        rois = [CalibrationDefaults.loseROI]
        }
        // 既存カスタムテンプレも削除
        CustomTemplateStore.remove(customKind)
        hasCustomTemplate = false
        recomputeScores()
    }

    private func saveAndDismiss() {
        var cfg = CalibrationStore.load()
        switch screen {
        case .partySelect: cfg.partySelectROIs = rois
        case .battlePrep:
            if let first = rois.first { cfg.battlePrepVSROI = first }
            if rois.count > 1 { cfg.battlePrepMonsterROIs = Array(rois.dropFirst()) }
        case .win:  if let r = rois.first { cfg.winROI = r }
        case .lose: if let r = rois.first { cfg.loseROI = r }
        }
        CalibrationStore.save(cfg)
        dismiss()
    }

    /// 現在の ROI 位置で BASE テンプレマッチを実行し、各 ROI のライブスコアを更新
    private func recomputeScores() {
        guard let scene = screenshotImage else {
            scores = Array(repeating: 0, count: rois.count)
            return
        }
        var newScores: [Double] = []
        let w = scene.size.width
        let h = scene.size.height
        for (idx, roi) in rois.enumerated() {
            // 対戦じゅんびのモンスタースロット (idx >= 1) は 126 体マッチが重いのでスキップ (— 表示)
            if screen == .battlePrep && idx >= 1 {
                newScores.append(-1)
                continue
            }
            guard let tpl = baseTemplateForScore(roiIndex: idx) else {
                newScores.append(0)
                continue
            }
            let cx = Int32(w * roi.centerXRatio)
            let cy = Int32(h * roi.centerYRatio)
            let hMargin = Int32(w * roi.searchHMarginRatio)
            let vMargin = Int32(h * roi.searchVMarginRatio)
            let s = NakamonWrapper.performMatch(withScene: scene,
                                              templateImg: tpl,
                                              centerX: cx,
                                              centerY: cy,
                                              verticalMargin: vMargin,
                                              horizontalMargin: hMargin)
            newScores.append(s)
        }
        scores = newScores
    }

    /// 対戦じゅんびの VS ロゴだけ "VS_FM" を返す。それ以外は baseTemplate と同じ
    private func baseTemplateForScore(roiIndex: Int) -> UIImage? {
        baseTemplate()
    }

    private func baseTemplate() -> UIImage? {
        let name: String
        switch screen {
        case .partySelect: name = "SELECT"
        case .battlePrep:  name = "VS_FM"
        case .win:         name = "WIN"
        case .lose:        name = "LOSE"
        }
        guard let path = Bundle.main.path(forResource: name, ofType: "png", inDirectory: "templates") else {
            return nil
        }
        return UIImage(contentsOfFile: path)
    }

    // MARK: - Auto-calibration

    /// パーティ選択画面用の自動校正。
    /// アルゴリズム (NMS):
    ///   1. BASE SELECT で imported screenshot 全体を matchTemplate
    ///   2. NMS で上位 3 件の位置を抽出 (水色 1 + 黄色 2、または黄色 3)
    ///   3. Y で昇順ソートして P1/P2/P3 にそのまま割り当て
    ///   4. 最高スコア位置 (水色) で screenshot を切り出し、1080-ref サイズに正規化 → SELECT_custom.png
    private func runAutoCalibration() {
        guard let scene = screenshotImage else {
            statusMessage = "スクショが読み込めていません。"
            return
        }
        switch screen {
        case .partySelect: runPartySelectAutoCal(scene: scene)
        case .battlePrep:  runBattlePrepAutoCal(scene: scene)
        case .win:         runResultAutoCal(scene: scene, kind: .win)
        case .lose:        runResultAutoCal(scene: scene, kind: .lose)
        }
    }

    /// 勝利 / ざんねん画面用の自動校正。
    /// アルゴリズム:
    ///   1. BASE WIN.png または LOSE.png で findBestMatchLocation
    ///   2. 最高スコア位置にロゴ ROI を移動
    ///   3. クロップして 1080-ref に正規化 → WIN_custom.png または LOSE_custom.png
    private func runResultAutoCal(scene: UIImage, kind: CustomTemplateKind) {
        let baseName = (kind == .win) ? "WIN" : "LOSE"
        guard let base = loadTemplate(baseName) else {
            statusMessage = "BASE \(baseName).png が見つかりません。"
            return
        }
        isAutoCalibrating = true
        CustomTemplateStore.remove(kind)
        hasCustomTemplate = false

        DispatchQueue.global(qos: .userInitiated).async {
            let baseScaled = self.templateScaledToScene(base, scene: scene)
            let loc = NakamonWrapper.findBestMatchLocation(inScene: scene, templateImg: baseScaled)
            DispatchQueue.main.async {
                handleResultAutoCalResult(matchLocation: loc, kind: kind, scene: scene)
            }
        }
    }

    private func handleResultAutoCalResult(matchLocation: NakamonMatchLocation,
                                           kind: CustomTemplateKind,
                                           scene: UIImage) {
        defer { isAutoCalibrating = false }
        guard matchLocation.score >= 0.4 else {
            statusMessage = String(format: "%@ ロゴを検出できませんでした (最高スコア %.3f)",
                                   (kind == .win ? "勝利" : "ざんねん"),
                                   matchLocation.score)
            return
        }
        let sceneW = Double(scene.size.width)
        let sceneH = Double(scene.size.height)
        let matchX = Double(matchLocation.centerX) / sceneW
        let matchY = Double(matchLocation.centerY) / sceneH

        let defaultROI = (kind == .win) ? CalibrationDefaults.winROI : CalibrationDefaults.loseROI
        var updated = defaultROI
        updated.centerXRatio = clamp(matchX, 0, 1)
        updated.centerYRatio = clamp(matchY, 0, 1)
        rois = [updated]

        if let custom = generateCustomTemplate(scene: scene,
                                               matchCenterXRatio: matchX,
                                               matchCenterYRatio: matchY,
                                               templateWidthRatio: defaultROI.widthRatio,
                                               templateHeightRatio: defaultROI.heightRatio) {
            CustomTemplateStore.save(custom, as: kind)
            hasCustomTemplate = true
        }

        recomputeScores()
        statusMessage = String(format: "自動校正完了 (スコア %.3f)\nこの位置で決定 を押すと保存されます。",
                               matchLocation.score)
    }

    private func runPartySelectAutoCal(scene: UIImage) {
        guard let baseSelect = baseTemplate() else {
            statusMessage = "BASE SELECT テンプレが見つかりません。"
            return
        }
        isAutoCalibrating = true
        CustomTemplateStore.remove(.select)
        hasCustomTemplate = false

        let sceneW = scene.size.width
        let sceneH = scene.size.height
        let suppressHalfW = Int32(sceneW * 0.05)
        let suppressHalfH = Int32(sceneH * 0.07)

        DispatchQueue.global(qos: .userInitiated).async {
            let locations = NakamonWrapper.findTopKMatches(inScene: scene,
                                                           templateImg: baseSelect,
                                                           k: 3,
                                                           suppressHalfWidth: suppressHalfW,
                                                           suppressHalfHeight: suppressHalfH)
            DispatchQueue.main.async {
                handleAutoCalibrationResult(locations: Array(locations),
                                            scene: scene)
            }
        }
    }

    /// 対戦じゅんび画面用の自動校正。
    /// アルゴリズム:
    ///   1. BASE VS_FM と VS_MG の両方で findBestMatchLocation → VS 位置決定 + カスタムテンプレ生成
    ///   2. 全 126 モンスターテンプレを scene スケールにリサイズしてキャッシュ
    ///   3. 各スロット (8 個) で広めの探索範囲を使い bestMonsterLocationInRegion
    ///      → 最高スコアのテンプレ位置をそのスロットの新中心とする
    ///   4. 重い処理 (126 × 8 + テンプレロード/リサイズ) なので砂時計表示
    private func runBattlePrepAutoCal(scene: UIImage) {
        guard let vsFM = loadTemplate("VS_FM") else {
            statusMessage = "BASE VS_FM テンプレが見つかりません。"
            return
        }
        let vsMG = loadTemplate("VS_MG")
        isAutoCalibrating = true
        CustomTemplateStore.remove(.vs)
        hasCustomTemplate = false

        DispatchQueue.global(qos: .userInitiated).async {
            // --- 1. VS 検出 (テンプレを scene スケールにリサイズしてからマッチング) ---
            let vsFMScaled = self.templateScaledToScene(vsFM, scene: scene)
            let locFM = NakamonWrapper.findBestMatchLocation(inScene: scene, templateImg: vsFMScaled)
            var bestVSLoc = locFM
            if let vsMG = vsMG {
                let vsMGScaled = self.templateScaledToScene(vsMG, scene: scene)
                let locMG = NakamonWrapper.findBestMatchLocation(inScene: scene, templateImg: vsMGScaled)
                if locMG.score > bestVSLoc.score {
                    bestVSLoc = locMG
                }
            }

            // --- 2. 全モンスターテンプレをロード + scene スケールにリサイズ + キャッシュ ---
            let scale = scene.size.width / 1080.0
            var resizedTemplates: [UIImage] = []
            var loadedIDs: [String] = []
            for entry in MonsterCatalog.all {
                guard let path = Bundle.main.path(forResource: entry.id, ofType: "png", inDirectory: "templates"),
                      let img = UIImage(contentsOfFile: path) else { continue }
                let target = CGSize(width: img.size.width * scale,
                                    height: img.size.height * scale)
                let format = UIGraphicsImageRendererFormat()
                format.scale = 1.0
                let renderer = UIGraphicsImageRenderer(size: target, format: format)
                let resized = renderer.image { _ in
                    img.draw(in: CGRect(origin: .zero, size: target))
                }
                resizedTemplates.append(resized)
                loadedIDs.append(entry.id)
            }
            NakamonWrapper.cacheMonsterTemplates(resizedTemplates)

            // --- 3. 各スロットで per-slot best-match を取得 ---
            // 探索範囲: テンプレ 80×130 + 各辺余白 (X: ±60, Y: ±300+) で広めに
            // iPhone SE 等の wider aspect 機種では味方行が default Y から大きく下にズレるため
            // 縦方向の探索範囲を 700/2364 (約 30%) まで拡大して機種差を吸収する
            let slotSearchW = 200.0 / 1080.0   // 1080-ref で 200px 幅
            let slotSearchH = 700.0 / 2364.0   // 1080-ref で 700px 縦 (低解像度・wider aspect 端末も吸収)
            let sceneW = scene.size.width
            let sceneH = scene.size.height
            var slotResults: [(ratioX: Double, ratioY: Double, score: Double, id: String)] = []
            for roi in CalibrationDefaults.battlePrepMonsterROIs {
                let cx = Int32(sceneW * roi.centerXRatio)
                let cy = Int32(sceneH * roi.centerYRatio)
                let w = Int32(sceneW * slotSearchW)
                let h = Int32(sceneH * slotSearchH)
                let match = NakamonWrapper.bestMonsterLocation(inRegion: scene,
                                                                centerX: cx,
                                                                centerY: cy,
                                                                width: w,
                                                                height: h)
                let rx = Double(match.centerX) / Double(sceneW)
                let ry = Double(match.centerY) / Double(sceneH)
                let id: String = (match.index >= 0 && match.index < loadedIDs.count)
                                 ? loadedIDs[match.index] : "?"
                slotResults.append((rx, ry, match.score, id))
            }

            DispatchQueue.main.async {
                handleBattlePrepAutoCalResult(vsLocation: bestVSLoc,
                                              slotResults: slotResults,
                                              scene: scene)
            }
        }
    }

    private func handleBattlePrepAutoCalResult(vsLocation: NakamonMatchLocation,
                                               slotResults: [(ratioX: Double, ratioY: Double, score: Double, id: String)],
                                               scene: UIImage) {
        defer { isAutoCalibrating = false }
        guard vsLocation.score >= 0.4 else {
            statusMessage = String(format: "VS ロゴを検出できませんでした (最高スコア %.3f)", vsLocation.score)
            return
        }
        let sceneW = Double(scene.size.width)
        let sceneH = Double(scene.size.height)
        let vsX = Double(vsLocation.centerX) / sceneW
        let vsY = Double(vsLocation.centerY) / sceneH

        // VS 位置を更新
        var newROIs: [CalibrationROI] = []
        var newVS = CalibrationDefaults.battlePrepVSROI
        newVS.centerXRatio = clamp(vsX, 0, 1)
        newVS.centerYRatio = clamp(vsY, 0, 1)
        newROIs.append(newVS)

        // 各スロット位置を更新 (スコアが低い場合はデフォルト維持)
        for (i, defROI) in CalibrationDefaults.battlePrepMonsterROIs.enumerated() {
            var copy = defROI
            if i < slotResults.count, slotResults[i].score >= 0.5 {
                copy.centerXRatio = clamp(slotResults[i].ratioX, 0, 1)
                copy.centerYRatio = clamp(slotResults[i].ratioY, 0, 1)
            }
            newROIs.append(copy)
        }
        rois = newROIs

        // VS カスタムテンプレを生成
        let defaultVS = CalibrationDefaults.battlePrepVSROI
        if let custom = generateCustomTemplate(scene: scene,
                                               matchCenterXRatio: vsX,
                                               matchCenterYRatio: vsY,
                                               templateWidthRatio: defaultVS.widthRatio,
                                               templateHeightRatio: defaultVS.heightRatio) {
            CustomTemplateStore.save(custom, as: .vs)
            hasCustomTemplate = true
        }

        recomputeScores()
        let slotSummary = slotResults.enumerated().map { idx, r in
            let label = idx < 4 ? "味方\(idx)" : "敵\(idx - 4)"
            return String(format: "%@:%@ %.2f", label, r.id, r.score)
        }.joined(separator: " / ")
        statusMessage = String(format: "自動校正完了\nVS Score %.3f\n%@\nこの位置で決定 を押すと保存されます。",
                               vsLocation.score, slotSummary)
    }

    /// 名前を指定して templates フォルダから BASE テンプレを取得
    private func loadTemplate(_ name: String) -> UIImage? {
        guard let path = Bundle.main.path(forResource: name, ofType: "png", inDirectory: "templates") else {
            return nil
        }
        return UIImage(contentsOfFile: path)
    }

    /// BASE テンプレ (1080-ref) を被探索 scene の横幅に合わせてリサイズする。
    /// matchTemplate は同一ピクセルスケール前提のため、低解像度端末 (例: iPhone SE 750w) では
    /// テンプレを縮小しないと VS/WIN/LOSE 等の大きめロゴが検出できない。
    private func templateScaledToScene(_ image: UIImage, scene: UIImage) -> UIImage {
        let scale = scene.size.width / 1080.0
        let target = CGSize(width: image.size.width * scale,
                            height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }

    private func handleAutoCalibrationResult(locations: [NakamonMatchLocation],
                                             scene: UIImage) {
        defer { isAutoCalibrating = false }

        guard locations.count == 3 else {
            statusMessage = "SELECT を 3 つ検出できませんでした (検出 \(locations.count) 件)。"
            return
        }
        // 最高スコア (= 水色 SELECT) チェック
        let bestScore = locations.map(\.score).max() ?? 0
        guard bestScore >= 0.4 else {
            statusMessage = String(format: "SELECT 検出スコアが低すぎます (最高 %.3f)", bestScore)
            return
        }

        // Y で昇順ソート → P1/P2/P3 の順に割り当て
        let sorted = locations.sorted { $0.centerY < $1.centerY }
        let sceneW = Double(scene.size.width)
        let sceneH = Double(scene.size.height)
        let defaults = CalibrationDefaults.partySelectROIs

        var newROIs: [CalibrationROI] = []
        for (i, loc) in sorted.enumerated() {
            var copy = defaults[i]
            copy.centerXRatio = clamp(Double(loc.centerX) / sceneW, 0, 1)
            copy.centerYRatio = clamp(Double(loc.centerY) / sceneH, 0, 1)
            newROIs.append(copy)
        }
        rois = newROIs

        // 水色 SELECT (最高スコア位置) からカスタムテンプレを生成
        if let bestIdx = locations.indices.max(by: { locations[$0].score < locations[$1].score }) {
            let best = locations[bestIdx]
            let bestX = Double(best.centerX) / sceneW
            let bestY = Double(best.centerY) / sceneH
            if let custom = generateCustomTemplate(scene: scene,
                                                   matchCenterXRatio: bestX,
                                                   matchCenterYRatio: bestY,
                                                   templateWidthRatio: defaults[0].widthRatio,
                                                   templateHeightRatio: defaults[0].heightRatio) {
                CustomTemplateStore.save(custom, as: .select)
                hasCustomTemplate = true
            }
        }

        recomputeScores()
        let scoreList = locations.sorted { $0.centerY < $1.centerY }
                                 .enumerated()
                                 .map { String(format: "P%d %.3f", $0.offset + 1, $0.element.score) }
                                 .joined(separator: ", ")
        statusMessage = "自動校正完了\n\(scoreList)\nこの位置で決定 を押すと保存されます。"
    }

    /// scene から指定 ROI を切り出し、1080-ref ピクセルサイズに正規化したカスタムテンプレ PNG データを返す
    private func generateCustomTemplate(scene: UIImage,
                                        matchCenterXRatio: Double,
                                        matchCenterYRatio: Double,
                                        templateWidthRatio: Double,
                                        templateHeightRatio: Double) -> Data? {
        guard let cgScene = scene.cgImage else { return nil }
        let sW = Double(cgScene.width)
        let sH = Double(cgScene.height)
        let cx = matchCenterXRatio * sW
        let cy = matchCenterYRatio * sH
        let w = templateWidthRatio * sW
        let h = templateHeightRatio * sH
        let cropRect = CGRect(x: cx - w / 2, y: cy - h / 2, width: w, height: h)
            .integral
        guard cropRect.minX >= 0, cropRect.minY >= 0,
              cropRect.maxX <= sW, cropRect.maxY <= sH,
              let cropped = cgScene.cropping(to: cropRect) else {
            return nil
        }

        // 1080-ref サイズに正規化 (本番マッチング時に他の BASE と同じスケール扱いに)
        let targetW = templateWidthRatio * 1080.0
        let targetH = templateHeightRatio * 2364.0
        let targetSize = CGSize(width: max(1, targetW), height: max(1, targetH))
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: {
            let f = UIGraphicsImageRendererFormat()
            f.scale = 1.0
            return f
        }())
        let resized = renderer.image { _ in
            UIImage(cgImage: cropped).draw(in: CGRect(origin: .zero, size: targetSize))
        }
        return resized.pngData()
    }

    private func clamp(_ v: Double, _ lower: Double, _ upper: Double) -> Double {
        min(max(v, lower), upper)
    }
}
