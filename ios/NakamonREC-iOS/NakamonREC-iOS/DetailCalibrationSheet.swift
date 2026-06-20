import SwiftUI
import NakamonREC_Shared

/// 詳細校正パネル (CalibrationView 上に overlay で表示される)。
/// - 4×2 グリッドで実際の VS 画面と同じ並びを表現:
///   - 上段: 敵 0〜3 (slot index 4..7)
///   - 下段: 味方 0〜3 (slot index 0..3)
/// - 背景は半透明 → インポート済み VS スクショが透けて見えるので、画面に居る monster の
///   位置を確認しながら指定できる
/// - 各セルをタップでモンスターピッカー (サムネのみのグリッド) を開く
/// - 全 8 スロット指定後、「校正開始」ボタンで即実行
struct DetailCalibrationSheet: View {
    @Binding var slotIds: [String?]
    let onChange: () -> Void
    let onStart: () -> Void
    let onClose: () -> Void

    @State private var pickerSlotIdx: Int? = nil

    private var allAssigned: Bool { slotIds.allSatisfy { $0 != nil } }

    var body: some View {
        ZStack {
            // 半透明 → CalibrationView のインポート済みスクショが透けて見える
            Color.black.opacity(0.35).ignoresSafeArea()

            VStack(spacing: 10) {
                // Top bar (戻る / タイトル / 校正開始)
                HStack {
                    Button("戻る") { onClose() }
                        .buttonStyle(.borderedProminent)
                        .tint(.gray)
                    Spacer()
                    Text("詳細校正の指定")
                        .font(.headline.bold())
                        .foregroundStyle(.white)
                    Spacer()
                    Button("校正開始") {
                        onStart()
                        onClose()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.recCoral)
                    .disabled(!allAssigned)
                }
                .padding(.horizontal, 10)
                .padding(.top, 8)

                // 上下の Spacer で 8 体スロットを縦方向の中央に配置
                Spacer()

                Text("各スロットの「居るモンスター」を指定して校正開始を押してください。")
                    .font(.caption)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(Color.black.opacity(0.6))
                    .clipShape(RoundedRectangle(cornerRadius: 4))

                // 上段: 敵 0〜3 (slot index 4..7)
                slotRow(indices: 4...7, prefix: "敵")
                // 下段: 味方 0〜3 (slot index 0..3)
                slotRow(indices: 0...3, prefix: "自")

                Spacer()
            }
            // 下に約 1 サムネ分のパディングを追加して 8 体グリッドを中央より上に寄せる
            .padding(.bottom, 100)
        }
        .sheet(item: Binding(
            get: { pickerSlotIdx.map(IdentifiableInt.init) },
            set: { pickerSlotIdx = $0?.value })) { wrapper in
            MonsterPickerGridSheet(currentId: slotIds[wrapper.value]) { picked in
                slotIds[wrapper.value] = picked
                onChange()
                pickerSlotIdx = nil
            }
        }
    }

    @ViewBuilder
    private func slotRow(indices: ClosedRange<Int>, prefix: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            ForEach(Array(indices), id: \.self) { idx in
                let slotInRow = idx % 4
                slotCell(slotIndex: idx, label: "\(prefix)\(slotInRow)")
            }
        }
        .padding(.horizontal, 12)
    }

    private func slotCell(slotIndex: Int, label: String) -> some View {
        Button {
            pickerSlotIdx = slotIndex
        } label: {
            VStack(spacing: 2) {
                Text(label)
                    .font(.caption2.bold())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 4).padding(.vertical, 1)
                    .background(Color.black.opacity(0.6))
                    .clipShape(RoundedRectangle(cornerRadius: 3))
                ZStack {
                    Color(white: 0.2)
                    if let id = slotIds[slotIndex],
                       let path = Bundle.main.path(forResource: id, ofType: "png", inDirectory: "templates"),
                       let img = UIImage(contentsOfFile: path) {
                        Image(uiImage: img)
                            .resizable()
                            .scaledToFill()
                    } else {
                        Image(systemName: "questionmark")
                            .font(.caption)
                            .foregroundStyle(.gray)
                    }
                }
                .frame(width: 48, height: 48)
                .clipShape(RoundedRectangle(cornerRadius: 5))
                .overlay(
                    RoundedRectangle(cornerRadius: 5)
                        .stroke(slotIds[slotIndex] == nil ? Color.orange : Color.recCoral,
                                lineWidth: 1)
                )
                Text(slotIds[slotIndex].map(MonsterCatalog.name(for:)) ?? "未指定")
                    .font(.system(size: 9))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 3).padding(.vertical, 1)
                    .background(Color.black.opacity(0.55))
                    .clipShape(RoundedRectangle(cornerRadius: 3))
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
    }
}

private struct IdentifiableInt: Identifiable {
    let value: Int
    var id: Int { value }
}

/// 軽負荷モードと同じスタイルのモンスター選択 UI。
/// サムネ 70pt + 名前付きの adaptive グリッド。タップで即選択 + 即閉じる
/// (Android `showMonsterPickerForSlot` と同等の操作感)。
struct MonsterPickerGridSheet: View {
    let currentId: String?
    let onPick: (String?) -> Void

    @Environment(\.dismiss) private var dismiss

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
            .navigationTitle("モンスター選択")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("戻る") { dismiss() }
                }
            }
        }
    }

    private func cell(_ entry: MonsterEntry) -> some View {
        let isOn = entry.id == currentId
        return Button {
            onPick(entry.id)
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
}
