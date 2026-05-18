import SwiftUI

/// 戦績画面の RECENT TREND グラフ (Android `WinRateGraphView.kt` の iOS 移植版)
/// - SwiftUI Charts ではなく Canvas + DragGesture で実装し、1700+ 戦の大量データでも軽快にスライドできる
/// - Visible window (20 戦) 内の点だけを描画
/// - 1080-ref のような上限なし: trend.count に応じて totalSpan を動的計算
struct RecentTrendChart: View {
    let trend: [BattleHistoryView.TrendPoint]
    @Binding var selectedBattleNum: Int?

    /// 永続スクロール位置。nil = 最新点 (右端) に追従
    @State private var pinnedScrollX: CGFloat? = nil
    /// ドラッグ中のリアルタイム位置オフセット
    @GestureState private var dragDelta: CGFloat = 0

    private let visibleCount: Int = 20
    private let leftPad: CGFloat = 26
    private let topPad: CGFloat = 12
    private let rightPad: CGFloat = 4
    private let bottomPad: CGFloat = 4

    var body: some View {
        GeometryReader { geo in
            let layout = Layout(
                size: geo.size,
                trendCount: trend.count,
                visibleCount: visibleCount,
                leftPad: leftPad,
                topPad: topPad,
                rightPad: rightPad,
                bottomPad: bottomPad
            )
            let baseScroll = pinnedScrollX ?? layout.maxScroll
            let currentScroll = max(0, min(layout.maxScroll, baseScroll - dragDelta))

            // 可視範囲のデータと Y レンジを計算
            let firstIdx = max(0, Int(currentScroll / layout.stepX))
            let lastIdx = min(trend.count - 1, firstIdx + visibleCount + 1)
            let visible = (trend.count > 0 && firstIdx <= lastIdx) ? Array(trend[firstIdx...lastIdx]) : []
            let yRange = computeYRange(from: visible)

            Canvas { ctx, size in
                var mctx = ctx
                drawChart(
                    ctx: &mctx,
                    size: size,
                    layout: layout,
                    visible: visible,
                    firstIdx: firstIdx,
                    currentScroll: currentScroll,
                    yMin: yRange.lowerBound,
                    yMax: yRange.upperBound
                )
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 1)
                    .updating($dragDelta) { val, state, _ in
                        state = val.translation.width
                    }
                    .onEnded { val in
                        let base = pinnedScrollX ?? layout.maxScroll
                        pinnedScrollX = max(0, min(layout.maxScroll, base - val.translation.width))
                    }
            )
            .onTapGesture { location in
                handleTap(at: location, layout: layout, currentScroll: currentScroll)
            }
        }
    }

    // MARK: - Layout

    private struct Layout {
        let size: CGSize
        let trendCount: Int
        let visibleCount: Int
        let leftPad: CGFloat
        let topPad: CGFloat
        let rightPad: CGFloat
        let bottomPad: CGFloat

        var innerW: CGFloat { size.width - leftPad - rightPad }
        var innerH: CGFloat { size.height - topPad - bottomPad }
        var stepX: CGFloat { innerW / CGFloat(max(1, visibleCount - 1)) }
        var totalSpan: CGFloat { CGFloat(max(0, trendCount - 1)) * stepX }
        var maxScroll: CGFloat { max(0, totalSpan - innerW) }
    }

    private func computeYRange(from points: [BattleHistoryView.TrendPoint]) -> ClosedRange<Double> {
        let rates = points.map(\.winRate)
        guard let mn = rates.min(), let mx = rates.max() else { return 0...100 }
        let yMin = max(0, floor((mn - 10) / 10) * 10)
        let yMax = min(100, ceil((mx + 10) / 10) * 10)
        return yMin < yMax ? yMin...yMax : 0...100
    }

    // MARK: - Tap handling

    private func handleTap(at location: CGPoint, layout: Layout, currentScroll: CGFloat) {
        let xInPlot = location.x - leftPad
        guard xInPlot >= 0, xInPlot <= layout.innerW else { return }
        let xInData = xInPlot + currentScroll
        let idx = Int((xInData / layout.stepX).rounded())
        guard idx >= 0, idx < trend.count else { return }
        selectedBattleNum = trend[idx].battleNum
    }

    // MARK: - Drawing

    private func drawChart(ctx: inout GraphicsContext,
                           size: CGSize,
                           layout: Layout,
                           visible: [BattleHistoryView.TrendPoint],
                           firstIdx: Int,
                           currentScroll: CGFloat,
                           yMin: Double,
                           yMax: Double) {
        let plotLeft = leftPad
        let plotRight = leftPad + layout.innerW
        let plotTop = topPad
        let plotBottom = topPad + layout.innerH

        // --- グリッド線 (3 段) ---
        let gridColor = GraphicsContext.Shading.color(.gray.opacity(0.25))
        for frac in [0.0, 0.5, 1.0] {
            let y = plotTop + CGFloat(frac) * layout.innerH
            var p = Path()
            p.move(to: CGPoint(x: plotLeft, y: y))
            p.addLine(to: CGPoint(x: plotRight, y: y))
            ctx.stroke(p, with: gridColor, lineWidth: 0.5)
        }
        // 縦線 5 本 (左から 0, 25%, 50%, 75%, 100% の位置)
        for frac in [0.0, 0.25, 0.5, 0.75, 1.0] {
            let x = plotLeft + CGFloat(frac) * layout.innerW
            var p = Path()
            p.move(to: CGPoint(x: x, y: plotTop))
            p.addLine(to: CGPoint(x: x, y: plotBottom))
            ctx.stroke(p, with: gridColor, lineWidth: 0.5)
        }

        // --- Y 軸 % ラベル ---
        for frac in [0.0, 0.5, 1.0] {
            let yVal = yMin + (yMax - yMin) * (1.0 - frac) // 上=yMax 下=yMin
            let y = plotTop + CGFloat(frac) * layout.innerH
            let text = Text("\(Int(yVal))%")
                .font(.system(size: 8))
                .foregroundStyle(.gray)
            ctx.draw(text, at: CGPoint(x: plotLeft - 2, y: y), anchor: .trailing)
        }

        guard !visible.isEmpty, yMax > yMin else { return }

        // --- Area + Line ---
        func xy(idx: Int, rate: Double) -> CGPoint {
            let x = plotLeft + CGFloat(idx) * layout.stepX - currentScroll
            let y = plotTop + (1 - CGFloat((rate - yMin) / (yMax - yMin))) * layout.innerH
            return CGPoint(x: x, y: y)
        }
        var linePath = Path()
        var areaPath = Path()
        for (offset, pt) in visible.enumerated() {
            let absIdx = firstIdx + offset
            let p = xy(idx: absIdx, rate: pt.winRate)
            if offset == 0 {
                linePath.move(to: p)
                areaPath.move(to: CGPoint(x: p.x, y: plotBottom))
                areaPath.addLine(to: p)
            } else {
                linePath.addLine(to: p)
                areaPath.addLine(to: p)
            }
        }
        if let _ = visible.last {
            let lastAbsIdx = firstIdx + visible.count - 1
            let lastX = plotLeft + CGFloat(lastAbsIdx) * layout.stepX - currentScroll
            areaPath.addLine(to: CGPoint(x: lastX, y: plotBottom))
            areaPath.closeSubpath()
        }

        // クリップ: 描画は plot 領域内のみ
        ctx.clip(to: Path(CGRect(x: plotLeft, y: plotTop, width: layout.innerW, height: layout.innerH)))
        ctx.fill(areaPath, with: .color(Color.recCoral.opacity(0.25)))
        ctx.stroke(linePath, with: .color(Color.recCoral), lineWidth: 1.5)

        // --- ツールチップ (デフォルトは最新点) ---
        let effectiveBattleNum = selectedBattleNum ?? trend.last?.battleNum
        if let battleNum = effectiveBattleNum,
           let pt = trend.first(where: { $0.battleNum == battleNum }) {
            let absIdx = battleNum - 1
            let p = xy(idx: absIdx, rate: pt.winRate)
            // 可視範囲内のときのみ描画
            if p.x >= plotLeft - 5, p.x <= plotRight + 5 {
                // 白点線
                var dash = Path()
                dash.move(to: CGPoint(x: p.x, y: plotTop))
                dash.addLine(to: CGPoint(x: p.x, y: plotBottom))
                ctx.stroke(dash, with: .color(.white.opacity(0.7)),
                           style: StrokeStyle(lineWidth: 1, dash: [3]))
                // 白ポイント
                let pointRect = CGRect(x: p.x - 3, y: p.y - 3, width: 6, height: 6)
                ctx.fill(Path(ellipseIn: pointRect), with: .color(.white))
                // ツールチップテキスト + 背景
                let label = String(format: "%.1f%%: %dMatches", pt.winRate, pt.battleNum)
                let text = Text(label)
                    .font(.system(size: 9, weight: .bold).monospacedDigit())
                    .foregroundStyle(.white)
                let resolved = ctx.resolve(text)
                let textSize = resolved.measure(in: CGSize(width: 200, height: 30))
                let tipCenter = CGPoint(x: p.x, y: max(plotTop + 8, p.y - 10))
                var bgX = tipCenter.x - textSize.width / 2 - 3
                var bgY = tipCenter.y - textSize.height / 2 - 1
                // 端でクランプ
                bgX = max(plotLeft, min(plotRight - textSize.width - 6, bgX))
                bgY = max(plotTop, bgY)
                let bgRect = CGRect(x: bgX, y: bgY, width: textSize.width + 6, height: textSize.height + 2)
                ctx.fill(Path(roundedRect: bgRect, cornerRadius: 3),
                         with: .color(.black.opacity(0.85)))
                ctx.draw(resolved,
                         at: CGPoint(x: bgX + (textSize.width + 6) / 2,
                                     y: bgY + (textSize.height + 2) / 2),
                         anchor: .center)
            }
        }
    }
}
