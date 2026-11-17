import SwiftUI
import Charts
import NakamonREC_Shared

/// タイムフィルタの範囲指定シート (2026-11-17 ビーフ設計、Android `TimeFilterDialog` と同仕様)。
/// 戦績画面のフィルタモードでレコードの日時をタップすると開く。
/// - 横軸 = 日時 (ファイル全体の最初〜最後。寝ている時間の横ばいもそのまま見せる)
/// - 縦軸 = グランプリ集計が使える (= 虫眼鏡メニューでグレーアウトされない) ならレーティング + ボーダー、
///   使えないなら 20 戦移動平均勝率 (戦績画面右上の RECENT TREND と同じ計算)
/// - 左右 2 本の縦線をドラッグして範囲を決める。縦線は最も近いレコードの日時に吸着し、範囲外は暗くする
/// - グラフは他のフィルタに関係なくファイル全体を描き、「フィルタ実行」で他の条件と AND で組み合わせる
struct TimeFilterView: View {
    /// ファイル全体の戦績 (古い順でなくてもよい)
    let records: [BattleRecord]
    /// 既に TIME フィルタがかかっていればその範囲 (timestamp 文字列)。nil = 全範囲から開始
    let initialRange: ClosedRange<String>?
    let onApply: (ClosedRange<String>) -> Void

    @Environment(\.dismiss) private var dismiss

    /// 吸着先 = レコードの日時 (古い順、パースできたものだけ)
    @State private var stops: [(ts: String, date: Date)] = []
    @State private var series: [Point] = []
    @State private var useGrandPrix = false
    @State private var startIdx = 0
    @State private var endIdx = 0
    /// ドラッグ中の縦線 (0 = 左, 1 = 右)。nil = ドラッグしていない
    @State private var dragging: Int? = nil

    struct Point: Identifiable {
        let id = UUID()
        let date: Date
        let value: Double
        let series: String
    }

    private var hasData: Bool { !stops.isEmpty && !series.isEmpty }
    private static let plotSpace = "timeFilterPlot"

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(alignment: .leading, spacing: 12) {
                    rangeHeader
                    chartCard
                    Text("左右の縦線をドラッグして、絞り込む期間を選んでください")
                        .font(.caption).foregroundStyle(.gray)
                    Spacer(minLength: 0)
                }
                .padding(16)
            }
            .navigationTitle("タイムフィルタ")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("キャンセル") { dismiss() }.foregroundStyle(Color.recCoral)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("フィルタ実行") {
                        onApply(stops[startIdx].ts...stops[endIdx].ts)
                        dismiss()
                    }
                    .bold()
                    .foregroundStyle(hasData ? Color.recCoral : .gray)
                    .disabled(!hasData)
                }
            }
            .onAppear(perform: prepare)
        }
    }

    // MARK: - Header (範囲の開始/終了日時。データが無ければ枠だけで空欄)

    private var rangeHeader: some View {
        HStack(spacing: 8) {
            rangeBox(title: "開始", ts: hasData ? stops[startIdx].ts : nil)
            Text("〜").foregroundStyle(.gray)
            rangeBox(title: "終了", ts: hasData ? stops[endIdx].ts : nil)
        }
    }

    private func rangeBox(title: String, ts: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption2).foregroundStyle(.gray)
            Text(ts.map(displayTimestamp) ?? " ")
                .font(.subheadline.monospacedDigit().bold())
                .foregroundStyle(.white)
                .lineLimit(1).minimumScaleFactor(0.7)
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    /// 2026-11-14 12:39:56 → 2026.11.14 12:39 (戦績画面と同じドット区切り)
    private func displayTimestamp(_ ts: String) -> String {
        String(ts.replacingOccurrences(of: "-", with: ".").prefix(16))
    }

    // MARK: - Chart

    private var chartCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(useGrandPrix ? "レーティング推移" : "勝率推移 (直近20戦)")
                .font(.caption.bold()).foregroundStyle(.gray)
            chart.frame(height: 300)
            if useGrandPrix {
                HStack(spacing: 12) {
                    Text("● 自分").font(.caption).foregroundStyle(Color.recCoral)
                    Text("● ボーダー").font(.caption).foregroundStyle(.cyan)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var chart: some View {
        Chart {
            ForEach(series) { p in
                LineMark(x: .value("日時", p.date), y: .value("値", p.value))
                    .foregroundStyle(by: .value("系列", p.series))
                    .lineStyle(StrokeStyle(lineWidth: 1.5))
            }
        }
        .chartForegroundStyleScale(["自分": Color.recCoral, "ボーダー": Color.cyan, "勝率": Color.recCoral])
        .chartLegend(.hidden)
        .chartXScale(domain: xDomain)
        .chartYScale(domain: yDomain)
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 3)) { value in
                AxisGridLine()
                if let d = value.as(Date.self) {
                    AxisValueLabel {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(d, format: .dateTime.month(.defaultDigits).day())
                            Text(d, format: .dateTime.hour().minute())
                        }
                    }
                }
            }
        }
        .chartOverlay { proxy in
            GeometryReader { geo in
                if hasData, let plotAnchor = proxy.plotFrame {
                    let plot = geo[plotAnchor]
                    rangeOverlay(proxy: proxy, plot: plot)
                }
            }
            // 縦線のドラッグ位置をグラフ全体の座標で受け取る (縦線自身の座標系だと 44pt 幅の中の位置になる)
            .coordinateSpace(name: Self.plotSpace)
        }
    }

    /// 範囲外の暗幕 + 左右の縦線 (上端に掴みやすい丸)。縦線は最も近いレコードの日時に吸着する
    private func rangeOverlay(proxy: ChartProxy, plot: CGRect) -> some View {
        let xs = proxy.position(forX: stops[startIdx].date) ?? 0
        let xe = proxy.position(forX: stops[endIdx].date) ?? plot.width
        let left = plot.minX + xs
        let right = plot.minX + xe
        return ZStack(alignment: .topLeading) {
            // 範囲外を暗く
            Rectangle().fill(Color.black.opacity(0.55))
                .frame(width: max(0, left - plot.minX), height: plot.height)
                .position(x: (plot.minX + left) / 2, y: plot.midY)
            Rectangle().fill(Color.black.opacity(0.55))
                .frame(width: max(0, plot.maxX - right), height: plot.height)
                .position(x: (right + plot.maxX) / 2, y: plot.midY)
            handle(x: left, plot: plot, which: 0, proxy: proxy)
            handle(x: right, plot: plot, which: 1, proxy: proxy)
        }
        .allowsHitTesting(true)
    }

    private func handle(x: CGFloat, plot: CGRect, which: Int, proxy: ChartProxy) -> some View {
        ZStack {
            Rectangle().fill(Color.white).frame(width: 2, height: plot.height)
            Circle().fill(Color.white).frame(width: 18, height: 18)
                .overlay(Circle().stroke(Color.recCoral, lineWidth: 2))
                .offset(y: -plot.height / 2)
        }
        // 細い縦線でも指で掴めるよう、当たり判定は幅 44pt
        .frame(width: 44, height: plot.height)
        .contentShape(Rectangle())
        .position(x: x, y: plot.midY)
        .gesture(
            DragGesture(minimumDistance: 0, coordinateSpace: .named(Self.plotSpace))
                .onChanged { g in
                    dragging = which
                    let localX = g.location.x - plot.minX
                    guard let d: Date = proxy.value(atX: min(max(localX, 0), plot.width)) else { return }
                    let i = nearestStop(to: d)
                    if which == 0 { startIdx = min(i, endIdx) } else { endIdx = max(i, startIdx) }
                }
                .onEnded { _ in dragging = nil }
        )
    }

    private func nearestStop(to d: Date) -> Int {
        // stops は日時の昇順。二分探索で最も近い点を返す
        var lo = 0, hi = stops.count - 1
        while lo < hi {
            let mid = (lo + hi) / 2
            if stops[mid].date < d { lo = mid + 1 } else { hi = mid }
        }
        if lo > 0, abs(stops[lo - 1].date.timeIntervalSince(d)) <= abs(stops[lo].date.timeIntervalSince(d)) {
            return lo - 1
        }
        return lo
    }

    private var xDomain: ClosedRange<Date> {
        guard let f = stops.first?.date, let l = stops.last?.date, f < l else {
            let now = Date(); return now.addingTimeInterval(-3600)...now
        }
        return f...l
    }

    private var yDomain: ClosedRange<Double> {
        let vals = series.map(\.value)
        guard let lo = vals.min(), let hi = vals.max() else { return 0...100 }
        if !useGrandPrix { return max(0, lo - 5)...min(100, hi + 5) }
        let span = max(hi - lo, 1)
        return (lo - span * 0.1)...(hi + span * 0.1)
    }

    // MARK: - Data

    private func prepare() {
        let sorted = records
            .compactMap { r -> (BattleRecord, Date)? in
                guard let d = BattleTimestampFormatter.date(from: r.timestamp) else { return nil }
                return (r, d)
            }
            .sorted { $0.1 < $1.1 }
        stops = sorted.map { ($0.0.timestamp, $0.1) }
        // 縦軸の種類は虫眼鏡メニューのグランプリ集計の有効/グレーアウトと同じ判定 (StatsMenuSheet.grandPrixAvailable)
        let gpRecords = BattleHistoryStore.shared.loadGrandPrixRecords()
        useGrandPrix = GrandPrixMode.isEnabled || !gpRecords.isEmpty
        var pts: [Point] = []
        if useGrandPrix {
            for g in gpRecords where !g.isUnread {
                guard let d = BattleTimestampFormatter.date(from: g.timestamp) else { continue }
                pts.append(Point(date: d, value: g.currentRating, series: "自分"))
                if let b = g.borderRating { pts.append(Point(date: d, value: b, series: "ボーダー")) }
            }
            pts.sort { $0.date < $1.date }
        } else {
            // RECENT TREND と同じ: 1〜20 戦目はそれまでの全戦、以降は直近 20 戦の勝率
            let window = 20
            for idx in sorted.indices {
                let start = max(0, idx - window + 1)
                let wins = sorted[start...idx].filter { $0.0.result == "WIN" }.count
                pts.append(Point(date: sorted[idx].1, value: Double(wins) / Double(idx - start + 1) * 100, series: "勝率"))
            }
        }
        series = pts
        // GP なのに記録が 0 件ならグラフは空のまま (開始/終了も空欄、実行不可)
        guard !stops.isEmpty else { return }
        if let r = initialRange,
           let s = stops.firstIndex(where: { $0.ts >= r.lowerBound }),
           let e = stops.lastIndex(where: { $0.ts <= r.upperBound }), s <= e {
            startIdx = s; endIdx = e
        } else {
            startIdx = 0; endIdx = stops.count - 1
        }
    }
}
