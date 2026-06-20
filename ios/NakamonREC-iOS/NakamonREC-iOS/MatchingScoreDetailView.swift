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
                VStack(spacing: 8) {
                    if !isCurrentSnapshot {
                        pastRecordNotice
                    }
                    partySection
                    // VS と勝敗は横並びにして 1 画面に収める
                    HStack(spacing: 8) {
                        vsSection
                        resultSection
                    }
                    monstersSection
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
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
            HStack(alignment: .top, spacing: 8) {
                ForEach(0..<3, id: \.self) { i in
                    VStack(spacing: 3) {
                        Text("P\(i + 1)")
                            .font(.caption2.bold())
                            .foregroundStyle(i == record.partyIndex ? Color.recCoral : .white)
                        snapshotImage(forFile: "p\(i).png", height: 56)
                            .overlay(
                                RoundedRectangle(cornerRadius: 6)
                                    .stroke(i == record.partyIndex ? Color.recCoral : Color.gray.opacity(0.3),
                                            lineWidth: i == record.partyIndex ? 2 : 1)
                            )
                        Text(scoreString(scores[safe: i]))
                            .font(.system(size: 9).monospacedDigit())
                            .foregroundStyle(.gray)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        }
    }

    private var vsSection: some View {
        sectionCard(title: "VS ロゴ") {
            VStack(spacing: 4) {
                snapshotImage(forFile: "vs.png", height: 50)
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(Color.gray.opacity(0.3), lineWidth: 1)
                    )
                Text(scoreString(record.vsScore))
                    .font(.callout.monospacedDigit().bold())
                    .foregroundStyle(.white)
                Text(String(format: "閾値 %.2f", DetectionThresholdsConfig.vsThreshold))
                    .font(.system(size: 9))
                    .foregroundStyle(.gray)
            }
        }
    }

    private var monstersSection: some View {
        sectionCard(title: "モンスター識別") {
            VStack(spacing: 6) {
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
        HStack(alignment: .top, spacing: 6) {
            Text(label)
                .font(.caption2.bold())
                .foregroundStyle(color)
                .frame(width: 26, alignment: .leading)
                .padding(.top, 14) // サムネに対して中央寄せ
            ForEach(0..<4, id: \.self) { i in
                VStack(spacing: 2) {
                    snapshotImage(forFile: "slot_\(slotIdxOffset + i).png", height: 48)
                        .overlay(
                            RoundedRectangle(cornerRadius: 6)
                                .stroke(color.opacity(0.6), lineWidth: 1)
                        )
                    Text(names[safe: i].map(MonsterCatalog.name(for:)) ?? "?")
                        .font(.system(size: 8))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text(scoreString(scores[safe: i]))
                        .font(.system(size: 8).monospacedDigit())
                        .foregroundStyle(.gray)
                }
                .frame(maxWidth: .infinity)
            }
        }
    }

    private var resultSection: some View {
        sectionCard(title: "勝敗ロゴ") {
            let label = record.result
            let color: Color = label == "WIN" ? Color.sideMy : (label == "LOSE" ? Color.sideEnemy : .gray)
            let thr = label == "LOSE" ? DetectionThresholdsConfig.loseThreshold
                                       : DetectionThresholdsConfig.winThreshold
            VStack(spacing: 4) {
                snapshotImage(forFile: "result.png", height: 50)
                    .overlay(
                        RoundedRectangle(cornerRadius: 6)
                            .stroke(color.opacity(0.6), lineWidth: 1)
                    )
                Text(label.isEmpty ? "?" : label)
                    .font(.caption.bold())
                    .foregroundStyle(color)
                Text(scoreString(record.resultScore))
                    .font(.callout.monospacedDigit())
                    .foregroundStyle(.white)
                Text(String(format: "閾値 %.2f", thr))
                    .font(.system(size: 9))
                    .foregroundStyle(.gray)
            }
        }
    }

    // MARK: - Helpers

    private func sectionCard<Content: View>(title: String,
                                            @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption.bold())
                .foregroundStyle(Color.recCoral)
            content()
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 8))
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
