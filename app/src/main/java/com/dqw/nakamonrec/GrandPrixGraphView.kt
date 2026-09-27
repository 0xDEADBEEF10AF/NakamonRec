package com.dqw.nakamonrec

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.toColorInt
import java.util.Locale

/**
 * グランプリのレーティング推移グラフ。MonsterStatsGraphView (勝率/出現率の2本線) と
 * 同じ見た目・操作 (横スクロール+タップでツールチップ) を踏襲しつつ、
 * Y軸を固定 0-100% ではなくデータの min..max に自動レンジ化し、
 * 自分のレーティング (赤) と ボーダー (青) の2本を描く。ボーダーは欠損点 (GM/ランクアップ) を挟んでも前後の点を連結する。
 * 横軸は戦闘の順番 (既定) / 実時間 (timeAxis=true) を切替可。
 */
class GrandPrixGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class RatingPoint(
        val rating: Double, val border: Double?, val dateLabel: String, val rankTier: String? = null,
        val epochMillis: Long = 0L   // 日時軸モードでの X 位置に使う (0 = 不明、その場合は等間隔扱い)
    )

    private var dataPoints: List<RatingPoint> = emptyList()
    private var scrollOffset: Float = 0f
    private var touchX: Float = -1f
    private var selectedIndex: Int = -1
    private var lastNotifiedIndex = -2
    var visibleCount = 8

    /**
     * 横軸。false = 戦闘の順番 (既定、1 戦 = 1 目盛で等間隔) / true = 実時間 (日時)。
     * 日時軸では全体の横幅 (= (n-1)*stepX) を保ったまま、各点を時刻の比率で配置する。
     * 実時間軸はセッションが縦に潰れて形が読めないため既定は戦闘数軸 (iOS と統一、2026-09-27)。
     */
    var timeAxis: Boolean = false
        set(value) { if (field != value) { field = value; lastNotifiedIndex = -2; notifySelection(); invalidate() } }
    private var tMin = 0L
    private var tMax = 0L

    /** 選択点が変わったら通知 (null = 選択なし)。数値表示はグラフ上部の情報枠が担う (iOS と統一) */
    var onSelectionChanged: ((RatingPoint?) -> Unit)? = null

    private val paddingLeft = 16f
    private val paddingRight = 88f   // Y軸目盛りは右側 (iOS Swift Charts と統一)
    private val paddingTop = 40f
    private val paddingBottom = 40f

    private val ratingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#F09199".toColorInt() // 赤系（自分のレーティング）
        strokeWidth = 4f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val borderLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#90D7EC".toColorInt() // 青系（ボーダー）
        strokeWidth = 4f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#444444".toColorInt(); strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#888888".toColorInt(); textSize = 18f; textAlign = Paint.Align.LEFT
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 各点の小さなリング (iOS Swift Charts の LineMark シンボルと同じ見え方: 線色の輪+背景色の中身) */
    private val dotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = "#333333".toColorInt() }
    private val dotRadius = 4f
    /** 点シンボルを描く上限 (表示中の点がこれ以下のときだけ。多いと線を覆って読めない。iOS と同じ値) */
    private val dotLimit = 100

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, dy: Float): Boolean {
            if (dataPoints.size <= visibleCount) return false
            scrollOffset = (scrollOffset + distanceX).coerceIn(0f, calculateMaxScroll())
            invalidate(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (dataPoints.isEmpty()) return false
            val stepX = calculateStepX(); if (stepX <= 0) return false
            val i = nearestIndex(e.x, stepX)
            selectedIndex = if (selectedIndex == i) -1 else i
            notifySelection()
            invalidate(); return true
        }
    })

    fun setData(points: List<RatingPoint>) {
        this.dataPoints = points
        val ts = points.map { it.epochMillis }.filter { it > 0L }
        tMin = ts.minOrNull() ?: 0L
        tMax = ts.maxOrNull() ?: 0L
        selectedIndex = if (points.isNotEmpty()) points.size - 1 else -1
        lastNotifiedIndex = -2
        post { scrollOffset = calculateMaxScroll(); notifySelection(); invalidate() }
    }

    /** i 番目の点の X 座標 (スクロール反映済み)。戦闘数軸は等間隔、日時軸は時刻の比率で配置 */
    private fun xOf(i: Int, stepX: Float): Float {
        val n = dataPoints.size
        val base = if (timeAxis && n > 1 && tMax > tMin) {
            val t = dataPoints[i].epochMillis
            val frac = if (t > 0L) (t - tMin).toDouble() / (tMax - tMin).toDouble() else i.toDouble() / (n - 1)
            (frac * (n - 1) * stepX).toFloat()
        } else i * stepX
        return paddingLeft + base - scrollOffset
    }

    /** 画面 X 座標に最も近い点のインデックス */
    private fun nearestIndex(screenX: Float, stepX: Float): Int {
        var best = 0; var bestD = Float.MAX_VALUE
        for (i in dataPoints.indices) {
            val d = kotlin.math.abs(xOf(i, stepX) - screenX)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** 現在のアクティブ点 (ドラッグ中はタッチ位置、それ以外はタップ選択) */
    private fun activeIndexNow(): Int {
        val stepX = calculateStepX()
        if (dataPoints.isEmpty() || stepX <= 0) return -1
        return if (touchX != -1f) {
            if (touchX in (paddingLeft - 20f)..(width - paddingRight + 50f)) nearestIndex(touchX, stepX)
            else -1
        } else selectedIndex
    }

    private fun notifySelection() {
        val idx = activeIndexNow()
        if (idx != lastNotifiedIndex) {
            lastNotifiedIndex = idx
            onSelectionChanged?.invoke(dataPoints.getOrNull(idx))
        }
    }

    private fun calculateStepX(): Float {
        val innerW = width.toFloat() - paddingLeft - paddingRight
        return if (visibleCount > 1) innerW / (visibleCount - 1) else 0f
    }

    private fun calculateMaxScroll(): Float {
        if (dataPoints.size <= visibleCount) return 0f
        return (dataPoints.size - visibleCount) * calculateStepX()
    }

    /** データの min..max に 10% パディングした Y レンジ */
    private fun yRange(): Pair<Double, Double> {
        val vals = dataPoints.flatMap { listOfNotNull(it.rating, it.border) }
        if (vals.isEmpty()) return 0.0 to 1.0
        val minV = vals.minOrNull()!!; val maxV = vals.maxOrNull()!!
        val range = (maxV - minV).coerceAtLeast(1.0)
        return (minV - range * 0.1) to (maxV + range * 0.1)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) return

        val w = width.toFloat(); val h = height.toFloat()
        val innerH = h - paddingTop - paddingBottom
        val stepX = calculateStepX()
        val (yMin, yMax) = yRange()
        val yspan = (yMax - yMin).coerceAtLeast(1.0)

        fun yOf(v: Double): Float = (paddingTop + innerH - ((v - yMin) / yspan * innerH)).toFloat()

        // Y軸目盛り (レーティング値)
        val steps = 4
        for (i in 0..steps) {
            val v = yMin + (yMax - yMin) * i / steps
            val y = yOf(v)
            canvas.drawLine(paddingLeft, y, w - paddingRight, y, gridPaint)
            canvas.drawText(String.format(Locale.US, "%.0f", v), w - paddingRight + 8f, y + 6f, textPaint)
        }

        val pathRating = Path()
        val pathBorder = Path()
        // X軸ラベルの重なり防止: 直前に描いたラベルの右端を追跡し、日付+時刻 (幅可変) でも重ならないようにする
        var lastLabelRight = Float.NEGATIVE_INFINITY
        val labelGap = 16f

        dataPoints.forEachIndexed { i, data ->
            val x = xOf(i, stepX)
            if (x < paddingLeft - stepX || x > w + stepX) return@forEachIndexed
            val yR = yOf(data.rating)
            if (pathRating.isEmpty) pathRating.moveTo(x, yR) else pathRating.lineTo(x, yR)

            // ボーダー線はレーティング線と同じく「点がある所だけを順に結ぶ」。
            // ボーダーはマスター帯のみ記録 (GM 中は null) のため、マスター1→GM→GM→マスター1 のような
            // 系列では点が飛地になる。26.9.1 までは欠損点で線を切っていたが、飛地の点同士も線で
            // 連結するよう変更 (2026-09-02 ビーフ要望。iOS Swift Charts は欠損点を渡さないため元から連結)
            data.border?.let { b ->
                val yB = yOf(b)
                if (pathBorder.isEmpty) pathBorder.moveTo(x, yB) else pathBorder.lineTo(x, yB)
            }

            // X軸ラベル（前ラベルと重ならない範囲で描画）
            val textW = textPaint.measureText(data.dateLabel)
            val left = x - textW / 2
            if (left > lastLabelRight + labelGap && x + textW / 2 <= w - paddingRight) {
                canvas.drawText(data.dateLabel, left, h - 10f, textPaint)
                lastLabelRight = x + textW / 2
            }
        }

        canvas.save()
        canvas.clipRect(paddingLeft, 0f, w - paddingRight, h)
        canvas.drawPath(pathBorder, borderLinePaint)
        canvas.drawPath(pathRating, ratingLinePaint)
        // 各点の小さなリング (表示中の点が dotLimit 以下のときだけ)。
        // 実測点と補間区間 (GM 帯を挟むボーダーの直線) を見分けられ、日時軸では記録の密度も見える
        if (visibleCount <= dotLimit) {
            fun ring(x: Float, y: Float, color: Int) {
                canvas.drawCircle(x, y, dotRadius, dotFillPaint)
                canvas.drawCircle(x, y, dotRadius, dotStrokePaint.apply { this.color = color })
            }
            dataPoints.forEachIndexed { i, data ->
                val x = xOf(i, stepX)
                if (x < paddingLeft - stepX || x > w + stepX) return@forEachIndexed
                data.border?.let { ring(x, yOf(it), borderLinePaint.color) }
                ring(x, yOf(data.rating), ratingLinePaint.color)
            }
        }
        canvas.restore()

        // タップ位置のインジケーター (縦線+強調点)。数値表示は上部情報枠 (iOS と統一)
        val activeIndex = activeIndexNow()

        if (activeIndex != -1 && stepX >= 0) {
            val targetX = xOf(activeIndex, stepX)
            if (targetX in (paddingLeft - 5f)..(w - paddingRight + 5f)) {
                val data = dataPoints[activeIndex]
                val yR = yOf(data.rating)
                canvas.drawLine(targetX, paddingTop, targetX, paddingTop + innerH, indicatorPaint)
                canvas.drawCircle(targetX, yR, 6f, circlePaint.apply { color = Color.WHITE })
                canvas.drawCircle(targetX, yR, 4f, circlePaint.apply { color = ratingLinePaint.color })
                data.border?.let {
                    val yB = yOf(it)
                    canvas.drawCircle(targetX, yB, 6f, circlePaint.apply { color = Color.WHITE })
                    canvas.drawCircle(targetX, yB, 4f, circlePaint.apply { color = borderLinePaint.color })
                }

            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchX = -1f
        }
        notifySelection()
        invalidate()
        return true
    }
}
