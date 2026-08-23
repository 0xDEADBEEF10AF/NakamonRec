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
}
