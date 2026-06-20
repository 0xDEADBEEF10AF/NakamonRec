import SwiftUI
import NakamonREC_Shared

/// マッチングスコア詳細画面 (Android `showScoreDetailsDialog` と同等のレイアウト)
/// - 縦並び: 【基本判定】(P1〜P3 + VS 横並び) / 【味方パーティ】 / 【敵パーティ】 / 【勝敗ロゴ】
/// - スコアの色分けは Android と一致: ≥0.7 緑 / ≥0.4 黄 / <0.4 赤
/// - サムネ画像は最新 1 戦ぶんのみ表示。過去戦は空枠 placeholder
struct MatchingScoreDetailView: View {
    let record: BattleRecord
    @Environment(\.dismiss) private var dismiss

    @State private var metadata: MatchingScoreSnapshot.Metadata? = nil

    /// 開いた record の timestamp と snapshot の battleTimestamp が一致するか
    private var isCurrentSnapshot: Bool {
        metadata?.battleTimestamp == record.timestamp
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 6) {
                    if !isCurrentSnapshot {
                        pastRecordNotice
                    }
                    basicJudgmentSection
                    mySection
                    enemySection
                    resultSection
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
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
            Image(systemName: "info.circle").foregroundStyle(.gray)
            Text("この戦績のサムネ画像は保持されていません。スコアのみ表示します。")
                .font(.caption2)
                .foregroundStyle(.gray)
            Spacer()
        }
        .padding(8)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    // MARK: - 【基本判定】 P1-P3 + VS を横並びで weight 1.5 : 1

    private var basicJudgmentSection: some View {
        sectionCard(title: "【基本判定】") {
            // 1 つの HStack に P1/P2/P3 + VS を等幅で並べる (4 列)。
            // SwiftUI の Layout では Android の weight 1.5:1 を簡潔に表現できないため、
            // 視認性を優先して等幅で配置する。
            HStack(alignment: .top, spacing: 4) {
                let scores = record.partySelectScores ?? []
                ForEach(0..<3, id: \.self) { i in
                    thumbCell(
                        file: "p\(i).png",
                        label: "P\(i + 1)",
                        score: scores[safe: i] ?? 0,
                        labelColor: i == record.partyIndex ? Color.recCoral : .white,
                        borderColor: i == record.partyIndex ? Color.recCoral : Color.gray.opacity(0.3),
                        borderWidth: i == record.partyIndex ? 2 : 1
                    )
                }
                thumbCell(
                    file: "vs.png",
                    label: "VSロゴ",
                    score: record.vsScore ?? 0,
                    labelColor: .white,
                    borderColor: Color.gray.opacity(0.3),
                    borderWidth: 1
                )
            }
        }
    }

    // MARK: - 【味方パーティ】

    private var mySection: some View {
        sectionCard(title: "【味方パーティ】") {
            HStack(alignment: .top, spacing: 4) {
                ForEach(0..<4, id: \.self) { i in
                    thumbCell(
                        file: "slot_\(i).png",
                        label: record.myParty[safe: i].map(MonsterCatalog.name(for:)) ?? "?",
                        score: (record.myPartyScores ?? [])[safe: i] ?? 0,
                        labelColor: .white,
                        borderColor: Color.sideMy.opacity(0.6),
                        borderWidth: 1
                    )
                }
            }
        }
    }

    // MARK: - 【敵パーティ】

    private var enemySection: some View {
        sectionCard(title: "【敵パーティ】") {
            HStack(alignment: .top, spacing: 4) {
                ForEach(0..<4, id: \.self) { i in
                    thumbCell(
                        file: "slot_\(i + 4).png",
                        label: record.enemyParty[safe: i].map(MonsterCatalog.name(for:)) ?? "?",
                        score: (record.enemyPartyScores ?? [])[safe: i] ?? 0,
                        labelColor: .white,
                        borderColor: Color.sideEnemy.opacity(0.6),
                        borderWidth: 1
                    )
                }
            }
        }
    }

    // MARK: - 【勝敗ロゴ】

    private var resultSection: some View {
        sectionCard(title: "【勝敗ロゴ】") {
            let label = record.result
            let color: Color = label == "WIN" ? Color.sideMy : (label == "LOSE" ? Color.sideEnemy : .gray)
            // 1 件だけだが、他のセクションと幅を揃えるため frame(maxWidth: .infinity) で左寄せ
            HStack {
                thumbCell(
                    file: "result.png",
                    label: label.isEmpty ? "?" : label,
                    score: record.resultScore ?? 0,
                    labelColor: color,
                    borderColor: color.opacity(0.6),
                    borderWidth: 1
                )
                .frame(maxWidth: .infinity)
                // セクションが 1 列だけだと寂しいので右側は空白
                Spacer().frame(maxWidth: .infinity)
                Spacer().frame(maxWidth: .infinity)
                Spacer().frame(maxWidth: .infinity)
            }
        }
    }

    // MARK: - Helpers

    /// Android `createRoiImageView` 相当の 1 セル (サムネ + ラベル + スコア)
    private func thumbCell(file: String,
                           label: String,
                           score: Double,
                           labelColor: Color,
                           borderColor: Color,
                           borderWidth: CGFloat) -> some View {
        VStack(spacing: 2) {
            snapshotImage(forFile: file, height: 50)
                .overlay(
                    RoundedRectangle(cornerRadius: 6)
                        .stroke(borderColor, lineWidth: borderWidth)
                )
            Text(label)
                .font(.system(size: 9))
                .foregroundStyle(labelColor)
                .lineLimit(1)
                .truncationMode(.middle)
            Text(String(format: "%.3f", score))
                .font(.system(size: 10).monospacedDigit().bold())
                .foregroundStyle(scoreColor(score))
        }
        .frame(maxWidth: .infinity)
    }

    /// Android と同じ閾値配色: ≥0.7 緑 / ≥0.4 黄 / <0.4 赤
    private func scoreColor(_ score: Double) -> Color {
        if score >= 0.7 { return .green }
        if score >= 0.4 { return .yellow }
        return .red
    }

    private func sectionCard<Content: View>(title: String,
                                            @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption.bold())
                .foregroundStyle(.gray)
            content()
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    /// App Group からスナップショット画像を読み込んで表示
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
}
