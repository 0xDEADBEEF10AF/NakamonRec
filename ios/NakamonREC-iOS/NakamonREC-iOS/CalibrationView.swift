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
    /// VS 画面のカスタムテンプレが「大会用 VS」で作られている (= グランプリ記録モード ON) か
    @State private var isGrandPrixTemplate: Bool = false

    // 詳細校正 (VS画面のみ): 8 スロットに事前にモンスター ID を指定し、1-vs-1 マッチで校正する
    @State private var showDetailCalSheet: Bool = false
    @State private var detailCalSlotIds: [String?] = DetailCalibrationConfig.slotIds

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
                    VStack(alignment: .trailing, spacing: 4) {
                        Text("Template: \(hasCustomTemplate ? "CUSTOM" : "BASE")")
                            .font(.caption.bold())
                            .foregroundStyle(hasCustomTemplate ? Color.recCoral : .gray)
                            .padding(.horizontal, 8).padding(.vertical, 4)
                            .background(Color.black.opacity(0.6))
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                        if isGrandPrixTemplate {
                            Text("GRAND PRIX MODE")
                                .font(.caption2.bold())
                                .foregroundStyle(Color.recCoral)
                                .padding(.horizontal, 8).padding(.vertical, 4)
                                .background(Color.black.opacity(0.6))
                                .clipShape(RoundedRectangle(cornerRadius: 6))
                        }
                    }
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
        .overlay {
            if showDetailCalSheet {
                DetailCalibrationSheet(
                    slotIds: $detailCalSlotIds,
                    onChange: { DetailCalibrationConfig.slotIds = detailCalSlotIds },
                    onStart: triggerDetailCalibration,
                    onClose: { showDetailCalSheet = false }
                )
                .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: showDetailCalSheet)
    }

    /// 詳細校正シートで「校正開始」が押された時に呼ばれる。シートは自動で閉じるので、
    /// ここでは即座に runBattlePrepDetailCal を起動する。
    private func triggerDetailCalibration() {
        guard let scene = screenshotImage else {
            statusMessage = "スクショが読み込めていません。"
            return
        }
        DetailCalibrationConfig.slotIds = detailCalSlotIds
        runBattlePrepDetailCal(scene: scene)
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
            if screen == .battlePrep {
                Button("詳細校正") { showDetailCalSheet = true }
                    .buttonStyle(.bordered)
                    .tint(.gray)
            }
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

    /// パーティ選択の実行時探索窓 (上/下マージン)。NakamonCaptureEngine.scanForPartySelect と
    /// 同一の定義で、薄緑表示・スコアテストの両方から使う。
    /// パーティ選択以外の画面では保存マージンの対称窓を返す
    private func partySearchMargins(roi: CalibrationROI) -> (up: Double, down: Double) {
        guard screen == .partySelect else {
            return (roi.searchVMarginRatio, roi.searchVMarginRatio)
        }
        if is16x9Screenshot {
            return (CalibrationDefaults.partyScrollAllowanceVRatio16x9,
                    CalibrationDefaults.partyDownMarginVRatio16x9)
        }
        return (roi.searchVMarginRatio + CalibrationDefaults.partyScrollAllowanceVRatio,
                roi.searchVMarginRatio)
    }

    private func roiOverlay(idx: Int, layout: Layout) -> some View {
        let roi = rois[idx]
        let center = layout.point(forRatio: CGPoint(x: roi.centerXRatio, y: roi.centerYRatio))
        let templateSize = layout.size(forRatio: CGSize(width: roi.widthRatio, height: roi.heightRatio))
        // パーティ選択は実行時判定の窓が上方向へスクロール吸収分だけ非対称に広がるため、
        // 薄緑もそれに同期させる (プロファイルごとの上/下マージンを反映)
        let (upRatio, downRatio) = partySearchMargins(roi: roi)
        let searchCenter = layout.point(forRatio: CGPoint(x: roi.centerXRatio,
                                                          y: roi.centerYRatio - (upRatio - downRatio) / 2))
        let searchSize = layout.size(forRatio: CGSize(
            width: roi.widthRatio + 2 * roi.searchHMarginRatio,
            height: roi.heightRatio + upRatio + downRatio
        ))
        return ZStack {
            // 薄緑塗り (探索範囲)
            Rectangle()
                .fill(Color.green.opacity(0.18))
                .frame(width: searchSize.width, height: searchSize.height)
                .position(searchCenter)
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
        // VS 画面のカスタムが大会用で作られている場合 (= グランプリモード) はラベル表示
        isGrandPrixTemplate = (customKind == .vs) && hasCustomTemplate && GrandPrixMode.isEnabled
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
        case .partySelect: rois = is16x9Screenshot ? CalibrationDefaults.partySelectROIs16x9
                                                   : CalibrationDefaults.partySelectROIs
        case .battlePrep:  rois = [CalibrationDefaults.battlePrepVSROI] + CalibrationDefaults.battlePrepMonsterROIs
        case .win:         rois = [CalibrationDefaults.winROI]
        case .lose:        rois = [CalibrationDefaults.loseROI]
        }
        // 既存カスタムテンプレも削除
        CustomTemplateStore.remove(customKind)
        hasCustomTemplate = false
        // VS をデフォルトに戻す = 大会用でなくなるためグランプリモード解除
        if screen == .battlePrep {
            GrandPrixMode.isEnabled = false
            isGrandPrixTemplate = false
        }
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
            // 16:9 の P3 は外挿位置 (未スクロール画像には写らない) のためスコア計算は無意味。
            // 「—」表示にして誤解を防ぐ
            if screen == .partySelect && is16x9Screenshot && idx == 2 {
                newScores.append(-1)
                continue
            }
            guard let tpl = baseTemplateForScore(roiIndex: idx) else {
                newScores.append(0)
                continue
            }
            // パーティ選択は実行時判定 (scanForPartySelect) と同じ非対称窓でテストする
            let (upRatio, downRatio) = partySearchMargins(roi: roi)
            let cx = Int32(w * roi.centerXRatio)
            let cy = Int32(h * (roi.centerYRatio - (upRatio - downRatio) / 2))
            let hMargin = Int32(w * roi.searchHMarginRatio)
            let vMargin = Int32(h * (upRatio + downRatio) / 2)
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

    /// インポート済みスクショが 16:9 (iPhone SE 系) かどうか
    private var is16x9Screenshot: Bool {
        guard let img = screenshotImage else { return false }
        return CalibrationDefaults.isWide16x9(width: Double(img.size.width),
                                              height: Double(img.size.height))
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
    /// アルゴリズム (per-ROI 近傍探索):
    ///   1. 各デフォルト ROI (P1/P2/P3) の近傍窓内で BASE SELECT を matchTemplate
    ///      - X 窓: cx=0.787 ±0.12 (左半分の磁石 = ネームプレート左隣 を構造的に除外)
    ///      - Y 窓: 各 centerY ±0.065 (隣接パーティと重ならず、上下ツールバーも除外)
    ///   2. 各 ROI の窓内 best をそのまま P1/P2/P3 に割り当て (全画面 NMS は廃止)
    ///   3. 最高スコア位置で screenshot を切り出し、1080-ref サイズに正規化 → SELECT_custom.png
    ///
    /// 旧実装は全画面 NMS top-3 だったが、非フォーカス行のスコア低下時に
    /// 左半分の磁石へ乗っ取られる誤校正 (iPhone15 報告 2026-06-28) があったため、
    /// 探索範囲を各 ROI 近傍に限定して磁石を構造的に排除する方式へ変更した。
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
            // マルチスケール: 各倍率で最良位置を取り、スコア最大のものを採用
            var bestLoc: NakamonMatchLocation? = nil
            for ms in Self.autoCalMicroScales {
                let scaled = self.templateScaledToScene(base, scene: scene, microScale: ms)
                let loc = NakamonWrapper.findBestMatchLocation(inScene: scene, templateImg: scaled)
                if bestLoc == nil || loc.score > bestLoc!.score {
                    bestLoc = loc
                }
            }
            let finalLoc = bestLoc ?? NakamonWrapper.findBestMatchLocation(inScene: scene, templateImg: base)
            DispatchQueue.main.async {
                handleResultAutoCalResult(matchLocation: finalLoc, kind: kind, scene: scene)
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

        // 16:9 (iPhone SE 系) はテンプレ探索を使わず固定レイアウト校正へ。
        // 16:9 の iPhone は SE2/SE3 (750×1334) しか存在せず幾何は決定的なので、
        // NCC 実測で確定した固定座標 (CalibrationData の 16:9 プロファイル参照) が最も確実。
        // 探索式で起きた誤着地は旧定数のズレが原因だったが、探索は磁石テンプレや
        // 枠アニメーションの影響 (角スコアが 0.60〜0.94 で揺れる) を受けるため採らない。
        if CalibrationDefaults.isWide16x9(width: Double(sceneW), height: Double(sceneH)) {
            runPartySelect16x9FixedCal(scene: scene)
            return
        }

        // per-ROI 近傍探索の窓幅 (デフォルト中心基準)。
        // X ±0.12 → 0.67〜0.91: 左半分の磁石を窓外に追い出す。
        //
        // Y は上下非対称 (Android a2b2096 と同値)。ゲーム UI は幅基準スケール +
        // 上寄せ配置のため、基準端末より縦長の画面では枠が画面比で上方向へシフトする
        // (21:9 実測で P3 は ±0.065 窓の外)。上 0.125 / 下 0.044。
        let searchHalfWRatio: CGFloat = 0.12
        let searchUpHRatio: CGFloat = 0.125
        let searchDownHRatio: CGFloat = 0.044
        let defaults = CalibrationDefaults.partySelectROIs
        let searchTargets = defaults

        DispatchQueue.global(qos: .userInitiated).async {
            // 各パーティ ROI の近傍窓内で SELECT を探し、その窓の best をそのまま採用する。
            // 全画面 NMS を廃止したことで、左半分の磁石・上下ツールバーへの誤検出を構造的に排除。
            // 窓内マッチングはマルチスケールで最良位置を取る (battlePrep スロット探索と同方式)。
            var locations: [NakamonMatchLocation] = []
            for def in searchTargets {
                let cx = Int32(sceneW * CGFloat(def.centerXRatio))
                // findSpecificMonsterLocation は中心+サイズ指定 (対称窓) のため、
                // 非対称窓は「中心を上へずらした対称窓」として渡す
                let cy = Int32(sceneH * (CGFloat(def.centerYRatio) - (searchUpHRatio - searchDownHRatio) / 2))
                let w = Int32(sceneW * (searchHalfWRatio * 2))
                let h = Int32(sceneH * (searchUpHRatio + searchDownHRatio))
                var bestLoc: NakamonMatchLocation? = nil
                for ms in Self.autoCalMicroScales {
                    let scaled = self.templateScaledToScene(baseSelect, scene: scene, microScale: ms)
                    let loc = NakamonWrapper.findSpecificMonsterLocation(
                        inRegion: scene,
                        templateImg: scaled,
                        centerX: cx, centerY: cy,
                        width: w, height: h
                    )
                    if bestLoc == nil || loc.score > bestLoc!.score {
                        bestLoc = loc
                    }
                }
                if let bestLoc = bestLoc {
                    locations.append(bestLoc)
                }
            }
            DispatchQueue.main.async {
                handleAutoCalibrationResult(locations: locations,
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
        isGrandPrixTemplate = false

        DispatchQueue.global(qos: .userInitiated).async {
            // --- 1. VS 検出 (テンプレを scene スケール × マルチスケールでリサイズしてマッチング) ---
            //   FM (通常) と MG (大会用) を別々に追跡し、それぞれの最高スコアを持つ。
            //   MG の方が高ければ「大会用 VS をインポートした」= グランプリモード ON と判定する。
            var bestFMOpt: NakamonMatchLocation? = nil
            var bestMGOpt: NakamonMatchLocation? = nil
            for ms in Self.autoCalMicroScales {
                let vsFMScaled = self.templateScaledToScene(vsFM, scene: scene, microScale: ms)
                let locFM = NakamonWrapper.findBestMatchLocation(inScene: scene, templateImg: vsFMScaled)
                if bestFMOpt == nil || locFM.score > bestFMOpt!.score { bestFMOpt = locFM }
                if let vsMG = vsMG {
                    let vsMGScaled = self.templateScaledToScene(vsMG, scene: scene, microScale: ms)
                    let locMG = NakamonWrapper.findBestMatchLocation(inScene: scene, templateImg: vsMGScaled)
                    if bestMGOpt == nil || locMG.score > bestMGOpt!.score { bestMGOpt = locMG }
                }
            }
            // 大会用 (MG) が通常 (FM) より高スコアなら大会 VS と判定。
            let fmScore = bestFMOpt?.score ?? -1
            let mgScore = bestMGOpt?.score ?? -1
            let isTournament = mgScore > fmScore
            let bestVSLoc: NakamonMatchLocation = isTournament ? bestMGOpt! : bestFMOpt!

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
                                              isTournament: isTournament,
                                              slotResults: slotResults,
                                              scene: scene)
            }
        }
    }

    private func handleBattlePrepAutoCalResult(vsLocation: NakamonMatchLocation,
                                               isTournament: Bool,
                                               slotResults: [(ratioX: Double, ratioY: Double, score: Double, id: String)],
                                               scene: UIImage) {
        defer { isAutoCalibrating = false }
        guard vsLocation.score >= 0.4 else {
            statusMessage = String(format: "VS ロゴを検出できませんでした (最高スコア %.3f)", vsLocation.score)
            // 検出失敗時はカスタム未保存 = グランプリモードは無効のまま
            GrandPrixMode.isEnabled = false
            isGrandPrixTemplate = false
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

        // 大会用 VS を校正した = グランプリ記録モードを ON (通常 VS なら OFF)。
        // B 方針: 校正が大会記録モードのスイッチを兼ねる。
        GrandPrixMode.isEnabled = isTournament
        isGrandPrixTemplate = isTournament

        recomputeScores()
        let slotSummary = slotResults.enumerated().map { idx, r in
            let label = idx < 4 ? "味方\(idx)" : "敵\(idx - 4)"
            return String(format: "%@:%@ %.2f", label, r.id, r.score)
        }.joined(separator: " / ")
        let modeNote = isTournament ? "\n🎖 グランプリ記録モード ON (大会用 VS を検出)" : ""
        statusMessage = String(format: "自動校正完了\nVS Score %.3f%@\n%@\nこの位置で決定 を押すと保存されます。",
                               vsLocation.score, modeNote, slotSummary)
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
    /// 詳細校正用の自動校正フロー。
    /// 1. VS 検出 (通常 auto-cal と同じくマルチスケール検索)
    /// 2. 各スロットでユーザー指定モンスター 1 体を 1-vs-1 で検索 (findSpecificMonsterLocation)
    /// 3. 磁石テンプレ干渉なしで真モンスター位置を確定し、ROI 中心を更新
    private func runBattlePrepDetailCal(scene: UIImage) {
        guard let vsFM = loadTemplate("VS_FM") else {
            statusMessage = "BASE VS_FM テンプレが見つかりません。"
            return
        }
        let vsMG = loadTemplate("VS_MG")
        let slotIds = DetailCalibrationConfig.slotIds
        // モンスターテンプレ事前ロード (8 スロットぶん、id→UIImage 辞書)
        var monsterImages: [String: UIImage] = [:]
        for case let id? in slotIds {
            if monsterImages[id] == nil,
               let path = Bundle.main.path(forResource: id, ofType: "png", inDirectory: "templates"),
               let img = UIImage(contentsOfFile: path) {
                monsterImages[id] = img
            }
        }

        isAutoCalibrating = true
        CustomTemplateStore.remove(.vs)
        hasCustomTemplate = false
        isGrandPrixTemplate = false

        DispatchQueue.global(qos: .userInitiated).async {
            // --- 1. VS 検出 (マルチスケール、FM/MG 別追跡で大会判定) ---
            var bestFMOpt: NakamonMatchLocation? = nil
            var bestMGOpt: NakamonMatchLocation? = nil
            for ms in Self.autoCalMicroScales {
                let vsFMScaled = self.templateScaledToScene(vsFM, scene: scene, microScale: ms)
                let locFM = NakamonWrapper.findBestMatchLocation(inScene: scene, templateImg: vsFMScaled)
                if bestFMOpt == nil || locFM.score > bestFMOpt!.score { bestFMOpt = locFM }
                if let vsMG = vsMG {
                    let vsMGScaled = self.templateScaledToScene(vsMG, scene: scene, microScale: ms)
                    let locMG = NakamonWrapper.findBestMatchLocation(inScene: scene, templateImg: vsMGScaled)
                    if bestMGOpt == nil || locMG.score > bestMGOpt!.score { bestMGOpt = locMG }
                }
            }
            let isTournament = (bestMGOpt?.score ?? -1) > (bestFMOpt?.score ?? -1)
            let bestVSLoc = isTournament ? bestMGOpt! : bestFMOpt!

            // --- 2. 各スロットを 1-vs-1 で検索 ---
            // 探索範囲は通常 auto-cal と同じ (X ±100, Y ±350 ぶん) — 詳細校正でも広めに取る
            let slotSearchW = 200.0 / 1080.0
            let slotSearchH = 700.0 / 2364.0
            let sceneW = scene.size.width
            let sceneH = scene.size.height
            // テンプレも各スロットでマルチスケール探索
            var slotResults: [(ratioX: Double, ratioY: Double, score: Double, id: String)] = []
            for (slotIdx, defROI) in CalibrationDefaults.battlePrepMonsterROIs.enumerated() {
                guard let monsterId = slotIds[slotIdx],
                      let baseTpl = monsterImages[monsterId] else {
                    slotResults.append((defROI.centerXRatio, defROI.centerYRatio, 0, "?"))
                    continue
                }
                let cx = Int32(sceneW * defROI.centerXRatio)
                let cy = Int32(sceneH * defROI.centerYRatio)
                let w = Int32(sceneW * slotSearchW)
                let h = Int32(sceneH * slotSearchH)
                // マルチスケールで最良位置
                var bestLoc: NakamonMatchLocation? = nil
                for ms in Self.autoCalMicroScales {
                    let scaledTpl = self.templateScaledToScene(baseTpl, scene: scene, microScale: ms)
                    let loc = NakamonWrapper.findSpecificMonsterLocation(
                        inRegion: scene,
                        templateImg: scaledTpl,
                        centerX: cx, centerY: cy,
                        width: w, height: h
                    )
                    if bestLoc == nil || loc.score > bestLoc!.score {
                        bestLoc = loc
                    }
                }
                let loc = bestLoc!
                let rx = Double(loc.centerX) / Double(sceneW)
                let ry = Double(loc.centerY) / Double(sceneH)
                slotResults.append((rx, ry, loc.score, monsterId))
            }

            DispatchQueue.main.async {
                handleBattlePrepAutoCalResult(vsLocation: bestVSLoc,
                                              isTournament: isTournament,
                                              slotResults: slotResults,
                                              scene: scene)
            }
        }
    }

    /// テンプレを縮小しないと VS/WIN/LOSE 等の大きめロゴが検出できない。
    /// microScale で base スケール (scene.width / 1080) にさらに係数をかけられる。
    /// auto-cal で複数の倍率を試して最高スコアを取るために使う。
    private func templateScaledToScene(_ image: UIImage, scene: UIImage, microScale: CGFloat = 1.0) -> UIImage {
        let scale = (scene.size.width / 1080.0) * microScale
        let target = CGSize(width: image.size.width * scale,
                            height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        let renderer = UIGraphicsImageRenderer(size: target, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }

    /// auto-cal 用のマイクロスケール群。
    /// ±10% (Android 標準) より広めの ±20% を採用し、iPhone SE3 等の
    /// アスペクト比が他機種と大きく異なる端末でも適切な倍率を捕捉できるようにする。
    /// 中央の 1.0 が含まれるため、Pixel 系・modern iPhone では従来同等のスコアになる。
    private static let autoCalMicroScales: [CGFloat] = [0.85, 0.95, 1.0, 1.10, 1.20]

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

    // MARK: - 16:9 (iPhone SE 系) 固定レイアウト校正

    /// フォーカス枠 (水色) の判定用: ROI 中心のテンプレ相当領域における水色ピクセル率。
    /// SE3 コーパス実測: P1 フォーカス画像の P1 位置 = 28〜34%、非フォーカス位置 = 0.0%
    private func cyanRatio(scene: UIImage, roi: CalibrationROI) -> Double {
        guard let cg = scene.cgImage else { return 0 }
        let sW = Double(cg.width), sH = Double(cg.height)
        let w = Int(roi.widthRatio * sW), h = Int(roi.heightRatio * sH)
        let x = Int(roi.centerXRatio * sW) - w / 2
        let y = Int(roi.centerYRatio * sH) - h / 2
        guard w > 0, h > 0, x >= 0, y >= 0, x + w <= Int(sW), y + h <= Int(sH),
              let crop = cg.cropping(to: CGRect(x: x, y: y, width: w, height: h)) else { return 0 }
        var buf = [UInt8](repeating: 0, count: w * h * 4)
        guard let ctx = CGContext(data: &buf, width: w, height: h, bitsPerComponent: 8,
                                  bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return 0 }
        ctx.draw(crop, in: CGRect(x: 0, y: 0, width: w, height: h))
        var cyan = 0
        for p in 0..<(w * h) {
            let r = Int(buf[p * 4]), g = Int(buf[p * 4 + 1]), b = Int(buf[p * 4 + 2])
            if b > 140 && b > r + 40 && g > 120 { cyan += 1 }
        }
        return Double(cyan) / Double(w * h)
    }

    /// 16:9 用の固定レイアウト校正。テンプレ探索を行わず、NCC 実測済みの固定座標
    /// (x 0.768、P1 0.4940 / P2 0.6904 / P3 0.8868) をそのまま採用する。
    /// カスタムテンプレは P1/P2 固定位置のうち水色率が高い方 (=フォーカス枠) から切り出す。
    /// ゲート2段: ①どちらにも水色がなければ不成立 (P3 フォーカスや非選択画像)
    /// ②フォーカス位置の直上1窓にも水色があれば不成立 (スクロール済み画像 —
    /// 枠の右辺の縦線が固定列を貫くため①だけでは検出できない。コーパス実測:
    /// 未スクロール 0% / 16px スクロール 16% / 明確なスクロール 30% 前後)。
    private func runPartySelect16x9FixedCal(scene: UIImage) {
        defer { isAutoCalibrating = false }
        let defaults = CalibrationDefaults.partySelectROIs16x9
        let cyanP1 = cyanRatio(scene: scene, roi: defaults[0])
        let cyanP2 = cyanRatio(scene: scene, roi: defaults[1])
        let focusedIdx = cyanP1 >= cyanP2 ? 0 : 1
        let focusedCyan = max(cyanP1, cyanP2)
        guard focusedCyan >= 0.10 else {
            statusMessage = String(format: "フォーカス枠 (水色) を検出できませんでした (P1 %.0f%% / P2 %.0f%%)。\nパーティ1か2を選択した状態・スクロールなしの全画面スクショを使ってください。",
                                   cyanP1 * 100, cyanP2 * 100)
            return
        }
        var aboveROI = defaults[focusedIdx]
        aboveROI.centerYRatio -= aboveROI.heightRatio
        let cyanAbove = cyanRatio(scene: scene, roi: aboveROI)
        guard cyanAbove < 0.10 else {
            statusMessage = String(format: "スクロールした状態の画像のようです (P%d 直上に水色 %.0f%%)。\nパーティ一覧をスクロールしていない状態の全画面スクショを使ってください。",
                                   focusedIdx + 1, cyanAbove * 100)
            return
        }
        rois = defaults
        if let custom = generateCustomTemplate(scene: scene,
                                               matchCenterXRatio: defaults[focusedIdx].centerXRatio,
                                               matchCenterYRatio: defaults[focusedIdx].centerYRatio,
                                               templateWidthRatio: defaults[focusedIdx].widthRatio,
                                               templateHeightRatio: defaults[focusedIdx].heightRatio) {
            CustomTemplateStore.save(custom, as: .select)
            hasCustomTemplate = true
        }
        recomputeScores()
        statusMessage = String(format: "自動校正完了 (16:9 固定レイアウト)\nフォーカス: P%d (水色率 %.0f%%)、P3 外挿\nこの位置で決定 を押すと保存されます。",
                               focusedIdx + 1, focusedCyan * 100)
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

        // 1080-ref サイズに正規化 (本番マッチング時に他の BASE と同じスケール扱いに)。
        // 高さは 2364 基準でなく切り出しのコンテンツ縦横比から導出する:
        // 実行時のテンプレスケールは幅基準 (frameWidth/1080) のため、高さを 2364 固定で
        // 正規化すると 19.7:9 以外のアスペクト (特に 16:9) でテンプレが縦に歪む
        let targetW = templateWidthRatio * 1080.0
        let targetH = cropRect.width > 0 ? targetW * (cropRect.height / cropRect.width)
                                         : templateHeightRatio * 2364.0
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
