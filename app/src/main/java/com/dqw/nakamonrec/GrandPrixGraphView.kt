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
    var visibleCount = 8

    private val paddingLeft = 88f
    private val paddingRight = 30f
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
    private val tooltipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 28f; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val badgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // ランク帯エンブレムの Bitmap キャッシュ (tier → drawable、無ければ null を記憶)
    private val emblemCache = HashMap<String, android.graphics.Bitmap?>()
    private fun emblemBitmap(tier: String?): android.graphics.Bitmap? {
        val asset = GrandPrixRecord.rankEmblemAsset(tier) ?: return null
        return emblemCache.getOrPut(asset) {
            val resId = resources.getIdentifier(asset, "drawable", context.packageName)
            if (resId == 0) null
            else android.graphics.BitmapFactory.decodeResource(resources, resId)
        }
    }

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
            invalidate(); return true
        }
    })

    fun setData(points: List<RatingPoint>) {
        this.dataPoints = points
        selectedIndex = if (points.isNotEmpty()) points.size - 1 else -1
        post { scrollOffset = calculateMaxScroll(); invalidate() }
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
            canvas.drawText(String.format(Locale.US, "%.0f", v), 5f, y + 6f, textPaint)
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

        // タップ位置のインジケーター+ツールチップ
        val activeIndex = if (touchX != -1f) {
            if (touchX in (paddingLeft - 20f)..(w - paddingRight + 50f))
                ((touchX + scrollOffset - paddingLeft) / stepX + 0.5f).toInt().coerceIn(0, dataPoints.size - 1)
            else -1
        } else selectedIndex

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

                val labelR = String.format(Locale.US, "R:%.1f", data.rating)
                val labelB = data.border?.let { String.format(Locale.US, "B:%.1f", it) }
                val maxTextW = maxOf(tooltipPaint.measureText(labelR), labelB?.let { tooltipPaint.measureText(it) } ?: 0f)
                val marginX = 12f
                val isRightSide = (targetX + marginX + maxTextW <= w - 5f)
                tooltipPaint.textAlign = if (isRightSide) Paint.Align.LEFT else Paint.Align.RIGHT
                val drawX = if (isRightSide) targetX + marginX else targetX - marginX

                val ts = tooltipPaint.textSize
                var rTextY = yR + (ts / 3f)
                var bTextY = data.border?.let { yOf(it) + (ts / 3f) } ?: rTextY
                if (labelB != null && Math.abs(rTextY - bTextY) < ts) {
                    if (yR < yOf(data.border!!)) { rTextY = yR - ts / 2f; bTextY = yOf(data.border!!) + ts * 0.8f }
                    else { rTextY = yR + ts * 0.8f; bTextY = yOf(data.border!!) - ts / 2f }
                }
                val topLimit = paddingTop + ts; val bottomLimit = paddingTop + innerH
                rTextY = rTextY.coerceIn(topLimit, bottomLimit)
                bTextY = bTextY.coerceIn(topLimit, bottomLimit)

                // ランク帯のエンブレムサムネイルを R:xxxx の左に描く
                // (色バッジ版は GrandPrixRecord.rankBadge に定義が残っており差し戻し可)
                emblemBitmap(data.rankTier)?.let { emblem ->
                    val labelLeftX = if (tooltipPaint.textAlign == Paint.Align.LEFT) drawX
                                     else drawX - tooltipPaint.measureText(labelR)
                    val bh = tooltipPaint.textSize * 1.5f
                    val bw = bh * emblem.width / emblem.height
                    val right = labelLeftX - 6f
                    val left = right - bw
                    val cy = rTextY - tooltipPaint.textSize * 0.34f
                    val dst = android.graphics.RectF(left, cy - bh / 2f, right, cy + bh / 2f)
                    canvas.drawBitmap(emblem, null, dst, badgeFillPaint)
                }

                tooltipPaint.color = ratingLinePaint.color
                canvas.drawText(labelR, drawX, rTextY, tooltipPaint)
                if (labelB != null) {
                    tooltipPaint.color = borderLinePaint.color
                    canvas.drawText(labelB, drawX, bTextY, tooltipPaint)
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
        invalidate()
        return true
    }
}
