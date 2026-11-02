import SwiftUI
import UniformTypeIdentifiers
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
    @State private var recIsActive: Bool = BroadcastStatus.isEffectivelyActive()
    @State private var statusNotificationsOn: Bool = CaptureNotifier.statusNotificationsEnabled

    // 自動エクスポート (iOS 27 以降)
    @State private var autoExportOn: Bool = AutoExportManager.isEnabled
    @State private var autoExportFormat: AutoExportManager.Format = AutoExportManager.format
    @State private var autoExportFolderName: String? = AutoExportManager.folderDisplayName
    @State private var autoExportLastAt: String? = AutoExportManager.lastExportAt
    @State private var showFolderPicker = false
    /// フォルダ未設定で ON にした場合、フォルダ選択の完了後に ON を確定させるためのフラグ
    @State private var enableAfterFolderPick = false
    @State private var autoExportError: String? = nil

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

                    // 通知設定: iOS 27 (ホスト内キャプチャ) でだけ通知が出せるので、項目自体を 27 以降に限定する。
                    // 項目が見えるかどうかで「通知対応の方式で動いているか」がユーザーにも分かる
                    if #available(iOS 27.0, *) {
                        Section {
                            HStack {
                                Image(systemName: "bell.fill")
                                    .foregroundStyle(statusNotificationsOn ? Color.recCoral : .gray)
                                    .frame(width: 28)
                                Text("戦闘の通知")
                                    .foregroundStyle(.white)
                                Spacer()
                                Toggle("", isOn: $statusNotificationsOn)
                                    .labelsHidden()
                                    .tint(Color.recCoral)
                                    .onChange(of: statusNotificationsOn) { _, newValue in
                                        CaptureNotifier.statusNotificationsEnabled = newValue
                                    }
                            }
                            .listRowBackground(Color.cardBackground)
                            .disabled(recIsActive)
                            .opacity(recIsActive ? 0.4 : 1.0)
                        } header: {
                            Text("通知")
                                .foregroundStyle(.gray)
                        } footer: {
                            if recIsActive {
                                Text("REC 中は通知設定を変更できません。\nREC を停止してから変更してください。")
                                    .font(.caption2)
                                    .foregroundStyle(Color.recCoral)
                            } else {
                                Text("iPhone のゲームモードがオンだと通知が約 10 秒遅れて届きます。気になる場合はゲームモードをオフにするか、この通知をオフにしてください。\nREC の切り忘れ警告 (30 分) はこの設定に関わらず届きます。")
                                    .font(.caption2)
                                    .foregroundStyle(.gray)
                            }
                        }

                        // 自動エクスポート: REC 中もアプリ本体が動く iOS 27 のホスト内キャプチャでのみ成立する
                        autoExportSection
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
            .fileImporter(isPresented: $showFolderPicker, allowedContentTypes: [.folder]) { result in
                handleFolderPick(result)
            }
            .alert("自動エクスポート", isPresented: Binding(
                get: { autoExportError != nil },
                set: { if !$0 { autoExportError = nil } }
            )) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(autoExportError ?? "")
            }
        }
    }

    // MARK: - 自動エクスポート (iOS 27 以降)

    private var autoExportSection: some View {
        Section {
            HStack {
                Image(systemName: "square.and.arrow.up.on.square")
                    .foregroundStyle(autoExportOn ? Color.recCoral : .gray)
                    .frame(width: 28)
                Text("自動エクスポート")
                    .foregroundStyle(.white)
                Spacer()
                Toggle("", isOn: $autoExportOn)
                    .labelsHidden()
                    .tint(Color.recCoral)
                    .onChange(of: autoExportOn) { _, newValue in
                        autoExportToggled(newValue)
                    }
            }
            .listRowBackground(Color.cardBackground)
            .disabled(recIsActive)
            .opacity(recIsActive ? 0.4 : 1.0)

            Button {
                enableAfterFolderPick = false
                showFolderPicker = true
            } label: {
                HStack {
                    Image(systemName: "folder")
                        .foregroundStyle(.gray)
                        .frame(width: 28)
                    Text("格納先フォルダ")
                        .foregroundStyle(.white)
                    Spacer()
                    Text(autoExportFolderName ?? "未設定")
                        .foregroundStyle(.gray)
                        .lineLimit(1)
                    Image(systemName: "chevron.right")
                        .foregroundStyle(.gray)
                }
            }
            .listRowBackground(Color.cardBackground)
            .disabled(recIsActive)
            .opacity(recIsActive ? 0.4 : 1.0)

            HStack {
                Image(systemName: "doc.text")
                    .foregroundStyle(.gray)
                    .frame(width: 28)
                Text("格納形式")
                    .foregroundStyle(.white)
                Spacer()
                Picker("", selection: $autoExportFormat) {
                    ForEach(AutoExportManager.Format.allCases) { f in
                        Text(f.label).tag(f)
                    }
                }
                .labelsHidden()
                .tint(.gray)
                .onChange(of: autoExportFormat) { _, newValue in
                    AutoExportManager.format = newValue
                    if autoExportOn { runExportNow() }
                }
            }
            .listRowBackground(Color.cardBackground)
            .disabled(recIsActive)
            .opacity(recIsActive ? 0.4 : 1.0)

            HStack {
                Image(systemName: "clock")
                    .foregroundStyle(.gray)
                    .frame(width: 28)
                Text("最終書き出し")
                    .foregroundStyle(.white)
                Spacer()
                Text(autoExportLastAt ?? "—")
                    .foregroundStyle(.gray)
            }
            .listRowBackground(Color.cardBackground)
        } header: {
            Text("自動エクスポート")
                .foregroundStyle(.gray)
        } footer: {
            if recIsActive {
                Text("REC 中は自動エクスポートの設定を変更できません。\nREC を停止してから変更してください。")
                    .font(.caption2)
                    .foregroundStyle(Color.recCoral)
            } else {
                Text("ON のあいだ、戦績が増えるたびに選択中の戦績ファイルと同じ名前で CSV / HTML を格納先フォルダへ上書きします。格納先に iCloud Drive や Google ドライブのフォルダを選ぶと、配信用 PC などから最新の戦績を参照できます。\nアプリの REC ボタンから始めたときのみ動作します (コントロールセンターから始めた放送は対象外)。")
                    .font(.caption2)
                    .foregroundStyle(.gray)
            }
        }
    }

    private func autoExportToggled(_ on: Bool) {
        if on {
            if AutoExportManager.hasFolder {
                AutoExportManager.isEnabled = true
                runExportNow()
            } else {
                // フォルダが未設定なら先に選んでもらい、選べたら ON を確定する
                enableAfterFolderPick = true
                showFolderPicker = true
            }
        } else {
            AutoExportManager.isEnabled = false
            enableAfterFolderPick = false
        }
    }

    private func handleFolderPick(_ result: Result<URL, Error>) {
        switch result {
        case .success(let url):
            if AutoExportManager.setFolder(url) {
                autoExportFolderName = AutoExportManager.folderDisplayName
                if enableAfterFolderPick {
                    AutoExportManager.isEnabled = true
                    enableAfterFolderPick = false
                }
                if autoExportOn { runExportNow() }
            } else {
                autoExportError = "このフォルダへのアクセス権を保存できませんでした。別のフォルダを選んでください。"
                if enableAfterFolderPick { autoExportOn = false }
            }
        case .failure:
            // キャンセル: ON にしようとしていたなら OFF に戻す
            if enableAfterFolderPick { autoExportOn = false }
        }
        // ピッカーをキャンセルした場合 (.failure が来ないことがある) も ON のまま残さない
        if enableAfterFolderPick && !AutoExportManager.hasFolder { autoExportOn = false }
        enableAfterFolderPick = false
    }

    /// 設定変更の直後に 1 回書き出し、結果 (最終書き出し時刻 or 失敗) を画面へ反映する
    private func runExportNow() {
        let fileName = BattleHistoryStore.shared.activeFileName
        Task.detached(priority: .utility) {
            let ok = AutoExportManager.exportNow(fileName: fileName)
            let last = AutoExportManager.lastExportAt
            let failure = ok ? nil : AutoExportManager.consumeFailureMessage()
            await MainActor.run {
                autoExportLastAt = last
                if !ok {
                    autoExportOn = false
                    autoExportError = "書き出しに失敗したため OFF にしました。\n\(failure ?? "")"
                }
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
