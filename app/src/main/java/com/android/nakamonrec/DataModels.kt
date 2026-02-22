package com.android.nakamonrec

import org.opencv.core.Mat

/**
 * モンスターのマスターデータ
 */
data class MonsterData(
    val name: String,
    val fileName: String,
    var templateMat: Mat? = null
)

/**
 * 戦闘記録の1セッション
 */
data class BattleRecord(
    val timestamp: String,
    val result: String,
    val partyIndex: Int,
    val myParty: List<String>,
    val enemyParty: List<String>
)

/**
 * 履歴全体のデータ構造
 */
data class BattleHistory(
    var totalWins: Int = 0,
    var totalLosses: Int = 0,
    val records: MutableList<BattleRecord> = mutableListOf()
)

/**
 * 統計表示用データクラス
 */
data class BattleStats(
    val totalWins: Int,
    val totalLosses: Int,
    val winRate: Double,
    val partyStats: List<PartyStat>
)

/**
 * パーティごとの戦績
 */
data class PartyStat(
    val index: Int,
    val wins: Int,
    val losses: Int,
    val winRate: Double,
    val usageRate: Double // パーティ使用率 (0.0-100.0)
)

/**
 * 位置とサイズを含む校正ユニット
 */
data class BoxConfig(
    var centerX: Float, // 画面全体に対する比率 (0.0-1.0)
    var centerY: Float, // 画面全体に対する比率 (0.0-1.0)
    var width: Int,     // 実機ピクセル幅
    var height: Int     // 実機ピクセル高
)

/**
 * ユーザーによる校正データ
 */
data class CalibrationData(
    // UI全体のスケール（テンプレートに対する倍率）
    var uiScale: Float = 1.0f,

    // 戦闘開始関連
    var vsBox: BoxConfig = BoxConfig(540f / 1080f, 1260f / 2364f, 280, 160),
    var myPartyBoxes: List<BoxConfig> = listOf(
        BoxConfig(196f / 1080f, 1635f / 2364f, 80, 130),
        BoxConfig(391f / 1080f, 1635f / 2364f, 80, 130),
        BoxConfig(585f / 1080f, 1635f / 2364f, 80, 130),
        BoxConfig(780f / 1080f, 1635f / 2364f, 80, 130)
    ),
    var enemyPartyBoxes: List<BoxConfig> = listOf(
        BoxConfig(201f / 1080f, 915f / 2364f, 80, 130),
        BoxConfig(396f / 1080f, 915f / 2364f, 80, 130),
        BoxConfig(590f / 1080f, 915f / 2364f, 80, 130),
        BoxConfig(785f / 1080f, 915f / 2364f, 80, 130)
    ),
    // パーティ選択
    var partySelectBoxes: List<BoxConfig> = listOf(
        BoxConfig(30f / 1080f, 1030f / 2364f, 50, 100),
        BoxConfig(30f / 1080f, 1430f / 2364f, 50, 100),
        BoxConfig(30f / 1080f, 1830f / 2364f, 50, 100)
    ),
    // 勝敗判定 (WINとLOSEを個別に管理)
    var winBox: BoxConfig = BoxConfig(540f / 1080f, 720f / 2364f, 1000, 400),
    var loseBox: BoxConfig = BoxConfig(540f / 1080f, 720f / 2364f, 1000, 400)
)
