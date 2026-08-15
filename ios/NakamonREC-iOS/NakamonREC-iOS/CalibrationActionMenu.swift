import SwiftUI
import PhotosUI
import NakamonREC_Shared

/// 校正の入口アクションメニュー
/// "画像をインポート / 画像を削除 / 校正を開始"
struct CalibrationActionMenu: View {
    let screen: CalibrationScreen
    @Environment(\.dismiss) private var dismiss

    @State private var hasScreenshot: Bool = false
    @State private var pickerItem: PhotosPickerItem? = nil
    @State private var showCalibration = false
    @State private var errorMessage: String? = nil
    @State private var showPartyImportNotice = false
    @State private var showPartyPicker = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(screen.title)
                .font(.headline)
                .foregroundStyle(Color.gray)
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 12)

            Divider().background(Color.gray.opacity(0.3))

            // パーティ選択画面のみ、ピッカーを開く前に注意ダイアログを必ず挟む。
            // フォーカスなしスクショのインポートが「パーティ選択が認識しない」問い合わせの
            // 最頻出原因で、README の注意書きだけでは読まれないため (2026-08-14)。
            if screen == .partySelect {
                Button {
                    showPartyImportNotice = true
                } label: {
                    actionLabel("画像をインポート", systemImage: "square.and.arrow.down")
                }
            } else {
                PhotosPicker(selection: $pickerItem, matching: .images) {
                    actionLabel("画像をインポート", systemImage: "square.and.arrow.down")
                }
            }

            Button {
                CalibrationScreenshotStore.remove(screen)
                CustomTemplateStore.remove(customKind)
                CalibrationStore.reset(screen: screen, wide16x9: Self.is16x9Device)
                hasScreenshot = false
            } label: {
                actionLabel("画像を削除", systemImage: "trash", disabled: !hasScreenshot)
            }
            .disabled(!hasScreenshot)

            Button {
                showCalibration = true
            } label: {
                actionLabel("校正を開始", systemImage: "scope", disabled: !hasScreenshot)
            }
            .disabled(!hasScreenshot)

            Spacer(minLength: 0)
        }
        .background(Color.cardBackground)
        .onAppear {
            hasScreenshot = CalibrationScreenshotStore.exists(screen)
        }
        .onChange(of: pickerItem) { _, newItem in
            guard let newItem else { return }
            Task {
                if let data = try? await newItem.loadTransferable(type: Data.self) {
                    CalibrationScreenshotStore.save(data, for: screen)
                    hasScreenshot = true
                } else {
                    errorMessage = "画像の読み込みに失敗しました"
                }
            }
        }
        .fullScreenCover(isPresented: $showCalibration) {
            CalibrationView(screen: screen)
        }
        .photosPicker(isPresented: $showPartyPicker, selection: $pickerItem, matching: .images)
        .alert("インポート前の確認", isPresented: $showPartyImportNotice) {
            Button("画像を選ぶ") { showPartyPicker = true }
            Button("キャンセル", role: .cancel) {}
        } message: {
            if Self.is16x9Device {
                // 16:9 (iPhone SE 系) は P3 が未スクロールで画面外のため、校正には
                // 未スクロール + P1/P2 フォーカスの画像が必要 (P3 は自動外挿される)
                Text("パーティ1か2を選択した状態 (フォーカスの水色枠が付いた状態) で、一覧をスクロールせずに撮ったスクリーンショットを使ってください。\nこの端末ではパーティ3の位置は自動で補完されます。")
            } else {
                Text("パーティ1〜3のどれかを選択した状態 (フォーカスの水色枠が付いた状態) のスクリーンショットを使ってください。\n選択していないスクショでは自動校正が失敗します。")
            }
        }
        .alert("エラー", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } })) {
            Button("OK") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }

    /// 16:9 (iPhone SE 系) 端末かどうか。画面の縦横比で判定 (スクショ/フレームと同じ規則)
    static var is16x9Device: Bool {
        let b = UIScreen.main.bounds
        return CalibrationDefaults.isWide16x9(width: Double(b.width), height: Double(b.height))
    }

    private var customKind: CustomTemplateKind {
        switch screen {
        case .partySelect: return .select
        case .battlePrep:  return .vs
        case .win:         return .win
        case .lose:        return .lose
        }
    }

    private func actionLabel(_ title: String, systemImage: String, disabled: Bool = false) -> some View {
        HStack {
            Image(systemName: systemImage)
                .foregroundStyle(disabled ? .gray : Color.recCoral)
                .frame(width: 28)
            Text(title)
                .foregroundStyle(disabled ? .gray : .white)
            Spacer()
        }
        .contentShape(Rectangle())
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }
}
