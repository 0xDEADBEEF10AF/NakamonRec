package com.android.nakamonrec

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
            "result" -> getString(R.string.calibrate_guide_result)
            else -> getString(R.string.calibrate_guide_default)
        }

        val currentData = loadCalibrationData()
        displayBoxes(currentData)

        binding.btnSave.setOnClickListener {
            saveChanges()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }

        binding.btnDefault.setOnClickListener {
            displayBoxes(CalibrationData())
            Toast.makeText(this, getString(R.string.toast_default_restored), Toast.LENGTH_SHORT).show()
        }

        binding.btnAuto.setOnClickListener {
            runAutoCalibration()
        }
    }

    private fun runAutoCalibration() {
        val bitmap = sourceBitmap ?: return
        Toast.makeText(this, getString(R.string.toast_scanning), Toast.LENGTH_SHORT).show()

        executor.execute {
            val results: List<CalibrationView.CalibrationBox>? = when (mode) {
                "party" -> {
                    analyzer.autoCalibrateParty(bitmap)?.mapIndexed { i, config ->
                        CalibrationView.CalibrationBox(i, config.centerX, config.centerY, config.width, config.height, "P${i + 1}")
                    }
                }
                "vs" -> {
                    val vsConfig = analyzer.findTemplateGlobal(bitmap, analyzer.getVsTemplate(), true)
                    if (vsConfig != null) {
                        val list = mutableListOf<CalibrationView.CalibrationBox>()
                        list.add(CalibrationView.CalibrationBox(0, vsConfig.centerX, vsConfig.centerY, vsConfig.width, vsConfig.height, "VS"))
                        
                        val def = CalibrationData()
                        val dx = vsConfig.centerX - def.vsBox.centerX
                        val dy = vsConfig.centerY - def.vsBox.centerY
                        
                        def.enemyPartyBoxes.forEachIndexed { i, b ->
                            list.add(CalibrationView.CalibrationBox(10+i, b.centerX + dx, b.centerY + dy, b.width, b.height, "敵${i+1}"))
                        }
                        def.myPartyBoxes.forEachIndexed { i, b ->
                            list.add(CalibrationView.CalibrationBox(20+i, b.centerX + dx, b.centerY + dy, b.width, b.height, "自${i+1}"))
                        }
                        list
                    } else null
                }
                "result" -> {
                    var resConfig = analyzer.findTemplateGlobal(bitmap, analyzer.getWinTemplate(), true)
                    if (resConfig == null) {
                        resConfig = analyzer.findTemplateGlobal(bitmap, analyzer.getLoseTemplate(), true)
                    }
                    resConfig?.let {
                        listOf(CalibrationView.CalibrationBox(0, it.centerX, it.centerY, it.width, it.height, "判定"))
                    }
                }
                else -> null
            }

            Handler(Looper.getMainLooper()).post {
                if (results != null) {
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
            "party" -> data.partySelectBoxes.mapIndexed { i, config ->
                CalibrationView.CalibrationBox(i, config.centerX, config.centerY, config.width, config.height, "P${i + 1}")
            }
            "vs" -> {
                val list = mutableListOf<CalibrationView.CalibrationBox>()
                list.add(CalibrationView.CalibrationBox(0, data.vsBox.centerX, data.vsBox.centerY, data.vsBox.width, data.vsBox.height, "VS"))
                data.enemyPartyBoxes.forEachIndexed { i, config ->
                    list.add(CalibrationView.CalibrationBox(10 + i, config.centerX, config.centerY, config.width, config.height, "敵${i + 1}"))
                }
                data.myPartyBoxes.forEachIndexed { i, config ->
                    list.add(CalibrationView.CalibrationBox(20 + i, config.centerX, config.centerY, config.width, config.height, "自${i + 1}"))
                }
                list
            }
            "result" -> listOf(
                CalibrationView.CalibrationBox(0, data.resultBox.centerX, data.resultBox.centerY, data.resultBox.width, data.resultBox.height, "判定")
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
            "result" -> {
                val res = updatedBoxes[0]
                data.resultBox = BoxConfig(res.centerX, res.centerY, res.width, res.height)
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
