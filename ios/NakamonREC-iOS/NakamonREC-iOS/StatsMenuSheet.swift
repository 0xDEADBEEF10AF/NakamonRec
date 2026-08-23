import SwiftUI

/// 戦績画面下部の 🔍 アイコンから開く集計メニュー
/// 「パーティ集計」「モンスター集計」の 2 ボタンを表示し、選ばれた集計画面を呼び出す
struct StatsMenuSheet: View {
    let onSelect: (BattleHistoryView.StatsTarget) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("集計メニュー")
                .font(.headline)
                .foregroundStyle(Color.gray)
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 12)

            Divider().background(Color.gray.opacity(0.3))

            menuRow(title: "パーティ集計", icon: "person.3.fill") {
                onSelect(.party)
            }
            menuRow(title: "モンスター集計", icon: "pawprint.fill") {
                onSelect(.monster)
            }
            menuRow(title: "グランプリ集計", icon: "trophy.fill") {
                onSelect(.grandPrix)
            }

            Spacer(minLength: 0)
        }
        .background(Color.cardBackground)
    }

    private func menuRow(title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Image(systemName: icon)
                    .foregroundStyle(Color.recCoral)
                    .frame(width: 28)
                Text(title)
                    .foregroundStyle(.white)
                Spacer()
                Image(systemName: "chevron.right")
                    .foregroundStyle(.gray)
            }
            .contentShape(Rectangle())
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
        }
    }
}
