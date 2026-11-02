//
//  MemoEditSheet.swift
//  NakamonREC-iOS
//
//  レコードのメモ入力 (2026-11-02 ビーフ設計、Android `showMemoDialog` と同仕様)。
//  最大 BattleRecordMemo.maxLength 文字、残数表示、保存 / キャンセル / 削除 (メモがあるときのみ)。
//

import SwiftUI
import NakamonREC_Shared

struct MemoEditSheet: View {
    let record: BattleRecord
    /// 保存 (memo を更新したレコード) または削除 (memo = nil) のときに呼ばれる
    let onSave: (BattleRecord) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var text: String
    @FocusState private var focused: Bool

    private let maxLength = BattleRecordMemo.maxLength

    init(record: BattleRecord, onSave: @escaping (BattleRecord) -> Void) {
        self.record = record
        self.onSave = onSave
        _text = State(initialValue: record.memo ?? "")
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                List {
                    Section {
                        TextField("例：初手あまいいき", text: $text, axis: .vertical)
                            .lineLimit(1...3)
                            .focused($focused)
                            .foregroundStyle(.white)
                            .onChange(of: text) { _, newValue in
                                // 入力中も上限で切る (ペースト対策)
                                if newValue.count > maxLength {
                                    text = String(newValue.prefix(maxLength))
                                }
                            }
                        HStack {
                            Spacer()
                            Text("\(text.count)/\(maxLength)")
                                .font(.caption)
                                .foregroundStyle(.gray)
                        }
                    }
                    .listRowBackground(Color.cardBackground)

                    if record.hasMemo {
                        Section {
                            Button(role: .destructive) {
                                var copy = record
                                copy.memo = nil
                                onSave(copy)
                                dismiss()
                            } label: {
                                HStack {
                                    Image(systemName: "trash")
                                        .frame(width: 28)
                                    Text("メモを削除")
                                    Spacer()
                                }
                                .foregroundStyle(.red)
                            }
                        }
                        .listRowBackground(Color.cardBackground)
                    }
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
            }
            .navigationTitle(record.hasMemo ? "メモを編集" : "メモを追加")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("キャンセル") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("保存") {
                        var copy = record
                        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
                        copy.memo = trimmed.isEmpty ? nil : String(trimmed.prefix(maxLength))
                        onSave(copy)
                        dismiss()
                    }
                    .bold()
                }
            }
            .onAppear { focused = true }
        }
    }
}
