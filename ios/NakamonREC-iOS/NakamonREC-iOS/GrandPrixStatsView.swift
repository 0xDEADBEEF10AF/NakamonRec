import SwiftUI
import Charts
import NakamonREC_Shared

/// グランプリ集計画面。読込中ファイルの grandPrixRecords を、
/// 自分のレーティング折れ線 + ボーダー折れ線 (現在+必要あと) で時系列表示する。
/// グラフ表示がデフォルト。トグルでテキスト(レコード一覧)表示に切替でき、
/// レコードをタップすると手動編集 (OCR 誤認の訂正) できる。
/// 「1 ファイル = 1 グランプリ」前提のため、ファイル全体が 1 大会ぶん。
struct GrandPrixStatsView: View {
    @State private var records: [GrandPrixRecord] = []
    @State private var showAsList = false          // false = グラフ(既定) / true = テキスト
    @State private var editing: GrandPrixRecord? = nil
    @State private var showAdd = false             // 記録漏れの手動追加
    @Environment(\.dismiss) private var dismiss

    /// 時刻昇順に整列
    private var sorted: [GrandPrixRecord] {
        records.sorted {
            (BattleTimestampFormatter.date(from: $0.timestamp) ?? .distantPast)
                < (BattleTimestampFormatter.date(from: $1.timestamp) ?? .distantPast)
        }
    }

    private var maxRating: Double? { sorted.map(\.currentRating).max() }

    private func reload() {
        records = BattleHistoryStore.shared.loadGrandPrixRecords()
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if sorted.isEmpty {
                    emptyState
                } else {
                    ScrollView {
                        VStack(spacing: 16) {
                            maxRatingHeader
                            if showAsList { listView } else { chartCard }
                            // グラフ/テキスト 切替 (コンテンツの下に控えめに配置)
                            Button {
                                showAsList.toggle()
                            } label: {
                                Label(showAsList ? "グラフ表示" : "テキスト表示",
                                      systemImage: showAsList ? "chart.xyaxis.line" : "list.bullet")
                                    .font(.subheadline)
                            }
                            .foregroundStyle(Color.recCoral)
                            .padding(.top, 4)
                        }
                        .padding(16)
                    }
                }
            }
            .navigationTitle("グランプリ集計")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showAdd = true
                    } label: {
                        Image(systemName: "plus")
                    }
                    .foregroundStyle(Color.recCoral)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("閉じる") { dismiss() }
                        .foregroundStyle(Color.recCoral)
                }
            }
            .onAppear { reload() }
            .sheet(item: $editing) { rec in
                GrandPrixEditSheet(
                    record: rec,
                    onSave: { updated in
                        BattleHistoryStore.shared.updateGrandPrix(updated)
                        reload()
                    },
                    onDelete: {
                        BattleHistoryStore.shared.deleteGrandPrix(id: rec.id)
                        reload()
                    }
                )
            }
            .sheet(isPresented: $showAdd) {
                GrandPrixEditSheet(
                    record: nil,
                    onSave: { new in
                        BattleHistoryStore.shared.appendGrandPrix(new)
                        reload()
                    },
                    onDelete: nil
                )
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

    // MARK: - Max rating header (目立たせる)

    private var maxRatingHeader: some View {
        VStack(spacing: 4) {
            Text("最高レーティング")
                .font(.caption2)
                .foregroundStyle(.gray)
            Text(maxRating.map { String(format: "%.1f", $0) } ?? "—")
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(Color.recCoral)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 12))
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
            // 横軸は日付+時刻 (例 8/23 18:00)。グランプリ最大5日ぶんを日時比例で表示。
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) { value in
                    AxisGridLine()
                    if let d = value.as(Date.self) {
                        AxisValueLabel {
                            Text(d, format: .dateTime.month(.defaultDigits).day().hour().minute())
                        }
                    }
                }
            }
            .chartLegend(position: .top, alignment: .leading)
            .frame(height: 300)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - List (テキスト表示)

    private var listView: some View {
        VStack(spacing: 0) {
            listHeader
            Divider().overlay(Color.gray.opacity(0.3))
            // 新しい順に表示。戦闘数は時系列(古い順)の連番。
            ForEach(Array(sorted.enumerated().reversed()), id: \.element.id) { idx, r in
                Button { editing = r } label: {
                    recordRow(r, battleNo: idx + 1, delta: delta(at: idx))
                }
                .buttonStyle(.plain)
                if idx != 0 { Divider().overlay(Color.gray.opacity(0.2)) }
            }
        }
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var listHeader: some View {
        HStack(spacing: 8) {
            Text("日時").frame(width: 88, alignment: .leading)
            Text("戦").frame(width: 28, alignment: .trailing)
            Text("レーティング").frame(maxWidth: .infinity, alignment: .trailing)
            Text("変動").frame(width: 56, alignment: .trailing)
            Text("ボーダー").frame(width: 64, alignment: .trailing)
        }
        .font(.caption2)
        .foregroundStyle(.gray)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    /// その戦の変動 = 現在レーティング − 前戦の現在レーティング (読まず導出)
    private func delta(at idx: Int) -> Double? {
        guard idx > 0 else { return nil }
        return sorted[idx].currentRating - sorted[idx - 1].currentRating
    }

    private func recordRow(_ r: GrandPrixRecord, battleNo: Int, delta: Double?) -> some View {
        HStack(spacing: 8) {
            // 日時 (エンブレムサムネイルは将来ここに追加)
            Text(shortTime(r.timestamp))
                .font(.caption2)
                .foregroundStyle(.white)
                .frame(width: 88, alignment: .leading)
            // 戦闘数
            Text("\(battleNo)")
                .font(.caption2)
                .foregroundStyle(.gray)
                .frame(width: 28, alignment: .trailing)
            // レーティング
            Text(String(format: "%.1f", r.currentRating))
                .font(.subheadline.bold())
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, alignment: .trailing)
            // 変動
            Text(delta.map { String(format: "%@%.1f", $0 >= 0 ? "+" : "", $0) } ?? "—")
                .font(.caption2)
                .foregroundStyle(delta.map { $0 >= 0 ? Color.recCoral : Color.cyan } ?? .gray)
                .frame(width: 56, alignment: .trailing)
            // ボーダー
            Text(r.borderRating.map { String(format: "%.1f", $0) } ?? "—")
                .font(.caption2)
                .foregroundStyle(.cyan.opacity(0.9))
                .frame(width: 64, alignment: .trailing)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }

    /// "yyyy-MM-dd HH:mm:ss" → "M/d HH:mm"
    private func shortTime(_ ts: String) -> String {
        guard let d = BattleTimestampFormatter.date(from: ts) else { return ts }
        let f = DateFormatter()
        f.dateFormat = "M/d HH:mm"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f.string(from: d)
    }
}

/// グランプリ記録の手動編集/新規追加シート (OCR 誤認の訂正・記録漏れの補完用)。
/// レーティング (現在) とボーダーを直接編集し、必要レーティングは border - current で保持する。
/// 勝敗 (WIN/LOSE) はメイン戦績側で編集できるためここでは扱わない (既存値を維持)。
/// record == nil のとき新規追加モード (日時をピッカーで指定、result は WIN 既定)。
private struct GrandPrixEditSheet: View {
    let record: GrandPrixRecord?            // nil = 新規追加
    let onSave: (GrandPrixRecord) -> Void
    let onDelete: (() -> Void)?             // 新規時は nil
    @Environment(\.dismiss) private var dismiss

    @State private var ratingText: String = ""
    @State private var borderText: String = ""
    @State private var date: Date = Date()
    @State private var showDeleteConfirm = false

    private var isNew: Bool { record == nil }

    var body: some View {
        NavigationStack {
            Form {
                Section(isNew ? "記録の追加" : "記録の訂正") {
                    if isNew {
                        DatePicker("日時", selection: $date)
                    } else {
                        LabeledContent("日時") {
                            Text(record!.timestamp).foregroundStyle(.secondary)
                        }
                    }
                    HStack {
                        Text("レーティング")
                        Spacer()
                        TextField("例 2208.1", text: $ratingText)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 120)
                    }
                    HStack {
                        Text("ボーダー")
                        Spacer()
                        TextField("空欄=なし", text: $borderText)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 120)
                    }
                }
                if onDelete != nil {
                    Section {
                        Button("この記録を削除", role: .destructive) {
                            showDeleteConfirm = true
                        }
                    }
                }
            }
            .navigationTitle(isNew ? "グランプリ記録の追加" : "グランプリ記録の編集")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("キャンセル") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                        .disabled(Double(ratingText) == nil)
                }
            }
            .onAppear {
                if let record {
                    ratingText = String(format: "%.1f", record.currentRating)
                    borderText = record.borderRating.map { String(format: "%.1f", $0) } ?? ""
                }
            }
            .confirmationDialog("この記録を削除しますか?", isPresented: $showDeleteConfirm, titleVisibility: .visible) {
                Button("削除", role: .destructive) { onDelete?(); dismiss() }
                Button("キャンセル", role: .cancel) {}
            }
        }
    }

    private func save() {
        guard let rating = Double(ratingText) else { return }
        // ボーダーが入力されていれば 必要あと = ボーダー − 現在。空欄なら nil。
        let needed: Double? = Double(borderText).map { $0 - rating }
        if let record {
            // 編集: 勝敗は維持
            var updated = record
            updated.currentRating = rating
            updated.neededRating = needed
            onSave(updated)
        } else {
            // 新規: 勝敗は WIN 既定 (GP 一覧では勝敗を表示しないため)
            let ts = BattleTimestampFormatter.formatter.string(from: date)
            let new = GrandPrixRecord(timestamp: ts, result: "WIN",
                                      currentRating: rating, neededRating: needed)
            onSave(new)
        }
        dismiss()
    }
}
