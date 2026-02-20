package com.android.nakamonrec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.Locale

data class PosRatio(val x: Float, val y: Float)

class BattleAnalyzer(private val monsterMaster: List<MonsterData>) {
    private val identifiedNames = arrayOfNulls<String>(8)

    private var vsTemplate: Mat? = null
    private var winTemplate: Mat? = null
    private var loseTemplate: Mat? = null
    private var partySelectTemplate: Mat? = null

    companion object {
        // 基準とする横幅
        private const val REFERENCE_WIDTH = 1080f

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
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)
                data.templateMat = mat
                Log.i("BattleAnalyzer", "✅ ロード完了: ${data.name}")
            } catch (_: Exception) {
                Log.e("BattleAnalyzer", "❌ ロード失敗: ${data.fileName}")
            }
        }

        vsTemplate = loadColorTemplate(context, "templates/VS.png")
        winTemplate = loadColorTemplate(context, "templates/WIN.png")
        loseTemplate = loadColorTemplate(context, "templates/LOSE.png")
        partySelectTemplate = loadColorTemplate(context, "templates/SELECT.png")
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
        } catch (_: Exception) {
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

        val imgW = fullMat.cols().toFloat()
        val imgH = fullMat.rows().toFloat()

        Imgproc.cvtColor(fullMat, fullMat, Imgproc.COLOR_RGBA2RGB)

        for (i in 0..7) {
            if (identifiedNames[i] != null) continue
            val roiMat = getSlotROI(fullMat, i, imgW, imgH) ?: continue
            val result = findBestMatch(roiMat)

            if (result.score > MONSTER_THRESHOLD) {
                identifiedNames[i] = result.name
                Log.i("BattleAnalyzer", "🎉 Slot[$i] ${result.name} 確定！ (Score: ${String.format(Locale.US, "%.3f", result.score)})")
            }
            roiMat.release()
        }
        fullMat.release()
    }

    private fun getSlotROI(fullMat: Mat, index: Int, imgW: Float, imgH: Float): Mat? {
        val scale = imgW / REFERENCE_WIDTH
        val ratio = if (index < 4) MY_PARTY_RATIOS[index] else ENEMY_PARTY_RATIOS[index - 4]
        val centerX = (imgW * ratio.x).toInt()
        val centerY = (imgH * ratio.y).toInt()

        // 実際の画面サイズに合わせた切り出しサイズ
        val actualW = (CROP_SIZE_W * scale).toInt()
        val actualH = (CROP_SIZE_H * scale).toInt()

        val left = (centerX - actualW / 2).coerceIn(0, (imgW.toInt() - actualW))
        val top = (centerY - actualH / 2).coerceIn(0, (imgH.toInt() - actualH))

        return try {
            val roi = fullMat.submat(top, top + actualH, left, left + actualW)
            val resizedRoi = Mat()
            // テンプレートのサイズ(基準サイズ)にリサイズして戻す
            Imgproc.resize(roi, resizedRoi, Size(CROP_SIZE_W.toDouble(), CROP_SIZE_H.toDouble()))
            roi.release()
            resizedRoi
        } catch (_: Exception) {
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
        return score > VS_THRESHOLD
    }

    fun checkBattleResult(bitmap: Bitmap): String? {
        val winScore = performColorMatch(bitmap, RESULT_X_RATIO, RESULT_Y_RATIO, WIN_W, WIN_H, winTemplate)
        if (winScore > WIN_THRESHOLD) return "WIN"

        val loseScore = performColorMatch(bitmap, RESULT_X_RATIO, RESULT_Y_RATIO, LOSE_W, LOSE_H, loseTemplate)
        if (loseScore > LOSE_THRESHOLD) return "LOSE"

        return null
    }

    private fun performColorMatch(bitmap: Bitmap, rx: Float, ry: Float, tw: Int, th: Int, template: Mat?): Double {
        if (template == null) return 0.0

        val fullMat = Mat()
        Utils.bitmapToMat(bitmap, fullMat)

        val rgbMat = Mat()
        Imgproc.cvtColor(fullMat, rgbMat, Imgproc.COLOR_RGBA2RGB)

        val imgW = rgbMat.cols()
        val imgH = rgbMat.rows()
        val scale = imgW.toFloat() / REFERENCE_WIDTH

        val centerX = (imgW * rx).toInt()
        val centerY = (imgH * ry).toInt()
        
        val actualTw = (tw * scale).toInt()
        val actualTh = (th * scale).toInt()

        val left = (centerX - actualTw / 2).coerceIn(0, imgW - actualTw)
        val top = (centerY - actualTh / 2).coerceIn(0, imgH - actualTh)

        val roi = rgbMat.submat(top, top + actualTh, left, left + actualTw)
        val resizedRoi = Mat()
        // テンプレートと同じサイズにリサイズ
        Imgproc.resize(roi, resizedRoi, Size(tw.toDouble(), th.toDouble()))
        
        val res = Mat()
        Imgproc.matchTemplate(resizedRoi, template, res, Imgproc.TM_CCOEFF_NORMED)
        val score = Core.minMaxLoc(res).maxVal

        roi.release(); resizedRoi.release(); res.release(); rgbMat.release(); fullMat.release()

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

        return if (maxScore >= PARTY_THRESHOLD) {
            maxIndex
        } else {
            -1
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
