package com.android.nakamonrec

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.nakamonrec.databinding.ActivityHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private lateinit var dataManager: BattleDataManager
    private var filterPartyIndex: Int = -1 // -1: All, 0: P1, 1: P2, 2: P3
    private var filterResult: String? = null // null: All, "WIN", "LOSE"
    private val filterMonsters = mutableListOf<String>()
    private var isFilterMode = false
    
    private lateinit var historyAdapter: BattleHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_history)

        val prefs = getSharedPreferences("NakamonPrefs", MODE_PRIVATE)
        val currentFile = prefs.getString("last_file_name", "default_record") ?: "default_record"

        dataManager = BattleDataManager(this)
        dataManager.loadHistory(currentFile)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAnalyze.setOnClickListener { showAnalysisDialog() }

        // モード切替ボタンの初期化 (最初はEditモードなのでFilterアイコンを出す)
        binding.btnModeToggle.text = ""
        binding.btnModeToggle.setIconResource(android.R.drawable.ic_menu_sort_by_size)
        binding.btnModeToggle.setOnClickListener { toggleMode() }

        // フィルタ解除ボタン
        binding.btnClearFilter.setOnClickListener {
            filterMonsters.clear()
            filterResult = null
            updateFilterStatusUI()
            setupUI()
        }

        // 既存のパーティフィルタ
        binding.cardTotal.setOnClickListener { setFilter(-1) }
        binding.cardP1.setOnClickListener { setFilter(0) }
        binding.cardP2.setOnClickListener { setFilter(1) }
        binding.cardP3.setOnClickListener { setFilter(2) }

        initRecyclerView()
        setupUI()
    }

    private fun initRecyclerView() {
        historyAdapter = BattleHistoryAdapter(
            mutableListOf(),
            dataManager.monsterMaster,
            onLongClick = { position ->
                val allRecords = dataManager.history.records
                val filtered = getFilteredRecords(allRecords)
                val realIndex = allRecords.indexOf(filtered[position])
                showEditRecordDialog(realIndex)
            },
            onResultClick = { showResultFilterDialog() },
            onMonsterClick = { monsterName -> addMonsterFilter(monsterName) }
        )
        binding.recyclerViewHistory.apply {
            val lm = LinearLayoutManager(this@HistoryActivity)
            lm.reverseLayout = true
            lm.stackFromEnd = true
            layoutManager = lm
            adapter = historyAdapter
        }
    }

    private fun toggleMode() {
        isFilterMode = !isFilterMode
        historyAdapter.isFilterMode = isFilterMode
        
        // ボタンには「次に切り替わるモード」のアイコンを表示する
        if (isFilterMode) {
            binding.btnModeToggle.setIconResource(android.R.drawable.ic_menu_edit)
        } else {
            binding.btnModeToggle.setIconResource(android.R.drawable.ic_menu_sort_by_size)
        }
        // モード切替時に統計表示を更新
        setupUI()
    }

    private fun addMonsterFilter(name: String) {
        if (filterMonsters.contains(name)) return
        if (filterMonsters.size >= 4) return
        
        filterMonsters.add(name)
        updateFilterStatusUI()
        setupUI()
    }

    private fun updateFilterStatusUI() {
        if (filterMonsters.isEmpty() && filterResult == null) {
            binding.layoutFilterStatus.visibility = View.GONE
            return
        }
        binding.layoutFilterStatus.visibility = View.VISIBLE
        binding.layoutFilterChips.removeAllViews()
        
        // 勝敗フィルタの状態もチップとして表示
        filterResult?.let { result ->
            val textView = TextView(this).apply {
                text = if (result == "WIN") "WIN" else "LOSE"
                setTextColor(Color.YELLOW)
                textSize = 10f
                setBackgroundResource(R.drawable.bg_filter_bar)
                setPadding(16, 4, 16, 4)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(4, 0, 4, 0)
                layoutParams = params
                setOnClickListener {
                    filterResult = null
                    updateFilterStatusUI()
                    setupUI()
                }
            }
            binding.layoutFilterChips.addView(textView)
        }

        filterMonsters.forEach { name ->
            val textView = TextView(this).apply {
                text = name
                setTextColor(Color.WHITE)
                textSize = 10f
                setBackgroundResource(R.drawable.bg_filter_bar) // 簡易的な背景
                setPadding(16, 4, 16, 4)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(4, 0, 4, 0)
                layoutParams = params
                setOnClickListener {
                    filterMonsters.remove(name)
                    updateFilterStatusUI()
                    setupUI()
                }
            }
            binding.layoutFilterChips.addView(textView)
        }
    }

    private fun showResultFilterDialog() {
        val items = arrayOf(getString(R.string.filter_all), getString(R.string.filter_win_only), getString(R.string.filter_lose_only))
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_result_filter)
            .setItems(items) { _, which ->
                filterResult = when (which) {
                    1 -> "WIN"
                    2 -> "LOSE"
                    else -> null
                }
                updateFilterStatusUI()
                setupUI()
            }
            .show()
    }

    private fun setFilter(index: Int) {
        if (filterPartyIndex == index) return
        val transition = AutoTransition().apply { duration = 250 }
        TransitionManager.beginDelayedTransition(binding.root as ViewGroup, transition)
        
        filterPartyIndex = index
        setupUI()
    }

    private fun getFilteredRecords(allRecords: List<BattleRecord>): List<BattleRecord> {
        return allRecords.filter { record ->
            val matchParty = filterPartyIndex == -1 || record.partyIndex == filterPartyIndex
            val matchResult = filterResult == null || record.result == filterResult
            val matchMonsters = if (filterMonsters.isEmpty()) true else {
                filterMonsters.all { fName -> record.enemyParty.contains(fName) }
            }
            matchParty && matchResult && matchMonsters
        }
    }

    private fun setupUI() {
        val allRecords = dataManager.history.records
        
        // --- 1. 左上カード (TOTAL / FILTER) の更新 ---
        if (isFilterMode) {
            // フィルタモード：フィルタ条件（勝敗・モンスター）に一致する「全パーティ合計」の統計
            val filteredTotalRecords = allRecords.filter { record ->
                (filterResult == null || record.result == filterResult) &&
                (filterMonsters.isEmpty() || filterMonsters.all { it in record.enemyParty })
            }
            val wins = filteredTotalRecords.count { it.result == "WIN" }
            val losses = filteredTotalRecords.count { it.result == "LOSE" }
            val totalCount = wins + losses
            val rate = if (totalCount > 0) (wins.toDouble() / totalCount * 100.0) else 0.0

            binding.textTotalLabel.text = getString(R.string.label_filter_win_rate)
            binding.valTotalRate.text = String.format(Locale.US, "%.1f%%", rate)
            binding.valTotalCount.text = getString(R.string.label_matches_format, totalCount)
            binding.valTotalWinLose.text = getString(R.string.label_win_lose_format, wins, losses)
        } else {
            // 編集モード：常に絶対的な全体統計を表示
            val stats = dataManager.getStatistics()
            binding.textTotalLabel.text = getString(R.string.label_total_win_rate)
            binding.valTotalRate.text = String.format(Locale.US, "%.1f%%", stats.winRate)
            binding.valTotalCount.text = getString(R.string.label_matches_format, stats.totalWins + stats.totalLosses)
            binding.valTotalWinLose.text = getString(R.string.label_win_lose_format, stats.totalWins, stats.totalLosses)
        }

        // --- 2. 各パーティカードの更新 ---
        val globalStats = dataManager.getStatistics()
        for (i in 0..2) {
            val partyRecords = allRecords.filter { it.partyIndex == i }
            
            val (wins, losses, rate) = if (isFilterMode) {
                // フィルタモード：このパーティのうち、条件に一致するものを抽出
                val filtered = partyRecords.filter { record ->
                    (filterResult == null || record.result == filterResult) &&
                    (filterMonsters.isEmpty() || filterMonsters.all { it in record.enemyParty })
                }
                val w = filtered.count { it.result == "WIN" }
                val l = filtered.count { it.result == "LOSE" }
                val t = w + l
                val r = if (t > 0) (w.toDouble() / t * 100.0) else 0.0
                Triple(w, l, r)
            } else {
                // 編集モード：このパーティの全期間戦績
                val w = partyRecords.count { it.result == "WIN" }
                val l = partyRecords.count { it.result == "LOSE" }
                val t = w + l
                val r = if (t > 0) (w.toDouble() / t * 100.0) else 0.0
                Triple(w, l, r)
            }

            val rateStr = String.format(Locale.US, "%.1f%%", rate)
            val winLoseStr = getString(R.string.label_win_lose_format, wins, losses)
            val partyStat = globalStats.partyStats.find { it.index == i }
            val usageRateStr = getString(R.string.label_usage_short_format, String.format(Locale.US, "%.1f%%", partyStat?.usageRate ?: 0.0))

            val latestRecord = allRecords.lastOrNull { it.partyIndex == i }
            val myParty = latestRecord?.myParty ?: listOf("", "", "", "")

            when (i) {
                0 -> {
                    binding.valP1Rate.text = rateStr
                    binding.valP1Usage.text = usageRateStr
                    binding.valP1WinLoseShort.text = winLoseStr
                    updatePartyIcons(myParty, listOf(binding.imgP1M1, binding.imgP1M2, binding.imgP1M3, binding.imgP1M4))
                }
                1 -> {
                    binding.valP2Rate.text = rateStr
                    binding.valP2Usage.text = usageRateStr
                    binding.valP2WinLoseShort.text = winLoseStr
                    updatePartyIcons(myParty, listOf(binding.imgP2M1, binding.imgP2M2, binding.imgP2M3, binding.imgP2M4))
                }
                2 -> {
                    binding.valP3Rate.text = rateStr
                    binding.valP3Usage.text = usageRateStr
                    binding.valP3WinLoseShort.text = winLoseStr
                    updatePartyIcons(myParty, listOf(binding.imgP3M1, binding.imgP3M2, binding.imgP3M3, binding.imgP3M4))
                }
            }
        }

        // --- 3. リストとグラフの更新 ---
        val filteredRecordsForList = getFilteredRecords(allRecords)
        updateTrendsGraph(filteredRecordsForList)
        historyAdapter.updateData(filteredRecordsForList)
        updateCardSelectionUI()
    }

    private fun updateTrendsGraph(targetRecords: List<BattleRecord>) {
        if (targetRecords.size < 2) {
            binding.winRateGraph.visibility = View.INVISIBLE
            return
        }
        binding.winRateGraph.visibility = View.VISIBLE
        val movingRates = mutableListOf<Double>()
        val windowSize = 20
        targetRecords.forEachIndexed { i, _ ->
            val start = (i - windowSize + 1).coerceAtLeast(0)
            val subList = targetRecords.subList(start, i + 1)
            val wins = subList.count { it.result == "WIN" }
            movingRates.add((wins.toDouble() / subList.size) * 100.0)
        }
        binding.winRateGraph.setData(movingRates)
    }

    private fun updateCardSelectionUI() {
        setCardStyle(binding.cardTotal, filterPartyIndex == -1)
        setCardStyle(binding.cardP1, filterPartyIndex == 0)
        setCardStyle(binding.cardP2, filterPartyIndex == 1)
        setCardStyle(binding.cardP3, filterPartyIndex == 2)
    }

    private fun setCardStyle(card: MaterialCardView, isSelected: Boolean) {
        val density = resources.displayMetrics.density
        if (isSelected) {
            // 選択時：青色の太枠(3dp)とわずかに拡大
            card.strokeWidth = (3f * density).toInt()
            card.strokeColor = "#AAAAAA".toColorInt()
            card.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).start()
            card.cardElevation = 12f * density
        } else {
            card.strokeWidth = 0
            card.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
            card.cardElevation = 4f * density
        }
    }

    private fun updatePartyIcons(partyNames: List<String>, imageViews: List<ImageView>) {
        imageViews.forEachIndexed { i, imageView ->
            val monsterName = partyNames.getOrNull(i) ?: ""
            val monsterData = dataManager.monsterMaster.find { it.name == monsterName }
            if (monsterData != null) {
                try {
                    assets.open("templates/${monsterData.fileName}").use {
                        imageView.setImageBitmap(BitmapFactory.decodeStream(it))
                        imageView.visibility = View.VISIBLE
                    }
                } catch (_: Exception) {
                    imageView.setImageResource(android.R.drawable.ic_menu_help)
                    imageView.visibility = View.VISIBLE
                }
            } else {
                imageView.setImageDrawable(null)
                imageView.visibility = View.INVISIBLE
            }
        }
    }

    private fun showAnalysisDialog() {
        val allRecords = dataManager.history.records
        val filteredRecords = getFilteredRecords(allRecords)
        if (filteredRecords.isEmpty()) return
        
        val appearanceCount = mutableMapOf<String, Int>()
        val winAgainstCount = mutableMapOf<String, Int>()
        filteredRecords.forEach { record ->
            record.enemyParty.filter { it.isNotEmpty() }.distinct().forEach { name ->
                appearanceCount[name] = appearanceCount.getOrDefault(name, 0) + 1
                if (record.result == "WIN") winAgainstCount[name] = winAgainstCount.getOrDefault(name, 0) + 1
            }
        }
        val totalCount = filteredRecords.size
        val rankingList = appearanceCount.map { (name, count) ->
            val wins = winAgainstCount.getOrDefault(name, 0)
            val winRate = if (count > 0) (wins.toDouble() / count * 100) else 0.0
            val appearanceRate = (count.toDouble() / totalCount * 100)
            MonsterRankData(name, count, appearanceRate, winRate)
        }.sortedByDescending { it.count }.toMutableList()

        val listView = ListView(this)
        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = rankingList.size
            override fun getItem(position: Int): Any = rankingList[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_monster_ranking, parent, false)
                val data = rankingList[position]
                view.findViewById<TextView>(R.id.textRank).text = String.format(Locale.US, "%d", position + 1)
                view.findViewById<TextView>(R.id.textMonsterName).text = data.name
                view.findViewById<TextView>(R.id.textAppearance).text = getString(R.string.rank_appearance_format, data.count, String.format(Locale.US, "%.1f", data.appearanceRate))
                val winRateTextView = view.findViewById<TextView>(R.id.textWinRate)
                winRateTextView.text = getString(R.string.rank_win_rate_format, String.format(Locale.US, "%.1f", data.winRate))
                when {
                    data.winRate >= 80.0 -> winRateTextView.setTextColor("#F09199".toColorInt())
                    data.winRate >= 50.0 -> winRateTextView.setTextColor("#CCCCCC".toColorInt())
                    else -> winRateTextView.setTextColor("#90D7EC".toColorInt())
                }
                val imageView = view.findViewById<ImageView>(R.id.imageMonster)
                val monsterData = dataManager.monsterMaster.find { it.name == data.name }
                if (monsterData != null) {
                    try {
                        assets.open("templates/${monsterData.fileName}").use { imageView.setImageBitmap(BitmapFactory.decodeStream(it)) }
                    } catch (_: Exception) { imageView.setImageResource(android.R.drawable.ic_menu_help) }
                } else {
                    imageView.setImageResource(android.R.drawable.ic_menu_help)
                }
                return view
            }
        }
        listView.adapter = adapter
        val titlePrefix = if (filterPartyIndex == -1) getString(R.string.analysis_label_all) else getString(R.string.analysis_label_party_format, filterPartyIndex + 1)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.analysis_title_appearance_format, titlePrefix))
            .setView(listView)
            .setPositiveButton(R.string.btn_close, null)
            .setNeutralButton(R.string.btn_sort_win_rate_worst, null)
            .create()
        dialog.setOnShowListener {
            val sortButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            var sortedByAppearance = true
            sortButton.setOnClickListener {
                if (sortedByAppearance) {
                    rankingList.sortBy { it.winRate }
                    sortButton.text = getString(R.string.btn_sort_appearance)
                    dialog.setTitle(getString(R.string.analysis_title_win_rate_worst_format, titlePrefix))
                } else {
                    rankingList.sortByDescending { it.count }
                    sortButton.text = getString(R.string.btn_sort_win_rate_worst)
                    dialog.setTitle(getString(R.string.analysis_title_appearance_format, titlePrefix))
                }
                sortedByAppearance = !sortedByAppearance
                adapter.notifyDataSetChanged()
            }
        }
        dialog.show()
    }

    data class MonsterRankData(val name: String, val count: Int, val appearanceRate: Double, val winRate: Double)

    private fun showEditRecordDialog(position: Int) {
        val record = dataManager.history.records[position]
        val options = arrayOf(
            getString(R.string.edit_option_toggle_result, if (record.result == "WIN") "→LOSE" else "→WIN"),
            getString(R.string.edit_option_change_party, record.partyIndex + 1),
            getString(R.string.edit_option_change_monsters),
            getString(R.string.edit_option_delete),
            getString(R.string.edit_option_insert_after)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_record_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> toggleResult(position)
                    1 -> showPartyEditSelector(position)
                    2 -> showMonsterEditSelector(position)
                    3 -> deleteRecord(position)
                    4 -> insertRecordAfter(position)
                }
            }
            .show()
    }

    private fun showPartyEditSelector(recordPos: Int) {
        val parties = arrayOf(
            getString(R.string.label_party_name_format, 1),
            getString(R.string.label_party_name_format, 2),
            getString(R.string.label_party_name_format, 3)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_party_title)
            .setItems(parties) { _, which ->
                val record = dataManager.history.records[recordPos]
                updateAndSave(recordPos, record.copy(partyIndex = which))
            }
            .show()
    }

    private fun showMonsterEditSelector(recordPos: Int) {
        val record = dataManager.history.records[recordPos]
        val scroll = HorizontalScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 20, 10, 20)
            gravity = Gravity.CENTER
        }
        scroll.addView(container)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.edit_monster_title)
            .setView(scroll)
            .create()
        val allMonsters = record.myParty + record.enemyParty
        allMonsters.forEachIndexed { index, name ->
            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(100, 100).apply { setMargins(4, 0, 4, 0) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(if (index < 4) android.R.drawable.editbox_dropdown_light_frame else android.R.drawable.editbox_dropdown_dark_frame)
                val monsterData = dataManager.monsterMaster.find { it.name == name }
                if (monsterData != null) {
                    try {
                        assets.open("templates/${monsterData.fileName}").use {
                            setImageBitmap(BitmapFactory.decodeStream(it))
                        }
                    } catch (_: Exception) {
                        setImageResource(android.R.drawable.ic_menu_help)
                    }
                }
                setOnClickListener {
                    // ダイアログを閉じないように変更
                    val isMyParty = index < 4
                    val monsterIndex = if (isMyParty) index else index - 4
                    showMonsterPicker { selectedName ->
                        val currentRecord = dataManager.history.records[recordPos]
                        val newMyParty = currentRecord.myParty.toMutableList()
                        val newEnemyParty = currentRecord.enemyParty.toMutableList()
                        if (isMyParty) newMyParty[monsterIndex] = selectedName
                        else newEnemyParty[monsterIndex] = selectedName
                        
                        val updatedRecord = currentRecord.copy(myParty = newMyParty, enemyParty = newEnemyParty)
                        updateAndSave(recordPos, updatedRecord)
                        
                        // アイコンを即座に更新（ダイアログを閉じずに連続編集を可能にする）
                        val monsterData = dataManager.monsterMaster.find { it.name == selectedName }
                        if (monsterData != null) {
                            try {
                                assets.open("templates/${monsterData.fileName}").use {
                                    setImageBitmap(BitmapFactory.decodeStream(it))
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
            if (index == 4) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(2, 60).apply { setMargins(10, 0, 10, 0) }
                    setBackgroundColor(Color.GRAY)
                }
                container.addView(divider)
            }
            container.addView(imageView)
        }
        dialog.show()
    }

    private fun showMonsterPicker(onSelected: (String) -> Unit) {
        val gridView = GridView(this).apply {
            numColumns = 4
            setPadding(20, 20, 20, 20)
            verticalSpacing = 24
            horizontalSpacing = 24
            adapter = object : BaseAdapter() {
                override fun getCount(): Int = dataManager.monsterMaster.size
                override fun getItem(position: Int): Any = dataManager.monsterMaster[position]
                override fun getItemId(position: Int): Long = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val imageView = (convertView as? ImageView) ?: ImageView(this@HistoryActivity).apply {
                        layoutParams = android.widget.AbsListView.LayoutParams(150, 150)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setPadding(4, 4, 4, 4)
                    }
                    val monster = dataManager.monsterMaster[position]
                    try {
                        assets.open("templates/${monster.fileName}").use {
                            imageView.setImageBitmap(BitmapFactory.decodeStream(it))
                        }
                    } catch (_: Exception) {
                        imageView.setImageResource(android.R.drawable.ic_menu_help)
                    }
                    return imageView
                }
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.picker_monster_title)
            .setView(gridView)
            .create()
        gridView.setOnItemClickListener { _, _, position, _ ->
            onSelected(dataManager.monsterMaster[position].name)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun updateAndSave(position: Int, newRecord: BattleRecord) {
        dataManager.history.records[position] = newRecord
        dataManager.saveHistory()
        if (MediaCaptureService.isRunning) {
            val intent = Intent(this, MediaCaptureService::class.java).apply {
                action = MediaCaptureService.ACTION_RELOAD_HISTORY
            }
            startService(intent)
        }
        setupUI()
    }

    private fun toggleResult(position: Int) {
        val record = dataManager.history.records[position]
        val newResult = if (record.result == "WIN") "LOSE" else "WIN"
        if (newResult == "WIN") {
            dataManager.history.totalWins++; dataManager.history.totalLosses--
        } else {
            dataManager.history.totalWins--; dataManager.history.totalLosses++
        }
        updateAndSave(position, record.copy(result = newResult))
    }

    private fun deleteRecord(position: Int) {
        val record = dataManager.history.records[position]
        if (record.result == "WIN") dataManager.history.totalWins-- else dataManager.history.totalLosses--
        dataManager.history.records.removeAt(position)
        dataManager.saveHistory()
        if (MediaCaptureService.isRunning) {
            val intent = Intent(this, MediaCaptureService::class.java).apply {
                action = MediaCaptureService.ACTION_RELOAD_HISTORY
            }
            startService(intent)
        }
        setupUI()
    }

    private fun insertRecordAfter(position: Int) {
        val parties = arrayOf(
            getString(R.string.label_party_name_format, 1),
            getString(R.string.label_party_name_format, 2),
            getString(R.string.label_party_name_format, 3)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_party_title)
            .setItems(parties) { _, partyIndex ->
                // 最新の履歴から、選択されたパーティIndexに合致する自パーティ構成を探す
                val latestPartyForIndex = dataManager.history.records
                    .filter { it.partyIndex == partyIndex }
                    .maxByOrNull { it.timestamp }
                    ?.myParty ?: listOf("?", "?", "?", "?")

                val newRecord = BattleRecord(
                    timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                    result = "WIN",
                    partyIndex = partyIndex,
                    myParty = latestPartyForIndex,
                    enemyParty = listOf("?", "?", "?", "?")
                )
                
                dataManager.history.records.add(position + 1, newRecord)
                dataManager.history.totalWins++
                dataManager.saveHistory()
                
                if (MediaCaptureService.isRunning) {
                    val intent = Intent(this, MediaCaptureService::class.java).apply {
                        action = MediaCaptureService.ACTION_RELOAD_HISTORY
                    }
                    startService(intent)
                }
                setupUI()
                // 追加した位置までスムーズにスクロールさせる
                binding.recyclerViewHistory.smoothScrollToPosition(position + 1)
                
                // 追加したレコードの編集ダイアログを自動で開く
                showEditRecordDialog(position + 1)
            }
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
