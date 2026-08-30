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
 * 自分のレーティング (赤) と ボーダー (青) の2本を描く。ボーダーは欠損点 (GM/ランクアップ) で途切れる。
 */
class GrandPrixGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class RatingPoint(val rating: Double, val border: Double?, val dateLabel: String, val rankTier: String? = null)

    private var dataPoints: List<RatingPoint> = emptyList()
    private var scrollOffset: Float = 0f
    private var touchX: Float = -1f
    private var selectedIndex: Int = -1
    private var lastNotifiedIndex = -2
    var visibleCount = 8

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

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, dy: Float): Boolean {
            if (dataPoints.size <= visibleCount) return false
            scrollOffset = (scrollOffset + distanceX).coerceIn(0f, calculateMaxScroll())
            invalidate(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (dataPoints.isEmpty()) return false
            val stepX = calculateStepX(); if (stepX <= 0) return false
            val i = ((e.x + scrollOffset - paddingLeft) / stepX + 0.5f).toInt().coerceIn(0, dataPoints.size - 1)
            selectedIndex = if (selectedIndex == i) -1 else i
            notifySelection()
            invalidate(); return true
        }
    })

    fun setData(points: List<RatingPoint>) {
        this.dataPoints = points
        selectedIndex = if (points.isNotEmpty()) points.size - 1 else -1
        lastNotifiedIndex = -2
        post { scrollOffset = calculateMaxScroll(); notifySelection(); invalidate() }
    }

    /** 現在のアクティブ点 (ドラッグ中はタッチ位置、それ以外はタップ選択) */
    private fun activeIndexNow(): Int {
        val stepX = calculateStepX()
        if (dataPoints.isEmpty() || stepX <= 0) return -1
        return if (touchX != -1f) {
            if (touchX in (paddingLeft - 20f)..(width - paddingRight + 50f))
                ((touchX + scrollOffset - paddingLeft) / stepX + 0.5f).toInt().coerceIn(0, dataPoints.size - 1)
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
        var borderStarted = false
        // X軸ラベルの重なり防止: 直前に描いたラベルの右端を追跡し、日付+時刻 (幅可変) でも重ならないようにする
        var lastLabelRight = Float.NEGATIVE_INFINITY
        val labelGap = 16f

        dataPoints.forEachIndexed { i, data ->
            val x = paddingLeft + i * stepX - scrollOffset
            if (x < paddingLeft - stepX || x > w + stepX) {
                borderStarted = false // 画面外に出たらボーダー線を一旦切る
                return@forEachIndexed
            }
            val yR = yOf(data.rating)
            if (pathRating.isEmpty) pathRating.moveTo(x, yR) else pathRating.lineTo(x, yR)

            val b = data.border
            if (b != null) {
                val yB = yOf(b)
                if (!borderStarted) { pathBorder.moveTo(x, yB); borderStarted = true }
                else pathBorder.lineTo(x, yB)
            } else {
                borderStarted = false // 欠損点でボーダー線を途切れさせる
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
        canvas.restore()

        // タップ位置のインジケーター (縦線+強調点)。数値表示は上部情報枠 (iOS と統一)
        val activeIndex = activeIndexNow()

        if (activeIndex != -1 && stepX >= 0) {
            val targetX = paddingLeft + activeIndex * stepX - scrollOffset
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
