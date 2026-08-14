import SwiftUI
import NakamonREC_Shared

/// モンスター集計画面の各行で使う「1 日ごとの勝率 + 出現率」2 系統グラフ。
/// - 勝率 (recCoral): そのモンスターが敵にいた試合での自分の勝率 (1 日単位)
/// - 出現率 (sideEnemy 水色): その日の全戦中、このモンスターが敵にいた割合
/// - 6 日表示、横スクロール可能、Y 軸固定 0-100%
/// - タップで 2 プロット同時にハイライト + 両値のツールチップ
struct MonsterDailyTrendChart: View {
    let filteredRecords: [BattleRecord]   // 集計対象の全レコード (フィルタ後)
    let monsterID: String                  // 着目するモンスター

    @State private var selectedDateKey: String? = nil
    @State private var pinnedScrollX: CGFloat? = nil
    @GestureState private var dragDelta: CGFloat = 0

    private let visibleCount: Int = 6
    private let leftPad: CGFloat = 26
    private let topPad: CGFloat = 8
    private let rightPad: CGFloat = 4
    private let bottomPad: CGFloat = 14   // X 軸日付ラベル分

    struct DailyPoint: Identifiable, Equatable {
        let id = UUID()
        let dateKey: String
        let display: String
        let totalBattles: Int
        let encounters: Int
        let wins: Int
        var encounterRate: Double {
            totalBattles > 0 ? Double(encounters) / Double(totalBattles) * 100 : 0
        }
        var winRate: Double {
            encounters > 0 ? Double(wins) / Double(encounters) * 100 : 0
        }
    }

    private var dailyPoints: [DailyPoint] {
        let byDate = Dictionary(grouping: filteredRecords, by: { String($0.timestamp.prefix(10)) })
        return byDate.keys.sorted().map { dateKey in
            let recs = byDate[dateKey] ?? []
            let encountered = recs.filter { rec in
                rec.enemyParty.contains(monsterID)
            }
            let wins = encountered.filter { $0.result == "WIN" }.count
            let parts = dateKey.split(separator: "-")
            let display = parts.count >= 3 ? "\(parts[1])/\(parts[2])" : dateKey
            return DailyPoint(
                dateKey: dateKey, display: display,
                totalBattles: recs.count,
                encounters: encountered.count,
                wins: wins
            )
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

        // グリッド
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

        // Y 軸ラベル
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

        // --- X 軸 日付ラベル (アンクリップ) ---
        for (offset, pt) in visible.enumerated() {
            let absIdx = firstIdx + offset
            let x = plotLeft + CGFloat(absIdx) * layout.stepX - currentScroll
            if x >= plotLeft - 2, x <= plotRight + 2 {
                ctx.draw(
                    Text(pt.display).font(.system(size: 7)).foregroundStyle(.gray),
                    at: CGPoint(x: x, y: plotBottom + 7), anchor: .center
                )
            }
        }

        // --- 線描画はクリップ版で ---
        var lineCtx = ctx
        lineCtx.clip(to: Path(CGRect(x: plotLeft, y: plotTop, width: layout.innerW, height: layout.innerH)))

        // 出現率 (水色) を背面に
        if visible.count >= 2 {
            var encLine = Path()
            for (offset, pt) in visible.enumerated() {
                let absIdx = firstIdx + offset
                let p = xy(idx: absIdx, rate: pt.encounterRate)
                if offset == 0 { encLine.move(to: p) } else { encLine.addLine(to: p) }
            }
            lineCtx.stroke(encLine, with: .color(Color.sideEnemy), lineWidth: 1.3)
        }

        // 勝率 (ピンク) を前面に。Area 塗り無し
        if visible.count >= 2 {
            var winLine = Path()
            for (offset, pt) in visible.enumerated() {
                let absIdx = firstIdx + offset
                let p = xy(idx: absIdx, rate: pt.winRate)
                if offset == 0 { winLine.move(to: p) } else { winLine.addLine(to: p) }
            }
            lineCtx.stroke(winLine, with: .color(Color.recCoral), lineWidth: 1.5)
        }

        // --- 選択 (デフォルト = 最新日) ---
        let effectiveDateKey = selectedDateKey ?? allPoints.last?.dateKey
        if let dk = effectiveDateKey,
           let absIdx = allPoints.firstIndex(where: { $0.dateKey == dk }) {
            let pt = allPoints[absIdx]
            let xPos = plotLeft + CGFloat(absIdx) * layout.stepX - currentScroll
            if xPos >= plotLeft - 5, xPos <= plotRight + 5 {
                // 白点線
                var dash = Path()
                dash.move(to: CGPoint(x: xPos, y: plotTop))
                dash.addLine(to: CGPoint(x: xPos, y: plotBottom))
                ctx.stroke(dash, with: .color(.white.opacity(0.7)),
                           style: StrokeStyle(lineWidth: 1, dash: [3]))
                let winY = plotTop + (1 - CGFloat(pt.winRate / 100)) * layout.innerH
                let encY = plotTop + (1 - CGFloat(pt.encounterRate / 100)) * layout.innerH
                // 白点 × 2
                ctx.fill(Path(ellipseIn: CGRect(x: xPos - 3, y: winY - 3, width: 6, height: 6)),
                         with: .color(.white))
                ctx.fill(Path(ellipseIn: CGRect(x: xPos - 3, y: encY - 3, width: 6, height: 6)),
                         with: .color(.white))

                // 値ラベルは黒シャドウで線と分離して可読性確保
                var labelCtx = ctx
                labelCtx.addFilter(.shadow(color: .black.opacity(0.95), radius: 1.5))

                // 勝率 値ラベル (recCoral)
                let winText = Text(RateFormat.percent(pt.winRate))
                    .font(.system(size: 10, weight: .bold).monospacedDigit())
                    .foregroundStyle(Color.recCoral)
                let winResolved = labelCtx.resolve(winText)
                let winSize = winResolved.measure(in: CGSize(width: 80, height: 16))
                let winPlaceRight = (xPos + winSize.width + 8) <= plotRight
                let winLX = winPlaceRight ? xPos + 5 : xPos - 5
                let winAnchor: UnitPoint = winPlaceRight ? .leading : .trailing
                labelCtx.draw(winResolved, at: CGPoint(x: winLX, y: winY), anchor: winAnchor)

                // 出現率 値ラベル (sideEnemy)
                let encText = Text(RateFormat.percent(pt.encounterRate))
                    .font(.system(size: 10, weight: .bold).monospacedDigit())
                    .foregroundStyle(Color.sideEnemy)
                let encResolved = labelCtx.resolve(encText)
                let encSize = encResolved.measure(in: CGSize(width: 80, height: 16))
                let encPlaceRight = (xPos + encSize.width + 8) <= plotRight
                let encLX = encPlaceRight ? xPos + 5 : xPos - 5
                let encAnchor: UnitPoint = encPlaceRight ? .leading : .trailing
                labelCtx.draw(encResolved, at: CGPoint(x: encLX, y: encY), anchor: encAnchor)
            }
        }
    }
}
