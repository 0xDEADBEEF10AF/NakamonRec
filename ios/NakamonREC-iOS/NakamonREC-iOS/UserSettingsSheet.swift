import SwiftUI
import NakamonREC_Shared

/// ユーザー設定シート (Android `ユーザー設定` 相当)
/// Step B: 軽負荷モード
/// Step C1: パーティ選択画面の校正が機能。他 3 画面は C2/C3 で実装予定
struct UserSettingsSheet: View {
    @Environment(\.dismiss) private var dismiss

    @State private var isLightMode: Bool = LightLoadConfig.mode == .light
    @State private var lightCount: Int = LightLoadConfig.lightMonsterIDs.count
    @State private var showLightPicker = false
    @State private var calibrationTarget: CalibrationScreen? = nil
    @State private var recIsActive: Bool = BroadcastStatus.isActive

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                List {
                    Section {
                        ForEach(CalibrationScreen.allCases, id: \.self) { screen in
                            calibrationRow(screen)
                        }
                    } header: {
                        Text("キャプチャー画面の校正")
                            .foregroundStyle(.gray)
                    }

                    Section {
                        HStack {
                            Image(systemName: "power")
                                .foregroundStyle(isLightMode ? Color.recCoral : .gray)
                                .frame(width: 28)
                            Text("軽負荷モード (指定モンスターのみ)")
                                .foregroundStyle(.white)
                            Spacer()
                            Toggle("", isOn: $isLightMode)
                                .labelsHidden()
                                .tint(Color.recCoral)
                                .onChange(of: isLightMode) { _, newValue in
                                    LightLoadConfig.mode = newValue ? .light : .normal
                                }
                        }
                        .listRowBackground(Color.cardBackground)
                        .disabled(recIsActive)
                        .opacity(recIsActive ? 0.4 : 1.0)

                        Button {
                            showLightPicker = true
                        } label: {
                            HStack {
                                Image(systemName: "checklist")
                                    .foregroundStyle(.gray)
                                    .frame(width: 28)
                                Text("対象モンスター (\(lightCount) 体)")
                                    .foregroundStyle(.white)
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .foregroundStyle(.gray)
                            }
                        }
                        .listRowBackground(Color.cardBackground)
                        .disabled(recIsActive)
                        .opacity(recIsActive ? 0.4 : 1.0)
                    } header: {
                        Text("解析モード")
                            .foregroundStyle(.gray)
                    } footer: {
                        if recIsActive {
                            Text("REC 中は解析モードを変更できません。\nREC を停止してから変更してください。")
                                .font(.caption2)
                                .foregroundStyle(Color.recCoral)
                        } else {
                            Text("通常モード: monsters.json の全 \(MonsterCatalog.all.count) 体を識別\n軽負荷モード: 対象モンスターのみを識別し、解析時間を短縮")
                                .font(.caption2)
                                .foregroundStyle(.gray)
                        }
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
            .sheet(isPresented: $showLightPicker) {
                LightLoadMonsterPicker {
                    lightCount = LightLoadConfig.lightMonsterIDs.count
                }
            }
            .sheet(item: $calibrationTarget) { screen in
                CalibrationActionMenu(screen: screen)
                    .presentationDetents([.fraction(0.4)])
            }
        }
    }

    private func calibrationRow(_ screen: CalibrationScreen) -> some View {
        Button {
            calibrationTarget = screen
        } label: {
            HStack {
                Image(systemName: screen.iconName)
                    .foregroundStyle(Color.recCoral)
                    .frame(width: 28)
                Text(screen.title)
                    .foregroundStyle(.white)
                Spacer()
                Image(systemName: "chevron.right")
                    .foregroundStyle(.gray)
            }
        }
        .listRowBackground(Color.cardBackground)
    }
}

extension CalibrationScreen {
    var title: String {
        switch self {
        case .partySelect: return "パーティ選択画面"
        case .battlePrep:  return "VS画面"
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
