import Foundation

/// 戦績 CSV の入出力。Android `戦績ファイル管理画面` の CSV と互換。
///
/// フォーマット (UTF-8, CRLF 区切り):
/// ```
/// 総合戦績,X戦 X勝 X敗
/// パーティ1戦績,X戦 X勝 X敗
/// パーティ2戦績,X戦 X勝 X敗
/// パーティ3戦績,X戦 X勝 X敗
/// (空行)
/// "戦闘終了時刻","勝敗","選択パーティ","自分1",..."自分4","相手1",..."相手4"
/// "2026-05-10 16:35:32","WIN","パーティ2","デスタムーア",..."ハーゴン"
/// ...
/// ```
public enum CSVSupport {

    // MARK: - Encode (BattleHistory → CSV)

    /// BattleHistory を Android 互換の CSV 文字列に変換 (改行は CRLF)
    public static func encode(_ history: BattleHistory) -> String {
        var lines: [String] = []

        // 上部: 集計サマリ (引用符なし、Android 形式と整合)
        let total = history.records.count
        let totalW = history.records.filter { $0.result == "WIN" }.count
        let totalL = total - totalW
        lines.append("総合戦績,\(total)戦 \(totalW)勝 \(totalL)敗")
        for partyIdx in 0..<3 {
            let partyRecords = history.records.filter { $0.partyIndex == partyIdx }
            let pTotal = partyRecords.count
            let pW = partyRecords.filter { $0.result == "WIN" }.count
            let pL = pTotal - pW
            lines.append("パーティ\(partyIdx + 1)戦績,\(pTotal)戦 \(pW)勝 \(pL)敗")
        }
        lines.append("")

        // カラムヘッダー (全セル引用符付き)
        let columns = ["戦闘終了時刻", "勝敗", "選択パーティ",
                       "自分1", "自分2", "自分3", "自分4",
                       "相手1", "相手2", "相手3", "相手4"]
        lines.append(columns.map(quoted).joined(separator: ","))

        // データ行
        for record in history.records {
            let party = record.partyIndex >= 0 ? "パーティ\(record.partyIndex + 1)" : "パーティ?"
            let myCells = (0..<4).map { idx -> String in
                guard idx < record.myParty.count else { return "?" }
                let id = record.myParty[idx]
                return id == "?" ? "?" : MonsterCatalog.name(for: id)
            }
            let enemyCells = (0..<4).map { idx -> String in
                guard idx < record.enemyParty.count else { return "?" }
                let id = record.enemyParty[idx]
                return id == "?" ? "?" : MonsterCatalog.name(for: id)
            }
            let cells = [record.timestamp, record.result, party] + myCells + enemyCells
            lines.append(cells.map(quoted).joined(separator: ","))
        }

        return lines.joined(separator: "\r\n") + "\r\n"
    }

    // MARK: - Decode (CSV → [BattleRecord])

    /// CSV 文字列を BattleRecord 配列に変換。識別不能な行はスキップ
    public static func decode(_ csv: String) -> [BattleRecord] {
        // BOM を除去
        var text = csv
        if text.hasPrefix("\u{FEFF}") {
            text.removeFirst()
        }
        // CRLF / LF 両対応
        let rawLines = text.components(separatedBy: CharacterSet.newlines)
        // 「戦闘終了時刻」を含む行を見つけて、その次の行から記録開始
        guard let headerIdx = rawLines.firstIndex(where: { $0.contains("戦闘終了時刻") }) else {
            return []
        }
        var records: [BattleRecord] = []
        for i in (headerIdx + 1)..<rawLines.count {
            let line = rawLines[i].trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { continue }
            let cells = parseRow(line)
            guard cells.count >= 11 else { continue }

            let timestamp = cells[0]
            let result = cells[1]
            let partyStr = cells[2]
            let partyIndex = parsePartyIndex(partyStr)

            let myParty = (3..<7).map { cellIdx -> String in
                let name = cells[cellIdx]
                if name == "?" || name.isEmpty { return "?" }
                return MonsterCatalog.id(for: name) ?? "?"
            }
            let enemyParty = (7..<11).map { cellIdx -> String in
                let name = cells[cellIdx]
                if name == "?" || name.isEmpty { return "?" }
                return MonsterCatalog.id(for: name) ?? "?"
            }
            let record = BattleRecord(
                timestamp: timestamp,
                result: result,
                partyIndex: partyIndex,
                myParty: myParty,
                enemyParty: enemyParty
            )
            records.append(record)
        }
        return records
    }

    // MARK: - Helpers

    /// "パーティ1" → 0、"パーティ?" or 解釈不能 → -1
    private static func parsePartyIndex(_ str: String) -> Int {
        if str.contains("1") { return 0 }
        if str.contains("2") { return 1 }
        if str.contains("3") { return 2 }
        return -1
    }

    /// 1 セルを引用符でくくる (内側の " はエスケープ)
    private static func quoted(_ s: String) -> String {
        let escaped = s.replacingOccurrences(of: "\"", with: "\"\"")
        return "\"\(escaped)\""
    }

    /// CSV 1 行を引用符対応でパースしてセル配列にする
    static func parseRow(_ line: String) -> [String] {
        var cells: [String] = []
        var current = ""
        var inQuotes = false
        let chars = Array(line)
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if inQuotes {
                if c == "\"" {
                    if i + 1 < chars.count && chars[i + 1] == "\"" {
                        current.append("\"")
                        i += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    current.append(c)
                }
            } else {
                if c == "\"" {
                    inQuotes = true
                } else if c == "," {
                    cells.append(current)
                    current = ""
                } else {
                    current.append(c)
                }
            }
            i += 1
        }
        cells.append(current)
        return cells
    }
}
