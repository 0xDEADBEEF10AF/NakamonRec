package com.dqw.nakamonrec

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject

/**
 * グランプリの勝敗画面に重なる「レーティングパネル」から、現在のレーティングと
 * 必要レーティング(あと)を読み取る。純ピクセル処理 (OpenCV 非依存)。iOS RatingPanelReader と同一ロジック。
 *
 * - 数字はゲームの固定ビットマップフォント。0〜9・小数点のグリフを assets の
 *   grandprix_glyphs.json (24×36 二値) として同梱し、切り出した桁を 24×36 に正規化して分類。
 * - 行位置は 1080×2364 基準の固定バンドを画面幅でスケール (UI は幅アンカー+上寄せ)。
 * - 現在レーティング行は白のみ。必要レーティングは「あと」ラベルと隣接するため、
 *   右詰め run から桁幅 (<=20px 相当) のボックスだけ採用して数値部を切り出す。
 * - 変動レーティングは読まない (連続する現在値の差分で導出できるため)。
 */
object RatingPanelReader {

    data class Reading(val currentRating: Double, val neededRating: Double?) {
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

    @Volatile private var glyphTable: Map<Char, BooleanArray>? = null

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

    /** 勝敗画面 (レーティングパネル表示中) の Bitmap から読み取る。妥当でなければ null */
    fun read(context: Context, bmp: Bitmap): Reading? {
        ensureGlyphs(context)
        val table = glyphTable ?: return null
        if (table.isEmpty()) return null
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val s = w / BASE_WIDTH

        fun sx(v: Double) = (v * s).toInt()
        fun sy(v: Double) = (v * s).toInt()

        // 現在のレーティング。低信頼度の桁があれば読み取り全体を破棄
        val curBoxes = numberBoxes(pixels, w, h, sy(CUR_Y_TOP), sy(CUR_Y_BOT), sx(CUR_X_MIN), sx(CUR_X_MAX))
        val curGlyphs = curBoxes.map {
            classify(bitmap(pixels, w, h, it, sy(CUR_Y_TOP), sy(CUR_Y_BOT)), table)
        }
        if (curGlyphs.any { it.second < MIN_AGREEMENT }) return null
        val curStr = curGlyphs.joinToString("") { it.first.toString() }
        val current = curStr.toDoubleOrNull() ?: return null

        // 必要レーティング (桁幅で「あと」かなを除外 + 妥当性ゲート)。
        // 低信頼度の桁があれば「必要値なし」として現在値のみ記録する
        val needRun = neededBoxes(pixels, w, h, sy(NEED_Y_TOP), sy(NEED_Y_BOT), sx(NEED_X_MIN), sx(NEED_X_MAX), s)
        val needGlyphs = needRun.map {
            classify(bitmap(pixels, w, h, it, sy(NEED_Y_TOP), sy(NEED_Y_BOT)), table)
        }
        val needStr = if (needGlyphs.all { it.second >= MIN_AGREEMENT })
            needGlyphs.joinToString("") { it.first.toString() } else ""
        val needed: Double? = if (needStr.any { it.isDigit() }) needStr.toDoubleOrNull() else null

        return Reading(current, needed)
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

    /** 最良一致の文字と一致率 (0..1) を返す */
    private fun classify(bm: BooleanArray, table: Map<Char, BooleanArray>): Pair<Char, Double> {
        var best = '?'; var bestScore = -1
        for ((ch, glyph) in table) {
            var agree = 0
            for (i in bm.indices) if (bm[i] == glyph[i]) agree++
            if (agree > bestScore) { bestScore = agree; best = ch }
        }
        val rate = if (bm.isEmpty()) 0.0 else bestScore.toDouble() / bm.size
        return best to rate
    }
}
