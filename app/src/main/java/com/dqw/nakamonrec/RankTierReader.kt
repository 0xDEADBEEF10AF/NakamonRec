package com.dqw.nakamonrec

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject

/**
 * グランプリの勝敗画面 (レーティングパネル表示中) のエンブレム文字帯から
 * ランク帯 (マスター3/2/1・グランドマスター) を識別する。iOS RankTierReader と同一ロジック。
 *
 * 手法 (プロトタイプで実スクショ 9 枚 9/9 + ネット素材 Ⅰ/Ⅱ/Ⅲ 検証済み):
 * - エンブレムは「現在のランク」を表す (ランクアップ戦では戦闘後の新ランク)。
 * - M帯とGMで文字サイズが異なる (高さ ~42px vs ~30px @1080) ため、アンカー語 2 種
 *   (「マスター」=M帯スケール /「グランド」=GMスケール) を assets の
 *   grandprix_rank_anchors.json (二値ビットマップ=ゲームフォント字形) として同梱し、
 *   探索帯にスライド照合 (dice 係数) して高い方を採用する。
 * - M帯はアンカー右側のローマ数字の柱数 (Ⅰ/Ⅱ/Ⅲ = 1/2/3 本) で分類。
 * - エンブレムの金縁・月桂樹 (金色: r-b 大) はインクから除外し、柱の誤カウントを防ぐ。
 * - 位置は 1080×2364 基準の探索帯を画面幅でスケール (通常パネルとランクアップ画面で
 *   ~20px ずれるため固定点ではなく帯走査)。識別できなければ null (誤記録より無記録)。
 */
object RankTierReader {

    // 1080×2364 基準ジオメトリ
    private const val BASE_WIDTH = 1080.0
    private const val BAND_X = 140; private const val BAND_Y = 1440
    private const val BAND_W = 380; private const val BAND_H = 80
    private const val MASTER_THR = 140; private const val GRAND_THR = 150
    private const val SCORE_THRESHOLD = 0.72  // 実測: 正解 ≥0.99 / 他帯誤マッチ ≤0.64
    // ローマ数字柱カウント (アンカー右側窓、1080 基準)
    private const val BAR_WIN_GAP = 6; private const val BAR_WIN_W = 74; private const val BAR_WIN_H = 46
    private const val BAR_MIN_INK_H = 21       // インク高 >= 50% of 文字高42px
    private const val BAR_MIN_W = 4; private const val BAR_MAX_W = 24
    private const val BAR_CLUSTER_GAP = 14     // 柱同士の最大ギャップ
    private const val BAR_FIRST_MAX_X = 40     // 先頭柱はアンカー近傍のみ

    private class Bin(val bits: BooleanArray, val w: Int, val h: Int) {
        fun at(x: Int, y: Int) = bits[y * w + x]
    }

    @Volatile private var anchors: Map<String, Bin>? = null

    /** assets からアンカーテンプレートをロード (初回のみ) */
    private fun ensureAnchors(context: Context) {
        if (anchors != null) return
        synchronized(this) {
            if (anchors != null) return
            val map = HashMap<String, Bin>()
            try {
                val json = context.assets.open("grandprix_rank_anchors.json")
                    .bufferedReader().use { it.readText() }
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
                android.util.Log.e("RankTierReader", "anchor load failed: ${e.message}")
            }
            anchors = map
        }
    }

    /** ランク帯を識別する。識別できなければ null。戻り値は GrandPrixRecord.rankTiers のいずれか */
    fun read(context: Context, bmp: Bitmap): String? {
        ensureAnchors(context)
        val master = anchors?.get("master") ?: return null
        val grand = anchors?.get("grand") ?: return null
        val w = bmp.width; val h = bmp.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val s = w / BASE_WIDTH

        val bandM = canonicalBand(pixels, w, h, s, MASTER_THR)
        val bandG = canonicalBand(pixels, w, h, s, GRAND_THR)
        val m = slideMatch(master, bandM)
        val g = slideMatch(grand, bandG)

        if (m.score >= SCORE_THRESHOLD && m.score >= g.score) {
            // アンカー右端の柱カウント窓 (canonical 座標)
            val winX = m.x + master.w + BAR_WIN_GAP
            val winY = maxOf(0, m.y - 2)
            return when (countBars(bandM, winX, winY, BAR_WIN_W, minOf(BAR_WIN_H, BAND_H - winY))) {
                1 -> "マスター1"
                2 -> "マスター2"
                3 -> "マスター3"
                else -> null
            }
        }
        if (g.score >= SCORE_THRESHOLD) return "グランドマスター"
        return null
    }

    /**
     * 文字インク: 明るく、かつ金色 (r-b 大: 縁・月桂樹) でない画素。
     * 文字は白/薄紫 (M帯)・青白 (GM) なので残る。
     */
    private fun isInk(pixels: IntArray, w: Int, h: Int, x: Int, y: Int, thr: Int): Boolean {
        if (x < 0 || x >= w || y < 0 || y >= h) return false
        val p = pixels[y * w + x]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        if ((r * 299 + g * 587 + b * 114) / 1000 < thr) return false
        return r - b <= 60
    }

    /** 探索帯を 1080 基準サイズで二値化 (非1080幅は nearest neighbor で吸収) */
    private fun canonicalBand(pixels: IntArray, w: Int, h: Int, scale: Double, thr: Int): Bin {
        val bits = BooleanArray(BAND_W * BAND_H)
        for (cy in 0 until BAND_H) {
            val sy = ((BAND_Y + cy) * scale).toInt()
            for (cx in 0 until BAND_W) {
                val sx = ((BAND_X + cx) * scale).toInt()
                bits[cy * BAND_W + cx] = isInk(pixels, w, h, sx, sy, thr)
            }
        }
        return Bin(bits, BAND_W, BAND_H)
    }

    private class MatchResult(val score: Double, val x: Int, val y: Int)

    /** スライド照合 (dice 係数) */
    private fun slideMatch(tpl: Bin, band: Bin): MatchResult {
        var best = MatchResult(0.0, 0, 0)
        val tplInk = tpl.bits.count { it }
        if (tplInk == 0 || band.w < tpl.w || band.h < tpl.h) return best
        for (oy in 0..(band.h - tpl.h)) {
            for (ox in 0..(band.w - tpl.w)) {
                var inter = 0; var bandInk = 0
                for (ty in 0 until tpl.h) {
                    val brow = (oy + ty) * band.w + ox
                    val trow = ty * tpl.w
                    for (tx in 0 until tpl.w) {
                        if (band.bits[brow + tx]) {
                            bandInk++
                            if (tpl.bits[trow + tx]) inter++
                        }
                    }
                }
                val dice = 2.0 * inter / (tplInk + bandInk)
                if (dice > best.score) best = MatchResult(dice, ox, oy)
            }
        }
        return best
    }

    /** ローマ数字の柱カウント (Ⅰ/Ⅱ/Ⅲ = 1/2/3) */
    private fun countBars(band: Bin, x: Int, y: Int, w: Int, h: Int): Int {
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || x >= band.w || y >= band.h) return 0
        val x1 = minOf(x + w, band.w); val y1 = minOf(y + h, band.h)
        val colInk = IntArray(x1 - x)
        for (cx in x until x1) {
            for (cy in y until y1) { if (band.at(cx, cy)) colInk[cx - x]++ }
        }
        // インク高しきい値以上の列をグループ化 (1列の欠けは許容)
        val groups = ArrayList<IntArray>()
        var cx = 0
        while (cx < colInk.size) {
            if (colInk[cx] >= BAR_MIN_INK_H) {
                var end = cx; var gap = 0; var cur = cx + 1
                while (cur < colInk.size && gap <= 1) {
                    if (colInk[cur] >= BAR_MIN_INK_H) { end = cur; gap = 0 } else gap++
                    cur++
                }
                groups.add(intArrayOf(cx, end))
                cx = end + 1
            } else cx++
        }
        // 柱幅のグループのみ採用し、先頭柱から近接クラスタを数える
        val bars = groups.filter { (it[1] - it[0] + 1) in BAR_MIN_W..BAR_MAX_W }
        if (bars.isEmpty() || bars[0][0] > BAR_FIRST_MAX_X) return 0
        var count = 1
        var prevEnd = bars[0][1]
        for (i in 1 until bars.size) {
            if (bars[i][0] - prevEnd <= BAR_CLUSTER_GAP) { count++; prevEnd = bars[i][1] } else break
        }
        return count
    }
}
