import SwiftUI
import NakamonREC_Shared

/// パーティ集計画面 (Phase 3)
///
/// 表示順 (上から):
///   1. TOTAL (常に最上段)
///   2. P1(最新) — 現在の P1 構成
///   3. P2(最新) — 現在の P2 構成
///   4. P3(最新) — 現在の P3 構成
///   5. (過去): 現在のいずれの P 構成にも一致しない、過去の組
///      多重利用 P 番号は最も新しい使用 P を先頭にし残りを昇順
///      例: P1,2(過去) / P3,1,2(過去)
///
/// 色: 勝率 50%+ ピンク / 50%- 水色 (Android と統一)
/// グラフ: 1 日ごとの平均勝率、6 日表示、左右スライド可能
struct PartyStatsView: View {
    let records: [BattleRecord]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 8) {
                        rowCard(.total(buildTotal()))
                        ForEach(0..<3, id: \.self) { p in
                            rowCard(.latestSlot(p, buildLatestSlot(partyIndex: p)))
                        }
                        ForEach(pastRows) { row in
                            rowCard(.past(row))
                        }
                        Spacer(minLength: 24)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 10)
                }
            }
            .navigationTitle("パーティ集計")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("閉じる") { dismiss() }
                }
            }
        }
    }

    // MARK: - Card

    private enum RowKind {
        case total(StatsBundle)
        case latestSlot(Int, StatsBundle?)
        case past(PastRow)
    }

    @ViewBuilder
    private func rowCard(_ kind: RowKind) -> some View {
        HStack(alignment: .top, spacing: 8) {
            // 左: ラベル + 数字 + サムネ (縦並び)
            leftColumn(kind)
                .frame(width: 160, alignment: .leading)
            // 右: 1 日ごとの勝率グラフ
            chartArea(kind)
                .frame(maxWidth: .infinity)
                .frame(height: 88)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    @ViewBuilder
    private func leftColumn(_ kind: RowKind) -> some View {
        switch kind {
        case .total(let stats):
            VStack(alignment: .leading, spacing: 1) {
                statsHeader(label: "TOTAL", rate: stats.winRate, labelColor: .white)
                Text("\(stats.matches)Matches")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.gray)
                Text("\(stats.wins)W-\(stats.losses)L")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.gray)
                // TOTAL 行はサムネなし (高さ揃え用のスペーサー)
                Color.clear.frame(height: 36)
            }
        case .latestSlot(let p, let stats):
            VStack(alignment: .leading, spacing: 1) {
                if let s = stats {
                    statsHeader(label: "P\(p + 1)(最新)", rate: s.winRate, labelColor: .white)
                    Text("\(s.matches)Matches")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.gray)
                    Text("\(s.wins)W-\(s.losses)L(Use:\(String(format: "%.1f%%", s.usageRate)))")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.gray)
                    thumbsRow(s.partyIDs)
                } else {
                    statsHeader(label: "P\(p + 1)(未使用)", rate: 0, labelColor: .gray)
                    Text("0Matches")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.gray)
                    Text("—")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.gray)
                    Color.clear.frame(height: 36)
                }
            }
        case .past(let row):
            VStack(alignment: .leading, spacing: 1) {
                statsHeader(label: row.label, rate: row.stats.winRate, labelColor: .gray)
                Text("\(row.stats.matches)Matches")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.gray)
                Text("\(row.stats.wins)W-\(row.stats.losses)L(Use:\(String(format: "%.1f%%", row.stats.usageRate)))")
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.gray)
                thumbsRow(row.stats.partyIDs)
            }
        }
    }

    private func statsHeader(label: String, rate: Double, labelColor: Color) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(label)
                .font(.subheadline.bold())
                .foregroundStyle(labelColor)
            Text(String(format: "%.1f%%", rate))
                .font(.title3.bold().monospacedDigit())
                .foregroundStyle(rateColor(rate))
        }
    }

    private func thumbsRow(_ ids: [String]) -> some View {
        HStack(spacing: 3) {
            ForEach(0..<4, id: \.self) { i in
                MonsterThumb(name: ids[safe: i] ?? "?", size: 36)
            }
        }
    }

    @ViewBuilder
    private func chartArea(_ kind: RowKind) -> some View {
        switch kind {
        case .total(let stats):
            DailyTrendChart(records: stats.recordsRef)
        case .latestSlot(_, let stats):
            if let s = stats, !s.recordsRef.isEmpty {
                DailyTrendChart(records: s.recordsRef)
            } else {
                Color.clear
            }
        case .past(let row):
            DailyTrendChart(records: row.stats.recordsRef)
        }
    }

    /// 50%+ = ピンク、50%- = 水色 (Android 互換)
    private func rateColor(_ rate: Double) -> Color {
        rate >= 50 ? Color.recCoral : Color.sideEnemy
    }

    // MARK: - Models

    private struct StatsBundle {
        let partyIDs: [String]   // 4 体 (TOTAL の場合は空)
        let matches: Int
        let wins: Int
        let losses: Int
        let winRate: Double
        let usageRate: Double
        let recordsRef: [BattleRecord]
    }

    private struct PastRow: Identifiable {
        let id: String
        let label: String
        let stats: StatsBundle
        let mostRecentTimestamp: String
    }

    // MARK: - Builders

    private func buildTotal() -> StatsBundle {
        let total = records.count
        let wins = records.filter { $0.result == "WIN" }.count
        let losses = total - wins
        let rate = total > 0 ? Double(wins) / Double(total) * 100 : 0
        return StatsBundle(
            partyIDs: [],
            matches: total, wins: wins, losses: losses,
            winRate: rate, usageRate: 100,
            recordsRef: records
        )
    }

    /// 現在の P{partyIndex+1} 構成 (= 最新の partyIndex 一致レコードの myParty)
    private func buildLatestSlot(partyIndex: Int) -> StatsBundle? {
        guard let latest = records.last(where: { $0.partyIndex == partyIndex }) else {
            return nil
        }
        let composition = Set(latest.myParty.filter { $0 != "?" })
        let matched = records.filter { rec in
            Set(rec.myParty.filter { $0 != "?" }) == composition
        }
        guard !matched.isEmpty else { return nil }
        let total = matched.count
        let wins = matched.filter { $0.result == "WIN" }.count
        let losses = total - wins
        let rate = Double(wins) / Double(total) * 100
        let usage = records.isEmpty ? 0 : Double(total) / Double(records.count) * 100
        return StatsBundle(
            partyIDs: latest.myParty,
            matches: total, wins: wins, losses: losses,
            winRate: rate, usageRate: usage,
            recordsRef: matched
        )
    }

    /// 過去 = 現在の P1/P2/P3 のどの構成にも一致しないグループ
    private var pastRows: [PastRow] {
        let valid = records.filter { $0.partyIndex >= 0 && $0.partyIndex < 3 }
        let groups = Dictionary(grouping: valid, by: { rec -> String in
            Set(rec.myParty.filter { $0 != "?" }).sorted().joined(separator: ",")
        })
        // 現在の P 構成 3 種を取得
        var currentSets: [Set<String>] = []
        for p in 0..<3 {
            if let latest = records.last(where: { $0.partyIndex == p }) {
                currentSets.append(Set(latest.myParty.filter { $0 != "?" }))
            }
        }
        var result: [PastRow] = []
        for (key, recs) in groups {
            let composition = Set(recs.first?.myParty.filter { $0 != "?" } ?? [])
            if currentSets.contains(composition) { continue } // 過去ではない
            let sortedRecs = recs.sorted { $0.timestamp > $1.timestamp }
            guard let mostRecentP = sortedRecs.first?.partyIndex else { continue }
            let allUsedPs = Set(sortedRecs.map { $0.partyIndex + 1 })
            var pNumbers: [Int] = [mostRecentP + 1]
            pNumbers.append(contentsOf: allUsedPs.subtracting([mostRecentP + 1]).sorted())
            let label = "P\(pNumbers.map(String.init).joined(separator: ","))(過去)"
            let total = recs.count
            let wins = recs.filter { $0.result == "WIN" }.count
            let losses = total - wins
            let rate = Double(wins) / Double(total) * 100
            let usage = records.isEmpty ? 0 : Double(total) / Double(records.count) * 100
            result.append(PastRow(
                id: key,
                label: label,
                stats: StatsBundle(
                    partyIDs: recs.first?.myParty ?? [],
                    matches: total, wins: wins, losses: losses,
                    winRate: rate, usageRate: usage,
                    recordsRef: recs
                ),
                mostRecentTimestamp: sortedRecs.first?.timestamp ?? ""
            ))
        }
        // 最新使用 timestamp 降順
        result.sort { $0.mostRecentTimestamp > $1.mostRecentTimestamp }
        return result
    }
}
