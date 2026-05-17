import SwiftUI
import NakamonREC_Shared

/// 戦績レコード編集ポップアップ (Android `戦績履歴レコード編集画面` 相当)
struct RecordEditMenu: View {
    let record: BattleRecord
    /// ユーザーが変更を確定したときの呼び出し。引数 nil = レコード削除
    let onApply: (BattleRecord?) -> Void
    /// 次に追加: 引数は選択されたパーティ index (0=P1, 1=P2, 2=P3)
    let onAddNext: (Int) -> Void
    let onChangeMonsters: () -> Void
    let onShowMatchingScore: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var showDeleteConfirm = false
    @State private var showPartyPicker = false
    @State private var showAddNextPartyPicker = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        let toggled = record.result == "WIN" ? "LOSE" : "WIN"
                        var copy = record
                        copy.result = toggled
                        onApply(copy)
                        dismiss()
                    } label: {
                        rowLabel(systemIcon: "arrow.left.arrow.right",
                                 title: "勝敗を修正 (→\(record.result == "WIN" ? "LOSE" : "WIN"))")
                    }

                    Button {
                        showPartyPicker = true
                    } label: {
                        rowLabel(systemIcon: "person.3.fill",
                                 title: "選択パーティを修正 (現在: \(partyLabel))")
                    }

                    Button {
                        dismiss()
                        // dismiss してから親側で monster picker を出す
                        DispatchQueue.main.async { onChangeMonsters() }
                    } label: {
                        rowLabel(systemIcon: "figure.run.circle",
                                 title: "使用モンスターを修正")
                    }
                }

                Section {
                    Button(role: .destructive) {
                        showDeleteConfirm = true
                    } label: {
                        rowLabel(systemIcon: "trash", title: "この1戦を削除", tint: .red)
                    }

                    Button {
                        showAddNextPartyPicker = true
                    } label: {
                        rowLabel(systemIcon: "plus.circle", title: "この1戦の次に戦績を追加")
                    }
                }

                Section {
                    Button {
                        dismiss()
                        DispatchQueue.main.async { onShowMatchingScore() }
                    } label: {
                        rowLabel(systemIcon: "scope", title: "マッチングスコアを確認")
                    }
                    .disabled(true)
                    .opacity(0.4)
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(Color.black)
            .navigationTitle("レコードの編集")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("閉じる") { dismiss() }
                }
            }
            .confirmationDialog("この1戦を削除しますか？", isPresented: $showDeleteConfirm, titleVisibility: .visible) {
                Button("削除", role: .destructive) {
                    onApply(nil)
                    dismiss()
                }
                Button("キャンセル", role: .cancel) {}
            }
            .sheet(isPresented: $showPartyPicker) {
                PartyPicker(current: record.partyIndex, includeUnset: true) { newIdx in
                    var copy = record
                    copy.partyIndex = newIdx
                    onApply(copy)
                    showPartyPicker = false
                    dismiss()
                }
                .presentationDetents([.fraction(0.35)])
            }
            .sheet(isPresented: $showAddNextPartyPicker) {
                PartyPicker(current: -1, includeUnset: false) { idx in
                    onAddNext(idx)
                    showAddNextPartyPicker = false
                    dismiss()
                }
                .presentationDetents([.fraction(0.35)])
            }
        }
    }

    private var partyLabel: String {
        record.partyIndex >= 0 ? "P\(record.partyIndex + 1)" : "P?"
    }

    private func rowLabel(systemIcon: String, title: String, tint: Color = .white) -> some View {
        HStack {
            Image(systemName: systemIcon)
                .frame(width: 28)
                .foregroundStyle(tint == .red ? Color.red : Color.recCoral)
            Text(title)
                .foregroundStyle(tint)
            Spacer()
        }
    }
}

/// パーティ選択用サブシート
/// - `includeUnset`: 「未設定 (P?)」も選択肢に含めるか
private struct PartyPicker: View {
    let current: Int
    let includeUnset: Bool
    let onSelect: (Int) -> Void

    private var choices: [Int] {
        includeUnset ? Array(-1...2) : Array(0...2)
    }

    var body: some View {
        NavigationStack {
            List {
                ForEach(choices, id: \.self) { idx in
                    Button {
                        onSelect(idx)
                    } label: {
                        HStack {
                            Text(idx >= 0 ? "P\(idx + 1)" : "未設定 (P?)")
                                .foregroundStyle(.white)
                            Spacer()
                            if idx == current {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(Color.recCoral)
                            }
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(Color.black)
            .navigationTitle("パーティ選択")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
