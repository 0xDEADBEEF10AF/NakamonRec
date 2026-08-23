package com.dqw.nakamonrec

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.LinearLayoutManager
import com.dqw.nakamonrec.databinding.ActivityHistoryBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private lateinit var dataManager: BattleDataManager
    private var filterPartyIndex: Int = -1 // -1: All, 0: P1, 1: P2, 2: P3
    private var filterMyPartyComposition: List<String>? = null // 特定のモンスター組成フィルタ
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
        binding.btnModeToggle.setIconResource(android.R.drawable.ic_menu_edit)
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

        binding.cardP1.setOnLongClickListener { showPartyCompositionHistoryDialog(0); true }
        binding.cardP2.setOnLongClickListener { showPartyCompositionHistoryDialog(1); true }
        binding.cardP3.setOnLongClickListener { showPartyCompositionHistoryDialog(2); true }

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
            onResultClick = { tappedResult ->
                // iOS と同じ動作: タップした WIN/LOSE をフィルタに「追加」のみ。
                // 同じ結果を再タップしても解除しない (上書きはする = WIN→LOSE は切替可)。
                // 解除はフィルタ条件欄の WIN/LOSE タップ or CLEAR ボタンで行う。
                if (filterResult != tappedResult) {
                    filterResult = tappedResult
                    updateFilterStatusUI()
                    setupUI()
                }
            },
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
        
        // アイコンの意味を反転 (Edit Mode = Pencil, Filter Mode = Search)
        if (isFilterMode) {
            binding.btnModeToggle.setIconResource(android.R.drawable.ic_menu_sort_by_size)
        } else {
            binding.btnModeToggle.setIconResource(android.R.drawable.ic_menu_edit)
            // 編集モードに戻る際に組成フィルタなどもクリア
            filterMyPartyComposition = null
            updateFilterStatusUI()
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


    /**
     * iOS と同じ W-L 表示: W はピンク (#F09199)、L は水色 (#90D7EC) に色分け。
     */
    private fun buildWinLoseSpan(wins: Int, losses: Int): android.text.SpannableString {
        val winText = "${wins}W"
        val loseText = "${losses}L"
        val combined = "$winText - $loseText"
        val span = android.text.SpannableString(combined)
        span.setSpan(android.text.style.ForegroundColorSpan("#F09199".toColorInt()),
                     0, winText.length,
                     android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        span.setSpan(android.text.style.ForegroundColorSpan("#90D7EC".toColorInt()),
                     combined.length - loseText.length, combined.length,
                     android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return span
    }

    private fun setFilter(index: Int) {
        if (filterPartyIndex == index && filterMyPartyComposition == null) {
            filterPartyIndex = -1
        } else {
            filterPartyIndex = index
            filterMyPartyComposition = null
        }
        val transition = AutoTransition().apply { duration = 250 }
        TransitionManager.beginDelayedTransition(binding.root as ViewGroup, transition)
        setupUI()
    }

    private fun getFilteredRecords(allRecords: List<BattleRecord>): List<BattleRecord> {
        return allRecords.filter { record ->
            val matchParty = filterPartyIndex == -1 || record.partyIndex == filterPartyIndex
            val matchComposition = filterMyPartyComposition == null || record.myParty == filterMyPartyComposition
            val matchResult = filterResult == null || record.result == filterResult
            
            val matchEnemy = if (filterEnemyMonsters.isEmpty()) true else {
                filterEnemyMonsters.all { fName -> record.enemyParty.contains(fName) }
            }
            val matchMy = if (filterMyMonsters.isEmpty()) true else {
                filterMyMonsters.all { fName -> record.myParty.contains(fName) }
            }
            
            matchParty && matchComposition && matchResult && matchEnemy && matchMy
        }
    }

    private fun setupUI() {
        val allRecords = dataManager.history.records
        
        // 統計表示用の計算（トップカード用：パーティ選択フィルタを除外し、検索/勝敗フィルタのみ適用）
        val topStatsRecords = allRecords.filter { record ->
            val matchResult = filterResult == null || record.result == filterResult
            val matchEnemy = if (filterEnemyMonsters.isEmpty()) true else {
                filterEnemyMonsters.all { fName -> record.enemyParty.contains(fName) }
            }
            val matchMy = if (filterMyMonsters.isEmpty()) true else {
                filterMyMonsters.all { fName -> record.myParty.contains(fName) }
            }
            matchResult && matchEnemy && matchMy
        }
        
        val wins = topStatsRecords.count { it.result == "WIN" }
        val losses = topStatsRecords.count { it.result == "LOSE" }
        val totalCount = wins + losses
        val rate = if (totalCount > 0) (wins.toDouble() / totalCount * 100.0) else 0.0

        if (isFilterMode) {
            binding.textTotalLabel.text = getString(R.string.label_filter_win_rate)
        } else {
            binding.textTotalLabel.text = getString(R.string.label_total_win_rate)
        }
        
        binding.valTotalRate.text = RateFormat.percent(rate)
        binding.valTotalCount.text = getString(R.string.label_matches_format, totalCount)
        binding.valTotalWinLose.text = buildWinLoseSpan(wins, losses)

        val globalStats = dataManager.getStatistics()
        for (i in 0..2) {
            val partyRecords = allRecords.filter { it.partyIndex == i }
            
            val (wins, losses, rate) = if (isFilterMode) {
                // パーティ別統計も同様に勝敗フィルタを除外
                val stats = partyRecords.filter { record ->
                    val matchEnemy = filterEnemyMonsters.isEmpty() || filterEnemyMonsters.all { it in record.enemyParty }
                    val matchMyFilter = filterMyMonsters.isEmpty() || filterMyMonsters.all { it in record.myParty }
                    // 組成フィルタが設定されているパーティカードなら適用
                    val matchComp = if (filterPartyIndex == i && filterMyPartyComposition != null) {
                        record.myParty == filterMyPartyComposition
                    } else true
                    
                    matchEnemy && matchMyFilter && matchComp
                }
                val w = stats.count { it.result == "WIN" }
                val l = stats.count { it.result == "LOSE" }
                val t = w + l
                val r = if (t > 0) (w.toDouble() / t * 100.0) else 0.0
                Triple(w, l, r)
            } else {
                // フィルタモードでない場合でも組成フィルタがかかっている場合はその統計を出す
                val baseStats = if (filterPartyIndex == i && filterMyPartyComposition != null) {
                    partyRecords.filter { it.myParty == filterMyPartyComposition }
                } else partyRecords
                
                val w = baseStats.count { it.result == "WIN" }
                val l = baseStats.count { it.result == "LOSE" }
                val t = w + l
                val r = if (t > 0) (w.toDouble() / t * 100.0) else 0.0
                Triple(w, l, r)
            }

            val rateStr = RateFormat.percent(rate)
            val winLoseStr: CharSequence = buildWinLoseSpan(wins, losses)
            
            // 使用率の計算 (組成フィルタ時はその組成の使用率)
            val usageRate = if (filterPartyIndex == i && filterMyPartyComposition != null) {
                val compCount = partyRecords.count { it.myParty == filterMyPartyComposition }
                if (allRecords.isNotEmpty()) (compCount.toDouble() / allRecords.size * 100.0) else 0.0
            } else {
                globalStats.partyStats.find { it.index == i }?.usageRate ?: 0.0
            }
            val usageRateStr = getString(R.string.label_usage_short_format, RateFormat.percent(usageRate))

            val latestRecord = allRecords.lastOrNull { it.partyIndex == i }
            val myParty = if (filterPartyIndex == i && filterMyPartyComposition != null) {
                filterMyPartyComposition!!
            } else {
                latestRecord?.myParty ?: listOf("", "", "", "")
            }

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
            // タップ時に表示する情報を label に格納: "〇%：〇Matches" (〇番目の試合)
            val label = String.format(Locale.US, "%s：%dMatches", RateFormat.percent(rate), i + 1)
            dataPoints.add(WinRateGraphView.PointData(rate, label))
        }
        binding.winRateGraph.setData(dataPoints)
    }

    private fun showPartyCompositionHistoryDialog(partyIndex: Int) {
        val allRecords = dataManager.history.records
        val partyRecords = allRecords.filter { it.partyIndex == partyIndex }
        
        data class CompositionItem(
            val members: List<String>,
            val count: Int,
            val winRate: Double,
            val isTotal: Boolean
        )

        // 最新の構成を取得（総合戦績表示用）
        val latestComp = partyRecords.maxByOrNull { it.timestamp }?.myParty ?: listOf("", "", "", "")
        val winsAll = partyRecords.count { it.result == "WIN" }
        val rateAll = if (partyRecords.isNotEmpty()) (winsAll.toDouble() / partyRecords.size * 100.0) else 0.0

        // ユニークな組成を抽出
        val compositions = mutableListOf<CompositionItem>()
        
        // 1. 最上部に総合戦績を追加
        compositions.add(CompositionItem(latestComp, partyRecords.size, rateAll, true))

        // 2. 個別の組成を抽出して追加
        val grouped = partyRecords.groupBy { it.myParty }
            .map { (members, records) ->
                val wins = records.count { it.result == "WIN" }
                val rate = if (records.isNotEmpty()) (wins.toDouble() / records.size * 100.0) else 0.0
                CompositionItem(members, records.size, rate, false)
            }.sortedByDescending { it.count }
        
        compositions.addAll(grouped)

        if (partyRecords.isEmpty()) {
            showTopToast("履歴がありません")
            return
        }

        val listView = ListView(this).apply {
            divider = null
            setPadding(16, 16, 16, 16)
        }

        val adapter = object : BaseAdapter() {
            override fun getCount() = compositions.size
            override fun getItem(position: Int) = compositions[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val item = compositions[position]
                val itemRoot = LinearLayout(this@HistoryActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val p = (12 * resources.displayMetrics.density).toInt()
                    setPadding(p, p, p, p)
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(android.R.drawable.list_selector_background)
                }

                // モンスターアイコン
                val iconsLayout = LinearLayout(this@HistoryActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val iconSize = (32 * resources.displayMetrics.density).toInt()
                item.members.forEach { name ->
                    val iv = ImageView(this@HistoryActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = 4 }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        dataManager.monsterMaster.find { it.name == name }?.let { monster ->
                            try { assets.open("templates/${monster.fileName}").use { setImageBitmap(BitmapFactory.decodeStream(it)) } }
                            catch(_:Exception) { setImageResource(android.R.drawable.ic_menu_help) }
                        } ?: setImageResource(android.R.drawable.ic_menu_help)
                    }
                    iconsLayout.addView(iv)
                }
                itemRoot.addView(iconsLayout)

                // 統計情報
                val statsText = TextView(this@HistoryActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 16 }
                    val label = if (item.isTotal) " (Total)" else ""
                    text = String.format(Locale.US, "%d Matches (%s)%s", item.count, RateFormat.percent(item.winRate), label)
                    setTextColor(if (item.isTotal) "#90D7EC".toColorInt() else Color.WHITE)
                    textSize = 14f
                    setTypeface(null, if (item.isTotal) Typeface.BOLD else Typeface.NORMAL)
                }
                itemRoot.addView(statsText)

                return itemRoot
            }
        }

        listView.adapter = adapter
        val dialog = AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle("P${partyIndex + 1} 過去の編成")
            .setView(listView)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = compositions[position]
            if (selected.isTotal) {
                // 総合戦績を選択した場合
                filterPartyIndex = partyIndex
                filterMyPartyComposition = null
                // フィルタバーの味方モンスターもクリア
                filterMyMonsters.clear()
            } else {
                // 特定の組成を選択した場合
                filterPartyIndex = partyIndex
                filterMyPartyComposition = selected.members
                
                // フィルタモードに強制移行
                if (!isFilterMode) {
                    toggleMode()
                }
                
                // フィルタ欄（味方モンスター）をこの組成で更新
                filterMyMonsters.clear()
                filterMyMonsters.addAll(selected.members.filter { it.isNotEmpty() && it != "?" })
            }
            
            updateFilterStatusUI()
            setupUI()
            dialog.dismiss()
        }

        dialog.show()
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
            // 角丸クリップ (一度設定すれば再利用される)
            applyRoundedCorners(imageView)
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

    /**
     * ImageView に 6dp 角丸クリップを適用 (iOS の RoundedRectangle(cornerRadius: 6) と同じ見た目)。
     * outlineProvider + clipToOutline は冪等なので何度呼んでも安全。
     */
    private fun applyRoundedCorners(imageView: ImageView) {
        val cornerRadiusPx = 6 * resources.displayMetrics.density
        imageView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
            }
        }
        imageView.clipToOutline = true
    }

    private fun showAnalysisDialog() {
        val items = arrayOf("パーティ集計", "モンスター集計", "グランプリ集計")
        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle("集計メニュー")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showPartyAnalysisDialog()
                    1 -> showMonsterRankingDialog()
                    2 -> showGrandPrixDialog()
                }
            }
            .show()
    }

    /**
     * グランプリ集計: 最高レーティング (目立つ) + グラフ(既定) / テキスト一覧 の切替表示。
     * テキスト一覧はレコードをタップで手動編集 (OCR 誤認の訂正)。「1 ファイル = 1 グランプリ」前提。
     */
    private fun showGrandPrixDialog() {
        if (dataManager.loadGrandPrixRecords().isEmpty()) {
            AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
                .setTitle("グランプリ集計")
                .setMessage("グランプリの記録がありません。\n\n大会用VS画面で校正 (右上に GRAND PRIX MODE と表示) し、大会中に記録すると、ここにレーティング推移が表示されます。")
                .setPositiveButton("閉じる", null)
                .setNeutralButton("手動で追加") { _, _ ->
                    val nowTs = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date())
                    showGrandPrixAddDialog(nowTs) { showGrandPrixDialog() }
                }
                .show()
            return
        }
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // 現在の記録 (時系列昇順)。編集後に再取得して再描画する。
        fun currentRecords() = dataManager.loadGrandPrixRecords().sortedBy { it.timestamp }

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        // 最高レーティング (目立つ)
        val maxRatingView = android.widget.TextView(this).apply {
            setTextColor("#F09199".toColorInt()); textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(android.widget.TextView(this).apply {
            text = "最高レーティング"; setTextColor("#888888".toColorInt()); textSize = 12f
        })
        root.addView(maxRatingView)

        // 差し替えるコンテンツ領域 (グラフ or テキスト)
        val contentFrame = android.widget.FrameLayout(this)
        root.addView(contentFrame, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(360)
        ))

        // グラフ/テキスト 切替はコンテンツの下に控えめに配置
        val toggleButton = android.widget.Button(this, null, android.R.attr.borderlessButtonStyle)
        root.addView(toggleButton)

        var showList = false
        fun rebuild() {
            val records = currentRecords()
            val max = records.maxOfOrNull { it.currentRating }
            maxRatingView.text = max?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
            toggleButton.text = if (showList) "グラフ表示" else "テキスト表示"
            contentFrame.removeAllViews()
            if (showList) {
                // 行を長押しで編集メニュー (メイン戦績の長押し編集と同じ作法)
                contentFrame.addView(buildGrandPrixList(records) { rec ->
                    showGrandPrixRecordMenu(rec) { rebuild() }
                })
            } else {
                contentFrame.addView(buildGrandPrixGraph(records))
            }
        }
        toggleButton.setOnClickListener { showList = !showList; rebuild() }
        rebuild()

        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle("グランプリ集計")
            .setView(root)
            .setPositiveButton("閉じる", null)
            .show()
    }

    /** グラフ表示 (凡例 + レーティング推移グラフ)。全記録=最大5日ぶんを一度に表示。 */
    private fun buildGrandPrixGraph(records: List<GrandPrixRecord>): android.view.View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val sdfIn = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sdfOut = SimpleDateFormat("M/d HH:mm", Locale.US)  // 例: 8/23 18:00
        val points = records.map { r ->
            val label = try { sdfOut.format(sdfIn.parse(r.timestamp)!!) } catch (_: Exception) { r.timestamp.takeLast(5) }
            GrandPrixGraphView.RatingPoint(r.currentRating, r.borderRating, label)
        }
        val col = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        col.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            addView(android.widget.TextView(this@HistoryActivity).apply { text = "● 自分"; textSize = 12f; setTextColor("#F09199".toColorInt()) })
            addView(android.widget.TextView(this@HistoryActivity).apply { text = "   ● ボーダー"; textSize = 12f; setTextColor("#90D7EC".toColorInt()) })
        })
        col.addView(GrandPrixGraphView(this).apply {
            visibleCount = points.size.coerceAtLeast(2)
            setData(points)
        }, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(320)
        ))
        return col
    }

    /** テキスト一覧: 日時 / 戦 / レーティング / 変動 / ボーダー。行を長押しで onRowLongClick。 */
    private fun buildGrandPrixList(records: List<GrandPrixRecord>,
                                   onRowLongClick: (GrandPrixRecord) -> Unit): android.view.View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val sdfIn = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sdfOut = SimpleDateFormat("M/d HH:mm", Locale.US)
        // 変動 = 前戦との差 (時系列)
        val deltas = HashMap<String, Double>()
        for (i in 1 until records.size) deltas[records[i].timestamp] = records[i].currentRating - records[i - 1].currentRating

        fun cell(text: String, color: Int, size: Float, weight: Float, gravityRight: Boolean, bold: Boolean = false): android.widget.TextView =
            android.widget.TextView(this).apply {
                this.text = text; setTextColor(color); textSize = size
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = if (gravityRight) android.view.Gravity.END else android.view.Gravity.START
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, weight)
            }

        val list = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        // ヘッダ
        list.addView(android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(cell("日時", "#888888".toColorInt(), 11f, 2.4f, false))
            addView(cell("戦", "#888888".toColorInt(), 11f, 0.7f, true))
            addView(cell("レーティング", "#888888".toColorInt(), 11f, 1.8f, true))
            addView(cell("変動", "#888888".toColorInt(), 11f, 1.2f, true))
            addView(cell("ボーダー", "#888888".toColorInt(), 11f, 1.4f, true))
        })

        // 新しい順に行を作る。戦闘数は時系列の連番。
        records.reversed().forEachIndexed { revIdx, r ->
            val chronoIdx = records.size - 1 - revIdx
            val battleNo = chronoIdx + 1
            val time = try { sdfOut.format(sdfIn.parse(r.timestamp)!!) } catch (_: Exception) { r.timestamp.takeLast(5) }
            val delta = deltas[r.timestamp]
            val deltaStr = delta?.let { String.format(Locale.US, "%+.1f", it) } ?: "—"
            val deltaColor = if (delta == null) "#888888".toColorInt() else if (delta >= 0) "#F09199".toColorInt() else "#90D7EC".toColorInt()
            val borderStr = r.borderRating?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

            // 日時セル: ランク帯が設定されていれば下に小さく表示 (将来エンブレム)
            val timeCell = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 2.4f)
                addView(android.widget.TextView(this@HistoryActivity).apply {
                    text = time; setTextColor(android.graphics.Color.WHITE); textSize = 12f
                })
                r.rankTier?.let { tier ->
                    addView(android.widget.TextView(this@HistoryActivity).apply {
                        text = tier; setTextColor("#888888".toColorInt()); textSize = 9f
                    })
                }
            }
            list.addView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                isLongClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                addView(timeCell)
                addView(cell("$battleNo", "#888888".toColorInt(), 12f, 0.7f, true))
                addView(cell(String.format(Locale.US, "%.1f", r.currentRating), android.graphics.Color.WHITE, 14f, 1.8f, true, bold = true))
                addView(cell(deltaStr, deltaColor, 12f, 1.2f, true))
                addView(cell(borderStr, "#90D7EC".toColorInt(), 12f, 1.4f, true))
                setOnLongClickListener { onRowLongClick(r); true }
            })
        }

        return android.widget.ScrollView(this).apply { addView(list) }
    }

    /** 行の長押しで出す編集メニュー (メイン戦績の長押し編集と同じ作法)。onDone で再描画。 */
    private fun showGrandPrixRecordMenu(record: GrandPrixRecord, onDone: () -> Unit) {
        val items = arrayOf(
            "レーティングスコアを修正",
            "ボーダースコアを修正",
            "ランク帯を修正",
            "このレコードを削除",
            "このレコードの次にスコアを追加"
        )
        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle("グランプリ記録")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> editGrandPrixNumber("レーティングスコアを修正",
                        String.format(Locale.US, "%.1f", record.currentRating), allowEmpty = false) { v ->
                        dataManager.updateGrandPrix(record.copy(currentRating = v!!)); onDone()
                    }
                    1 -> editGrandPrixNumber("ボーダースコアを修正 (空欄=なし)",
                        record.borderRating?.let { String.format(Locale.US, "%.1f", it) } ?: "", allowEmpty = true) { v ->
                        dataManager.updateGrandPrix(record.copy(neededRating = v?.let { it - record.currentRating })); onDone()
                    }
                    2 -> editGrandPrixRank(record, onDone)
                    3 -> AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
                        .setMessage("このレコードを削除しますか?")
                        .setPositiveButton("削除") { _, _ -> dataManager.deleteGrandPrix(record.timestamp); onDone() }
                        .setNegativeButton("キャンセル", null)
                        .show()
                    4 -> {
                        // この記録の 1 秒後を初期日時にして追加
                        val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        val base = try { tsFormat.parse(record.timestamp)?.time ?: System.currentTimeMillis() }
                                   catch (_: Exception) { System.currentTimeMillis() }
                        showGrandPrixAddDialog(tsFormat.format(java.util.Date(base + 1000)), onDone)
                    }
                }
            }
            .show()
    }

    /** 数値入力ダイアログ (レーティング/ボーダー修正用)。allowEmpty=true なら空欄で null を返す。 */
    private fun editGrandPrixNumber(title: String, initial: String, allowEmpty: Boolean, onSave: (Double?) -> Unit) {
        val edit = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(initial)
        }
        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle(title)
            .setView(edit)
            .setPositiveButton("保存") { _, _ ->
                val text = edit.text.toString().trim()
                if (text.isEmpty()) {
                    if (allowEmpty) onSave(null)
                    else Toast.makeText(this, "数値を入力してください", Toast.LENGTH_SHORT).show()
                } else {
                    val v = text.toDoubleOrNull()
                    if (v == null) Toast.makeText(this, "数値が不正です", Toast.LENGTH_SHORT).show()
                    else onSave(v)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** ランク帯選択ダイアログ (未設定 + rankTiers)。 */
    private fun editGrandPrixRank(record: GrandPrixRecord, onDone: () -> Unit) {
        val options = (listOf("未設定") + GrandPrixRecord.rankTiers).toTypedArray()
        val currentIdx = record.rankTier?.let { GrandPrixRecord.rankTiers.indexOf(it) + 1 } ?: 0
        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle("ランク帯を選択")
            .setSingleChoiceItems(options, currentIdx) { d, which ->
                val tier = if (which == 0) null else GrandPrixRecord.rankTiers[which - 1]
                dataManager.updateGrandPrix(record.copy(rankTier = tier))
                onDone()
                d.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** 記録漏れの手動追加 (日時・レーティング・ボーダー)。勝敗は WIN 既定。 */
    private fun showGrandPrixAddDialog(initialTs: String, onDone: () -> Unit) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val tsFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        col.addView(android.widget.TextView(this).apply { text = "日時"; setTextColor("#888888".toColorInt()); textSize = 12f })
        val tsEdit = android.widget.EditText(this).apply { setText(initialTs) }
        col.addView(tsEdit)
        col.addView(android.widget.TextView(this).apply { text = "レーティング"; setTextColor("#888888".toColorInt()); textSize = 12f })
        val ratingEdit = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "例 2208.1"
        }
        col.addView(ratingEdit)
        col.addView(android.widget.TextView(this).apply { text = "ボーダー (空欄=なし)"; setTextColor("#888888".toColorInt()); textSize = 12f })
        val borderEdit = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        col.addView(borderEdit)

        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle("グランプリ記録の追加")
            .setView(col)
            .setPositiveButton("保存") { _, _ ->
                val rating = ratingEdit.text.toString().toDoubleOrNull()
                if (rating == null) { Toast.makeText(this, "レーティングが不正です", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val ts = tsEdit.text.toString().trim()
                val parsed = try { tsFormat.parse(ts) } catch (_: Exception) { null }
                if (parsed == null) { Toast.makeText(this, "日時の形式が不正です (yyyy-MM-dd HH:mm:ss)", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                val needed = borderEdit.text.toString().toDoubleOrNull()?.let { it - rating }
                dataManager.appendGrandPrix(GrandPrixRecord(timestamp = ts, result = "WIN",
                    currentRating = rating, neededRating = needed))
                onDone()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showPartyAnalysisDialog() {
        val allRecords = dataManager.history.records
        if (allRecords.isEmpty()) return

        // 日次グラフの表示/非表示 (シンプルビュー)。モンスター集計と共通キーで永続化
        // (iOS の @AppStorage "showStatsTrendGraphs" と対応する設定)
        val prefsStats = getSharedPreferences("NakamonPrefs", MODE_PRIVATE)
        val showTrendGraphs = prefsStats.getBoolean("show_stats_trend_graphs", false)

        val sdfInput = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sdfDisplay = SimpleDateFormat("MM/dd", Locale.US)

        data class PartyStats(
            var partyIndices: MutableSet<Int> = mutableSetOf(), // 元のパーティ番号（0-2）
            val members: List<String>,
            var wins: Int = 0,
            var losses: Int = 0,
            var lastUsed: Long = 0,
            var lastUsedIndex: Int = -1, // 最も新しく使われたパーティ番号
            var isLatest: Boolean = false,
            var isTotal: Boolean = false, // 総合集計行か
            var historyRates: List<WinRateGraphView.PointData> = emptyList()
        ) {
            val total get() = wins + losses
            val winRate get() = if (total > 0) (wins.toDouble() / total * 100.0) else 0.0
            val sortedMembers get() = members.filter { it.isNotEmpty() && it != "?" }.sorted()
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
                val tooltipLabel = String.format(Locale.US, "%s：%dMatches (%s)", RateFormat.percent(rate), dayRecords.size, dateLabel)
                WinRateGraphView.PointData(rate, tooltipLabel)
            }
        }

        // キーを「ソート済みのモンスター名のリスト」にすることで、並び順が違ってもマージする
        val statsMap = mutableMapOf<List<String>, PartyStats>()
        val totalStats = PartyStats(members = emptyList(), isTotal = true)
        
        val latestMembersByIndex = mutableMapOf<Int, List<String>>()
        for (i in 0..2) {
            allRecords.filter { it.partyIndex == i }.maxByOrNull { it.timestamp }?.let {
                latestMembersByIndex[i] = it.myParty.filter { it.isNotEmpty() && it != "?" }.sorted()
            }
        }

        allRecords.forEach { record ->
            if (record.result == "WIN") totalStats.wins++ else totalStats.losses++
            val sortedMembers = record.myParty.filter { it.isNotEmpty() && it != "?" }.sorted()
            val stats = statsMap.getOrPut(sortedMembers) { PartyStats(members = record.myParty) }
            if (record.partyIndex != -1) {
                stats.partyIndices.add(record.partyIndex)
            }
            if (record.result == "WIN") stats.wins++ else stats.losses++
            val time = try { sdfInput.parse(record.timestamp)?.time ?: 0L } catch(_: Exception) { 0L }
            if (time > stats.lastUsed) {
                stats.lastUsed = time
                stats.lastUsedIndex = record.partyIndex
            }
        }

        totalStats.historyRates = getDailyRates(allRecords)
        statsMap.forEach { (sortedKey, stats) ->
            val partyRecords = allRecords.filter { it.myParty.filter { m -> m.isNotEmpty() && m != "?" }.sorted() == sortedKey }
            stats.historyRates = getDailyRates(partyRecords)
            
            // 最新のパーティ構成に含まれているかチェック
            for (i in 0..2) {
                if (latestMembersByIndex[i] == sortedKey) {
                    stats.isLatest = true
                    stats.partyIndices.add(i)
                }
            }
        }

        val displayList = mutableListOf<PartyStats>().apply {
            add(totalStats)
            // 最新の構成を優先して並べる
            for (i in 0..2) {
                statsMap.values.find { it.isLatest && latestMembersByIndex[i] == it.sortedMembers }?.let { 
                    if (!contains(it)) add(it) 
                }
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
                    strokeWidth = 0
                    
                    val density = resources.displayMetrics.density
                    val contentPad = (12 * density).toInt()

                    val titleText = if (stats.isTotal) {
                        "TOTAL"
                    } else {
                        // メインとなるパーティ（最後、または最新の利用スロット）
                        val mainIdx = stats.lastUsedIndex
                        // その他の利用履歴スロットを昇順で抽出
                        val otherIndices = stats.partyIndices.filter { it != mainIdx && it >= 0 }.sorted()

                        val sb = StringBuilder(if (mainIdx != -1) "P${mainIdx + 1}" else "P?")
                        if (otherIndices.isNotEmpty()) {
                            sb.append(",")
                            sb.append(otherIndices.map { it + 1 }.joinToString(","))
                        }
                        sb.append(if (stats.isLatest) "(最新)" else "(過去)")
                        sb.toString()
                    }
                    // (過去) カードのラベルは iOS 版 (PartyStatsView) と同じくグレー
                    val labelColor = if (!stats.isTotal && !stats.isLatest) Color.GRAY else Color.WHITE
                    val title = TextView(context).apply {
                        text = titleText
                        setTextColor(labelColor)
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                    val rateColor = if (stats.winRate >= 50.0) "#F09199".toColorInt() else "#90D7EC".toColorInt()
                    val matchesStr = getString(R.string.label_matches_format, stats.total)
                    val wlStr = getString(R.string.label_win_lose_format, stats.wins, stats.losses)
                    val useStr = if (stats.isTotal) null else {
                        val usageRate = (stats.total.toDouble() / allRecords.size * 100.0)
                        "Use:${RateFormat.percent(usageRate)}"
                    }

                    fun buildIconsLayout(iconDp: Int, topPad: Int): LinearLayout {
                        val iconsLayout = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(0, topPad, 0, 0)
                        }
                        val iconSize = (iconDp * density).toInt()
                        stats.members.forEach { name ->
                            val iv = ImageView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = 4 }
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                dataManager.monsterMaster.find { it.name == name }?.let { monster ->
                                    try { assets.open("templates/${monster.fileName}").use { setImageBitmap(BitmapFactory.decodeStream(it)) } }
                                    catch(_:Exception) { setImageResource(android.R.drawable.ic_menu_help) }
                                } ?: setImageResource(android.R.drawable.ic_menu_help)
                            }
                            applyRoundedCorners(iv)
                            iconsLayout.addView(iv)
                        }
                        return iconsLayout
                    }

                    if (showTrendGraphs) {
                        val horizontalRoot = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(contentPad, contentPad, contentPad, contentPad)
                            gravity = Gravity.CENTER_VERTICAL
                        }

                        // 左側コンテンツ (テキストとアイコン)
                        val leftContent = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams((124 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                        }

                        // タイトルと勝率
                        val titleRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        }
                        val spacer = View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                        }
                        val rateText = TextView(context).apply {
                            text = RateFormat.percent(stats.winRate)
                            setTextColor(rateColor)
                            textSize = 15f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                        }
                        titleRow.addView(title)
                        titleRow.addView(spacer)
                        titleRow.addView(rateText)
                        leftContent.addView(titleRow)

                        // 対戦数・使用率 (2行に分割、フォーマット統一)
                        val subInfo = TextView(context).apply {
                            text = if (useStr == null) "${matchesStr}\n${wlStr}" else "${matchesStr}\n${wlStr}(${useStr})"
                            setTextColor(Color.LTGRAY)
                            textSize = 11f
                            setPadding(0, 2, 0, 0)
                        }
                        leftContent.addView(subInfo)

                        // モンスターアイコン
                        if (!stats.partyIndices.contains(-1)) {
                            leftContent.addView(buildIconsLayout(28, 8))
                        }
                        horizontalRoot.addView(leftContent)

                        // 右側：勝率推移グラフ
                        val graphContainer = FrameLayout(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, (80 * density).toInt(), 1f).apply {
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
                    } else {
                        // シンプルビュー: モンスター集計と同じメトリクス2列+列ヘッダの形式。
                        // 1列目 ラベル (番号/状態の2行)、2列目 サムネ、3-4列目 使用率/勝率
                        // (iOS PartyStatsView.simpleRowContent と同一構成)
                        val simpleRoot = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setPadding(contentPad, contentPad, contentPad, contentPad)
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        // 「P1,2(過去)」等を「P1,2」+「(過去)」の2行に分割
                        val labelParts = titleText.split("(", limit = 2)
                        val labelCol = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams((52 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                            addView(TextView(context).apply {
                                text = labelParts[0]
                                setTextColor(labelColor)
                                textSize = 13f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                            })
                            if (labelParts.size == 2) {
                                addView(TextView(context).apply {
                                    text = "(" + labelParts[1]
                                    setTextColor(labelColor)
                                    textSize = 10f
                                })
                            }
                        }
                        simpleRoot.addView(labelCol)
                        if (!stats.partyIndices.contains(-1)) {
                            simpleRoot.addView(buildIconsLayout(36, 0))
                        }
                        val simpleSpacer = View(context).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                        }
                        simpleRoot.addView(simpleSpacer)

                        fun metricColumn(value: String, sub: String, valueColor: Int) = LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams((62 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                marginStart = (6 * density).toInt()
                            }
                            addView(TextView(context).apply {
                                text = value
                                setTextColor(valueColor)
                                textSize = 17f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                gravity = Gravity.END
                                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            })
                            addView(TextView(context).apply {
                                text = sub
                                setTextColor(Color.GRAY)
                                textSize = 9f
                                gravity = Gravity.END
                                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            })
                        }
                        // TOTAL の使用率は定義上 100%
                        val useValue = if (stats.isTotal) RateFormat.percent(100.0)
                                       else RateFormat.percent(stats.total.toDouble() / allRecords.size * 100.0)
                        simpleRoot.addView(metricColumn(useValue, "${stats.total}回", Color.WHITE))
                        simpleRoot.addView(metricColumn(RateFormat.percent(stats.winRate), "${stats.wins}W-${stats.losses}L", rateColor))

                        addView(simpleRoot)
                    }
                }
                return card
            }
        }

        // 列タイトル行 (シンプルビュー時のみ、TOTAL カードの上に1回だけ表示)。
        // カード内右端のメトリクス列 (62dp ×2、間隔 6dp) と横位置を揃える:
        // 右余白 = listView の padding 16px + カード内 padding 12dp
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (!showTrendGraphs) {
                val d = resources.displayMetrics.density
                fun headerLabel(label: String, marginStartDp: Int) = TextView(this@HistoryActivity).apply {
                    text = label
                    setTextColor(Color.GRAY)
                    textSize = 10f
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams((62 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = (marginStartDp * d).toInt()
                    }
                }
                addView(LinearLayout(this@HistoryActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, (6 * d).toInt(), 16 + (12 * d).toInt(), 0)
                    addView(headerLabel("使用率", 0))
                    addView(headerLabel("勝率", 6))
                })
            }
            addView(listView)
        }

        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle("パーティ集計")
            .setView(dialogContent)
            .setPositiveButton("閉じる", null)
            .setNegativeButton(if (showTrendGraphs) "グラフ非表示" else "グラフ表示") { _, _ ->
                // 設定を反転して開き直す (カードは構築済みのため再生成が必要)
                prefsStats.edit().putBoolean("show_stats_trend_graphs", !showTrendGraphs).apply()
                showPartyAnalysisDialog()
            }
            .show()
    }

    private fun showMonsterRankingDialog() {
        val allRecords = dataManager.history.records
        val filteredRecords = getFilteredRecords(allRecords)
        if (filteredRecords.isEmpty()) return

        val density = resources.displayMetrics.density

        // 日別推移グラフの表示/非表示 (シンプルビュー)。パーティ集計と共通キーで永続化。
        // グラフボタンで切替時は refreshList() で再構築するため var で持つ
        val prefsStats = getSharedPreferences("NakamonPrefs", MODE_PRIVATE)
        var showTrendGraphs = prefsStats.getBoolean("show_stats_trend_graphs", false)

        val sdfInput = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sdfDisplay = SimpleDateFormat("MM/dd", Locale.US)

        // 日別の全対戦数を計算（出現率の分母用）
        val dailyTotalMatches = allRecords.groupBy {
            try { sdfDate.format(sdfInput.parse(it.timestamp)!!) } catch (_: Exception) { it.timestamp.take(10) }
        }.mapValues { it.value.size }

        // ===== モンスター単体ランキング =====
        val appearanceCount = mutableMapOf<String, Int>()
        val winAgainstCount = mutableMapOf<String, Int>()
        filteredRecords.forEach { record ->
            record.enemyParty.filter { it.isNotEmpty() && it != "?" }.distinct().forEach { name ->
                appearanceCount[name] = appearanceCount.getOrDefault(name, 0) + 1
                if (record.result == "WIN") winAgainstCount[name] = winAgainstCount.getOrDefault(name, 0) + 1
            }
        }
        val totalCount = filteredRecords.size
        // 同率タイも決定的に並ぶよう、副キー → 名前/構成キーまで含めた比較器で順序付ける
        // (iOS MonsterStatsView.sortedRows / sortedPartyRows と同一規則)
        val monsterOrderByCount = compareByDescending<MonsterRankData> { it.count }
            .thenByDescending { it.winRate }.thenBy { it.name }
        val monsterOrderByRateAsc = compareBy<MonsterRankData> { it.winRate }
            .thenByDescending { it.count }.thenBy { it.name }
        val monsterOrderByRateDesc = compareByDescending<MonsterRankData> { it.winRate }
            .thenByDescending { it.count }.thenBy { it.name }
        val partyOrderByCount = compareByDescending<PartyRankData> { it.count }
            .thenByDescending { it.winRate }.thenBy { it.key.joinToString("_") }
        val partyOrderByRateAsc = compareBy<PartyRankData> { it.winRate }
            .thenByDescending { it.count }.thenBy { it.key.joinToString("_") }
        val partyOrderByRateDesc = compareByDescending<PartyRankData> { it.winRate }
            .thenByDescending { it.count }.thenBy { it.key.joinToString("_") }

        val monsterRankingList = appearanceCount.map { (name, count) ->
            val wins = winAgainstCount.getOrDefault(name, 0)
            val winRate = if (count > 0) (wins.toDouble() / count * 100) else 0.0
            val appearanceRate = (count.toDouble() / totalCount * 100)

            // 日別推移データの作成
            val monsterRecords = allRecords.filter { it.enemyParty.contains(name) }
            val dailyMonsterStats = monsterRecords.groupBy {
                try { sdfDate.format(sdfInput.parse(it.timestamp)!!) } catch (_: Exception) { it.timestamp.take(10) }
            }

            val historyData = dailyTotalMatches.keys.sorted().map { dateStr ->
                val dayRecords = dailyMonsterStats[dateStr] ?: emptyList()
                val dayTotalAll = dailyTotalMatches[dateStr] ?: 1

                val appearRate = (dayRecords.size.toDouble() / dayTotalAll * 100.0)
                val winCount = dayRecords.count { it.result == "WIN" }
                val dayWinRate = if (dayRecords.isNotEmpty()) (winCount.toDouble() / dayRecords.size * 100.0) else 0.0

                val displayDate = try { sdfDisplay.format(sdfDate.parse(dateStr)!!) } catch (_: Exception) { dateStr.takeLast(5) }
                MonsterStatsGraphView.DualPointData(dayWinRate, appearRate, displayDate)
            }

            MonsterRankData(name, count, appearanceRate, winRate, historyData)
        }.sortedWith(monsterOrderByCount).toMutableList()

        // ===== 敵パーティ構成ランキング (順序無視・4体識別必須) =====
        var qualifiedPartyBattles = 0
        val partyMap = mutableMapOf<List<String>, IntArray>() // [count, wins]
        filteredRecords.forEach { record ->
            val cleaned = record.enemyParty.filter { it.isNotEmpty() && it != "?" }
            if (cleaned.size != 4) return@forEach
            qualifiedPartyBattles++
            val key = cleaned.sorted()
            val entry = partyMap.getOrPut(key) { intArrayOf(0, 0) }
            entry[0]++
            if (record.result == "WIN") entry[1]++
        }
        val partyTotalDenominator = qualifiedPartyBattles.coerceAtLeast(1)
        val partyRankingList = partyMap.map { (key, entry) ->
            val count = entry[0]
            val wins = entry[1]
            val winRate = if (count > 0) (wins.toDouble() / count * 100) else 0.0
            val appearanceRate = (count.toDouble() / partyTotalDenominator * 100)
            PartyRankData(key, count, appearanceRate, winRate)
        }.sortedWith(partyOrderByCount).toMutableList()

        val listView = ListView(this).apply {
            divider = null
            setPadding(16, 16, 16, 16)
            clipToPadding = false
        }

        var currentMode = 0  // 0 = monster, 1 = party
        var sortMode = 0     // 0: 出現数(降), 1: 勝率(昇), 2: 勝率(降)

        fun buildMonsterCard(position: Int, data: MonsterRankData): View {
            return MaterialCardView(this@HistoryActivity).apply {
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, (3 * density).toInt(), 0, (3 * density).toInt())
                }
                radius = 8f * density
                cardElevation = 2f * density
                setCardBackgroundColor("#252525".toColorInt())
                strokeWidth = 0

                val root = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val ph = (10 * density).toInt()
                    val pv = (4 * density).toInt()
                    setPadding(ph, pv, ph, pv)
                    gravity = Gravity.CENTER_VERTICAL
                }

                val rankText = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                    text = "${position + 1}"
                    setTextColor(Color.GRAY)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTypeface(null, Typeface.BOLD)
                }
                root.addView(rankText)

                val iconSize = (40 * density).toInt()
                val imageView = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    dataManager.monsterMaster.find { it.name == data.name }?.let { monster ->
                        try { assets.open("templates/${monster.fileName}").use { setImageBitmap(BitmapFactory.decodeStream(it)) } }
                        catch(_: Exception) { setImageResource(android.R.drawable.ic_menu_help) }
                    } ?: setImageResource(android.R.drawable.ic_menu_help)
                }
                applyRoundedCorners(imageView)
                root.addView(imageView)

                val winRateColor = when {
                    data.winRate >= 80.0 -> "#F09199".toColorInt()
                    data.winRate >= 50.0 -> Color.WHITE
                    else -> "#90D7EC".toColorInt()
                }
                val nameText = TextView(context).apply {
                    text = data.name
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 1
                }

                if (showTrendGraphs) {
                    val infoLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams((90 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = (8 * density).toInt()
                        }
                    }
                    val appearText = TextView(context).apply {
                        text = String.format(Locale.US, "出現:%d回(%s)", data.count, RateFormat.percent(data.appearanceRate))
                        setTextColor(Color.LTGRAY)
                        textSize = 10f
                    }
                    val winRateText = TextView(context).apply {
                        text = "勝率:" + RateFormat.percent(data.winRate)
                        setTextColor(winRateColor)
                        textSize = 10f
                        setTypeface(null, Typeface.BOLD)
                    }
                    infoLayout.addView(nameText)
                    infoLayout.addView(appearText)
                    infoLayout.addView(winRateText)
                    root.addView(infoLayout)

                    val graphContainer = FrameLayout(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, (60 * density).toInt(), 1f).apply {
                            marginStart = (4 * density).toInt()
                        }
                        val graph = MonsterStatsGraphView(context).apply {
                            setData(data.historyData)
                        }
                        addView(graph)
                    }
                    root.addView(graphContainer)
                } else {
                    // シンプルビュー: 名前を左に、右の空きスペースへ「出現率」「勝率」を
                    // 大きめ数字 (17sp) の2列で配置。列タイトルは simpleHeaderRow に1回だけ
                    // 表示し、% の下には実数 (回数 / W-L) を併記する
                    // (iOS MonsterStatsView.simpleMetric と同一構成)
                    nameText.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (8 * density).toInt()
                    }
                    root.addView(nameText)

                    fun metricColumn(value: String, sub: String, valueColor: Int) = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams((62 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = (6 * density).toInt()
                        }
                        addView(TextView(context).apply {
                            text = value
                            setTextColor(valueColor)
                            textSize = 17f
                            setTypeface(null, Typeface.BOLD)
                            gravity = Gravity.END
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        })
                        addView(TextView(context).apply {
                            text = sub
                            setTextColor(Color.GRAY)
                            textSize = 9f
                            gravity = Gravity.END
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        })
                    }
                    // winRate = wins/count*100 なので実数を逆算 (丸め誤差なし)
                    val wins = Math.round(data.winRate * data.count / 100.0).toInt()
                    val losses = data.count - wins
                    root.addView(metricColumn(RateFormat.percent(data.appearanceRate), "${data.count}回", Color.WHITE))
                    root.addView(metricColumn(RateFormat.percent(data.winRate), "${wins}W-${losses}L", winRateColor))
                }

                addView(root)
            }
        }

        fun buildPartyCard(position: Int, data: PartyRankData): View {
            return MaterialCardView(this@HistoryActivity).apply {
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, (3 * density).toInt(), 0, (3 * density).toInt())
                }
                radius = 8f * density
                cardElevation = 2f * density
                setCardBackgroundColor("#252525".toColorInt())
                strokeWidth = 0

                val root = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val ph = (10 * density).toInt()
                    val pv = (6 * density).toInt()
                    setPadding(ph, pv, ph, pv)
                    gravity = Gravity.CENTER_VERTICAL
                }

                val rankText = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                    text = "${position + 1}"
                    setTextColor(Color.GRAY)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTypeface(null, Typeface.BOLD)
                }
                root.addView(rankText)

                val iconSize = (40 * density).toInt()
                val iconRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                // 壁モンスターを左側へ並べ替える (集計キーは順序無視のまま、表示順だけ変更)
                val displayOrder = data.key.sortedWith(Comparator { a, b ->
                    val aWall = dataManager.monsterMaster.find { it.name == a }?.isWall == true
                    val bWall = dataManager.monsterMaster.find { it.name == b }?.isWall == true
                    if (aWall != bWall) {
                        if (aWall) -1 else 1
                    } else {
                        a.compareTo(b)
                    }
                })
                displayOrder.forEach { name ->
                    val iv = ImageView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = (4 * density).toInt() }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        dataManager.monsterMaster.find { it.name == name }?.let { monster ->
                            try { assets.open("templates/${monster.fileName}").use { setImageBitmap(BitmapFactory.decodeStream(it)) } }
                            catch(_: Exception) { setImageResource(android.R.drawable.ic_menu_help) }
                        } ?: setImageResource(android.R.drawable.ic_menu_help)
                    }
                    applyRoundedCorners(iv)
                    iconRow.addView(iv)
                }
                root.addView(iconRow)

                val spacer = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                }
                root.addView(spacer)

                // モンスタータブと同じメトリクス2列 (列タイトルは simpleHeaderRow が担う)。
                // パーティ行にはグラフがないためレイアウトは常時この形
                val winRateColor = when {
                    data.winRate >= 80.0 -> "#F09199".toColorInt()
                    data.winRate >= 50.0 -> Color.WHITE
                    else -> "#90D7EC".toColorInt()
                }
                fun metricColumn(value: String, sub: String, valueColor: Int) = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams((62 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = (6 * density).toInt()
                    }
                    addView(TextView(context).apply {
                        text = value
                        setTextColor(valueColor)
                        textSize = 17f
                        setTypeface(null, Typeface.BOLD)
                        gravity = Gravity.END
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                    addView(TextView(context).apply {
                        text = sub
                        setTextColor(Color.GRAY)
                        textSize = 9f
                        gravity = Gravity.END
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    })
                }
                // winRate = wins/count*100 なので実数を逆算 (丸め誤差なし)
                val wins = Math.round(data.winRate * data.count / 100.0).toInt()
                val losses = data.count - wins
                root.addView(metricColumn(RateFormat.percent(data.appearanceRate), "${data.count}回", Color.WHITE))
                root.addView(metricColumn(RateFormat.percent(data.winRate), "${wins}W-${losses}L", winRateColor))

                addView(root)
            }
        }

        val emptyPartyView = TextView(this).apply {
            text = "4 体識別できた戦績がまだありません"
            setTextColor(Color.GRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, (60 * density).toInt(), 0, (60 * density).toInt())
            visibility = View.GONE
        }

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = if (currentMode == 0) monsterRankingList.size else partyRankingList.size
            override fun getItem(position: Int): Any = if (currentMode == 0) monsterRankingList[position] else partyRankingList[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                return if (currentMode == 0) buildMonsterCard(position, monsterRankingList[position])
                       else buildPartyCard(position, partyRankingList[position])
            }
        }
        listView.adapter = adapter

        fun updateTabStyle(tab: TextView, selected: Boolean) {
            if (selected) {
                tab.setTextColor("#F09199".toColorInt())
                tab.setTypeface(null, Typeface.BOLD)
            } else {
                tab.setTextColor(Color.GRAY)
                tab.setTypeface(null, Typeface.NORMAL)
            }
        }

        fun makeTab(label: String, isSelected: Boolean): TextView {
            return TextView(this).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                gravity = Gravity.CENTER
                setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
                textSize = 14f
                isClickable = true
                isFocusable = true
                updateTabStyle(this, isSelected)
            }
        }

        val monsterTab = makeTab("モンスター", true)
        val partyTab = makeTab("パーティ", false)

        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), 0)
            addView(monsterTab)
            addView(partyTab)
        }

        // シンプルビューの列タイトル行 (モンスタータブのみ、1位カードの上に1回だけ表示)。
        // カード内右端のメトリクス列 (62dp ×2、間隔 6dp) と横位置を揃える:
        // 右余白 = listView の padding 16px + カード内 padding 10dp
        fun makeHeaderLabel(label: String, marginStartDp: Int) = TextView(this).apply {
            text = label
            setTextColor(Color.GRAY)
            textSize = 10f
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams((62 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (marginStartDp * density).toInt()
            }
        }
        val simpleHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (6 * density).toInt(), 16 + (10 * density).toInt(), 0)
            addView(makeHeaderLabel("出現率", 0))
            addView(makeHeaderLabel("勝率", 6))
        }

        fun updateSimpleHeader() {
            // モンスタータブはシンプルビュー時のみ、パーティタブ (グラフなし) は常時表示。
            // ただしパーティタブが空 (emptyPartyView 表示中) のときは出さない
            val visible = if (currentMode == 1) partyRankingList.isNotEmpty() else !showTrendGraphs
            simpleHeaderRow.visibility = if (visible) View.VISIBLE else View.GONE
        }
        updateSimpleHeader()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tabBar)
            addView(simpleHeaderRow)
            addView(listView)
            addView(emptyPartyView)
        }

        val titlePrefix = if (filterPartyIndex == -1) getString(R.string.analysis_label_all) else getString(R.string.analysis_label_party_format, filterPartyIndex + 1)

        val dialog = AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle(getString(R.string.analysis_title_appearance_format, titlePrefix))
            .setView(container)
            .setPositiveButton(R.string.btn_close, null)
            .setNeutralButton("勝率の低い順", null)
            .setNegativeButton(if (showTrendGraphs) "グラフ非表示" else "グラフ表示", null)
            .create()

        fun titleSubject(): String = if (currentMode == 0) "敵モンスター" else "敵パーティ"

        fun updateTitle() {
            val base = "$titlePrefix: ${titleSubject()}出現率"
            dialog.setTitle(when (sortMode) {
                1 -> "勝率の低い順: $base"
                2 -> "勝率の高い順: $base"
                else -> base
            })
        }

        fun applySort() {
            if (currentMode == 0) {
                when (sortMode) {
                    0 -> monsterRankingList.sortWith(monsterOrderByCount)
                    1 -> monsterRankingList.sortWith(monsterOrderByRateAsc)
                    2 -> monsterRankingList.sortWith(monsterOrderByRateDesc)
                }
            } else {
                when (sortMode) {
                    0 -> partyRankingList.sortWith(partyOrderByCount)
                    1 -> partyRankingList.sortWith(partyOrderByRateAsc)
                    2 -> partyRankingList.sortWith(partyOrderByRateDesc)
                }
            }
        }

        fun refreshList() {
            val showEmpty = currentMode == 1 && partyRankingList.isEmpty()
            listView.visibility = if (showEmpty) View.GONE else View.VISIBLE
            emptyPartyView.visibility = if (showEmpty) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
        }

        fun selectTab(mode: Int) {
            if (currentMode == mode) return
            currentMode = mode
            updateTabStyle(monsterTab, currentMode == 0)
            updateTabStyle(partyTab, currentMode == 1)
            applySort()
            refreshList()
            updateTitle()
            updateSimpleHeader()
        }

        monsterTab.setOnClickListener { selectTab(0) }
        partyTab.setOnClickListener { selectTab(1) }

        dialog.setOnShowListener {
            val sortButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            sortButton.setOnClickListener {
                sortMode = (sortMode + 1) % 3
                when (sortMode) {
                    0 -> sortButton.text = "勝率の低い順"
                    1 -> sortButton.text = "勝率の高い順"
                    2 -> sortButton.text = "出現数の多い順"
                }
                applySort()
                refreshList()
                updateTitle()
            }
            // グラフ表示/非表示の切替 (ソートと同じく dismiss せずリスト再構築)
            val graphButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            graphButton.setOnClickListener {
                showTrendGraphs = !showTrendGraphs
                prefsStats.edit().putBoolean("show_stats_trend_graphs", showTrendGraphs).apply()
                graphButton.text = if (showTrendGraphs) "グラフ非表示" else "グラフ表示"
                refreshList()
                updateSimpleHeader()
            }
        }
        dialog.show()
    }

    data class MonsterRankData(
        val name: String,
        val count: Int,
        val appearanceRate: Double,
        val winRate: Double,
        val historyData: List<MonsterStatsGraphView.DualPointData> = emptyList()
    )

    /** 敵パーティ構成ランキング用 (順序無視・4体識別済み戦績のみ集計) */
    data class PartyRankData(
        val key: List<String>,         // 4 体ソート済み
        val count: Int,
        val appearanceRate: Double,
        val winRate: Double
    )

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
        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
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
        val records = dataManager.history.records
        val record = records.getOrNull(position) ?: return
        val isLatest = (position == records.size - 1)
        
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            setBackgroundColor(0xFF222222.toInt()) // 背景をダークに固定
        }

        fun createSectionTitle(text: String) = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 10, 0, 5)
            setTextColor(Color.LTGRAY)
        }

        fun createRoiImageView(label: String, score: Double, name: String = ""): View {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(5, 5, 5, 5)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams((60 * resources.displayMetrics.density).toInt(), (60 * resources.displayMetrics.density).toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFF333333.toInt())
                
                if (isLatest) {
                    val file = File(filesDir, "last_roi_$label.png")
                    if (file.exists()) setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                    else setImageResource(android.R.drawable.ic_menu_report_image)
                } else {
                    setImageResource(android.R.drawable.ic_menu_report_image)
                }
            }
            val scoreText = TextView(this).apply {
                text = String.format(Locale.US, "%.3f", score)
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(if (score >= 0.7) Color.GREEN else if (score >= 0.4) Color.YELLOW else Color.RED)
            }
            container.addView(imageView)
            container.addView(scoreText)
            if (name.isNotEmpty()) {
                container.addView(TextView(this).apply {
                    text = name
                    textSize = 8f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setTextColor(Color.WHITE)
                })
            }
            return container
        }

        // --- パーティ ＆ VS ---
        root.addView(createSectionTitle("【基本判定】"))
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        
        // P1〜P3の3枚を並べて表示
        val partyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f)
        }
        repeat(3) { i ->
            partyContainer.addView(createRoiImageView("party_p$i", record.partySelectScores?.getOrNull(i) ?: 0.0, "P${i + 1}"))
        }
        topRow.addView(partyContainer)

        topRow.addView(createRoiImageView("vs", record.vsScore ?: 0.0, "VSロゴ"))
        root.addView(topRow)

        // --- 味方 ---
        root.addView(createSectionTitle("【味方パーティ】"))
        val myRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        record.myParty.forEachIndexed { i, name ->
            myRow.addView(createRoiImageView("monster_$i", record.myPartyScores?.getOrNull(i) ?: 0.0, name))
        }
        root.addView(myRow)

        // --- 敵 ---
        root.addView(createSectionTitle("【敵パーティ】"))
        val enemyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        record.enemyParty.forEachIndexed { i, name ->
            enemyRow.addView(createRoiImageView("monster_${i + 4}", record.enemyPartyScores?.getOrNull(i) ?: 0.0, name))
        }
        root.addView(enemyRow)

        // --- 勝敗 ---
        root.addView(createSectionTitle("【勝敗ロゴ】"))
        val resultRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        resultRow.addView(createRoiImageView("result", record.resultScore ?: 0.0, record.result))
        root.addView(resultRow)

        if (!isLatest) {
            root.addView(TextView(this).apply {
                text = "※画像表示は最新の記録のみ対応しています"
                textSize = 10f
                setPadding(0, 20, 0, 0)
                setTextColor(Color.GRAY)
            })
        }

        val scrollView = ScrollView(this).apply { addView(root) }

        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle("マッチングスコア詳細")
            .setView(scrollView)
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun showPartyEditSelector(recordPos: Int) {
        val parties = arrayOf(
            getString(R.string.label_party_name_format, 1),
            getString(R.string.label_party_name_format, 2),
            getString(R.string.label_party_name_format, 3)
        )
        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
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
        val dialog = AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
            .setTitle(R.string.edit_monster_title)
            .setView(scroll)
            .create()
        val allMonsters = record.myParty + record.enemyParty
        allMonsters.forEachIndexed { index, name ->
            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(100, 100).apply { setMargins(4, 0, 4, 0) }
                // 他のサムネと統一: 正方形 ROI を CENTER_CROP で埋める + 角丸
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
            applyRoundedCorners(imageView)
            imageView.apply {
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
                    // GridView の自動カラム幅で width が拡張されるため、onMeasure で width = height を
                    // 強制し、正方形セルにする (旧版はテンプレ縦長 (78x130) がそのまま見えていた)
                    val imageView = (convertView as? ImageView) ?: object : ImageView(this@HistoryActivity) {
                        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
                        }
                    }.apply {
                        layoutParams = android.widget.AbsListView.LayoutParams(
                            android.widget.AbsListView.LayoutParams.MATCH_PARENT,
                            android.widget.AbsListView.LayoutParams.WRAP_CONTENT
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setPadding(4, 4, 4, 4)
                    }
                    applyRoundedCorners(imageView)
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
        val dialog = AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
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
        AlertDialog.Builder(this, R.style.Theme_NakamonRec_Dialog)
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

    private fun showTopToast(message: String) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        toast.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 200)
        toast.show()
    }
}
