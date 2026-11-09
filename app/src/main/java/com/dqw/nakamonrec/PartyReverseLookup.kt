package com.dqw.nakamonrec

import android.content.Context

/**
 * 選択パーティ逆引き (2026-11-09 ビーフ設計、iOS `PartyReverseLookup.swift` と同仕様)。
 * パーティ選択画面を識別できない端末 (タブレット等) 向けの奥の手。ON のあいだは、記録する戦闘の
 * 味方 4 体の並びと完全一致するレコードを現在の戦績ファイルの新しい順に探し、最初に見つかった
 * レコードのパーティ番号を「問答無用で」採用する (画面からの検知結果は使わない)。
 * 見つからなければ P? (-1)。利用者が最初に P1〜P3 を手動で割り当て、パーティ編集のたびに
 * 割り当て直すことが前提。
 */
object PartyReverseLookup {
    private const val PREFS = "party_reverse_lookup_prefs"
    private const val KEY = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }

    /** 逆引きの結果。partyIndex は 0..2 (P1=0)、見つからなければ -1 */
    data class Match(val partyIndex: Int, val matchedTimestamp: String?)

    /**
     * [myParty] (味方 4 体、VS 画面の並び順) と完全一致するレコードを [records] の末尾 (= 新しい方) から探す。
     * 探索対象から外すもの: パーティが P? のレコード、味方に識別失敗 ("?") を含むレコード。
     * 今回の味方に "?" が含まれていれば一致させようがないので -1 を返す
     */
    fun lookup(myParty: List<String>, records: List<BattleRecord>): Match {
        if (myParty.size != 4 || myParty.contains("?")) return Match(-1, null)
        for (r in records.asReversed()) {
            if (r.partyIndex !in 0..2 || r.myParty.contains("?")) continue
            if (r.myParty == myParty) return Match(r.partyIndex, r.timestamp)
        }
        return Match(-1, null)
    }
}
