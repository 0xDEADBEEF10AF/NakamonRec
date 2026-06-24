package com.dqw.nakamonrec

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.dqw.nakamonrec.databinding.ActivityCalibrationBinding
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.Executors

class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private val dataManager by lazy { BattleDataManager(this) }
    private val analyzer by lazy { BattleAnalyzer(dataManager.monsterMaster) }
    private var mode: String? = null
    private var fileName: String? = null
    private var sourceBitmap: Bitmap? = null
    private var lastMeasuredRecord: BattleRecord? = null
    private var lastWinRecord: BattleRecord? = null
    private var lastLoseRecord: BattleRecord? = null
    private var detectedScale: Float = 1.0f
    private val executor = Executors.newSingleThreadExecutor()

    // 詳細校正の 8 体指定を Activity セッション中保持する。
    // ダイアログを開き直しても前回の選択が表示され、選び直し負担を抑える。
    // Activity 自体が破棄されると初期化されるため (校正画面を閉じて再度入ると消える)、
    // 別バトル/別校正セッションでの混在は発生しない。
    private val detailSpecs = arrayOfNulls<String>(8)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra("EXTRA_MODE")
        fileName = intent.getStringExtra("EXTRA_FILE_NAME")

        if (fileName == null || mode == null) {
            finish()
            return
        }

        analyzer.loadTemplates(this)
        analyzer.loadCustomTemplates(this)

        setupUI()
    }

    private fun setupUI() {
        val file = File(filesDir, fileName!!)
        if (!file.exists()) {
            showTopToast(getString(R.string.toast_image_not_found))
            finish()
            return
        }

        sourceBitmap = BitmapFactory.decodeFile(file.absolutePath)
        binding.calibrationView.setSourceImage(sourceBitmap!!)

        val currentData = loadCalibrationData()
        detectedScale = currentData.uiScale
        binding.calibrationView.setUiScale(detectedScale)

        binding.textInstruction.text = when (mode) {
            "party" -> getString(R.string.calibrate_guide_party)
            "vs" -> getString(R.string.calibrate_guide_vs)
            "win" -> getString(R.string.calibrate_guide_win)
            "lose" -> getString(R.string.calibrate_guide_lose)
            else -> getString(R.string.calibrate_guide_default)
        }

        // 最後に記録された戦績を取得
        val prefs = getSharedPreferences("NakamonPrefs", MODE_PRIVATE)
        val lastFile = prefs.getString("last_file_name", "history_default") ?: "history_default"
        dataManager.loadHistory(lastFile)
        lastMeasuredRecord = dataManager.history.records.lastOrNull()
        lastWinRecord = dataManager.history.records.findLast { it.result == "WIN" }
        lastLoseRecord = dataManager.history.records.findLast { it.result == "LOSE" }

        displayBoxes(currentData)

        binding.btnSave.setOnClickListener {
            saveChanges()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }

        binding.btnDefault.setOnClickListener {
            // 本番スコアをリセット
            lastMeasuredRecord = null
            lastWinRecord = null
            lastLoseRecord = null

            // 現在のモードに応じて対象のカスタムテンプレートのみを削除
            when (mode) {
                "party" -> analyzer.deleteCustomTemplate("party_custom.png")
                "vs" -> analyzer.deleteCustomTemplate("vs_custom.png")
                "win" -> analyzer.deleteCustomTemplate("win_custom.png")
                "lose" -> analyzer.deleteCustomTemplate("lose_custom.png")
            }

            val defaultData = CalibrationData()
            detectedScale = defaultData.uiScale
            binding.calibrationView.setUiScale(detectedScale)
            displayBoxes(defaultData)
            updateTemplateNameDisplay()
            showTopToast(getString(R.string.toast_default_restored))
        }

        binding.btnAuto.setOnClickListener {
            runAutoCalibration()
        }

        // 詳細校正は VS モードでのみ意味を持つ (モンスタースロット位置決め用)。
        // 他モードでは非表示にしてユーザーを混乱させないようにする。
        if (mode == "vs") {
            binding.btnAutoDetail.visibility = View.VISIBLE
            binding.btnAutoDetail.setOnClickListener {
                showDetailCalibrationSpec()
            }
        } else {
            binding.btnAutoDetail.visibility = View.GONE
        }

        updateTemplateNameDisplay()
    }

    private fun updateTemplateNameDisplay() {
        val text = when (mode) {
            "vs" -> {
                val vsFile = File(filesDir, "vs_custom.png")
                if (vsFile.exists()) "Template: CUSTOM" else "Template: BASE"
            }
            "party" -> {
                val partyFile = File(filesDir, "party_custom.png")
                if (partyFile.exists()) "Template: CUSTOM" else "Template: BASE"
            }
            "win" -> {
                val vsFile = File(filesDir, "win_custom.png")
                if (vsFile.exists()) "Template: CUSTOM" else "Template: BASE"
            }
            "lose" -> {
                val vsFile = File(filesDir, "lose_custom.png")
                if (vsFile.exists()) "Template: CUSTOM" else "Template: BASE"
            }
            else -> ""
        }
        binding.textTemplateName.text = text
    }

    private fun runAutoCalibration() {
        val bitmap = sourceBitmap ?: return
        
        // 自動校正の開始前に、既存の不適切なカスタムテンプレートがあれば削除してクリーンな状態にする
        when (mode) {
            "party" -> analyzer.deleteCustomTemplate("party_custom.png")
            "vs" -> analyzer.deleteCustomTemplate("vs_custom.png")
            "win" -> analyzer.deleteCustomTemplate("win_custom.png")
            "lose" -> analyzer.deleteCustomTemplate("lose_custom.png")
        }

        binding.layoutProgress.visibility = View.VISIBLE

        executor.execute {
            var newScale = detectedScale
            val results: List<CalibrationView.CalibrationBox>? = when (mode) {
                "party" -> {
                    val res = analyzer.autoCalibrateParty(bitmap)
                    if (res != null) {
                        newScale = res.second
                        
                        // 自動校正で見つかった最適な枠をその場でカスタムテンプレートとして保存
                        val configs = res.first
                        val bestConfig = configs.maxByOrNull { analyzer.detectPartyScore(bitmap, it) }
                        bestConfig?.let { analyzer.saveCustomTemplate(bitmap, it, "party_custom.png") }
                        
                        configs.mapIndexed { i: Int, config: BoxConfig ->
                            val score = analyzer.detectPartyScore(bitmap, config)
                            val actual = lastMeasuredRecord?.partySelectScores?.getOrNull(i) ?: -1.0
                            CalibrationView.CalibrationBox(i, config.centerX, config.centerY, config.width, config.height, "P${i + 1}", score, actual)
                        }
                    } else null
                }
                "vs" -> {
                    val autoData = analyzer.autoCalibrateBattleScene(bitmap)
                    if (autoData != null) {
                        newScale = autoData.uiScale
                        
                        // 見つかったVSロゴをその場でカスタムテンプレートとして保存
                        analyzer.saveCustomTemplate(bitmap, autoData.vsBox, "vs_custom.png")

                        val list = mutableListOf<CalibrationView.CalibrationBox>()
                        val vsScore = analyzer.detectVsScore(bitmap, autoData.vsBox)
                        list.add(CalibrationView.CalibrationBox(0, autoData.vsBox.centerX, autoData.vsBox.centerY, autoData.vsBox.width, autoData.vsBox.height, "VS", vsScore, lastMeasuredRecord?.vsScore ?: -1.0))
                        
                        autoData.enemyPartyBoxes.forEachIndexed { i: Int, b: BoxConfig ->
                            val s = analyzer.detectMonsterScore(bitmap, b)
                            val actual = lastMeasuredRecord?.enemyPartyScores?.getOrNull(i) ?: -1.0
                            list.add(CalibrationView.CalibrationBox(10+i, b.centerX, b.centerY, b.width, b.height, "敵${i+1}", s, actual))
                        }
                        autoData.myPartyBoxes.forEachIndexed { i: Int, b: BoxConfig ->
                            val s = analyzer.detectMonsterScore(bitmap, b)
                            val actual = lastMeasuredRecord?.myPartyScores?.getOrNull(i) ?: -1.0
                            list.add(CalibrationView.CalibrationBox(20+i, b.centerX, b.centerY, b.width, b.height, "自${i+1}", s, actual))
                        }
                        list
                    } else null
                }
                "win" -> {
                    val res = analyzer.autoCalibrateResult(bitmap, true)
                    if (res != null) {
                        val config = res.first
                        newScale = res.second
                        
                        // 自動校正で見つかった最適な枠をその場でカスタムテンプレートとして保存
                        analyzer.saveCustomTemplate(bitmap, config, "win_custom.png")
                        
                        val score = analyzer.detectWinScore(bitmap, config)
                        val actual = lastWinRecord?.resultScore ?: -1.0
                        listOf(CalibrationView.CalibrationBox(0, config.centerX, config.centerY, config.width, config.height, getString(R.string.label_win_short), score, actual))
                    } else null
                }
                "lose" -> {
                    val res = analyzer.autoCalibrateResult(bitmap, false)
                    if (res != null) {
                        val config = res.first
                        newScale = res.second

                        // 自動校正で見つかった最適な枠をその場でカスタムテンプレートとして保存
                        analyzer.saveCustomTemplate(bitmap, config, "lose_custom.png")

                        val score = analyzer.detectLoseScore(bitmap, config)
                        val actual = lastLoseRecord?.resultScore ?: -1.0
                        listOf(CalibrationView.CalibrationBox(0, config.centerX, config.centerY, config.width, config.height, getString(R.string.label_lose_short), score, actual))
                    } else null
                }
                else -> null
            }

            Handler(Looper.getMainLooper()).post {
                binding.layoutProgress.visibility = View.GONE
                if (results != null) {
                    detectedScale = newScale
                    binding.calibrationView.setUiScale(detectedScale)
                    binding.calibrationView.setBoxes(results)
                    updateTemplateNameDisplay() // スコア更新に合わせて表示確認
                    showTopToast(getString(R.string.toast_auto_calibrated))
                } else {
                    showTopToast(getString(R.string.toast_auto_calibrate_failed))
                }
            }
        }
    }

    private fun displayBoxes(data: CalibrationData) {
        val bitmap = sourceBitmap
        val boxes = when (mode) {
            "party" -> data.partySelectBoxes.mapIndexed { i: Int, config: BoxConfig ->
                val score = if (bitmap != null) analyzer.detectPartyScore(bitmap, config) else -1.0
                val actual = lastMeasuredRecord?.partySelectScores?.getOrNull(i) ?: -1.0
                CalibrationView.CalibrationBox(i, config.centerX, config.centerY, config.width, config.height, "P${i + 1}", score, actual)
            }
            "vs" -> {
                val list = mutableListOf<CalibrationView.CalibrationBox>()
                val vsScore = if (bitmap != null) analyzer.detectVsScore(bitmap, data.vsBox) else -1.0
                list.add(CalibrationView.CalibrationBox(0, data.vsBox.centerX, data.vsBox.centerY, data.vsBox.width, data.vsBox.height, "VS", vsScore, lastMeasuredRecord?.vsScore ?: -1.0))
                data.enemyPartyBoxes.forEachIndexed { i: Int, config: BoxConfig ->
                    val s = if (bitmap != null) analyzer.detectMonsterScore(bitmap, config) else -1.0
                    val actual = lastMeasuredRecord?.enemyPartyScores?.getOrNull(i) ?: -1.0
                    list.add(CalibrationView.CalibrationBox(10 + i, config.centerX, config.centerY, config.width, config.height, "敵${i + 1}", s, actual))
                }
                data.myPartyBoxes.forEachIndexed { i: Int, b: BoxConfig ->
                    val s = if (bitmap != null) analyzer.detectMonsterScore(bitmap, b) else -1.0
                    val actual = lastMeasuredRecord?.myPartyScores?.getOrNull(i) ?: -1.0
                    list.add(CalibrationView.CalibrationBox(20 + i, b.centerX, b.centerY, b.width, b.height, "自${i + 1}", s, actual))
                }
                list
            }
            "win" -> {
                val score = if (bitmap != null) analyzer.detectWinScore(bitmap, data.winBox) else -1.0
                val actual = lastWinRecord?.resultScore ?: -1.0
                listOf(CalibrationView.CalibrationBox(0, data.winBox.centerX, data.winBox.centerY, data.winBox.width, data.winBox.height, getString(R.string.label_win_short), score, actual))
            }
            "lose" -> {
                val score = if (bitmap != null) analyzer.detectLoseScore(bitmap, data.loseBox) else -1.0
                val actual = lastLoseRecord?.resultScore ?: -1.0
                listOf(CalibrationView.CalibrationBox(0, data.loseBox.centerX, data.loseBox.centerY, data.loseBox.width, data.loseBox.height, getString(R.string.label_lose_short), score, actual))
            }
            else -> emptyList()
        }
        binding.calibrationView.setBoxes(boxes)
    }

    private fun loadCalibrationData(): CalibrationData {
        val prefs = getSharedPreferences("NakamonPrefs", MODE_PRIVATE)
        val json = prefs.getString("calibration_data", null)
        return if (json != null) {
            Gson().fromJson(json, CalibrationData::class.java)
        } else {
            CalibrationData()
        }
    }

    private fun saveChanges() {
        val updatedBoxes = binding.calibrationView.getBoxes()
        if (updatedBoxes.isEmpty()) return

        val data = loadCalibrationData()
        data.uiScale = detectedScale

        val bitmap = sourceBitmap
        when (mode) {
            "party" -> {
                data.partySelectBoxes = updatedBoxes.map { BoxConfig(it.centerX, it.centerY, it.width, it.height) }
                // スコア（水色比率加味済み）が最も高い枠をカスタムテンプレートとして保存
                if (bitmap != null) {
                    val bestBox = updatedBoxes.maxByOrNull { it.score }
                    bestBox?.let {
                        analyzer.saveCustomTemplate(bitmap, BoxConfig(it.centerX, it.centerY, it.width, it.height), "party_custom.png")
                    }
                }
            }
            "vs" -> {
                val vs = updatedBoxes.find { it.id == 0 } ?: return
                data.vsBox = BoxConfig(vs.centerX, vs.centerY, vs.width, vs.height)
                data.enemyPartyBoxes = updatedBoxes.filter { it.id in 10..13 }.map { BoxConfig(it.centerX, it.centerY, it.width, it.height) }
                data.myPartyBoxes = updatedBoxes.filter { it.id in 20..23 }.map { BoxConfig(it.centerX, it.centerY, it.width, it.height) }
                // VSロゴをカスタムテンプレートとして保存
                if (bitmap != null) {
                    analyzer.saveCustomTemplate(bitmap, data.vsBox, "vs_custom.png")
                }
            }
            "win" -> {
                val res = updatedBoxes[0]
                val config = BoxConfig(res.centerX, res.centerY, res.width, res.height)
                data.winBox = config
                if (bitmap != null) {
                    analyzer.saveCustomTemplate(bitmap, config, "win_custom.png")
                }
            }
            "lose" -> {
                val res = updatedBoxes[0]
                val config = BoxConfig(res.centerX, res.centerY, res.width, res.height)
                data.loseBox = config
                if (bitmap != null) {
                    analyzer.saveCustomTemplate(bitmap, config, "lose_custom.png")
                }
            }
        }

        val json = Gson().toJson(data)
        getSharedPreferences("NakamonPrefs", MODE_PRIVATE).edit {
            putString("calibration_data", json)
        }

        showTopToast(getString(R.string.toast_save_success))
        finish()
    }

    private fun showTopToast(message: String) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 200)
        toast.show()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    // ============================================================
    // 詳細校正 (テンプレ指定モード)
    // ============================================================
    //
    // 通常の自動校正は 8 スロット各々で 127 体走査するため、磁石テンプレが
    // 近傍ラベル等に false positive ロックする問題が発生しうる (POCO F7 等
    // 高 DPI 端末で顕著)。詳細校正では事前にユーザーがスロットごとに「いる
    // モンスター」を指定し、1-vs-1 探索に切り替えることで競合相手をゼロに
    // して問題を回避する。

    private fun showDetailCalibrationSpec() {
        val bitmap = sourceBitmap ?: return

        // 8 スロット分の指定状態。Activity セッション中保持する detailSpecs を使う。
        // (毎回開くたびに選び直しを強要するのは UX が悪いという feedback への対応)
        val specs = detailSpecs
        val slotLabels = (0..7).map { i ->
            if (i < 4) getString(R.string.detail_calib_slot_my, i + 1)
            else getString(R.string.detail_calib_slot_enemy, i - 3)
        }

        val rootLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            // 背景は親 dialog window 側で半透明 surface を持たせるためここは透明
        }

        val description = android.widget.TextView(this).apply {
            text = getString(R.string.detail_calib_description)
            textSize = 12f
            setTextColor(android.graphics.Color.LTGRAY)
            setPadding(8, 0, 8, 16)
        }
        rootLayout.addView(description)

        // 4 列 × 2 行のグリッドで実際の VS 画面と同じ並びを表現:
        //   上段: 敵[0] 敵[1] 敵[2] 敵[3]   (slot index 4-7)
        //   下段: 自[0] 自[1] 自[2] 自[3]   (slot index 0-3)
        val slotCells = arrayOfNulls<android.widget.LinearLayout>(8)
        val slotThumbs = arrayOfNulls<android.widget.ImageView>(8)
        val slotNames = arrayOfNulls<android.widget.TextView>(8)

        // セル 1 個分のビルダ
        fun buildSlotCell(slotIndex: Int): android.widget.LinearLayout {
            val cell = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(6, 6, 6, 6)
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val label = android.widget.TextView(this).apply {
                text = slotLabels[slotIndex]
                textSize = 12f
                setTextColor(android.graphics.Color.WHITE)
                gravity = Gravity.CENTER
            }
            val thumb = android.widget.ImageView(this).apply {
                // 列幅基準で正方形にする
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = true
                val cornerPx = 6 * resources.displayMetrics.density
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
                    }
                }
                clipToOutline = true
                setBackgroundColor(0xFF333333.toInt())
                // 高さを width と同じにするため onMeasure を上書き
                val that = this
                viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        val w = that.width
                        if (w > 0 && that.layoutParams.height != w) {
                            that.layoutParams.height = w
                            that.requestLayout()
                            that.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        }
                    }
                })
            }
            val pickedName = android.widget.TextView(this).apply {
                text = getString(R.string.detail_calib_not_selected)
                textSize = 10f
                setTextColor(android.graphics.Color.LTGRAY)
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
            }
            cell.addView(label)
            cell.addView(thumb)
            cell.addView(pickedName)
            slotThumbs[slotIndex] = thumb
            slotNames[slotIndex] = pickedName
            slotCells[slotIndex] = cell

            // 既に指定済みなら表示を反映 (前回開いた時の選択を引き継ぐ)
            specs[slotIndex]?.let { id ->
                val monster = dataManager.monsterMaster.firstOrNull { it.name == id }
                if (monster != null) {
                    pickedName.text = monster.name
                    try {
                        assets.open("templates/${monster.fileName}").use {
                            thumb.setImageBitmap(BitmapFactory.decodeStream(it))
                        }
                    } catch (_: Exception) {}
                }
            }
            return cell
        }

        // 上段: 敵[0..3] (slot index 4..7)
        val enemyRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }
        for (slotIndex in 4..7) enemyRow.addView(buildSlotCell(slotIndex))
        rootLayout.addView(enemyRow)

        // 下段: 自[0..3] (slot index 0..3)
        val myRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }
        for (slotIndex in 0..3) myRow.addView(buildSlotCell(slotIndex))
        rootLayout.addView(myRow)

        val scroll = android.widget.ScrollView(this).apply {
            addView(rootLayout)
        }

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle(R.string.detail_calib_title)
            .setView(scroll)
            .setPositiveButton(R.string.detail_calib_start, null)  // override 後段
            .setNegativeButton(R.string.btn_back, null)
            .create()

        // VS スクショが透けて見えるように:
        //   1. window 全体の dim を下げる (背景アクティビティが暗くならない)
        //   2. dialog のカード surface を半透明 + 角丸に差し替え (テーマの不透明 #333333 を上書き)
        dialog.window?.let { window ->
            val params = window.attributes
            params.dimAmount = 0.2f
            window.attributes = params
            window.setBackgroundDrawable(
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 24f
                    setColor(0xC0222222.toInt())  // ~75% 不透明、25% 透過
                }
            )
        }

        dialog.setOnShowListener {
            val okBtn = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            // 全 8 スロット指定済みなら開いた瞬間に校正開始できる (前回値の引き継ぎ対応)
            okBtn.isEnabled = specs.all { it != null }
            // 各スロットセルのクリックで monster picker を開く
            for (i in 0..7) {
                slotCells[i]?.setOnClickListener {
                    showMonsterPickerForSlot(i, specs[i]) { pickedId ->
                        specs[i] = pickedId
                        val monster = dataManager.monsterMaster.firstOrNull { it.name == pickedId }
                        slotNames[i]?.text = monster?.name ?: pickedId
                        if (monster != null) {
                            try {
                                assets.open("templates/${monster.fileName}").use {
                                    slotThumbs[i]?.setImageBitmap(BitmapFactory.decodeStream(it))
                                }
                            } catch (_: Exception) {}
                        }
                        okBtn.isEnabled = specs.all { it != null }
                    }
                }
            }
            okBtn.setOnClickListener {
                if (specs.any { it == null }) {
                    showTopToast(getString(R.string.detail_calib_select_all))
                    return@setOnClickListener
                }
                dialog.dismiss()
                runDetailedAutoCalibration(bitmap, specs.filterNotNull())
            }
        }

        dialog.show()
    }

    /**
     * 1 スロット分のモンスター選択 dialog。grid から 1 体タップで onPicked コールバック。
     */
    private fun showMonsterPickerForSlot(
        slotIndex: Int,
        currentlySelected: String?,
        onPicked: (String) -> Unit
    ) {
        val rootLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xFF222222.toInt())
        }

        val grid = android.widget.GridView(this).apply {
            numColumns = 4
            horizontalSpacing = 10
            verticalSpacing = 10
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        rootLayout.addView(grid)

        val monsters = dataManager.monsterMaster
        val pickerDialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle(R.string.detail_calib_pick_monster)
            .setView(rootLayout)
            .setNegativeButton(R.string.btn_back, null)
            .create()

        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = monsters.size
            override fun getItem(p0: Int) = monsters[p0]
            override fun getItemId(p0: Int) = p0.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                val monster = getItem(position)
                val frame = object : android.widget.FrameLayout(this@CalibrationActivity) {
                    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                        super.onMeasure(widthSpec, widthSpec)
                    }
                }
                val img = android.widget.ImageView(this@CalibrationActivity).apply {
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    val cornerPx = 6 * resources.displayMetrics.density
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
                        }
                    }
                    clipToOutline = true
                    alpha = if (monster.name == currentlySelected) 1.0f else 0.85f
                    try {
                        assets.open("templates/${monster.fileName}").use {
                            setImageBitmap(BitmapFactory.decodeStream(it))
                        }
                    } catch (_: Exception) {}
                }
                frame.addView(img)
                frame.setOnClickListener {
                    onPicked(monster.name)
                    pickerDialog.dismiss()
                }
                return frame
            }
        }
        grid.adapter = adapter
        pickerDialog.show()
    }

    private fun runDetailedAutoCalibration(bitmap: Bitmap, specs: List<String>) {
        // 既存のカスタムテンプレ (VS) があれば削除して fresh state に
        analyzer.deleteCustomTemplate("vs_custom.png")

        binding.layoutProgress.visibility = View.VISIBLE
        executor.execute {
            val autoData = analyzer.autoCalibrateBattleSceneWithSpec(bitmap, specs)
            val results: List<CalibrationView.CalibrationBox>? = if (autoData != null) {
                analyzer.saveCustomTemplate(bitmap, autoData.vsBox, "vs_custom.png")
                val list = mutableListOf<CalibrationView.CalibrationBox>()
                val vsScore = analyzer.detectVsScore(bitmap, autoData.vsBox)
                list.add(CalibrationView.CalibrationBox(0, autoData.vsBox.centerX, autoData.vsBox.centerY, autoData.vsBox.width, autoData.vsBox.height, "VS", vsScore, lastMeasuredRecord?.vsScore ?: -1.0))
                autoData.enemyPartyBoxes.forEachIndexed { i, b ->
                    val s = analyzer.detectMonsterScore(bitmap, b)
                    val actual = lastMeasuredRecord?.enemyPartyScores?.getOrNull(i) ?: -1.0
                    list.add(CalibrationView.CalibrationBox(10 + i, b.centerX, b.centerY, b.width, b.height, "敵${i + 1}", s, actual))
                }
                autoData.myPartyBoxes.forEachIndexed { i, b ->
                    val s = analyzer.detectMonsterScore(bitmap, b)
                    val actual = lastMeasuredRecord?.myPartyScores?.getOrNull(i) ?: -1.0
                    list.add(CalibrationView.CalibrationBox(20 + i, b.centerX, b.centerY, b.width, b.height, "自${i + 1}", s, actual))
                }
                detectedScale = autoData.uiScale
                list
            } else null

            Handler(Looper.getMainLooper()).post {
                binding.layoutProgress.visibility = View.GONE
                if (results != null) {
                    binding.calibrationView.setUiScale(detectedScale)
                    binding.calibrationView.setBoxes(results)
                    updateTemplateNameDisplay()
                    showTopToast(getString(R.string.toast_auto_calibrated))
                } else {
                    showTopToast(getString(R.string.toast_auto_calibrate_failed))
                }
            }
        }
    }
}
