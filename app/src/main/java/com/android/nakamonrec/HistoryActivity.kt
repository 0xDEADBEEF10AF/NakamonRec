package com.android.nakamonrec

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
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
    private val filterEnemyMonsters = mutableListOf<String>()
    private val filterMyMonsters = mutableListOf<String>()
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

        binding.btnModeToggle.text = ""
        binding.btnModeToggle.setIconResource(android.R.drawable.ic_menu_sort_by_size)
        binding.btnModeToggle.setOnClickListener { toggleMode() }

        binding.btnClearFilter.setOnClickListener {
            filterEnemyMonsters.clear()
            filterMyMonsters.clear()
            filterResult = null
            updateFilterStatusUI()
            setupUI()
        }

        binding.cardTotal.setOnClickListener { setFilter(-1) }
        binding.cardP1.setOnClickListener { setFilter(0) }
        binding.cardP2.setOnClickListener { setFilter(1) }
        binding.cardP3.setOnClickListener { setFilter(2) }

        initRecyclerView()
        setupUI()
    }

    private fun initRecyclerView() {
        binding.winRateGraph.visibleCount = 20 // 20戦分を表示
        binding.winRateGraph.isDynamicScale = true // ダイナミックスケールを有効化
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
            onMonsterClick = { name, isEnemy ->
                addMonsterFilter(name, isEnemy)
            }
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
        
        if (isFilterMode) {
            binding.btnModeToggle.setIconResource(android.R.drawable.ic_menu_edit)
        } else {
            binding.btnModeToggle.setIconResource(android.R.drawable.ic_menu_sort_by_size)
        }
        setupUI()
    }

    private fun addMonsterFilter(name: String, isEnemy: Boolean) {
        val list = if (isEnemy) filterEnemyMonsters else filterMyMonsters
        if (list.contains(name)) return
        if (list.size >= 4) return
        
        list.add(name)
        updateFilterStatusUI()
        setupUI()
    }

    private fun updateFilterStatusUI() {
        if (filterEnemyMonsters.isEmpty() && filterMyMonsters.isEmpty() && filterResult == null) {
            binding.layoutFilterStatus.visibility = View.GONE
            historyAdapter.filterMyMonsters = listOf()
            historyAdapter.filterEnemyMonsters = listOf()
            historyAdapter.notifyItemRangeChanged(0, historyAdapter.itemCount)
            return
        }
        binding.layoutFilterStatus.visibility = View.VISIBLE
        binding.chipGroupFilters.removeAllViews()
        
        filterResult?.let { result ->
            val color = if (result == "WIN") "#F09199".toColorInt() else "#90D7EC".toColorInt()
            addFilterChip(if (result == "WIN") "WIN" else "LOSE", color) {
                filterResult = null
                updateFilterStatusUI()
                setupUI()
            }
        }

        filterMyMonsters.forEach { name -> addMonsterFilterChip(name, false) }
        filterEnemyMonsters.forEach { name -> addMonsterFilterChip(name, true) }
        
        historyAdapter.filterMyMonsters = filterMyMonsters.toList()
        historyAdapter.filterEnemyMonsters = filterEnemyMonsters.toList()
        historyAdapter.notifyItemRangeChanged(0, historyAdapter.itemCount)
    }

    private fun addFilterChip(labelText: String, colorInt: Int, onClose: () -> Unit) {
        val chip = com.google.android.material.chip.Chip(this).apply {
            text = labelText
            isCloseIconVisible = false
            setTextColor(Color.WHITE)
            chipBackgroundColor = ColorStateList.valueOf(colorInt)
            setChipStrokeWidthResource(R.dimen.none)
            textSize = 10f
            setEnsureMinTouchTargetSize(false)
            chipMinHeight = 24f * resources.displayMetrics.density
            chipStartPadding = 8f
            chipEndPadding = 8f
            
            // 親(LinearLayout)の垂直中央に配置
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins((4 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt(), 0)
            }

            setOnClickListener { onClose() }
        }
        binding.chipGroupFilters.addView(chip)
    }

    private fun addMonsterFilterChip(name: String, isEnemy: Boolean) {
        val iconSize = resources.getDimensionPixelSize(R.dimen.battle_history_icon_size)
        val density = resources.displayMetrics.density
        
        // Chipの代わりにFrameLayoutでアイコンと枠線を構成
        val container = FrameLayout(this).apply {
            // 親(LinearLayout)の垂直中央に配置
            val params = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            layoutParams = params
            
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 4 * density
                setColor(Color.TRANSPARENT)
            }
            background = bg
            clipToOutline = true
        }

        val imageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            
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
        }

        // 枠線（縁取り）
        val strokeColor = if (isEnemy) "#90D7EC".toColorInt() else "#F09199".toColorInt()
        val border = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            val gd = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 4 * density
                setStroke((2 * density).toInt(), strokeColor)
            }
            background = gd
        }

        container.addView(imageView)
        container.addView(border)
        
        container.setOnClickListener {
            if (isEnemy) filterEnemyMonsters.remove(name) else filterMyMonsters.remove(name)
            updateFilterStatusUI()
            setupUI()
        }

        binding.chipGroupFilters.addView(container)
    }


    private fun showResultFilterDialog() {
        val items = arrayOf(getString(R.string.filter_win_only), getString(R.string.filter_lose_only))
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title_result_filter)
            .setItems(items) { _, which ->
                filterResult = when (which) {
                    0 -> "WIN"
                    1 -> "LOSE"
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
            
            val matchEnemy = if (filterEnemyMonsters.isEmpty()) true else {
                filterEnemyMonsters.all { fName -> record.enemyParty.contains(fName) }
            }
            val matchMy = if (filterMyMonsters.isEmpty()) true else {
                filterMyMonsters.all { fName -> record.myParty.contains(fName) }
            }
            
            matchParty && matchResult && matchEnemy && matchMy
        }
    }

    private fun setupUI() {
        val allRecords = dataManager.history.records
        
        if (isFilterMode) {
            // 統計表示では「勝敗フィルタ(filterResult)」を除外して計算する（勝率0/100%固定を避けるため）
            val statsRecords = allRecords.filter { record ->
                (filterEnemyMonsters.isEmpty() || filterEnemyMonsters.all { it in record.enemyParty }) &&
                (filterMyMonsters.isEmpty() || filterMyMonsters.all { it in record.myParty })
            }
            val wins = statsRecords.count { it.result == "WIN" }
            val losses = statsRecords.count { it.result == "LOSE" }
            val totalCount = wins + losses
            val rate = if (totalCount > 0) (wins.toDouble() / totalCount * 100.0) else 0.0

            binding.textTotalLabel.text = getString(R.string.label_filter_win_rate)
            binding.valTotalRate.text = String.format(Locale.US, "%.1f%%", rate)
            binding.valTotalCount.text = getString(R.string.label_matches_format, totalCount)
            binding.valTotalWinLose.text = getString(R.string.label_win_lose_format, wins, losses)
        } else {
            val stats = dataManager.getStatistics()
            binding.textTotalLabel.text = getString(R.string.label_total_win_rate)
            binding.valTotalRate.text = String.format(Locale.US, "%.1f%%", stats.winRate)
            binding.valTotalCount.text = getString(R.string.label_matches_format, stats.totalWins + stats.totalLosses)
            binding.valTotalWinLose.text = getString(R.string.label_win_lose_format, stats.totalWins, stats.totalLosses)
        }

        val globalStats = dataManager.getStatistics()
        for (i in 0..2) {
            val partyRecords = allRecords.filter { it.partyIndex == i }
            
            val (wins, losses, rate) = if (isFilterMode) {
                // パーティ別統計も同様に勝敗フィルタを除外
                val stats = partyRecords.filter { record ->
                    (filterEnemyMonsters.isEmpty() || filterEnemyMonsters.all { it in record.enemyParty }) &&
                    (filterMyMonsters.isEmpty() || filterMyMonsters.all { it in record.myParty })
                }
                val w = stats.count { it.result == "WIN" }
                val l = stats.count { it.result == "LOSE" }
                val t = w + l
                val r = if (t > 0) (w.toDouble() / t * 100.0) else 0.0
                Triple(w, l, r)
            } else {
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

        val filteredRecordsForList = getFilteredRecords(allRecords)
        updateTrendsGraph(filteredRecordsForList)
        historyAdapter.updateData(filteredRecordsForList)
        
        // フィルタ解除時やデータ更新時、最新のレコード（リストの最後）へスクロール
        if (filteredRecordsForList.isNotEmpty()) {
            binding.recyclerViewHistory.scrollToPosition(filteredRecordsForList.size - 1)
        }

        updateCardSelectionUI()
    }

    private fun updateTrendsGraph(targetRecords: List<BattleRecord>) {
        if (targetRecords.size < 2) {
            binding.winRateGraph.visibility = View.INVISIBLE
            return
        }
        binding.winRateGraph.visibility = View.VISIBLE
        val dataPoints = mutableListOf<WinRateGraphView.PointData>()
        val windowSize = 20
        targetRecords.forEachIndexed { i, _ ->
            val start = (i - windowSize + 1).coerceAtLeast(0)
            val subList = targetRecords.subList(start, i + 1)
            val wins = subList.count { it.result == "WIN" }
            val rate = (wins.toDouble() / subList.size) * 100.0
            // タップ時に表示する情報を label に格納: "〇%：〇Matches"
            val label = String.format(Locale.US, "%.1f%% : %dMatches", rate, subList.size)
            dataPoints.add(WinRateGraphView.PointData(rate, label))
        }
        binding.winRateGraph.setData(dataPoints)
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
        val items = arrayOf("パーティ分析", "モンスター分析")
        AlertDialog.Builder(this)
            .setTitle("分析メニュー")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showPartyAnalysisDialog()
                    1 -> showMonsterRankingDialog()
                }
            }
            .show()
    }

    private fun showPartyAnalysisDialog() {
        val allRecords = dataManager.history.records
        if (allRecords.isEmpty()) return

        val globalStats = dataManager.getStatistics()
        val sdfInput = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sdfDisplay = SimpleDateFormat("MM/dd", Locale.US)

        data class PartyStats(
            val partyIndex: Int, // -1: Total, 0-2: P1-P3
            val members: List<String>,
            var wins: Int = 0,
            var losses: Int = 0,
            var lastUsed: Long = 0,
            var isLatest: Boolean = false,
            var historyRates: List<WinRateGraphView.PointData> = emptyList()
        ) {
            val total get() = wins + losses
            val winRate get() = if (total > 0) (wins.toDouble() / total * 100.0) else 0.0
        }

        fun getDailyRates(records: List<BattleRecord>): List<WinRateGraphView.PointData> {
            val grouped = records.groupBy {
                try { sdfDate.format(sdfInput.parse(it.timestamp)!!) } catch (_: Exception) { it.timestamp.take(10) }
            }
            return grouped.entries.sortedBy { it.key }.map { (dateStr, dayRecords) ->
                val wins = dayRecords.count { it.result == "WIN" }
                val rate = (wins.toDouble() / dayRecords.size) * 100.0
                val dateLabel = try { sdfDisplay.format(sdfDate.parse(dateStr)!!) } catch (_: Exception) { dateStr.takeLast(5) }
                // タップ時に表示する情報を label に格納。X軸表示用の日付はここでは含めず、ツールチップ用として構成
                val tooltipLabel = String.format(Locale.US, "%.1f%% : %dMatches (%s)", rate, dayRecords.size, dateLabel)
                WinRateGraphView.PointData(rate, tooltipLabel)
            }
        }

        val statsMap = mutableMapOf<Pair<Int, List<String>>, PartyStats>()
        val totalStats = PartyStats(-1, emptyList())
        
        val latestMembersByIndex = mutableMapOf<Int, List<String>>()
        for (i in 0..2) {
            allRecords.filter { it.partyIndex == i }.maxByOrNull { it.timestamp }?.let {
                latestMembersByIndex[i] = it.myParty
            }
        }

        allRecords.forEach { record ->
            if (record.result == "WIN") totalStats.wins++ else totalStats.losses++
            val key = record.partyIndex to record.myParty
            val stats = statsMap.getOrPut(key) { PartyStats(record.partyIndex, record.myParty) }
            if (record.result == "WIN") stats.wins++ else stats.losses++
            val time = try { sdfInput.parse(record.timestamp)?.time ?: 0L } catch(_: Exception) { 0L }
            if (time > stats.lastUsed) stats.lastUsed = time
        }

        totalStats.historyRates = getDailyRates(allRecords)
        statsMap.forEach { (key, stats) ->
            val partyRecords = allRecords.filter { it.partyIndex == key.first && it.myParty == key.second }
            stats.historyRates = getDailyRates(partyRecords)
        }

        statsMap.values.forEach { 
            if (it.members == latestMembersByIndex[it.partyIndex]) it.isLatest = true
        }

        val displayList = mutableListOf<PartyStats>().apply {
            add(totalStats)
            for (i in 0..2) {
                statsMap.values.find { it.partyIndex == i && it.isLatest }?.let { add(it) }
            }
            addAll(statsMap.values.filter { !it.isLatest }.sortedByDescending { it.lastUsed })
        }

        val listView = ListView(this).apply {
            divider = null
            setPadding(16, 16, 16, 16)
            clipToPadding = false
        }
        
        listView.adapter = object : BaseAdapter() {
            override fun getCount(): Int = displayList.size
            override fun getItem(position: Int) = displayList[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val stats = displayList[position]
                val card = MaterialCardView(this@HistoryActivity).apply {
                    layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 8, 0, 8)
                    }
                    radius = 12f * resources.displayMetrics.density
                    cardElevation = 4f * resources.displayMetrics.density
                    setCardBackgroundColor("#252525".toColorInt())
                    if (stats.isLatest) {
                        strokeWidth = (2 * resources.displayMetrics.density).toInt()
                        strokeColor = "#F09199".toColorInt()
                    } else {
                        strokeWidth = 0
                    }
                    
                    val horizontalRoot = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        val p = (12 * resources.displayMetrics.density).toInt()
                        setPadding(p, p, p, p)
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    // 左側コンテンツ (テキストとアイコン)
                    val leftContent = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams((124 * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                    }

                    // タイトルと勝率
                    val titleRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    val titleText = when {
                        stats.partyIndex == -1 -> "TOTAL"
                        stats.isLatest -> "P${stats.partyIndex + 1}(最新)"
                        else -> "P${stats.partyIndex + 1}(過去)"
                    }
                    val title = TextView(context).apply {
                        text = titleText
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                    val spacer = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                    }
                    val rateText = TextView(context).apply {
                        text = String.format(Locale.US, "%.1f%%", stats.winRate)
                        setTextColor(if (stats.winRate >= 50.0) "#F09199".toColorInt() else "#90D7EC".toColorInt())
                        textSize = 15f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                    titleRow.addView(title)
                    titleRow.addView(spacer)
                    titleRow.addView(rateText)
                    leftContent.addView(titleRow)

                    // 対戦数・使用率 (2行に分割、フォーマット統一)
                    val subInfo = TextView(context).apply {
                        val matchesStr = getString(R.string.label_matches_format, stats.total)
                        val wlStr = getString(R.string.label_win_lose_format, stats.wins, stats.losses)
                        val usageRate = if (stats.partyIndex == -1) 100.0 else {
                            globalStats.partyStats.find { it.index == stats.partyIndex }?.usageRate ?: 0.0
                        }
                        val usageStr = String.format(Locale.US, "%.1f%%", usageRate)

                        text = "${matchesStr}\n${wlStr}(Use:${usageStr})"
                        setTextColor(Color.LTGRAY)
                        textSize = 11f
                        setPadding(0, 2, 0, 0)
                    }
                    leftContent.addView(subInfo)

                    // モンスターアイコン
                    if (stats.partyIndex != -1) {
                        val iconsLayout = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, 8, 0, 0)
                        }
                        val iconSize = (28 * resources.displayMetrics.density).toInt()
                        stats.members.forEach { name ->
                            val iv = ImageView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = 4 }
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                dataManager.monsterMaster.find { it.name == name }?.let { monster ->
                                    try { assets.open("templates/${monster.fileName}").use { setImageBitmap(BitmapFactory.decodeStream(it)) } }
                                    catch(_:Exception) { setImageResource(android.R.drawable.ic_menu_help) }
                                } ?: setImageResource(android.R.drawable.ic_menu_help)
                            }
                            iconsLayout.addView(iv)
                        }
                        leftContent.addView(iconsLayout)
                    }
                    horizontalRoot.addView(leftContent)

                    // 右側：勝率推移グラフ
                    val graphContainer = FrameLayout(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, (80 * resources.displayMetrics.density).toInt(), 1f).apply {
                            marginStart = 16
                        }
                        val graph = WinRateGraphView(context).apply {
                            visibleCount = 7 // 日別データは1週間分程度を表示
                            setData(stats.historyRates)
                        }
                        addView(graph)
                    }
                    horizontalRoot.addView(graphContainer)
                    
                    addView(horizontalRoot)
                }
                return card
            }
        }

        AlertDialog.Builder(this)
            .setTitle("パーティ分析")
            .setView(listView)
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun showMonsterRankingDialog() {
        val allRecords = dataManager.history.records
        val filteredRecords = getFilteredRecords(allRecords)
        if (filteredRecords.isEmpty()) return
        
        val appearanceCount = mutableMapOf<String, Int>()
        val winAgainstCount = mutableMapOf<String, Int>()
        filteredRecords.forEach { record ->
            record.enemyParty.filter { it.isNotEmpty() && it != "?" }.distinct().forEach { name ->
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
            getString(R.string.edit_option_insert_after),
            "マッチングスコアを確認"
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
                    5 -> showScoreDetailsDialog(position)
                }
            }
            .show()
    }

    private fun showScoreDetailsDialog(position: Int) {
        val record = dataManager.history.records.getOrNull(position) ?: return
        val sb = StringBuilder()
        sb.append("【パーティ選択】\n")
        record.partySelectScores?.forEachIndexed { i, s ->
            sb.append("P${i + 1}: ${String.format(Locale.US, "%.3f", s)}\n")
        }
        sb.append("\n【VS画面】\n")
        sb.append("VSロゴ: ${String.format(Locale.US, "%.3f", record.vsScore ?: 0.0)}\n")

        sb.append("\n【モンスター】\n")
        record.myParty.forEachIndexed { i, name ->
            val s = record.myPartyScores?.getOrNull(i) ?: 0.0
            sb.append("自${i + 1}($name): ${String.format(Locale.US, "%.3f", s)}\n")
        }
        record.enemyParty.forEachIndexed { i, name ->
            val s = record.enemyPartyScores?.getOrNull(i) ?: 0.0
            sb.append("敵${i + 1}($name): ${String.format(Locale.US, "%.3f", s)}\n")
        }

        sb.append("\n【勝敗ロゴ】\n")
        val resScore = record.resultScore ?: 0.0
        sb.append("${record.result}: ${String.format(Locale.US, "%.3f", resScore)}\n")

        AlertDialog.Builder(this)
            .setTitle("マッチングスコア詳細")
            .setMessage(sb.toString())
            .setPositiveButton("閉じる", null)
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
                scaleType = ImageView.ScaleType.FIT_CENTER
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
                binding.recyclerViewHistory.smoothScrollToPosition(position + 1)
                showEditRecordDialog(position + 1)
            }
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
