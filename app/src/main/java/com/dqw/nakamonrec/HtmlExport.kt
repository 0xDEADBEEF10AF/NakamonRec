package com.dqw.nakamonrec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 戦績画面ライクな HTML を 1 ファイルで生成する (自動エクスポートの HTML 形式)。
 *
 * - 外部参照なし: モンスターのサムネイルは半分サイズ (39×65) の PNG を base64 で埋め込む。
 *   同じモンスターは CSS クラス 1 つにまとめ、行ごとに重複させない (100 戦分でも数百 KB)。
 * - 数秒ごとに自動再読込する (meta refresh)。OBS のブラウザソースや PC のブラウザで開いたまま更新を追える。
 * - 配色はアプリと同じ (WIN #F09199 / LOSE #90D7EC、ダーク背景)。
 */
object HtmlExport {
    private const val MAX_ROWS = 100
    private const val MAX_GP_ROWS = 30
    private const val REFRESH_SEC = 5
    private const val ICON_W = 39
    private const val ICON_H = 65

    /** fileName → data URI。プロセス内で使い回す (同じ体は 1 回だけ縮小・符号化) */
    private val iconCache = ConcurrentHashMap<String, String>()

    fun render(context: Context, history: BattleHistory, baseName: String, monsterMaster: List<MonsterData>): String {
        val records = history.records
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val totalWins = records.count { it.result == "WIN" }
        val totalLosses = records.count { it.result == "LOSE" }
        val todayRecords = records.filter { it.timestamp.startsWith(today) }
        val todayWins = todayRecords.count { it.result == "WIN" }
        val todayLosses = todayRecords.count { it.result == "LOSE" }

        val shown = records.asReversed().take(MAX_ROWS)
        val gpAll = history.grandPrixRecords ?: emptyList()
        val gpShown = gpAll.asReversed().take(MAX_GP_ROWS)

        // 表示する体だけサムネイルを埋め込む
        val fileByName = monsterMaster.associate { it.name to it.fileName }
        val iconClass = linkedMapOf<String, String>()   // monster name → css class
        shown.forEach { r -> (r.myParty + r.enemyParty).forEach { n -> if (n != "?" && n.isNotEmpty()) iconClass.getOrPut(n) { "m${iconClass.size}" } } }
        val iconCss = StringBuilder()
        iconClass.forEach { (name, cls) ->
            val data = fileByName[name]?.let { iconDataUri(context, it) }
            if (data != null) iconCss.append(".$cls{background-image:url($data)}\n")
        }

        val sb = StringBuilder(64 * 1024)
        sb.append("<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">")
        sb.append("<meta http-equiv=\"refresh\" content=\"$REFRESH_SEC\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>なかモンREC 戦績 - ").append(esc(baseName)).append("</title>")
        sb.append("<style>")
        sb.append(
            """
            :root{--bg:#15171b;--card:#22252b;--line:#33373f;--fg:#f2f2f2;--muted:#9aa0a8;--win:#F09199;--lose:#90D7EC}
            *{box-sizing:border-box}
            body{margin:0;padding:16px;background:var(--bg);color:var(--fg);font-family:-apple-system,"Hiragino Sans","Yu Gothic",Roboto,sans-serif;font-size:14px}
            header{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap;margin-bottom:12px}
            h1{font-size:18px;margin:0}
            .file{color:var(--muted)}
            .updated{margin-left:auto;color:var(--muted);font-size:12px}
            .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin-bottom:14px}
            .card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:10px 12px}
            .card .t{color:var(--muted);font-size:12px;margin-bottom:4px}
            .card .v{font-size:20px;font-weight:700}
            .card .s{color:var(--muted);font-size:12px;margin-top:2px}
            .win{color:var(--win)} .lose{color:var(--lose)}
            table{width:100%;border-collapse:collapse;background:var(--card);border:1px solid var(--line);border-radius:10px;overflow:hidden}
            th,td{padding:6px 8px;border-bottom:1px solid var(--line);text-align:left;vertical-align:middle;white-space:nowrap}
            th{color:var(--muted);font-weight:600;font-size:12px}
            tr:last-child td{border-bottom:none}
            td.res{font-weight:700}
            .party{display:flex;gap:3px}
            .mi{display:inline-block;width:${ICON_W}px;height:${ICON_H}px;border-radius:6px;background:#2c3038 center/cover no-repeat}
            .vs{color:var(--muted);padding:0 6px}
            .time{color:var(--muted);font-size:12px}
            h2{font-size:15px;margin:18px 0 8px;color:var(--muted)}
            footer{margin-top:16px;color:var(--muted);font-size:11px}
            """.trimIndent()
        )
        sb.append('\n').append(iconCss)
        sb.append("</style></head><body>")

        // ---- ヘッダー ----
        sb.append("<header><h1>なかモンREC 戦績</h1><div class=\"file\">").append(esc(baseName))
            .append("</div><div class=\"updated\">更新 ").append(now).append("</div></header>")

        // ---- サマリーカード ----
        sb.append("<div class=\"cards\">")
        sb.append(card("総合戦績", "${records.size}戦 <span class=\"win\">${totalWins}勝</span> <span class=\"lose\">${totalLosses}敗</span>",
            "勝率 " + rate(totalWins, records.size)))
        sb.append(card("今日", "${todayRecords.size}戦 <span class=\"win\">${todayWins}勝</span> <span class=\"lose\">${todayLosses}敗</span>",
            "勝率 " + rate(todayWins, todayRecords.size)))
        (0..2).forEach { idx ->
            val p = records.filter { it.partyIndex == idx }
            val w = p.count { it.result == "WIN" }
            sb.append(card("パーティ${idx + 1}", "${p.size}戦 <span class=\"win\">${w}勝</span> <span class=\"lose\">${p.size - w}敗</span>",
                "勝率 " + rate(w, p.size) + " / 使用率 " + rate(p.size, records.size)))
        }
        val gpLatest = gpAll.lastOrNull { !it.isUnread }
        if (gpLatest != null) {
            val prev = gpAll.filter { !it.isUnread }.dropLast(1).lastOrNull()
            val diff = prev?.let { gpLatest.currentRating - it.currentRating }
            val diffText = diff?.let { (if (it >= 0) "+" else "") + String.format(Locale.US, "%.1f", it) } ?: ""
            sb.append(card("グランプリ レーティング", String.format(Locale.US, "%.1f", gpLatest.currentRating) + (if (diffText.isNotEmpty()) " <span class=\"s\">($diffText)</span>" else ""),
                "${gpAll.size}戦記録" + (gpLatest.rankTier?.let { " / $it" } ?: "")))
        }
        sb.append("</div>")

        // ---- 対戦履歴 ----
        sb.append("<h2>対戦履歴").append(if (records.size > shown.size) " (直近 ${shown.size} 戦)" else "").append("</h2>")
        sb.append("<table><thead><tr><th>日時</th><th>勝敗</th><th>P</th><th>自分</th><th></th><th>相手</th></tr></thead><tbody>")
        shown.forEach { r ->
            val cls = if (r.result == "WIN") "win" else "lose"
            sb.append("<tr><td class=\"time\">").append(esc(r.timestamp.replace('-', '.')))
                .append("</td><td class=\"res $cls\">").append(esc(r.result))
                .append("</td><td>").append(if (r.partyIndex >= 0) "P${r.partyIndex + 1}" else "P?")
                .append("</td><td>").append(partyHtml(r.myParty, iconClass))
                .append("</td><td class=\"vs\">vs</td><td>").append(partyHtml(r.enemyParty, iconClass))
                .append("</td></tr>")
        }
        if (shown.isEmpty()) sb.append("<tr><td colspan=\"6\" class=\"time\">まだ記録がありません</td></tr>")
        sb.append("</tbody></table>")

        // ---- グランプリ記録 ----
        if (gpShown.isNotEmpty()) {
            sb.append("<h2>グランプリ記録").append(if (gpAll.size > gpShown.size) " (直近 ${gpShown.size} 件)" else "").append("</h2>")
            sb.append("<table><thead><tr><th>日時</th><th>勝敗</th><th>レーティング</th><th>ボーダー</th><th>ランク帯</th></tr></thead><tbody>")
            gpShown.forEach { g ->
                val cls = if (g.result == "WIN") "win" else "lose"
                sb.append("<tr><td class=\"time\">").append(esc(g.timestamp))
                    .append("</td><td class=\"res $cls\">").append(esc(g.result))
                    .append("</td><td>").append(if (g.isUnread) "未取得" else String.format(Locale.US, "%.1f", g.currentRating))
                    .append("</td><td>").append(g.borderRating?.let { String.format(Locale.US, "%.1f", it) } ?: "")
                    .append("</td><td>").append(esc(g.rankTier ?: ""))
                    .append("</td></tr>")
            }
            sb.append("</tbody></table>")
        }

        sb.append("<footer>なかモンREC 自動エクスポート · ${REFRESH_SEC} 秒ごとに自動更新</footer>")
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun card(title: String, value: String, sub: String): String =
        "<div class=\"card\"><div class=\"t\">${esc(title)}</div><div class=\"v\">$value</div><div class=\"s\">${esc(sub)}</div></div>"

    private fun rate(num: Int, den: Int): String =
        if (den > 0) RateFormat.percent(num.toDouble() / den * 100.0) else "-"

    private fun partyHtml(names: List<String>, iconClass: Map<String, String>): String {
        val sb = StringBuilder("<div class=\"party\">")
        names.forEach { n ->
            val cls = iconClass[n]
            if (cls != null) sb.append("<i class=\"mi $cls\" title=\"").append(esc(n)).append("\"></i>")
            else sb.append("<i class=\"mi\" title=\"").append(esc(n)).append("\"></i>")
        }
        return sb.append("</div>").toString()
    }

    /** templates/<fileName> を半分サイズの PNG にして data URI に。失敗したら null (灰色の枠だけ表示) */
    private fun iconDataUri(context: Context, fileName: String): String? {
        iconCache[fileName]?.let { return it }
        return try {
            val src = context.assets.open("templates/$fileName").use { BitmapFactory.decodeStream(it) } ?: return null
            val scaled = Bitmap.createScaledBitmap(src, ICON_W, ICON_H, true)
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, bos)
            if (scaled !== src) scaled.recycle()
            src.recycle()
            val uri = "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            iconCache[fileName] = uri
            uri
        } catch (_: Exception) {
            null
        }
    }

    private fun esc(s: String): String = buildString(s.length) {
        s.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
