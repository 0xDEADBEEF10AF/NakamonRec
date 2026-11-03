package com.dqw.nakamonrec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
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
 *   パーティ別 3 カード (最新の構成 4 体、勝率、Use:、W-L) / 戦績リスト (HTML では最新が一番上)。
 *   アプリ下部の 3 ボタンは操作できないので載せない (ビーフ判断 2026-11-02)。
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
        // グランプリ記録 (時系列昇順)。1 件以上あるときだけ「グランプリ集計」タブを出す (2026-11-03)
        val gp = history.grandPrixRecords?.sortedBy { it.timestamp } ?: emptyList()
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
        // 自動再読込は末尾のスクリプト (location.reload) で行う。meta refresh だと URL 末尾の #gp (タブ指定) が
        // 落ちる環境があるため
        sb.append("<meta name=\"viewport\" content=\"width=$PAGE_W\">")
        sb.append("<title>なかモンREC 戦績 - ").append(esc(baseName)).append("</title>")
        // 表示するタブは本文を描く前に決める (<html> の class で CSS が出し分ける)。末尾のスクリプトで切り替えると
        // 再読込のたびに「戦績 → グランプリ集計」の切替が一瞬見えてちらつく (ビーフ指摘 2026-11-03)
        sb.append(headScript())
        sb.append("<style>").append(css()).append(gpCss()).append("</style></head><body>")
        sb.append("<div class=\"screen\">")
        // タブ (グランプリ記録があるときだけ)。上端 48dp の空き (アプリではステータスバー領域) に重ねる
        if (gp.isNotEmpty()) {
            sb.append("<div class=\"tabs\"><div class=\"tab\" data-t=\"history\">戦績</div>")
                .append("<div class=\"tab\" data-t=\"gp\">グランプリ集計</div></div>")
        }
        sb.append("<div class=\"pane\" id=\"pane-history\">")

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

        // ===== 戦績リスト (HTML では最新を最上部に。配信画面で新しい結果がすぐ目に入るようにするため、ビーフ指定 2026-11-02) =====
        sb.append("<div class=\"list\" id=\"list\">")
        records.asReversed().forEach { r ->
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
        sb.append("</div>")   // list
        sb.append("</div>")   // pane-history

        // ===== グランプリ集計タブ =====
        if (gp.isNotEmpty()) {
            sb.append("<div class=\"pane gp\" id=\"pane-gp\">").append(gpPane(context, gp)).append("</div>")
        }

        sb.append("</div>")   // screen

        // サムネイルの CSS (使った体だけ)
        sb.append("<style>")
        iconClass.forEach { (name, c) ->
            val data = fileByName[name]?.let { iconDataUri(context, it) }
            if (data != null) sb.append(".").append(c).append("{background-image:url(").append(data).append(")}\n")
        }
        sb.append("</style>")
        sb.append(script())
        sb.append("</body></html>")
        return sb.toString()
    }

    // ---- 部品 ----

    private fun css(): String {
        fun dp(v: Int) = "${v * S}px"
        fun sp(v: Int) = "${v * S}px"
        return """
        html,body{margin:0;padding:0;height:100%;background:#222222;overflow-x:auto;overflow-y:hidden}
        body{width:${PAGE_W}px;font-family:Roboto,"Noto Sans JP","Hiragino Sans","Yu Gothic",sans-serif;color:#FFFFFF;-webkit-font-smoothing:antialiased}
        .screen{position:relative;width:${PAGE_W}px;height:min(${PAGE_H}px,100vh);display:flex;flex-direction:column;overflow:hidden}
        .card{background:#333333;box-shadow:0 ${dp(2)} ${dp(4)} rgba(0,0,0,.45)}
        .top{display:flex;height:${dp(120)};margin:${dp(48)} ${dp(8)} 0 ${dp(8)}}
        .top .card{margin:${dp(4)};border-radius:${dp(16)};padding:${dp(8)};box-sizing:border-box;display:flex;flex-direction:column;min-width:0}
        .total{flex:1;align-items:center;justify-content:center;text-align:center}
        .graph{flex:1.2}
        .tlabel{color:#AAAAAA;font-weight:700;letter-spacing:.1em;font-size:${sp(14)};line-height:1.2;width:100%;white-space:nowrap}
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
        .list{flex:1;margin:${dp(4)} 0 ${dp(8)} 0;overflow-y:auto;overflow-x:hidden}
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

    /**
     * ページ末尾のスクリプト (両 OS 同一)。
     * - TOTAL WIN RATE ラベルは表示環境のフォントで幅が変わる (Mac/iPhone の Hiragino 太字は Windows より約 1 割広く
     *   14sp 相当だとカード幅に収まらない)。基準の 14sp のまま、収まらない環境でだけ 1px ずつ縮めて 1 行に収める
     * - タブ: URL 末尾の #history / #gp → 前回選んだタブ (localStorage、ファイル名ごと) → 戦績 の順で決める。
     *   OBS では URL に #gp を付けたブラウザソースを置けばグランプリ集計だけを映せる
     * - 自動再読込: location.reload は URL 末尾の # を保つので、再読込後も同じタブが開く
     */
    private fun script(): String = """
        <script>(function(){
        var root=document.documentElement;var key='nakamonrec.tab.'+document.title;
        function fit(){var l=document.querySelector('.tlabel');if(!l)return;var s=parseFloat(getComputedStyle(l).fontSize);while(l.scrollWidth>l.clientWidth&&s>20){s-=1;l.style.fontSize=s+'px';}}
        function show(t){if(t==='gp'&&!document.getElementById('pane-gp'))t='history';root.className='tab-'+t;if(t==='history')fit();try{localStorage.setItem(key,t);}catch(e){}}
        show(root.className==='tab-gp'?'gp':'history');
        var tabs=document.querySelectorAll('.tab');for(var i=0;i<tabs.length;i++){tabs[i].onclick=function(){var v=this.getAttribute('data-t');location.hash=v;show(v);};}
        setTimeout(function(){location.reload();},${REFRESH_SEC * 1000});
        })();</script>
    """.trimIndent()

    /** head 内で <html> に tab-history / tab-gp を付ける (本文描画前に決めるのでタブ切替のちらつきが出ない) */
    private fun headScript(): String =
        "<script>(function(){var t=location.hash.replace('#','');if(!t){try{t=localStorage.getItem('nakamonrec.tab.'+document.title)||'';}catch(e){}}" +
            "document.documentElement.className=(t==='gp')?'tab-gp':'tab-history';})();</script>"

    // ===== グランプリ集計タブ (2026-11-03) =====
    // HistoryActivity.showGrandPrixDialog (サマリ 2 カード) / buildGrandPrixGraph (情報枠 + GrandPrixGraphView + 凡例) /
    // buildGrandPrixList (日時・ランク・戦・レーティング・変動・ボーダー) の写し。アプリではグラフとテキスト一覧を
    // ボタンで切り替えるが、HTML は操作できないのでグラフの下に一覧を並べる (iOS 版と同一出力)。
    // ズーム (直近 50 戦) と横軸切替の操作も載せない (既定 = 全体表示・戦闘数軸)。

    /** 埋め込むエンブレムの高さ (28dp) */
    private const val EMBLEM_H = 84
    private val emblemCache = ConcurrentHashMap<String, String>()

    private data class GpPoint(val rating: Double, val border: Double?, val label: String, val tier: String?, val dateLabel: String)

    private fun gpPane(context: Context, gp: List<GrandPrixRecord>): String {
        val sb = StringBuilder(16 * 1024)
        fun emblemImg(tier: String?, cls: String): String {
            val uri = emblemDataUri(context, tier) ?: return ""
            return "<img class=\"$cls\" src=\"$uri\" alt=\"\">"
        }
        fun r1(v: Double) = String.format(Locale.US, "%.1f", v)
        // サマリはレーティングが読めているレコードだけ (未取得レコードは対象外)
        val readable = gp.filter { !it.isUnread }
        val maxRec = readable.maxByOrNull { it.currentRating }
        val curRec = readable.lastOrNull()

        // 現在レーティング | 最高レーティング
        sb.append("<div class=\"gpsum\">")
        sb.append("<div class=\"gpcard\"><div class=\"gplabel\">現在レーティング</div><div class=\"gpval\">")
            .append(emblemImg(curRec?.rankTier, "gpemblem"))
            .append("<span class=\"gpcur\">").append(curRec?.let { r1(it.currentRating) } ?: "—").append("</span></div></div>")
        sb.append("<div class=\"gpcard\"><div class=\"gplabel\">最高レーティング</div><div class=\"gpval\">")
            .append(emblemImg(maxRec?.rankTier, "gpemblem"))
            .append("<span class=\"gpmax\">").append(maxRec?.let { r1(it.currentRating) } ?: "—").append("</span></div></div>")
        sb.append("</div>")

        // グラフカード: 情報枠 (最新点 = アプリの選択なし既定) + グラフ + 凡例
        val sdfIn = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sdfSel = SimpleDateFormat("M/d HH:mm", Locale.US)
        val points = gp.mapIndexedNotNull { idx, r ->
            if (r.isUnread) return@mapIndexedNotNull null
            val d = try { sdfIn.parse(r.timestamp) } catch (_: Exception) { null }
            GpPoint(r.currentRating, r.borderRating, "${idx + 1}", r.rankTier,
                d?.let { sdfSel.format(it) } ?: r.timestamp.takeLast(5))
        }
        sb.append("<div class=\"gpgraph\"><div class=\"gpsel\">")
        points.lastOrNull()?.let { sel ->
            sb.append(emblemImg(sel.tier, "gpselemblem"))
            sb.append("<span class=\"win\">R:").append(r1(sel.rating)).append("</span>&nbsp;&nbsp;")
            sb.append("<span class=\"lose\">").append(sel.border?.let { "B:" + r1(it) } ?: "B:—").append("</span>&nbsp;&nbsp;")
            sb.append("<span class=\"gpdim\">").append(esc(sel.dateLabel)).append("</span>")
        }
        sb.append("</div>").append(gpGraphSvg(points))
        sb.append("<div class=\"gplegend\"><span class=\"win\">● 自分</span><span class=\"lose\">&nbsp;&nbsp;&nbsp;● ボーダー</span></div></div>")

        // テキスト一覧 (最新が上)。変動 = 直前の読めているレコードとの差 (未取得は飛ばす)
        val sdfDate = SimpleDateFormat("yyyy.MM.dd", Locale.US)
        val sdfTime = SimpleDateFormat("HH:mm", Locale.US)
        val deltas = HashMap<String, Double>()
        var prev: GrandPrixRecord? = null
        for (r in gp) {
            if (r.isUnread) continue
            prev?.let { deltas[r.timestamp] = r.currentRating - it.currentRating }
            prev = r
        }
        sb.append("<div class=\"gphead\"><span class=\"c1\">日時</span><span class=\"c2\">ランク</span><span class=\"c3\">戦</span>")
            .append("<span class=\"c4\">レーティング</span><span class=\"c5\">変動</span><span class=\"c6\">ボーダー</span></div>")
        sb.append("<div class=\"gplist\">")
        gp.asReversed().forEachIndexed { revIdx, r ->
            val battleNo = gp.size - revIdx
            val parsed = try { sdfIn.parse(r.timestamp) } catch (_: Exception) { null }
            val dateStr = parsed?.let { sdfDate.format(it) } ?: r.timestamp.take(10)
            val timeStr = parsed?.let { sdfTime.format(it) } ?: r.timestamp.takeLast(8).take(5)
            val delta = deltas[r.timestamp]
            val deltaStr = delta?.let { String.format(Locale.US, "%+.1f", it) } ?: "—"
            val deltaCls = if (delta == null) "gpdim" else if (delta >= 0) "win" else "lose"
            sb.append("<div class=\"gprow\"><span class=\"c1\">").append(esc(dateStr)).append("<br>").append(esc(timeStr)).append("</span>")
            sb.append("<span class=\"c2\">").append(emblemImg(r.rankTier, "gprowemblem")).append("</span>")
            sb.append("<span class=\"c3 gpdim\">").append(battleNo).append("</span>")
            if (r.isUnread) sb.append("<span class=\"c4 gpunread\">未取得</span>")
            else sb.append("<span class=\"c4 gprating\">").append(r1(r.currentRating)).append("</span>")
            sb.append("<span class=\"c5 ").append(deltaCls).append("\">").append(deltaStr).append("</span>")
            sb.append("<span class=\"c6 lose\">").append(r.borderRating?.let { r1(it) } ?: "—").append("</span></div>")
        }
        sb.append("</div>")
        return sb.toString()
    }

    /**
     * GrandPrixGraphView.onDraw の写し (全体表示・戦闘数軸・選択 = 最新点)。
     * 余白 16/88/40/40 px、Y 軸 4 分割の目盛りを右側に、線 4px、点が 100 以下ならリング、最新点に白い縦線と強調点
     */
    private fun gpGraphSvg(points: List<GpPoint>): String {
        val w = (PAGE_W - 2 * 20 * S - 2 * 12 * S).toDouble()   // ペイン余白 20dp + カード余白 12dp
        val h = (265 * S).toDouble()
        fun fx(v: Double) = String.format(Locale.US, "%.1f", v)
        if (points.isEmpty()) return "<svg class=\"gpsvg\" viewBox=\"0 0 ${w.toInt()} ${h.toInt()}\" xmlns=\"http://www.w3.org/2000/svg\"></svg>"
        val padL = 16.0; val padR = 88.0; val padT = 40.0; val padB = 40.0
        val innerW = w - padL - padR
        val innerH = h - padT - padB
        val n = points.size
        val stepX = if (n > 1) innerW / (n - 1) else innerW
        val vals = points.flatMap { listOfNotNull(it.rating, it.border) }
        val minV = vals.minOrNull() ?: 0.0
        val maxV = vals.maxOrNull() ?: 1.0
        val range = (maxV - minV).coerceAtLeast(1.0)
        val yMin = minV - range * 0.1
        val yMax = maxV + range * 0.1
        val yspan = (yMax - yMin).coerceAtLeast(1.0)
        fun yOf(v: Double) = padT + innerH - ((v - yMin) / yspan * innerH)
        fun xOf(i: Int) = padL + i * stepX

        val sb = StringBuilder(8 * 1024)
        sb.append("<svg class=\"gpsvg\" viewBox=\"0 0 ${w.toInt()} ${h.toInt()}\" xmlns=\"http://www.w3.org/2000/svg\">")
        for (i in 0..4) {
            val v = yMin + (yMax - yMin) * i / 4
            val y = yOf(v)
            sb.append("<line x1=\"${fx(padL)}\" y1=\"${fx(y)}\" x2=\"${fx(w - padR)}\" y2=\"${fx(y)}\" stroke=\"#444444\" stroke-width=\"1\"/>")
            sb.append("<text x=\"${fx(w - padR + 8)}\" y=\"${fx(y + 6)}\" fill=\"#888888\" font-size=\"18\">")
                .append(String.format(Locale.US, "%.0f", v)).append("</text>")
        }
        // X 軸ラベル (戦闘番号)。前のラベルと重なるものは描かない
        var lastRight = Double.NEGATIVE_INFINITY
        points.forEachIndexed { i, p ->
            val x = xOf(i)
            val tw = p.label.length * 10.0
            val left = x - tw / 2
            if (left > lastRight + 16 && x + tw / 2 <= w - padR) {
                sb.append("<text x=\"${fx(left)}\" y=\"${fx(h - 10)}\" fill=\"#888888\" font-size=\"18\">").append(esc(p.label)).append("</text>")
                lastRight = x + tw / 2
            }
        }
        val pr = StringBuilder()
        val pb = StringBuilder()
        points.forEachIndexed { i, p ->
            val x = xOf(i)
            pr.append(if (pr.isEmpty()) "M" else " L").append(fx(x)).append(' ').append(fx(yOf(p.rating)))
            p.border?.let { b -> pb.append(if (pb.isEmpty()) "M" else " L").append(fx(x)).append(' ').append(fx(yOf(b))) }
        }
        sb.append("<path d=\"").append(pb).append("\" fill=\"none\" stroke=\"#90D7EC\" stroke-width=\"4\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>")
        sb.append("<path d=\"").append(pr).append("\" fill=\"none\" stroke=\"#F09199\" stroke-width=\"4\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>")
        if (n <= 100) {
            points.forEachIndexed { i, p ->
                val x = xOf(i)
                p.border?.let { sb.append("<circle cx=\"${fx(x)}\" cy=\"${fx(yOf(it))}\" r=\"4\" fill=\"#333333\" stroke=\"#90D7EC\" stroke-width=\"2\"/>") }
                sb.append("<circle cx=\"${fx(x)}\" cy=\"${fx(yOf(p.rating))}\" r=\"4\" fill=\"#333333\" stroke=\"#F09199\" stroke-width=\"2\"/>")
            }
        }
        // 最新点のインジケーター (白い縦線 + 強調点)
        val lp = points[n - 1]
        val lx = xOf(n - 1)
        sb.append("<line x1=\"${fx(lx)}\" y1=\"${fx(padT)}\" x2=\"${fx(lx)}\" y2=\"${fx(padT + innerH)}\" stroke=\"#FFFFFF\" stroke-width=\"2\"/>")
        sb.append("<circle cx=\"${fx(lx)}\" cy=\"${fx(yOf(lp.rating))}\" r=\"6\" fill=\"#FFFFFF\"/>")
            .append("<circle cx=\"${fx(lx)}\" cy=\"${fx(yOf(lp.rating))}\" r=\"4\" fill=\"#F09199\"/>")
        lp.border?.let {
            sb.append("<circle cx=\"${fx(lx)}\" cy=\"${fx(yOf(it))}\" r=\"6\" fill=\"#FFFFFF\"/>")
                .append("<circle cx=\"${fx(lx)}\" cy=\"${fx(yOf(it))}\" r=\"4\" fill=\"#90D7EC\"/>")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    /** ランク帯エンブレム (drawable-nodpi の PNG) を高さ EMBLEM_H に縮小して data URI に。未設定/無しなら null */
    private fun emblemDataUri(context: Context, tier: String?): String? {
        val asset = GrandPrixRecord.rankEmblemAsset(tier) ?: return null
        emblemCache[asset]?.let { return it }
        val resId = context.resources.getIdentifier(asset, "drawable", context.packageName)
        if (resId == 0) return null
        val src = BitmapFactory.decodeResource(context.resources, resId) ?: return null
        val w = (src.width.toDouble() / src.height * EMBLEM_H).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, EMBLEM_H, true)
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, bos)
        val uri = "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        emblemCache[asset] = uri
        return uri
    }

    /** タブ + グランプリ集計ペインの CSS (dp×3 / sp×3 の px 直書き。両 OS 同一) */
    private fun gpCss(): String = """
        .tabs{position:absolute;top:0;left:0;right:0;height:132px;display:flex;align-items:flex-end;padding:0 60px;gap:48px;z-index:2}
        .tab{font-size:39px;font-weight:700;color:#888888;padding:0 6px 10px;border-bottom:6px solid transparent;cursor:pointer}
        html:not(.tab-gp) .tab[data-t=history],html.tab-gp .tab[data-t=gp]{color:#F09199;border-bottom-color:#F09199}
        .pane{display:none;flex:1;min-height:0;flex-direction:column}
        html:not(.tab-gp) #pane-history,html.tab-gp #pane-gp{display:flex}
        .gp{padding:192px 60px 24px}
        .gpsum{display:flex}
        .gpcard{flex:1;margin:0 12px;background:#333333;border-radius:30px;padding:30px 24px;display:flex;flex-direction:column;align-items:center}
        .gplabel{color:#888888;font-size:33px;line-height:1.3}
        .gpval{display:flex;align-items:center}
        .gpemblem{height:84px;margin-right:18px}
        .gpcur{color:#FFFFFF;font-size:78px;font-weight:700;line-height:1.2}
        .gpmax{color:#F09199;font-size:78px;font-weight:700;line-height:1.2}
        .gpgraph{margin-top:24px;background:#333333;border-radius:36px;padding:24px 36px;flex:none}
        .gpsel{display:flex;align-items:center;padding:12px 0;font-size:39px;min-height:60px}
        .gpselemblem{height:60px;margin-right:18px}
        .gpdim{color:#888888}
        .gpsvg{display:block;width:100%;height:795px}
        .gplegend{padding-top:12px;font-size:36px}
        .gphead{display:flex;align-items:center;padding:18px 24px;margin-top:24px}
        .gphead span{font-size:33px;color:#888888}
        .gplist{flex:1;min-height:0;overflow-y:auto}
        .gplist::-webkit-scrollbar{display:none}
        .gprow{display:flex;align-items:center;background:#333333;border-radius:24px;padding:24px;margin:6px 0}
        .c1{flex:1.6;font-size:30px;color:#FFFFFF;line-height:1.3}
        .c2{flex:1;display:flex;align-items:center}
        .c3{flex:1;text-align:right;font-size:36px}
        .c4{flex:1.8;text-align:right}
        .c5{flex:1.1;text-align:right;font-size:36px}
        .c6{flex:1.3;text-align:right;font-size:36px}
        .gprowemblem{height:72px}
        .gprating{color:#FFFFFF;font-size:42px;font-weight:700}
        .gpunread{color:#FFB74D;font-size:33px;font-weight:700}
    """.trimIndent()

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
