package com.dqw.nakamonrec

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BattleDataManager(private val context: Context) {
    private val gson = Gson()
    // デフォルト名を統一
    var currentFileName: String = "default_record"
    var history: BattleHistory = BattleHistory()
    var monsterMaster: List<MonsterData> = emptyList()
    var analysisMode: String = "normal" // "normal" or "light"
    val lightModeMonsters = mutableSetOf<String>() // 軽負荷モードの対象モンスター名

    // ユーザー可変の検知閾値 (デフォルト 0.4、許容範囲 0.4〜0.8)
    var vsThreshold: Double = BattleAnalyzer.DEFAULT_VS_THRESHOLD
    var winThreshold: Double = BattleAnalyzer.DEFAULT_WIN_THRESHOLD
    var loseThreshold: Double = BattleAnalyzer.DEFAULT_LOSE_THRESHOLD

    init {
        loadMasterData()
    }

    private fun loadMasterData() {
        // 解析モードと絞り込み設定の読み込み
        val prefs = context.getSharedPreferences("analysis_prefs", Context.MODE_PRIVATE)
        analysisMode = prefs.getString("mode", "normal") ?: "normal"
        
        lightModeMonsters.clear()
        if (prefs.contains("light_monsters")) {
            lightModeMonsters.addAll(prefs.getStringSet("light_monsters", emptySet()) ?: emptySet())
        }

        vsThreshold = prefs.getFloat("vs_threshold", BattleAnalyzer.DEFAULT_VS_THRESHOLD.toFloat()).toDouble()
            .coerceIn(BattleAnalyzer.THRESHOLD_MIN, BattleAnalyzer.THRESHOLD_MAX)
        winThreshold = prefs.getFloat("win_threshold", BattleAnalyzer.DEFAULT_WIN_THRESHOLD.toFloat()).toDouble()
            .coerceIn(BattleAnalyzer.THRESHOLD_MIN, BattleAnalyzer.THRESHOLD_MAX)
        loseThreshold = prefs.getFloat("lose_threshold", BattleAnalyzer.DEFAULT_LOSE_THRESHOLD.toFloat()).toDouble()
            .coerceIn(BattleAnalyzer.THRESHOLD_MIN, BattleAnalyzer.THRESHOLD_MAX)

        try {
            // モンスターマスタの読み込み
            val json = context.assets.open("monsters.json").bufferedReader().use { it.readText() }
            monsterMaster = gson.fromJson(json, Array<MonsterData>::class.java).toList()

            // 保存データが空（または未存在）の場合に target_monsters.json から初期値を読み込む
            if (lightModeMonsters.isEmpty()) {
                try {
                    val targetJson = context.assets.open("target_monsters.json").bufferedReader().use { it.readText() }
                    val defaultTargets = gson.fromJson(targetJson, Array<MonsterData>::class.java)
                    if (defaultTargets != null) {
                        lightModeMonsters.addAll(defaultTargets.map { it.name })
                        // 初回起動時や、意図的に空にした場合でも「初期リスト」は一度保存する
                        saveAnalysisSettings()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("DataManager", "Failed to load target_monsters.json: ${e.message}")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addRecord(
        result: String,
        myParty: List<String>,
        enemyParty: List<String>,
        partyIndex: Int,
        vsScore: Double? = null,
        myPartyScores: List<Double>? = null,
        enemyPartyScores: List<Double>? = null,
        resultScore: Double? = null,
        partySelectScores: List<Double>? = null
    ): BattleRecord {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val record = BattleRecord(
            timestamp, result, partyIndex, myParty, enemyParty,
            vsScore, myPartyScores, enemyPartyScores, resultScore,
            partySelectScores
        )
        // グランプリ追記 (キャプチャスレッド) との read-modify-write 競合を防ぐ
        synchronized(saveLock) {
            if (result == "WIN") history.totalWins++ else history.totalLosses++
            history.records.add(record)
            saveHistory()
        }
        return record
    }

    fun updateRecord(record: BattleRecord) {
        // オブジェクト参照が同じなので history.records 内のデータも更新されているが
        // 明示的に保存を走らせる
        saveHistory()
    }

    // グランプリ記録 (通常戦績とは別系列 history.grandPrixRecords)。
    // グランプリ追記 (キャプチャスレッド) と戦績追記が同時に走り得るため saveLock で直列化。
    private val saveLock = Any()

    fun loadGrandPrixRecords(): List<GrandPrixRecord> =
        history.grandPrixRecords?.toList() ?: emptyList()

    fun appendGrandPrix(record: GrandPrixRecord) {
        synchronized(saveLock) {
            val list = history.grandPrixRecords ?: mutableListOf<GrandPrixRecord>().also {
                history.grandPrixRecords = it
            }
            list.add(record)
            saveHistory()
        }
    }

    fun updateGrandPrix(record: GrandPrixRecord) {
        synchronized(saveLock) {
            val list = history.grandPrixRecords ?: return
            val idx = list.indexOfFirst { it.timestamp == record.timestamp }
            if (idx >= 0) {
                list[idx] = record
                saveHistory()
            }
        }
    }

    fun deleteGrandPrix(timestamp: String) {
        synchronized(saveLock) {
            val list = history.grandPrixRecords ?: return
            if (list.removeAll { it.timestamp == timestamp }) {
                saveHistory()
            }
        }
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

    /**
     * フライトレコーダー（直近1戦のログ）をクリアする
     */
    fun clearFlightLog() {
        File(context.filesDir, "latest_battle.log").delete()
        File(context.filesDir, "previous_battle.log").delete()
    }

    /**
     * 戦闘開始時に呼ぶ: 直前ログを previous へ退避し、最新ログを空にする (iOS同期)
     */
    fun rotateFlightLog() {
        val latest = File(context.filesDir, "latest_battle.log")
        val previous = File(context.filesDir, "previous_battle.log")
        if (previous.exists()) previous.delete()
        if (latest.exists()) {
            latest.renameTo(previous)
        }
    }

    /**
     * フライトレコーダーに1行追記する (iOS同期フォーマット)
     */
    fun appendFlightLog(message: String) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val timestamp = sdf.format(Date())
        val logLine = "$timestamp  $message\n" // iOSに合わせ2スペース
        try {
            context.openFileOutput("latest_battle.log", Context.MODE_APPEND).use {
                it.write(logLine.toByteArray())
            }
        } catch (_: Exception) {}
    }

    /**
     * フライトレコーダーの内容を読み込む
     */
    fun readFlightLog(): String {
        val file = File(context.filesDir, "latest_battle.log")
        if (!file.exists()) return "(まだログがありません)"
        return file.readText()
    }

    fun readPreviousFlightLog(): String? {
        val file = File(context.filesDir, "previous_battle.log")
        if (!file.exists()) return null
        return file.readText()
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

        val partyStats = (0..2).map { idx ->
            val pRecords = records.filter { it.partyIndex == idx }
            val pCount = pRecords.size
            val pWins = pRecords.count { it.result == "WIN" }
            val pLosses = pRecords.count { it.result == "LOSE" }
            val pWinRate = if (pCount > 0) (pWins.toDouble() / pCount) * 100 else 0.0
            val pUsageRate = if (totalCount > 0) (pCount.toDouble() / totalCount) * 100 else 0.0
            
            PartyStat(idx, pWins, pLosses, pWinRate, pUsageRate)
        }

        return BattleStats(totalWins, totalLosses, totalWinRate, partyStats)
    }

    fun saveAnalysisSettings() {
        val prefs = context.getSharedPreferences("analysis_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("mode", analysisMode)
            .putStringSet("light_monsters", lightModeMonsters)
            .apply()
    }

    fun saveThresholds(vs: Double, win: Double, lose: Double) {
        vsThreshold = vs.coerceIn(BattleAnalyzer.THRESHOLD_MIN, BattleAnalyzer.THRESHOLD_MAX)
        winThreshold = win.coerceIn(BattleAnalyzer.THRESHOLD_MIN, BattleAnalyzer.THRESHOLD_MAX)
        loseThreshold = lose.coerceIn(BattleAnalyzer.THRESHOLD_MIN, BattleAnalyzer.THRESHOLD_MAX)
        val prefs = context.getSharedPreferences("analysis_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("vs_threshold", vsThreshold.toFloat())
            .putFloat("win_threshold", winThreshold.toFloat())
            .putFloat("lose_threshold", loseThreshold.toFloat())
            .apply()
    }

    fun resetThresholds() {
        saveThresholds(
            BattleAnalyzer.DEFAULT_VS_THRESHOLD,
            BattleAnalyzer.DEFAULT_WIN_THRESHOLD,
            BattleAnalyzer.DEFAULT_LOSE_THRESHOLD
        )
    }
}
