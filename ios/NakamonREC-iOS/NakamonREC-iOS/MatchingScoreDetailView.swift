import SwiftUI
import NakamonREC_Shared

/// マッチングスコア詳細画面 (Android `マッチングスコア詳細画面` 相当)
/// - 最新 1 戦ぶんの snapshot を表示する
/// - 開いた record の timestamp と snapshot の battleTimestamp が一致するときのみデータを表示
struct MatchingScoreDetailView: View {
    let record: BattleRecord
    @Environment(\.dismiss) private var dismiss

    @State private var metadata: MatchingScoreSnapshot.Metadata? = nil

    /// snapshot がこの record のものか
    private var snapshotMatchesRecord: Bool {
        metadata?.battleTimestamp == record.timestamp
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if metadata == nil {
                            emptyStateCard(message: "まだスナップショットが記録されていません。\n戦闘を録画して計測してください。")
                        } else if !snapshotMatchesRecord {
                            // 最新 snapshot は別の戦闘 - 戦闘の timestamp を見せて案内
                            emptyStateCard(
                                message: "この戦績のスナップショットは保存されていません。\n最新スナップショットは別の戦闘 (\(metadata?.battleTimestamp ?? "?")) のものです。"
                            )
                        } else {
                            partySection
                            vsSection
                            monstersSection
                            resultSection
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 16)
                }
            }
            .navigationTitle("マッチングスコア詳細")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("閉じる") { dismiss() }
                }
            }
            .onAppear(perform: load)
        }
    }

    private func load() {
        metadata = MatchingScoreSnapshot.loadMetadata()
    }

    // MARK: - Sections

    private var partySection: some View {
        sectionCard(title: "パーティ選択") {
            let scores = metadata?.partyScores ?? []
            HStack(alignment: .top, spacing: 12) {
                ForEach(0..<3, id: \.self) { i in
                    VStack(spacing: 6) {
                        Text("P\(i + 1)")
                            .font(.caption.bold())
                            .foregroundStyle(i == record.partyIndex ? Color.recCoral : .white)
                        snapshotImage(forFile: "p\(i).png", height: 90)
                            .overlay(
                                RoundedRectangle(cornerRadius: 6)
                                    .stroke(i == record.partyIndex ? Color.recCoral : Color.gray.opacity(0.3),
                                            lineWidth: i == record.partyIndex ? 2 : 1)
                            )
                        Text(scoreString(scores[safe: i]))
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.gray)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        }
    }

    private var vsSection: some View {
        sectionCard(title: "VS ロゴ検知") {
            HStack(spacing: 12) {
                snapshotImage(forFile: "vs.png", height: 90)
                    .frame(maxWidth: 200)
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(Color.gray.opacity(0.3), lineWidth: 1)
                    )
                VStack(alignment: .leading, spacing: 4) {
                    Text("Score")
                        .font(.caption)
                        .foregroundStyle(.gray)
                    Text(scoreString(metadata?.vsScore))
                        .font(.title3.monospacedDigit().bold())
                        .foregroundStyle(.white)
                    Text("閾値 0.40")
                        .font(.caption2)
                        .foregroundStyle(.gray)
                }
                Spacer()
            }
        }
    }

    private var monstersSection: some View {
        sectionCard(title: "モンスター識別") {
            VStack(spacing: 12) {
                slotRow(isEnemy: false, label: "味方", color: Color.sideMy,
                        names: metadata?.myPartyNames ?? [],
                        scores: metadata?.myPartyScores ?? [],
                        slotIdxOffset: 0)
                slotRow(isEnemy: true, label: "敵", color: Color.sideEnemy,
                        names: metadata?.enemyPartyNames ?? [],
                        scores: metadata?.enemyPartyScores ?? [],
                        slotIdxOffset: 4)
            }
        }
    }

    private func slotRow(isEnemy: Bool, label: String, color: Color,
                         names: [String], scores: [Double], slotIdxOffset: Int) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.caption.bold())
                .foregroundStyle(color)
            HStack(alignment: .top, spacing: 6) {
                ForEach(0..<4, id: \.self) { i in
                    VStack(spacing: 4) {
                        snapshotImage(forFile: "slot_\(slotIdxOffset + i).png", height: 70)
                            .overlay(
                                RoundedRectangle(cornerRadius: 6)
                                    .stroke(color.opacity(0.6), lineWidth: 1)
                            )
                        Text(names[safe: i] ?? "?")
                            .font(.system(size: 9))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Text(scoreString(scores[safe: i]))
                            .font(.system(size: 9).monospacedDigit())
                            .foregroundStyle(.gray)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        }
    }

    private var resultSection: some View {
        sectionCard(title: "勝敗ロゴ検知") {
            let label = metadata?.resultLabel ?? "?"
            let color: Color = label == "WIN" ? Color.sideMy : (label == "LOSE" ? Color.sideEnemy : .gray)
            HStack(spacing: 12) {
                snapshotImage(forFile: "result.png", height: 90)
                    .frame(maxWidth: 200)
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(color.opacity(0.6), lineWidth: 1)
                    )
                VStack(alignment: .leading, spacing: 4) {
                    Text(label)
                        .font(.title3.bold())
                        .foregroundStyle(color)
                    Text(scoreString(metadata?.resultScore))
                        .font(.body.monospacedDigit())
                        .foregroundStyle(.white)
                    Text("閾値 0.40")
                        .font(.caption2)
                        .foregroundStyle(.gray)
                }
                Spacer()
            }
        }
    }

    // MARK: - Helpers

    private func sectionCard<Content: View>(title: String,
                                            @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.subheadline.bold())
                .foregroundStyle(Color.recCoral)
            content()
        }
        .padding(12)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private func emptyStateCard(message: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: "doc.text.magnifyingglass")
                .font(.title)
                .foregroundStyle(.gray)
            Text(message)
                .font(.callout)
                .foregroundStyle(.gray)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
        .padding(.horizontal, 16)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    /// App Group からスナップショット画像を読み込んで表示。無ければ "?" プレースホルダ
    private func snapshotImage(forFile name: String, height: CGFloat) -> some View {
        Group {
            if let url = MatchingScoreSnapshot.url(forFile: name),
               let img = UIImage(contentsOfFile: url.path) {
                Image(uiImage: img)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: height)
            } else {
                ZStack {
                    Color.gray.opacity(0.15)
                    Image(systemName: "questionmark")
                        .foregroundStyle(.gray)
                }
                .frame(height: height)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    private func scoreString(_ score: Double?) -> String {
        guard let s = score else { return "—" }
        return String(format: "%.3f", s)
    }
}
