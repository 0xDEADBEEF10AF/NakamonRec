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
        return String(format: "%.3f", scores[idx])
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
        guard let scene = screenshotImage, let tpl = baseTemplate() else {
            scores = Array(repeating: 0, count: rois.count)
            return
        }
        var newScores: [Double] = []
        let w = scene.size.width
        let h = scene.size.height
        for roi in rois {
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
        guard screen == .partySelect else {
            statusMessage = "この画面の自動校正は C2/C3 で実装予定です。"
            return
        }
        guard let scene = screenshotImage else {
            statusMessage = "スクショが読み込めていません。"
            return
        }
        guard let baseSelect = baseTemplate() else {
            statusMessage = "BASE SELECT テンプレが見つかりません。"
            return
        }
        isAutoCalibrating = true
        CustomTemplateStore.remove(.select)
        hasCustomTemplate = false

        // 上下の SELECT 同士は約 400px (1080-ref Y で 0.17 ぶん) 離れている。
        // 抑制範囲はその半分以下、かつテンプレ自身の大きさより十分大きく取る
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
