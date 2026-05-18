import SwiftUI
import NakamonREC_Shared

/// パーティ集計の各行で使う「1 日ごとの勝率」グラフ。
/// - records を yyyy-MM-dd で集約し、各日の勝率を 1 点として描画
/// - 6 日分を一度に表示。それを超える場合は横スクロールで遡れる
/// - Y 軸固定 0~100%
/// - タップで点選択 → 白点線 + 白点 + `XX.X%: NMatches (MM/DD)` ツールチップ
struct DailyTrendChart: View {
    let records: [BattleRecord]

    @State private var selectedDateKey: String? = nil
    @State private var pinnedScrollX: CGFloat? = nil
    @GestureState private var dragDelta: CGFloat = 0

    private let visibleCount: Int = 6
    private let leftPad: CGFloat = 26
    private let topPad: CGFloat = 8
    private let rightPad: CGFloat = 4
    private let bottomPad: CGFloat = 14    // X 軸日付ラベル分の余白

    struct DailyPoint: Identifiable, Equatable {
        let id = UUID()
        let dateKey: String         // yyyy-MM-dd
        let displayDate: String     // MM/DD
        let winRate: Double
        let matches: Int
    }

    private var dailyPoints: [DailyPoint] {
        let byDate = Dictionary(grouping: records, by: { String($0.timestamp.prefix(10)) })
        return byDate.keys.sorted().map { dateKey in
            let recs = byDate[dateKey] ?? []
            let wins = recs.filter { $0.result == "WIN" }.count
            let rate = recs.isEmpty ? 0 : Double(wins) / Double(recs.count) * 100
            let parts = dateKey.split(separator: "-")
            let display = parts.count >= 3 ? "\(parts[1])/\(parts[2])" : dateKey
            return DailyPoint(dateKey: dateKey, displayDate: display, winRate: rate, matches: recs.count)
        }
    }

    var body: some View {
        GeometryReader { geo in
            let pts = dailyPoints
            let layout = Layout(
                size: geo.size,
                pointsCount: pts.count,
                visibleCount: visibleCount,
                leftPad: leftPad, topPad: topPad,
                rightPad: rightPad, bottomPad: bottomPad
            )
            let baseScroll = pinnedScrollX ?? layout.maxScroll
            let currentScroll = max(0, min(layout.maxScroll, baseScroll - dragDelta))
            let firstIdx = max(0, Int(currentScroll / layout.stepX))
            let lastIdx = min(pts.count - 1, firstIdx + visibleCount + 1)
            let visible = (pts.count > 0 && firstIdx <= lastIdx) ? Array(pts[firstIdx...lastIdx]) : []

            Canvas { ctx, size in
                var mctx = ctx
                drawChart(
                    ctx: &mctx, size: size, layout: layout,
                    allPoints: pts, visible: visible, firstIdx: firstIdx,
                    currentScroll: currentScroll
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
                handleTap(at: location, layout: layout, pts: pts, currentScroll: currentScroll)
            }
        }
    }

    private struct Layout {
        let size: CGSize
        let pointsCount: Int
        let visibleCount: Int
        let leftPad: CGFloat
        let topPad: CGFloat
        let rightPad: CGFloat
        let bottomPad: CGFloat

        var innerW: CGFloat { size.width - leftPad - rightPad }
        var innerH: CGFloat { size.height - topPad - bottomPad }
        var stepX: CGFloat { innerW / CGFloat(max(1, visibleCount - 1)) }
        var totalSpan: CGFloat { CGFloat(max(0, pointsCount - 1)) * stepX }
        var maxScroll: CGFloat { max(0, totalSpan - innerW) }
    }

    private func handleTap(at location: CGPoint, layout: Layout, pts: [DailyPoint], currentScroll: CGFloat) {
        let plotX = location.x - leftPad
        guard plotX >= 0, plotX <= layout.innerW else { return }
        let xInData = plotX + currentScroll
        let idx = Int((xInData / layout.stepX).rounded())
        guard idx >= 0, idx < pts.count else { return }
        selectedDateKey = pts[idx].dateKey
    }

    private func drawChart(ctx: inout GraphicsContext,
                           size: CGSize,
                           layout: Layout,
                           allPoints: [DailyPoint],
                           visible: [DailyPoint],
                           firstIdx: Int,
                           currentScroll: CGFloat) {
        let plotLeft = leftPad
        let plotRight = leftPad + layout.innerW
        let plotTop = topPad
        let plotBottom = topPad + layout.innerH

        let yMin: Double = 0
        let yMax: Double = 100

        // --- グリッド ---
        let gridColor = GraphicsContext.Shading.color(.gray.opacity(0.25))
        for frac in [0.0, 0.5, 1.0] {
            let y = plotTop + CGFloat(frac) * layout.innerH
            var p = Path()
            p.move(to: CGPoint(x: plotLeft, y: y))
            p.addLine(to: CGPoint(x: plotRight, y: y))
            ctx.stroke(p, with: gridColor, lineWidth: 0.5)
        }
        for frac in [0.0, 0.2, 0.4, 0.6, 0.8, 1.0] {
            let x = plotLeft + CGFloat(frac) * layout.innerW
            var p = Path()
            p.move(to: CGPoint(x: x, y: plotTop))
            p.addLine(to: CGPoint(x: x, y: plotBottom))
            ctx.stroke(p, with: gridColor, lineWidth: 0.5)
        }

        // --- Y 軸 % ラベル ---
        for frac in [0.0, 0.5, 1.0] {
            let yVal = yMin + (yMax - yMin) * (1.0 - frac)
            let y = plotTop + CGFloat(frac) * layout.innerH
            ctx.draw(
                Text("\(Int(yVal))%").font(.system(size: 8)).foregroundStyle(.gray),
                at: CGPoint(x: plotLeft - 2, y: y), anchor: .trailing
            )
        }

        guard visible.count >= 1 else { return }

        func xy(idx: Int, rate: Double) -> CGPoint {
            let x = plotLeft + CGFloat(idx) * layout.stepX - currentScroll
            let y = plotTop + (1 - CGFloat((rate - yMin) / (yMax - yMin))) * layout.innerH
            return CGPoint(x: x, y: y)
        }

        // --- X 軸 日付ラベル (アンクリップで描画) ---
        for (offset, pt) in visible.enumerated() {
            let absIdx = firstIdx + offset
            let x = plotLeft + CGFloat(absIdx) * layout.stepX - currentScroll
            if x >= plotLeft - 2, x <= plotRight + 2 {
                ctx.draw(
                    Text(pt.displayDate).font(.system(size: 7)).foregroundStyle(.gray),
                    at: CGPoint(x: x, y: plotBottom + 7), anchor: .center
                )
            }
        }

        // --- Area + Line (クリップ版コンテキストで描画) ---
        var lineCtx = ctx
        lineCtx.clip(to: Path(CGRect(x: plotLeft, y: plotTop, width: layout.innerW, height: layout.innerH)))
        if visible.count >= 2 {
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
                let lastIdx = firstIdx + visible.count - 1
                let lastX = plotLeft + CGFloat(lastIdx) * layout.stepX - currentScroll
                areaPath.addLine(to: CGPoint(x: lastX, y: plotBottom))
                areaPath.closeSubpath()
            }
            lineCtx.fill(areaPath, with: .color(Color.recCoral.opacity(0.25)))
            lineCtx.stroke(linePath, with: .color(Color.recCoral), lineWidth: 1.2)
        } else if let only = visible.first {
            let absIdx = firstIdx
            let p = xy(idx: absIdx, rate: only.winRate)
            let r = CGRect(x: p.x - 2.5, y: p.y - 2.5, width: 5, height: 5)
            lineCtx.fill(Path(ellipseIn: r), with: .color(Color.recCoral))
        }

        // --- ツールチップ: 黒背景なし、ドット隣に値ラベル ---
        let effectiveDateKey = selectedDateKey ?? allPoints.last?.dateKey
        if let dk = effectiveDateKey,
           let absIdx = allPoints.firstIndex(where: { $0.dateKey == dk }) {
            let pt = allPoints[absIdx]
            let p = xy(idx: absIdx, rate: pt.winRate)
            if p.x >= plotLeft - 5, p.x <= plotRight + 5 {
                // 白点線
                var dash = Path()
                dash.move(to: CGPoint(x: p.x, y: plotTop))
                dash.addLine(to: CGPoint(x: p.x, y: plotBottom))
                ctx.stroke(dash, with: .color(.white.opacity(0.7)),
                           style: StrokeStyle(lineWidth: 1, dash: [3]))
                // 白点
                let r = CGRect(x: p.x - 3, y: p.y - 3, width: 6, height: 6)
                ctx.fill(Path(ellipseIn: r), with: .color(.white))
                // recCoral 値ラベル (黒シャドウで線と分離して可読性確保)
                let label = String(format: "%.1f%%", pt.winRate)
                let text = Text(label)
                    .font(.system(size: 10, weight: .bold).monospacedDigit())
                    .foregroundStyle(Color.recCoral)
                var labelCtx = ctx
                labelCtx.addFilter(.shadow(color: .black.opacity(0.95), radius: 1.5))
                let resolved = labelCtx.resolve(text)
                let textSize = resolved.measure(in: CGSize(width: 100, height: 20))
                let placeRight = (p.x + textSize.width + 8) <= plotRight
                let lx = placeRight ? p.x + 5 : p.x - 5
                let anchor: UnitPoint = placeRight ? .leading : .trailing
                labelCtx.draw(resolved, at: CGPoint(x: lx, y: p.y), anchor: anchor)
            }
        }
    }
}
