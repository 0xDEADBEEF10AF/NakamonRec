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
    /// 注意シートで「画像を選ぶ」が押された (シートが閉じ終わってからピッカーを開く)
    @State private var proceedToPartyPicker = false

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
        // 注意ダイアログは alert ではなく見本画像付きのシート (alert には画像を置けない)。
        // 文字の注意書きは読まれず「フォーカスなしスクショで校正 → パーティが識別されない」問い合わせが
        // 繰り返されるため、正しいスクショの見本を小さく見せて印象づける (ビーフ 2026-10-30)
        .sheet(isPresented: $showPartyImportNotice, onDismiss: {
            if proceedToPartyPicker {
                proceedToPartyPicker = false
                showPartyPicker = true
            }
        }) {
            PartyImportNoticeSheet(is16x9: Self.is16x9Device) {
                proceedToPartyPicker = true
                showPartyImportNotice = false
            }
            .presentationDetents([.large])
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


// MARK: - パーティ選択スクショのインポート前の注意 (見本画像付き)

/// 正しいスクショ (パーティを選択してフォーカスの水色枠が付いた状態・未スクロール) の見本を見せる。
/// 見本画像 `party_import_sample` は Assets に置く (相手・味方の名前はマスク済みのものを使うこと)
private struct PartyImportNoticeSheet: View {
    let is16x9: Bool
    let onPick: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        Image("party_import_sample")
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 420)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                            .overlay(
                                RoundedRectangle(cornerRadius: 12)
                                    .stroke(Color.recCoral, lineWidth: 2)
                            )
                            .padding(.top, 8)

                        Text("見本: パーティを選択して水色の枠が付いた状態")
                            .font(.caption)
                            .foregroundStyle(Color.recCoral)

                        Text(is16x9
                             ? "パーティ1か2を選択した状態 (フォーカスの水色枠が付いた状態) で、一覧をスクロールせずに撮ったスクリーンショットを使ってください。\nこの端末ではパーティ3の位置は自動で補完されます。"
                             : "パーティ1〜3のどれかを選択した状態 (フォーカスの水色枠が付いた状態) のスクリーンショットを使ってください。\n選択していないスクショでは自動校正が失敗します。")
                            .font(.subheadline)
                            .foregroundStyle(.white)
                            .multilineTextAlignment(.leading)
                            .frame(maxWidth: .infinity, alignment: .leading)

                        Button {
                            onPick()
                        } label: {
                            Text("画像を選ぶ")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                                .background(Color.recCoral)
                                .foregroundStyle(.white)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                        }
                        .padding(.top, 4)
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 24)
                }
            }
            .navigationTitle("インポート前の確認")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("キャンセル") { dismiss() }
                }
            }
        }
    }
}
