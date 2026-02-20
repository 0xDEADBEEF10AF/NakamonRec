package com.android.nakamonrec

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import com.android.nakamonrec.databinding.ActivityMainBinding
import org.opencv.android.OpenCVLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var isResetRequested = false
    private lateinit var binding: ActivityMainBinding

    private val serviceStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MediaCaptureService.ACTION_SERVICE_STOPPED) {
                updateUI(false)
            }
        }
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                startCaptureService(result.resultCode, result.data!!, isResetRequested)
            }, 200)
        }
    }

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            csvContentToSave?.let { content ->
                try {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        // UTF-8 with BOM for Excel compatibility
                        outputStream.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                        outputStream.write(content.toByteArray())
                    }
                    Toast.makeText(this, "CSVファイルに保存しました", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "保存に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private var csvContentToSave: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        if (OpenCVLoader.initDebug()) {
            Log.i("OpenCV", "OpenCV loaded successfully")
        } else {
            Log.e("OpenCV", "OpenCV load failed")
        }

        binding.btnToggleService.setOnClickListener {
            if (MediaCaptureService.isRunning) {
                stopCaptureService()
                updateUI(false)
            } else {
                isResetRequested = false
                val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                captureLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        binding.btnShowHistory.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }

        binding.btnManageFileName.setOnClickListener {
            showFileSelectorDialog()
        }

        binding.btnResetHistory.setOnClickListener {
            showResetHistoryConfirmDialog(getCurrentFileName())
        }

        updateUI(MediaCaptureService.isRunning)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(MediaCaptureService.ACTION_SERVICE_STOPPED)
        // ContextCompatを使用してAPIレベルの差異を吸収
        ContextCompat.registerReceiver(
            this,
            serviceStopReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateUI(MediaCaptureService.isRunning)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(serviceStopReceiver)
    }

    private fun startCaptureService(resultCode: Int, data: Intent, reset: Boolean) {
        val serviceIntent = Intent(this, MediaCaptureService::class.java).apply {
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA", data)
            putExtra("RESET_STATS", reset)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        updateUI(true)
    }

    private fun stopCaptureService() {
        val serviceIntent = Intent(this, MediaCaptureService::class.java)
        stopService(serviceIntent)
        updateUI(false)
    }

    override fun onResume() {
        super.onResume()
        updateUI(MediaCaptureService.isRunning)
    }

    private fun generateDefaultFileName(): String {
        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
        return "record_${sdf.format(Date())}"
    }

    private var pulseAnimation: ObjectAnimator? = null

    private fun updateUI(isRunning: Boolean) {
        if (isRunning) {
            binding.btnToggleService.apply {
                text = getString(R.string.btn_stop)
                backgroundTintList = ColorStateList.valueOf("#90D7EC".toColorInt())
            }
            startPulseAnimation()
        } else {
            binding.btnToggleService.apply {
                text = getString(R.string.btn_rec)
                backgroundTintList = ColorStateList.valueOf("#F09199".toColorInt())
            }
            stopPulseAnimation()
        }

        binding.textCurrentFile.text = getString(R.string.current_file_format, getCurrentFileName())
    }

    private fun startPulseAnimation() {
        if (pulseAnimation != null) return
        pulseAnimation = ObjectAnimator.ofPropertyValuesHolder(
            binding.btnToggleService,
            PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.05f),
            PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.05f)
        ).apply {
            duration = 800
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimation?.cancel()
        pulseAnimation = null
        binding.btnToggleService.scaleX = 1.0f
        binding.btnToggleService.scaleY = 1.0f
    }

    private fun getCurrentFileName(): String {
        val prefs = getSharedPreferences("NakamonPrefs", MODE_PRIVATE)
        return prefs.getString("last_file_name", "battle_history") ?: "battle_history"
    }

    private fun saveCurrentFileName(name: String) {
        getSharedPreferences("NakamonPrefs", MODE_PRIVATE).edit {
            putString("last_file_name", name)
        }
    }

    private fun showFileSelectorDialog() {
        val files = filesDir.listFiles { file -> file.extension == "json" && file.name != "monsters.json" }
        val fileNames = files?.map { it.nameWithoutExtension }?.toTypedArray() ?: arrayOf()

        if (fileNames.isEmpty()) {
            Toast.makeText(this, "ファイルがありません", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("ファイルを選択")
            .setItems(fileNames) { _, which ->
                val selectedFile = fileNames[which]
                showFileActionDialog(selectedFile)
            }
            .setNeutralButton("新規作成") { _, _ ->
                showCreateFileDialog()
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun showFileActionDialog(fileName: String) {
        AlertDialog.Builder(this)
            .setTitle("ファイル操作: $fileName")
            .setItems(arrayOf("このファイルを使用する", "名前を変更する", "削除する", "CSVにエクスポート")) { _, which ->
                when (which) {
                    0 -> {
                        saveCurrentFileName(fileName)
                        Toast.makeText(this, getString(R.string.file_switched_toast, fileName), Toast.LENGTH_SHORT).show()
                        refreshServiceAndUI()
                    }
                    1 -> showRenameDialog(fileName)
                    2 -> showDeleteConfirmDialog(fileName)
                    3 -> exportHistoryToCsv(fileName)
                }
            }
            .show()
    }

    private fun exportHistoryToCsv(fileName: String) {
        val dm = BattleDataManager(this)
        dm.loadHistory(fileName)

        val stats = dm.getStatistics()
        val records = dm.history.records
        val csvBuilder = StringBuilder()

        val totalMatches = stats.totalWins + stats.totalLosses
        val overallSummary = "総合戦績,${totalMatches}戦 ${stats.totalWins}勝 ${stats.totalLosses}敗 (勝率${String.format(Locale.US, "%.1f", stats.winRate)}%)"
        csvBuilder.appendLine(overallSummary)

        stats.partyStats.forEach { partyStat ->
            val partyMatches = partyStat.wins + partyStat.losses
            val partySummary = "パーティ${partyStat.index + 1}戦績,${partyMatches}戦 ${partyStat.wins}勝 ${partyStat.losses}敗 (勝率${String.format(Locale.US, "%.1f", partyStat.winRate)}%)"
            csvBuilder.appendLine(partySummary)
        }

        csvBuilder.appendLine("\n" + "\"戦闘終了時刻\",\"勝敗\",\"選択パーティ\",\"自分1\",\"自分2\",\"自分3\",\"自分4\",\"相手1\",\"相手2\",\"相手3\",\"相手4\"")

        records.forEach { record ->
            val partyName = "パーティ${record.partyIndex + 1}"
            val my1 = record.myParty.getOrElse(0) { "" }
            val my2 = record.myParty.getOrElse(1) { "" }
            val my3 = record.myParty.getOrElse(2) { "" }
            val my4 = record.myParty.getOrElse(3) { "" }
            val en1 = record.enemyParty.getOrElse(0) { "" }
            val en2 = record.enemyParty.getOrElse(1) { "" }
            val en3 = record.enemyParty.getOrElse(2) { "" }
            val en4 = record.enemyParty.getOrElse(3) { "" }
            val line = "\"${record.timestamp}\",\"${record.result}\",\"$partyName\",\"$my1\",\"$my2\",\"$my3\",\"$my4\",\"$en1\",\"$en2\",\"$en3\",\"$en4\""
            csvBuilder.appendLine(line)
        }

        csvContentToSave = csvBuilder.toString()
        val suggestedFileName = "${fileName}_${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())}.csv"
        createDocumentLauncher.launch(suggestedFileName)
    }

    private fun showCreateFileDialog() {
        val editText = EditText(this)
        val defaultName = generateDefaultFileName()
        editText.setText(defaultName)
        editText.selectAll()

        AlertDialog.Builder(this)
            .setTitle("新規ファイル名を入力")
            .setView(editText)
            .setPositiveButton("作成") { _, _ ->
                val newName = editText.text.toString()
                if (newName.isNotEmpty()) {
                    val dm = BattleDataManager(this)
                    dm.currentFileName = newName
                    dm.resetHistory()
                    saveCurrentFileName(newName)
                    refreshServiceAndUI()
                    updateUI(MediaCaptureService.isRunning)
                    Toast.makeText(this, "「$newName」を作成しました", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showRenameDialog(oldName: String) {
        val editText = EditText(this)
        editText.setText(oldName)
        editText.selectAll()

        AlertDialog.Builder(this)
            .setTitle("新しい名前を入力")
            .setView(editText)
            .setPositiveButton("変更") { _, _ ->
                val newName = editText.text.toString()
                if (newName.isNotEmpty() && newName != oldName) {
                    val oldFile = File(filesDir, "$oldName.json")
                    val newFile = File(filesDir, "$newName.json")

                    if (oldFile.renameTo(newFile)) {
                        if (getCurrentFileName() == oldName) {
                            saveCurrentFileName(newName)
                        }
                        Toast.makeText(this, "名前を変更しました", Toast.LENGTH_SHORT).show()
                        refreshServiceAndUI()
                    } else {
                        Toast.makeText(this, "変更に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showDeleteConfirmDialog(fileName: String) {
        AlertDialog.Builder(this)
            .setTitle("ファイルの削除")
            .setMessage("「$fileName」を削除しますか？\nこの操作は取り消せません。")
            .setPositiveButton("削除") { _, _ ->
                val file = File(filesDir, "$fileName.json")
                if (file.delete()) {
                    Toast.makeText(this, "削除しました", Toast.LENGTH_SHORT).show()
                    if (getCurrentFileName() == fileName) {
                        saveCurrentFileName("battle_history")
                    }
                    refreshServiceAndUI()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showResetHistoryConfirmDialog(fileName: String) {
        AlertDialog.Builder(this)
            .setTitle("データのクリア")
            .setMessage("「$fileName」の戦績データをすべて削除しますか？\n(ファイル自体は削除されません)")
            .setPositiveButton("クリア") { _, _ ->
                val dm = BattleDataManager(this)
                dm.loadHistory(fileName)
                dm.resetHistory()
                Toast.makeText(this, "データをクリアしました", Toast.LENGTH_SHORT).show()
                updateUI(MediaCaptureService.isRunning)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun refreshServiceAndUI() {
        updateUI(MediaCaptureService.isRunning)
        if (MediaCaptureService.isRunning) {
            val intent = Intent(this, MediaCaptureService::class.java)
            ContextCompat.startForegroundService(this, intent)
        }
    }
}
