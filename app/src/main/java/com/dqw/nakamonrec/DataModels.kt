package com.dqw.nakamonrec

import org.opencv.core.Mat

/**
 * モンスターのマスターデータ
 */
data class MonsterData(
    val name: String,
    val fileName: String,
    val isWall: Boolean = false,
    var templateMat: Mat? = null
)

/**
 * 戦闘記録の1セッション
 */
data class BattleRecord(
    val timestamp: String,
    val result: String,
    val partyIndex: Int,
    var myParty: List<String>,
    var enemyParty: List<String>,
    var vsScore: Double? = null,
    var myPartyScores: List<Double>? = null,
    var enemyPartyScores: List<Double>? = null,
    var resultScore: Double? = null,
    var partySelectScores: List<Double>? = null
)

/**
 * グランプリ (大会) 1 戦のレーティング記録。
 * 通常の BattleRecord とは別系列 (BattleHistory.grandPrixRecords) に保存する
 * (レーティング読み取りの誤りが本体戦績を汚さないための分離)。
 * 読み取り対象は現在レーティングと必要レーティング(あと)の2値のみ。変動は
 * 連続する currentRating の差分として表示時に導出する。iOS と同一 JSON スキーマ。
 */
data class GrandPrixRecord(
    val timestamp: String,       // "yyyy-MM-dd HH:mm:ss" (対応する BattleRecord と一致)
    val result: String,          // "WIN" / "LOSE"
    val currentRating: Double,   // 現在のレーティング (白・常に正)
    val neededRating: Double? = null,   // 必要レーティング「あと」。GM/ランクアップ戦では null
    val nationalRank: String? = null,   // 全国ランキング表示テキスト (数値化しない)
    val isRankUp: Boolean = false,
    val lowConfidence: Boolean = false,
    val screenshotFile: String? = null,
    val rankTier: String? = null        // ランク帯 (手動設定。rankTiers のいずれか)。将来エンブレム表示に使う
) {
    /** ボーダー (次ランク到達ライン) = 現在 + 必要あと。必要が無ければ null */
    val borderRating: Double?
        get() = neededRating?.let { currentRating + it }

    companion object {
        /** ランク帯の選択肢 (低い順)。レーティングを記録する区間のみ (マスター3〜GM)。iOS と同一。 */
        val rankTiers: List<String> = listOf(
            "マスター3", "マスター2", "マスター1", "グランドマスター"
        )

        /**
         * ランク帯のエンブレムサムネイル画像名 (drawable / iOS Asset Catalog 共通)。
         * ゲームのエンブレム画像 (モンスターサムネイルと同じ著作物リスク承知の上で採用)。
         */
        fun rankEmblemAsset(tier: String?): String? = when (tier) {
            "マスター3" -> "rank_master3"
            "マスター2" -> "rank_master2"
            "マスター1" -> "rank_master1"
            "グランドマスター" -> "rank_grandmaster"
            else -> null
        }

        /**
         * ランク帯のオリジナル色付きバッジ情報 (略称 to 16進カラー配列 #なし)。
         * ゲームの紋章 (著作物) は使わず自前の色バッジで表現。iOS と同一。
         * 色配列は左上→右下のグラデーション。金銀銅はハイライト→地色→影の
         * 3 段でメタリック調に、グランドマスターは 6 色の虹。
         * マスター3=銅 / マスター2=銀 / マスター1=金 / グランドマスター=虹。
         */
        fun rankBadge(tier: String?): Pair<String, List<String>>? = when (tier) {
            "マスター3" -> "M3" to listOf("E8A56C", "CD7F32", "7A4A16")
            "マスター2" -> "M2" to listOf("F2F5F8", "AAB2BD", "6E7683")
            "マスター1" -> "M1" to listOf("FFE27A", "F4C430", "A8781C")
            "グランドマスター" -> "GM" to listOf("E74C3C", "E67E22", "F1C40F", "2ECC71", "3498DB", "9B59B6")
            else -> null
        }
    }
}

/**
 * 履歴全体のデータ構造
 */
data class BattleHistory(
    var totalWins: Int = 0,
    var totalLosses: Int = 0,
    val records: MutableList<BattleRecord> = mutableListOf(),
    // グランプリのレーティング記録 (別系列)。「1 ファイル = 1 グランプリ」運用のため
    // 戦績と同じファイルに同梱。旧ファイル (キー無し) は null でデコードされ後方互換。
    var grandPrixRecords: MutableList<GrandPrixRecord>? = null
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
        BoxConfig(850f / 1080f, 1030f / 2364f, 50, 100),
        BoxConfig(850f / 1080f, 1430f / 2364f, 50, 100),
        BoxConfig(850f / 1080f, 1830f / 2364f, 50, 100)
    ),
    // 勝敗判定 (WINとLOSEを個別に管理)
    var winBox: BoxConfig = BoxConfig(540f / 1080f, 720f / 2364f, 1000, 400),
    var loseBox: BoxConfig = BoxConfig(540f / 1080f, 720f / 2364f, 1000, 400)
)
