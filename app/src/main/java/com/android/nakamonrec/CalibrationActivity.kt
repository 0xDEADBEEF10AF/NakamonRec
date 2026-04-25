package com.android.nakamonrec

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.android.nakamonrec.databinding.ActivityCalibrationBinding
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.Executors

class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private lateinit var analyzer: BattleAnalyzer
    private var mode: String? = null
    private var fileName: String? = null
    private var sourceBitmap: Bitmap? = null
    private var detectedScale: Float = 1.0f
    private val executor = Executors.newSingleThreadExecutor()

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

        val dm = BattleDataManager(this)
        analyzer = BattleAnalyzer(dm.monsterMaster)
        analyzer.loadTemplates(this)

        setupUI()
    }

    private fun setupUI() {
        val file = File(filesDir, fileName!!)
        if (!file.exists()) {
            Toast.makeText(this, getString(R.string.toast_image_not_found), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        sourceBitmap = BitmapFactory.decodeFile(file.absolutePath)
        binding.calibrationView.setSourceImage(sourceBitmap!!)

        binding.textInstruction.text = when (mode) {
            "party" -> getString(R.string.calibrate_guide_party)
            "vs" -> getString(R.string.calibrate_guide_vs)
            "win" -> getString(R.string.calibrate_guide_win)
            "lose" -> getString(R.string.calibrate_guide_lose)
            else -> getString(R.string.calibrate_guide_default)
        }

        val currentData = loadCalibrationData()
        detectedScale = currentData.uiScale
        displayBoxes(currentData)

        binding.btnSave.setOnClickListener {
            saveChanges()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }

        binding.btnDefault.setOnClickListener {
            // 現在のモードに応じて対象のカスタムテンプレートのみを削除
            when (mode) {
                "party" -> analyzer.deleteCustomTemplate("party_custom.png")
                "vs" -> analyzer.deleteCustomTemplate("vs_custom.png")
            }

            val defaultData = CalibrationData()
            detectedScale = defaultData.uiScale
            displayBoxes(defaultData)
            updateTemplateNameDisplay()
            Toast.makeText(this, getString(R.string.toast_default_restored), Toast.LENGTH_SHORT).show()
        }

        binding.btnAuto.setOnClickListener {
            runAutoCalibration()
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
            "win" -> "Template: BASE"
            "lose" -> "Template: BASE"
            else -> ""
        }
        binding.textTemplateName.text = text
    }

    private fun runAutoCalibration() {
        val bitmap = sourceBitmap ?: return
        
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
                            CalibrationView.CalibrationBox(i, config.centerX, config.centerY, config.width, config.height, "P${i + 1}", score)
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
                        list.add(CalibrationView.CalibrationBox(0, autoData.vsBox.centerX, autoData.vsBox.centerY, autoData.vsBox.width, autoData.vsBox.height, "VS", vsScore))
                        
                        autoData.enemyPartyBoxes.forEachIndexed { i: Int, b: BoxConfig ->
                            val s = analyzer.detectMonsterScore(bitmap, b)
                            list.add(CalibrationView.CalibrationBox(10+i, b.centerX, b.centerY, b.width, b.height, "敵${i+1}", s))
                        }
                        autoData.myPartyBoxes.forEachIndexed { i: Int, b: BoxConfig ->
                            val s = analyzer.detectMonsterScore(bitmap, b)
                            list.add(CalibrationView.CalibrationBox(20+i, b.centerX, b.centerY, b.width, b.height, "自${i+1}", s))
                        }
                        list
                    } else null
                }
                "win" -> {
                    val resRes = analyzer.findTemplateGlobal(bitmap, analyzer.getWinTemplate(), false, 0.0f, 0.5f)
                    if (resRes != null) {
                        val config = resRes.first
                        listOf(CalibrationView.CalibrationBox(0, config.centerX, config.centerY, config.width, config.height, getString(R.string.label_win_short), resRes.second))
                    } else null
                }
                "lose" -> {
                    val resRes = analyzer.findTemplateGlobal(bitmap, analyzer.getLoseTemplate(), false, 0.0f, 0.5f)
                    if (resRes != null) {
                        val config = resRes.first
                        listOf(CalibrationView.CalibrationBox(0, config.centerX, config.centerY, config.width, config.height, getString(R.string.label_lose_short), resRes.second))
                    } else null
                }
                else -> null
            }

            Handler(Looper.getMainLooper()).post {
                binding.layoutProgress.visibility = View.GONE
                if (results != null) {
                    detectedScale = newScale
                    binding.calibrationView.setBoxes(results)
                    updateTemplateNameDisplay() // スコア更新に合わせて表示確認
                    Toast.makeText(this, getString(R.string.toast_auto_calibrated), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.toast_auto_calibrate_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun displayBoxes(data: CalibrationData) {
        val bitmap = sourceBitmap
        val boxes = when (mode) {
            "party" -> data.partySelectBoxes.mapIndexed { i: Int, config: BoxConfig ->
                val score = if (bitmap != null) analyzer.detectPartyScore(bitmap, config) else -1.0
                CalibrationView.CalibrationBox(i, config.centerX, config.centerY, config.width, config.height, "P${i + 1}", score)
            }
            "vs" -> {
                val list = mutableListOf<CalibrationView.CalibrationBox>()
                val vsScore = if (bitmap != null) analyzer.detectVsScore(bitmap, data.vsBox) else -1.0
                list.add(CalibrationView.CalibrationBox(0, data.vsBox.centerX, data.vsBox.centerY, data.vsBox.width, data.vsBox.height, "VS", vsScore))
                data.enemyPartyBoxes.forEachIndexed { i: Int, config: BoxConfig ->
                    val s = if (bitmap != null) analyzer.detectMonsterScore(bitmap, config) else -1.0
                    list.add(CalibrationView.CalibrationBox(10 + i, config.centerX, config.centerY, config.width, config.height, "敵${i + 1}", s))
                }
                data.myPartyBoxes.forEachIndexed { i: Int, config: BoxConfig ->
                    val s = if (bitmap != null) analyzer.detectMonsterScore(bitmap, config) else -1.0
                    list.add(CalibrationView.CalibrationBox(20 + i, config.centerX, config.centerY, config.width, config.height, "自${i + 1}", s))
                }
                list
            }
            "win" -> {
                val score = if (bitmap != null) analyzer.detectWinScore(bitmap, data.winBox) else -1.0
                listOf(CalibrationView.CalibrationBox(0, data.winBox.centerX, data.winBox.centerY, data.winBox.width, data.winBox.height, getString(R.string.label_win_short), score))
            }
            "lose" -> {
                val score = if (bitmap != null) analyzer.detectLoseScore(bitmap, data.loseBox) else -1.0
                listOf(CalibrationView.CalibrationBox(0, data.loseBox.centerX, data.loseBox.centerY, data.loseBox.width, data.loseBox.height, getString(R.string.label_lose_short), score))
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
                data.winBox = BoxConfig(res.centerX, res.centerY, res.width, res.height)
            }
            "lose" -> {
                val res = updatedBoxes[0]
                data.loseBox = BoxConfig(res.centerX, res.centerY, res.width, res.height)
            }
        }

        val json = Gson().toJson(data)
        getSharedPreferences("NakamonPrefs", MODE_PRIVATE).edit {
            putString("calibration_data", json)
        }

        Toast.makeText(this, getString(R.string.toast_save_success), Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
