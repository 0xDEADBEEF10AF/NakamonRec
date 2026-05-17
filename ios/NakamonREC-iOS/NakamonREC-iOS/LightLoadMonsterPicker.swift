import SwiftUI
import NakamonREC_Shared

/// 軽負荷モード対象モンスターの選択画面。
/// - monsters.json 全体をグリッド表示
/// - タップで選択/解除 (即時保存)
/// - 右上「デフォルト」ボタンで Android 由来のデフォルト集合に戻す
struct LightLoadMonsterPicker: View {
    /// 選択変更が発生したときの通知 (件数表示の更新に使う)
    var onChange: () -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var selected: Set<String> = LightLoadConfig.lightMonsterIDs

    private let columns = [GridItem(.adaptive(minimum: 76), spacing: 8)]

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 8) {
                        ForEach(MonsterCatalog.all) { entry in
                            cell(entry)
                        }
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 8)
                }
            }
            .navigationTitle("対象モンスター (\(selected.count) 体)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("閉じる") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("デフォルト") {
                        LightLoadConfig.resetToDefault()
                        selected = LightLoadConfig.lightMonsterIDs
                        onChange()
                    }
                }
            }
        }
    }

    private func cell(_ entry: MonsterEntry) -> some View {
        let isOn = selected.contains(entry.id)
        return Button {
            toggle(entry.id)
        } label: {
            VStack(spacing: 2) {
                ZStack(alignment: .topTrailing) {
                    MonsterThumb(name: entry.id, size: 70)
                        .overlay(
                            RoundedRectangle(cornerRadius: 6)
                                .stroke(isOn ? Color.recCoral : Color.gray.opacity(0.3),
                                        lineWidth: isOn ? 2 : 1)
                        )
                    if isOn {
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 18))
                            .foregroundStyle(Color.recCoral, .white)
                            .offset(x: 4, y: -4)
                    }
                }
                Text(entry.name)
                    .font(.system(size: 9))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
        }
    }

    private func toggle(_ id: String) {
        if selected.contains(id) {
            selected.remove(id)
            LightLoadConfig.removeMonster(id)
        } else {
            selected.insert(id)
            LightLoadConfig.addMonster(id)
        }
        onChange()
    }
}
