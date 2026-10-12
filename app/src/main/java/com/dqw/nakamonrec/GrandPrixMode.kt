package com.dqw.nakamonrec

import android.content.Context

/**
 * グランプリ記録モードの ON/OFF (SharedPreferences で永続化)。
 *
 * B 方針: 大会用 VS 画面のカスタムテンプレを校正した状態 = グランプリモード ON。
 * 校正でインポート画像が大会用 VS と判定されたら true、通常 VS なら false。
 * キャプチャは、この値が true かつ大会 VS で始まった戦闘のときだけ、WIN/LOSE 後に
 * レーティング画面を待って読む。false (既定=全既存ユーザー) の間は挙動不変。
 * iOS の GrandPrixMode (App Group フラグ) と対応。
 */
object GrandPrixMode {
    private const val PREFS = "grandprix_prefs"
    private const val KEY = "grandPrixModeEnabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }

    /**
     * レーティングパネル行の学習済み縦オフセット (1080 基準 px、null = 未学習)。
     * RatingPanelReader がアンカー探索で見つけた端末固有のずれを保存し、次回以降は探索なしで
     * 固定バンドをこの量だけずらして読む (ユーザー操作なしの自動校正)。iOS と対応。
     */
    private const val KEY_ROW_OFFSET = "grandPrixRowOffsetY"

    fun learnedRowOffsetY(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_ROW_OFFSET)) prefs.getInt(KEY_ROW_OFFSET, 0) else null
    }

    fun setLearnedRowOffsetY(context: Context, offsetY: Int?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (offsetY == null) editor.remove(KEY_ROW_OFFSET) else editor.putInt(KEY_ROW_OFFSET, offsetY)
        editor.apply()
    }
}
