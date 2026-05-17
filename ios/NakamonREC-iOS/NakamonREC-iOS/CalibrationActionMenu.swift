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

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(screen.title)
                .font(.headline)
                .foregroundStyle(Color.gray)
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 12)

            Divider().background(Color.gray.opacity(0.3))

            PhotosPicker(selection: $pickerItem, matching: .images) {
                actionLabel("画像をインポート", systemImage: "square.and.arrow.down")
            }

            Button {
                CalibrationScreenshotStore.remove(screen)
                CustomTemplateStore.remove(customKind)
                CalibrationStore.reset(screen: screen)
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
        .alert("エラー", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } })) {
            Button("OK") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
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
