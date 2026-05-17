import SwiftUI
import Charts
import NakamonREC_Shared

// MARK: - Filter model

/// 戦績フィルタ条件 (AND 結合)
struct BattleFilter: Equatable {
    /// nil = 指定なし、"WIN" or "LOSE" でフィルタ
    var winLoseFilter: String? = nil
    /// 味方に含まれているべきモンスター
    var requiredMyMonsters: Set<String> = []
    /// 敵に含まれているべきモンスター
    var requiredEnemyMonsters: Set<String> = []
    /// パーティ index (カードタップによる簡易フィルタ用) — nil = 全パーティ
    var partyIndex: Int? = nil

    var isEmpty: Bool {
        winLoseFilter == nil &&
        requiredMyMonsters.isEmpty &&
        requiredEnemyMonsters.isEmpty &&
        partyIndex == nil
    }

    func matches(_ record: BattleRecord) -> Bool {
        if let wl = winLoseFilter, record.result != wl { return false }
        for m in requiredMyMonsters {
            if !record.myParty.contains(m) { return false }
        }
        for m in requiredEnemyMonsters {
            if !record.enemyParty.contains(m) { return false }
        }
        if let pi = partyIndex, record.partyIndex != pi { return false }
        return true
    }
}

enum BattleHistoryMode {
    case edit
    case filter
}

// MARK: - Main view

/// 戦績履歴メイン画面 (Android `HistoryActivity` 相当)
struct BattleHistoryView: View {
    @State private var history: BattleHistory = BattleHistory()
    @State private var filter: BattleFilter = BattleFilter()
    @State private var mode: BattleHistoryMode = .edit
    @State private var editingRecord: BattleRecord? = nil
    @State private var editingMonstersFor: BattleRecord? = nil
    @Environment(\.dismiss) private var dismiss

    /// フィルタ後の records (古い順)
    private var filtered: [BattleRecord] {
        if filter.isEmpty { return history.records }
        return history.records.filter(filter.matches)
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 0) {
                // 上部: TOTAL/FILTER WIN RATE + RECENT TREND
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

                // フィルタチップ行 (フィルタモード時のみ表示)
                if mode == .filter && !filter.isEmpty {
                    filterChipsRow
                        .padding(.horizontal, 8)
                        .padding(.top, 8)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }

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
        .sheet(item: $editingRecord) { record in
            RecordEditMenu(
                record: record,
                onApply: { updated in
                    if let updated {
                        BattleHistoryStore.shared.updateRecord(updated)
                    } else {
                        BattleHistoryStore.shared.deleteRecord(id: record.id)
                    }
                    reload()
                },
                onAddNext: { partyIdx in
                    insertNewRecord(after: record, partyIndex: partyIdx)
                },
                onChangeMonsters: {
                    editingMonstersFor = record
                },
                onShowMatchingScore: {
                    // Phase 7 で実装
                }
            )
            .presentationDetents([.medium, .large])
        }
        .sheet(item: $editingMonstersFor) { record in
            MonsterPartyEditor(record: record) { updated in
                BattleHistoryStore.shared.updateRecord(updated)
                reload()
            }
        }
    }

    /// この1戦の次に新規レコードを追加 (timestamp は現在時刻、myParty は選択パーティの最新編成を引き継ぐ)
    private func insertNewRecord(after record: BattleRecord, partyIndex: Int) {
        let myParty = latestMyParty(forPartyIndex: partyIndex) ?? ["?", "?", "?", "?"]
        let newRecord = BattleRecord(
            timestamp: BattleTimestampFormatter.now(),
            result: "WIN",
            partyIndex: partyIndex,
            myParty: myParty,
            enemyParty: ["?", "?", "?", "?"]
        )
        BattleHistoryStore.shared.insertRecord(newRecord, afterId: record.id)
        reload()
    }

    private func latestMyParty(forPartyIndex idx: Int) -> [String]? {
        history.records.last(where: { $0.partyIndex == idx })?.myParty
    }

    private func reload() {
        history = BattleHistoryStore.shared.loadActive()
    }

    // MARK: - Top: TOTAL/FILTER WIN RATE

    private var totalWinRateCard: some View {
        // 編集モード時: 簡易フィルタ (partyIndex) を無視して全戦績を表示
        // フィルタモード時: フィルタ反映 (FILTER WIN RATE)
        let useFiltered = (mode == .filter) && !filter.isEmpty
        let recs = useFiltered ? filtered : history.records
        let wins = recs.filter { $0.result == "WIN" }.count
        let losses = recs.filter { $0.result == "LOSE" }.count
        let total = wins + losses
        let winRate = total > 0 ? Double(wins) / Double(total) * 100 : 0
        let isSelected = (filter.partyIndex == nil)  // 簡易フィルタで「全パーティ」相当

        return VStack(alignment: .leading, spacing: 4) {
            Text(useFiltered ? "FILTER WIN RATE" : "TOTAL WIN RATE")
                .font(.caption2)
                .foregroundStyle(.gray)
            Text(String(format: "%.1f%%", winRate))
                .font(.system(size: 36, weight: .bold))
                .foregroundStyle(Color.recCoral)
            Text("\(total) Matches")
                .font(.caption)
                .foregroundStyle(.gray)
            HStack(spacing: 4) {
                Text("\(wins)W").foregroundStyle(Color.sideMy)
                Text("-").foregroundStyle(.gray)
                Text("\(losses)L").foregroundStyle(Color.sideEnemy)
            }
            .font(.caption)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(isSelected ? Color.cardSelected : Color.clear, lineWidth: 2)
        )
        .onTapGesture {
            withAnimation(.easeInOut(duration: 0.25)) {
                filter.partyIndex = nil
            }
        }
    }

    // MARK: - Top: RECENT TREND

    private var recentTrendCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("RECENT TREND")
                .font(.caption2)
                .foregroundStyle(.gray)

            let trend = movingAverageWinRates(records: filtered, window: 20)
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
                Text(String(format: "%.1f%% : %d Matches", trend.last ?? 0, filtered.count))
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

    private func movingAverageWinRates(records: [BattleRecord], window: Int) -> [Double] {
        guard records.count >= 2 else { return [] }
        var result: [Double] = []
        for i in 0..<records.count {
            let start = max(0, i - window + 1)
            let slice = records[start...i]
            let wins = slice.filter { $0.result == "WIN" }.count
            let rate = Double(wins) / Double(slice.count) * 100
            result.append(rate)
        }
        return result
    }

    // MARK: - Middle: P1/P2/P3

    private func partyCard(index: Int) -> some View {
        // フィルタ反映 (winLose / monsters は適用、partyIndex は除外して各カードの母集団とする)
        var statsFilter = filter
        statsFilter.partyIndex = nil
        let baseRecords = statsFilter.isEmpty ? history.records : history.records.filter(statsFilter.matches)

        let partyRecords = baseRecords.filter { $0.partyIndex == index }
        let wins = partyRecords.filter { $0.result == "WIN" }.count
        let losses = partyRecords.filter { $0.result == "LOSE" }.count
        let total = wins + losses
        let winRate = total > 0 ? Double(wins) / Double(total) * 100 : 0
        let useRate = baseRecords.isEmpty ? 0 : Double(total) / Double(baseRecords.count) * 100
        let latest = partyRecords.last
        let isSelected = filter.partyIndex == index

        return VStack(alignment: .leading, spacing: 4) {
            Text("P\(index + 1)")
                .font(.subheadline.bold())
                .foregroundStyle(.white)
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
                Text("\(wins)W").foregroundStyle(Color.sideMy)
                Text("-").foregroundStyle(.gray)
                Text("\(losses)L").foregroundStyle(Color.sideEnemy)
            }
            .font(.caption2)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(isSelected ? Color.cardSelected : Color.clear, lineWidth: 2)
        )
        .onTapGesture {
            // P# タップ = そのパーティに絞り込み (もう一度タップで解除)
            withAnimation(.easeInOut(duration: 0.25)) {
                filter.partyIndex = (filter.partyIndex == index) ? nil : index
            }
        }
    }

    // MARK: - Filter chips row

    private var filterChipsRow: some View {
        HStack(spacing: 4) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 3) {
                    if let wl = filter.winLoseFilter {
                        chipText(wl, color: wl == "WIN" ? Color.sideMy : Color.sideEnemy) {
                            withAnimation(.easeInOut(duration: 0.25)) {
                                filter.winLoseFilter = nil
                            }
                        }
                    }
                    if let pi = filter.partyIndex {
                        chipText("P\(pi + 1)", color: Color.recCoral) {
                            withAnimation(.easeInOut(duration: 0.25)) {
                                filter.partyIndex = nil
                            }
                        }
                    }
                    ForEach(Array(filter.requiredMyMonsters), id: \.self) { m in
                        chipMonster(name: m, isEnemy: false) {
                            withAnimation(.easeInOut(duration: 0.25)) {
                                _ = filter.requiredMyMonsters.remove(m)
                            }
                        }
                    }
                    ForEach(Array(filter.requiredEnemyMonsters), id: \.self) { m in
                        chipMonster(name: m, isEnemy: true) {
                            withAnimation(.easeInOut(duration: 0.25)) {
                                _ = filter.requiredEnemyMonsters.remove(m)
                            }
                        }
                    }
                }
            }
            Spacer(minLength: 0)
            Button("CLEAR") {
                withAnimation(.easeInOut(duration: 0.25)) {
                    filter = BattleFilter()
                }
            }
            .font(.caption.bold())
            .foregroundStyle(.white)
        }
        .padding(8)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    /// テキストチップ (WIN/LOSE/P#) — タップで削除、× アイコンは省略
    private func chipText(_ text: String, color: Color, onRemove: @escaping () -> Void) -> some View {
        Button(action: onRemove) {
            Text(text)
                .font(.caption.bold())
                .foregroundStyle(color)
                .padding(.horizontal, 6).padding(.vertical, 3)
                .background(Color.black.opacity(0.6))
                .clipShape(Capsule())
                .overlay(Capsule().stroke(color, lineWidth: 1.5))
        }
    }

    /// モンスターチップ — 味方=コーラル枠、敵=水色枠。ラベル/× は省略
    private func chipMonster(name: String, isEnemy: Bool, onRemove: @escaping () -> Void) -> some View {
        let color = isEnemy ? Color.sideEnemy : Color.sideMy
        return Button(action: onRemove) {
            MonsterThumb(name: name, size: 24)
                .overlay(
                    RoundedRectangle(cornerRadius: 4)
                        .stroke(color, lineWidth: 2)
                )
        }
    }

    // MARK: - Bottom: Records list

    private var recordsList: some View {
        ScrollView {
            LazyVStack(spacing: 6) {
                ForEach(filtered.reversed()) { record in
                    BattleRecordRow(
                        record: record,
                        mode: mode,
                        myFilter: filter.requiredMyMonsters,
                        enemyFilter: filter.requiredEnemyMonsters,
                        onAddFilter: addFilter,
                        onLongPress: {
                            if mode == .edit {
                                editingRecord = record
                            }
                        }
                    )
                    .transition(.opacity.combined(with: .move(edge: .leading)))
                }
                if filtered.isEmpty {
                    Text("(該当する戦闘がありません)")
                        .font(.caption)
                        .foregroundStyle(.gray)
                        .padding(.top, 40)
                }
            }
            .padding(.horizontal, 8)
            .padding(.bottom, 80)
            .animation(.easeInOut(duration: 0.3), value: filtered.map { $0.id })
        }
    }

    private func addFilter(_ event: BattleRecordRow.FilterEvent) {
        withAnimation(.easeInOut(duration: 0.25)) {
            switch event {
            case .winLose(let value):
                filter.winLoseFilter = value
            case .myMonster(let name) where name != "?":
                filter.requiredMyMonsters.insert(name)
            case .enemyMonster(let name) where name != "?":
                filter.requiredEnemyMonsters.insert(name)
            default:
                break
            }
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
                withAnimation(.easeInOut(duration: 0.25)) {
                    mode = (mode == .edit) ? .filter : .edit
                }
            } label: {
                Image(systemName: mode == .edit ? "pencil" : "line.3.horizontal.decrease.circle")
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
    enum FilterEvent {
        case winLose(String)
        case myMonster(String)
        case enemyMonster(String)
    }

    let record: BattleRecord
    let mode: BattleHistoryMode
    let myFilter: Set<String>
    let enemyFilter: Set<String>
    let onAddFilter: (FilterEvent) -> Void
    let onLongPress: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            VStack(alignment: .leading, spacing: 2) {
                Text(record.result)
                    .font(.subheadline.bold())
                    .foregroundStyle(record.result == "WIN" ? Color.sideMy : Color.sideEnemy)
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
                    .onTapGesture {
                        if mode == .filter { onAddFilter(.winLose(record.result)) }
                    }
                Text(record.partyIndex >= 0 ? "P\(record.partyIndex + 1)" : "P?")
                    .font(.caption)
                    .foregroundStyle(.gray)
            }
            .frame(width: 42, alignment: .leading)

            HStack(spacing: 1) {
                ForEach(0..<4, id: \.self) { i in
                    let name = record.myParty[safe: i]
                    let highlighted = name.map { myFilter.contains($0) } ?? false
                    MonsterThumb(name: name, size: 26)
                        .overlay(
                            Color.sideMy.opacity(highlighted ? 0.45 : 0)
                                .clipShape(RoundedRectangle(cornerRadius: 4))
                        )
                        .onTapGesture {
                            if mode == .filter, let name { onAddFilter(.myMonster(name)) }
                        }
                }
                Text("VS")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 1)
                ForEach(0..<4, id: \.self) { i in
                    let name = record.enemyParty[safe: i]
                    let highlighted = name.map { enemyFilter.contains($0) } ?? false
                    MonsterThumb(name: name, size: 26)
                        .overlay(
                            Color.sideEnemy.opacity(highlighted ? 0.45 : 0)
                                .clipShape(RoundedRectangle(cornerRadius: 4))
                        )
                        .onTapGesture {
                            if mode == .filter, let name { onAddFilter(.enemyMonster(name)) }
                        }
                }
            }
            .frame(maxWidth: .infinity)

            VStack(alignment: .trailing, spacing: 1) {
                let parts = splitTimestamp(record.timestamp)
                Text(parts.date).font(.system(size: 8))
                Text(parts.time).font(.system(size: 8))
            }
            .foregroundStyle(.gray)
            .frame(width: 50)
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 4)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .onLongPressGesture(minimumDuration: 0.4) {
            onLongPress()
        }
    }

    private func splitTimestamp(_ ts: String) -> (date: String, time: String) {
        let parts = ts.split(separator: " ", maxSplits: 1)
        if parts.count == 2 {
            return (String(parts[0]), String(parts[1]))
        }
        return (ts, "")
    }
}

// MARK: - Monster thumbnail

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
    /// Android 版と統一したピンク #F09199 (味方 / WIN / 主要アクセント)
    static let recCoral = Color(red: 0xF0/255.0, green: 0x91/255.0, blue: 0x99/255.0)
    static let cardBackground = Color(white: 0.18)
    /// 味方の識別色 (= recCoral)
    static let sideMy = Color(red: 0xF0/255.0, green: 0x91/255.0, blue: 0x99/255.0)
    /// 敵の識別色 #90D7EC (Android 版と統一)
    static let sideEnemy = Color(red: 0x90/255.0, green: 0xD7/255.0, blue: 0xEC/255.0)
    /// 簡易フィルタ (TOTAL / P1〜3) の選択枠色 (ライトグレー)
    static let cardSelected = Color(white: 0.7)
}

// MARK: - Helpers

extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

#Preview {
    NavigationStack {
        BattleHistoryView()
    }
}
