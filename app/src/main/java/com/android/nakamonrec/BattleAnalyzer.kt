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
    
    // マイクロスケール対応のキャッシュ構造: Map<MonsterName, List<ScaledMat>>
    private val scaledMonsterTemplates = mutableMapOf<String, List<Mat>>()
    private var vsTemplateScaled: Mat? = null
    private var winTemplateScaled: Mat? = null
    private var loseTemplateScaled: Mat? = null
    private var partySelectTemplateScaled: Mat? = null
    private var cachedScale = -1.0

    companion object {
        // カスタムゲッターでワーニングを回避しつつデバッグ機能をオフにする
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
        prepareScaledTemplates()
    }

    private fun prepareScaledTemplates() {
        val s = calibrationData.uiScale.toDouble()
        if (s == cachedScale) return
        
        if (DEBUG) Log.i("BattleAnalyzer", "🔄 高品質テンプレートキャッシュを作成中... (Base Scale: $s)")
        val startTime = System.currentTimeMillis()

        // 全リソースの解放
        scaledMonsterTemplates.values.forEach { list -> list.forEach { it.release() } }
        scaledMonsterTemplates.clear()
        
        // マイクロスケール探索範囲 (98%, 100%, 102%)
        val microScales = listOf(s * 0.98, s * 1.0, s * 1.02)
        
        monsterMaster.forEach { data ->
            data.templateMat?.let { tpl ->
                val variants = microScales.map { ms ->
                    val scaled = Mat()
                    // INTER_CUBIC を使用して拡大時のエッジ鮮明度を維持
                    Imgproc.resize(tpl, scaled, Size(), ms, ms, Imgproc.INTER_CUBIC)
                    scaled
                }
                scaledMonsterTemplates[data.name] = variants
            }
        }

        // 基本UIテンプレートのリサイズ
        vsTemplateScaled?.release(); vsTemplateScaled = vsTemplate?.let { Mat().apply { Imgproc.resize(it, this, Size(), s, s, Imgproc.INTER_CUBIC) } }
        winTemplateScaled?.release(); winTemplateScaled = winTemplate?.let { Mat().apply { Imgproc.resize(it, this, Size(), s, s, Imgproc.INTER_CUBIC) } }
        loseTemplateScaled?.release(); loseTemplateScaled = loseTemplate?.let { Mat().apply { Imgproc.resize(it, this, Size(), s, s, Imgproc.INTER_CUBIC) } }
        partySelectTemplateScaled?.release(); partySelectTemplateScaled = partySelectTemplate?.let { Mat().apply { Imgproc.resize(it, this, Size(), s, s, Imgproc.INTER_CUBIC) } }

        cachedScale = s
        if (DEBUG) Log.i("BattleAnalyzer", "✅ キャッシュ作成完了: ${System.currentTimeMillis() - startTime}ms")
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
        
        prepareScaledTemplates()
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
            Imgproc.resize(template, workTpl, Size(), s, s, Imgproc.INTER_CUBIC)
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
            val config = BoxConfig((bestPos.x + tw / 2).toFloat() / scene.cols(), (bestPos.y + startY + th / 2).toFloat() / scene.rows(), tw, th)
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

        newData.myPartyBoxes = listOf(getMonsterConfig(196f, 1635f), getMonsterConfig(391f, 1635f), getMonsterConfig(585f, 1635f), getMonsterConfig(780f, 1635f))
        newData.enemyPartyBoxes = listOf(getMonsterConfig(201f, 915f), getMonsterConfig(396f, 915f), getMonsterConfig(590f, 915f), getMonsterConfig(785f, 915f))

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
                Imgproc.resize(tpl, scaledTpl, Size(), ls, ls, Imgproc.INTER_CUBIC)
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
            BoxConfig((startX + bestPos.x + bestSize.width / 2).toFloat() / scene.cols(), (startY + bestPos.y + bestSize.height / 2).toFloat() / scene.rows(), bestSize.width.toInt(), bestSize.height.toInt())
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
            Imgproc.resize(grayTemplate, scaledTpl, Size(), s, s, Imgproc.INTER_CUBIC)
            val result = Mat()
            Imgproc.matchTemplate(grayScene, scaledTpl, result, Imgproc.TM_CCOEFF_NORMED)
            val currentConfigs = mutableListOf<BoxConfig>()
            var sumScore = 0.0
            repeat(3) {
                val mm = Core.minMaxLoc(result)
                if (mm.maxVal < 0.25) return@repeat
                sumScore += mm.maxVal
                val pos = mm.maxLoc
                currentConfigs.add(BoxConfig((pos.x + scaledTpl.cols() / 2).toFloat() / grayScene.cols(), (pos.y + scaledTpl.rows() / 2).toFloat() / grayScene.rows(), scaledTpl.cols(), scaledTpl.rows()))
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
            val expandedConfig = BoxConfig(config.centerX, config.centerY, config.width + 10, config.height + 10)
            
            val left = ((imgW * expandedConfig.centerX) - (expandedConfig.width / 2)).toInt().coerceIn(0, imgW.toInt() - expandedConfig.width)
            val top = ((imgH * expandedConfig.centerY) - (expandedConfig.height / 2)).toInt().coerceIn(0, imgH.toInt() - expandedConfig.height)
            
            try {
                val roi = fullMat.submat(top, top + expandedConfig.height, left, left + expandedConfig.width)
                if (DEBUG) saveDebugMat(roi, "monster_$i")
                
                val result = findBestMonsterMatchMicroScales(roi)
                if (result.score > MONSTER_THRESHOLD) {
                    identifiedNames[i] = result.name
                    if (DEBUG) Log.i("BattleAnalyzer", "🎉 Slot[$i] ${result.name} 確定！ (Score: ${String.format(Locale.US, "%.3f", result.score)})")
                } else if (DEBUG && result.score > 0.5) {
                    Log.d("BattleAnalyzer", "接近 Slot[$i] ${result.name} (Score: ${String.format(Locale.US, "%.3f", result.score)})")
                }
                roi.release()
            } catch (_: Exception) {}
        }
        fullMat.release()
    }

    private fun findBestMonsterMatchMicroScales(roiMat: Mat): MatchResult {
        var bestScore = -1.0
        var bestName = ""
        
        for (monster in monsterMaster) {
            val variants = scaledMonsterTemplates[monster.name] ?: continue
            for (scaledTpl in variants) {
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
            }
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
        return performColorMatchCached(bitmap, calibrationData.vsBox, vsTemplateScaled, "vs") > VS_THRESHOLD
    }

    fun checkBattleResult(bitmap: Bitmap): String? {
        val winScore = performColorMatchCached(bitmap, calibrationData.winBox, winTemplateScaled, "win")
        if (winScore > WIN_THRESHOLD) {
            Log.i("BattleAnalyzer", "🏁 WIN detected! (Score: ${String.format(Locale.US, "%.3f", winScore)})")
            return "WIN"
        }
        val loseScore = performColorMatchCached(bitmap, calibrationData.loseBox, loseTemplateScaled, "lose")
        if (loseScore > LOSE_THRESHOLD) {
            Log.i("BattleAnalyzer", "🏁 LOSE detected! (Score: ${String.format(Locale.US, "%.3f", loseScore)})")
            return "LOSE"
        }
        return null
    }

    private fun performColorMatchCached(bitmap: Bitmap, config: BoxConfig, scaledTemplate: Mat?, debugLabel: String? = null): Double {
        if (scaledTemplate == null) return 0.0
        val fullMat = Mat()
        Utils.bitmapToMat(bitmap, fullMat)
        Imgproc.cvtColor(fullMat, fullMat, Imgproc.COLOR_RGBA2RGB)
        val imgW = fullMat.cols().toFloat()
        val imgH = fullMat.rows().toFloat()
        val centerX = (imgW * config.centerX).toInt()
        val centerY = (imgH * config.centerY).toInt()
        val left = (centerX - config.width / 2).coerceIn(0, imgW.toInt() - config.width)
        val top = (centerY - config.height / 2).coerceIn(0, imgH.toInt() - config.height)
        
        var score = 0.0
        try {
            val roi = fullMat.submat(top, top + config.height, left, left + config.width)
            if (DEBUG && debugLabel != null) saveDebugMat(roi, debugLabel)
            if (scaledTemplate.cols() <= roi.cols() && scaledTemplate.rows() <= roi.rows()) {
                val res = Mat()
                Imgproc.matchTemplate(roi, scaledTemplate, res, Imgproc.TM_CCOEFF_NORMED)
                score = Core.minMaxLoc(res).maxVal
                res.release()
            }
            roi.release()
        } catch (_: Exception) {}
        fullMat.release()
        return score
    }

    private fun saveDebugMat(mat: Mat, label: String) {
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
            val config = calibrationData.partySelectBoxes[i]
            val expandedConfig = BoxConfig(config.centerX, config.centerY, config.width, config.height + 200)
            scores.add(performColorMatchCached(bitmap, expandedConfig, partySelectTemplateScaled, "party$i"))
        }
        val maxScore = scores.maxOrNull() ?: 0.0
        val maxIndex = scores.indexOf(maxScore)
        return if (maxScore >= PARTY_THRESHOLD) maxIndex else -1
    }

    fun releaseTemplates() {
        monsterMaster.forEach { it.templateMat?.release() }
        vsTemplate?.release(); winTemplate?.release(); loseTemplate?.release(); partySelectTemplate?.release()
        scaledMonsterTemplates.values.forEach { list -> list.forEach { it.release() } }
        vsTemplateScaled?.release(); winTemplateScaled?.release(); loseTemplateScaled?.release(); partySelectTemplateScaled?.release()
    }
}
