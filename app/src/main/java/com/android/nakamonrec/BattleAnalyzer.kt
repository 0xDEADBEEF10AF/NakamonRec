package com.android.nakamonrec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

data class PosRatio(val x: Float, val y: Float)

class BattleAnalyzer(private val master: List<MonsterData>) {
    private val monsterMaster = master
    private val identifiedNames = arrayOfNulls<String>(8)

    private var vsTemplate: Mat? = null
    private var winTemplate: Mat? = null
    private var loseTemplate: Mat? = null
    private var partySelectTemplate: Mat? = null

    companion object {
        // VSロゴの中心座標比率
        private const val VS_X_RATIO = 540f / 1080f
        private const val VS_Y_RATIO = 1260f / 2364f
        private const val VS_W = 280
        private const val VS_H = 160

        // 勝敗ロゴの中心座標比率
        private const val RESULT_X_RATIO = 540f / 1080f
        private const val RESULT_Y_RATIO = 720f / 2364f
        private const val WIN_W = 1000
        private const val WIN_H = 500
        private const val LOSE_W = 900
        private const val LOSE_H = 300

        private const val CROP_SIZE_W = 80
        private const val CROP_SIZE_H = 130
        private const val VS_THRESHOLD = 0.7
        private const val WIN_THRESHOLD = 0.5
        private const val LOSE_THRESHOLD = 0.5
        private const val MONSTER_THRESHOLD = 0.7

        private val MY_PARTY_RATIOS = listOf(
            PosRatio(196f / 1080f, 1635f / 2364f),
            PosRatio(391f / 1080f, 1635f / 2364f),
            PosRatio(585f / 1080f, 1635f / 2364f),
            PosRatio(780f / 1080f, 1635f / 2364f)
        )
        private val ENEMY_PARTY_RATIOS = listOf(
            PosRatio(201f / 1080f, 915f / 2364f),
            PosRatio(396f / 1080f, 915f / 2364f),
            PosRatio(590f / 1080f, 915f / 2364f),
            PosRatio(785f / 1080f, 915f / 2364f)
        )

        private val PARTY_SELECT_RATIOS = listOf(
            PosRatio(30f / 1080f, 1030f / 2364f), // パーティ1
            PosRatio(30f / 1080f, 1430f / 2364f), // パーティ2
            PosRatio(30f / 1080f, 1830f / 2364f)  // パーティ3
        )
        private const val PARTY_W = 50
        private const val PARTY_H = 100
        private const val PARTY_THRESHOLD = 0.45
    }

    fun loadTemplates(context: Context) {
        monsterMaster.forEach { data ->
            try {
                val inputStream = context.assets.open("templates/${data.fileName}")
                val bitmap = BitmapFactory.decodeStream(inputStream)
                val mat = Mat()
                Utils.bitmapToMat(bitmap, mat)
                //Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)
                data.templateMat = mat
                Log.i("BattleAnalyzer", "✅ ロード完了: ${data.name}")
            } catch (e: Exception) {
                Log.e("BattleAnalyzer", "❌ ロード失敗: ${data.fileName}")
            }
        }

        // ロゴのロード (assets内のtemplatesフォルダを想定)
        vsTemplate = loadColorTemplate(context, "templates/VS.png")
        winTemplate = loadColorTemplate(context, "templates/WIN.png")
        //loseTemplate = loadGrayTemplate(context, "templates/LOSE.png")
        loseTemplate = loadColorTemplate(context, "templates/LOSE.png")
        partySelectTemplate = loadColorTemplate(context, "templates/SELECT.png")
        //partySelectTemplate = loadGrayTemplate(context, "templates/SELECT.png")
    }

    private fun loadGrayTemplate(context: Context, path: String): Mat? {
        return try {
            val stream = context.assets.open(path)
            val bitmap = BitmapFactory.decodeStream(stream)
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
            mat
        } catch (e: Exception) {
            Log.e("BattleAnalyzer", "Template load failed: $path")
            null
        }
    }

    private fun loadColorTemplate(context: Context, path: String): Mat? {
        return try {
            val stream = context.assets.open(path)
            val bitmap = BitmapFactory.decodeStream(stream)
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            val rgbMat = Mat()
            Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_RGBA2RGB)
            mat.release()
            rgbMat
        } catch (e: Exception) {
            Log.e("BattleAnalyzer", "Template load failed: $path")
            null
        }
    }

    fun resetIdentification() {
        identifiedNames.fill(null)
    }

    fun isAllIdentified(): Boolean = identifiedNames.all { it != null }

    fun identifyStepByStep(bitmap: Bitmap) {
        val fullMat = Mat()
        Utils.bitmapToMat(bitmap, fullMat)

        // 画像のサイズを取得しておく
        val imgW = fullMat.cols().toFloat()
        val imgH = fullMat.rows().toFloat()

        //Imgproc.cvtColor(fullMat, fullMat, Imgproc.COLOR_BGR2GRAY)
        Imgproc.cvtColor(fullMat, fullMat, Imgproc.COLOR_RGBA2RGB)

        for (i in 0..7) {
            if (identifiedNames[i] != null) continue
            val roiMat = getSlotROI(fullMat, i, imgW, imgH) ?: continue
            val result = findBestMatch(roiMat)

            if (result.score > MONSTER_THRESHOLD) {
                identifiedNames[i] = result.name
                Log.i("BattleAnalyzer", "🎉 Slot[$i] ${result.name} 確定！ (Score: ${String.format("%.3f", result.score)})")
            }
            roiMat.release()
        }
        fullMat.release()
    }

    private fun getSlotROI(fullMat: Mat, index: Int, imgW: Float, imgH: Float): Mat? {
        val ratio = if (index < 4) MY_PARTY_RATIOS[index] else ENEMY_PARTY_RATIOS[index - 4]
        val centerX = (imgW * ratio.x).toInt()
        val centerY = (imgH * ratio.y).toInt()

        val left = (centerX - CROP_SIZE_W / 2).coerceIn(0, (imgW.toInt() - CROP_SIZE_W))
        val top = (centerY - CROP_SIZE_H / 2).coerceIn(0, (imgH.toInt() - CROP_SIZE_H))

        return try {
            fullMat.submat(top, top + CROP_SIZE_H, left, left + CROP_SIZE_W)
        } catch (e: Exception) {
            null
        }
    }

    private fun findBestMatch(roiMat: Mat): MatchResult {
        var bestScore = -1.0
        var bestName = ""

        for (monster in monsterMaster) {
            val template = monster.templateMat ?: continue
            val result = Mat()
            Imgproc.matchTemplate(roiMat, template, result, Imgproc.TM_CCOEFF_NORMED)
            val score = Core.minMaxLoc(result).maxVal

            if (score > bestScore) {
                bestScore = score
                bestName = monster.name
            }
            result.release()
        }
        return MatchResult(bestName, bestScore)
    }

    data class MatchResult(val name: String, val score: Double)

    fun getCurrentResults(): Pair<List<String>, List<String>> {
        val my = identifiedNames.slice(0..3).map { it ?: "?" }
        val enemy = identifiedNames.slice(4..7).map { it ?: "?" }
        return Pair(my, enemy)
    }

    fun isVsDetected(bitmap: Bitmap): Boolean {
        val score = performColorMatch(bitmap, VS_X_RATIO, VS_Y_RATIO, VS_W, VS_H, vsTemplate)
        //if (score > 0.1) {
        //    Log.d("BattleAnalyzer", "🔍 VS Matching Score: ${String.format("%.3f", score)}")
        //}
        return score > VS_THRESHOLD
    }

    fun checkBattleResult(bitmap: Bitmap): String? {
        val winScore = performColorMatch(bitmap, RESULT_X_RATIO, RESULT_Y_RATIO, WIN_W, WIN_H, winTemplate)
        //if (winScore > 0.1) {
        //    Log.d("BattleAnalyzer", "🔍 WIN Matching Score: ${String.format("%.3f", winScore)}")
        //}
        if (winScore > WIN_THRESHOLD) return "WIN"

        val loseScore = performColorMatch(bitmap, RESULT_X_RATIO, RESULT_Y_RATIO, LOSE_W, LOSE_H, loseTemplate)
        //if (loseScore > 0.1) {
        //   Log.d("BattleAnalyzer", "🔍 LOSE Matching Score: ${String.format("%.3f", loseScore)}")
        //}
        if (loseScore > LOSE_THRESHOLD) return "LOSE"

        return null
    }

    private fun performGrayMatch(bitmap: Bitmap, rx: Float, ry: Float, tw: Int, th: Int, template: Mat?): Double {
        if (template == null) return 0.0

        val fullMat = Mat()
        Utils.bitmapToMat(bitmap, fullMat)

        val grayMat = Mat()
        Imgproc.cvtColor(fullMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

        val imgW = grayMat.cols()
        val imgH = grayMat.rows()

        val left = (imgW * rx - tw / 2).toInt().coerceIn(0, imgW - tw)
        val top = (imgH * ry - th / 2).toInt().coerceIn(0, imgH - th)

        val roi = grayMat.submat(top, top + th, left, left + tw)
        val res = Mat()

        Imgproc.matchTemplate(roi, template, res, Imgproc.TM_CCOEFF_NORMED)
        val score = Core.minMaxLoc(res).maxVal

        roi.release(); res.release(); grayMat.release(); fullMat.release()
        return score
    }

    private fun performColorMatch(bitmap: Bitmap, rx: Float, ry: Float, tw: Int, th: Int, template: Mat?): Double {
        if (template == null) return 0.0

        val fullMat = Mat()
        Utils.bitmapToMat(bitmap, fullMat)

        val rgbMat = Mat()
        Imgproc.cvtColor(fullMat, rgbMat, Imgproc.COLOR_RGBA2RGB)

        val imgW = rgbMat.cols()
        val imgH = rgbMat.rows()

        val left = (imgW * rx - tw / 2).toInt().coerceIn(0, imgW - tw)
        val top = (imgH * ry - th / 2).toInt().coerceIn(0, imgH - th)

        val roi = rgbMat.submat(top, top + th, left, left + tw)
        val res = Mat()

        Imgproc.matchTemplate(roi, template, res, Imgproc.TM_CCOEFF_NORMED)
        val score = Core.minMaxLoc(res).maxVal

        roi.release(); res.release(); rgbMat.release(); fullMat.release()

        return score
    }

    fun detectSelectedParty(bitmap: Bitmap): Int {
        val scores = mutableListOf<Double>()
        for (i in PARTY_SELECT_RATIOS.indices) {
            val score = performColorMatch(
                bitmap,
                PARTY_SELECT_RATIOS[i].x,
                PARTY_SELECT_RATIOS[i].y,
                PARTY_W,
                PARTY_H,
                partySelectTemplate
            )
            scores.add(score)
        }

        val maxScore = scores.maxOrNull() ?: 0.0
        val maxIndex = scores.indexOf(maxScore)

        // 2位のスコアを取得
        val secondMax = scores.filterIndexed { index, _ -> index != maxIndex }.maxOrNull() ?: 0.0

        // デバッグログ: 相対値の差を確認
        //Log.d("BattleAnalyzer", "Scores: P1=${String.format("%.2f", scores[0])}, P2=${String.format("%.2f", scores[1])}, P3=${String.format("%.2f", scores[2])}")

        // 判定条件:
        // 1. 1位が最低限の閾値(0.45)を超えている
        // 2. 1位が2位よりも「明らかに高い」(例: 0.05〜0.1以上の差)
        return if (maxScore >= PARTY_THRESHOLD) {
            maxIndex
        } else {
            -1 // 確信が持てない(遷移中など)は更新しない
        }
    }

    fun releaseTemplates() {
        monsterMaster.forEach { it.templateMat?.release() }
        vsTemplate?.release()
        winTemplate?.release()
        loseTemplate?.release()
        partySelectTemplate?.release()
    }
}