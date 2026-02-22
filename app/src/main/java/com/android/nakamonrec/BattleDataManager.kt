package com.android.nakamonrec

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BattleDataManager(private val context: Context) {
    private val gson = Gson()
    var currentFileName: String = "default_stats"
    var history: BattleHistory = BattleHistory()
    var monsterMaster: List<MonsterData> = emptyList()

    init {
        loadMasterData()
    }

    private fun loadMasterData() {
        try {
            val json = context.assets.open("monsters.json").bufferedReader().use { it.readText() }
            monsterMaster = gson.fromJson(json, Array<MonsterData>::class.java).toList()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addRecord(result: String, myParty: List<String>, enemyParty: List<String>, partyIndex: Int) {
        if (result == "WIN") history.totalWins++ else history.totalLosses++

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        history.records.add(BattleRecord(timestamp, result, partyIndex, myParty, enemyParty))
        saveHistory()
    }

    fun loadHistory(fileName: String) {
        this.currentFileName = fileName
        val file = File(context.filesDir, "$fileName.json")
        if (file.exists()) {
            val json = file.readText()
            history = Gson().fromJson(json, BattleHistory::class.java)
        } else {
            history = BattleHistory()
        }
    }

    fun saveHistory() {
        val file = File(context.filesDir, "$currentFileName.json")
        val json = Gson().toJson(history)
        file.writeText(json)
    }

    fun resetHistory() {
        history = BattleHistory()
        saveHistory()
    }

    fun getStatistics(): BattleStats {
        val records = history.records
        val totalWins = records.count { it.result == "WIN" }
        val totalLosses = records.count { it.result == "LOSE" }
        val totalCount = records.size
        val totalWinRate = if (totalCount > 0) (totalWins.toDouble() / totalCount) * 100 else 0.0

        // パーティごとの集計 (0, 1, 2)
        val partyStats = (0..2).map { idx ->
            val pRecords = records.filter { it.partyIndex == idx }
            val pCount = pRecords.size
            val pWins = pRecords.count { it.result == "WIN" }
            val pLosses = pRecords.count { it.result == "LOSE" }
            val pWinRate = if (pCount > 0) (pWins.toDouble() / pCount) * 100 else 0.0
            
            // 使用率の計算 (そのパーティの戦闘数 / 全戦闘数)
            val pUsageRate = if (totalCount > 0) (pCount.toDouble() / totalCount) * 100 else 0.0
            
            PartyStat(idx, pWins, pLosses, pWinRate, pUsageRate)
        }

        return BattleStats(totalWins, totalLosses, totalWinRate, partyStats)
    }
}
