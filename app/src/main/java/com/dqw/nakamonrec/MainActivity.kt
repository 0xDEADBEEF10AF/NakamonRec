package com.dqw.nakamonrec

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import com.dqw.nakamonrec.databinding.ActivityMainBinding
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
    private val dataManager by lazy { BattleDataManager(this) }

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
                showTopToast(getString(R.string.msg_imported))
                calibrationSelectorDialog?.dismiss()
                showCalibrationSelectorDialog() 
            } else {
                showTopToast(getString(R.string.msg_import_failed))
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
                    showTopToast("CSVファイルに保存しました", true)
                } catch (e: Exception) {
                    showTopToast("保存に失敗しました: ${e.message}", true)
                }
            }
        }
    }
    private var csvContentToSave: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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

        binding.textVersion.setOnLongClickListener {
            showDeveloperMenu(currentVersionName)
            true
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

        startTitleAnimation()
    }

    private fun startTitleAnimation() {
        binding.arcTitle.animationProgress = 0.0f
        val offset = 100f * resources.displayMetrics.density
        binding.arcTitle.translationY = offset
        binding.arcTitle.alpha = 0f

        val delay = 400L // 起動後の待機時間

        // 進捗（アーチの回転）
        ObjectAnimator.ofFloat(binding.arcTitle, "animationProgress", 0.0f, 1.0f).apply {
            duration = 1200
            startDelay = delay
            interpolator = OvershootInterpolator(0.8f)
            start()
        }

        // 移動
        ObjectAnimator.ofFloat(binding.arcTitle, "translationY", offset, 0f).apply {
            duration = 1000
            startDelay = delay
            interpolator = DecelerateInterpolator()
            start()
        }

        // フェードイン
        ObjectAnimator.ofFloat(binding.arcTitle, "alpha", 0f, 1f).apply {
            duration = 800
            startDelay = delay
            start()
        }
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
                        if (isNewerVersion(latestName, cleanCurrentName)) {
                            // 自動チェック時のみ「このバージョンを抑止」フラグを尊重する。
                            // 手動 (デバッグメニューから) のときは常にダイアログを出す。
                            val suppressed = getSuppressedUpdateVersion()
                            if (!isManual && suppressed == latest.tagName) {
                                // 抑止対象なのでスキップ
                            } else {
                                showUpdateDialog(latest.name, latest.htmlUrl, latest.tagName)
                            }
                        } else if (isManual) showTopToast(getString(R.string.msg_latest_version))
                    }
                }
            } catch (_: Exception) {
                if (isManual) Handler(Looper.getMainLooper()).post { showTopToast(getString(R.string.msg_update_failed)) }
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

    private fun showUpdateDialog(title: String, updateUrl: String, tagName: String) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
        }
        val messageView = TextView(this).apply {
            text = getString(R.string.msg_update_desc, title)
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
        }
        val checkbox = android.widget.CheckBox(this).apply {
            text = "このバージョンの通知は今後表示しない"
            textSize = 12f
            setTextColor(android.graphics.Color.LTGRAY)
            val mt = (12 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = mt }
        }
        container.addView(messageView)
        container.addView(checkbox)

        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle(getString(R.string.msg_update_available))
            .setView(container)
            .setPositiveButton(getString(R.string.btn_update)) { _, _ ->
                if (checkbox.isChecked) saveSuppressedUpdateVersion(tagName)
                startActivity(Intent(Intent.ACTION_VIEW, updateUrl.toUri()))
            }
            .setNegativeButton(getString(R.string.btn_later)) { _, _ ->
                if (checkbox.isChecked) saveSuppressedUpdateVersion(tagName)
            }
            .show()
    }

    private fun saveSuppressedUpdateVersion(tagName: String) {
        getSharedPreferences("NakamonPrefs", MODE_PRIVATE).edit()
            .putString("suppressed_update_version", tagName).apply()
    }

    private fun getSuppressedUpdateVersion(): String? {
        return getSharedPreferences("NakamonPrefs", MODE_PRIVATE).getString("suppressed_update_version", null)
    }

    private fun clearSuppressedUpdateVersion() {
        getSharedPreferences("NakamonPrefs", MODE_PRIVATE).edit()
            .remove("suppressed_update_version").apply()
    }

    private fun showFileSelectorDialog() {
        val files = filesDir.listFiles { file -> file.extension == "json" && file.name != "monsters.json" }
        val fileNames = files?.map { it.nameWithoutExtension }?.toTypedArray() ?: arrayOf()
        
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle("ファイルを選択")
            .setView(root)
            .create()

        if (fileNames.isNotEmpty()) {
            val listView = android.widget.ListView(this).apply {
                adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, fileNames)
                // ファイルが多い場合に備えて高さを調整（重み付け）
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                setOnItemClickListener { _, _, idx, _ ->
                    dialog.dismiss()
                    showFileActionDialog(fileNames[idx])
                }
            }
            root.addView(listView)
        } else {
            val emptyText = TextView(this).apply {
                text = "保存されたファイルがありません。"
                setPadding(50, 50, 50, 50)
                gravity = Gravity.CENTER
            }
            root.addView(emptyText)
        }

        // カスタムボタンエリア (4つのボタンを横並び)
        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
        }
        val btnParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        
        fun createBtn(label: String, onClick: () -> Unit) = android.widget.Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            textSize = 10f
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }

        buttonBar.addView(createBtn(getString(R.string.btn_new_file)) { dialog.dismiss(); showCreateFileDialog() }, btnParams)
        buttonBar.addView(createBtn(getString(R.string.btn_merge_files)) { dialog.dismiss(); showMergeSelectorDialog() }, btnParams)
        buttonBar.addView(createBtn(getString(R.string.btn_import_csv_short)) { dialog.dismiss(); pickCsvLauncher.launch("text/*") }, btnParams)
        buttonBar.addView(createBtn(getString(R.string.btn_close)) { dialog.dismiss() }, btnParams)
        
        root.addView(buttonBar)
        dialog.show()
    }

    private fun showMergeSelectorDialog() {
        val files = filesDir.listFiles { file -> file.extension == "json" && file.name != "monsters.json" }
        val fileNames = files?.map { it.nameWithoutExtension }?.toTypedArray() ?: arrayOf()
        if (fileNames.isEmpty()) {
            showTopToast("マージできるファイルがありません")
            return
        }

        val checkedItems = BooleanArray(fileNames.size) { false }
        val selectedFiles = mutableListOf<String>()

        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle(R.string.title_merge_files)
            .setMultiChoiceItems(fileNames, checkedItems) { _, which, isChecked ->
                if (isChecked) selectedFiles.add(fileNames[which])
                else selectedFiles.remove(fileNames[which])
            }
            .setPositiveButton(R.string.btn_execute_merge) { _, _ ->
                if (selectedFiles.size < 2) {
                    showTopToast(getString(R.string.msg_select_two_files))
                } else {
                    showMergeNameDialog(selectedFiles)
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showMergeNameDialog(selectedFiles: List<String>) {
        val editText = EditText(this).apply {
            setText("merged_${SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())}")
            selectAll()
        }
        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle(R.string.label_merge_new_name)
            .setView(editText)
            .setPositiveButton("実行") { _, _ ->
                val newName = editText.text.toString()
                if (isValidFileName(newName)) {
                    performMerge(selectedFiles, newName)
                } else {
                    showTopToast("ファイル名が無効です")
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun performMerge(selectedFiles: List<String>, newName: String) {
        thread {
            try {
                val allRecords = mutableListOf<BattleRecord>()
                val gson = Gson()
                selectedFiles.forEach { fileName ->
                    val file = File(filesDir, "$fileName.json")
                    if (file.exists()) {
                        val json = file.readText()
                        val history = gson.fromJson(json, BattleHistory::class.java)
                        allRecords.addAll(history.records)
                    }
                }

                // 時系列（timestamp）でソート
                allRecords.sortBy { it.timestamp }

                val newHistory = BattleHistory(
                    totalWins = allRecords.count { it.result == "WIN" },
                    totalLosses = allRecords.count { it.result == "LOSE" },
                    records = allRecords.toMutableList()
                )

                val newFile = File(filesDir, "$newName.json")
                newFile.writeText(gson.toJson(newHistory))

                Handler(Looper.getMainLooper()).post {
                    showTopToast(getString(R.string.msg_merge_success, newName, allRecords.size), true)
                    saveCurrentFileName(newName)
                    refreshServiceAndUI()
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    showTopToast(getString(R.string.msg_merge_failed, e.message ?: "Unknown"))
                }
            }
        }
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

            val dm = dataManager.apply { currentFileName = csvFileName; resetHistory() }
            var importedCount = 0
            lines.drop(5).forEach { line ->
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
            showTopToast("「$csvFileName.json」として${importedCount}件をインポートしました", true)
        } catch (e: Exception) { showTopToast("インポートに失敗しました: ${e.message}", true) }
    }

    private fun showFileActionDialog(fileName: String) {
        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog).setTitle("ファイル操作: $fileName")
            .setItems(arrayOf("このファイルを使用する", "名前を変更する", "削除する", "CSVにエクスポート")) { _, idx ->
                when (idx) {
                    0 -> { saveCurrentFileName(fileName); showTopToast(getString(R.string.file_switched_toast, fileName)); refreshServiceAndUI() }
                    1 -> showRenameDialog(fileName); 2 -> showDeleteConfirmDialog(fileName); 3 -> exportHistoryToCsv(fileName)
                }
            }.show()
    }

    private fun exportHistoryToCsv(fileName: String) {
        val dm = dataManager.apply { loadHistory(fileName) }
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
        val container = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
            setBackgroundColor(0xFF222222.toInt()) // 背景をダークに固定
        }

        // キャプチャー画面の校正 見出し
        container.addView(TextView(this).apply { 
            text = "キャプチャー画面の校正"
            setPadding(0, 10, 0, 20) 
            textSize = 18f 
            setTextColor(Color.WHITE) 
            setTypeface(null, Typeface.BOLD) 
        })

        for (i in titles.indices) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 10, 0, 10); gravity = Gravity.CENTER_VERTICAL; isClickable = true; setBackgroundResource(android.R.drawable.list_selector_background) }
            val thumb = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(120, 120); scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF333333.toInt())
                val file = File(filesDir, fileNames[i]); if (file.exists()) setImageBitmap(BitmapFactory.decodeFile(file.absolutePath)) else setImageResource(android.R.drawable.ic_menu_gallery)
            }
            row.addView(thumb); row.addView(TextView(this).apply { text = titles[i]; setPadding(20, 0, 0, 0); textSize = 16f; setTextColor(0xFFFFFFFF.toInt()) })
            row.setOnClickListener {
                AlertDialog.Builder(this@MainActivity, R.style.Theme_NakamonRec_Dialog).setTitle(titles[i]).setItems(arrayOf("画像をインポート", "画像を削除", "校正を開始")) { _, idx ->
                    when (idx) {
                        0 -> { pendingCalibrationFileName = fileNames[i]; pickImageLauncher.launch("image/*") }
                        1 -> { if (File(filesDir, fileNames[i]).exists()) showDeleteImageConfirmDialog(fileNames[i]) }
                        2 -> { if (File(filesDir, fileNames[i]).exists()) startActivity(Intent(this@MainActivity, CalibrationActivity::class.java).apply { putExtra("EXTRA_MODE", modes[i]); putExtra("EXTRA_FILE_NAME", fileNames[i]) }) else showTopToast("先に画像をインポートしてください") }
                    }
                }.show()
            }
            container.addView(row)
        }

        // 解析モード設定の見出し
        container.addView(TextView(this).apply { 
            text = "モンスター識別の解析モード設定"
            setPadding(0, 40, 0, 20) 
            textSize = 18f 
            setTextColor(Color.WHITE) 
            setTypeface(null, Typeface.BOLD) 
        })
        
        // iOS の UserSettingsSheet と同じ Toggle (Switch) パターン:
        //   1 行目: 軽負荷モード切替 Switch
        //   2 行目: 対象モンスター (N 体) 一覧編集 (チェブロン付き行)
        val recIsActive = MediaCaptureService.isRunning

        // === Row 1: 軽負荷モード Switch ===
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 20)
        }
        val modeIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(120, 120)
            setImageResource(android.R.drawable.ic_lock_power_off)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(if (dataManager.analysisMode == "light") Color.parseColor("#F09199") else Color.GRAY)
        }
        val modeTextContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val modeStatusText = TextView(this).apply {
            text = "軽負荷モード (指定モンスターのみ)"
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        modeTextContainer.addView(modeStatusText)
        val modeSwitch = androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = dataManager.analysisMode == "light"
            isEnabled = !recIsActive
        }
        modeRow.addView(modeIcon)
        modeRow.addView(modeTextContainer)
        modeRow.addView(modeSwitch)

        // === Row 2: 対象モンスター一覧 (Picker) ===
        val pickerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 20, 0, 20)
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        val pickerIcon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(120, 120)
            setImageResource(android.R.drawable.ic_menu_manage)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(Color.GRAY)
        }
        val pickerLabel = TextView(this).apply {
            text = "対象モンスター (${dataManager.lightModeMonsters.size} 体)"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(20, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pickerChevron = TextView(this).apply {
            text = "›"
            textSize = 24f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 10, 0)
        }
        pickerRow.addView(pickerIcon)
        pickerRow.addView(pickerLabel)
        pickerRow.addView(pickerChevron)
        pickerRow.setOnClickListener {
            if (recIsActive) {
                android.widget.Toast.makeText(
                    this,
                    "REC 中は解析モードを変更できません。\nREC を停止してから変更してください。",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                showMonsterFilterDialog()
            }
        }

        // Switch change handler (REC active ガード)
        modeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (recIsActive) return@setOnCheckedChangeListener
            dataManager.analysisMode = if (isChecked) "light" else "normal"
            dataManager.saveAnalysisSettings()
            modeIcon.setColorFilter(if (isChecked) Color.parseColor("#F09199") else Color.GRAY)
            // 軽負荷 ON 時は対象モンスター行 (下の Picker) からユーザーが手動で開く
            // iOS と挙動を揃えるため、自動でフィルタダイアログは開かない
        }

        // REC 中表示: 両行をグレーアウト + 注意文
        if (recIsActive) {
            modeRow.alpha = 0.4f
            pickerRow.alpha = 0.4f
            val hintText = TextView(this).apply {
                text = "REC 中は解析モードを変更できません。REC を停止してから変更してください。"
                textSize = 12f
                setTextColor(Color.parseColor("#FF9090"))
                setPadding(0, 10, 0, 0)
            }
            container.addView(modeRow)
            container.addView(pickerRow)
            container.addView(hintText)
        } else {
            container.addView(modeRow)
            container.addView(pickerRow)
        }

        calibrationSelectorDialog = AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog).setTitle("ユーザー設定").setView(container).setNegativeButton("閉じる", null).show()
    }

    private fun showMonsterFilterDialog() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xFF222222.toInt()) // 背景をダークに固定
        }

        val countText = TextView(this).apply {
            text = "選択モンスター数：${dataManager.lightModeMonsters.size}体"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(20, 0, 0, 10)
        }
        rootLayout.addView(countText)

        val grid = GridView(this).apply {
            numColumns = 4
            horizontalSpacing = 10
            verticalSpacing = 10
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        rootLayout.addView(grid)
        
        val monsters = dataManager.monsterMaster
        val adapter = object : BaseAdapter() {
            override fun getCount() = monsters.size
            override fun getItem(p0: Int): MonsterData = monsters[p0]
            override fun getItemId(p0: Int) = p0.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val monster = getItem(position)
                // GridView の列幅で正方形になる FrameLayout (戦績画面のサムネと統一)
                val frame = object : FrameLayout(this@MainActivity) {
                    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                        super.onMeasure(widthSpec, widthSpec)
                    }
                }
                val img = ImageView(this@MainActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // 他のサムネと統一: 正方形 + CENTER_CROP + 6dp 角丸
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    alpha = if (dataManager.lightModeMonsters.contains(monster.name)) 1.0f else 0.3f
                    val cornerRadiusPx = 6 * resources.displayMetrics.density
                    outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                        }
                    }
                    clipToOutline = true
                    try {
                        assets.open("templates/${monster.fileName}").use { setImageBitmap(BitmapFactory.decodeStream(it)) }
                    } catch (_: Exception) {}
                }
                frame.addView(img)
                
                if (dataManager.lightModeMonsters.contains(monster.name)) {
                    val check = TextView(this@MainActivity).apply {
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { 
                            gravity = Gravity.BOTTOM or Gravity.END
                            setMargins(0, 0, 4, 4)
                        }
                        text = "✅"
                        textSize = 20f
                    }
                    frame.addView(check)
                }

                frame.setOnClickListener {
                    if (dataManager.lightModeMonsters.contains(monster.name)) {
                        dataManager.lightModeMonsters.remove(monster.name)
                    } else {
                        dataManager.lightModeMonsters.add(monster.name)
                    }
                    countText.text = "選択モンスター数：${dataManager.lightModeMonsters.size}体"
                    dataManager.saveAnalysisSettings()
                    notifyDataSetChanged()
                }
                return frame
            }
        }
        grid.adapter = adapter
        
        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle("軽負荷モード: 解析対象の選択")
            .setView(rootLayout)
            .setNeutralButton("デフォルト") { _, _ ->
                try {
                    val targetJson = assets.open("target_monsters.json").bufferedReader().use { it.readText() }
                    val defaultTargets = Gson().fromJson(targetJson, Array<MonsterData>::class.java)
                    dataManager.lightModeMonsters.clear()
                    dataManager.lightModeMonsters.addAll(defaultTargets.map { it.name })
                    dataManager.saveAnalysisSettings()
                    showMonsterFilterDialog() // ダイアログを再描画
                } catch (e: Exception) {
                    showTopToast("デフォルト値の読み込みに失敗しました")
                }
            }
            .setPositiveButton("確定", null)
            .show()
    }

    private fun showDeleteConfirmDialog(fileName: String) {
        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog).setTitle("ファイルの削除").setMessage("「$fileName」を削除しますか？")
            .setPositiveButton("削除") { _, _ -> if (File(filesDir, "$fileName.json").delete()) { showTopToast("削除しました"); if (getCurrentFileName() == fileName) saveCurrentFileName("default_record"); refreshServiceAndUI() } }.setNegativeButton("キャンセル", null).show()
    }

    private fun showResetHistoryConfirmDialog(fileName: String) {
        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog).setTitle("データのクリア").setMessage("「$fileName」の戦績データをすべて削除しますか？")
            .setPositiveButton("クリア") { _, _ -> val dm = dataManager; dm.loadHistory(fileName); dm.resetHistory(); if (MediaCaptureService.isRunning) startService(Intent(this, MediaCaptureService::class.java).apply { action = MediaCaptureService.ACTION_RELOAD_HISTORY }); showTopToast("データをクリアしました"); updateUI(MediaCaptureService.isRunning) }.setNegativeButton("キャンセル", null).show()
    }

    private fun refreshServiceAndUI() {
        updateUI(MediaCaptureService.isRunning); if (MediaCaptureService.isRunning) startService(Intent(this, MediaCaptureService::class.java).apply { action = MediaCaptureService.ACTION_RELOAD_HISTORY })
    }

    private fun showDeleteImageConfirmDialog(fileName: String) {
        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog).setTitle("画像を削除").setMessage("この画像を削除しますか？").setPositiveButton("削除") { _, _ -> if (File(filesDir, fileName).delete()) { showTopToast("削除しました"); calibrationSelectorDialog?.dismiss(); showCalibrationSelectorDialog() } }.setNegativeButton("キャンセル", null).show()
    }

    private fun importImageForCalibration(uri: Uri, destFileName: String): Boolean {
        return try { contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(File(filesDir, destFileName)).use { output -> input.copyTo(output) } }; true } catch (_: Exception) { false }
    }

    private fun showReadmeDialog() {
        val scrollView = ScrollView(this); val textView = TextView(this).apply { text = getString(R.string.readme_content); textSize = 13f; setPadding(60, 40, 60, 40); setLineSpacing(0f, 1.2f); setTextColor("#CCCCCC".toColorInt()) }
        scrollView.addView(textView); AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog).setTitle(R.string.readme_title).setView(scrollView).setPositiveButton("閉じる", null).show()
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
        val stats = dataManager.apply { loadHistory(currentName) }.getStatistics()
        binding.valTotalRateMain.text = String.format(Locale.US, "%.1f%%", stats.winRate); binding.valTotalCountMain.text = getString(R.string.label_matches_format, stats.totalWins + stats.totalLosses)
        // W は recCoral 系 (REC ボタン色)、L は sideEnemy 系 (STOP ボタン色) で色分けして
        // 数字を見なくても勝敗バランスが分かるようにする (iOS 版と挙動を揃える)
        val winText = "${stats.totalWins}W"
        val loseText = "${stats.totalLosses}L"
        val combined = "$winText - $loseText"
        val span = android.text.SpannableString(combined)
        span.setSpan(android.text.style.ForegroundColorSpan("#F09199".toColorInt()),
                     0, winText.length,
                     android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(android.text.style.ForegroundColorSpan("#90D7EC".toColorInt()),
                     combined.length - loseText.length, combined.length,
                     android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.valTotalWinLoseMain.text = span
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
        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle("新規ファイル名を入力")
            .setView(editText)
            .setPositiveButton("作成") { _, _ ->
                val newName = editText.text.toString()
                if (isValidFileName(newName)) {
                    val file = File(filesDir, "$newName.json")
                    if (file.exists()) {
                        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
                            .setTitle("ファイルが既に存在します")
                            .setMessage("「$newName」は既に存在します。上書きして戦績をリセットしますか？")
                            .setPositiveButton("上書き") { _, _ ->
                                createNewFile(newName)
                            }
                            .setNegativeButton("キャンセル", null)
                            .show()
                    } else {
                        createNewFile(newName)
                    }
                } else {
                    showTopToast("ファイル名が無効です")
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun createNewFile(name: String) {
        dataManager.apply {
            currentFileName = name
            resetHistory()
        }
        saveCurrentFileName(name)
        refreshServiceAndUI()
        showTopToast("「$name」を作成しました")
    }

    private fun showRenameDialog(oldName: String) {
        val editText = EditText(this).apply { setText(oldName); selectAll() }
        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle("新しい名前を入力")
            .setView(editText)
            .setPositiveButton("変更") { _, _ ->
                val newName = editText.text.toString()
                if (isValidFileName(newName) && newName != oldName) {
                    val newFile = File(filesDir, "$newName.json")
                    if (newFile.exists()) {
                        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
                            .setTitle("ファイルが既に存在します")
                            .setMessage("「$newName」は既に存在します。上書き（既存のデータを消去して置換）しますか？")
                            .setPositiveButton("上書き") { _, _ ->
                                performRename(oldName, newName)
                            }
                            .setNegativeButton("キャンセル", null)
                            .show()
                    } else {
                        performRename(oldName, newName)
                    }
                } else if (newName != oldName) {
                    showTopToast("ファイル名が無効です")
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun performRename(oldName: String, newName: String) {
        val oldFile = File(filesDir, "$oldName.json")
        val newFile = File(filesDir, "$newName.json")
        
        // 上書きの場合は先に削除
        if (newFile.exists()) {
            newFile.delete()
        }
        
        if (oldFile.renameTo(newFile)) {
            if (getCurrentFileName() == oldName) {
                saveCurrentFileName(newName)
            }
            showTopToast("名前を変更しました")
            refreshServiceAndUI()
        } else {
            showTopToast("変更に失敗しました")
        }
    }

    private fun showDeveloperMenu(currentVersion: String) {
        val suppressedVersion = getSuppressedUpdateVersion()
        val reEnableLabel = if (suppressedVersion != null) {
            "アップデート通知を再有効化 ($suppressedVersion を抑止中)"
        } else {
            "アップデート通知を再有効化 (抑止なし)"
        }
        val options = arrayOf(
            "直近の解析ログ",
            "アプリのアップデートを確認",
            reEnableLabel,
            "マッチング閾値を調整"
        )
        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle("デバッグメニュー")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showFlightLog()
                    1 -> {
                        showTopToast(getString(R.string.msg_checking_update))
                        checkForUpdates(currentVersion, isManual = true)
                    }
                    2 -> {
                        if (suppressedVersion != null) {
                            clearSuppressedUpdateVersion()
                            showTopToast("アップデート通知を再有効化しました")
                        } else {
                            showTopToast("現在抑止されている通知はありません")
                        }
                    }
                    3 -> showThresholdAdjustDialog()
                }
            }
            .show()
    }

    /**
     * マッチング閾値 (VS / WIN / LOSE) を 0.4〜0.8 の範囲で調整するダイアログ。
     * 設定保存時に SharedPreferences に永続化 + 起動中サービスに ACTION_RELOAD_SETTINGS を送信。
     */
    private fun showThresholdAdjustDialog() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val min = BattleAnalyzer.THRESHOLD_MIN
        val max = BattleAnalyzer.THRESHOLD_MAX
        val step = 0.05
        val steps = ((max - min) / step).toInt() // 8 (0.40〜0.80 を 0.05 刻み)

        fun toValue(progress: Int): Double = (min + progress * step).let {
            // floating-point 誤差吸収
            Math.round(it * 100) / 100.0
        }
        fun toProgress(value: Double): Int = ((value - min) / step).toInt().coerceIn(0, steps)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
        }

        val intro = TextView(this).apply {
            text = "VS / WIN / LOSE 検知の閾値を調整します ($min ～ $max)。\n値を上げると誤検知が減りますが、本来の検知も逃しやすくなります。"
            textSize = 11f
            setTextColor(android.graphics.Color.LTGRAY)
        }
        container.addView(intro)

        data class Row(val label: String, val initial: Double) {
            lateinit var valueLabel: TextView
            lateinit var seekBar: SeekBar
            var value: Double = initial
        }

        val rows = listOf(
            Row("VS 検知", dataManager.vsThreshold),
            Row("WIN 検知", dataManager.winThreshold),
            Row("LOSE 検知", dataManager.loseThreshold)
        )

        rows.forEach { row ->
            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val mt = (16 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = mt }
            }
            val labelText = TextView(this).apply {
                text = row.label
                textSize = 13f
                setTextColor(android.graphics.Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val valueLabel = TextView(this).apply {
                text = String.format(Locale.US, "%.2f", row.initial)
                textSize = 13f
                setTextColor(android.graphics.Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            }
            row.valueLabel = valueLabel
            labelRow.addView(labelText)
            labelRow.addView(valueLabel)
            container.addView(labelRow)

            val seek = SeekBar(this).apply {
                this.max = steps
                progress = toProgress(row.initial)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = toValue(p)
                        row.value = v
                        row.valueLabel.text = String.format(Locale.US, "%.2f", v)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            row.seekBar = seek
            container.addView(seek)
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle("マッチング閾値を調整")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                dataManager.saveThresholds(rows[0].value, rows[1].value, rows[2].value)
                if (MediaCaptureService.isRunning) {
                    val intent = Intent(this, MediaCaptureService::class.java).apply {
                        action = MediaCaptureService.ACTION_RELOAD_SETTINGS
                    }
                    startService(intent)
                }
                showTopToast(String.format(
                    Locale.US,
                    "閾値を保存: VS=%.2f WIN=%.2f LOSE=%.2f",
                    rows[0].value, rows[1].value, rows[2].value
                ))
            }
            .setNeutralButton("デフォルトに戻す", null) // OnShowListener で動作上書き
            .setNegativeButton(getString(R.string.btn_close), null)
            .create()

        dialog.setOnShowListener {
            // Neutral ボタンはタップしても dismiss させず、SeekBar をデフォルトに戻すだけ
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val defaults = listOf(
                    BattleAnalyzer.DEFAULT_VS_THRESHOLD,
                    BattleAnalyzer.DEFAULT_WIN_THRESHOLD,
                    BattleAnalyzer.DEFAULT_LOSE_THRESHOLD
                )
                rows.zip(defaults).forEach { (row, def) ->
                    row.value = def
                    row.seekBar.progress = toProgress(def)
                    row.valueLabel.text = String.format(Locale.US, "%.2f", def)
                }
            }
        }
        dialog.show()
    }

    private fun showFlightLog() {
        val latestLog = dataManager.readFlightLog()
        val previousLog = dataManager.readPreviousFlightLog()
        
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(android.graphics.Color.parseColor("#111111"))
        }

        fun addSection(title: String, body: String) {
            val titleView = android.widget.TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(0, 20, 0, 10)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            val bodyView = android.widget.TextView(this).apply {
                text = body
                textSize = 11f
                setPadding(20, 20, 20, 20)
                setBackgroundColor(android.graphics.Color.parseColor("#222222"))
                setTextColor(android.graphics.Color.LTGRAY)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            container.addView(titleView)
            container.addView(bodyView)
        }

        addSection("📋 最新のログ", latestLog)
        if (previousLog != null) {
            addSection("🗂 前回のログ", previousLog)
        }

        val scrollView = android.widget.ScrollView(this).apply {
            addView(container)
        }

        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle("解析ログ")
            .setView(scrollView)
            .setPositiveButton("閉じる", null)
            .setNeutralButton("クリア") { _, _ ->
                dataManager.clearFlightLog()
            }
            .show()
    }

    private fun showTopToast(message: String, isLong: Boolean = false) {
        val duration = if (isLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        val toast = Toast.makeText(this, message, duration)
        // 上部に表示 (ステータスバーを避けるため yOffset を 200程度に設定)
        toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 200)
        toast.show()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
