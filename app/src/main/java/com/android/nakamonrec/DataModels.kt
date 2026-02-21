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

/**
 * 座標比率を保持するシンプルなデータクラス
 */
data class PosRatio(val x: Float, val y: Float)

/**
 * 位置とサイズを含む校正ユニット
 */
data class BoxConfig(
    var centerX: Float,
    var centerY: Float,
    var width: Int,
    var height: Int
)

/**
 * ユーザーによる校正データ
 * デフォルト値として 1080x2364 基準の値を保持
 */
data class CalibrationData(
    // VSロゴ (REF: 540, 1260)
    var vsBox: BoxConfig = BoxConfig(540f / 1080f, 1260f / 2364f, 364, 208),
    
    // 味方パーティ (REF: Y=1635)
    var myPartyBoxes: List<BoxConfig> = listOf(
        BoxConfig(196f / 1080f, 1635f / 2364f, 80, 130),
        BoxConfig(391f / 1080f, 1635f / 2364f, 80, 130),
        BoxConfig(585f / 1080f, 1635f / 2364f, 80, 130),
        BoxConfig(780f / 1080f, 1635f / 2364f, 80, 130)
    ),
    
    // 相手パーティ (REF: Y=915)
    var enemyPartyBoxes: List<BoxConfig> = listOf(
        BoxConfig(201f / 1080f, 915f / 2364f, 80, 130),
        BoxConfig(396f / 1080f, 915f / 2364f, 80, 130),
        BoxConfig(590f / 1080f, 915f / 2364f, 80, 130),
        BoxConfig(785f / 1080f, 915f / 2364f, 80, 130)
    ),
    
    // パーティ選択マーク (REF: X=30)
    var partySelectBoxes: List<BoxConfig> = listOf(
        BoxConfig(30f / 1080f, 1030f / 2364f, 50, 100),
        BoxConfig(30f / 1080f, 1430f / 2364f, 50, 100),
        BoxConfig(30f / 1080f, 1830f / 2364f, 50, 100)
    ),
    
    // 勝敗判定ロゴ (REF: 540, 720)
    var resultBox: BoxConfig = BoxConfig(540f / 1080f, 720f / 2364f, 1000, 400)
)
