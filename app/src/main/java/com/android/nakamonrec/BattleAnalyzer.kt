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
import java.io.File
import java.util.Locale
import androidx.core.graphics.createBitmap

class BattleAnalyzer(private val monsterMaster: List<MonsterData>) {
    private val identifiedNames = arrayOfNulls<String>(8)
    private var calibrationData: CalibrationData = CalibrationData()
    private var appContext: Context? = null

    private var vsTemplate: Mat? = null
    private var winTemplate: Mat? = null
    private var loseTemplate: Mat? = null
    private var partySelectTemplate: Mat? = null

    companion object {
        private const val VS_THRESHOLD = 0.7
        private const val WIN_THRESHOLD = 0.5
        private const val LOSE_THRESHOLD = 0.5
        private const val MONSTER_THRESHOLD = 0.7
        private const val PARTY_THRESHOLD = 0.45
    }

    fun setCalibrationData(data: CalibrationData) {
        this.calibrationData = data
        Log.i("BattleAnalyzer", "⚙️ 校正データを適用しました")
    }

    fun loadTemplates(context: Context) {
        this.appContext = context
        monsterMaster.forEach { data ->
            try {
                context.assets.open("templates/${data.fileName}").use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    val mat = Mat()
                    Utils.bitmapToMat(bitmap, mat)
                    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)
                    data.templateMat = mat
                }
            } catch (_: Exception) {}
        }
        vsTemplate = loadColorTemplate(context, "templates/VS.png")
        winTemplate = loadColorTemplate(context, "templates/WIN.png")
        loseTemplate = loadColorTemplate(context, "templates/LOSE.png")
        partySelectTemplate = loadColorTemplate(context, "templates/SELECT.png")
    }

    private fun loadColorTemplate(context: Context, path: String): Mat? {
        return try {
            context.assets.open(path).use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream)
                val mat = Mat()
                Utils.bitmapToMat(bitmap, mat)
                val rgbMat = Mat()
                Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_RGBA2RGB)
                mat.release()
                rgbMat
            }
        } catch (_: Exception) { null }
    }

    fun resetIdentification() { identifiedNames.fill(null) }
    fun isAllIdentified(): Boolean = identifiedNames.all { it != null }

    fun identifyStepByStep(bitmap: Bitmap) {
        val fullMat = Mat()
        Utils.bitmapToMat(bitmap, fullMat)
        val imgW = fullMat.cols().toFloat()
        val imgH = fullMat.rows().toFloat()
        Imgproc.cvtColor(fullMat, fullMat, Imgproc.COLOR_RGBA2RGB)

        for (i in 0..7) {
            if (identifiedNames[i] != null) continue
            val config = if (i < 4) calibrationData.myPartyBoxes[i] else calibrationData.enemyPartyBoxes[i - 4]
            val roiMat = getNormalizedROI(fullMat, config, imgW, imgH) ?: continue
            
            val result = findBestMatch(roiMat)
            if (result.score > MONSTER_THRESHOLD) {
                identifiedNames[i] = result.name
                val scoreLog = String.format(Locale.US, "%.3f", result.score)
                Log.i("BattleAnalyzer", "🎉 Slot[$i] ${result.name} 確定！ (Score: $scoreLog)")
            }
            roiMat.release()
        }
        fullMat.release()
    }

    private fun getNormalizedROI(fullMat: Mat, config: BoxConfig, imgW: Float, imgH: Float): Mat? {
        val centerX = (imgW * config.centerX).toInt()
        val centerY = (imgH * config.centerY).toInt()
        
        val scale = imgW / 1080f
        val actualW = (config.width * scale).toInt()
        val actualH = (config.height * scale).toInt()

        val left = (centerX - actualW / 2).coerceIn(0, (imgW.toInt() - actualW))
        val top = (centerY - actualH / 2).coerceIn(0, (imgH.toInt() - actualH))
        
        return try {
            val nativeRoi = fullMat.submat(top, top + actualH, left, left + actualW)
            val normalizedRoi = Mat()
            Imgproc.resize(nativeRoi, normalizedRoi, Size(config.width.toDouble(), config.height.toDouble()))
            nativeRoi.release()
            normalizedRoi
        } catch (_: Exception) { null }
    }

    private fun findBestMatch(roiMat: Mat): MatchResult {
        var bestScore = -1.0
        var bestName = ""
        for (monster in monsterMaster) {
            val template = monster.templateMat ?: continue
            if (template.cols() > roiMat.cols() || template.rows() > roiMat.rows()) continue

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
        return performColorMatch(bitmap, calibrationData.vsBox, vsTemplate, "vs") > VS_THRESHOLD
    }

    fun checkBattleResult(bitmap: Bitmap): String? {
        if (performColorMatch(bitmap, calibrationBoxToResultBox(calibrationData.resultBox), winTemplate, "win") > WIN_THRESHOLD) return "WIN"
        if (performColorMatch(bitmap, calibrationBoxToResultBox(calibrationData.resultBox), loseTemplate, "lose") > LOSE_THRESHOLD) return "LOSE"
        return null
    }

    private fun calibrationBoxToResultBox(box: BoxConfig): BoxConfig {
        return box
    }

    private fun performColorMatch(bitmap: Bitmap, config: BoxConfig, template: Mat?, debugLabel: String? = null): Double {
        if (template == null) return 0.0
        val fullMat = Mat()
        Utils.bitmapToMat(bitmap, fullMat)
        Imgproc.cvtColor(fullMat, fullMat, Imgproc.COLOR_RGBA2RGB)
        val imgW = fullMat.cols().toFloat()
        val imgH = fullMat.rows().toFloat()

        val roi = getNormalizedROI(fullMat, config, imgW, imgH)
        val score = if (roi != null) {
            if (debugLabel != null) saveDebugMat(roi, debugLabel)
            
            val s = if (template.cols() <= roi.cols() && template.rows() <= roi.rows()) {
                val res = Mat()
                Imgproc.matchTemplate(roi, template, res, Imgproc.TM_CCOEFF_NORMED)
                val maxVal = Core.minMaxLoc(res).maxVal
                res.release()
                maxVal
            } else 0.0
            
            roi.release()
            s
        } else 0.0

        fullMat.release()
        return score
    }

    private fun saveDebugMat(mat: Mat, label: String) {
        val ctx = appContext ?: return
        try {
            val bitmap = createBitmap(mat.cols(), mat.rows())
            Utils.matToBitmap(mat, bitmap)
            val file = File(ctx.filesDir, "debug_$label.png")
            file.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        } catch (e: Exception) {
            Log.e("BattleAnalyzer", "❌ Failed to save debug image: ${e.message}")
        }
    }

    fun detectSelectedParty(bitmap: Bitmap): Int {
        val scores = mutableListOf<Double>()
        for (i in calibrationData.partySelectBoxes.indices) {
            scores.add(performColorMatch(bitmap, calibrationData.partySelectBoxes[i], partySelectTemplate, "party$i"))
        }
        val maxScore = scores.maxOrNull() ?: 0.0
        val maxIndex = scores.indexOf(maxScore)
        
        return if (maxScore >= PARTY_THRESHOLD) maxIndex else -1
    }

    fun releaseTemplates() {
        monsterMaster.forEach { it.templateMat?.release() }
        vsTemplate?.release(); winTemplate?.release(); loseTemplate?.release(); partySelectTemplate?.release()
    }
}
