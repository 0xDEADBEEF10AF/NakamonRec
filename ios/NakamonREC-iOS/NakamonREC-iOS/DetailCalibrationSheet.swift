import SwiftUI
import NakamonREC_Shared

/// 詳細校正シート: VS画面校正の opt-in モード。
/// 8 スロット (味方0..3, 敵0..3) それぞれに「ここに居るモンスター」を事前指定し、
/// 1-vs-1 マッチで磁石テンプレ問題を回避する。
/// 設定保存後にシートを閉じ、呼び出し元の CalibrationView 側で
/// 「自動校正」ボタン押下時に詳細校正フローへ分岐する。
struct DetailCalibrationSheet: View {
    @Binding var enabled: Bool
    @Binding var slotIds: [String?]
    let onChange: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var pickerSlotIdx: Int? = nil

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Toggle("詳細校正モード", isOn: $enabled)
                        .onChange(of: enabled) { _, _ in onChange() }
                    Text("自動校正で精度が出ない場合に有効化します。\n8 スロットすべてに「そこに居るモンスター」を事前指定すると、1-vs-1 マッチで磁石テンプレ干渉を回避できます。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Section("スロット指定") {
                    slotRow(index: 0, side: "味方 0")
                    slotRow(index: 1, side: "味方 1")
                    slotRow(index: 2, side: "味方 2")
                    slotRow(index: 3, side: "味方 3")
                    slotRow(index: 4, side: "敵 0")
                    slotRow(index: 5, side: "敵 1")
                    slotRow(index: 6, side: "敵 2")
                    slotRow(index: 7, side: "敵 3")
                }

                Section {
                    Button(role: .destructive) {
                        slotIds = Array(repeating: nil, count: DetailCalibrationConfig.slotCount)
                        onChange()
                    } label: {
                        Label("全スロット未指定に戻す", systemImage: "arrow.counterclockwise")
                    }
                }

                if !DetailCalibrationConfig.allSlotsAssigned {
                    Section {
                        Label("8 スロットすべての指定が必要です", systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                            .font(.caption)
                    }
                }
            }
            .navigationTitle("詳細校正の設定")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("閉じる") { dismiss() }
                }
            }
            .sheet(item: Binding(
                get: { pickerSlotIdx.map(IdentifiableInt.init) },
                set: { pickerSlotIdx = $0?.value })) { wrapper in
                MonsterPickerSheet(currentId: slotIds[wrapper.value]) { picked in
                    slotIds[wrapper.value] = picked
                    onChange()
                    pickerSlotIdx = nil
                }
            }
        }
    }

    @ViewBuilder
    private func slotRow(index: Int, side: String) -> some View {
        Button {
            pickerSlotIdx = index
        } label: {
            HStack(spacing: 12) {
                Text(side)
                    .font(.callout.bold())
                    .foregroundStyle(.white)
                    .frame(width: 60, alignment: .leading)
                if let id = slotIds[index] {
                    if let path = Bundle.main.path(forResource: id, ofType: "png", inDirectory: "templates"),
                       let img = UIImage(contentsOfFile: path) {
                        Image(uiImage: img)
                            .resizable()
                            .frame(width: 40, height: 40)
                            .clipShape(RoundedRectangle(cornerRadius: 4))
                    }
                    Text(MonsterCatalog.name(for: id))
                        .foregroundStyle(.white)
                } else {
                    Text("未指定")
                        .foregroundStyle(.orange)
                        .italic()
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(.gray)
            }
        }
    }
}

private struct IdentifiableInt: Identifiable {
    let value: Int
    var id: Int { value }
}

/// シンプルなモンスター ID ピッカー。検索 + リストから選択。
struct MonsterPickerSheet: View {
    let currentId: String?
    let onPick: (String?) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query: String = ""

    private var filteredEntries: [MonsterEntry] {
        if query.isEmpty { return MonsterCatalog.all }
        return MonsterCatalog.all.filter { entry in
            entry.name.localizedCaseInsensitiveContains(query) ||
            entry.id.localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button(role: .destructive) {
                        onPick(nil)
                    } label: {
                        Label("選択をクリア", systemImage: "xmark.circle")
                    }
                }
                Section("モンスター一覧") {
                    ForEach(filteredEntries) { entry in
                        Button {
                            onPick(entry.id)
                        } label: {
                            HStack(spacing: 12) {
                                if let path = Bundle.main.path(forResource: entry.id, ofType: "png", inDirectory: "templates"),
                                   let img = UIImage(contentsOfFile: path) {
                                    Image(uiImage: img)
                                        .resizable()
                                        .frame(width: 32, height: 32)
                                        .clipShape(RoundedRectangle(cornerRadius: 4))
                                }
                                VStack(alignment: .leading) {
                                    Text(entry.name).foregroundStyle(.white)
                                    Text(entry.id).font(.caption2).foregroundStyle(.gray)
                                }
                                Spacer()
                                if entry.id == currentId {
                                    Image(systemName: "checkmark").foregroundStyle(Color.recCoral)
                                }
                            }
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: "名前または ID で検索")
            .navigationTitle("モンスター選択")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("閉じる") { dismiss() }
                }
            }
        }
    }
}
