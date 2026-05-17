import SwiftUI
import NakamonREC_Shared

/// 使用モンスター修正画面
/// - 8 スロット (味方 4 + 敵 4) を表示
/// - スロットタップでモンスターピッカーを開いて差し替え
/// - 一度の編集セッションで複数スロットを順に修正できる (ピッカー側でモンスターを選ぶと自動で閉じ、編集画面に戻る)
/// - ユーザーが下スワイプ等で編集画面自体を閉じるまで開きっぱなし
struct MonsterPartyEditor: View {
    let originalRecord: BattleRecord
    /// 編集中の draft が変わるたびに呼ばれる (即時保存)
    let onApply: (BattleRecord) -> Void

    @State private var draft: BattleRecord
    @State private var pickerSlot: SlotKey? = nil
    @Environment(\.dismiss) private var dismiss

    init(record: BattleRecord, onApply: @escaping (BattleRecord) -> Void) {
        self.originalRecord = record
        self.onApply = onApply
        // 4 体未満で保存されている可能性に備えて "?" で埋める
        var safeRecord = record
        while safeRecord.myParty.count < 4 { safeRecord.myParty.append("?") }
        while safeRecord.enemyParty.count < 4 { safeRecord.enemyParty.append("?") }
        self._draft = State(initialValue: safeRecord)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                VStack(spacing: 20) {
                    Text("スロットをタップして差し替えてください")
                        .font(.caption)
                        .foregroundStyle(.gray)
                        .padding(.top, 16)

                    // 味方 4 体 (1 行)
                    VStack(spacing: 4) {
                        Text("味方")
                            .font(.caption.bold())
                            .foregroundStyle(Color.sideMy)
                        HStack(spacing: 8) {
                            ForEach(0..<4, id: \.self) { i in
                                slotButton(isEnemy: false, idx: i, name: draft.myParty[safe: i])
                            }
                        }
                    }

                    Text("VS")
                        .font(.title3.bold())
                        .foregroundStyle(.white)

                    // 敵 4 体 (1 行)
                    VStack(spacing: 4) {
                        Text("敵")
                            .font(.caption.bold())
                            .foregroundStyle(Color.sideEnemy)
                        HStack(spacing: 8) {
                            ForEach(0..<4, id: \.self) { i in
                                slotButton(isEnemy: true, idx: i, name: draft.enemyParty[safe: i])
                            }
                        }
                    }

                    Spacer()
                }
                .padding(.horizontal, 8)
            }
            .navigationTitle("使用モンスターを修正")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("閉じる") { dismiss() }
                }
            }
            .sheet(item: $pickerSlot) { slot in
                MonsterPickerGrid { picked in
                    if slot.isEnemy {
                        draft.enemyParty[slot.idx] = picked
                    } else {
                        draft.myParty[slot.idx] = picked
                    }
                    onApply(draft)  // 即時保存
                    pickerSlot = nil  // ピッカーを閉じ、編集画面は維持
                }
            }
        }
    }

    private func slotButton(isEnemy: Bool, idx: Int, name: String?) -> some View {
        Button {
            pickerSlot = SlotKey(isEnemy: isEnemy, idx: idx)
        } label: {
            VStack(spacing: 2) {
                MonsterThumb(name: name, size: 70)
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(isEnemy ? Color.sideEnemy : Color.sideMy, lineWidth: 2)
                    )
                Text("[\(idx)]")
                    .font(.system(size: 10))
                    .foregroundStyle(.gray)
            }
        }
    }
}

private struct SlotKey: Identifiable {
    let isEnemy: Bool
    let idx: Int
    var id: String { "\(isEnemy)-\(idx)" }
}

/// モンスター選択用グリッド (Android 軽負荷モード画面の選択 UI と同等)
/// `monsters.json` に定義されたモンスターのみを 4 列グリッドで表示。タップで選択。
struct MonsterPickerGrid: View {
    let onSelect: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 6), count: 4)

    /// monsters.json から読み込んだカタログ (静的キャッシュ)
    private static let catalog: [MonsterData] = {
        guard let url = Bundle.main.url(forResource: "monsters", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let list = try? JSONDecoder().decode([MonsterData].self, from: data) else {
            return []
        }
        return list
    }()

    /// `id001.png` → `id001` に変換
    private func templateID(from fileName: String) -> String {
        (fileName as NSString).deletingPathExtension
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 10) {
                        // 「未識別」に戻すための先頭セル
                        Button {
                            onSelect("?")
                        } label: {
                            VStack(spacing: 2) {
                                MonsterThumb(name: nil, size: 64)
                                Text("未識別")
                                    .font(.system(size: 10))
                                    .foregroundStyle(.gray)
                                    .lineLimit(1)
                            }
                        }
                        ForEach(Self.catalog) { monster in
                            let tid = templateID(from: monster.fileName)
                            Button {
                                onSelect(tid)
                            } label: {
                                VStack(spacing: 2) {
                                    MonsterThumb(name: tid, size: 64)
                                    Text(monster.name)
                                        .font(.system(size: 10))
                                        .foregroundStyle(.white)
                                        .lineLimit(1)
                                        .minimumScaleFactor(0.7)
                                }
                            }
                        }
                    }
                    .padding(8)
                }
            }
            .navigationTitle("モンスター選択 (\(Self.catalog.count) 体)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("キャンセル") { dismiss() }
                }
            }
        }
    }
}

// safe subscript は BattleHistoryView.swift で定義済み
