package com.android.nakamonrec

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
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
            val defaultData = CalibrationData()
            detectedScale = defaultData.uiScale
            displayBoxes(defaultData)
            Toast.makeText(this, getString(R.string.toast_default_restored), Toast.LENGTH_SHORT).show()
        }

        binding.btnAuto.setOnClickListener {
            runAutoCalibration()
        }
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
                        res.first.mapIndexed { i: Int, config: BoxConfig ->
                            CalibrationView.CalibrationBox(i, config.centerX, config.centerY, config.width, config.height, "P${i + 1}")
                        }
                    } else null
                }
                "vs" -> {
                    val autoData = analyzer.autoCalibrateBattleScene(bitmap)
                    if (autoData != null) {
                        newScale = autoData.uiScale
                        val list = mutableListOf<CalibrationView.CalibrationBox>()
                        list.add(CalibrationView.CalibrationBox(0, autoData.vsBox.centerX, autoData.vsBox.centerY, autoData.vsBox.width, autoData.vsBox.height, "VS"))
                        autoData.enemyPartyBoxes.forEachIndexed { i: Int, b: BoxConfig ->
                            list.add(CalibrationView.CalibrationBox(10+i, b.centerX, b.centerY, b.width, b.height, "敵${i+1}"))
                        }
                        autoData.myPartyBoxes.forEachIndexed { i: Int, b: BoxConfig ->
                            list.add(CalibrationView.CalibrationBox(20+i, b.centerX, b.centerY, b.width, b.height, "自${i+1}"))
                        }
                        list
                    } else null
                }
                "win" -> {
                    val resRes = analyzer.findTemplateGlobal(bitmap, analyzer.getWinTemplate(), false, 0.0f, 0.5f)
                    if (resRes != null) {
                        val config = resRes.first
                        listOf(CalibrationView.CalibrationBox(0, config.centerX, config.centerY, config.width, config.height, getString(R.string.label_win_short)))
                    } else null
                }
                "lose" -> {
                    val resRes = analyzer.findTemplateGlobal(bitmap, analyzer.getLoseTemplate(), false, 0.0f, 0.5f)
                    if (resRes != null) {
                        val config = resRes.first
                        listOf(CalibrationView.CalibrationBox(0, config.centerX, config.centerY, config.width, config.height, getString(R.string.label_lose_short)))
                    } else null
                }
                else -> null
            }

            Handler(Looper.getMainLooper()).post {
                binding.layoutProgress.visibility = View.GONE
                if (results != null) {
                    detectedScale = newScale
                    binding.calibrationView.setBoxes(results)
                    Toast.makeText(this, getString(R.string.toast_auto_calibrated), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.toast_auto_calibrate_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun displayBoxes(data: CalibrationData) {
        val boxes = when (mode) {
            "party" -> data.partySelectBoxes.mapIndexed { i: Int, config: BoxConfig ->
                CalibrationView.CalibrationBox(i, config.centerX, config.centerY, config.width, config.height, "P${i + 1}")
            }
            "vs" -> {
                val list = mutableListOf<CalibrationView.CalibrationBox>()
                list.add(CalibrationView.CalibrationBox(0, data.vsBox.centerX, data.vsBox.centerY, data.vsBox.width, data.vsBox.height, "VS"))
                data.enemyPartyBoxes.forEachIndexed { i: Int, config: BoxConfig ->
                    list.add(CalibrationView.CalibrationBox(10 + i, config.centerX, config.centerY, config.width, config.height, "敵${i + 1}"))
                }
                data.myPartyBoxes.forEachIndexed { i: Int, config: BoxConfig ->
                    list.add(CalibrationView.CalibrationBox(20 + i, config.centerX, config.centerY, config.width, config.height, "自${i + 1}"))
                }
                list
            }
            "win" -> listOf(
                CalibrationView.CalibrationBox(0, data.winBox.centerX, data.winBox.centerY, data.winBox.width, data.winBox.height, getString(R.string.label_win_short))
            )
            "lose" -> listOf(
                CalibrationView.CalibrationBox(0, data.loseBox.centerX, data.loseBox.centerY, data.loseBox.width, data.loseBox.height, getString(R.string.label_lose_short))
            )
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

        when (mode) {
            "party" -> {
                data.partySelectBoxes = updatedBoxes.map { BoxConfig(it.centerX, it.centerY, it.width, it.height) }
            }
            "vs" -> {
                val vs = updatedBoxes.find { it.id == 0 } ?: return
                data.vsBox = BoxConfig(vs.centerX, vs.centerY, vs.width, vs.height)
                data.enemyPartyBoxes = updatedBoxes.filter { it.id in 10..13 }.map { BoxConfig(it.centerX, it.centerY, it.width, it.height) }
                data.myPartyBoxes = updatedBoxes.filter { it.id in 20..23 }.map { BoxConfig(it.centerX, it.centerY, it.width, it.height) }
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
