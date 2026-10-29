//
//  DiagnosticsExporter.swift
//  NakamonREC-iOS
//
//  デバッグメニュー用。App Group コンテナ直下にある診断資料 (マッチングスコア詳細の切り出し画像、
//  校正スクショ、カスタムテンプレ、フライトログ、校正 JSON など) をアプリ自身の Documents/diag/ へコピーする。
//  Xcode の devicectl は App Group コンテナの Library/Documents/tmp 以外を読めないため、
//  Mac 側で解析したいときはこの経路で取り出す:
//    xcrun devicectl device copy from --device <UDID> --domain-type appDataContainer \
//      --domain-identifier com.android.NakamonREC-iOS --source Documents/diag --destination <dir>
//

import Foundation
import NakamonREC_Shared

enum DiagnosticsExporter {
    private static let appGroupID = "group.com.android.NakamonREC-iOS"

    /// バースト先頭フレームを PNG 保存するデバッグフラグ (エンジンが参照)。App Group UserDefaults
    static let saveBurstFrameKey = "debugSaveBurstFrame"

    static var saveBurstFrameEnabled: Bool {
        get { UserDefaults(suiteName: appGroupID)?.bool(forKey: saveBurstFrameKey) ?? false }
        set { UserDefaults(suiteName: appGroupID)?.set(newValue, forKey: saveBurstFrameKey) }
    }

    /// SCK 経路でフレームを端末ネイティブ解像度のまま取り込むデバッグフラグ。
    /// 既定 (false) は ReplayKit と同じ「長辺 1920 px」へ縮小して受け取る。
    /// 1126 px のネイティブでは 13 mini でモンスター解析が 24〜28 秒 (886 px なら 6.8 秒) かかり、
    /// 戦闘終了通知が解析完了まで出ない・バースト取得も 0.7〜1.4 秒かかるため (2026-10-30 実測)。
    /// 両経路のフレーム幅を揃えて判定条件を同一にする狙いもある。比較実験用に残すトグル
    static let sckNativeResolutionKey = "debugSCKNativeResolution"

    static var sckNativeResolutionEnabled: Bool {
        get { UserDefaults(suiteName: appGroupID)?.bool(forKey: sckNativeResolutionKey) ?? false }
        set { UserDefaults(suiteName: appGroupID)?.set(newValue, forKey: sckNativeResolutionKey) }
    }

    /// App Group 直下の全ファイル/ディレクトリ (Library を除く) を Documents/diag/ へコピーする。
    /// 戻り値は結果メッセージ (コピー件数 or エラー)
    static func exportToDocuments() -> String {
        let fm = FileManager.default
        guard let group = fm.containerURL(forSecurityApplicationGroupIdentifier: appGroupID) else {
            return "App Group コンテナが見つかりません"
        }
        guard let docs = fm.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return "Documents が見つかりません"
        }
        let dest = docs.appendingPathComponent("diag", isDirectory: true)
        do {
            if fm.fileExists(atPath: dest.path) {
                try fm.removeItem(at: dest)
            }
            try fm.createDirectory(at: dest, withIntermediateDirectories: true)
            let items = try fm.contentsOfDirectory(at: group, includingPropertiesForKeys: nil,
                                                   options: [.skipsHiddenFiles])
            var copied: [String] = []
            var failed: [String] = []
            // 1 項目ずつコピーし、システム管理のメタデータ等で失敗しても他を続ける
            for item in items where item.lastPathComponent != "Library" {
                let name = item.lastPathComponent
                do {
                    try fm.copyItem(at: item, to: dest.appendingPathComponent(name))
                    copied.append(name)
                } catch {
                    failed.append("\(name): \(error.localizedDescription)")
                }
            }
            var msg = "Documents/diag へ \(copied.count) 項目をコピー: " + copied.joined(separator: ", ")
            if !failed.isEmpty {
                msg += "\n失敗 \(failed.count) 件: " + failed.joined(separator: " / ")
            }
            return msg
        } catch {
            return "コピー失敗 (一覧取得/作成): \(error.localizedDescription)"
        }
    }
}
