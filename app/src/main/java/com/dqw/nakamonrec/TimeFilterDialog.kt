package com.dqw.nakamonrec

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.toColorInt
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * タイムフィルタの範囲指定ダイアログ (2026-11-17 ビーフ設計、iOS `TimeFilterView` と同仕様)。
 * 戦績画面のフィルタモードでレコードの日時をタップすると開く。
 * - 横軸 = グランプリ集計が使えるなら日時 (寝ている時間の横ばいもそのまま見せる)、
 *   使えないなら戦闘数 (日時軸の勝率推移は何のグラフか分かりにくかった、ビーフ確認 2026-11-18)。
 *   上部の開始/終了はどちらのときも日時で表示する
 * - 縦軸 = グランプリ集計が使える (= 虫眼鏡メニューでグレーアウトされない) ならレーティング + ボーダー、
 *   使えないなら 20 戦移動平均勝率 (戦績画面右上のグラフと同じ計算)
 * - 左右 2 本の縦線をドラッグして範囲を決める。縦線は最も近いレコードの日時に吸着し、範囲外は暗くする
 * - グラフは他のフィルタに関係なくファイル全体を描き、「フィルタ実行」で他の条件と AND で組み合わせる
 */
object TimeFilterDialog {

    fun show(
        context: Context,
        records: List<BattleRecord>,
        grandPrixRecords: List<GrandPrixRecord>,
        useGrandPrix: Boolean,
        initialRange: ClosedRange<String>?,
        onApply: (ClosedRange<String>) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        // 吸着先 = レコードの日時 (古い順、パースできたものだけ)
        val stops = records.mapNotNull { r ->
            val t = try { sdf.parse(r.timestamp)?.time } catch (_: Exception) { null }
            t?.let { r.timestamp to it }
        }.sortedBy { it.second }

        val graph = TimeRangeGraphView(context)
        if (useGrandPrix) {
            val pts = grandPrixRecords.filter { !it.isUnread }.mapNotNull { g ->
                val t = try { sdf.parse(g.timestamp)?.time } catch (_: Exception) { null } ?: return@mapNotNull null
                Triple(t, g.currentRating, g.borderRating)
            }.sortedBy { it.first }
            graph.lines = listOf(
                TimeRangeGraphView.Line(pts.map { it.first to it.second }, "#F09199".toColorInt()),
                TimeRangeGraphView.Line(pts.mapNotNull { p -> p.third?.let { p.first to it } }, "#90D7EC".toColorInt())
            )
            graph.isPercent = false
        } else {
            // 戦績画面右上のグラフと同じ: 1〜20 戦目はそれまでの全戦、以降は直近 20 戦の勝率
            // 勝敗は stops (日時順) と同じ並びで引く (日時を読めないレコードがあっても添字がずれない)
            val resultByTs = records.associate { it.timestamp to it.result }
            val isWin = stops.map { resultByTs[it.first] == "WIN" }
            val window = 20
            // 横軸は戦闘数: x = stops の添字
            val pts = stops.indices.map { idx ->
                val start = maxOf(0, idx - window + 1)
                val wins = (start..idx).count { isWin[it] }
                idx.toLong() to wins.toDouble() / (idx - start + 1) * 100.0
            }
            graph.lines = listOf(TimeRangeGraphView.Line(pts, "#F09199".toColorInt()))
            graph.isPercent = true
        }
        graph.battleAxis = !useGrandPrix
        graph.stops = if (useGrandPrix) stops.map { it.second } else stops.indices.map { it.toLong() }
        val hasData = stops.isNotEmpty() && graph.lines.any { it.points.isNotEmpty() }
        graph.hasData = hasData
        if (hasData) {
            val s = initialRange?.let { r -> stops.indexOfFirst { it.first >= r.start } }?.takeIf { it >= 0 }
            val e = initialRange?.let { r -> stops.indexOfLast { it.first <= r.endInclusive } }?.takeIf { it >= 0 }
            if (s != null && e != null && s <= e) { graph.startIdx = s; graph.endIdx = e }
            else { graph.startIdx = 0; graph.endIdx = stops.size - 1 }
        }

        // 範囲の開始/終了日時 (データが無ければ枠だけで空欄)
        fun box(title: String): Pair<LinearLayout, TextView> {
            val value = TextView(context).apply {
                textSize = 14f; setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                text = " "
            }
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor("#2E2E2E".toColorInt()); cornerRadius = dp(8).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply { text = title; textSize = 11f; setTextColor("#8E8E93".toColorInt()) })
                addView(value)
            }
            return col to value
        }
        val (startBox, startText) = box("開始")
        val (endBox, endText) = box("終了")
        fun display(ts: String) = ts.replace('-', '.').take(16)
        fun refreshHeader() {
            if (!hasData) { startText.text = " "; endText.text = " "; return }
            startText.text = display(stops[graph.startIdx].first)
            endText.text = display(stops[graph.endIdx].first)
        }
        graph.onRangeChanged = { refreshHeader() }
        refreshHeader()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(startBox)
                addView(TextView(context).apply {
                    text = "〜"; setTextColor("#8E8E93".toColorInt()); setPadding(dp(6), 0, dp(6), 0)
                })
                addView(endBox)
            })
            addView(TextView(context).apply {
                text = if (useGrandPrix) "レーティング推移" else "勝率推移 (直近20戦)"
                textSize = 12f; setTextColor("#8E8E93".toColorInt()); setPadding(0, dp(12), 0, dp(4))
            })
            addView(graph, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(280)))
            if (useGrandPrix) {
                addView(TextView(context).apply {
                    val s = android.text.SpannableString("● 自分   ● ボーダー")
                    s.setSpan(android.text.style.ForegroundColorSpan("#F09199".toColorInt()), 0, 4, 0)
                    s.setSpan(android.text.style.ForegroundColorSpan("#90D7EC".toColorInt()), 7, s.length, 0)
                    text = s; textSize = 12f; setPadding(0, dp(4), 0, 0)
                })
            }
            addView(TextView(context).apply {
                text = "左右の縦線をドラッグして、絞り込む期間を選んでください"
                textSize = 12f; setTextColor("#8E8E93".toColorInt()); setPadding(0, dp(8), 0, 0)
            })
        }

        val dialog = AlertDialog.Builder(context, R.style.Theme_NakamonRec_Dialog)
            .setTitle("タイムフィルタ")
            .setView(root)
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("フィルタ実行") { _, _ ->
                if (hasData) onApply(stops[graph.startIdx].first..stops[graph.endIdx].first)
            }
            .create()
        dialog.setOnShowListener {
            // GP なのに記録が 0 件などでデータが無ければ実行できない (グラフは空のまま表示)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = hasData
        }
        dialog.show()
    }
}

/**
 * タイムフィルタ用のグラフ。横軸 = 日時 (epoch ms) または戦闘数 ([battleAxis])、左右 2 本の縦線をドラッグして範囲を選ぶ。
 * 縦線は [stops] (レコードの日時) の最も近い点に吸着する。範囲外は暗くする
 */
class TimeRangeGraphView(context: Context) : View(context) {
    data class Line(val points: List<Pair<Long, Double>>, val color: Int)

    var lines: List<Line> = emptyList()
    var stops: List<Long> = emptyList()
    var isPercent = false
    /** true = 横軸は戦闘数 (stops は 0 始まりの添字)。false = 日時 (stops は epoch ms) */
    var battleAxis = false
    var hasData = false
    var startIdx = 0
    var endIdx = 0
    var onRangeChanged: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val padL = 8 * density
    private val padR = 52 * density   // 右側に縦軸ラベル (iOS Swift Charts と同じ)
    private val padT = 16 * density   // 縦線上端の丸の分
    private val padB = 28 * density   // 横軸ラベル (日付 / 時刻 の 2 行)
    private var dragging = -1         // 0 = 左, 1 = 右

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = "#444444".toColorInt(); strokeWidth = 1f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = "#8E8E93".toColorInt(); textSize = 10 * density }
    private val dimPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2 * density }
    private val knobStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#F09199".toColorInt(); style = Paint.Style.STROKE; strokeWidth = 2 * density
    }

    private val tMin get() = stops.firstOrNull() ?: 0L
    private val tMax get() = stops.lastOrNull() ?: 1L

    private fun xOf(t: Long): Float {
        val span = (tMax - tMin).coerceAtLeast(1L).toFloat()
        return padL + (t - tMin) / span * (width - padL - padR)
    }

    private fun tOf(x: Float): Long {
        val frac = ((x - padL) / (width - padL - padR)).coerceIn(0f, 1f)
        return tMin + ((tMax - tMin) * frac).toLong()
    }

    private fun nearestStop(t: Long): Int {
        var lo = 0; var hi = stops.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (stops[mid] < t) lo = mid + 1 else hi = mid }
        if (lo > 0 && kotlin.math.abs(stops[lo - 1] - t) <= kotlin.math.abs(stops[lo] - t)) return lo - 1
        return lo
    }

    /** raw 以上で最小の「1・2・5 × 10^n」(GrandPrixGraphView と同じ目盛り間隔) */
    private fun niceStep(raw: Double): Double {
        if (raw <= 0.0) return 1.0
        val mag = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw)))
        val f = raw / mag
        return (when { f <= 1.0 -> 1.0; f <= 2.0 -> 2.0; f <= 5.0 -> 5.0; else -> 10.0 }) * mag
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val top = padT; val bottom = h - padB
        val vals = lines.flatMap { l -> l.points.map { it.second } }
        if (vals.isEmpty() || stops.isEmpty()) {
            canvas.drawLine(padL, bottom, w - padR, bottom, gridPaint)
            return
        }
        var yMin = vals.minOrNull()!!; var yMax = vals.maxOrNull()!!
        if (isPercent) { yMin = (yMin - 5).coerceAtLeast(0.0); yMax = (yMax + 5).coerceAtMost(100.0) }
        else { val span = (yMax - yMin).coerceAtLeast(1.0); yMin -= span * 0.1; yMax += span * 0.1 }
        val ySpan = (yMax - yMin).coerceAtLeast(1.0)
        fun yOf(v: Double) = (bottom - (v - yMin) / ySpan * (bottom - top)).toFloat()

        // 縦軸の目盛り (切りの良い値で約 5 本)
        val step = niceStep(ySpan / 5.0)
        var v = kotlin.math.ceil(yMin / step) * step
        while (v <= yMax) {
            val y = yOf(v)
            canvas.drawLine(padL, y, w - padR, y, gridPaint)
            canvas.drawText(if (isPercent) String.format(Locale.US, "%.0f%%", v) else String.format(Locale.US, "%.0f", v),
                w - padR + 6 * density, y + 4 * density, textPaint)
            v += step
        }
        if (battleAxis) {
            // 横軸 (戦闘数) の目盛り: 切りの良い戦闘番号で約 5 本 (iOS の desiredCount: 5 と同じ)
            val n = stops.size
            val xStep = niceStep(n / 5.0).coerceAtLeast(1.0).toInt()
            var no = xStep
            while (no <= n) {
                val x = xOf((no - 1).toLong())
                canvas.drawLine(x, top, x, bottom, gridPaint)
                val label = no.toString()
                canvas.drawText(label, (x - textPaint.measureText(label) / 2).coerceAtLeast(padL), bottom + 14 * density, textPaint)
                no += xStep
            }
        } else
        // 横軸 (日時) の目盛り: 3 本 (iOS の desiredCount: 3 と同じ)
        for (i in 0..2) {
            val sdfD = SimpleDateFormat("M/d", Locale.US); val sdfT = SimpleDateFormat("HH:mm", Locale.US)
            val t = tMin + (tMax - tMin) * i / 2
            val x = xOf(t)
            canvas.drawLine(x, top, x, bottom, gridPaint)
            val d = java.util.Date(t)
            val lx = (x - (if (i == 2) 30 * density else if (i == 1) 15 * density else 0f)).coerceAtLeast(padL)
            canvas.drawText(sdfD.format(d), lx, bottom + 12 * density, textPaint)
            canvas.drawText(sdfT.format(d), lx, bottom + 24 * density, textPaint)
        }
        // 折れ線
        for (l in lines) {
            if (l.points.isEmpty()) continue
            val path = Path()
            l.points.forEachIndexed { i, (t, value) ->
                val x = xOf(t); val y = yOf(value)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            linePaint.color = l.color
            canvas.drawPath(path, linePaint)
        }
        if (!hasData) return
        // 範囲外を暗く + 左右の縦線 (上端に掴みやすい丸)
        val left = xOf(stops[startIdx]); val right = xOf(stops[endIdx])
        canvas.drawRect(padL, top, left, bottom, dimPaint)
        canvas.drawRect(right, top, w - padR, bottom, dimPaint)
        for (x in listOf(left, right)) {
            canvas.drawLine(x, top, x, bottom, handlePaint)
            handlePaint.style = Paint.Style.FILL
            canvas.drawCircle(x, top, 8 * density, handlePaint)
            canvas.drawCircle(x, top, 8 * density, knobStroke)
            handlePaint.style = Paint.Style.STROKE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasData) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 近い方の縦線を掴む (細い線でも掴めるよう判定は ±24dp)
                val left = xOf(stops[startIdx]); val right = xOf(stops[endIdx])
                val dl = kotlin.math.abs(event.x - left); val dr = kotlin.math.abs(event.x - right)
                dragging = when {
                    dl > 24 * density && dr > 24 * density -> -1
                    dl <= dr -> 0
                    else -> 1
                }
                if (dragging == -1) return false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging == -1) return false
                val i = nearestStop(tOf(event.x))
                if (dragging == 0) startIdx = minOf(i, endIdx) else endIdx = maxOf(i, startIdx)
                onRangeChanged?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = -1
        }
        return true
    }
}
