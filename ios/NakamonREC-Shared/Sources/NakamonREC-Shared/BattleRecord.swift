import Foundation

/// 戦闘 1 件の記録 (Android `DataModels.kt:BattleRecord` 互換)
public struct BattleRecord: Codable, Identifiable, Hashable {
    public var timestamp: String        // "yyyy-MM-dd HH:mm:ss" 形式
    public var result: String           // "WIN" or "LOSE"
    public var partyIndex: Int          // 使用パーティ 0..2 (P1=0, P2=1, P3=2)。未検知なら -1
    public var myParty: [String]        // 味方 4 体のテンプレ ID (例: "id001")
    public var enemyParty: [String]     // 敵 4 体のテンプレ ID
    public var vsScore: Double?
    public var myPartyScores: [Double]?
    public var enemyPartyScores: [Double]?
    public var resultScore: Double?
    public var partySelectScores: [Double]?

    /// id は timestamp ベース (秒単位精度。同秒の戦闘は実質ありえない想定)
    /// result/partyIndex などが変わっても id が安定するので SwiftUI の ForEach 追跡が破綻しない
    public var id: String { timestamp }

    public init(timestamp: String,
                result: String,
                partyIndex: Int,
                myParty: [String],
                enemyParty: [String],
                vsScore: Double? = nil,
                myPartyScores: [Double]? = nil,
                enemyPartyScores: [Double]? = nil,
                resultScore: Double? = nil,
                partySelectScores: [Double]? = nil) {
        self.timestamp = timestamp
        self.result = result
        self.partyIndex = partyIndex
        self.myParty = myParty
        self.enemyParty = enemyParty
        self.vsScore = vsScore
        self.myPartyScores = myPartyScores
        self.enemyPartyScores = enemyPartyScores
        self.resultScore = resultScore
        self.partySelectScores = partySelectScores
    }
}

/// 戦績全体 (Android `BattleHistory` 互換)。複数 JSON ファイル切替時の単位
public struct BattleHistory: Codable {
    public var totalWins: Int
    public var totalLosses: Int
    public var records: [BattleRecord]

    /// グランプリのレーティング記録 (別系列)。
    /// 「1 ファイル = 1 グランプリ」運用のため戦績と同じファイルに同梱し、ファイル切替で一緒に切り替わる。
    /// Optional なので旧ファイル (キー無し) は nil としてデコードされ後方互換。
    /// Android も同一スキーマを持つこと。
    public var grandPrixRecords: [GrandPrixRecord]?

    public init(totalWins: Int = 0,
                totalLosses: Int = 0,
                records: [BattleRecord] = [],
                grandPrixRecords: [GrandPrixRecord]? = nil) {
        self.totalWins = totalWins
        self.totalLosses = totalLosses
        self.records = records
        self.grandPrixRecords = grandPrixRecords
    }

    /// records から totalWins/Losses を再計算 (grandPrixRecords は勝敗集計に含めない)
    public mutating func recomputeTotals() {
        totalWins = records.filter { $0.result == "WIN" }.count
        totalLosses = records.filter { $0.result == "LOSE" }.count
    }
}

/// タイムスタンプフォーマッタ (Android 互換)
public enum BattleTimestampFormatter {
    public static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        return f
    }()

    public static func now() -> String {
        formatter.string(from: Date())
    }

    public static func date(from string: String) -> Date? {
        formatter.date(from: string)
    }
}
