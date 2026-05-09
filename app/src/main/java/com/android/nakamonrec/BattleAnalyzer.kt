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

class BattleAnalyzer(private val monsterMaster: List<MonsterData>) {
    private val identifiedNames = arrayOfNulls<String>(8)
    private val identifiedScores = arrayOfNulls<Double>(8)
    var calibrationData: CalibrationData = CalibrationData()
        set(value) {
            field = value
            prepareScaledTemplates()
        }
    private var appContext: Context? = null

    private var vsFmTemplate: Mat? = null
    private var vsMgTemplate: Mat? = null
    private var vsCustomTemplate: Mat? = null
    private var winTemplate: Mat? = null
    private var loseTemplate: Mat? = null
    private var winCustomTemplate: Mat? = null
    private var loseCustomTemplate: Mat? = null
    private var partySelectTemplate: Mat? = null
    private var partyCustomTemplate: Mat? = null
    
    private val scaledMonsterTemplates = mutableMapOf<String, List<Mat>>()
    private var vsFmTemplateScaled: Mat? = null
    private var vsMgTemplateScaled: Mat? = null
    private var vsCustomTemplateScaled: Mat? = null
    private var winTemplateScaled: Mat? = null
    private var loseTemplateScaled: Mat? = null
    private var winCustomTemplateScaled: Mat? = null
    private var loseCustomTemplateScaled: Mat? = null
    private var partySelectTemplateScaled: Mat? = null
    private var partyCustomTemplateScaled: Mat? = null
    private var cachedScale = -1.0
    private var bestImageIndex = 0 // バースト画像の中で成功したインデックスを保持
    private var nextAnalysisSlot = 0 // ラウンドロビン用のスロット追跡
    private val monsterMatchCounts = mutableMapOf<String, Int>() // 出現頻度統計

    companion object {
        private const val VS_THRESHOLD = 0.4
        private const val WIN_THRESHOLD = 0.4
        private const val LOSE_THRESHOLD = 0.4
        private const val MONSTER_THRESHOLD = 0.7
        private const val PARTY_THRESHOLD = 0.7
        
        /**
         * ROI（探索範囲）を広げるためのパディング値（ピクセル）。
         */
        const val ROI_PAD_MONSTER = 20
        const val ROI_PAD_PARTY_H = 30  // GALAXY等の縦横比ズレを考慮
        const val ROI_PAD_PARTY_V = 100 // GALAXY等の縦方向ズレを考慮
        const val ROI_PAD_GENERAL_H = 10 // 一般的な水平マージン
    }

    data class ScanResult(val config: BoxConfig, val score: Double, val scale: Double)

    fun getWinTemplate(): Mat? = winTemplate
    fun getLoseTemplate(): Mat? = loseTemplate

    private fun prepareScaledTemplates() {
        val s = calibrationData.uiScale.toDouble()
        if (s == cachedScale) return
        
        scaledMonsterTemplates.values.forEach { list -> list.forEach { it.release() } }
        scaledMonsterTemplates.clear()
        
        val microScales = listOf(s * 0.98, s * 1.0, s * 1.02)
        
        monsterMaster.forEach { data ->
            data.templateMat?.let { tpl ->
                val variants = microScales.map { ms ->
                    val scaled = Mat()
                    Imgproc.resize(tpl, scaled, Size(), ms, ms, Imgproc.INTER_CUBIC)
                    scaled
                }
                scaledMonsterTemplates[data.name] = variants
            }
        }

        vsFmTemplateScaled?.release(); vsFmTemplateScaled = vsFmTemplate?.let { Mat().apply { Imgproc.resize(it, this, Size(), s, s, Imgproc.INTER_CUBIC) } }
        vsMgTemplateScaled?.release(); vsMgTemplateScaled = vsMgTemplate?.let { Mat().apply { Imgproc.resize(it, this, Size(), s, s, Imgproc.INTER_CUBIC) } }
        
        // カスタムテンプレートは、その端末で切り出されたものなので uiScale を適用せず 1.0 で使用する
        vsCustomTemplateScaled?.release(); vsCustomTemplateScaled = vsCustomTemplate?.let { val m = Mat(); it.copyTo(m); m }
        
        winTemplateScaled?.release(); winTemplateScaled = winTemplate?.let { Mat().apply { Imgproc.resize(it, this, Size(), s, s, Imgproc.INTER_CUBIC) } }
        loseTemplateScaled?.release(); loseTemplateScaled = loseTemplate?.let { Mat().apply { Imgproc.resize(it, this, Size(), s, s, Imgproc.INTER_CUBIC) } }
        
        // カスタムテンプレートは 1.0 固定
        winCustomTemplateScaled?.release(); winCustomTemplateScaled = winCustomTemplate?.let { val m = Mat(); it.copyTo(m); m }
        loseCustomTemplateScaled?.release(); loseCustomTemplateScaled = loseCustomTemplate?.let { val m = Mat(); it.copyTo(m); m }
        
        partySelectTemplateScaled?.release(); partySelectTemplateScaled = partySelectTemplate?.let { Mat().apply { Imgproc.resize(it, this, Size(), s, s, Imgproc.INTER_CUBIC) } }
        
        // カスタムテンプレートは 1.0 固定
        partyCustomTemplateScaled?.release(); partyCustomTemplateScaled = partyCustomTemplate?.let { val m = Mat(); it.copyTo(m); m }

        cachedScale = s
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
        vsFmTemplate = loadColorTemplate(context, "templates/VS_FM.png")
        vsMgTemplate = loadColorTemplate(context, "templates/VS_MG.png")
        winTemplate = loadColorTemplate(context, "templates/WIN.png")
        loseTemplate = loadColorTemplate(context, "templates/LOSE.png")
        partySelectTemplate = loadColorTemplate(context, "templates/SELECT.png")
        
        loadCustomTemplates(context)
        prepareScaledTemplates()
    }

    fun loadCustomTemplates(context: Context) {
        val vsFile = File(context.filesDir, "vs_custom.png")
        if (vsFile.exists()) {
            vsCustomTemplate = loadExternalTemplate(vsFile.absolutePath)
        }
        val partyFile = File(context.filesDir, "party_custom.png")
        if (partyFile.exists()) {
            partyCustomTemplate = loadExternalTemplate(partyFile.absolutePath)
        }
        val winFile = File(context.filesDir, "win_custom.png")
        if (winFile.exists()) {
            winCustomTemplate = loadExternalTemplate(winFile.absolutePath)
        }
        val loseFile = File(context.filesDir, "lose_custom.png")
        if (loseFile.exists()) {
            loseCustomTemplate = loadExternalTemplate(loseFile.absolutePath)
        }
    }

    private fun loadExternalTemplate(path: String): Mat? {
        return try {
            val bitmap = BitmapFactory.decodeFile(path) ?: return null
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            val rgbMat = Mat()
            Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_RGBA2RGB)
            mat.release()
            rgbMat
        } catch (_: Exception) { null }
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

        val startY = (workScene.rows() * topLimit).toInt().coerceAtMost(workScene.rows() - 1)
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

        // 各種テンプレートで個別に検索
        val customRes = findTemplateWithScale(fullMat, vsCustomTemplate, true, 0.3f, 0.8f)
        val fmRes = findTemplateWithScale(fullMat, vsFmTemplate, true, 0.3f, 0.8f)
        val mgRes = findTemplateWithScale(fullMat, vsMgTemplate, true, 0.3f, 0.8f)

        // 最もスコアの高い結果を位置基準として採用
        val bestRes = listOfNotNull(customRes, fmRes, mgRes).maxByOrNull { it.score }
        
        if (bestRes == null || bestRes.score < 0.4) {
            fullMat.release()
            return null
        }
        
        // 標準アセット幅を取得（1080p基準）
        val standardVsWidth = (vsFmTemplate ?: vsMgTemplate)?.cols()?.toDouble() ?: return null

        // 標準アセット（モンスター等）のためのスケールを決定
        val assetRes = listOfNotNull(fmRes, mgRes).maxByOrNull { it.score }
        val vsScaleForAssets = if (assetRes != null) {
            assetRes.scale
        } else if (customRes != null) {
            // カスタムしか見つからない場合、カスタムテンプレートの画像サイズから本来の uiScale を逆算する
            customRes.config.width.toDouble() / standardVsWidth
        } else {
            bestRes.scale
        }
        
        val vsBox = bestRes.config
        val newData = CalibrationData()
        newData.vsBox = vsBox
        newData.uiScale = vsScaleForAssets.toFloat()

        val vsCx = vsBox.centerX * fullMat.cols()
        val vsCy = vsBox.centerY * fullMat.rows()

        fun getMonsterConfig(refX: Float, refY: Float): BoxConfig {
            // 座標計算には「標準(1080p)から実機への倍率」である vsScaleForAssets を使用
            val dx = (refX - 540f) * vsScaleForAssets
            val dy = (refY - 1260f) * vsScaleForAssets
            val estCx = (vsCx + dx).toFloat()
            val estCy = (vsCy + dy).toFloat()
            return findBestMonsterStrict(fullMat, estCx / fullMat.cols(), estCy / fullMat.rows(), vsScaleForAssets)
                ?: BoxConfig(estCx / fullMat.cols(), estCy / fullMat.rows(), (80 * vsScaleForAssets).toInt(), (130 * vsScaleForAssets).toInt())
        }

        newData.myPartyBoxes = listOf(getMonsterConfig(196f, 1635f), getMonsterConfig(391f, 1635f), getMonsterConfig(585f, 1635f), getMonsterConfig(780f, 1635f))
        newData.enemyPartyBoxes = listOf(getMonsterConfig(201f, 915f), getMonsterConfig(396f, 915f), getMonsterConfig(590f, 915f), getMonsterConfig(785f, 915f))

        fullMat.release()
        return newData
    }

    private fun findBestMonsterStrict(scene: Mat, estCX: Float, estCY: Float, baseScale: Double): BoxConfig? {
        val searchW = (scene.cols() * 0.15).toInt()
        val searchH = (scene.rows() * 0.15).toInt()
        
        // 探索範囲が画像サイズを超えないように制限
        if (searchW <= 0 || searchH <= 0 || searchW > scene.cols() || searchH > scene.rows()) return null

        val startX = ((scene.cols() * estCX) - (searchW / 2)).toInt().coerceIn(0, (scene.cols() - searchW).coerceAtLeast(0))
        val startY = ((scene.rows() * estCY) - (searchH / 2)).toInt().coerceIn(0, (scene.rows() - searchH).coerceAtLeast(0))
        
        val roi = scene.submat(startY, startY + searchH, startX, startX + searchW)
        
        var bestScore = -1.0
        var bestPos = Point()
        var bestSize = Size(80.0 * baseScale, 130.0 * baseScale)
        val localScales = listOf(baseScale * 0.9, baseScale * 1.0, baseScale * 1.1)

        val monsters = monsterMaster // 校正時は全モンスターを対象
        for (m in monsters) {
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
        val standardTpl = partySelectTemplate ?: return null
        val customTpl = partyCustomTemplate
        
        val fullMat = Mat()
        Utils.bitmapToMat(sceneBitmap, fullMat)
        val grayScene = Mat()
        Imgproc.cvtColor(fullMat, grayScene, Imgproc.COLOR_RGBA2GRAY)
        
        val standardWidth = standardTpl.cols().toDouble()
        
        data class PartyCandidate(val configs: List<BoxConfig>, val score: Double, val uiScale: Double)
        var bestCandidate: PartyCandidate? = null

        // 検索対象のテンプレートリスト
        val targets = listOfNotNull(
            customTpl?.let { "custom" to it },
            "standard" to standardTpl
        )

        for ((type, tpl) in targets) {
            val grayTpl = Mat()
            Imgproc.cvtColor(tpl, grayTpl, Imgproc.COLOR_RGB2GRAY)
            
            // カスタムの場合は 1.0 固定、標準の場合はマルチスケール
            val scales = if (type == "custom") listOf(1.0) else listOf(0.7, 1.0, 1.3, 1.6, 1.9, 2.2, 2.5)
            
            for (s in scales) {
                val scaledTpl = Mat()
                Imgproc.resize(grayTpl, scaledTpl, Size(), s, s, Imgproc.INTER_CUBIC)
                
                if (scaledTpl.cols() >= grayScene.cols() || scaledTpl.rows() >= grayScene.rows()) {
                    scaledTpl.release()
                    continue
                }

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

                if (currentConfigs.size == 3) {
                    // カスタムの場合は画像サイズから uiScale を逆算、標準の場合は s をそのまま使う
                    val calculatedUiScale = if (type == "custom") scaledTpl.cols().toDouble() / standardWidth else s
                    if (bestCandidate == null || sumScore > bestCandidate!!.score) {
                        bestCandidate = PartyCandidate(currentConfigs.sortedBy { it.centerY }, sumScore, calculatedUiScale)
                    }
                }
                result.release()
                scaledTpl.release()
            }
            grayTpl.release()
        }
        
        grayScene.release()
        fullMat.release()
        
        return bestCandidate?.let { it.configs to it.uiScale.toFloat() }
    }

    fun autoCalibrateResult(sceneBitmap: Bitmap, isWin: Boolean): Pair<BoxConfig, Float>? {
        val standardTpl = (if (isWin) winTemplate else loseTemplate) ?: return null
        val customTpl = if (isWin) winCustomTemplate else loseCustomTemplate
        
        val fullMat = Mat()
        Utils.bitmapToMat(sceneBitmap, fullMat)
        Imgproc.cvtColor(fullMat, fullMat, Imgproc.COLOR_RGBA2RGB)
        
        val standardWidth = standardTpl.cols().toDouble()
        
        // カスタムと標準の両方で検索（上半分 0.0〜0.5 を重点的に）
        val customRes = findTemplateWithScale(fullMat, customTpl, false, 0.0f, 0.5f)
        val assetRes = findTemplateWithScale(fullMat, standardTpl, false, 0.0f, 0.5f)
        
        val bestRes = listOfNotNull(customRes, assetRes).maxByOrNull { it.score }
        
        if (bestRes == null || bestRes.score < 0.4) {
            fullMat.release()
            return null
        }
        
        // スケールの決定
        val finalUiScale = if (assetRes != null) {
            assetRes.scale
        } else if (customRes != null) {
            customRes.config.width.toDouble() / standardWidth
        } else {
            bestRes.scale
        }
        
        fullMat.release()
        return bestRes.config to finalUiScale.toFloat()
    }

    fun isVsDetected(bitmap: Bitmap): Boolean {
        // VS検知には画面高さの10%程度の大きな垂直マージンを持たせる (GALAXY等のナビバー対策)
        val vMargin = (bitmap.height * 0.1).toInt()
        val hMargin = (ROI_PAD_GENERAL_H * calibrationData.uiScale).toInt()
        
        var detected = false
        if (vsCustomTemplateScaled != null) {
            if (performColorMatchCached(bitmap, calibrationData.vsBox, vsCustomTemplateScaled, vMargin, hMargin) > VS_THRESHOLD) detected = true
        }
        if (!detected && performColorMatchCached(bitmap, calibrationData.vsBox, vsFmTemplateScaled, vMargin, hMargin) > VS_THRESHOLD) detected = true
        if (!detected && performColorMatchCached(bitmap, calibrationData.vsBox, vsMgTemplateScaled, vMargin, hMargin) > VS_THRESHOLD) detected = true
        
        if (detected) {
            saveRoi(bitmap, calibrationData.vsBox, "vs", vMargin, hMargin)
        }
        return detected
    }

    fun checkBattleResult(bitmap: Bitmap): String? {
        val vMargin = (bitmap.height * 0.05).toInt() // 勝敗ロゴは5%程度のマージン
        val hMargin = (ROI_PAD_GENERAL_H * calibrationData.uiScale).toInt()

        // WIN判定
        var detected: String? = null
        if (winCustomTemplateScaled != null) {
            if (performColorMatchCached(bitmap, calibrationData.winBox, winCustomTemplateScaled, vMargin, hMargin) > WIN_THRESHOLD) detected = "WIN"
        }
        if (detected == null && performColorMatchCached(bitmap, calibrationData.winBox, winTemplateScaled, vMargin, hMargin) > WIN_THRESHOLD) detected = "WIN"

        if (detected == "WIN") {
            saveRoi(bitmap, calibrationData.winBox, "result", vMargin, hMargin)
            return "WIN"
        }

        // LOSE判定
        if (loseCustomTemplateScaled != null) {
            if (performColorMatchCached(bitmap, calibrationData.loseBox, loseCustomTemplateScaled, vMargin, hMargin) > LOSE_THRESHOLD) detected = "LOSE"
        }
        if (detected == null && performColorMatchCached(bitmap, calibrationData.loseBox, loseTemplateScaled, vMargin, hMargin) > LOSE_THRESHOLD) detected = "LOSE"
        
        if (detected == "LOSE") {
            saveRoi(bitmap, calibrationData.loseBox, "result", vMargin, hMargin)
            return "LOSE"
        }
        
        return null
    }

    private fun performColorMatchCached(bitmap: Bitmap, config: BoxConfig, scaledTemplate: Mat?, verticalMargin: Int = 0, horizontalMargin: Int = 0): Double {
        if (scaledTemplate == null) return 0.0
        val fullMat = Mat()
        Utils.bitmapToMat(bitmap, fullMat)
        Imgproc.cvtColor(fullMat, fullMat, Imgproc.COLOR_RGBA2RGB)
        val imgW = fullMat.cols()
        val imgH = fullMat.rows()
        
        // テンプレートが画像より大きい場合は、テンプレート側に合わせるのではなく失敗とする
        if (scaledTemplate.cols() > imgW || scaledTemplate.rows() > imgH) {
            fullMat.release()
            return 0.0
        }

        val centerX = (imgW * config.centerX).toInt()
        val centerY = (imgH * config.centerY).toInt()
        
        // 探索範囲の設定 (テンプレートサイズ + マージン)
        // 探索範囲自体が画像サイズを超えないように制限
        val roiW = (scaledTemplate.cols() + horizontalMargin * 2).coerceAtMost(imgW)
        val roiH = (scaledTemplate.rows() + verticalMargin * 2).coerceAtMost(imgH)
        
        val left = (centerX - roiW / 2).coerceIn(0, (imgW - roiW).coerceAtLeast(0))
        val top = (centerY - roiH / 2).coerceIn(0, (imgH - roiH).coerceAtLeast(0))
        
        var score = 0.0
        try {
            // submatの終点が画像サイズを超えないことを保証
            val actualW = if (left + roiW > imgW) imgW - left else roiW
            val actualH = if (top + roiH > imgH) imgH - top else roiH
            
            if (actualW >= scaledTemplate.cols() && actualH >= scaledTemplate.rows()) {
                val roi = fullMat.submat(top, top + actualH, left, left + actualW)
                val res = Mat()
                Imgproc.matchTemplate(roi, scaledTemplate, res, Imgproc.TM_CCOEFF_NORMED)
                score = Core.minMaxLoc(res).maxVal
                res.release()
                roi.release()
            }
        } catch (_: Exception) {}
        fullMat.release()
        return score
    }

    fun resetIdentification() { 
        identifiedNames.fill(null)
        identifiedScores.fill(null)
        bestImageIndex = 0 
        nextAnalysisSlot = 0 // リセット
    }
    fun isAllIdentified(): Boolean = identifiedNames.all { it != null }

    fun identifyStepByStep(bitmap: Bitmap) {
        val monsters = monsterMaster.sortedByDescending { monsterMatchCounts.getOrDefault(it.name, 0) }
        identifySlot(bitmap, (0..7).firstOrNull { identifiedNames[it] == null } ?: return, monsters)
    }

    fun identifyNextSlot(bitmaps: List<Bitmap>): Boolean {
        if (bitmaps.isEmpty()) return false
        
        val sortedMonsters = monsterMaster.sortedByDescending { monsterMatchCounts.getOrDefault(it.name, 0) }
        
        // ラウンドロビン方式で次の未確定スロットを探す
        for (offset in 0..7) {
            val i = (nextAnalysisSlot + offset) % 8
            if (identifiedNames[i] != null) continue
            
            // このスロットを各画像で試す
            val indicesToTry = mutableListOf(bestImageIndex)
            for (idx in bitmaps.indices) {
                if (idx != bestImageIndex) indicesToTry.add(idx)
            }
            
            for (idx in indicesToTry) {
                if (idx >= bitmaps.size) continue
                val success = identifySlot(bitmaps[idx], i, sortedMonsters)
                if (success) {
                    bestImageIndex = idx
                    nextAnalysisSlot = (i + 1) % 8 // 次回は次のスロットから開始
                    return true
                }
            }
            // 5枚の画像すべてで失敗した場合でも、次回の呼び出しでは次のスロットを試すようにする
            // これにより1つの難しいスロットで全体が止まるのを防ぐ
            nextAnalysisSlot = (i + 1) % 8
            return false // 1回の呼び出しで1スロット分だけ処理する
        }
        return false
    }

    private fun identifySlot(bitmap: Bitmap, slotIndex: Int, sortedMonsters: List<MonsterData>): Boolean {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        val imgW = mat.cols().toFloat()
        val imgH = mat.rows().toFloat()
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)

        val config = if (slotIndex < 4) calibrationData.myPartyBoxes[slotIndex] else calibrationData.enemyPartyBoxes[slotIndex - 4]
        
        // 2段階探索： 1. 狭い範囲 (パディング最小)
        val narrowPad = (20 * calibrationData.uiScale).toInt()
        val resultNarrow = tryIdentify(mat, config, narrowPad, imgW, imgH, sortedMonsters)
        
        if (resultNarrow.score > MONSTER_THRESHOLD) {
            finalizeSlot(slotIndex, resultNarrow, bitmap, config, narrowPad)
            mat.release()
            return true
        }
        
        // 2段階探索： 2. 広い範囲 (driftFactor適用)
        val driftFactor = Math.abs(config.centerX - 0.5f) * 2.5f
        val basePad = (ROI_PAD_MONSTER * calibrationData.uiScale).toInt()
        val extraPad = (40 * driftFactor * calibrationData.uiScale).toInt() 
        val widePad = basePad + extraPad
        
        val resultWide = tryIdentify(mat, config, widePad, imgW, imgH, sortedMonsters)
        
        // スコア更新（原因調査用：はみ出しの -1.0 も含めて記録する）
        if (resultWide.score > (identifiedScores[slotIndex] ?: -2.0)) {
            identifiedScores[slotIndex] = resultWide.score
            // スコアが更新されたら、その時点のROIを「最もマシな画像」として暫定保存
            saveRoi(bitmap, config, "monster_$slotIndex", widePad, widePad)
        }

        if (resultWide.score > MONSTER_THRESHOLD) {
            finalizeSlot(slotIndex, resultWide, bitmap, config, widePad)
            mat.release()
            return true
        }

        mat.release()
        return false
    }

    private fun tryIdentify(fullMat: Mat, config: BoxConfig, pad: Int, imgW: Float, imgH: Float, monsters: List<MonsterData>): MatchResult {
        val expandedConfig = BoxConfig(config.centerX, config.centerY, config.width + pad * 2, config.height + pad * 2)
        
        // 画像サイズ制限ガード
        if (expandedConfig.width > imgW || expandedConfig.height > imgH) return MatchResult("", -1.0)

        val left = ((imgW * expandedConfig.centerX) - (expandedConfig.width / 2)).toInt().coerceIn(0, (imgW.toInt() - expandedConfig.width).coerceAtLeast(0))
        val top = ((imgH * expandedConfig.centerY) - (expandedConfig.height / 2)).toInt().coerceIn(0, (imgH.toInt() - expandedConfig.height).coerceAtLeast(0))
        
        return try {
            val roi = fullMat.submat(top, top + expandedConfig.height, left, left + expandedConfig.width)
            
            // 【重要】ループに入る前に一度だけコントラスト補正を行う
            val workRoi = Mat()
            roi.copyTo(workRoi)
            Core.normalize(workRoi, workRoi, 0.0, 255.0, Core.NORM_MINMAX)
            
            val result = findBestMonsterMatchMicroScales(workRoi, monsters)
            
            workRoi.release()
            roi.release()
            result
        } catch (_: Exception) { MatchResult("", -1.0) }
    }

    private fun finalizeSlot(slotIndex: Int, result: MatchResult, bitmap: Bitmap, config: BoxConfig, pad: Int) {
        identifiedNames[slotIndex] = result.name
        identifiedScores[slotIndex] = result.score
        monsterMatchCounts[result.name] = monsterMatchCounts.getOrDefault(result.name, 0) + 1
        Log.i("BattleAnalyzer", "🎉 Slot[$slotIndex] ${result.name} 確定！ (Score: ${String.format(Locale.US, "%.3f", result.score)})")
        
        // 確定した瞬間のROIを保存
        saveRoi(bitmap, config, "monster_$slotIndex", pad, pad)
    }

    private fun findBestMonsterMatchMicroScales(workRoi: Mat, monsters: List<MonsterData>): MatchResult {
        var bestScore = -1.0
        var bestName = ""
        for (monster in monsters) {
            val variants = scaledMonsterTemplates[monster.name] ?: continue
            for (scaledTpl in variants) {
                if (scaledTpl.cols() <= workRoi.cols() && scaledTpl.rows() <= workRoi.rows()) {
                    val result = Mat()
                    Imgproc.matchTemplate(workRoi, scaledTpl, result, Imgproc.TM_CCOEFF_NORMED)
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

    fun getCurrentResults(): Triple<List<String>, List<String>, Pair<List<Double>, List<Double>>> {
        val my = identifiedNames.slice(0..3).map { it ?: "?" }
        val enemy = identifiedNames.slice(4..7).map { it ?: "?" }
        val myScores = identifiedScores.slice(0..3).map { it ?: -1.0 }
        val enemyScores = identifiedScores.slice(4..7).map { it ?: -1.0 }
        return Triple(my, enemy, Pair(myScores, enemyScores))
    }

    fun detectSelectedParty(bitmap: Bitmap): Pair<Int, List<Double>> {
        val template = partyCustomTemplateScaled ?: partySelectTemplateScaled ?: return -1 to emptyList()
        val allScores = mutableListOf<Double>()
        var bestIndex = -1
        var maxScore = -1.0

        for (i in calibrationData.partySelectBoxes.indices) {
            val config = calibrationData.partySelectBoxes[i]
            // パーティ選択は垂直方向に大きな遊びを持たせる
            val vMargin = (ROI_PAD_PARTY_V * calibrationData.uiScale).toInt()
            // 水平方向にもマージンを持たせる
            val hMargin = (ROI_PAD_PARTY_H * calibrationData.uiScale).toInt()
            val score = performColorMatchCached(bitmap, config, template, vMargin, hMargin)
            
            allScores.add(score)
            if (score > maxScore) {
                maxScore = score
                bestIndex = i
            }
        }

        val finalIdx = if (maxScore >= PARTY_THRESHOLD) bestIndex else -1
        
        val vMargin = (ROI_PAD_PARTY_V * calibrationData.uiScale).toInt()
        val hMargin = (ROI_PAD_PARTY_H * calibrationData.uiScale).toInt()

        if (finalIdx != -1) {
            // 【成功時】3枚セットで保存。0.7を超えている間は常に最新状態で上書き（心変わり対応）
            calibrationData.partySelectBoxes.forEachIndexed { i, config ->
                saveRoi(bitmap, config, "party_p$i", vMargin, hMargin)
            }
        }

        return finalIdx to allScores
    }

    fun saveDebugBitmap(bitmap: Bitmap, label: String) {
        val ctx = appContext ?: return
        try {
            val file = File(ctx.filesDir, "debug_${label}.png")
            file.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            Log.i("BattleAnalyzer", "📸 デバッグ画像を保存しました: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("BattleAnalyzer", "デバッグ画像の保存失敗: ${e.message}")
        }
    }

    fun saveRoi(bitmap: Bitmap, config: BoxConfig, label: String, vMargin: Int = 0, hMargin: Int = 0) {
        val ctx = appContext ?: return
        try {
            val imgW = bitmap.width
            val imgH = bitmap.height
            
            // マージンを含めたROIサイズを計算
            val roiW = (config.width + hMargin * 2).coerceIn(2, imgW)
            val roiH = (config.height + vMargin * 2).coerceIn(2, imgH)
            
            val centerX = (imgW * config.centerX).toInt()
            val centerY = (imgH * config.centerY).toInt()
            
            val left = (centerX - roiW / 2).coerceIn(0, (imgW - roiW).coerceAtLeast(0))
            val top = (centerY - roiH / 2).coerceIn(0, (imgH - roiH).coerceAtLeast(0))
            
            val cropped = Bitmap.createBitmap(bitmap, left, top, roiW, roiH)
            val file = File(ctx.filesDir, "last_roi_${label}.png")
            file.outputStream().use {
                cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            cropped.recycle()
        } catch (_: Exception) {}
    }

    fun detectVsScore(bitmap: Bitmap, config: BoxConfig): Double {
        val vMargin = (bitmap.height * 0.1).toInt()
        val hMargin = (ROI_PAD_GENERAL_H * calibrationData.uiScale).toInt()
        val s1 = if (vsCustomTemplateScaled != null) performColorMatchCached(bitmap, config, vsCustomTemplateScaled, vMargin, hMargin) else 0.0
        val s2 = performColorMatchCached(bitmap, config, vsFmTemplateScaled, vMargin, hMargin)
        val s3 = performColorMatchCached(bitmap, config, vsMgTemplateScaled, vMargin, hMargin)
        return maxOf(s1, maxOf(s2, s3))
    }
    fun detectWinScore(bitmap: Bitmap, config: BoxConfig): Double {
        val vMargin = (bitmap.height * 0.05).toInt()
        val hMargin = (ROI_PAD_GENERAL_H * calibrationData.uiScale).toInt()
        val s1 = if (winCustomTemplateScaled != null) performColorMatchCached(bitmap, config, winCustomTemplateScaled, vMargin, hMargin) else 0.0
        val s2 = performColorMatchCached(bitmap, config, winTemplateScaled, vMargin, hMargin)
        return maxOf(s1, s2)
    }
    fun detectLoseScore(bitmap: Bitmap, config: BoxConfig): Double {
        val vMargin = (bitmap.height * 0.05).toInt()
        val hMargin = (ROI_PAD_GENERAL_H * calibrationData.uiScale).toInt()
        val s1 = if (loseCustomTemplateScaled != null) performColorMatchCached(bitmap, config, loseCustomTemplateScaled, vMargin, hMargin) else 0.0
        val s2 = performColorMatchCached(bitmap, config, loseTemplateScaled, vMargin, hMargin)
        return maxOf(s1, s2)
    }
    fun detectPartyScore(bitmap: Bitmap, config: BoxConfig): Double {
        val template = partyCustomTemplateScaled ?: partySelectTemplateScaled ?: return 0.0
        val vMargin = (ROI_PAD_PARTY_V * calibrationData.uiScale).toInt()
        val hMargin = (ROI_PAD_PARTY_H * calibrationData.uiScale).toInt()
        return performColorMatchCached(bitmap, config, template, vMargin, hMargin)
    }

    fun detectMonsterScore(bitmap: Bitmap, config: BoxConfig): Double {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)
        val imgW = mat.cols()
        val imgH = mat.rows()

        // 画像サイズ制限ガード
        if (config.width > imgW || config.height > imgH) {
            mat.release()
            return -1.0
        }

        val centerX = (imgW * config.centerX).toInt()
        val centerY = (imgH * config.centerY).toInt()
        val left = (centerX - config.width / 2).coerceIn(0, (imgW - config.width).coerceAtLeast(0))
        val top = (centerY - config.height / 2).coerceIn(0, (imgH - config.height).coerceAtLeast(0))
        
        return try {
            val roi = mat.submat(top, top + config.height, left, left + config.width)
            val res = findBestMonsterMatchMicroScales(roi, monsterMaster)
            roi.release(); mat.release()
            res.score
        } catch (_: Exception) {
            mat.release()
            -1.0
        }
    }

    fun releaseTemplates() {
        monsterMaster.forEach { it.templateMat?.release() }
        vsFmTemplate?.release()
        vsMgTemplate?.release()
        vsCustomTemplate?.release()
        winTemplate?.release()
        loseTemplate?.release()
        winCustomTemplate?.release()
        loseCustomTemplate?.release()
        partySelectTemplate?.release()
        partyCustomTemplate?.release()
        
        scaledMonsterTemplates.values.forEach { list -> list.forEach { it.release() } }
        vsFmTemplateScaled?.release()
        vsMgTemplateScaled?.release()
        vsCustomTemplateScaled?.release()
        winTemplateScaled?.release()
        loseTemplateScaled?.release()
        winCustomTemplateScaled?.release()
        loseCustomTemplateScaled?.release()
        partySelectTemplateScaled?.release()
        partyCustomTemplateScaled?.release()
    }

    fun saveCustomTemplate(bitmap: Bitmap, config: BoxConfig, fileName: String) {
        val ctx = appContext ?: return
        val imgW = bitmap.width
        val imgH = bitmap.height
        val centerX = (imgW * config.centerX).toInt()
        val centerY = (imgH * config.centerY).toInt()
        val left = (centerX - config.width / 2).coerceIn(0, imgW - config.width)
        val top = (centerY - config.height / 2).coerceIn(0, imgH - config.height)
        
        try {
            val cropped = Bitmap.createBitmap(bitmap, left, top, config.width, config.height)
            val file = File(ctx.filesDir, fileName)
            file.outputStream().use {
                cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            Log.i("BattleAnalyzer", "✨ カスタムテンプレートを保存しました: ${file.absolutePath}")
            
            // 即座に読み込んで適用
            if (fileName == "vs_custom.png") {
                vsCustomTemplate?.release()
                vsCustomTemplate = loadExternalTemplate(file.absolutePath)
            } else if (fileName == "party_custom.png") {
                partyCustomTemplate?.release()
                partyCustomTemplate = loadExternalTemplate(file.absolutePath)
            } else if (fileName == "win_custom.png") {
                winCustomTemplate?.release()
                winCustomTemplate = loadExternalTemplate(file.absolutePath)
            } else if (fileName == "lose_custom.png") {
                loseCustomTemplate?.release()
                loseCustomTemplate = loadExternalTemplate(file.absolutePath)
            }
            cachedScale = -1.0 // 再スケーリングを強制
            prepareScaledTemplates()
        } catch (e: Exception) {
            Log.e("BattleAnalyzer", "カスタムテンプレート保存失敗: ${e.message}")
        }
    }

    fun deleteCustomTemplate(fileName: String) {
        val ctx = appContext ?: return
        val file = File(ctx.filesDir, fileName)
        if (file.exists()) file.delete()

        if (fileName == "vs_custom.png") {
            vsCustomTemplate?.release()
            vsCustomTemplate = null
        } else if (fileName == "party_custom.png") {
            partyCustomTemplate?.release()
            partyCustomTemplate = null
        } else if (fileName == "win_custom.png") {
            winCustomTemplate?.release()
            winCustomTemplate = null
        } else if (fileName == "lose_custom.png") {
            loseCustomTemplate?.release()
            loseCustomTemplate = null
        }
        
        cachedScale = -1.0 // 再スケーリングを強制してフォールバック
        prepareScaledTemplates()
        Log.i("BattleAnalyzer", "🗑️ カスタムテンプレート $fileName を削除しました。標準に戻ります。")
    }
}
