import SwiftUI
import NakamonREC_Shared

/// 戦績 JSON ファイルを複数選択してマージするビュー
/// - 全 JSON ファイルをチェックボックスリスト表示
/// - 2 つ以上選択 + 新規ファイル名指定 → 全選択ファイルのレコードを統合し新規ファイル作成
/// - 重複は timestamp で判定 (同一 timestamp は最初に出現したものを保持)
struct FileMergeView: View {
    /// マージ後の新規ファイル名を返す。キャンセル時は空文字
    var onComplete: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var files: [String] = BattleHistoryStore.shared.availableFiles()
    @State private var selected: Set<String> = []
    @State private var newFileName: String = ""
    @State private var errorMessage: String? = nil
    @State private var infoMessage: String? = nil

    private var canMerge: Bool {
        selected.count >= 2 && !newFileName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 0) {
                    // 新規ファイル名入力
                    VStack(alignment: .leading, spacing: 8) {
                        Text("マージ後の新規ファイル名")
                            .font(.caption)
                            .foregroundStyle(.gray)
                        TextField("ファイル名", text: $newFileName)
                            .textFieldStyle(.roundedBorder)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                    .padding(.bottom, 8)

                    Divider().background(Color.gray.opacity(0.3))

                    // ファイル一覧 (多重選択)
                    List {
                        ForEach(files, id: \.self) { name in
                            Button {
                                if selected.contains(name) {
                                    selected.remove(name)
                                } else {
                                    selected.insert(name)
                                }
                            } label: {
                                HStack {
                                    Image(systemName: selected.contains(name) ? "checkmark.square.fill" : "square")
                                        .foregroundStyle(selected.contains(name) ? Color.recCoral : .gray)
                                    Text(stripExtension(name))
                                        .foregroundStyle(.white)
                                    Spacer()
                                }
                            }
                            .listRowBackground(Color.cardBackground)
                        }
                    }
                    .listStyle(.insetGrouped)
                    .scrollContentBackground(.hidden)
                }
            }
            .navigationTitle("ファイルマージ")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("キャンセル") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("実行") { runMerge() }
                        .disabled(!canMerge)
                }
            }
            .onAppear {
                files = BattleHistoryStore.shared.availableFiles()
                newFileName = defaultNewFileName()
            }
            .alert("エラー", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } })) {
                Button("OK") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
            .alert("完了", isPresented: Binding(
                get: { infoMessage != nil },
                set: { if !$0 { infoMessage = nil } })) {
                Button("OK") {
                    let msg = infoMessage
                    infoMessage = nil
                    if msg != nil {
                        dismiss()
                    }
                }
            } message: {
                Text(infoMessage ?? "")
            }
        }
    }

    private func runMerge() {
        let trimmed = newFileName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let target = trimmed.hasSuffix(".json") ? trimmed : "\(trimmed).json"
        if BattleHistoryStore.shared.availableFiles().contains(target) {
            errorMessage = "同名のファイル「\(stripExtension(target))」が既に存在します。"
            return
        }
        var seenTimestamps = Set<String>()
        var mergedRecords: [BattleRecord] = []
        for name in selected {
            let history = BattleHistoryStore.shared.load(fileName: name)
            for r in history.records where !seenTimestamps.contains(r.timestamp) {
                seenTimestamps.insert(r.timestamp)
                mergedRecords.append(r)
            }
        }
        // timestamp 昇順 (古い順)
        mergedRecords.sort { $0.timestamp < $1.timestamp }
        var newHistory = BattleHistory()
        newHistory.records = mergedRecords
        newHistory.recomputeTotals()
        BattleHistoryStore.shared.save(newHistory, fileName: target)
        onComplete(target)
        infoMessage = "\(selected.count) ファイルから \(mergedRecords.count) 件をマージし、「\(stripExtension(target))」を作成しました。"
    }

    private func stripExtension(_ name: String) -> String {
        name.hasSuffix(".json") ? String(name.dropLast(5)) : name
    }

    private func defaultNewFileName() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone.current
        formatter.dateFormat = "yyyyMMdd"
        return "merged_\(formatter.string(from: Date()))"
    }
}
