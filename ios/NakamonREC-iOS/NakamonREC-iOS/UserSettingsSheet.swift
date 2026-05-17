import SwiftUI
import NakamonREC_Shared

/// ユーザー設定シート (Android `ユーザー設定` 相当)
/// Step A (現在): UI スケルトンのみ。校正各項目と軽負荷モードは disabled プレースホルダ
/// Step B で軽負荷モード、Step C で校正の各項目が機能する
struct UserSettingsSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                List {
                    Section {
                        ForEach(CalibrationScreen.allCases) { screen in
                            calibrationRow(screen)
                        }
                    } header: {
                        Text("キャプチャー画面の校正")
                            .foregroundStyle(.gray)
                    } footer: {
                        Text("(Step C で機能予定)")
                            .font(.caption2)
                            .foregroundStyle(.gray)
                    }

                    Section {
                        HStack {
                            Image(systemName: "power")
                                .foregroundStyle(.gray)
                                .frame(width: 28)
                            Text("軽負荷モード (指定モンスターのみ)")
                                .foregroundStyle(.white)
                            Spacer()
                            Toggle("", isOn: .constant(false))
                                .labelsHidden()
                                .disabled(true)
                        }
                        .listRowBackground(Color.cardBackground)
                    } footer: {
                        Text("(Step B で機能予定)")
                            .font(.caption2)
                            .foregroundStyle(.gray)
                    }
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)
            }
            .navigationTitle("ユーザー設定")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("閉じる") { dismiss() }
                }
            }
        }
    }

    private func calibrationRow(_ screen: CalibrationScreen) -> some View {
        HStack {
            Image(systemName: screen.iconName)
                .foregroundStyle(.gray)
                .frame(width: 28)
            Text(screen.title)
                .foregroundStyle(.white)
            Spacer()
            Image(systemName: "chevron.right")
                .foregroundStyle(.gray)
        }
        .opacity(0.5)
        .listRowBackground(Color.cardBackground)
    }
}

/// 校正対象の 4 画面
enum CalibrationScreen: String, CaseIterable, Identifiable {
    case partySelect
    case battlePrep
    case win
    case lose

    var id: String { rawValue }

    var title: String {
        switch self {
        case .partySelect: return "パーティ選択画面"
        case .battlePrep:  return "対戦じゅんび画面"
        case .win:         return "勝利画面"
        case .lose:        return "ざんねん画面"
        }
    }

    var iconName: String {
        switch self {
        case .partySelect: return "person.3.fill"
        case .battlePrep:  return "shield.lefthalf.filled"
        case .win:         return "crown.fill"
        case .lose:        return "xmark.octagon.fill"
        }
    }
}
