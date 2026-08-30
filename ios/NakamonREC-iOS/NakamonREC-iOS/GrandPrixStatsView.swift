import SwiftUI
import Charts
import NakamonREC_Shared

/// グランプリ集計画面。読込中ファイルの grandPrixRecords を、自分のレーティング折れ線 +
/// ボーダー折れ線で表示(グラフ既定)。トグルでテキスト(レコード一覧)に切替でき、
/// レコードをタップすると編集メニュー(レーティング/ボーダー/ランク帯の修正・削除・次に追加)。
/// 「1 ファイル = 1 グランプリ」前提。
struct GrandPrixStatsView: View {
    @State private var records: [GrandPrixRecord] = []
    @State private var showAsList = false          // false = グラフ(既定) / true = テキスト
    @State private var editing: GrandPrixRecord? = nil      // 編集メニュー対象
    @State private var pendingAddDate: Date? = nil         // 手動追加(初期日時)
    @Environment(\.dismiss) private var dismiss

    private var sorted: [GrandPrixRecord] {
        records.sorted {
            (BattleTimestampFormatter.date(from: $0.timestamp) ?? .distantPast)
                < (BattleTimestampFormatter.date(from: $1.timestamp) ?? .distantPast)
        }
    }
    private var maxRating: Double? { sorted.map(\.currentRating).max() }
    private var currentRating: Double? { sorted.last?.currentRating }

    private func reload() { records = BattleHistoryStore.shared.loadGrandPrixRecords() }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if sorted.isEmpty {
                    emptyState
                } else {
                    ScrollView {
                        VStack(spacing: 16) {
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
                    Button { pendingAddDate = Date() } label: { Image(systemName: "plus") }
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
                    onApply: { updated in
                        if let updated {
                            BattleHistoryStore.shared.updateGrandPrix(updated)
                        } else {
                            BattleHistoryStore.shared.deleteGrandPrix(id: rec.id)
                        }
                        reload()
                    },
                    onAddAfter: { r in
                        // この記録の 1 秒後を初期日時にして追加フォームを開く
                        let base = BattleTimestampFormatter.date(from: r.timestamp) ?? Date()
                        pendingAddDate = base.addingTimeInterval(1)
                    }
                )
            }
            .sheet(item: Binding(get: { pendingAddDate.map { DateBox(date: $0) } },
                                 set: { pendingAddDate = $0?.date })) { box in
                GrandPrixAddSheet(initialDate: box.date) { new in
                    BattleHistoryStore.shared.appendGrandPrix(new)
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
            Button("手動で追加") { pendingAddDate = Date() }
                .foregroundStyle(Color.recCoral).padding(.top, 8)
        }
        .padding(32)
    }

    // MARK: - Summary header (現在 | 最高)

    private var summaryHeader: some View {
        HStack(spacing: 0) {
            VStack(spacing: 4) {
                Text("現在レーティング").font(.caption2).foregroundStyle(.gray)
                Text(currentRating.map { String(format: "%.1f", $0) } ?? "—")
                    .font(.system(size: 30, weight: .bold)).foregroundStyle(.white)
            }
            .frame(maxWidth: .infinity)
            VStack(spacing: 4) {
                Text("最高レーティング").font(.caption2).foregroundStyle(.gray)
                Text(maxRating.map { String(format: "%.1f", $0) } ?? "—")
                    .font(.system(size: 30, weight: .bold)).foregroundStyle(Color.recCoral)
            }
            .frame(maxWidth: .infinity)
        }
        .padding(.vertical, 12)
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
    private var chartCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("レーティング推移").font(.caption.bold()).foregroundStyle(.gray)
            Chart(chartPoints) { pt in
                LineMark(x: .value("日時", pt.date), y: .value("レーティング", pt.rating))
                    .foregroundStyle(by: .value("系列", pt.series))
                    .symbol(by: .value("系列", pt.series))
            }
            .chartForegroundStyleScale(["自分": Color.recCoral, "ボーダー": Color.cyan])
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) { value in
                    AxisGridLine()
                    if let d = value.as(Date.self) {
                        AxisValueLabel { Text(d, format: .dateTime.month(.defaultDigits).day().hour().minute()) }
                    }
                }
            }
            .chartLegend(position: .top, alignment: .leading)
            .frame(height: 300)
        }
        .padding(14).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.cardBackground).clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - List

    private var listView: some View {
        VStack(spacing: 0) {
            listHeader
            Divider().overlay(Color.gray.opacity(0.3))
            ForEach(Array(sorted.enumerated().reversed()), id: \.element.id) { idx, r in
                Button { editing = r } label: {
                    recordRow(r, battleNo: idx + 1, delta: delta(at: idx))
                }
                .buttonStyle(.plain)
                if idx != 0 { Divider().overlay(Color.gray.opacity(0.2)) }
            }
        }
        .background(Color.cardBackground).clipShape(RoundedRectangle(cornerRadius: 12))
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
}

/// Identifiable ラッパ (pendingAddDate を .sheet(item:) に載せる用)
private struct DateBox: Identifiable { let date: Date; var id: TimeInterval { date.timeIntervalSince1970 } }

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

private extension Color {
    /// "RRGGBB" (# なし) から生成
    init(hex: String) {
        var v: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&v)
        self.init(.sRGB,
                  red: Double((v >> 16) & 0xFF) / 255.0,
                  green: Double((v >> 8) & 0xFF) / 255.0,
                  blue: Double(v & 0xFF) / 255.0)
    }
}

/// グランプリ記録の編集メニュー (メイン戦績の RecordEditMenu と同じ作法)。
/// タップした 1 レコードに対する操作一覧を出す。onApply(nil) = 削除。
private struct GrandPrixEditMenu: View {
    let record: GrandPrixRecord
    let onApply: (GrandPrixRecord?) -> Void
    let onAddAfter: (GrandPrixRecord) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var ratingText = ""
    @State private var borderText = ""
    @State private var showRatingEdit = false
    @State private var showBorderEdit = false
    @State private var showRankPicker = false
    @State private var showDeleteConfirm = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        ratingText = String(format: "%.1f", record.currentRating)
                        showRatingEdit = true
                    } label: {
                        rowLabel("figure.walk.motion", "レーティングスコアを修正 (現在 \(String(format: "%.1f", record.currentRating)))")
                    }
                    Button {
                        borderText = record.borderRating.map { String(format: "%.1f", $0) } ?? ""
                        showBorderEdit = true
                    } label: {
                        rowLabel("flag.checkered", "ボーダースコアを修正 (現在 \(record.borderRating.map { String(format: "%.1f", $0) } ?? "なし"))")
                    }
                    Button {
                        showRankPicker = true
                    } label: {
                        rowLabel("rosette", "ランク帯を修正 (現在 \(record.rankTier ?? "未設定"))")
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
            .navigationTitle("グランプリ記録の編集")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("閉じる") { dismiss() } }
            }
            .alert("レーティングスコアを修正", isPresented: $showRatingEdit) {
                TextField("例 2208.1", text: $ratingText).keyboardType(.decimalPad)
                Button("保存") {
                    if let v = Double(ratingText) {
                        var c = record; c.currentRating = v; onApply(c); dismiss()
                    }
                }
                Button("キャンセル", role: .cancel) {}
            }
            .alert("ボーダースコアを修正", isPresented: $showBorderEdit) {
                TextField("空欄=なし", text: $borderText).keyboardType(.decimalPad)
                Button("保存") {
                    var c = record
                    c.neededRating = Double(borderText).map { $0 - c.currentRating }
                    onApply(c); dismiss()
                }
                Button("キャンセル", role: .cancel) {}
            }
            .sheet(isPresented: $showRankPicker) {
                RankTierPicker(current: record.rankTier) { tier in
                    var c = record; c.rankTier = tier; onApply(c)
                    showRankPicker = false; dismiss()
                }
            }
            .confirmationDialog("このレコードを削除しますか?", isPresented: $showDeleteConfirm, titleVisibility: .visible) {
                Button("削除", role: .destructive) { onApply(nil); dismiss() }
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

/// ランク帯選択サブシート
private struct RankTierPicker: View {
    let current: String?
    let onSelect: (String?) -> Void

    var body: some View {
        NavigationStack {
            List {
                Button {
                    onSelect(nil)
                } label: {
                    HStack {
                        Text("未設定").foregroundStyle(.white); Spacer()
                        if current == nil { Image(systemName: "checkmark").foregroundStyle(Color.recCoral) }
                    }
                }
                ForEach(GrandPrixRecord.rankTiers, id: \.self) { tier in
                    Button {
                        onSelect(tier)
                    } label: {
                        HStack {
                            Text(tier).foregroundStyle(.white); Spacer()
                            if current == tier { Image(systemName: "checkmark").foregroundStyle(Color.recCoral) }
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(Color.black)
            .navigationTitle("ランク帯を選択")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/// 記録漏れの手動追加フォーム (日時・レーティング・ボーダー)。勝敗は WIN 既定。
private struct GrandPrixAddSheet: View {
    let initialDate: Date
    let onSave: (GrandPrixRecord) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var date = Date()
    @State private var ratingText = ""
    @State private var borderText = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("記録の追加") {
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
                }
            }
            .navigationTitle("グランプリ記録の追加")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("キャンセル") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        guard let rating = Double(ratingText) else { return }
                        let needed = Double(borderText).map { $0 - rating }
                        let ts = BattleTimestampFormatter.formatter.string(from: date)
                        onSave(GrandPrixRecord(timestamp: ts, result: "WIN", currentRating: rating, neededRating: needed))
                        dismiss()
                    }
                    .disabled(Double(ratingText) == nil)
                }
            }
            .onAppear { date = initialDate }
        }
    }
}
