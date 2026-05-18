import SwiftUI

/// ヘルプ + バージョン情報 + アップデート確認画面 (Phase 9)
/// メイン画面の右上「?」アイコンから表示する。Android `readme_content` を iOS 向けに調整した内容
struct HelpView: View {
    @Environment(\.dismiss) private var dismiss

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "Unknown"
    }
    private var buildNumber: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "Unknown"
    }

    /// GitHub Releases へのリンク (最新版確認用)
    private let releasesURL = URL(string: "https://github.com/0xDEADBEEF10AF/NakamonRec/releases")!

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        versionCard
                        section(title: "アプリ概要", body: appOverview)
                        section(title: "主要機能", body: features)
                        section(title: "使い方", body: usage)
                        section(title: "推奨環境", body: requirements)
                        section(title: "注意事項", body: cautions)
                        section(title: "不具合・ご要望について", body: feedback)
                        section(title: "著作権表記", body: copyright)
                        Spacer(minLength: 24)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 16)
                }
            }
            .navigationTitle("NakamonREC について")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("閉じる") { dismiss() }
                }
            }
        }
    }

    private var versionCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Version \(appVersion) (build \(buildNumber))")
                        .font(.headline)
                        .foregroundStyle(.white)
                    Text("iOS 17.0 以上")
                        .font(.caption)
                        .foregroundStyle(.gray)
                }
                Spacer()
            }
            Button {
                UIApplication.shared.open(releasesURL)
            } label: {
                HStack {
                    Image(systemName: "arrow.up.right.square")
                    Text("GitHub で最新版を確認")
                }
                .font(.callout.bold())
                .foregroundStyle(Color.recCoral)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private func section(title: String, body: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("【\(title)】")
                .font(.subheadline.bold())
                .foregroundStyle(Color.recCoral)
            Text(body)
                .font(.callout)
                .foregroundStyle(.white)
                .lineSpacing(4)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    // MARK: - Texts (Android strings.xml readme_content をベースに iOS 向け調整)

    private let appOverview = """
本アプリはドラクエウォークの「なかまモンスター」戦闘画面をリアルタイムで解析し、自分と相手のモンスターおよび勝敗を自動的に記録する「非公式」の分析ツールです。ファンコミュニティの活動をサポートし、より楽しいゲーム体験を提供することを目的としています。
"""

    private let features = """
・自動戦闘記録: iOS の画面ブロードキャスト (Broadcast Upload Extension) によるリアルタイム画面解析 (動画ファイルは生成されません)
・OpenCV パターンマッチング: 独自の高速キャッシュ + 1080-ref スケーリングで様々な iPhone 解像度に対応
・戦績統計: パーティごとの勝率・使用率を表示 (敵モンスター集計は今後追加予定)
・戦績の編集・削除: 誤って記録された内容は、戦績一覧から手動で修正・削除が可能
・データ管理: 複数の戦績ファイル切替、CSV エクスポート/インポート、ファイルマージに対応
・柔軟な校正: 端末ごとの表示位置のズレを自動校正で吸収。校正によって生成されたカスタムテンプレで識別精度を最大化
・軽負荷モード: 識別対象を指定モンスターのみに絞って解析時間を短縮
"""

    private let usage = """
1. 画面上部の 📂 アイコンから記録用のファイルを選択または新規作成します。
2. 初回のみ 📷 アイコンの「キャプチャー画面の校正」から、パーティ選択画面・対戦じゅんび画面・勝利画面・ざんねん画面のスクショをインポートし、「自動校正」ボタンを押してください。緑枠がモンスター/ロゴ位置に合うはずです。
3. メイン画面の「REC」ボタンを押し、画面ブロードキャストを開始します。
4. 戦闘を開始すると、対戦じゅんび画面で自動的にモンスターが識別され、終了時に勝敗が記録されます。
5. 記録された内容は「戦績チェック」 (画面下部の戦績サマリ) から確認できます。レコードを長押しすることで、勝敗・モンスター・使用パーティの修正・削除が可能です。
6. 画面下部のフィルタ/編集モード切替アイコンから、WIN/LOSE やモンスターによるフィルタが行えます。
"""

    private let requirements = """
・OS: iOS 17.0 以上
・メモリ: 4GB 以上推奨 (Broadcast Upload Extension は 50MB 制限がありシビアです)
・動作確認済み: iPhone 13 mini
・他機種は未検証ですが、校正機能で機種差は吸収できる設計です
"""

    private let cautions = """
・本アプリはファンによって制作された非公式のアプリであり、株式会社スクウェア・エニックスを代表とする公式とは直接の関係はありません。
・公式からの協賛や提供を受けておらず、公式のサポートやアップデートは保証されていません。
・本アプリは画面ブロードキャスト権限を必要とします。
・iOS 標準の画面収録と同時に使用することはできません。
・バックグラウンドで画像解析を行うため、バッテリー消費にご注意ください。
・正確な識別のために、必ずご使用の端末に合わせた「校正」を行ってください。
・REC 停止時に iOS 標準のシステムシートが表示されますが、これは iOS の仕様で抑制できません。
"""

    private let feedback = """
動作しない場合や改善のご要望がありましたら、GitHub の Issue または SNS にてお知らせください。その際、ご使用の機種名 (例: iPhone 15 Pro) を併記いただけますと幸いです。皆様のフィードバックがアプリの改善に繋がります。
"""

    private let copyright = """
このアプリで利用している株式会社スクウェア・エニックスを代表とする共同著作者が権利を所有する画像の転載・配布は禁止いたします。
© ARMOR PROJECT/BIRD STUDIO/SQUARE ENIX All Rights Reserved.
"""
}
