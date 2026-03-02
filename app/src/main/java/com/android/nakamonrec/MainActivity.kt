package com.android.nakamonrec

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import com.android.nakamonrec.databinding.ActivityMainBinding
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private var isResetRequested = false
    private lateinit var binding: ActivityMainBinding
    private var pendingCalibrationFileName: String? = null
    private var calibrationSelectorDialog: AlertDialog? = null

    // GitHub API応答用
    data class GithubRelease(
        @SerializedName("tag_name") val tagName: String,
        val name: String,
        @SerializedName("html_url") val htmlUrl: String
    )

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

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val fileName = pendingCalibrationFileName ?: return@let
            if (importImageForCalibration(it, fileName)) {
                Toast.makeText(this, getString(R.string.msg_imported), Toast.LENGTH_SHORT).show()
                calibrationSelectorDialog?.dismiss()
                showCalibrationSelectorDialog() 
            } else {
                Toast.makeText(this, getString(R.string.msg_import_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // CSVインポート用ランチャー
    private val pickCsvLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importHistoryFromCsv(it) }
    }

    private val createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            csvContentToSave?.let { content ->
                try {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
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

        var currentVersionName = ""
        try {
            val pInfo = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            currentVersionName = pInfo.versionName ?: "1.0.0"
            binding.textVersion.text = getString(R.string.app_version, currentVersionName)
        } catch (_: Exception) {
            binding.textVersion.text = getString(R.string.ver_unknown)
        }

        binding.textVersion.setOnClickListener {
            Toast.makeText(this, getString(R.string.msg_checking_update), Toast.LENGTH_SHORT).show()
            checkForUpdates(currentVersionName, isManual = true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        if (OpenCVLoader.initLocal()) Log.i("OpenCV", "OpenCV loaded successfully")

        binding.btnToggleService.setOnClickListener {
            if (MediaCaptureService.isRunning) {
                stopCaptureService(); updateUI(false)
            } else {
                isResetRequested = false
                val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                captureLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        binding.btnShowHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        binding.cardCurrentFile.setOnClickListener { showFileSelectorDialog() }
        binding.btnResetHistory.setOnClickListener { showResetHistoryConfirmDialog(getCurrentFileName()) }
        binding.btnReadme.setOnClickListener { showReadmeDialog() }
        binding.btnCalibrate.setOnClickListener { showCalibrationSelectorDialog() }

        updateUI(MediaCaptureService.isRunning)
        if (currentVersionName.isNotEmpty()) checkForUpdates(currentVersionName, isManual = false)
    }

    private fun checkForUpdates(currentName: String, isManual: Boolean) {
        thread {
            try {
                val url = "https://api.github.com/repos/0xDEADBEEF10AF/NakamonRec/releases"
                val connection = URL(url).openConnection()
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                val json = connection.getInputStream().bufferedReader().use { it.readText() }
                val listType = object : TypeToken<List<GithubRelease>>() {}.type
                val releases: List<GithubRelease> = Gson().fromJson(json, listType)
                if (releases.isNotEmpty()) {
                    val latest = releases[0]
                    val latestName = latest.tagName.replace("v", "").trim()
                    val cleanCurrentName = currentName.replace("v", "").trim()
                    Handler(Looper.getMainLooper()).post {
                        if (isNewerVersion(latestName, cleanCurrentName)) showUpdateDialog(latest.name, latest.htmlUrl)
                        else if (isManual) Toast.makeText(this, getString(R.string.msg_latest_version), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                if (isManual) Handler(Looper.getMainLooper()).post { Toast.makeText(this, getString(R.string.msg_update_failed), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrElse(i) { 0 }; val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true; if (l < c) return false
        }
        return false
    }

    private fun showUpdateDialog(title: String, updateUrl: String) {
        AlertDialog.Builder(this).setTitle(getString(R.string.msg_update_available)).setMessage(getString(R.string.msg_update_desc, title))
            .setPositiveButton(getString(R.string.btn_update)) { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, updateUrl.toUri())) }
            .setNegativeButton(getString(R.string.btn_later), null).show()
    }

    private fun showFileSelectorDialog() {
        val files = filesDir.listFiles { file -> file.extension == "json" && file.name != "monsters.json" }
        val fileNames = files?.map { it.nameWithoutExtension }?.toTypedArray() ?: arrayOf()
        
        // あなたのデバイスでの正解物理配置を固定 [左端: Neutral] [中央: Negative] [右端: Positive]
        val builder = AlertDialog.Builder(this).setTitle("ファイルを選択")
            .setNeutralButton("新規作成") { _, _ -> showCreateFileDialog() }
            .setNegativeButton("CSVインポート") { _, _ -> pickCsvLauncher.launch("text/*") }
            .setPositiveButton("閉じる", null)

        if (fileNames.isNotEmpty()) {
            builder.setItems(fileNames) { _, idx -> showFileActionDialog(fileNames[idx]) }
        } else {
            builder.setMessage("保存されたファイルがありません。")
        }
        builder.show()
    }

    private fun importHistoryFromCsv(uri: Uri) {
        try {
            var csvFileName = "imported_record"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst()) csvFileName = cursor.getString(nameIndex).substringBeforeLast(".")
            }
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
            val lines = content.split(Regex("\\r?\\n")).filter { it.isNotBlank() }
            if (lines.size <= 6) return 

            val dm = BattleDataManager(this).apply { currentFileName = csvFileName; resetHistory() }
            var importedCount = 0
            lines.drop(6).forEach { line -> 
                val parts = line.split(",").map { it.trim().removeSurrounding("\"") }
                if (parts.size >= 11) {
                    val timestamp = parts[0]; val result = parts[1]; val partyName = parts[2]
                    val partyIndex = partyName.replace(Regex("[^0-9]"), "").toIntOrNull()?.minus(1) ?: 0
                    val myParty = listOf(parts[3], parts[4], parts[5], parts[6]); val enemyParty = listOf(parts[7], parts[8], parts[9], parts[10])
                    dm.history.records.add(BattleRecord(timestamp, result, partyIndex, myParty, enemyParty))
                    if (result == "WIN") dm.history.totalWins++ else dm.history.totalLosses++
                    importedCount++
                }
            }
            dm.saveHistory(); saveCurrentFileName(csvFileName); refreshServiceAndUI()
            Toast.makeText(this, "「$csvFileName.json」として${importedCount}件をインポートしました", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Toast.makeText(this, "インポートに失敗しました: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun showFileActionDialog(fileName: String) {
        AlertDialog.Builder(this).setTitle("ファイル操作: $fileName")
            .setItems(arrayOf("このファイルを使用する", "名前を変更する", "削除する", "CSVにエクスポート")) { _, idx ->
                when (idx) {
                    0 -> { saveCurrentFileName(fileName); Toast.makeText(this, getString(R.string.file_switched_toast, fileName), Toast.LENGTH_SHORT).show(); refreshServiceAndUI() }
                    1 -> showRenameDialog(fileName); 2 -> showDeleteConfirmDialog(fileName); 3 -> exportHistoryToCsv(fileName)
                }
            }.show()
    }

    private fun exportHistoryToCsv(fileName: String) {
        val dm = BattleDataManager(this).apply { loadHistory(fileName) }
        val csvBuilder = StringBuilder()
        csvBuilder.appendLine("総合戦績,${dm.history.records.size}戦 ${dm.history.totalWins}勝 ${dm.history.totalLosses}敗")
        (0..2).forEach { idx ->
            val pRecs = dm.history.records.filter { it.partyIndex == idx }
            val pWins = pRecs.count { it.result == "WIN" }
            csvBuilder.appendLine("パーティ${idx + 1}戦績,${pRecs.size}戦 ${pWins}勝 ${pRecs.size - pWins}敗")
        }
        csvBuilder.appendLine("\n\"戦闘終了時刻\",\"勝敗\",\"選択パーティ\",\"自分1\",\"自分2\",\"自分3\",\"自分4\",\"相手1\",\"相手2\",\"相手3\",\"相手4\"")
        dm.history.records.forEach { r ->
            csvBuilder.appendLine("\"${r.timestamp}\",\"${r.result}\",\"パーティ${r.partyIndex + 1}\",\"${r.myParty.getOrElse(0){""}}\",\"${r.myParty.getOrElse(1){""}}\",\"${r.myParty.getOrElse(2){""}}\",\"${r.myParty.getOrElse(3){""}}\",\"${r.enemyParty.getOrElse(0){""}}\",\"${r.enemyParty.getOrElse(1){""}}\",\"${r.enemyParty.getOrElse(2){""}}\",\"${r.enemyParty.getOrElse(3){""}}\"")
        }
        csvContentToSave = csvBuilder.toString(); createDocumentLauncher.launch("${fileName}_${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())}.csv")
    }

    private fun showCalibrationSelectorDialog() {
        val titles = arrayOf("パーティ選択画面", "VS画面", "勝利画面", "敗北画面"); val fileNames = arrayOf("base_party.png", "base_vs.png", "base_win.png", "base_lose.png"); val modes = arrayOf("party", "vs", "win", "lose")
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
        for (i in titles.indices) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 10, 0, 10); gravity = Gravity.CENTER_VERTICAL; isClickable = true; setBackgroundResource(android.R.drawable.list_selector_background) }
            val thumb = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(120, 120); scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF333333.toInt())
                val file = File(filesDir, fileNames[i]); if (file.exists()) setImageBitmap(BitmapFactory.decodeFile(file.absolutePath)) else setImageResource(android.R.drawable.ic_menu_gallery)
            }
            row.addView(thumb); row.addView(TextView(this).apply { text = titles[i]; setPadding(20, 0, 0, 0); textSize = 16f; setTextColor(0xFFFFFFFF.toInt()) })
            row.setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle(titles[i]).setItems(arrayOf("画像をインポート", "画像を削除", "校正を開始")) { _, idx ->
                    when (idx) {
                        0 -> { pendingCalibrationFileName = fileNames[i]; pickImageLauncher.launch("image/*") }
                        1 -> { if (File(filesDir, fileNames[i]).exists()) showDeleteImageConfirmDialog(fileNames[i]) }
                        2 -> { if (File(filesDir, fileNames[i]).exists()) startActivity(Intent(this@MainActivity, CalibrationActivity::class.java).apply { putExtra("EXTRA_MODE", modes[i]); putExtra("EXTRA_FILE_NAME", fileNames[i]) }) else Toast.makeText(this@MainActivity, "先に画像をインポートしてください", Toast.LENGTH_SHORT).show() }
                    }
                }.show()
            }
            container.addView(row)
        }
        calibrationSelectorDialog = AlertDialog.Builder(this).setTitle("キャプチャー画面の校正").setView(container).setNegativeButton("閉じる", null).show()
    }

    private fun showDeleteConfirmDialog(fileName: String) {
        AlertDialog.Builder(this).setTitle("ファイルの削除").setMessage("「$fileName」を削除しますか？")
            .setPositiveButton("削除") { _, _ -> if (File(filesDir, "$fileName.json").delete()) { Toast.makeText(this, "削除しました", Toast.LENGTH_SHORT).show(); if (getCurrentFileName() == fileName) saveCurrentFileName("default_record"); refreshServiceAndUI() } }.setNegativeButton("キャンセル", null).show()
    }

    private fun showResetHistoryConfirmDialog(fileName: String) {
        AlertDialog.Builder(this).setTitle("データのクリア").setMessage("「$fileName」の戦績データをすべて削除しますか？")
            .setPositiveButton("クリア") { _, _ -> val dm = BattleDataManager(this); dm.loadHistory(fileName); dm.resetHistory(); if (MediaCaptureService.isRunning) startService(Intent(this, MediaCaptureService::class.java).apply { action = MediaCaptureService.ACTION_RELOAD_HISTORY }); Toast.makeText(this, "データをクリアしました", Toast.LENGTH_SHORT).show(); updateUI(MediaCaptureService.isRunning) }.setNegativeButton("キャンセル", null).show()
    }

    private fun refreshServiceAndUI() {
        updateUI(MediaCaptureService.isRunning); if (MediaCaptureService.isRunning) startService(Intent(this, MediaCaptureService::class.java).apply { action = MediaCaptureService.ACTION_RELOAD_HISTORY })
    }

    private fun showDeleteImageConfirmDialog(fileName: String) {
        AlertDialog.Builder(this).setTitle("画像を削除").setMessage("この画像を削除しますか？").setPositiveButton("削除") { _, _ -> if (File(filesDir, fileName).delete()) { Toast.makeText(this, "削除しました", Toast.LENGTH_SHORT).show(); calibrationSelectorDialog?.dismiss(); showCalibrationSelectorDialog() } }.setNegativeButton("キャンセル", null).show()
    }

    private fun importImageForCalibration(uri: Uri, destFileName: String): Boolean {
        return try { contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(File(filesDir, destFileName)).use { output -> input.copyTo(output) } }; true } catch (_: Exception) { false }
    }

    private fun showReadmeDialog() {
        val scrollView = ScrollView(this); val textView = TextView(this).apply { text = getString(R.string.readme_content); textSize = 13f; setPadding(60, 40, 60, 40); setLineSpacing(0f, 1.2f); setTextColor("#CCCCCC".toColorInt()) }
        scrollView.addView(textView); AlertDialog.Builder(this).setTitle(R.string.readme_title).setView(scrollView).setPositiveButton("閉じる", null).show()
    }

    override fun onStart() { super.onStart(); ContextCompat.registerReceiver(this, serviceStopReceiver, IntentFilter(MediaCaptureService.ACTION_SERVICE_STOPPED), ContextCompat.RECEIVER_NOT_EXPORTED); updateUI(MediaCaptureService.isRunning) }
    override fun onStop() { super.onStop(); unregisterReceiver(serviceStopReceiver) }
    private fun startCaptureService(resultCode: Int, data: Intent, reset: Boolean) { val serviceIntent = Intent(this, MediaCaptureService::class.java).apply { putExtra("RESULT_CODE", resultCode); putExtra("DATA", data); putExtra("RESET_STATS", reset) }; ContextCompat.startForegroundService(this, serviceIntent); updateUI(true) }
    private fun stopCaptureService() { stopService(Intent(this, MediaCaptureService::class.java)); updateUI(false) }
    override fun onResume() { super.onResume(); updateUI(MediaCaptureService.isRunning) }
    private fun generateDefaultFileName(): String = "record_${SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault()).format(Date())}"

    private var pulseAnimation: ObjectAnimator? = null
    private fun updateUI(isRunning: Boolean) {
        if (isRunning) { binding.btnToggleService.apply { text = getString(R.string.btn_stop); backgroundTintList = ColorStateList.valueOf("#90D7EC".toColorInt()); strokeColor = ColorStateList.valueOf("#CCFFFFFF".toColorInt()) }; binding.cardCurrentFile.apply { strokeWidth = (2f * resources.displayMetrics.density).toInt(); strokeColor = "#90D7EC".toColorInt() }; startPulseAnimation() }
        else { binding.btnToggleService.apply { text = getString(R.string.btn_rec); backgroundTintList = ColorStateList.valueOf("#F09199".toColorInt()); strokeColor = ColorStateList.valueOf("#CCFFFFFF".toColorInt()) }; binding.cardCurrentFile.apply { strokeWidth = (1f * resources.displayMetrics.density).toInt(); strokeColor = "#444444".toColorInt() }; stopPulseAnimation() }
        val currentName = getCurrentFileName(); binding.textCurrentFile.text = getString(R.string.file_name_ext_format, currentName)
        val stats = BattleDataManager(this).apply { loadHistory(currentName) }.getStatistics()
        binding.valTotalRateMain.text = String.format(Locale.US, "%.1f%%", stats.winRate); binding.valTotalCountMain.text = getString(R.string.label_matches_format, stats.totalWins + stats.totalLosses); binding.valTotalWinLoseMain.text = getString(R.string.label_win_lose_format, stats.totalWins, stats.totalLosses)
    }

    private fun startPulseAnimation() { if (pulseAnimation != null) return; pulseAnimation = ObjectAnimator.ofPropertyValuesHolder(binding.btnToggleService, PropertyValuesHolder.ofFloat("scaleX", 1.0f, 1.05f), PropertyValuesHolder.ofFloat("scaleY", 1.0f, 1.05f)).apply { duration = 800; repeatCount = ObjectAnimator.INFINITE; repeatMode = ObjectAnimator.REVERSE; start() } }
    private fun stopPulseAnimation() { pulseAnimation?.cancel(); pulseAnimation = null; binding.btnToggleService.apply { scaleX = 1.0f; scaleY = 1.0f } }
    private fun getCurrentFileName(): String = getSharedPreferences("NakamonPrefs", MODE_PRIVATE).getString("last_file_name", "default_record") ?: "default_record"
    private fun saveCurrentFileName(name: String) = getSharedPreferences("NakamonPrefs", MODE_PRIVATE).edit { putString("last_file_name", name) }
    private fun isValidFileName(name: String): Boolean = name.isNotEmpty() && name.none { it in charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|', '.') }

    private fun showCreateFileDialog() {
        val editText = EditText(this).apply {
            setText(generateDefaultFileName())
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("新規ファイル名を入力")
            .setView(editText)
            .setPositiveButton("作成") { _, _ ->
                val newName = editText.text.toString()
                if (isValidFileName(newName)) {
                    BattleDataManager(this).apply {
                        currentFileName = newName
                        resetHistory()
                    }
                    saveCurrentFileName(newName)
                    refreshServiceAndUI()
                    Toast.makeText(this, "「$newName」を作成しました", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "ファイル名が無効です", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showRenameDialog(oldName: String) {
        val editText = EditText(this).apply { setText(oldName); selectAll() }
        AlertDialog.Builder(this).setTitle("新しい名前を入力").setView(editText).setPositiveButton("変更") { _, _ -> val newName = editText.text.toString()
            if (isValidFileName(newName) && newName != oldName) {
                if (File(filesDir, "$oldName.json").renameTo(File(filesDir, "$newName.json"))) {
                    if (getCurrentFileName() == oldName) saveCurrentFileName(newName); Toast.makeText(this, "名前を変更しました", Toast.LENGTH_SHORT).show(); refreshServiceAndUI()
                } else Toast.makeText(this, "変更に失敗しました", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, "ファイル名が無効です", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("キャンセル", null).show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
