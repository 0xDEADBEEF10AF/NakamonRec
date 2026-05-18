import SwiftUI
import NakamonREC_Shared

/// モンスター集計画面 (Phase 4)
/// - 敵 (enemyParty) に出現したモンスター ID ごとに集計
/// - records は呼び出し側 (BattleHistoryView) のフィルタ後リストを受け取るので、
///   P1~3 簡易フィルタ・フィルタモードの結果に追従してランキングが変化する
/// - タイトルは partyFilter に応じて「全体：…」「P1:…」と変化
/// - ソート 3 種: 出現率高い順 / 勝率低い順 / 勝率高い順
/// - 行ごとに 1 日単位の勝率 + 出現率の 2 系統グラフ (MonsterDailyTrendChart)
struct MonsterStatsView: View {
    let records: [BattleRecord]
    let partyFilter: Int?     // 0/1/2 = P1/P2/P3、nil = TOTAL
    @Environment(\.dismiss) private var dismiss

    enum SortKey: String, CaseIterable, Identifiable {
        case encountersDesc, winRateAsc, winRateDesc
        var id: String { rawValue }
        var label: String {
            switch self {
            case .encountersDesc: return "出現率が高い順"
            case .winRateAsc:     return "勝率が低い順"
            case .winRateDesc:    return "勝率が高い順"
            }
        }
    }
    @State private var sortKey: SortKey = .encountersDesc

    private var titleText: String {
        if let p = partyFilter, (0...2).contains(p) {
            return "P\(p + 1):敵モンスター出現率"
        }
        return "全体:敵モンスター出現率"
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                ScrollView {
                    LazyVStack(spacing: 3) {
                        ForEach(Array(sortedRows.enumerated()), id: \.element.id) { index, row in
                            monsterRow(rank: index + 1, row: row)
                        }
                        Spacer(minLength: 12)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 6)
                }
            }
            .navigationTitle(titleText)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Picker("", selection: $sortKey) {
                            ForEach(SortKey.allCases) { key in
                                Text(key.label).tag(key)
                            }
                        }
                    } label: {
                        Label(sortKey.label, systemImage: "arrow.up.arrow.down")
                            .font(.caption.bold())
                            .foregroundStyle(Color.recCoral)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("閉じる") { dismiss() }
                }
            }
        }
    }

    // MARK: - Row

    private let rowContentHeight: CGFloat = 50

    private func monsterRow(rank: Int, row: Row) -> some View {
        HStack(alignment: .center, spacing: 8) {
            // 左カラム (rank + thumb + 名前/出現/勝率) — 高さは chart と揃える
            HStack(spacing: 6) {
                Text("\(rank)")
                    .font(.caption.bold().monospacedDigit())
                    .foregroundStyle(.gray)
                    .frame(width: 18, alignment: .trailing)
                MonsterThumb(name: row.id, size: 40)
                VStack(alignment: .leading, spacing: 0) {
                    Text(MonsterCatalog.name(for: row.id))
                        .font(.caption.bold())
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text(String(format: "出現:%d回(%.1f%%)", row.encounters, row.encounterRate))
                        .font(.system(size: 9).monospacedDigit())
                        .foregroundStyle(.gray)
                    Text(String(format: "勝率:%.1f%%", row.winRate))
                        .font(.system(size: 9).monospacedDigit())
                        .foregroundStyle(rateColor(row.winRate))
                }
            }
            .frame(width: 170, height: rowContentHeight, alignment: .leading)

            MonsterDailyTrendChart(filteredRecords: records, monsterID: row.id)
                .frame(maxWidth: .infinity)
                .frame(height: rowContentHeight)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    private func rateColor(_ rate: Double) -> Color {
        rate >= 50 ? Color.recCoral : Color.sideEnemy
    }

    // MARK: - Aggregation

    private struct Row: Identifiable {
        let id: String
        let encounters: Int
        let encounterRate: Double
        let wins: Int
        let losses: Int
        let winRate: Double
    }

    private var rows: [Row] {
        var map: [String: (encounters: Int, wins: Int)] = [:]
        for record in records {
            let uniqueEnemies = Set(record.enemyParty.filter { $0 != "?" })
            for id in uniqueEnemies {
                var entry = map[id] ?? (0, 0)
                entry.encounters += 1
                if record.result == "WIN" { entry.wins += 1 }
                map[id] = entry
            }
        }
        let totalBattles = max(1, records.count)
        return map.map { id, val in
            let losses = val.encounters - val.wins
            let winRate = val.encounters > 0 ? Double(val.wins) / Double(val.encounters) * 100 : 0
            let encRate = Double(val.encounters) / Double(totalBattles) * 100
            return Row(
                id: id,
                encounters: val.encounters,
                encounterRate: encRate,
                wins: val.wins,
                losses: losses,
                winRate: winRate
            )
        }
    }

    private var sortedRows: [Row] {
        switch sortKey {
        case .encountersDesc: return rows.sorted { $0.encounters > $1.encounters }
        case .winRateAsc:     return rows.sorted { $0.winRate < $1.winRate }
        case .winRateDesc:    return rows.sorted { $0.winRate > $1.winRate }
        }
    }
}
