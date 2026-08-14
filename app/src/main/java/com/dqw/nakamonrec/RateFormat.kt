package com.dqw.nakamonrec

import java.util.Locale
import kotlin.math.roundToLong

/**
 * 勝率・出現率などのパーセント表示フォーマッタ。
 * %.1f で「100.0%」になる値 (>= 99.95) のときだけ小数点を省いて「100%」にする。
 * 4文字ぶんのフル幅表示が小画面の戦績カードで折り返すための策で、
 * 100% 以外は従来どおり小数第1位まで表示する (iOS RateFormat.swift と同一仕様)。
 */
object RateFormat {
    fun percent(value: Double): String =
        if ((value * 10).roundToLong() >= 1000L) "100%"
        else String.format(Locale.US, "%.1f%%", value)

    fun percent(value: Float): String = percent(value.toDouble())
}
