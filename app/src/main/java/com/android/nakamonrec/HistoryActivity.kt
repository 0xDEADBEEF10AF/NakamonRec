package com.android.nakamonrec

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.nakamonrec.databinding.ActivityHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHistoryBinding
    private lateinit var dataManager: BattleDataManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "戦績"

        val prefs = getSharedPreferences("NakamonPrefs", MODE_PRIVATE)
        val currentFile = prefs.getString("last_file_name", "battle_history") ?: "battle_history"

        dataManager = BattleDataManager(this)
        dataManager.loadHistory(currentFile)

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnAnalyze.setOnClickListener {
            showAnalysisDialog()
        }

        setupUI()
    }

    private fun setupUI() {
        val stats = dataManager.getStatistics()

        // 総合戦績の反映
        binding.valTotalCount.text = getString(R.string.stats_count_format, stats.totalWins + stats.totalLosses)
        binding.valTotalWin.text = getString(R.string.stats_win_format, stats.totalWins)
        binding.valTotalLose.text = getString(R.string.stats_lose_format, stats.totalLosses)
        binding.valTotalRate.text = getString(R.string.stats_rate_format, String.format(Locale.US, "%.1f", stats.winRate))

        // 各パーティ戦績の反映
        stats.partyStats.forEach { party ->
            val count = party.wins + party.losses
            when (party.index) {
                0 -> {
                    binding.valP1Count.text = getString(R.string.stats_count_format, count)
                    binding.valP1Win.text = getString(R.string.stats_win_format, party.wins)
                    binding.valP1Lose.text = getString(R.string.stats_lose_format, party.losses)
                    binding.valP1Rate.text = getString(R.string.stats_rate_format, String.format(Locale.US, "%.1f", party.winRate))
                }
                1 -> {
                    binding.valP2Count.text = getString(R.string.stats_count_format, count)
                    binding.valP2Win.text = getString(R.string.stats_win_format, party.wins)
                    binding.valP2Lose.text = getString(R.string.stats_lose_format, party.losses)
                    binding.valP2Rate.text = getString(R.string.stats_rate_format, String.format(Locale.US, "%.1f", party.winRate))
                }
                2 -> {
                    binding.valP3Count.text = getString(R.string.stats_count_format, count)
                    binding.valP3Win.text = getString(R.string.stats_win_format, party.wins)
                    binding.valP3Lose.text = getString(R.string.stats_lose_format, party.losses)
                    binding.valP3Rate.text = getString(R.string.stats_rate_format, String.format(Locale.US, "%.1f", party.winRate))
                }
            }
        }

        binding.recyclerViewHistory.apply {
            val layoutManager = LinearLayoutManager(this@HistoryActivity)
            layoutManager.reverseLayout = true
            layoutManager.stackFromEnd = true
            this.layoutManager = layoutManager
            this.adapter = BattleHistoryAdapter(dataManager.history.records, dataManager.monsterMaster) { position ->
                showEditRecordDialog(position)
            }
        }
    }

    private fun showAnalysisDialog() {
        val records = dataManager.history.records
        if (records.isEmpty()) {
            Toast.makeText(this, "データがありません", Toast.LENGTH_SHORT).show()
            return
        }

        val appearanceCount = mutableMapOf<String, Int>()
        val winAgainstCount = mutableMapOf<String, Int>()

        records.forEach { record ->
            record.enemyParty.filter { it.isNotEmpty() }.distinct().forEach { name ->
                appearanceCount[name] = appearanceCount.getOrDefault(name, 0) + 1
                if (record.result == "WIN") {
                    winAgainstCount[name] = winAgainstCount.getOrDefault(name, 0) + 1
                }
            }
        }

        val totalBattles = records.size
        val rankingList = appearanceCount.map { (name, count) ->
            val wins = winAgainstCount.getOrDefault(name, 0)
            val winRate = if (count > 0) (wins.toDouble() / count * 100) else 0.0
            val appearanceRate = (count.toDouble() / totalBattles * 100)
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
                view.findViewById<TextView>(R.id.textAppearance).text = getString(
                    R.string.rank_appearance_format,
                    data.count,
                    String.format(Locale.US, "%.1f", data.appearanceRate)
                )
                val winRateTextView = view.findViewById<TextView>(R.id.textWinRate)
                winRateTextView.text = getString(R.string.rank_win_rate_format, String.format(Locale.US, "%.1f", data.winRate))
                when {
                    data.winRate < 40.0 -> winRateTextView.setTextColor("#F09199".toColorInt())
                    data.winRate > 60.0 -> winRateTextView.setTextColor("#90D7EC".toColorInt())
                    else -> winRateTextView.setTextColor("#CCCCCC".toColorInt())
                }
                val imageView = view.findViewById<ImageView>(R.id.imageMonster)
                val monsterData = dataManager.monsterMaster.find { it.name == data.name }
                if (monsterData != null) {
                    try {
                        assets.open("templates/${monsterData.fileName}").use {
                            imageView.setImageBitmap(android.graphics.BitmapFactory.decodeStream(it))
                        }
                    } catch (_: Exception) {
                        imageView.setImageResource(android.R.drawable.ic_menu_help)
                    }
                }
                return view
            }
        }
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle("敵モンスター出現率ランキング")
            .setView(listView)
            .setPositiveButton("閉じる", null)
            .setNeutralButton("勝率の低い順", null)
            .create()

        dialog.setOnShowListener {
            val sortButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            var sortedByAppearance = true
            sortButton.setOnClickListener {
                if (sortedByAppearance) {
                    rankingList.sortBy { it.winRate }
                    sortButton.text = "出現数の多い順"
                } else {
                    rankingList.sortByDescending { it.count }
                    sortButton.text = "勝率の低い順"
                }
                sortedByAppearance = !sortedByAppearance
                adapter.notifyDataSetChanged()
            }
        }
        dialog.show()
    }

    data class MonsterRankData(
        val name: String,
        val count: Int,
        val appearanceRate: Double,
        val winRate: Double
    )

    private fun showEditRecordDialog(position: Int) {
        val record = dataManager.history.records[position]
        val options = arrayOf(
            "勝敗を修正 (${if (record.result == "WIN") "→LOSE" else "→WIN"})",
            "選択パーティを修正 (現在: P${record.partyIndex + 1})",
            "使用モンスターを修正",
            "この1戦を削除",
            "この1戦の次に戦績を追加"
        )
        AlertDialog.Builder(this)
            .setTitle("レコードの編集")
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

    private fun showPartyEditSelector(position: Int) {
        val parties = arrayOf("パーティ 1", "パーティ 2", "パーティ 3")
        AlertDialog.Builder(this)
            .setTitle("正しいパーティを選択")
            .setItems(parties) { _, which ->
                val record = dataManager.history.records[position]
                updateAndSave(position, record.copy(partyIndex = which))
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
            .setTitle("修正するモンスターをタップ")
            .setView(scroll)
            .create()
        val allMonsters = record.myParty + record.enemyParty
        allMonsters.forEachIndexed { index, name ->
            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(100, 100).apply {
                    setMargins(4, 0, 4, 0)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(if (index < 4) android.R.drawable.editbox_dropdown_light_frame else android.R.drawable.editbox_dropdown_dark_frame)
                val monsterData = dataManager.monsterMaster.find { it.name == name }
                if (monsterData != null) {
                    try {
                        assets.open("templates/${monsterData.fileName}").use {
                            setImageBitmap(android.graphics.BitmapFactory.decodeStream(it))
                        }
                    } catch (_: Exception) {
                        setImageResource(android.R.drawable.ic_menu_help)
                    }
                }
                setOnClickListener {
                    dialog.dismiss()
                    val isMyParty = index < 4
                    val monsterIndex = if (isMyParty) index else index - 4
                    showMonsterPicker { selectedName ->
                        val newMyParty = record.myParty.toMutableList()
                        val newEnemyParty = record.enemyParty.toMutableList()
                        if (isMyParty) newMyParty[monsterIndex] = selectedName
                        else newEnemyParty[monsterIndex] = selectedName
                        updateAndSave(recordPos, record.copy(myParty = newMyParty, enemyParty = newEnemyParty))
                    }
                }
            }
            if (index == 4) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(2, 60).apply {
                        setMargins(10, 0, 10, 0)
                    }
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
                            imageView.setImageBitmap(android.graphics.BitmapFactory.decodeStream(it))
                        }
                    } catch (_: Exception) {
                        imageView.setImageResource(android.R.drawable.ic_menu_help)
                    }
                    return imageView
                }
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("モンスターを選択")
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
        setupUI()
    }

    private fun insertRecordAfter(position: Int) {
        val baseRecord = dataManager.history.records[position]
        val newRecord = baseRecord.copy(
            timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
        )
        dataManager.history.records.add(position + 1, newRecord)
        if (newRecord.result == "WIN") dataManager.history.totalWins++
        else dataManager.history.totalLosses++
        dataManager.saveHistory()
        setupUI()
        Toast.makeText(this, "レコードを追加しました", Toast.LENGTH_SHORT).show()
        showEditRecordDialog(position + 1)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
