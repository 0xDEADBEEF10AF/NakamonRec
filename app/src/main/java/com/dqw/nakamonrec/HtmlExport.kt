package com.dqw.nakamonrec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor

/**
 * アプリの戦績画面 (HistoryActivity + item_battle_record) をそのまま写した HTML を 1 ファイルで生成する
 * (自動エクスポートの HTML 形式)。
 *
 * - ページ幅はスマホ実寸の 1080px、高さ 2364px (Pixel 基準)。dp 値は ×3 で px に、sp も ×3。
 *   レイアウト・色・文字サイズは activity_history.xml / item_battle_record.xml / dimens.xml と同じ値を使う。
 * - 上段: TOTAL WIN RATE カード + RECENT TREND (直近 20 戦の移動勝率グラフ、WinRateGraphView と同じ描画) /
 *   パーティ別 3 カード (最新の構成 4 体、勝率、Use:、W-L) / 戦績リスト (アプリと同じく最新が一番下、
 *   読み込み時に最下部へスクロール) / 下部の 3 ボタン (見た目のみ)。
 * - 外部参照なし: モンスターのサムネイルは縮小 PNG を base64 で CSS クラスに 1 回だけ埋め込む。
 * - 数秒ごとに自動再読込 (meta refresh)。OBS のブラウザソースや PC のブラウザで開いたまま更新を追える。
 */
object HtmlExport {
    private const val REFRESH_SEC = 5
    /** dp → px (1080px 幅 = 360dp 相当) */
    private const val S = 3
    private const val PAGE_W = 1080
    private const val PAGE_H = 2364
    /** 埋め込むサムネの画素数 (元は 78×130。22dp=66px の正方形に cover で収めるので高さ 100 あれば足りる) */
    private const val ICON_W = 60
    private const val ICON_H = 100
    private const val GRAPH_WINDOW = 20

    private val iconCache = ConcurrentHashMap<String, String>()

    fun render(context: Context, history: BattleHistory, baseName: String, monsterMaster: List<MonsterData>): String {
        val records = history.records
        val wins = records.count { it.result == "WIN" }
        val losses = records.count { it.result == "LOSE" }
        val total = wins + losses
        val rate = if (total > 0) wins.toDouble() / total * 100.0 else 0.0

        // 使用するサムネイルだけ埋め込む (リスト + パーティカードの最新構成)
        val fileByName = monsterMaster.associate { it.name to it.fileName }
        val iconClass = linkedMapOf<String, String>()
        fun cls(name: String): String? {
            if (name.isEmpty() || name == "?") return null
            return iconClass.getOrPut(name) { "m${iconClass.size}" }
        }
        records.forEach { r -> (r.myParty + r.enemyParty).forEach { cls(it) } }

        val sb = StringBuilder(96 * 1024)
        sb.append("<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">")
        sb.append("<meta http-equiv=\"refresh\" content=\"$REFRESH_SEC\">")
        sb.append("<meta name=\"viewport\" content=\"width=$PAGE_W\">")
        sb.append("<title>なかモンREC 戦績 - ").append(esc(baseName)).append("</title>")
        sb.append("<style>").append(css()).append("</style></head><body>")
        sb.append("<div class=\"screen\">")

        // ===== 上段: TOTAL WIN RATE / RECENT TREND =====
        sb.append("<div class=\"top\">")
        sb.append("<div class=\"card total\">")
        sb.append("<div class=\"tlabel\">TOTAL WIN RATE</div>")
        sb.append("<div class=\"trate\">").append(if (total > 0) RateFormat.percent(rate) else "□").append("</div>")
        sb.append("<div class=\"tcount\">").append(if (total > 0) "$total Matches" else "□").append("</div>")
        sb.append("<div class=\"twl\">").append(winLose(wins, losses)).append("</div>")
        sb.append("</div>")
        sb.append("<div class=\"card graph\"><div class=\"glabel\">RECENT TREND</div>")
        sb.append(graphSvg(records))
        sb.append("</div></div>")

        // ===== パーティ別カード =====
        sb.append("<div class=\"parties\">")
        for (i in 0..2) {
            val p = records.filter { it.partyIndex == i }
            val w = p.count { it.result == "WIN" }
            val l = p.count { it.result == "LOSE" }
            val t = w + l
            val pr = if (t > 0) w.toDouble() / t * 100.0 else 0.0
            val usage = if (records.isNotEmpty()) p.size.toDouble() / records.size * 100.0 else 0.0
            val latest = records.lastOrNull { it.partyIndex == i }?.myParty ?: listOf("", "", "", "")
            sb.append("<div class=\"card party\"><div class=\"pname\">P${i + 1}</div><div class=\"picons\">")
            for (k in 0..3) {
                val c = latest.getOrNull(k)?.let { cls(it) }
                sb.append(if (c != null) "<i class=\"pi $c\"></i>" else "<i class=\"pi hidden\"></i>")
            }
            sb.append("</div><div class=\"prate\">").append(if (t > 0) RateFormat.percent(pr) else "□")
                .append("</div><div class=\"puse\">Use: ").append(RateFormat.percent(usage))
                .append("</div><div class=\"pwl\">").append(winLose(w, l)).append("</div></div>")
        }
        sb.append("</div>")

        // ===== 戦績リスト (アプリと同じく古い → 新しい、最下部が最新) =====
        sb.append("<div class=\"list\" id=\"list\">")
        records.forEach { r ->
            val resCls = if (r.result == "WIN") "win" else "lose"
            val hasMemo = !r.memo.isNullOrBlank()
            sb.append("<div class=\"row\"")
            if (hasMemo) sb.append(" title=\"").append(esc(r.memo!!)).append("\"")
            sb.append(">")
            sb.append("<div class=\"left\"><div class=\"res $resCls\">").append(esc(r.result))
                .append("</div><div class=\"pno\">P").append(r.partyIndex + 1).append(if (hasMemo) " ✎" else "").append("</div></div>")
            sb.append("<div class=\"mid\">").append(iconsHtml(r.myParty, ::cls))
                .append("<div class=\"vs\">vs</div>").append(iconsHtml(r.enemyParty, ::cls)).append("</div>")
            val ts = r.timestamp.replace('-', '.')
            val date = ts.substringBefore(' ')
            val time = ts.substringAfter(' ', "")
            sb.append("<div class=\"time\">").append(esc(date)).append("<br>").append(esc(time)).append("</div>")
            sb.append("</div>")
        }
        sb.append("</div>")

        // ===== 下部ボタン (見た目のみ) =====
        sb.append("<div class=\"fab back\">⟲</div><div class=\"fab mode\">✎</div><div class=\"fab analyze\">⌕</div>")
        sb.append("</div>")

        // サムネイルの CSS (使った体だけ)
        sb.append("<style>")
        iconClass.forEach { (name, c) ->
            val data = fileByName[name]?.let { iconDataUri(context, it) }
            if (data != null) sb.append(".").append(c).append("{background-image:url(").append(data).append(")}\n")
        }
        sb.append("</style>")
        // 読み込みのたびに最新 (最下部) へスクロール (RecyclerView の scrollToPosition(last) と同じ)
        sb.append("<script>var l=document.getElementById('list');if(l){l.scrollTop=l.scrollHeight;}</script>")
        sb.append("</body></html>")
        return sb.toString()
    }

    // ---- 部品 ----

    private fun css(): String {
        fun dp(v: Int) = "${v * S}px"
        fun sp(v: Int) = "${v * S}px"
        return """
        html,body{margin:0;padding:0;background:#222222}
        body{width:${PAGE_W}px;font-family:Roboto,"Noto Sans JP","Hiragino Sans","Yu Gothic",sans-serif;color:#FFFFFF;-webkit-font-smoothing:antialiased}
        .screen{position:relative;width:${PAGE_W}px;height:${PAGE_H}px;display:flex;flex-direction:column;overflow:hidden}
        .card{background:#333333;box-shadow:0 ${dp(2)} ${dp(4)} rgba(0,0,0,.45)}
        .top{display:flex;height:${dp(120)};margin:${dp(48)} ${dp(8)} 0 ${dp(8)}}
        .top .card{margin:${dp(4)};border-radius:${dp(16)};padding:${dp(8)};box-sizing:border-box;display:flex;flex-direction:column}
        .total{flex:1;align-items:center;justify-content:center;text-align:center}
        .graph{flex:1.2}
        .tlabel{color:#AAAAAA;font-weight:700;letter-spacing:.1em;font-size:${sp(14)};line-height:1.2;width:100%}
        .trate{color:#F09199;font-weight:700;font-size:${sp(30)};line-height:1.15;flex:3;display:flex;align-items:center}
        .tcount{color:#AAAAAA;font-size:${sp(12)};flex:1.2;display:flex;align-items:center}
        .twl{font-size:${sp(12)};flex:1.2;display:flex;align-items:center}
        .win{color:#F09199}.lose{color:#90D7EC}
        .glabel{color:#666666;font-size:${sp(11)};font-weight:700;text-align:right;margin-right:${dp(4)};line-height:1.2}
        .graph svg{display:block;width:100%;flex:1;margin-top:${dp(2)}}
        .parties{display:flex;margin:${dp(4)} ${dp(4)} 0 ${dp(4)}}
        .party{flex:1;margin:${dp(4)};border-radius:${dp(10)};padding:${dp(4)} ${dp(4)} ${dp(12)} ${dp(4)};display:flex;flex-direction:column;align-items:center}
        .pname{font-size:${sp(14)};font-weight:700;line-height:1.3}
        .picons{display:flex;justify-content:center;margin:${dp(4)} 0}
        .pi{display:inline-block;width:${dp(18)};height:${dp(18)};margin:${dp(1)};border-radius:${dp(6)};background:#2c2c2c center/cover no-repeat}
        .pi.hidden{visibility:hidden}
        .prate{color:#F09199;font-size:${sp(16)};font-weight:700;line-height:1.3}
        .puse{color:#AAAAAA;font-size:${sp(11)};line-height:1.4}
        .pwl{font-size:${sp(11)};line-height:1.4}
        .list{flex:1;margin:${dp(4)} 0 ${dp(80)} 0;overflow-y:auto;overflow-x:hidden}
        .list::-webkit-scrollbar{display:none}
        .row{display:flex;align-items:center;background:#333333;border-radius:${dp(2)};margin:${dp(2)} ${dp(4)};padding:${dp(8)} ${dp(4)};box-shadow:0 ${dp(1)} ${dp(2)} rgba(0,0,0,.35)}
        .left{width:${dp(45)};display:flex;flex-direction:column;align-items:center;justify-content:center;flex:none}
        .res{font-weight:700;font-size:${sp(14)};line-height:1.2}
        .pno{font-weight:700;font-size:${sp(10)};line-height:1.3;color:#FFFFFF}
        .mid{flex:1;display:flex;align-items:center;justify-content:center;min-width:0}
        .icons{display:flex}
        .mi{display:inline-block;width:${dp(22)};height:${dp(22)};margin-right:${dp(1)};border-radius:${dp(6)};background:#2c2c2c center/cover no-repeat;flex:none}
        .vs{font-weight:700;font-size:${sp(10)};margin:0 ${dp(2)}}
        .time{width:${dp(65)};flex:none;text-align:right;color:#8E8E93;font-size:${sp(10)};line-height:1.25;margin-left:${dp(2)}}
        .fab{position:absolute;bottom:${dp(12)};width:${dp(56)};height:${dp(56)};border-radius:50%;background:#333333;color:#FFFFFF;display:flex;align-items:center;justify-content:center;font-size:${sp(26)};box-shadow:0 ${dp(4)} ${dp(8)} rgba(0,0,0,.5)}
        .fab.back{left:${dp(24)}}
        .fab.mode{left:50%;transform:translateX(-50%);bottom:${dp(16)};box-sizing:border-box;border:${dp(2)} solid #AAAAAA}
        .fab.analyze{right:${dp(24)}}
        """.trimIndent()
    }

    private fun winLose(w: Int, l: Int): String =
        "<span class=\"win\">${w}W</span> - <span class=\"lose\">${l}L</span>"

    private fun iconsHtml(names: List<String>, cls: (String) -> String?): String {
        val sb = StringBuilder("<div class=\"icons\">")
        names.forEach { n ->
            val c = cls(n)
            sb.append("<i class=\"mi").append(if (c != null) " $c" else "").append("\" title=\"").append(esc(n)).append("\"></i>")
        }
        return sb.append("</div>").toString()
    }

    /**
     * WinRateGraphView と同じ描画を SVG で再現する。
     * 直近 20 戦の移動勝率、表示は最後の 20 点 (scrollOffset 最大)、ダイナミックスケール (10% 刻み・最低 20% 幅)、
     * 最新点を選択状態 (白い点線 + 丸 + ツールチップ)。グラフ領域の実寸 (px) は右カードの内側から算出する
     */
    private fun graphSvg(records: List<BattleRecord>): String {
        // 右カードの内寸: 上段幅 1080 - 左右 8dp - カード margin 4dp×4、weight 1:1.2、padding 8dp
        val innerTop = PAGE_W - 2 * 8 * S - 4 * 4 * S
        val cardW = innerTop * 1.2f / 2.2f
        val w = cardW - 2 * 8 * S
        val h = (120 * S - 2 * 8 * S - (11 * S * 1.2f) - 2 * S)
        if (records.size < 2) {
            return "<svg viewBox=\"0 0 ${w.toInt()} ${h.toInt()}\" xmlns=\"http://www.w3.org/2000/svg\"></svg>"
        }
        // 移動勝率 (直近 20 戦)
        val rates = records.indices.map { i ->
            val start = (i - GRAPH_WINDOW + 1).coerceAtLeast(0)
            val sub = records.subList(start, i + 1)
            sub.count { it.result == "WIN" }.toDouble() / sub.size * 100.0
        }
        val padL = 60f; val padR = 30f; val padT = 40f; val padB = 45f
        val innerW = w - padL - padR
        val innerH = h - padT - padB
        val visible = GRAPH_WINDOW
        val stepX = innerW / (visible - 1)
        val scroll = ((rates.size - visible).coerceAtLeast(0)) * stepX

        // 表示範囲内の点だけでスケールを決める
        var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        rates.forEachIndexed { i, r ->
            val x = padL + i * stepX - scroll
            if (x >= padL - 1f && x <= w - padR + 1f) { minV = minOf(minV, r.toFloat()); maxV = maxOf(maxV, r.toFloat()) }
        }
        var dMin = (floor(minV / 10.0) * 10.0).toFloat().coerceAtLeast(0f)
        var dMax = (ceil(maxV / 10.0) * 10.0).toFloat().coerceAtMost(100f)
        if (dMax - dMin < 20f) { if (dMax <= 80f) dMax = dMin + 20f else dMin = dMax - 20f }
        val range = dMax - dMin
        fun yOf(r: Double) = padT + innerH - ((r.toFloat() - dMin) / range * innerH)

        val sb = StringBuilder()
        sb.append("<svg viewBox=\"0 0 ${w.toInt()} ${h.toInt()}\" xmlns=\"http://www.w3.org/2000/svg\">")
        // Y 軸目盛り
        for (i in 0..5) {
            val rv = dMin + range * i / 5
            val y = yOf(rv.toDouble())
            sb.append("<line x1=\"$padL\" y1=\"${f(y)}\" x2=\"${f(w - padR)}\" y2=\"${f(y)}\" stroke=\"#444444\" stroke-width=\"1\"/>")
            sb.append("<text x=\"5\" y=\"${f(y + 6f)}\" fill=\"#888888\" font-size=\"18\">${rv.toInt()}%</text>")
        }
        // クリップ領域
        sb.append("<defs><clipPath id=\"gc\"><rect x=\"$padL\" y=\"0\" width=\"${f(w - padL - padR)}\" height=\"${f(h)}\"/></clipPath></defs>")
        sb.append("<g clip-path=\"url(#gc)\">")
        // X 軸目盛り (1 戦ごと)
        rates.forEachIndexed { i, _ ->
            val x = padL + i * stepX - scroll
            if (x >= padL - 1f && x <= w - padR + 1f) {
                sb.append("<line x1=\"${f(x)}\" y1=\"$padT\" x2=\"${f(x)}\" y2=\"${f(padT + innerH)}\" stroke=\"#444444\" stroke-width=\"1\"/>")
            }
        }
        // 面と線
        val line = StringBuilder(); val area = StringBuilder()
        var first = true
        var lastX = padL
        rates.forEachIndexed { i, r ->
            val x = padL + i * stepX - scroll
            if (x < padL - stepX || x > w + stepX) return@forEachIndexed
            val y = yOf(r)
            if (first) { line.append("M${f(x)} ${f(y)}"); area.append("M${f(x)} ${f(padT + innerH)} L${f(x)} ${f(y)}"); first = false }
            else { line.append(" L${f(x)} ${f(y)}"); area.append(" L${f(x)} ${f(y)}") }
            lastX = x
        }
        area.append(" L${f(lastX)} ${f(padT + innerH)} Z")
        sb.append("<path d=\"$area\" fill=\"#F09199\" fill-opacity=\"0.12\"/>")
        sb.append("<path d=\"$line\" fill=\"none\" stroke=\"#F09199\" stroke-width=\"4\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>")
        sb.append("</g>")
        // 最新点を選択状態で表示 (点線 + 丸 + ツールチップ)
        val li = rates.size - 1
        val lx = padL + li * stepX - scroll
        val ly = yOf(rates[li])
        sb.append("<line x1=\"${f(lx)}\" y1=\"$padT\" x2=\"${f(lx)}\" y2=\"${f(padT + innerH)}\" stroke=\"#FFFFFF\" stroke-width=\"2\" stroke-dasharray=\"10 10\"/>")
        sb.append("<circle cx=\"${f(lx)}\" cy=\"${f(ly)}\" r=\"8\" fill=\"#FFFFFF\"/><circle cx=\"${f(lx)}\" cy=\"${f(ly)}\" r=\"6\" fill=\"#F09199\"/>")
        val tip = String.format(Locale.US, "%s：%dMatches", RateFormat.percent(rates[li]), rates.size)
        val tipW = tip.length * 12f   // 22px 太字の概算幅 (全角混じり)
        val tx = lx.coerceIn(padL + tipW / 2, w - padR - tipW / 2)
        sb.append("<text x=\"${f(tx)}\" y=\"${f(padT - 12f)}\" fill=\"#FFFFFF\" font-size=\"22\" font-weight=\"700\" text-anchor=\"middle\">").append(esc(tip)).append("</text>")
        if (rates.size > visible) {
            sb.append("<text x=\"$padL\" y=\"${f(h - 2f)}\" fill=\"#888888\" fill-opacity=\"0.47\" font-size=\"${10 * S}\">SLIDE TO SEE</text>")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun f(v: Float): String = String.format(Locale.US, "%.1f", v)

    /** templates/<fileName> を縮小 PNG にして data URI に。失敗したら null (灰色の枠だけ表示) */
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
