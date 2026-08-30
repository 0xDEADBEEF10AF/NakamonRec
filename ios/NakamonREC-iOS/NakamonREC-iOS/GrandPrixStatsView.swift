import SwiftUI
import Charts
import NakamonREC_Shared

/// グランプリ集計画面。読込中ファイルの grandPrixRecords を、自分のレーティング折れ線 +
/// ボーダー折れ線で表示(グラフ既定)。トグルでテキスト(レコード一覧)に切替でき、
/// レコードをタップすると操作メニュー(編集/削除/次に追加)。
/// 「1 ファイル = 1 グランプリ」前提。
struct GrandPrixStatsView: View {
    @State private var records: [GrandPrixRecord] = []
    @State private var showAsList = false          // false = グラフ(既定) / true = テキスト
    @State private var chartZoomed = false         // true = 直近N戦ズーム+横スクロール
    @State private var editing: GrandPrixRecord? = nil      // 操作メニュー対象
    @State private var editingForm: GrandPrixRecord? = nil  // 編集フォーム対象
    @State private var pendingAdd: FormSeed? = nil          // 追加フォーム (初期日時+引き継ぐランク帯)
    @State private var rawSelection: Date? = nil            // グラフのドラッグ/タップ選択 (生値)
    @State private var pinnedDate: Date? = nil              // 確定した選択点 (nil = 最新)

    /// ズーム時に表示する直近の戦闘数 (大会中は4桁になり得るため全体表示だと潰れる)
    private let zoomBattleCount = 50
    @Environment(\.dismiss) private var dismiss

    private var sorted: [GrandPrixRecord] {
        records.sorted {
            (BattleTimestampFormatter.date(from: $0.timestamp) ?? .distantPast)
                < (BattleTimestampFormatter.date(from: $1.timestamp) ?? .distantPast)
        }
    }
    private var currentRecord: GrandPrixRecord? { sorted.last }
    private var maxRecord: GrandPrixRecord? { sorted.max { $0.currentRating < $1.currentRating } }

    private func reload() { records = BattleHistoryStore.shared.loadGrandPrixRecords() }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if sorted.isEmpty {
                    emptyState
                } else {
                    ScrollView {
                        // pinnedViews: テキスト一覧のタイトル行をスクロール中も上部に固定
                        LazyVStack(spacing: 16, pinnedViews: [.sectionHeaders]) {
                            summaryHeader
                            if showAsList { listView } else { chartCard }
                        }
                        .padding(16)
                    }
                }
            }
            .navigationTitle("グランプリ集計")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if !sorted.isEmpty {
                        Button { showAsList.toggle() } label: {
                            Label(showAsList ? "グラフ" : "テキスト",
                                  systemImage: showAsList ? "chart.xyaxis.line" : "list.bullet")
                        }
                        .foregroundStyle(Color.recCoral)
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        // 追加はランク帯を最新レコードから引き継ぐ
                        pendingAdd = FormSeed(date: Date(), rankTier: sorted.last?.rankTier)
                    } label: { Image(systemName: "plus") }
                        .foregroundStyle(Color.recCoral)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("閉じる") { dismiss() }.foregroundStyle(Color.recCoral)
                }
            }
            .onAppear { reload() }
            .sheet(item: $editing) { rec in
                GrandPrixEditMenu(
                    record: rec,
                    onEdit: { editingForm = rec },
                    onDelete: {
                        BattleHistoryStore.shared.deleteGrandPrix(id: rec.id)
                        reload()
                    },
                    onAddAfter: { r in
                        // この記録の 1 秒後を初期日時に、ランク帯を引き継いで追加フォームを開く
                        let base = BattleTimestampFormatter.date(from: r.timestamp) ?? Date()
                        pendingAdd = FormSeed(date: base.addingTimeInterval(1), rankTier: r.rankTier)
                    }
                )
            }
            .sheet(item: $editingForm) { rec in
                GrandPrixFormSheet(
                    title: "グランプリ記録の編集",
                    initialDate: BattleTimestampFormatter.date(from: rec.timestamp) ?? Date(),
                    initialRating: String(format: "%.1f", rec.currentRating),
                    initialBorder: rec.borderRating.map { String(format: "%.1f", $0) } ?? "",
                    initialTier: rec.rankTier
                ) { date, rating, border, tier in
                    var c = rec
                    c.timestamp = BattleTimestampFormatter.formatter.string(from: date)
                    c.currentRating = rating
                    c.neededRating = border.map { $0 - rating }
                    c.rankTier = tier
                    BattleHistoryStore.shared.updateGrandPrix(id: rec.id, with: c)
                    reload()
                }
            }
            .sheet(item: $pendingAdd) { seed in
                GrandPrixFormSheet(
                    title: "グランプリ記録の追加",
                    initialDate: seed.date,
                    initialRating: "",
                    initialBorder: "",
                    initialTier: seed.rankTier
                ) { date, rating, border, tier in
                    let ts = BattleTimestampFormatter.formatter.string(from: date)
                    BattleHistoryStore.shared.appendGrandPrix(
                        GrandPrixRecord(timestamp: ts, result: "WIN", currentRating: rating,
                                        neededRating: border.map { $0 - rating }, rankTier: tier))
                    reload()
                }
            }
        }
    }

    // MARK: - Empty

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "trophy").font(.system(size: 44)).foregroundStyle(.gray)
            Text("グランプリの記録がありません").foregroundStyle(.gray)
            Text("大会用 VS 画面で校正し、大会中に記録すると\nここにレーティング推移が表示されます。")
                .font(.caption).multilineTextAlignment(.center).foregroundStyle(.gray.opacity(0.8))
            Button("手動で追加") { pendingAdd = FormSeed(date: Date(), rankTier: nil) }
                .foregroundStyle(Color.recCoral).padding(.top, 8)
        }
        .padding(32)
    }

    // MARK: - Summary header (現在 | 最高、メイン画面と同じ独立カード2枚)
    // 数値の左にそのレコードのランク帯エンブレムを表示 (現在=最新 / 最高=最高値のレコード)

    private var summaryHeader: some View {
        HStack(spacing: 8) {
            summaryCard("現在レーティング", record: currentRecord, valueColor: .white)
            summaryCard("最高レーティング", record: maxRecord, valueColor: Color.recCoral)
        }
    }

    private func summaryCard(_ label: String, record: GrandPrixRecord?, valueColor: Color) -> some View {
        VStack(spacing: 4) {
            Text(label).font(.caption2).foregroundStyle(.gray)
            HStack(spacing: 6) {
                if record?.rankTier != nil { RankBadge(tier: record?.rankTier, height: 28) }
                Text(record.map { String(format: "%.1f", $0.currentRating) } ?? "—")
                    .font(.system(size: 28, weight: .bold)).foregroundStyle(valueColor)
                    .lineLimit(1).minimumScaleFactor(0.7)
            }
        }
        .frame(maxWidth: .infinity).padding(.vertical, 12)
        .background(Color.cardBackground).clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Chart

    private struct ChartPoint: Identifiable {
        let id = UUID(); let date: Date; let rating: Double; let series: String
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

    /// Y レンジ = データの min..max ±10% (Android GrandPrixGraphView と同じ。0 起点だと潰れる)
    private var yDomain: ClosedRange<Double> {
        let vals = sorted.flatMap { [$0.currentRating] + ($0.borderRating.map { [$0] } ?? []) }
        guard let lo = vals.min(), let hi = vals.max() else { return 0...1 }
        let span = max(hi - lo, 1)
        return (lo - span * 0.1)...(hi + span * 0.1)
    }

    /// 選択中のレコード (未選択時は最新 = Android と同じ既定)
    private var selectedRecord: GrandPrixRecord? {
        guard let pinned = pinnedDate else { return sorted.last }
        return sorted.first { BattleTimestampFormatter.date(from: $0.timestamp) == pinned } ?? sorted.last
    }

    /// 生の選択日時から最も近いレコードの日時にスナップする
    private func nearestRecordDate(to date: Date) -> Date? {
        let dates = sorted.compactMap { BattleTimestampFormatter.date(from: $0.timestamp) }
        return dates.min { abs($0.timeIntervalSince(date)) < abs($1.timeIntervalSince(date)) }
    }

    private var chartCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("レーティング推移").font(.caption.bold()).foregroundStyle(.gray)
            selectionInfoRow
            if chartZoomed, let domain = zoomDomain {
                ratingChart
                    .chartScrollableAxes(.horizontal)
                    .chartXVisibleDomain(length: domain.length)
                    .chartScrollPosition(initialX: domain.start)
            } else {
                ratingChart
            }
            // 凡例 + ズームトグルはグラフ (横軸ラベル) の下 (Android と統一)
            HStack(spacing: 12) {
                Text("● 自分").font(.caption).foregroundStyle(Color.recCoral)
                Text("● ボーダー").font(.caption).foregroundStyle(.cyan)
                Spacer()
                // レコードが多いときだけ「直近N戦ズーム+横スクロール」への切替を出す
                if sorted.count > zoomBattleCount {
                    Button(chartZoomed ? "全体表示" : "直近\(zoomBattleCount)戦") {
                        chartZoomed.toggle()
                    }
                    .font(.caption.bold()).foregroundStyle(Color.recCoral)
                }
            }
        }
        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.cardBackground).clipShape(RoundedRectangle(cornerRadius: 12))
    }

    /// 選択点の詳細 (Android のツールチップ相当): エンブレム + R/B + 日時
    private var selectionInfoRow: some View {
        HStack(spacing: 8) {
            if let sel = selectedRecord {
                RankBadge(tier: sel.rankTier, height: 20)
                Text("R:" + String(format: "%.1f", sel.currentRating))
                    .font(.footnote.bold()).foregroundStyle(Color.recCoral)
                Text("B:" + (sel.borderRating.map { String(format: "%.1f", $0) } ?? "—"))
                    .font(.footnote.bold()).foregroundStyle(.cyan)
                Text(shortDateTime(sel.timestamp)).font(.caption2).foregroundStyle(.gray)
            }
            Spacer()
        }
        .lineLimit(1).minimumScaleFactor(0.7)
    }

    private var ratingChart: some View {
        Chart {
            ForEach(chartPoints) { pt in
                LineMark(x: .value("日時", pt.date), y: .value("レーティング", pt.rating))
                    .foregroundStyle(by: .value("系列", pt.series))
                    .symbol(.circle)
                    .symbolSize(24)
            }
            // 選択点: 縦ルーラー + 白い強調点 (Android のインジケーターと同じ)
            if let sel = selectedRecord,
               let d = BattleTimestampFormatter.date(from: sel.timestamp) {
                RuleMark(x: .value("日時", d))
                    .foregroundStyle(.white.opacity(0.5))
                    .lineStyle(StrokeStyle(lineWidth: 1))
                PointMark(x: .value("日時", d), y: .value("レーティング", sel.currentRating))
                    .foregroundStyle(.white)
                    .symbolSize(60)
            }
        }
        .chartForegroundStyleScale(["自分": Color.recCoral, "ボーダー": Color.cyan])
        .chartYScale(domain: yDomain)
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 3)) { value in
                AxisGridLine()
                if let d = value.as(Date.self) {
                    // 2 行ラベル (日付/時刻) で横幅を抑え、ラベル同士の重なりを防ぐ
                    AxisValueLabel {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(d, format: .dateTime.month(.defaultDigits).day())
                            Text(d, format: .dateTime.hour().minute())
                        }
                    }
                }
            }
        }
        .chartXSelection(value: $rawSelection)
        .onChange(of: rawSelection) { _, new in
            if let d = new { pinnedDate = nearestRecordDate(to: d) }
        }
        .chartLegend(.hidden)   // 凡例は chartCard 側でグラフ下に自前描画 (Android と統一)
        .frame(height: 300)
    }

    /// ズーム時の可視範囲: 直近 zoomBattleCount 戦ぶんの時間幅 (最低10分)。
    /// initialX = その先頭日時 (開いた時点で最新側が見える)
    private var zoomDomain: (start: Date, length: TimeInterval)? {
        let dates = sorted.compactMap { BattleTimestampFormatter.date(from: $0.timestamp) }
        guard let last = dates.last, dates.count > zoomBattleCount else { return nil }
        let start = dates[dates.count - zoomBattleCount]
        let length = max(last.timeIntervalSince(start), 600)
        return (start, length)
    }

    // MARK: - List

    // メイン戦績と同じ「1 レコード = 1 カード」形式。
    // タイトル行は Section ヘッダーとしてスクロール中も上部に固定される。
    private var listView: some View {
        Section {
            VStack(spacing: 6) {
                ForEach(Array(sorted.enumerated().reversed()), id: \.element.id) { idx, r in
                    Button { editing = r } label: {
                        recordRow(r, battleNo: idx + 1, delta: delta(at: idx))
                            .background(Color.cardBackground)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                    .buttonStyle(.plain)
                }
            }
        } header: {
            listHeader.background(Color.black)   // 固定時に下のカードが透けないよう黒背景
        }
    }

    private var listHeader: some View {
        HStack(spacing: 6) {
            Text("日時").frame(width: 56, alignment: .leading)
            Text("ランク").frame(width: 40, alignment: .leading)
            Text("戦").frame(width: 36, alignment: .trailing)   // 大会中は4桁になり得る
            Text("レーティング").frame(maxWidth: .infinity, alignment: .trailing)
            Text("変動").frame(width: 52, alignment: .trailing)
            Text("ボーダー").frame(width: 60, alignment: .trailing)
        }
        .font(.caption2).foregroundStyle(.gray)
        .lineLimit(1).minimumScaleFactor(0.7)   // Dynamic Type 大でも折り返さず縮小
        .padding(.horizontal, 12).padding(.vertical, 8)
    }

    private func delta(at idx: Int) -> Double? {
        guard idx > 0 else { return nil }
        return sorted[idx].currentRating - sorted[idx - 1].currentRating
    }

    private func recordRow(_ r: GrandPrixRecord, battleNo: Int, delta: Double?) -> some View {
        HStack(spacing: 6) {
            // 日時 (メイン戦績と同じ 2 行: 2026.08.29 / 22:05)
            let ts = splitTimestamp(r.timestamp)
            VStack(alignment: .leading, spacing: 1) {
                Text(ts.date).font(.system(size: 9))
                Text(ts.time).font(.system(size: 9))
            }
            .foregroundStyle(.white)
            .frame(width: 56, alignment: .leading)
            // ランク列 (エンブレムサムネイル)
            HStack(spacing: 0) {
                if r.rankTier != nil { RankBadge(tier: r.rankTier, height: 24) }
            }
            .frame(width: 40, alignment: .leading)
            Text("\(battleNo)").font(.caption2).foregroundStyle(.gray).frame(width: 36, alignment: .trailing)
            Text(String(format: "%.1f", r.currentRating)).font(.subheadline.bold()).foregroundStyle(.white)
                .frame(maxWidth: .infinity, alignment: .trailing)
            Text(delta.map { String(format: "%@%.1f", $0 >= 0 ? "+" : "", $0) } ?? "—")
                .font(.caption2).foregroundStyle(delta.map { $0 >= 0 ? Color.recCoral : Color.cyan } ?? .gray)
                .frame(width: 52, alignment: .trailing)
            Text(r.borderRating.map { String(format: "%.1f", $0) } ?? "—")
                .font(.caption2).foregroundStyle(.cyan.opacity(0.9)).frame(width: 60, alignment: .trailing)
        }
        .lineLimit(1).minimumScaleFactor(0.6)   // Dynamic Type 大でも数値を折り返さず縮小
        .padding(.horizontal, 12).padding(.vertical, 10).contentShape(Rectangle())
    }

    /// 表示専用の分割 (メイン戦績と同じ作法)。日付はドット区切り、時刻は HH:mm。
    /// (保存形式は yyyy-MM-dd HH:mm:ss のまま変更しない)
    private func splitTimestamp(_ ts: String) -> (date: String, time: String) {
        let parts = ts.split(separator: " ", maxSplits: 1)
        if parts.count == 2 {
            return (String(parts[0]).replacingOccurrences(of: "-", with: "."),
                    String(parts[1].prefix(5)))
        }
        return (ts.replacingOccurrences(of: "-", with: "."), "")
    }

    private func shortDateTime(_ ts: String) -> String {
        guard let d = BattleTimestampFormatter.date(from: ts) else { return ts }
        let f = DateFormatter(); f.dateFormat = "M/d HH:mm"; f.locale = Locale(identifier: "en_US_POSIX")
        return f.string(from: d)
    }
}

/// 追加フォームの初期値 (日時 + 引き継ぐランク帯) を .sheet(item:) に載せる用
private struct FormSeed: Identifiable {
    let date: Date
    let rankTier: String?
    var id: TimeInterval { date.timeIntervalSince1970 }
}

/// ランク帯のエンブレムサムネイル。tier が未設定/対象外なら何も描かない。
/// (色バッジ版は GrandPrixRecord.rankBadge に定義が残っており差し戻し可)
struct RankBadge: View {
    let tier: String?
    var height: CGFloat = 16

    var body: some View {
        if let asset = GrandPrixRecord.rankEmblemAsset(tier) {
            Image(asset)
                .resizable()
                .scaledToFit()
                .frame(height: height)
        }
    }
}

/// グランプリ記録の操作メニュー (メイン戦績の RecordEditMenu と同じ作法)。
/// 編集 (日時/レーティング/ボーダー/ランク帯を一括) / 削除 / 次に追加 の 3 択。
private struct GrandPrixEditMenu: View {
    let record: GrandPrixRecord
    let onEdit: () -> Void
    let onDelete: () -> Void
    let onAddAfter: (GrandPrixRecord) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var showDeleteConfirm = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        onEdit(); dismiss()
                    } label: {
                        rowLabel("square.and.pencil", "このレコードを編集")
                    }
                }
                Section {
                    Button(role: .destructive) { showDeleteConfirm = true } label: {
                        rowLabel("trash", "このレコードを削除", tint: .red)
                    }
                    Button {
                        onAddAfter(record); dismiss()
                    } label: {
                        rowLabel("plus.circle", "このレコードの次にスコアを追加")
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(Color.black)
            .navigationTitle("グランプリ記録")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("閉じる") { dismiss() } }
            }
            .confirmationDialog("このレコードを削除しますか?", isPresented: $showDeleteConfirm, titleVisibility: .visible) {
                Button("削除", role: .destructive) { onDelete(); dismiss() }
                Button("キャンセル", role: .cancel) {}
            }
        }
    }

    private func rowLabel(_ icon: String, _ title: String, tint: Color = .white) -> some View {
        HStack {
            Image(systemName: icon).frame(width: 28)
                .foregroundStyle(tint == .red ? Color.red : Color.recCoral)
            Text(title).foregroundStyle(tint)
            Spacer()
        }
    }
}

/// グランプリ記録の入力フォーム (編集/追加 共用)。
/// 日時・レーティング・ボーダー・ランク帯を一度に編集できる。
private struct GrandPrixFormSheet: View {
    let title: String
    let initialDate: Date
    let initialRating: String
    let initialBorder: String
    let initialTier: String?
    /// (日時, レーティング, ボーダー(なし=nil), ランク帯(未設定=nil))
    let onSave: (Date, Double, Double?, String?) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var date = Date()
    @State private var ratingText = ""
    @State private var borderText = ""
    @State private var tier: String? = nil

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker("日時", selection: $date)
                    HStack {
                        Text("レーティング"); Spacer()
                        TextField("例 2208.1", text: $ratingText)
                            .keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 120)
                    }
                    HStack {
                        Text("ボーダー"); Spacer()
                        TextField("空欄=なし", text: $borderText)
                            .keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 120)
                    }
                    Picker("ランク帯", selection: $tier) {
                        Text("未設定").tag(String?.none)
                        ForEach(GrandPrixRecord.rankTiers, id: \.self) { t in
                            Text(t).tag(String?.some(t))
                        }
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("キャンセル") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        guard let rating = Double(ratingText) else { return }
                        onSave(date, rating, Double(borderText), tier)
                        dismiss()
                    }
                    .disabled(Double(ratingText) == nil)
                }
            }
            .onAppear {
                date = initialDate
                ratingText = initialRating
                borderText = initialBorder
                tier = initialTier
            }
        }
    }
}
