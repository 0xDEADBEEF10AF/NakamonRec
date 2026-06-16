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
    var dataManager: BattleDataManager? = null // フライトレコーダー書き込み用
    private val slotCandidates = arrayOfNulls<Set<String>>(8) // Top-K 追跡用 (O(1) lookup のため Set)

    // ユーザー可変の検知閾値。MediaCaptureService が dataManager から取得して書き戻す。
    var vsThreshold: Double = DEFAULT_VS_THRESHOLD
    var winThreshold: Double = DEFAULT_WIN_THRESHOLD
    var loseThreshold: Double = DEFAULT_LOSE_THRESHOLD

    fun applyThresholds(vs: Double, win: Double, lose: Double) {
        vsThreshold = vs.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)
        winThreshold = win.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)
        loseThreshold = lose.coerceIn(THRESHOLD_MIN, THRESHOLD_MAX)
    }

    companion object {
        // VS / WIN / LOSE 閾値のデフォルトと許容範囲 (ユーザー設定で 0.4〜0.8 に可変)
        const val DEFAULT_VS_THRESHOLD = 0.4
        const val DEFAULT_WIN_THRESHOLD = 0.4
        const val DEFAULT_LOSE_THRESHOLD = 0.4
        const val THRESHOLD_MIN = 0.4
        const val THRESHOLD_MAX = 0.8
        private const val MONSTER_THRESHOLD = 0.7
        private const val PARTY_THRESHOLD = 0.7
        private const val CANDIDATE_COUNT = 10 // Top-10 追跡
        private const val FALLBACK_THRESHOLD = 0.7 // Frame 1 がこれ未満なら次フレームもフルスキャン
        // 識別閾値 (MONSTER_THRESHOLD=0.7) に合わせる。
        // 0.5〜0.7 のグレーゾーンで「磁石テンプレ」(id037 / id055 / id109 等、軽負荷対象外の
        // 弱マッチしやすいテンプレ群) が Top-K を占有し、正解テンプレがランク外に押し出される
        // ケースが iOS の診断ログで実機確認されたため、Android も揃えて穴を塞ぐ。

        // performDeepAnalysisBatch のハードキャップ。v1.5.0 の最悪 ~26s を超えないための上限。
        // 超過したら残りフレームを切り捨てて識別をコミットし、次バトル開始の検知漏れを防ぐ。
        private const val ANALYSIS_BUDGET_MS: Long = 24_000L
        // フレーム間 yield。サーマルスロットリング下でも SoC に短い idle 期間を与える。
        private const val INTER_FRAME_YIELD_MS: Long = 50L
        
        /**
         * ROI（探索範囲）を広げるためのパディング値（ピクセル）。
         */
        const val ROI_PAD_MONSTER = 20
        const val ROI_PAD_PARTY_H = 30  // GALAXY等の縦横比ズレを考慮
        const val ROI_PAD_PARTY_V = 100 // GALAXY等の縦方向ズレを考慮
        const val ROI_PAD_GENERAL_H = 10 // 一般的な水平マージン

        // Case D で参照するテンプレートの基準解像度幅 (Pixel 10 Pro Fold 由来)。
        // 実フレーム幅 / TEMPLATE_BASE_WIDTH > 1.0 のとき ROI を 1/ratio で
        // INTER_AREA ダウンサンプリングしてからマッチングを実施する。
        const val TEMPLATE_BASE_WIDTH = 1080.0
    }

    data class ScanResult(val config: BoxConfig, val score: Double, val scale: Double)

    fun getWinTemplate(): Mat? = winTemplate
    fun getLoseTemplate(): Mat? = loseTemplate

    private fun prepareScaledTemplates() {
        val s = calibrationData.uiScale.toDouble()
        if (s == cachedScale) return

        scaledMonsterTemplates.values.forEach { list -> list.forEach { it.release() } }
        scaledMonsterTemplates.clear()

        // モンスターテンプレ scale 戦略:
        //   uiScale > 1.0 (高 DPI 端末: POCO F7 1280×2772 等):
        //     テンプレを INTER_CUBIC で upscale するとエッジが鈍り、ネイティブ
        //     高解像度描画された実画面とのシャープネス不一致で NCC が落ちる。
        //     → テンプレはネイティブのまま保持し、tryIdentify 側で ROI を
        //       1/uiScale で INTER_AREA ダウンサンプリングして比較する。
        //   uiScale ≤ 1.0 (Pixel-base / 小型機):
        //     従来通りテンプレを uiScale × micro で pre-scale して使う。
        val templateBaseScale = if (s > 1.0) 1.0 else s
        // GALAXY等の特殊なレンダリング（場所による縮小）に対応するため、探索範囲を +/- 10% に拡大
        val microScales = listOf(templateBaseScale * 0.90, templateBaseScale * 0.95, templateBaseScale * 1.0, templateBaseScale * 1.05, templateBaseScale * 1.10)

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

    /**
     * matchTemplate / minMaxLoc / normalize 等の hot path を一度実行して
     * ART JIT コンパイラ・OpenCV 内部キャッシュを warm にする。
     * cold-state からの初戦 (アプリ起動直後の 1 戦目) で解析時間が 3-4 倍に
     * ふくれる現象を防ぐための前処理。
     *
     * onCreate / 初期化フェーズで 1 回呼ぶ想定。コストは数百 ms 程度で、
     * REC ボタン押下から実バトル開始までの間に余裕で完了する。
     */
    fun warmupMatchPipeline() {
        if (scaledMonsterTemplates.isEmpty() || monsterMaster.isEmpty()) return
        val firstName = scaledMonsterTemplates.keys.first()
        val firstMonster = monsterMaster.firstOrNull { it.name == firstName } ?: return

        // ダミー ROI を生成して本番と同じ stack を 1 回通す
        // (submat → copyTo → normalize → matchTemplate × micro-scales → minMaxLoc)
        val dummyFrame = Mat(400, 400, CvType.CV_8UC3, Scalar.all(128.0))
        try {
            // tryIdentify のロジックをミニマル再現 (private なので直接呼ばずインライン化)
            val workRoi = Mat()
            dummyFrame.copyTo(workRoi)
            Core.normalize(workRoi, workRoi, 0.0, 255.0, Core.NORM_MINMAX)
            findBestMonsterMatchMicroScales(workRoi, listOf(firstMonster), returnAll = true)
            workRoi.release()
        } catch (_: Exception) {
            // warmup 失敗しても production には影響しない
        } finally {
            dummyFrame.release()
        }
        Log.i("BattleAnalyzer", "🔥 warmupMatchPipeline 完了")
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

        val rawRoi = scene.submat(startY, startY + searchH, startX, startX + searchW)

        // Case D (tryIdentify と同じ戦略): 高 DPI 端末は ROI を 1/baseScale で
        // INTER_AREA ダウンサンプリングして、テンプレはネイティブ解像度で比較。
        // これにより auto-cal でもネイティブシャープな実画面とテンプレのエッジ
        // 特性が揃い、127 テンプレ走査中の false positive (磁石テンプレ→ラベル
        // 上ロックオン) を抑制する。
        val useDownscale = baseScale > 1.0
        val matchRoi: Mat
        val templateLocalScales: List<Double>
        if (useDownscale) {
            val inv = 1.0 / baseScale
            matchRoi = Mat()
            Imgproc.resize(rawRoi, matchRoi, Size(), inv, inv, Imgproc.INTER_AREA)
            templateLocalScales = listOf(0.9, 1.0, 1.1)  // ネイティブ基準
        } else {
            matchRoi = rawRoi  // submat なのでメモリ共有、release は rawRoi のみで OK
            templateLocalScales = listOf(baseScale * 0.9, baseScale * 1.0, baseScale * 1.1)
        }

        var bestScore = -1.0
        var bestPos = Point()
        // 初期値: useDownscale=true ならネイティブ 80×130、false なら baseScale 適用
        var bestSize = if (useDownscale) Size(80.0, 130.0) else Size(80.0 * baseScale, 130.0 * baseScale)

        val monsters = monsterMaster // 校正時は全モンスターを対象
        for (m in monsters) {
            val tpl = m.templateMat ?: continue
            for (ls in templateLocalScales) {
                val scaledTpl = Mat()
                Imgproc.resize(tpl, scaledTpl, Size(), ls, ls, Imgproc.INTER_CUBIC)
                if (scaledTpl.cols() < matchRoi.cols() && scaledTpl.rows() < matchRoi.rows()) {
                    val result = Mat()
                    Imgproc.matchTemplate(matchRoi, scaledTpl, result, Imgproc.TM_CCOEFF_NORMED)
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

        // 座標とサイズを scene 座標系に戻す
        // useDownscale=true の場合、bestPos / bestSize はダウンサンプル空間の値なので
        // baseScale を掛けて元解像度に戻す。useDownscale=false は変換不要 (×1.0)。
        val scaleBack = if (useDownscale) baseScale else 1.0
        // 位置確定の閾値は意図的に 0.55 と低め:
        //   高 DPI 端末 (POCO F7 等) ではテンプレと実描画のシャープネス不一致で
        //   NCC が depressed する傾向があり、真モンスターの一致でも 0.55-0.68
        //   程度に頭打ちすることが実測で確認されている。識別閾値 0.7 と揃えると
        //   ほとんどのスロットが推定位置にフォールバックし、refinement の恩恵が
        //   失われるため敢えて 0.55 のまま維持。false positive (磁石テンプレが
        //   近傍テキストにロックオン) リスクは残るが、15% × 15% の探索窓で
        //   位置は推定位置近傍に限定されるため、runtime 側で pad 探索によって
        //   許容範囲内で吸収されることを期待する。
        val config = if (bestScore > 0.55) {
            val centerX = (startX + bestPos.x * scaleBack + bestSize.width * scaleBack / 2.0).toFloat() / scene.cols()
            val centerY = (startY + bestPos.y * scaleBack + bestSize.height * scaleBack / 2.0).toFloat() / scene.rows()
            val w = (bestSize.width * scaleBack).toInt()
            val h = (bestSize.height * scaleBack).toInt()
            BoxConfig(centerX, centerY, w, h)
        } else null

        if (useDownscale) matchRoi.release()
        rawRoi.release()
        return config
    }

    /**
     * 「詳細校正」用: 指定された 1 体のテンプレだけで matchTemplate を実行し、探索窓内の
     * 最良位置を返す。findBestMonsterStrict の 127 体走査と違い、磁石テンプレが false
     * positive を引き起こす余地がない (競合相手がいないため、指定モンスター本人の peak が
     * 必ず勝つ)。閾値判定もしない (1-vs-1 なので最良位置が必ず正解と仮定)。
     */
    private fun findSpecificMonster(
        scene: Mat,
        estCX: Float,
        estCY: Float,
        baseScale: Double,
        monsterId: String
    ): BoxConfig? {
        val monster = monsterMaster.firstOrNull { it.name == monsterId } ?: return null
        val tpl = monster.templateMat ?: return null

        val searchW = (scene.cols() * 0.15).toInt()
        val searchH = (scene.rows() * 0.15).toInt()
        if (searchW <= 0 || searchH <= 0 || searchW > scene.cols() || searchH > scene.rows()) return null

        val startX = ((scene.cols() * estCX) - (searchW / 2)).toInt().coerceIn(0, (scene.cols() - searchW).coerceAtLeast(0))
        val startY = ((scene.rows() * estCY) - (searchH / 2)).toInt().coerceIn(0, (scene.rows() - searchH).coerceAtLeast(0))

        val rawRoi = scene.submat(startY, startY + searchH, startX, startX + searchW)

        // findBestMonsterStrict と同じ Case D 戦略
        val useDownscale = baseScale > 1.0
        val matchRoi: Mat
        val templateLocalScales: List<Double>
        if (useDownscale) {
            val inv = 1.0 / baseScale
            matchRoi = Mat()
            Imgproc.resize(rawRoi, matchRoi, Size(), inv, inv, Imgproc.INTER_AREA)
            templateLocalScales = listOf(0.9, 1.0, 1.1)
        } else {
            matchRoi = rawRoi
            templateLocalScales = listOf(baseScale * 0.9, baseScale * 1.0, baseScale * 1.1)
        }

        var bestScore = -1.0
        var bestPos = Point()
        var bestSize = if (useDownscale) Size(80.0, 130.0) else Size(80.0 * baseScale, 130.0 * baseScale)

        for (ls in templateLocalScales) {
            val scaledTpl = Mat()
            Imgproc.resize(tpl, scaledTpl, Size(), ls, ls, Imgproc.INTER_CUBIC)
            if (scaledTpl.cols() < matchRoi.cols() && scaledTpl.rows() < matchRoi.rows()) {
                val result = Mat()
                Imgproc.matchTemplate(matchRoi, scaledTpl, result, Imgproc.TM_CCOEFF_NORMED)
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

        val config = if (bestScore >= 0.0) {
            val scaleBack = if (useDownscale) baseScale else 1.0
            val centerX = (startX + bestPos.x * scaleBack + bestSize.width * scaleBack / 2.0).toFloat() / scene.cols()
            val centerY = (startY + bestPos.y * scaleBack + bestSize.height * scaleBack / 2.0).toFloat() / scene.rows()
            val w = (bestSize.width * scaleBack).toInt()
            val h = (bestSize.height * scaleBack).toInt()
            BoxConfig(centerX, centerY, w, h)
        } else null

        if (useDownscale) matchRoi.release()
        rawRoi.release()
        return config
    }

    /**
     * 「詳細校正」: ユーザーが事前に 8 スロット分のモンスターを指定した上で実行する。
     * 各スロットで findSpecificMonster を使うため、127 体走査の磁石テンプレ false
     * positive 問題を原理的に回避できる。POCO F7 等の高 DPI 端末で auto-cal の
     * 緑枠がラベル等に乗ってしまう症状の救済策。
     *
     * @param specifiedMonsters サイズ 8 のリスト。
     *                          index 0..3 = 味方 [0..3]、index 4..7 = 敵 [0..3]
     */
    fun autoCalibrateBattleSceneWithSpec(
        sceneBitmap: Bitmap,
        specifiedMonsters: List<String>
    ): CalibrationData? {
        require(specifiedMonsters.size == 8) { "specifiedMonsters must have exactly 8 entries" }

        val fullMat = Mat()
        Utils.bitmapToMat(sceneBitmap, fullMat)
        Imgproc.cvtColor(fullMat, fullMat, Imgproc.COLOR_RGBA2RGB)

        // VS マッチング (autoCalibrateBattleScene と同じ)
        val customRes = findTemplateWithScale(fullMat, vsCustomTemplate, true, 0.3f, 0.8f)
        val fmRes = findTemplateWithScale(fullMat, vsFmTemplate, true, 0.3f, 0.8f)
        val mgRes = findTemplateWithScale(fullMat, vsMgTemplate, true, 0.3f, 0.8f)
        val bestRes = listOfNotNull(customRes, fmRes, mgRes).maxByOrNull { it.score }

        if (bestRes == null || bestRes.score < 0.4) {
            fullMat.release()
            return null
        }

        val standardVsWidth = (vsFmTemplate ?: vsMgTemplate)?.cols()?.toDouble() ?: return null
        val assetRes = listOfNotNull(fmRes, mgRes).maxByOrNull { it.score }
        val vsScaleForAssets = if (assetRes != null) {
            assetRes.scale
        } else if (customRes != null) {
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

        fun getMonsterConfigWithSpec(refX: Float, refY: Float, monsterId: String): BoxConfig {
            val dx = (refX - 540f) * vsScaleForAssets
            val dy = (refY - 1260f) * vsScaleForAssets
            val estCx = (vsCx + dx).toFloat()
            val estCy = (vsCy + dy).toFloat()
            return findSpecificMonster(fullMat, estCx / fullMat.cols(), estCy / fullMat.rows(), vsScaleForAssets, monsterId)
                ?: BoxConfig(estCx / fullMat.cols(), estCy / fullMat.rows(), (80 * vsScaleForAssets).toInt(), (130 * vsScaleForAssets).toInt())
        }

        newData.myPartyBoxes = listOf(
            getMonsterConfigWithSpec(196f, 1635f, specifiedMonsters[0]),
            getMonsterConfigWithSpec(391f, 1635f, specifiedMonsters[1]),
            getMonsterConfigWithSpec(585f, 1635f, specifiedMonsters[2]),
            getMonsterConfigWithSpec(780f, 1635f, specifiedMonsters[3])
        )
        newData.enemyPartyBoxes = listOf(
            getMonsterConfigWithSpec(201f, 915f, specifiedMonsters[4]),
            getMonsterConfigWithSpec(396f, 915f, specifiedMonsters[5]),
            getMonsterConfigWithSpec(590f, 915f, specifiedMonsters[6]),
            getMonsterConfigWithSpec(785f, 915f, specifiedMonsters[7])
        )

        fullMat.release()
        return newData
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
            if (performColorMatchCached(bitmap, calibrationData.vsBox, vsCustomTemplateScaled, vMargin, hMargin) > vsThreshold) detected = true
        }
        if (!detected && performColorMatchCached(bitmap, calibrationData.vsBox, vsFmTemplateScaled, vMargin, hMargin) > vsThreshold) detected = true
        if (!detected && performColorMatchCached(bitmap, calibrationData.vsBox, vsMgTemplateScaled, vMargin, hMargin) > vsThreshold) detected = true
        
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
            if (performColorMatchCached(bitmap, calibrationData.winBox, winCustomTemplateScaled, vMargin, hMargin) > winThreshold) detected = "WIN"
        }
        if (detected == null && performColorMatchCached(bitmap, calibrationData.winBox, winTemplateScaled, vMargin, hMargin) > winThreshold) detected = "WIN"

        if (detected == "WIN") {
            saveRoi(bitmap, calibrationData.winBox, "result", vMargin, hMargin)
            return "WIN"
        }

        // LOSE判定
        if (loseCustomTemplateScaled != null) {
            if (performColorMatchCached(bitmap, calibrationData.loseBox, loseCustomTemplateScaled, vMargin, hMargin) > loseThreshold) detected = "LOSE"
        }
        if (detected == null && performColorMatchCached(bitmap, calibrationData.loseBox, loseTemplateScaled, vMargin, hMargin) > loseThreshold) detected = "LOSE"
        
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
        slotCandidates.fill(null) // 候補もリセット
        bestImageIndex = 0 
        nextAnalysisSlot = 0 // リセット
    }

    fun isAllIdentified(): Boolean = identifiedNames.all { it != null }

    /**
     * バースト解析を一括実行する (iOS版 performDeepAnalysis 同期ロジック)
     *
     * 設計:
     *   - 5 フレーム × 8 スロットを一括処理。フレーム単位で bitmap → Mat 変換を 1 回だけ実施。
     *   - per-slot は narrow のみ。wide pad は廃止 (Top-K キャッシュ汚染対策)。
     *   - per-ROI normalize は tryIdentify 内部で実施 (テンプレと同条件)。
     *   - Top-K + フォールバック: Frame 1 で全テンプレ走査して上位 K 件を記憶、
     *     Frame 2-5 ではその K 件だけ評価。max < 0.5 のスロットは fallback として
     *     全テンプレ走査を継続。
     *   - 早期 finalize しない: 5 フレーム全体で最高スコアを追跡し、最後にまとめて確定。
     *
     * 安全機構 (連続バトル下でのサーマルスロットリング対策):
     *   - 解析時間ハードキャップ ANALYSIS_BUDGET_MS。超過時は残りフレームをスキップして
     *     その時点までの best 結果でコミット。次バトル開始の検知を絶対に取り逃さないため。
     *   - フレーム間に短い yield を挿入。v1.5.0 の repeatScan 100ms delay と同様、
     *     CPU governor / JIT / GC に recovery 時間を与える狙い。
     *
     * @param bitmaps バースト 5 フレーム (RGBA)
     * @param allowedMonsters 軽負荷モードのフィルタ、null なら全モンスター
     */
    fun performDeepAnalysisBatch(bitmaps: List<Bitmap>, allowedMonsters: Set<String>?) {
        if (bitmaps.isEmpty()) return

        val sortedMonsters = monsterMaster.filter { allowedMonsters == null || allowedMonsters.contains(it.name) }
            .sortedByDescending { monsterMatchCounts.getOrDefault(it.name, 0) }

        val slotFallback = BooleanArray(8) { false }

        // 5 フレーム全体での最高スコアを追跡 (早期 finalize しない)
        val bestScores = DoubleArray(8) { -1.0 }
        val bestNames = arrayOfNulls<String>(8)

        // 診断ログ用: Frame 1 の Top-K 候補と各スコアを保持 (グレーゾーン解析用)
        val slotTopKDiag = arrayOfNulls<List<Pair<String, Double>>>(8)

        val analysisStartTime = System.currentTimeMillis()
        var processedFrames = 0
        var budgetExceeded = false

        for (fIdx in bitmaps.indices) {
            // ハードキャップ: v1.5.0 の最悪 ~26s を超えないための安全弁。
            // 超過した時点で残りフレームを切り捨て、その時点までの結果でコミット。
            val elapsed = System.currentTimeMillis() - analysisStartTime
            if (elapsed > ANALYSIS_BUDGET_MS) {
                budgetExceeded = true
                Log.w("BattleAnalyzer", "解析予算超過 (${elapsed}ms) — Frame ${fIdx + 1} 以降をスキップ")
                dataManager?.appendFlightLog(String.format(
                    Locale.US,
                    "⏱ 解析予算 %dms 超過、Frame %d 以降スキップ (%d/%d 完了)",
                    ANALYSIS_BUDGET_MS, fIdx + 1, processedFrames, bitmaps.size))
                break
            }

            // フレーム間 yield: v1.5.0 の repeatScan delay 模倣。
            // SoC に短い idle 期間を与え、サーマルスロットリングを抑制する狙い。
            if (fIdx > 0) {
                try { Thread.sleep(INTER_FRAME_YIELD_MS) } catch (_: InterruptedException) {}
            }

            val bitmap = bitmaps[fIdx]
            val fullMat = Mat()
            Utils.bitmapToMat(bitmap, fullMat)
            val imgW = fullMat.cols().toFloat()
            val imgH = fullMat.rows().toFloat()
            Imgproc.cvtColor(fullMat, fullMat, Imgproc.COLOR_RGBA2RGB)

            val isFirstFrame = (fIdx == 0)

            for (slotIndex in 0..7) {
                val config = if (slotIndex < 4) calibrationData.myPartyBoxes[slotIndex] else calibrationData.enemyPartyBoxes[slotIndex - 4]
                val candidates = slotCandidates[slotIndex]

                // 解析対象モンスターの決定
                //   Frame 1 (isFirstFrame) → 全テンプレ走査 + 結果をキャッシュ
                //   Frame 2-5 で fallback フラグ立ち → 全テンプレ走査
                //   Frame 2-5 で候補あり → Top-K のみ走査
                val monstersToTry = if (!isFirstFrame && !slotFallback[slotIndex] && candidates != null) {
                    sortedMonsters.filter { candidates.contains(it.name) }
                } else {
                    sortedMonsters
                }
                // Frame 1 のときだけ allScores を返してもらう (キャッシュ生成のため)
                val needAllScores = isFirstFrame

                // 2 段階探索: narrow (狭い range)
                val narrowPad = (20 * calibrationData.uiScale).toInt()
                val resultNarrow = tryIdentify(fullMat, config, narrowPad, imgW, imgH, monstersToTry, needAllScores)

                // Frame 1 で Top-K キャッシュ生成
                if (isFirstFrame && resultNarrow.allScores != null) {
                    val sortedTopK = resultNarrow.allScores
                        .sortedByDescending { it.second }
                        .take(CANDIDATE_COUNT)
                    slotTopKDiag[slotIndex] = sortedTopK
                    if (resultNarrow.score >= FALLBACK_THRESHOLD) {
                        slotCandidates[slotIndex] = sortedTopK.map { it.first }.toSet()
                    } else {
                        slotFallback[slotIndex] = true
                    }
                }

                // best スコア更新?
                // 注: 旧コードはここで wide-pad 2 段階探索を実施していたが、
                //   - Frame 1 で wide が隣スロットに食い込んで誤マッチした候補が Top-K に
                //     混入し、Frame 2-5 を支配して撃沈する汚染ケースが発生していた
                //   - wide ROI は narrow より探索面積が大きく per-call が重い
                //   - iOS は最初から narrow のみ (search margin) 設計で動作している
                // → batch 解析は narrow のみに統一。校正がズレている端末はユーザー側で
                //   校正画面から再校正する運用とする。
                if (resultNarrow.score > bestScores[slotIndex]) {
                    bestScores[slotIndex] = resultNarrow.score
                    bestNames[slotIndex] = resultNarrow.name
                    saveRoi(bitmap, config, "monster_$slotIndex", narrowPad, narrowPad)
                }
            }

            fullMat.release()
            processedFrames++
        }

        // 全フレーム (もしくは予算内ぶん) 終了後にまとめて確定 (iOS の finalize 相当)
        for (slotIndex in 0..7) {
            val score = bestScores[slotIndex]
            val name = bestNames[slotIndex]
            identifiedScores[slotIndex] = score
            if (name != null && score >= MONSTER_THRESHOLD) {
                identifiedNames[slotIndex] = name
                monsterMatchCounts[name] = monsterMatchCounts.getOrDefault(name, 0) + 1
            } else {
                identifiedNames[slotIndex] = null  // 識別失敗 → getCurrentResults() が "?" を返す
            }

            // 診断ログ: グレーゾーン (0.5 <= best < 0.7) のスロットのみ Frame 1 Top-K を吐く
            if (score >= 0.5 && score < 0.7) {
                val isEnemy = slotIndex >= 4
                val side = if (isEnemy) "敵" else "味方"
                val withinSide = if (isEnemy) slotIndex - 4 else slotIndex
                val topK = slotTopKDiag[slotIndex]
                if (topK != null) {
                    val detail = topK.mapIndexed { rank, (n, s) ->
                        String.format(Locale.US, "#%d:%s(%.2f)", rank + 1, n, s)
                    }.joinToString(" ")
                    val fb = if (slotFallback[slotIndex]) " [fallback使用]" else ""
                    dataManager?.appendFlightLog("🔍 $side[$withinSide] Frame1 Top-K$fb: $detail")
                }
            }
        }

        if (budgetExceeded) {
            val identified = identifiedNames.count { it != null }
            dataManager?.appendFlightLog(
                "⚠ 予算切れ確定: $identified/8 識別 ($processedFrames/${bitmaps.size} フレーム処理)")
        }
    }

    fun identifyStepByStep(bitmap: Bitmap, allowedMonsters: Set<String>? = null) {
        val monsters = monsterMaster.filter { allowedMonsters == null || allowedMonsters.contains(it.name) }
            .sortedByDescending { monsterMatchCounts.getOrDefault(it.name, 0) }
        identifySlot(bitmap, (0..7).firstOrNull { identifiedNames[it] == null } ?: return, monsters)
    }

    private fun identifySlot(bitmap: Bitmap, slotIndex: Int, sortedMonsters: List<MonsterData>): Boolean {
        // 注: Top-K 追跡は本番バースト解析 (performDeepAnalysisBatch) のみで使用。
        // この identifySlot は校正画面の単発テスト用なので、毎回フルスキャンする。
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

    private fun tryIdentify(fullMat: Mat, config: BoxConfig, pad: Int, imgW: Float, imgH: Float, monsters: List<MonsterData>, returnAll: Boolean = false): MatchResult {
        val expandedConfig = BoxConfig(config.centerX, config.centerY, config.width + pad * 2, config.height + pad * 2)
        
        // 画像サイズ制限ガード
        if (expandedConfig.width > imgW || expandedConfig.height > imgH) return MatchResult("", -1.0)

        val left = ((imgW * expandedConfig.centerX) - (expandedConfig.width / 2)).toInt().coerceIn(0, (imgW.toInt() - expandedConfig.width).coerceAtLeast(0))
        val top = ((imgH * expandedConfig.centerY) - (expandedConfig.height / 2)).toInt().coerceIn(0, (imgH.toInt() - expandedConfig.height).coerceAtLeast(0))
        
        return try {
            val roi = fullMat.submat(top, top + expandedConfig.height, left, left + expandedConfig.width)

            // 【重要】ループに入る前に一度だけコントラスト補正を行う
            val workRoi = Mat()
            // Case D: 発動条件は保存された uiScale ではなく実フレーム幅で判定する。
            //   ユーザーが校正画面で「デフォルト」を選ぶと uiScale=1.0 が保存されてしまい、
            //   POCO F7 等の高 DPI 端末でも Case D が起動しないバグがあった。
            //   実フレーム幅 / 1080-base で見ると uiScale 保存値に依存せず判定できる。
            val effectiveScale = fullMat.cols() / TEMPLATE_BASE_WIDTH
            if (effectiveScale > 1.0) {
                // ROI をテンプレネイティブ解像度にダウンサンプリング (INTER_AREA)
                val inv = 1.0 / effectiveScale
                Imgproc.resize(roi, workRoi, Size(), inv, inv, Imgproc.INTER_AREA)
            } else {
                roi.copyTo(workRoi)
            }
            Core.normalize(workRoi, workRoi, 0.0, 255.0, Core.NORM_MINMAX)

            val result = findBestMonsterMatchMicroScales(workRoi, monsters, returnAll)

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

    /**
     * iOS版同期: 8スロット分の解析結果サマリーを取得する
     */
    fun getIdentificationSummary(): List<String> {
        val summary = mutableListOf<String>()
        for (i in 0..7) {
            val isEnemy = i >= 4
            val side = if (isEnemy) "敵" else "味方"
            val withinSide = if (isEnemy) i - 4 else i
            val score = identifiedScores[i] ?: -1.0
            val name = identifiedNames[i] ?: "?"
            val marker = if (score >= MONSTER_THRESHOLD) "✅" else "❓"
            
            summary.add(String.format(Locale.US, "%s %s[%d] %s Score %.3f",
                marker, side, withinSide, name, score))
        }
        return summary
    }

    private fun findBestMonsterMatchMicroScales(workRoi: Mat, monsters: List<MonsterData>, returnAll: Boolean = false): MatchResult {
        var bestScore = -1.0
        var bestName = ""
        val allScores = if (returnAll) mutableListOf<Pair<String, Double>>() else null

        for (monster in monsters) {
            val variants = scaledMonsterTemplates[monster.name] ?: continue
            var monsterMaxScore = -1.0
            for (scaledTpl in variants) {
                if (scaledTpl.cols() <= workRoi.cols() && scaledTpl.rows() <= workRoi.rows()) {
                    val result = Mat()
                    Imgproc.matchTemplate(workRoi, scaledTpl, result, Imgproc.TM_CCOEFF_NORMED)
                    val score = Core.minMaxLoc(result).maxVal
                    if (score > monsterMaxScore) {
                        monsterMaxScore = score
                    }
                    result.release()
                }
            }
            
            if (monsterMaxScore > bestScore) {
                bestScore = monsterMaxScore
                bestName = monster.name
            }
            allScores?.add(monster.name to monsterMaxScore)
        }
        return MatchResult(bestName, bestScore, allScores)
    }

    data class MatchResult(val name: String, val score: Double, val allScores: List<Pair<String, Double>>? = null)

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

        // 注: 旧コードはここで 3 枚 ROI を毎フレーム保存していたが、画面遷移中の
        // フェードフレームで最後の score>=0.7 フレームを上書きしてしまい、結果として
        // "うっすらした切り出し" が残るバグがあった。
        // 保存責務は MediaCaptureService.handleIdleState に移し、パーティ index が
        // 変化したタイミング (= 初回検知 or 心変わり) のみ 3 枚セットで保存する。
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
            // tryIdentify と同じ Case D ダウンサンプリング戦略を適用
            // (発動条件は実フレーム幅基準。uiScale 保存値には依存しない)
            val workRoi = Mat()
            val effectiveScale = mat.cols() / TEMPLATE_BASE_WIDTH
            if (effectiveScale > 1.0) {
                val inv = 1.0 / effectiveScale
                Imgproc.resize(roi, workRoi, Size(), inv, inv, Imgproc.INTER_AREA)
            } else {
                roi.copyTo(workRoi)
            }
            val res = findBestMonsterMatchMicroScales(workRoi, monsterMaster)
            workRoi.release(); roi.release(); mat.release()
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
