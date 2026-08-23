import SwiftUI
import Charts
import NakamonREC_Shared

/// グランプリ集計画面。読込中ファイルの grandPrixRecords を、
/// 自分のレーティング折れ線 + ボーダー折れ線 (現在+必要あと) で時系列表示する。
/// 「1 ファイル = 1 グランプリ」前提のため、ファイル全体が 1 大会ぶん。
struct GrandPrixStatsView: View {
    let records: [GrandPrixRecord]
    @Environment(\.dismiss) private var dismiss

    /// 時刻昇順に整列
    private var sorted: [GrandPrixRecord] {
        records.sorted {
            (BattleTimestampFormatter.date(from: $0.timestamp) ?? .distantPast)
                < (BattleTimestampFormatter.date(from: $1.timestamp) ?? .distantPast)
        }
    }

    private var maxRating: Double? { sorted.map(\.currentRating).max() }
    private var winCount: Int { sorted.filter { $0.result == "WIN" }.count }
    private var loseCount: Int { sorted.filter { $0.result == "LOSE" }.count }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if sorted.isEmpty {
                    emptyState
                } else {
                    ScrollView {
                        VStack(spacing: 16) {
                            summaryCard
                            chartCard
                            recordList
                        }
                        .padding(16)
                    }
                }
            }
            .navigationTitle("グランプリ集計")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("閉じる") { dismiss() }
                        .foregroundStyle(Color.recCoral)
                }
            }
        }
    }

    // MARK: - Empty

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "trophy")
                .font(.system(size: 44))
                .foregroundStyle(.gray)
            Text("グランプリの記録がありません")
                .foregroundStyle(.gray)
            Text("大会用 VS 画面で校正し、大会中に記録すると\nここにレーティング推移が表示されます。")
                .font(.caption)
                .multilineTextAlignment(.center)
                .foregroundStyle(.gray.opacity(0.8))
        }
        .padding(32)
    }

    // MARK: - Summary

    private var summaryCard: some View {
        HStack(spacing: 0) {
            summaryItem(title: "最高レーティング",
                        value: maxRating.map { String(format: "%.1f", $0) } ?? "—",
                        highlight: true)
            Divider().frame(height: 40).overlay(Color.gray.opacity(0.3))
            summaryItem(title: "記録数", value: "\(sorted.count)戦")
            Divider().frame(height: 40).overlay(Color.gray.opacity(0.3))
            summaryItem(title: "勝敗", value: "\(winCount)W-\(loseCount)L")
        }
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private func summaryItem(title: String, value: String, highlight: Bool = false) -> some View {
        VStack(spacing: 4) {
            Text(title)
                .font(.caption2)
                .foregroundStyle(.gray)
            Text(value)
                .font(highlight ? .title3.bold() : .body.bold())
                .foregroundStyle(highlight ? Color.recCoral : .white)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Chart

    private struct ChartPoint: Identifiable {
        let id = UUID()
        let date: Date
        let rating: Double
        let series: String   // "自分" / "ボーダー"
    }

    private var chartPoints: [ChartPoint] {
        var pts: [ChartPoint] = []
        for r in sorted {
            guard let d = BattleTimestampFormatter.date(from: r.timestamp) else { continue }
            pts.append(ChartPoint(date: d, rating: r.currentRating, series: "自分"))
            if let border = r.borderRating {
                pts.append(ChartPoint(date: d, rating: border, series: "ボーダー"))
            }
        }
        return pts
    }

    private var chartCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("レーティング推移")
                .font(.caption.bold())
                .foregroundStyle(.gray)
            Chart(chartPoints) { pt in
                LineMark(
                    x: .value("日時", pt.date),
                    y: .value("レーティング", pt.rating)
                )
                .foregroundStyle(by: .value("系列", pt.series))
                .symbol(by: .value("系列", pt.series))
            }
            .chartForegroundStyleScale([
                "自分": Color.recCoral,
                "ボーダー": Color.cyan
            ])
            .chartLegend(position: .top, alignment: .leading)
            .frame(height: 220)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - List

    private var recordList: some View {
        VStack(spacing: 0) {
            ForEach(Array(sorted.enumerated().reversed()), id: \.element.id) { idx, r in
                recordRow(r, delta: delta(at: idx))
                if idx != 0 { Divider().overlay(Color.gray.opacity(0.2)) }
            }
        }
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    /// その戦の変動 = 現在レーティング − 前戦の現在レーティング (読まず導出)
    private func delta(at idx: Int) -> Double? {
        guard idx > 0 else { return nil }
        return sorted[idx].currentRating - sorted[idx - 1].currentRating
    }

    private func recordRow(_ r: GrandPrixRecord, delta: Double?) -> some View {
        HStack(spacing: 10) {
            Text(r.result == "WIN" ? "WIN" : "LOSE")
                .font(.caption.bold())
                .foregroundStyle(r.result == "WIN" ? Color.recCoral : Color.cyan)
                .frame(width: 44, alignment: .leading)

            VStack(alignment: .leading, spacing: 2) {
                Text(shortTime(r.timestamp))
                    .font(.caption2)
                    .foregroundStyle(.gray)
                if let border = r.borderRating {
                    Text("ボーダー \(String(format: "%.1f", border))")
                        .font(.caption2)
                        .foregroundStyle(.cyan.opacity(0.9))
                }
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                Text(String(format: "%.1f", r.currentRating))
                    .font(.body.bold())
                    .foregroundStyle(.white)
                if let d = delta {
                    Text(String(format: "%@%.1f", d >= 0 ? "+" : "", d))
                        .font(.caption2)
                        .foregroundStyle(d >= 0 ? Color.recCoral : Color.cyan)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }

    /// "yyyy-MM-dd HH:mm:ss" → "MM/dd HH:mm"
    private func shortTime(_ ts: String) -> String {
        guard let d = BattleTimestampFormatter.date(from: ts) else { return ts }
        let f = DateFormatter()
        f.dateFormat = "MM/dd HH:mm"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f.string(from: d)
    }
}
