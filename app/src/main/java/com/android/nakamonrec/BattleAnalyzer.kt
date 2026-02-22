package com.android.nakamonrec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
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
        // カスタムゲッターにすることで、「Condition is always false」 と 「Make it const」 の両方のワーニングを回避
        private val DEBUG: Boolean get() = false
        private const val VS_THRESHOLD = 0.7
        private const val WIN_THRESHOLD = 0.4
        private const val LOSE_THRESHOLD = 0.4
        private const val MONSTER_THRESHOLD = 0.7
        private const val PARTY_THRESHOLD = 0.45
    }

    private data class ScanResult(val config: BoxConfig, val score: Double, val scale: Double)

    fun getWinTemplate(): Mat? = winTemplate
    fun getLoseTemplate(): Mat? = loseTemplate

    fun setCalibrationData(data: CalibrationData) {
        this.calibrationData = data
        if (DEBUG) Log.i("BattleAnalyzer", "⚙️ 校正データを適用しました (uiScale: ${data.uiScale})")
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

    private fun findTemplateWithScale(
        scene: Mat,
        template: Mat?,
        useGray: Boolean,
        topLimit: Float,
        bottomLimit: Float
    ): ScanResult? {
        if (template == null) return null

        val workScene = Mat()
        if (useGray) Imgproc.cvtColor(scene, workScene, Imgproc.COLOR_RGBA2GRAY)
        else scene.copyTo(workScene)

        val startY = (workScene.rows() * topLimit).toInt().coerceIn(0, workScene.rows() - 1)
        val endY = (workScene.rows() * bottomLimit).toInt().coerceIn(startY + 1, workScene.rows())
        val roiScene = workScene.submat(startY, endY, 0, workScene.cols())

        var bestScore = -1.0
        var bestPos = Point()
        var bestScale = 1.0

        val scales = listOf(0.5, 0.7, 0.9, 1.0, 1.1, 1.3, 1.5, 1.7, 1.9, 2.1, 2.3, 2.5)
        for (s in scales) {
            val workTpl = Mat()
            Imgproc.resize(template, workTpl, Size(), s, s)
            if (useGray) Imgproc.cvtColor(workTpl, workTpl, Imgproc.COLOR_RGB2GRAY)

            if (workTpl.cols() < roiScene.cols() && workTpl.rows() < roiScene.rows()) {
                val result = Mat()
                Imgproc.matchTemplate(roiScene, workTpl, result, Imgproc.TM_CCOEFF_NORMED)
                val mm = Core.minMaxLoc(result)
                if (mm.maxVal > bestScore) {
                    bestScore = mm.maxVal
                    bestPos = mm.maxLoc
                    bestScale = s
                }
                result.release()
            }
            workTpl.release()
        }

        val res = if (bestScore > 0.4) {
            val tw = (template.cols() * bestScale).toInt()
            val th = (template.rows() * bestScale).toInt()
            val config = BoxConfig(
                (bestPos.x + tw / 2).toFloat() / scene.cols(),
                (bestPos.y + startY + th / 2).toFloat() / scene.rows(),
                tw,
                th
            )
            ScanResult(config, bestScore, bestScale)
        } else null

        roiScene.release()
        workScene.release()
        return res
    }

    fun findTemplateGlobal(sceneBitmap: Bitmap, template: Mat?, useGray: Boolean = false, topLimit: Float = 0.0f, bottomLimit: Float = 1.0f): Pair<BoxConfig, Double>? {
        val scene = Mat()
        Utils.bitmapToMat(sceneBitmap, scene)
        Imgproc.cvtColor(scene, scene, Imgproc.COLOR_RGBA2RGB)
        val res = findTemplateWithScale(scene, template, useGray, topLimit, bottomLimit)
        scene.release()
        return res?.let { it.config to it.score }
    }

    fun autoCalibrateBattleScene(sceneBitmap: Bitmap): CalibrationData? {
        val fullMat = Mat()
        Utils.bitmapToMat(sceneBitmap, fullMat)
        Imgproc.cvtColor(fullMat, fullMat, Imgproc.COLOR_RGBA2RGB)

        val vsRes = findTemplateWithScale(fullMat, vsTemplate, false, 0.3f, 0.7f) ?: return null
        val vsScale = vsRes.scale
        val vsBox = vsRes.config

        val newData = CalibrationData()
        newData.vsBox = vsBox
        newData.uiScale = vsScale.toFloat()

        val vsCx = vsBox.centerX * fullMat.cols()
        val vsCy = vsBox.centerY * fullMat.rows()

        fun getMonsterConfig(refX: Float, refY: Float): BoxConfig {
            val dx = (refX - 540f) * vsScale
            val dy = (refY - 1260f) * vsScale
            val estCx = (vsCx + dx).toFloat()
            val estCy = (vsCy + dy).toFloat()
            return findBestMonsterStrict(fullMat, estCx / fullMat.cols(), estCy / fullMat.rows(), vsScale)
                ?: BoxConfig(estCx / fullMat.cols(), estCy / fullMat.rows(), (80 * vsScale).toInt(), (130 * vsScale).toInt())
        }

        newData.myPartyBoxes = listOf(
            getMonsterConfig(196f, 1635f), getMonsterConfig(391f, 1635f),
            getMonsterConfig(585f, 1635f), getMonsterConfig(780f, 1635f)
        )
        newData.enemyPartyBoxes = listOf(
            getMonsterConfig(201f, 915f), getMonsterConfig(396f, 915f),
            getMonsterConfig(590f, 915f), getMonsterConfig(785f, 915f)
        )

        fullMat.release()
        return newData
    }

    private fun findBestMonsterStrict(scene: Mat, estCX: Float, estCY: Float, baseScale: Double): BoxConfig? {
        val searchW = (scene.cols() * 0.15).toInt()
        val searchH = (scene.rows() * 0.15).toInt()
        val startX = (scene.cols() * estCX - searchW / 2).toInt().coerceIn(0, scene.cols() - searchW)
        val startY = (scene.rows() * estCY - searchH / 2).toInt().coerceIn(0, scene.rows() - searchH)
        val roi = scene.submat(startY, startY + searchH, startX, startX + searchW)
        
        var bestScore = -1.0
        var bestPos = Point()
        var bestSize = Size(80.0 * baseScale, 130.0 * baseScale)
        val localScales = listOf(baseScale * 0.9, baseScale * 1.0, baseScale * 1.1)

        for (m in monsterMaster) {
            val tpl = m.templateMat ?: continue
            for (ls in localScales) {
                val scaledTpl = Mat()
                Imgproc.resize(tpl, scaledTpl, Size(), ls, ls)
                if (scaledTpl.cols() < roi.cols() && scaledTpl.rows() < roi.rows()) {
                    val result = Mat()
                    Imgproc.matchTemplate(roi, scaledTpl, result, Imgproc.TM_CCOEFF_NORMED)
                    val mm = Core.minMaxLoc(result)
                    if (mm.maxVal > bestScore) {
                        bestScore = mm.maxVal
                        bestPos = mm.maxLoc
                        bestSize = Size(scaledTpl.cols().toDouble(), scaledTpl.rows().toDouble())
                    }
                    result.release()
                }
                scaledTpl.release()
            }
        }

        val config = if (bestScore > 0.55) {
            BoxConfig(
                (startX + bestPos.x + bestSize.width / 2).toFloat() / scene.cols(),
                (startY + bestPos.y + bestSize.height / 2).toFloat() / scene.rows(),
                bestSize.width.toInt(),
                bestSize.height.toInt()
            )
        } else null

        roi.release()
        return config
    }

    fun autoCalibrateParty(sceneBitmap: Bitmap): Pair<List<BoxConfig>, Float>? {
        val template = partySelectTemplate ?: return null
        val fullMat = Mat()
        Utils.bitmapToMat(sceneBitmap, fullMat)
        val grayScene = Mat()
        Imgproc.cvtColor(fullMat, grayScene, Imgproc.COLOR_RGBA2GRAY)
        val grayTemplate = Mat()
        Imgproc.cvtColor(template, grayTemplate, Imgproc.COLOR_RGB2GRAY)
        var bestOverallScore = -1.0
        var bestConfigs: List<BoxConfig>? = null
        var bestScale = 1.0f

        val scales = listOf(0.7, 1.0, 1.3, 1.6, 1.9, 2.2, 2.5)
        for (s in scales) {
            val scaledTpl = Mat()
            Imgproc.resize(grayTemplate, scaledTpl, Size(), s, s)
            val result = Mat()
            Imgproc.matchTemplate(grayScene, scaledTpl, result, Imgproc.TM_CCOEFF_NORMED)
            val currentConfigs = mutableListOf<BoxConfig>()
            var sumScore = 0.0
            repeat(3) {
                val mm = Core.minMaxLoc(result)
                if (mm.maxVal < 0.25) return@repeat
                sumScore += mm.maxVal
                val pos = mm.maxLoc
                currentConfigs.add(BoxConfig(
                    (pos.x + scaledTpl.cols() / 2).toFloat() / grayScene.cols(),
                    (pos.y + scaledTpl.rows() / 2).toFloat() / grayScene.rows(),
                    scaledTpl.cols(),
                    scaledTpl.rows()
                ))
                val mask = result.submat((pos.y - scaledTpl.rows()).toInt().coerceAtLeast(0), (pos.y + scaledTpl.rows() * 2).toInt().coerceAtMost(result.rows()), (pos.x - scaledTpl.cols()).toInt().coerceAtLeast(0), (pos.x + scaledTpl.cols() * 2).toInt().coerceAtMost(result.cols()))
                mask.setTo(Scalar(-1.0))
                mask.release()
            }
            if (currentConfigs.size == 3 && sumScore > bestOverallScore) {
                bestOverallScore = sumScore
                bestConfigs = currentConfigs.sortedBy { it.centerY }
                bestScale = s.toFloat()
            }
            result.release()
            scaledTpl.release()
        }
        grayScene.release()
        fullMat.release()
        grayTemplate.release()
        val finalConfigs = bestConfigs ?: return null
        return finalConfigs to bestScale
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
            val roiMat = getRawROI(fullMat, config, imgW, imgH, "monster_$i") ?: continue
            val result = findBestMonsterMatch(roiMat)
            if (result.score > MONSTER_THRESHOLD) {
                identifiedNames[i] = result.name
                if (DEBUG) {
                    val scoreLog = String.format(Locale.US, "%.3f", result.score)
                    Log.i("BattleAnalyzer", "🎉 Slot[$i] ${result.name} 確定！ (Score: $scoreLog)")
                }
            }
            roiMat.release()
        }
        fullMat.release()
    }

    private fun getRawROI(fullMat: Mat, config: BoxConfig, imgW: Float, imgH: Float, label: String): Mat? {
        val centerX = (imgW * config.centerX).toInt()
        val centerY = (imgH * config.centerY).toInt()
        val tw = config.width
        val th = config.height
        val left = (centerX - tw / 2).coerceIn(0, (imgW.toInt() - tw))
        val top = (centerY - th / 2).coerceIn(0, (imgH.toInt() - th))

        if (DEBUG) Log.d("BattleAnalyzer", "ROI[$label]: Pos=($left, $top), Size=${tw}x${th}")

        return try {
            val safeW = if (left + tw > fullMat.cols()) fullMat.cols() - left else tw
            val safeH = if (top + th > fullMat.rows()) fullMat.rows() - top else th
            if (safeW <= 0 || safeH <= 0) return null
            fullMat.submat(top, top + safeH, left, left + safeW).clone()
        } catch (e: Exception) { 
            if (DEBUG) Log.e("BattleAnalyzer", "ROI Error: ${e.message}")
            null 
        }
    }

    private fun findBestMonsterMatch(roiMat: Mat): MatchResult {
        var bestScore = -1.0
        var bestName = ""
        val s = calibrationData.uiScale.toDouble()
        
        for (monster in monsterMaster) {
            val template = monster.templateMat ?: continue
            val scaledTpl = Mat()
            Imgproc.resize(template, scaledTpl, Size(), s, s)
            
            if (scaledTpl.cols() <= roiMat.cols() && scaledTpl.rows() <= roiMat.rows()) {
                val result = Mat()
                Imgproc.matchTemplate(roiMat, scaledTpl, result, Imgproc.TM_CCOEFF_NORMED)
                val score = Core.minMaxLoc(result).maxVal
                if (score > bestScore) {
                    bestScore = score
                    bestName = monster.name
                }
                result.release()
            }
            scaledTpl.release()
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
        val winScore = performColorMatch(bitmap, calibrationData.winBox, winTemplate, "win")
        if (winScore > WIN_THRESHOLD) {
            Log.i("BattleAnalyzer", "🏁 WIN detected! (Score: ${String.format(Locale.US, "%.3f", winScore)})")
            return "WIN"
        }
        
        val loseScore = performColorMatch(bitmap, calibrationData.loseBox, loseTemplate, "lose")
        if (loseScore > LOSE_THRESHOLD) {
            Log.i("BattleAnalyzer", "🏁 LOSE detected! (Score: ${String.format(Locale.US, "%.3f", loseScore)})")
            return "LOSE"
        }
        
        if (DEBUG && (winScore > 0.3 || loseScore > 0.3)) {
            Log.d("BattleAnalyzer", "CheckResult: Win=${String.format(Locale.US, "%.3f", winScore)}, Lose=${String.format(Locale.US, "%.3f", loseScore)}")
        }
        return null
    }

    private fun performColorMatch(bitmap: Bitmap, config: BoxConfig, template: Mat?, debugLabel: String? = null): Double {
        if (template == null) return 0.0
        val fullMat = Mat()
        Utils.bitmapToMat(bitmap, fullMat)
        Imgproc.cvtColor(fullMat, fullMat, Imgproc.COLOR_RGBA2RGB)
        val imgW = fullMat.cols().toFloat()
        val imgH = fullMat.rows().toFloat()
        
        val roi = getRawROI(fullMat, config, imgW, imgH, debugLabel ?: "unknown")
        var score = 0.0
        
        if (roi != null) {
            if (DEBUG && debugLabel != null) saveDebugMat(roi, debugLabel)
            
            val s = calibrationData.uiScale.toDouble()
            val scaledTpl = Mat()
            Imgproc.resize(template, scaledTpl, Size(), s, s)
            
            if (scaledTpl.cols() <= roi.cols() && scaledTpl.rows() <= roi.rows()) {
                val res = Mat()
                Imgproc.matchTemplate(roi, scaledTpl, res, Imgproc.TM_CCOEFF_NORMED)
                score = Core.minMaxLoc(res).maxVal
                res.release()
            }
            scaledTpl.release()
            roi.release()
        }
        fullMat.release()
        return score
    }

    private fun saveDebugMat(mat: Mat, label: String) {
        if (!DEBUG) return
        val ctx = appContext ?: return
        try {
            val bitmap = createBitmap(mat.cols(), mat.rows())
            Utils.matToBitmap(mat, bitmap)
            val file = File(ctx.filesDir, "debug_$label.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } catch (_: Exception) {}
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
        vsTemplate?.release()
        winTemplate?.release()
        loseTemplate?.release()
        partySelectTemplate?.release()
    }
}
