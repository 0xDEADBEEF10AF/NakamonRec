import SwiftUI
import NakamonREC_Shared

/// 戦績画面下部の 🔍 アイコンから開く集計メニュー
/// 「パーティ集計」「モンスター集計」「グランプリ集計」を表示し、選ばれた集計画面を呼び出す。
/// グランプリ集計は「GP モード ON または GP 記録あり」のときだけ有効 (それ以外はグレーアウト)。
struct StatsMenuSheet: View {
    let onSelect: (BattleHistoryView.StatsTarget) -> Void
    @Environment(\.dismiss) private var dismiss

    /// グランプリ集計の有効条件: ①校正でグランプリモード ②GP 記録が 1 件以上
    private var grandPrixAvailable: Bool {
        GrandPrixMode.isEnabled || !BattleHistoryStore.shared.loadGrandPrixRecords().isEmpty
    }

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
            menuRow(title: "グランプリ集計", icon: "trophy.fill", enabled: grandPrixAvailable) {
                onSelect(.grandPrix)
            }

            Spacer(minLength: 0)
        }
        .background(Color.cardBackground)
    }

    private func menuRow(title: String, icon: String, enabled: Bool = true,
                         action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Image(systemName: icon)
                    .foregroundStyle(enabled ? Color.recCoral : Color.gray.opacity(0.5))
                    .frame(width: 28)
                Text(title)
                    .foregroundStyle(enabled ? .white : Color.gray.opacity(0.6))
                Spacer()
                Image(systemName: "chevron.right")
                    .foregroundStyle(enabled ? Color.gray : Color.gray.opacity(0.4))
            }
            .contentShape(Rectangle())
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
        }
        .disabled(!enabled)
    }
}
