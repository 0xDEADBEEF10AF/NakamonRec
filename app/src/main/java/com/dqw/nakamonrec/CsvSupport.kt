package com.dqw.nakamonrec

import java.util.Locale

/**
 * 戦績 CSV の入出力ヘルパー。iOS `CSVSupport.swift` と同一フォーマット・同一ロジック。
 *
 * フォーマット (UTF-8):
 * ```
 * 総合戦績,X戦 X勝 X敗
 * パーティ1戦績,X戦 X勝 X敗
 * パーティ2戦績,X戦 X勝 X敗
 * パーティ3戦績,X戦 X勝 X敗
 * (空行)
 * "戦闘終了時刻","勝敗","選択パーティ","自分1",..."自分4","相手1",..."相手4"
 * "2026-05-10 16:35:32","WIN","パーティ2","デスタムーア",..."ハーゴン"
 * (空行)                                  ← 以下 26.10.1 で追加。GP 記録が 1 件以上あるときだけ出力
 * グランプリ戦績,X件
 * "日時","勝敗","レーティング","必要レーティング","ランク帯"
 * "2026-11-07 21:02:11","WIN","2208.1","270.2","マスター1"
 * ```
 *
 * グランプリ (GP) セクションの設計 (2026-09-02 ビーフ承認):
 * - 戦績と GP は 1 対 1 対応しないため、JSON が別配列なのと同じく CSV でも独立セクション。
 * - **GP セクションの列数は将来も 10 列以下を維持すること。** 旧バージョンのインポータ
 *   (parts.size >= 11) は GP 行を自然にスキップするため、これが後方互換の根拠。
 * - インポートはヘッダ行探索方式: 「戦闘終了時刻」を含む行 → 戦績 /
 *   先頭セル「日時」かつ「レーティング」を含む行 → GP。固定オフセット (旧 drop(5)) には依存しない。
 */
object CsvSupport {

    /** GP セクションのカラムヘッダー (両OS同一。**10 列以下を維持**) */
    private val grandPrixColumns = listOf("日時", "勝敗", "レーティング", "必要レーティング", "ランク帯")

    data class Decoded(val records: List<BattleRecord>, val grandPrixRecords: List<GrandPrixRecord>)

    // ---- Encode ----

    fun encode(history: BattleHistory): String {
        val sb = StringBuilder()
        sb.appendLine("総合戦績,${history.records.size}戦 ${history.totalWins}勝 ${history.totalLosses}敗")
        (0..2).forEach { idx ->
            val pRecs = history.records.filter { it.partyIndex == idx }
            val pWins = pRecs.count { it.result == "WIN" }
            sb.appendLine("パーティ${idx + 1}戦績,${pRecs.size}戦 ${pWins}勝 ${pRecs.size - pWins}敗")
        }
        sb.appendLine()
        sb.appendLine(
            listOf("戦闘終了時刻", "勝敗", "選択パーティ", "自分1", "自分2", "自分3", "自分4", "相手1", "相手2", "相手3", "相手4")
                .joinToString(",") { quote(it) }
        )
        history.records.forEach { r ->
            val cells = listOf(r.timestamp, r.result, "パーティ${r.partyIndex + 1}") +
                (0..3).map { r.myParty.getOrElse(it) { "" } } +
                (0..3).map { r.enemyParty.getOrElse(it) { "" } }
            sb.appendLine(cells.joinToString(",") { quote(it) })
        }

        // グランプリセクション (記録があるときだけ。無ければ従来と完全に同じ出力)
        val gp = history.grandPrixRecords ?: emptyList()
        if (gp.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("グランプリ戦績,${gp.size}件")
            sb.appendLine(grandPrixColumns.joinToString(",") { quote(it) })
            gp.forEach { r ->
                val cells = listOf(
                    r.timestamp,
                    r.result,
                    formatRating(r.currentRating),
                    r.neededRating?.let { formatRating(it) } ?: "",
                    r.rankTier ?: ""
                )
                sb.appendLine(cells.joinToString(",") { quote(it) })
            }
        }
        return sb.toString()
    }

    /** レーティングは小数 1 桁・カンマなし (画面表記と同じ) */
    private fun formatRating(v: Double): String = String.format(Locale.US, "%.1f", v)

    // ---- Decode ----

    /** CSV 文字列から 戦績 + GP 記録 の両方を読む。どちらのセクションも無ければ空リスト */
    fun decodeAll(csv: String): Decoded {
        val text = csv.removePrefix("\uFEFF")   // BOM 除去
        val rawLines = text.split(Regex("\\r?\\n"))
        return Decoded(decodeBattleRecords(rawLines), decodeGrandPrixRecords(rawLines))
    }

    private fun decodeBattleRecords(rawLines: List<String>): List<BattleRecord> {
        val headerIdx = rawLines.indexOfFirst { it.contains("戦闘終了時刻") }
        if (headerIdx < 0) return emptyList()
        val out = mutableListOf<BattleRecord>()
        for (i in headerIdx + 1 until rawLines.size) {
            val line = rawLines[i].trim()
            if (line.isEmpty()) continue
            val cells = parseRow(line)
            // 11 列未満 (GP セクションの行や集計行) はスキップ = GP セクションとの共存の根拠
            if (cells.size < 11) continue
            val timestamp = cells[0]; val result = cells[1]; val partyName = cells[2]
            val partyIndex = partyName.replace(Regex("[^0-9]"), "").toIntOrNull()?.minus(1) ?: 0
            val myParty = listOf(cells[3], cells[4], cells[5], cells[6])
            val enemyParty = listOf(cells[7], cells[8], cells[9], cells[10])
            out.add(BattleRecord(timestamp, result, partyIndex, myParty, enemyParty))
        }
        return out
    }

    /**
     * GP セクション: 先頭セル「日時」かつ「レーティング」を含むヘッダ行の次から、
     * 3 列以上 10 列以下の行を読む。レーティングが数値でない行はスキップ
     */
    private fun decodeGrandPrixRecords(rawLines: List<String>): List<GrandPrixRecord> {
        val headerIdx = rawLines.indexOfFirst { it.contains("レーティング") && parseRow(it).firstOrNull() == "日時" }
        if (headerIdx < 0) return emptyList()
        val out = mutableListOf<GrandPrixRecord>()
        for (i in headerIdx + 1 until rawLines.size) {
            val line = rawLines[i].trim()
            if (line.isEmpty()) continue
            val cells = parseRow(line)
            if (cells.size < 3 || cells.size > 10) continue
            val current = cells[2].trim().toDoubleOrNull() ?: continue
            val needed = cells.getOrNull(3)?.trim()?.toDoubleOrNull()
            val tierRaw = cells.getOrNull(4) ?: ""
            val tier = if (GrandPrixRecord.rankTiers.contains(tierRaw)) tierRaw else null
            val result = if (cells[1] == "LOSE") "LOSE" else "WIN"
            out.add(
                GrandPrixRecord(
                    timestamp = cells[0],
                    result = result,
                    currentRating = current,
                    neededRating = needed,
                    isRankUp = false,
                    rankTier = tier
                )
            )
        }
        return out
    }

    // ---- Helpers ----

    /** 1 セルを引用符でくくる (内側の " はエスケープ) */
    private fun quote(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

    /** CSV 1 行を引用符対応でパースしてセル配列にする (iOS parseRow と同一ロジック) */
    fun parseRow(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"'); i += 2; continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    current.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> { cells.add(current.toString()); current.setLength(0) }
                    else -> current.append(c)
                }
            }
            i++
        }
        cells.add(current.toString())
        return cells
    }
}
