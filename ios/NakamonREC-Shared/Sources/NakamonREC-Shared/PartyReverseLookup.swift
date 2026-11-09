import Foundation

/// 選択パーティ逆引き (2026-11-09 ビーフ設計、Android `PartyReverseLookup.kt` と同仕様)。
/// パーティ選択画面を識別できない端末 (タブレット等) 向けの奥の手。ON のあいだは、記録する戦闘の
/// 味方 4 体の並びと完全一致するレコードを現在の戦績ファイルの新しい順に探し、最初に見つかった
/// レコードのパーティ番号を「問答無用で」採用する (画面からの検知結果は使わない)。
/// 見つからなければ P? (-1)。利用者が最初に P1〜P3 を手動で割り当て、パーティ編集のたびに
/// 割り当て直すことが前提。設定は App Group UserDefaults に置き、Host / Extension で共有する。
public enum PartyReverseLookup {
    private static let suiteName = "group.com.android.NakamonREC-iOS"
    private static let enabledKey = "partyReverseLookup.enabled"

    private static var defaults: UserDefaults? {
        UserDefaults(suiteName: suiteName)
    }

    /// 逆引きが ON か (既定 OFF)
    public static var isEnabled: Bool {
        get { defaults?.bool(forKey: enabledKey) ?? false }
        set { defaults?.set(newValue, forKey: enabledKey) }
    }

    /// 逆引きの結果
    public struct Match {
        /// 0..2 (P1=0)。見つからなければ -1
        public let partyIndex: Int
        /// 一致したレコードの timestamp (飛行記録用)。見つからなければ nil
        public let matchedTimestamp: String?
    }

    /// `myParty` (味方 4 体、VS 画面の並び順) と完全一致するレコードを `records` の末尾 (= 新しい方) から探す。
    /// 探索対象から外すもの: パーティが P? のレコード、味方に識別失敗 ("?") を含むレコード。
    /// 今回の味方に "?" が含まれていれば一致させようがないので -1 を返す
    public static func lookup(myParty: [String], in records: [BattleRecord]) -> Match {
        guard myParty.count == 4, !myParty.contains("?") else {
            return Match(partyIndex: -1, matchedTimestamp: nil)
        }
        for r in records.reversed() {
            guard (0...2).contains(r.partyIndex), !r.myParty.contains("?") else { continue }
            if r.myParty == myParty {
                return Match(partyIndex: r.partyIndex, matchedTimestamp: r.timestamp)
            }
        }
        return Match(partyIndex: -1, matchedTimestamp: nil)
    }
}
