import SwiftUI
import NakamonREC_Shared

/// About 画面 (バージョン情報 + アップデート確認 + 概要 / 注意事項 / 著作権)
/// メイン画面の右上「?」アイコンから表示する。Android `readme_content` と同じ構成。
///
/// 使い方・主要機能・推奨環境などバージョンごとに変化する説明は 26.10.1 でアプリから外し、
/// 公開ヘルプ (docs/help.html) に一本化した。「使い方はこちら」ボタンで外部ブラウザを開く
/// (通信の主体はブラウザであり、アプリ自身はヘルプ取得のための通信を行わない)。
struct HelpView: View {
    @Environment(\.dismiss) private var dismiss

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "Unknown"
    }
    private var buildNumber: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "Unknown"
    }

    // App Store のアプリページ URL は iTunes Lookup API から動的に取得する
    // (GitHub Releases リンクはストア一本化 2026-08-15 で廃止)

    /// 公開ヘルプ (GitHub Pages)。Android `help_url` と同じ URL
    private let helpURL = URL(string: "https://0xdeadbeef10af.github.io/NakamonRec/help.html")!

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        versionCard
                        helpLinkCard
                        section(title: "アプリ概要", body: appOverview)
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
                    Text("iOS 26.5 以上 / iPhone 専用")
                        .font(.caption)
                        .foregroundStyle(.gray)
                }
                Spacer()
            }
            Button {
                Task {
                    if let info = await AppStoreUpdateChecker.fetch() {
                        await UIApplication.shared.open(info.storeURL)
                    }
                }
            } label: {
                HStack {
                    Image(systemName: "arrow.up.right.square")
                    Text("App Store で最新版を確認")
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

    /// 公開ヘルプへの導線。使い方・機能一覧・推奨環境・グランプリ特設などはすべてこちらに集約
    private var helpLinkCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                UIApplication.shared.open(helpURL)
            } label: {
                HStack {
                    Text("📖")
                    Text("使い方はこちら")
                    Spacer()
                    Image(systemName: "safari")
                }
                .font(.callout.bold())
                .foregroundStyle(Color.recCoral)
            }
            Text("使い方・主要機能・推奨環境・グランプリ集計の手順などは、ブラウザで開く公開ヘルプに掲載しています。")
                .font(.caption)
                .foregroundStyle(.gray)
                .fixedSize(horizontal: false, vertical: true)
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

    // MARK: - Texts (Android strings.xml readme_content と同じ構成)
    // 使い方・主要機能・推奨環境は docs/help.html に移管済み (26.10.1)。ここには変化の少ない文書だけを置く。

    private let appOverview = """
本アプリはドラクエウォークの「なかまモンスター」戦闘画面をリアルタイムで解析し、自分と相手のモンスターおよび勝敗を自動的に記録する「非公式」の分析ツールです。ファンコミュニティの活動をサポートし、より楽しいゲーム体験を提供することを目的としています。
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
GitHub Issues: https://github.com/0xDEADBEEF10AF/NakamonRec/issues
"""

    private let copyright = """
このアプリで利用している株式会社スクウェア・エニックスを代表とする共同著作者が権利を所有する画像の転載・配布は禁止いたします。
© ARMOR PROJECT/BIRD STUDIO/SQUARE ENIX All Rights Reserved.
"""
}
