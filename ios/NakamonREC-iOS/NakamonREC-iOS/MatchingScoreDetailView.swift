import SwiftUI
import NakamonREC_Shared

/// マッチングスコア詳細画面 (Android `マッチングスコア詳細画面` 相当)
/// - スコアはレコード自身 (BattleRecord) に保存されているので過去戦でも表示できる
/// - サムネ画像のみ最新 1 戦ぶんを保持。過去戦のサムネは空枠 (placeholder)
struct MatchingScoreDetailView: View {
    let record: BattleRecord
    @Environment(\.dismiss) private var dismiss

    @State private var metadata: MatchingScoreSnapshot.Metadata? = nil

    /// 開いた record の timestamp と snapshot の battleTimestamp が一致するか
    /// (= サムネ画像をこの record のものとして表示してよいか)
    private var isCurrentSnapshot: Bool {
        metadata?.battleTimestamp == record.timestamp
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if !isCurrentSnapshot {
                            pastRecordNotice
                        }
                        partySection
                        vsSection
                        monstersSection
                        resultSection
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

    private var pastRecordNotice: some View {
        HStack(spacing: 8) {
            Image(systemName: "info.circle")
                .foregroundStyle(.gray)
            Text("この戦績のサムネ画像は保持されていません。スコアのみ表示します。")
                .font(.caption)
                .foregroundStyle(.gray)
            Spacer()
        }
        .padding(10)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    // MARK: - Sections (data sources are record-first)

    private var partySection: some View {
        sectionCard(title: "パーティ選択") {
            let scores = record.partySelectScores ?? []
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
                    Text(scoreString(record.vsScore))
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
                slotRow(label: "味方", color: Color.sideMy,
                        names: record.myParty,
                        scores: record.myPartyScores ?? [],
                        slotIdxOffset: 0)
                slotRow(label: "敵", color: Color.sideEnemy,
                        names: record.enemyParty,
                        scores: record.enemyPartyScores ?? [],
                        slotIdxOffset: 4)
            }
        }
    }

    private func slotRow(label: String, color: Color,
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
                        Text(names[safe: i].map(MonsterCatalog.name(for:)) ?? "?")
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
            let label = record.result
            let color: Color = label == "WIN" ? Color.sideMy : (label == "LOSE" ? Color.sideEnemy : .gray)
            HStack(spacing: 12) {
                snapshotImage(forFile: "result.png", height: 90)
                    .frame(maxWidth: 200)
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(color.opacity(0.6), lineWidth: 1)
                    )
                VStack(alignment: .leading, spacing: 4) {
                    Text(label.isEmpty ? "?" : label)
                        .font(.title3.bold())
                        .foregroundStyle(color)
                    Text(scoreString(record.resultScore))
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

    /// App Group からスナップショット画像を読み込んで表示。
    /// この record が最新 snapshot と一致するときだけ実画像、それ以外は空枠 placeholder
    private func snapshotImage(forFile name: String, height: CGFloat) -> some View {
        Group {
            if isCurrentSnapshot,
               let url = MatchingScoreSnapshot.url(forFile: name),
               let img = UIImage(contentsOfFile: url.path) {
                Image(uiImage: img)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: height)
            } else {
                ZStack {
                    Color.gray.opacity(0.15)
                    Image(systemName: "questionmark")
                        .foregroundStyle(.gray.opacity(0.5))
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
