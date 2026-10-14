package com.dqw.nakamonrec

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.util.Locale

/**
 * グランプリの勝敗画面に重なる「レーティングパネル」から、現在のレーティングと
 * 必要レーティング(あと)を読み取る。純ピクセル処理 (OpenCV 非依存)。iOS RatingPanelReader と同一ロジック。
 *
 * - 数字はゲームの固定ビットマップフォント。0〜9・小数点のグリフを assets の
 *   grandprix_glyphs.json (24×36 二値) として同梱し、切り出した桁を 24×36 に正規化して分類。
 * - 行位置は 1080×2364 基準の固定バンドを画面幅でスケール (UI は幅アンカー+上寄せ) するのが
 *   高速パス。ただし固定バンドは縦 ±5px しか許容しないため (2026-10-12 YouTube 素材で
 *   約 60px のずれが判明)、読めないフレームでは **ラベルアンカー探索** に落ちる:
 *   「現在の」(現在のレーティング行の先頭) をダイス係数で中央帯から探し、見つかった行を
 *   基準に数字を読む。成功したオフセットは GrandPrixMode.learnedRowOffsetY に学習し、
 *   次回以降は探索なしで即読みする (端末ごとのパネル位置は一定という前提)。
 * - 現在レーティング行は白のみ。必要レーティングは「あと」ラベルと隣接するため、
 *   右詰め run から桁幅 (<=20px 相当) のボックスだけ採用して数値部を切り出す。
 * - 変動レーティングは読まない (連続する現在値の差分で導出できるため)。
 * - 「無警告誤記録をしない」: アンカー不一致 / 桁一致率不足 / 妥当性外はすべて null。
 */
object RatingPanelReader {

    /**
     * 読み取り結果。rowOffsetY = 実際に使った行オフセット (1080 基準 px、0 = 固定バンドそのまま)。
     * RankTierReader にも同じオフセットを渡してエンブレム帯を追従させる。
     */
    data class Reading(val currentRating: Double, val neededRating: Double?, val rowOffsetY: Int = 0) {
        val borderRating: Double? get() = neededRating?.let { currentRating + it }
    }

    // 1080×2364 基準ジオメトリ
    private const val BASE_WIDTH = 1080.0
    private const val CUR_Y_TOP = 1165.0; private const val CUR_Y_BOT = 1214.0
    private const val CUR_X_MIN = 620.0;  private const val CUR_X_MAX = 1010.0
    private const val NEED_Y_TOP = 1438.0; private const val NEED_Y_BOT = 1468.0
    private const val NEED_X_MIN = 640.0;  private const val NEED_X_MAX = 1010.0
    private const val GW = 24; private const val GH = 36

    /**
     * 桁分類の最低一致率。下回る桁があれば誤読とみなす (現在行→読み取り全体を破棄 /
     * 必要行→必要値なしとして継続)。実測: 正しく読める桁 >=0.85、スケール歪み
     * (異アスペクト端末のスクショ紙芝居等) での誤分類 <=0.74 — もっともらしい偽値
     * (例 2208.1→7705.1) が妥当性ゲートを通過するのをここで防ぐ。iOS と同一。
     */
    private const val MIN_AGREEMENT = 0.82
    /**
     * 低画質入力の救済 (2026-10-14 実測): 一致率が 0.78〜0.82 でも、2 位候補との差 (マージン) が
     * 十分大きければ正読とみなす。正しい桁はマージン >=0.08、帯域ずれの誤読は 1 位 <=0.73 または
     * マージン <=0.05 に分布しており、この 2 条件で分離できる。iOS と同一。
     */
    private const val RELAXED_AGREEMENT = 0.78; private const val RELAXED_MARGIN = 0.12

    /**
     * 小数点ルール: この幅 (1080 基準 px) 以下の箱は分類結果に関わらず「.」とみなし一致率ゲートを免除。
     * 実機では「.」8px に対して最細の数字「1」が 14px (現在行) なので誤判定の余地がない。
     * 低画質入力では小数点だけ潰れて一致率 0.79 程度に落ち全体が棄却されるため (2026-10-12 実測)。
     */
    private const val DOT_MAX_W_CUR = 10.0
    private const val DOT_MAX_W_NEED = 7.0   // 必要行は数字が小さい (「.」~5px / 「1」~9px)

    // ラベルアンカー探索 (1080 基準)
    private const val ANCHOR_CUR_BASE_Y = 1165      // 「現在の」の基準上端 (r3 実測、x=87)
    private const val ANCHOR_NEED_BASE_Y = 1437     // 「必要」の基準上端 (x=450)
    private const val SEARCH_Y_MIN_RATIO = 0.35; private const val SEARCH_Y_MAX_RATIO = 0.65   // 中央帯
    private const val SEARCH_X_MAX = 320            // ラベルは左寄せ
    private const val NEED_SEARCH_DY = 40; private const val NEED_SEARCH_X0 = 400; private const val NEED_SEARCH_W = 160
    private const val ANCHOR_THRESHOLD = 0.72       // 実測: 実機 0.99〜1.00 / 低画質動画 0.77〜0.88 / 非パネル ≤0.61
    private const val COARSE_STEP_Y = 4; private const val COARSE_STEP_X = 2; private const val REFINE_RADIUS = 4

    /** 直近の read() の診断文字列 (フライトログ用)。タイムアウト時に原因を切り分けるために残す */
    @Volatile var lastDiagnostic: String = ""
        private set
    /**
     * 直近の read() が失敗した段階の粗い分類 (待機中の集計用)。成功時は ""。
     * 値: "箱なし" / "桁一致率不足" / "数値形式不正" / "アンカー未検出" / "アンカー資源なし"
     */
    @Volatile var lastFailureCategory: String = ""
        private set

    private class Bin(val bits: BooleanArray, val w: Int, val h: Int)

    @Volatile private var glyphTable: Map<Char, BooleanArray>? = null
    @Volatile private var anchors: Map<String, Bin>? = null

    /** assets からグリフ辞書をロード (初回のみ)。char → 24×36 二値 */
    fun ensureGlyphs(context: Context) {
        if (glyphTable != null) return
        synchronized(this) {
            if (glyphTable != null) return
            val map = HashMap<Char, BooleanArray>()
            try {
                val json = context.assets.open("grandprix_glyphs.json").bufferedReader().use { it.readText() }
                val obj = JSONObject(json)
                for (key in obj.keys()) {
                    val ch = if (key == "dot") '.' else key[0]
                    val rows = obj.getJSONArray(key)
                    val bits = BooleanArray(GW * GH)
                    var i = 0
                    for (r in 0 until rows.length()) {
                        val row = rows.getString(r)
                        for (c in row) { if (i < bits.size) bits[i] = (c == '#'); i++ }
                    }
                    if (i == GW * GH) map[ch] = bits
                }
            } catch (e: Exception) {
                android.util.Log.e("RatingPanelReader", "glyph load failed: ${e.message}")
            }
            glyphTable = map
        }
    }

    /** assets からラベルアンカー (「現在の」= current /「必要」= need) をロード (初回のみ) */
    private fun ensureAnchors(context: Context) {
        if (anchors != null) return
        synchronized(this) {
            if (anchors != null) return
            val map = HashMap<String, Bin>()
            try {
                val json = context.assets.open("grandprix_label_anchors.json").bufferedReader().use { it.readText() }
                val obj = JSONObject(json)
                for (key in obj.keys()) {
                    val rows = obj.getJSONArray(key)
                    if (rows.length() == 0) continue
                    val w = rows.getString(0).length; val h = rows.length()
                    val bits = BooleanArray(w * h)
                    var i = 0
                    for (r in 0 until h) {
                        val row = rows.getString(r)
                        for (c in row) { if (i < bits.size) bits[i] = (c == '#'); i++ }
                    }
                    if (i == w * h) map[key] = Bin(bits, w, h)
                }
            } catch (e: Exception) {
                android.util.Log.e("RatingPanelReader", "anchor load failed: ${e.message}")
            }
            anchors = map
        }
    }

    /** 勝敗画面 (レーティングパネル表示中) の Bitmap から読み取る。妥当でなければ null */
    fun read(context: Context, bmp: Bitmap): Reading? {
        ensureGlyphs(context); ensureAnchors(context)
        val table = glyphTable ?: return null
        if (table.isEmpty()) return null
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val s = w / BASE_WIDTH
        val diag = ArrayList<String>()

        // 1) 高速パス: 固定バンド (Pixel 系 19.5:9〜20:9 はここで読める)
        readRows(pixels, w, h, s, 0, 0, table, diag)?.let { lastDiagnostic = ""; lastFailureCategory = ""; return it }

        // 2) 学習済みオフセット (以前アンカー探索で見つけた端末固有のずれ)
        val learned = GrandPrixMode.learnedRowOffsetY(context)
        if (learned != null && learned != 0) {
            readRows(pixels, w, h, s, learned, learned, table, diag)?.let { lastDiagnostic = ""; lastFailureCategory = ""; return it }
        }

        // 3) アンカー探索: 「現在の」を中央帯から探し、その行を基準に読む
        val anchorCur = anchors?.get("current") ?: run { lastDiagnostic = "アンカー資源なし"; lastFailureCategory = "アンカー資源なし"; return null }
        val canonH = (h / s).toInt()
        val by0 = (canonH * SEARCH_Y_MIN_RATIO).toInt(); val by1 = (canonH * SEARCH_Y_MAX_RATIO).toInt()
        val band = canonicalBand(pixels, w, h, s, 0, by0, SEARCH_X_MAX, maxOf(0, by1 - by0))
        val m = slideMatch(anchorCur, band, COARSE_STEP_X, COARSE_STEP_Y, REFINE_RADIUS)
        if (m.score < ANCHOR_THRESHOLD) {
            diag.add(String.format(Locale.US, "アンカー: %.2f@y%d (しきい値 %.2f 未満)", m.score, by0 + m.y, ANCHOR_THRESHOLD))
            lastDiagnostic = diag.joinToString(" / "); lastFailureCategory = "アンカー未検出"; return null
        }
        val offsetY = (by0 + m.y) - ANCHOR_CUR_BASE_Y
        // 必要行は第 2 アンカー「必要」で行位置を補正 (見つからなければ現在行と同じオフセット)
        var needOffsetY = offsetY
        anchors?.get("need")?.let { anchorNeed ->
            val ny0 = ANCHOR_NEED_BASE_Y + offsetY - NEED_SEARCH_DY
            val band2 = canonicalBand(pixels, w, h, s, NEED_SEARCH_X0, ny0, NEED_SEARCH_W, NEED_SEARCH_DY * 2 + anchorNeed.h)
            val m2 = slideMatch(anchorNeed, band2, 1, 1, 0)
            if (m2.score >= ANCHOR_THRESHOLD) needOffsetY = (ny0 + m2.y) - ANCHOR_NEED_BASE_Y
        }
        diag.add(String.format(Locale.US, "アンカー: %.2f Δy%+d", m.score, offsetY))
        val r = readRows(pixels, w, h, s, offsetY, needOffsetY, table, diag)
            ?: run { lastDiagnostic = diag.joinToString(" / "); return null }   // 分類は readRows が設定済み
        // 端末固有のずれとして学習 (次回以降は探索なしで読める)
        GrandPrixMode.setLearnedRowOffsetY(context, offsetY)
        lastDiagnostic = ""; lastFailureCategory = ""
        return r
    }

    /** 固定バンドを offsetY (1080 基準 px) だけ縦にずらして読む */
    private fun readRows(pixels: IntArray, w: Int, h: Int, s: Double, offsetY: Int, needOffsetY: Int,
                         table: Map<Char, BooleanArray>, diag: MutableList<String>): Reading? {
        fun sx(v: Double) = (v * s).toInt()
        fun sy(v: Double) = (v * s).toInt()
        val label = if (offsetY == 0) "固定帯域" else String.format(Locale.US, "帯域Δ%+d", offsetY)

        // 現在のレーティング。低信頼度の桁があれば読み取り全体を破棄
        val cyTop = sy(CUR_Y_TOP + offsetY); val cyBot = sy(CUR_Y_BOT + offsetY)
        val curBoxes = numberBoxes(pixels, w, h, cyTop, cyBot, sx(CUR_X_MIN), sx(CUR_X_MAX))
        if (curBoxes.isEmpty()) { diag.add("$label: 箱なし"); lastFailureCategory = "箱なし"; return null }
        val curStr = readDigits(pixels, w, h, curBoxes, cyTop, cyBot, s, DOT_MAX_W_CUR, table)
        if (curStr == null) { diag.add("$label: 桁一致率不足 (箱${curBoxes.size})"); lastFailureCategory = "桁一致率不足"; return null }
        // 表示形式の検証: ゲームは常に「整数部 + 小数 1 桁」(例 2208.1)。桁の融合や run の途切れで
        // 一部だけ読めた文字列 (例 "8" / ".8") を妥当な数値として通さないためのゲート
        val current = if (isValidRatingText(curStr, 3)) curStr.toDoubleOrNull() else null
        if (current == null) { diag.add("$label: 数値形式不正 \"$curStr\""); lastFailureCategory = "数値形式不正"; return null }

        // 必要レーティング (桁幅で「あと」かなを除外 + 形式ゲート)。
        // 低信頼度の桁があれば「必要値なし」として現在値のみ記録する
        val nyTop = sy(NEED_Y_TOP + needOffsetY); val nyBot = sy(NEED_Y_BOT + needOffsetY)
        val needRun = neededBoxes(pixels, w, h, nyTop, nyBot, sx(NEED_X_MIN), sx(NEED_X_MAX), s)
        val needStr = readDigits(pixels, w, h, needRun, nyTop, nyBot, s, DOT_MAX_W_NEED, table) ?: ""
        val needed: Double? = if (isValidRatingText(needStr, 1)) needStr.toDoubleOrNull() else null

        return Reading(current, needed, offsetY)
    }

    /**
     * 「整数部 (minIntDigits〜5 桁) + '.' + 小数 1 桁」の形式か。
     * 2026-10-14: 低画質動画で「あと300.8」の「00」が 1 箱に融合 → 桁幅超で run が切れ、
     * 残った "8" を必要値 8.0 として通してしまったのを機に追加 (26.10.1 以前は ".8"→0.8 の潜在リスク)。
     */
    private fun isValidRatingText(s: String, minIntDigits: Int): Boolean {
        val parts = s.split('.')
        if (parts.size != 2) return false
        val intPart = parts[0]; val frac = parts[1]
        if (intPart.length < minIntDigits || intPart.length > 5 || frac.length != 1) return false
        return intPart.all { it.isDigit() } && frac.all { it.isDigit() }
    }

    /**
     * 箱列を文字列化。細い箱は小数点、それ以外は一致率ゲート付きで分類。1 桁でも不合格なら null。
     *
     * 細い箱 (幅 <= dotMaxWidth @1080) の扱い (2026-10-14 低画質動画の実測で追加):
     * - 隣接する細い箱 (隙間 <=3px) は圧縮で割れた 1 つの小数点として統合する
     * - 先頭/末尾の細い箱は捨てる (数値は「.」で始まらず終わらない = 数字の縁や背景の光のノイズ)
     * - 残った細い箱が 2 個以上なら読み取り不能 (null)
     */
    private fun readDigits(pixels: IntArray, w: Int, h: Int, boxes: List<IntArray>, yTop: Int, yBot: Int,
                           s: Double, dotMaxWidth: Double, table: Map<Char, BooleanArray>): String? {
        val maxDotPx = (dotMaxWidth * s).toInt()
        val mergeGap = (3.0 * s).toInt()
        // 1) 細い箱の統合 — 要素: [x0, x1, thin(1/0)]
        val merged = ArrayList<IntArray>()
        for (box in boxes) {
            val thin = (box[1] - box[0] + 1) <= maxDotPx
            val last = merged.lastOrNull()
            if (thin && last != null && last[2] == 1 && box[0] - last[1] - 1 <= mergeGap) {
                last[1] = box[1]
            } else {
                merged.add(intArrayOf(box[0], box[1], if (thin) 1 else 0))
            }
        }
        // 2) 先頭/末尾の細い箱を捨てる
        while (merged.isNotEmpty() && merged.first()[2] == 1) merged.removeAt(0)
        while (merged.isNotEmpty() && merged.last()[2] == 1) merged.removeAt(merged.size - 1)
        if (merged.isEmpty()) return null
        // 3) 小数点は最大 1 個
        if (merged.count { it[2] == 1 } > 1) return null
        // 4) 桁分類
        val sb = StringBuilder()
        for (b in merged) {
            if (b[2] == 1) { sb.append('.'); continue }
            val g = classify(bitmap(pixels, w, h, intArrayOf(b[0], b[1]), yTop, yBot), table)
            val accepted = g.agreement >= MIN_AGREEMENT ||
                (g.agreement >= RELAXED_AGREEMENT && g.agreement - g.second >= RELAXED_MARGIN)
            if (!accepted) return null
            sb.append(g.ch)
        }
        return sb.toString()
    }

    // 白インク判定 (レーティング値は白。色つき紙吹雪を拾わないよう白のみ)
    private fun isInk(pixels: IntArray, w: Int, h: Int, x: Int, y: Int): Boolean {
        if (x < 0 || x >= w || y < 0 || y >= h) return false
        val p = pixels[y * w + x]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000 > 165
    }

    /** バンド内の数字ボックスを右詰め連続 run で返す (ラベルとの大ギャップで停止) */
    // gapMax=40: 桁間ギャップ (narrow な "1" が並ぶと ~22px) は繋ぎ、ラベルとの大ギャップ
    // (~300px) では停止する。18 だと "2115.9" の "11" 間 22px で切れて先頭を落とす。
    private fun numberBoxes(pixels: IntArray, w: Int, h: Int, yTop: Int, yBot: Int,
                            xMin: Int, xMax: Int, gapMax: Int = 40): List<IntArray> {
        if (yTop < 0 || yBot >= h || xMin < 0 || xMax > w || xMin >= xMax || yTop > yBot) return emptyList()
        val colHas = BooleanArray(xMax - xMin)
        for (x in xMin until xMax) {
            var any = false
            for (y in yTop..yBot) { if (isInk(pixels, w, h, x, y)) { any = true; break } }
            colHas[x - xMin] = any
        }
        val boxes = ArrayList<IntArray>()
        var cs = -1
        for (xi in colHas.indices) {
            val x = xi + xMin
            if (colHas[xi]) { if (cs < 0) cs = x }
            else if (cs >= 0) { if (x - cs >= 2) boxes.add(intArrayOf(cs, x - 1)); cs = -1 }
        }
        if (cs >= 0) boxes.add(intArrayOf(cs, xMax - 1))
        if (boxes.isEmpty()) return emptyList()
        val run = ArrayList<IntArray>()
        run.add(boxes.removeAt(boxes.size - 1))
        while (boxes.isNotEmpty()) {
            val last = boxes[boxes.size - 1]
            if (run[0][0] - last[1] <= gapMax) { run.add(0, last); boxes.removeAt(boxes.size - 1) } else break
        }
        return run
    }

    /** 必要レーティングの数値ボックス: 桁幅 (<=20px @1080 スケール適用) の間だけ採用、広い「あと」で停止 */
    private fun neededBoxes(pixels: IntArray, w: Int, h: Int, yTop: Int, yBot: Int,
                            xMin: Int, xMax: Int, scale: Double): List<IntArray> {
        val run = numberBoxes(pixels, w, h, yTop, yBot, xMin, xMax)
        val maxDigitW = (20.0 * scale).toInt()
        val out = ArrayList<IntArray>()
        for (i in run.indices.reversed()) {
            val box = run[i]
            if (box[1] - box[0] + 1 <= maxDigitW) out.add(0, box) else break
        }
        return out
    }

    /** ボックスを 24×36 二値ビットマップに正規化 (タイトな縦範囲でトリミング) */
    private fun bitmap(pixels: IntArray, w: Int, h: Int, box: IntArray, yTop: Int, yBot: Int): BooleanArray {
        var top = yBot; var bot = yTop
        for (y in yTop..yBot) {
            for (x in box[0]..box[1]) {
                if (isInk(pixels, w, h, x, y)) { if (y < top) top = y; if (y > bot) bot = y }
            }
        }
        if (top > bot) { top = yTop; bot = yBot }
        val bw = box[1] - box[0] + 1; val bh = bot - top + 1
        val out = BooleanArray(GW * GH)
        for (ty in 0 until GH) {
            for (tx in 0 until GW) {
                val sx = box[0] + tx * bw / GW
                val sy = top + ty * bh / GH
                out[ty * GW + tx] = isInk(pixels, w, h, sx, sy)
            }
        }
        return out
    }

    private class Glyph(val ch: Char, val agreement: Double, val second: Double)

    /** 最良一致の文字と一致率 (0..1)、および 2 位の一致率 (マージン判定用) を返す */
    private fun classify(bm: BooleanArray, table: Map<Char, BooleanArray>): Glyph {
        var best = '?'; var bestScore = -1; var secondScore = -1
        for ((ch, glyph) in table) {
            var agree = 0
            for (i in bm.indices) if (bm[i] == glyph[i]) agree++
            if (agree > bestScore) { secondScore = bestScore; bestScore = agree; best = ch }
            else if (agree > secondScore) secondScore = agree
        }
        val n = maxOf(bm.size, 1).toDouble()
        return Glyph(best, if (bm.isEmpty()) 0.0 else bestScore / n, if (secondScore < 0) 0.0 else secondScore / n)
    }

    // ---- アンカー探索 (二値ビットマップ + ダイス係数) ----

    /** 画像の一部を 1080 基準の canonical 座標で二値化 (白インク、nearest neighbor)。画像外は false */
    private fun canonicalBand(pixels: IntArray, w: Int, h: Int, s: Double, x0: Int, y0: Int, cw: Int, ch: Int): Bin {
        val bits = BooleanArray(maxOf(0, cw * ch))
        for (cy in 0 until ch) {
            val sy = ((y0 + cy) * s).toInt()
            for (cx in 0 until cw) bits[cy * cw + cx] = isInk(pixels, w, h, ((x0 + cx) * s).toInt(), sy)
        }
        return Bin(bits, cw, ch)
    }

    private class MatchResult(val score: Double, val x: Int, val y: Int)

    /**
     * 粗 (stepY/stepX) → 密 (±refine, 1px) の 2 段スライド照合。
     * 各窓のインク量を行プレフィックス和で見積もり、テンプレのインク量とかけ離れた窓
     * (背景のみ / 真っ白) はダイス計算をスキップする (処理時間を約 1/2〜1/3 に短縮)。
     */
    private fun slideMatch(tpl: Bin, band: Bin, stepX: Int, stepY: Int, refine: Int): MatchResult {
        var best = MatchResult(0.0, 0, 0)
        val tplInk = tpl.bits.count { it }
        if (tplInk == 0 || band.w < tpl.w || band.h < tpl.h) return best
        val stride = band.w + 1
        val pre = IntArray(band.h * stride)
        for (y in 0 until band.h) {
            var acc = 0; val base = y * stride
            for (x in 0 until band.w) { if (band.bits[y * band.w + x]) acc++; pre[base + x + 1] = acc }
        }
        val lo = tplInk * 2 / 5; val hi = tplInk * 5 / 2
        fun eval(ox: Int, oy: Int) {
            var wink = 0
            for (ty in 0 until tpl.h) { val base = (oy + ty) * stride; wink += pre[base + ox + tpl.w] - pre[base + ox] }
            if (wink < lo || wink > hi) return
            var inter = 0; var bandInk = 0
            for (ty in 0 until tpl.h) {
                val brow = (oy + ty) * band.w + ox; val trow = ty * tpl.w
                for (tx in 0 until tpl.w) {
                    if (band.bits[brow + tx]) { bandInk++; if (tpl.bits[trow + tx]) inter++ }
                }
            }
            val dice = 2.0 * inter / (tplInk + bandInk)
            if (dice > best.score) best = MatchResult(dice, ox, oy)
        }
        var oy = 0
        while (oy <= band.h - tpl.h) { var ox = 0; while (ox <= band.w - tpl.w) { eval(ox, oy); ox += maxOf(1, stepX) }; oy += maxOf(1, stepY) }
        if (refine > 0) {
            val c = best
            for (ry in maxOf(0, c.y - refine)..minOf(band.h - tpl.h, c.y + refine)) {
                for (rx in maxOf(0, c.x - refine)..minOf(band.w - tpl.w, c.x + refine)) eval(rx, ry)
            }
        }
        return best
    }
}
