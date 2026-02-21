package com.android.nakamonrec

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.android.nakamonrec.databinding.ActivityCalibrationBinding
import com.google.gson.Gson
import java.io.File

class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private var mode: String? = null
    private var fileName: String? = null

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

        setupUI()
    }

    private fun setupUI() {
        val file = File(filesDir, fileName!!)
        if (!file.exists()) {
            Toast.makeText(this, "画像ファイルが見つかりません", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        binding.calibrationView.setSourceImage(bitmap)

        binding.textInstruction.text = when (mode) {
            "party" -> getString(R.string.calibrate_guide_party)
            "vs" -> getString(R.string.calibrate_guide_vs)
            "result" -> getString(R.string.calibrate_guide_result)
            else -> getString(R.string.calibrate_guide_default)
        }

        // 初期表示
        val currentData = loadCalibrationData()
        displayBoxes(currentData)

        binding.btnSave.setOnClickListener {
            saveChanges()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }

        binding.btnDefault.setOnClickListener {
            // CalibrationData() のコンストラクタで定義されているデフォルト値を使用
            displayBoxes(CalibrationData())
            Toast.makeText(this, "デフォルト位置に戻しました", Toast.LENGTH_SHORT).show()
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
        val data = loadCalibrationData()

        when (mode) {
            "party" -> {
                data.partySelectBoxes = updatedBoxes.map { BoxConfig(it.centerX, it.centerY, it.width, it.height) }
            }
            "vs" -> {
                val vs = updatedBoxes.find { it.id == 0 }!!
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

        Toast.makeText(this, "校正データを保存しました", Toast.LENGTH_SHORT).show()
        finish()
    }
}
