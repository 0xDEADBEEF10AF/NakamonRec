import SwiftUI
import Charts
import NakamonREC_Shared

/// 戦績履歴メイン画面 (Android `HistoryActivity` 相当)
/// Phase 1: 編集モードのみ。フィルタモード、レコード編集ポップアップ、集計画面は後続フェーズ
struct BattleHistoryView: View {
    @State private var history: BattleHistory = BattleHistory()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 0) {
                // 上部: TOTAL WIN RATE + RECENT TREND
                HStack(spacing: 8) {
                    totalWinRateCard
                    recentTrendCard
                }
                .padding(.horizontal, 8)
                .padding(.top, 8)

                // 中段: P1/P2/P3
                HStack(spacing: 8) {
                    ForEach(0..<3) { idx in
                        partyCard(index: idx)
                    }
                }
                .padding(.horizontal, 8)
                .padding(.top, 8)

                // 下部: レコードリスト
                recordsList
                    .padding(.top, 8)
            }
        }
        .navigationBarHidden(true)
        .safeAreaInset(edge: .bottom) {
            bottomToolbar
        }
        .onAppear(perform: reload)
    }

    private func reload() {
        history = BattleHistoryStore.shared.loadActive()
    }

    // MARK: - Top: TOTAL WIN RATE

    private var totalWinRateCard: some View {
        let total = history.totalWins + history.totalLosses
        let winRate = total > 0 ? Double(history.totalWins) / Double(total) * 100 : 0
        return VStack(alignment: .leading, spacing: 4) {
            Text("TOTAL WIN RATE")
                .font(.caption2)
                .foregroundStyle(.gray)
            Text(String(format: "%.1f%%", winRate))
                .font(.system(size: 36, weight: .bold))
                .foregroundStyle(Color.recCoral)
            Text("\(total) Matches")
                .font(.caption)
                .foregroundStyle(.gray)
            HStack(spacing: 4) {
                Text("\(history.totalWins)W")
                    .foregroundStyle(.green)
                Text("-")
                    .foregroundStyle(.gray)
                Text("\(history.totalLosses)L")
                    .foregroundStyle(.red)
            }
            .font(.caption)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Top: RECENT TREND

    private var recentTrendCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("RECENT TREND")
                .font(.caption2)
                .foregroundStyle(.gray)

            let trend = movingAverageWinRates(records: history.records, window: 20)
            if trend.count >= 2 {
                Chart {
                    ForEach(Array(trend.enumerated()), id: \.offset) { idx, value in
                        LineMark(
                            x: .value("idx", idx),
                            y: .value("rate", value)
                        )
                        .foregroundStyle(Color.recCoral)
                    }
                }
                .chartXAxis(.hidden)
                .chartYAxis(.hidden)
                .chartYScale(domain: 0...100)
                .frame(height: 60)
                Text(String(format: "%.1f%% : %d Matches", trend.last ?? 0, history.records.count))
                    .font(.caption2)
                    .foregroundStyle(.gray)
            } else {
                Text("(データ不足)")
                    .font(.caption)
                    .foregroundStyle(.gray)
                    .frame(maxWidth: .infinity, minHeight: 60)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // 直近 N 戦の勝率を計算 (移動平均)
    private func movingAverageWinRates(records: [BattleRecord], window: Int) -> [Double] {
        guard records.count >= 2 else { return [] }
        var result: [Double] = []
        // 古い順に処理 (records は append 順 = 古い順を想定)
        let ordered = records
        for i in 0..<ordered.count {
            let start = max(0, i - window + 1)
            let slice = ordered[start...i]
            let wins = slice.filter { $0.result == "WIN" }.count
            let rate = Double(wins) / Double(slice.count) * 100
            result.append(rate)
        }
        return result
    }

    // MARK: - Middle: P1/P2/P3

    private func partyCard(index: Int) -> some View {
        let partyRecords = history.records.filter { $0.partyIndex == index }
        let wins = partyRecords.filter { $0.result == "WIN" }.count
        let losses = partyRecords.filter { $0.result == "LOSE" }.count
        let total = wins + losses
        let winRate = total > 0 ? Double(wins) / Double(total) * 100 : 0
        let useRate = history.records.isEmpty ? 0 : Double(total) / Double(history.records.count) * 100
        let latest = partyRecords.last  // 最新編成 (records は古い順で append される前提)

        return VStack(alignment: .leading, spacing: 4) {
            Text("P\(index + 1)")
                .font(.subheadline.bold())
                .foregroundStyle(.white)
            // 最新編成のモンスターサムネ 4 体 (P# 直下)
            HStack(spacing: 2) {
                ForEach(0..<4, id: \.self) { i in
                    MonsterThumb(name: latest?.myParty[safe: i], size: 22)
                }
            }
            Text(String(format: "%.1f%%", winRate))
                .font(.title3.bold())
                .foregroundStyle(Color.recCoral)
            Text(String(format: "Use: %.1f%%", useRate))
                .font(.caption2)
                .foregroundStyle(.gray)
            HStack(spacing: 2) {
                Text("\(wins)W").foregroundStyle(.green)
                Text("-").foregroundStyle(.gray)
                Text("\(losses)L").foregroundStyle(.red)
            }
            .font(.caption2)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    // MARK: - Bottom: Records list

    private var recordsList: some View {
        ScrollView {
            LazyVStack(spacing: 6) {
                ForEach(history.records.reversed()) { record in
                    BattleRecordRow(record: record)
                }
            }
            .padding(.horizontal, 8)
            .padding(.bottom, 80)  // bottom toolbar 分の余白
        }
    }

    // MARK: - Bottom toolbar

    private var bottomToolbar: some View {
        HStack {
            Button { dismiss() } label: {
                Image(systemName: "arrow.uturn.backward")
                    .font(.title3)
                    .foregroundStyle(.gray)
                    .frame(maxWidth: .infinity)
            }
            Button {
                // Phase 2 でフィルタモード切替
            } label: {
                Image(systemName: "pencil")
                    .font(.title3)
                    .foregroundStyle(.white)
                    .padding(10)
                    .background(Circle().stroke(Color.gray.opacity(0.4)))
            }
            .frame(maxWidth: .infinity)
            Button {
                // Phase 3/4 で集計画面遷移
            } label: {
                Image(systemName: "magnifyingglass")
                    .font(.title3)
                    .foregroundStyle(.gray)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.vertical, 10)
        .background(Color.black.opacity(0.95))
    }
}

// MARK: - Row view

private struct BattleRecordRow: View {
    let record: BattleRecord

    var body: some View {
        HStack(spacing: 4) {
            VStack(alignment: .leading, spacing: 2) {
                Text(record.result)
                    .font(.subheadline.bold())
                    .foregroundStyle(record.result == "WIN" ? Color.recCoral : Color.blue)
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
                Text(record.partyIndex >= 0 ? "P\(record.partyIndex + 1)" : "P?")
                    .font(.caption)
                    .foregroundStyle(.gray)
            }
            .frame(width: 42, alignment: .leading)

            // 味方 4 - VS - 敵 4
            HStack(spacing: 1) {
                ForEach(0..<4, id: \.self) { i in
                    MonsterThumb(name: record.myParty[safe: i], size: 26)
                }
                Text("VS")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 1)
                ForEach(0..<4, id: \.self) { i in
                    MonsterThumb(name: record.enemyParty[safe: i], size: 26)
                }
            }
            .frame(maxWidth: .infinity)

            // 日付と時刻を 2 行で表示 (スペース節約)
            VStack(alignment: .trailing, spacing: 1) {
                let parts = splitTimestamp(record.timestamp)
                Text(parts.date)
                    .font(.system(size: 8))
                Text(parts.time)
                    .font(.system(size: 8))
            }
            .foregroundStyle(.gray)
            .frame(width: 50)
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 4)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    /// "2026-05-17 14:35:30" → ("2026-05-17", "14:35:30")
    private func splitTimestamp(_ ts: String) -> (date: String, time: String) {
        let parts = ts.split(separator: " ", maxSplits: 1)
        if parts.count == 2 {
            return (String(parts[0]), String(parts[1]))
        }
        return (ts, "")
    }
}

// MARK: - Monster thumbnail (共通)

/// モンスター 1 体のサムネ。`name` が nil/"?" もしくは画像が見つからない場合は ? アイコン表示
struct MonsterThumb: View {
    let name: String?
    let size: CGFloat

    var body: some View {
        Group {
            if let name, name != "?", let image = loadImage(name) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                ZStack {
                    Color.gray.opacity(0.2)
                    Image(systemName: "questionmark")
                        .font(.system(size: size * 0.55, weight: .bold))
                        .foregroundStyle(.gray)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 4))
    }

    private func loadImage(_ name: String) -> UIImage? {
        guard let path = Bundle.main.path(forResource: name, ofType: "png", inDirectory: "templates") else {
            return nil
        }
        return UIImage(contentsOfFile: path)
    }
}

// MARK: - Colors

extension Color {
    static let recCoral = Color(red: 240/255, green: 130/255, blue: 130/255)
    static let cardBackground = Color(white: 0.18)
}

// MARK: - Helpers

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

#Preview {
    NavigationStack {
        BattleHistoryView()
    }
}
