package com.android.nakamonrec

import org.opencv.core.Mat

data class MonsterData(
    val name: String,
    val fileName: String,
    var templateMat: Mat? = null
)

data class BattleRecord(
    val timestamp: String,
    val result: String,
    val partyIndex: Int,
    val myParty: List<String>,
    val enemyParty: List<String>
)

data class BattleHistory(
    var totalWins: Int = 0,
    var totalLosses: Int = 0,
    val records: MutableList<BattleRecord> = mutableListOf()
)

data class BattleStats(
    val totalWins: Int,
    val totalLosses: Int,
    val winRate: Double,
    val partyStats: List<PartyStat>
)

data class PartyStat(
    val index: Int,
    val wins: Int,
    val losses: Int,
    val winRate: Double
)
