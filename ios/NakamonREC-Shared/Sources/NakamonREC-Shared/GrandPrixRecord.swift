import Foundation

/// グランプリ (大会) 1 戦のレーティング記録。
///
/// 通常の `BattleRecord` とは別系列 (`BattleHistory.grandPrixRecords`) に保存する。
/// レーティング読み取りの誤りが本体戦績 (勝敗・モンスター) を汚さないための分離。
/// グランプリ戦は既存パイプラインで通常の `BattleRecord` も生成されるため、
/// このレコードは「その戦のレーティング付随情報」という位置づけ。
///
/// Android 側も同一 JSON スキーマ (`grandPrixRecords` 配列) を持たせて両OS互換にすること。
public struct GrandPrixRecord: Codable, Identifiable, Hashable {
    public var timestamp: String        // "yyyy-MM-dd HH:mm:ss" 形式 (対応する BattleRecord と一致)
    public var result: String           // "WIN" or "LOSE"
    public var currentRating: Double    // 現在のレーティング (画面から読む値①、白・常に正)
    public var neededRating: Double?    // 必要レーティング「あと」(画面から読む値②、白・常に正)。次ランクなし(GM)/ランクアップ戦では nil
    public var nationalRank: String?    // 全国ランキング表示テキスト (例 "200位以上")。意味が可変なので数値化しない
    public var isRankUp: Bool           // ランクアップ戦 (パネルに必要レーティングが出ない戦)
    public var lowConfidence: Bool      // 読み取り信頼度が低く要確認 (手入力を促す)
    public var screenshotFile: String?  // 低信頼度時に保存した確認用スクショのファイル名
    public var rankTier: String?        // ランク帯 (手動設定。GrandPrixRecord.rankTiers のいずれか)。将来エンブレム表示に使う

    /// ランク帯の選択肢 (低い順)。レーティングを記録する区間のみ (マスター3〜GM)。
    public static let rankTiers: [String] = [
        "マスター3", "マスター2", "マスター1", "グランドマスター"
    ]

    /// id は timestamp ベース (対応する BattleRecord と揃える)
    public var id: String { timestamp }

    /// ボーダー (次ランク到達ライン) = 現在のレーティング + 必要レーティングあと。
    /// 必要レーティングが無い (GM 帯 / ランクアップ戦) 場合は nil。
    public var borderRating: Double? {
        guard let needed = neededRating else { return nil }
        return currentRating + needed
    }

    // 変動レーティングは画面から読まない。連続する currentRating の差分として
    // 表示時に導出する (前戦がなければ変動なし)。読み取り対象は現在/必要の2値のみ。

    public init(timestamp: String,
                result: String,
                currentRating: Double,
                neededRating: Double? = nil,
                nationalRank: String? = nil,
                isRankUp: Bool = false,
                lowConfidence: Bool = false,
                screenshotFile: String? = nil,
                rankTier: String? = nil) {
        self.timestamp = timestamp
        self.result = result
        self.currentRating = currentRating
        self.neededRating = neededRating
        self.nationalRank = nationalRank
        self.isRankUp = isRankUp
        self.lowConfidence = lowConfidence
        self.screenshotFile = screenshotFile
        self.rankTier = rankTier
    }
}
