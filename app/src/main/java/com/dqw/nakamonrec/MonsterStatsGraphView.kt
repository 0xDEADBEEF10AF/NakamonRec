package com.dqw.nakamonrec

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.toColorInt
import java.util.Locale

class MonsterStatsGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var dataPoints: List<DualPointData> = emptyList()
    private var scrollOffset: Float = 0f
    private var touchX: Float = -1f
    private var selectedIndex: Int = -1
    var visibleCount = 7 // 1週間分程度を表示

    private val paddingLeft = 70f
    private val paddingRight = 30f
    private val paddingTop = 40f // ツールチップ用に少し余裕を持たせる
    private val paddingBottom = 40f

    data class DualPointData(
        val winRate: Double,
        val appearRate: Double,
        val dateLabel: String
    )

    private val winLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#F09199".toColorInt() // 赤系（勝率）
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val appearLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#90D7EC".toColorInt() // 青系（出現率）
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#444444".toColorInt()
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#888888".toColorInt()
        textSize = 18f
        textAlign = Paint.Align.LEFT
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val tooltipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val pathWin = Path()
    private val pathAppear = Path()
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, dy: Float): Boolean {
            if (dataPoints.size <= visibleCount) return false
            scrollOffset += distanceX
            scrollOffset = scrollOffset.coerceIn(0f, calculateMaxScroll())
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (dataPoints.isEmpty()) return false
            val stepX = calculateStepX()
            if (stepX <= 0) return false
            val i = ((e.x + scrollOffset - paddingLeft) / stepX + 0.5f).toInt().coerceIn(0, dataPoints.size - 1)
            selectedIndex = if (selectedIndex == i) -1 else i
            invalidate()
            return true
        }
    })

    fun setData(points: List<DualPointData>) {
        this.dataPoints = points
        selectedIndex = if (points.isNotEmpty()) points.size - 1 else -1
        post {
            scrollOffset = calculateMaxScroll()
            invalidate()
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val innerW = w - paddingLeft - paddingRight
        val innerH = h - paddingTop - paddingBottom
        val stepX = calculateStepX()

        // Y軸目盛り
        val steps = 4
        for (i in 0..steps) {
            val rate = 100 * i / steps
            val y = paddingTop + innerH - (rate.toFloat() / 100f * innerH)
            canvas.drawLine(paddingLeft, y, w - paddingRight, y, gridPaint)
            canvas.drawText("${rate}%", 5f, y + 6f, textPaint)
        }

        pathWin.reset()
        pathAppear.reset()

        dataPoints.forEachIndexed { i, data ->
            val x = paddingLeft + i * stepX - scrollOffset
            val yWin = paddingTop + innerH - (data.winRate.toFloat() / 100f * innerH)
            val yAppear = paddingTop + innerH - (data.appearRate.toFloat() / 100f * innerH)

            if (x < paddingLeft - stepX || x > w + stepX) return@forEachIndexed

            if (pathWin.isEmpty) {
                pathWin.moveTo(x, yWin)
                pathAppear.moveTo(x, yAppear)
            } else {
                pathWin.lineTo(x, yWin)
                pathAppear.lineTo(x, yAppear)
            }

            // X軸ラベル（間引き）
            val labelInterval = (dataPoints.size / 4).coerceAtLeast(1)
            if (i % labelInterval == 0 || i == dataPoints.size - 1) {
                val textW = textPaint.measureText(data.dateLabel)
                canvas.drawText(data.dateLabel, x - textW / 2, h - 10f, textPaint)
            }
        }

        canvas.save()
        canvas.clipRect(paddingLeft, 0f, w - paddingRight, h)
        canvas.drawPath(pathWin, winLinePaint)
        canvas.drawPath(pathAppear, appearLinePaint)
        canvas.restore()

        // インジケーター表示
        val activeIndex = if (touchX != -1f) {
            if (touchX >= paddingLeft - 20f && touchX <= w - paddingRight + 50f) {
                ((touchX + scrollOffset - paddingLeft) / stepX + 0.5f).toInt().coerceIn(0, dataPoints.size - 1)
            } else -1
        } else selectedIndex

        if (activeIndex != -1 && stepX >= 0) {
            val targetX = paddingLeft + activeIndex * stepX - scrollOffset
            if (targetX >= paddingLeft - 5f && targetX <= w - paddingRight + 5f) {
                val data = dataPoints[activeIndex]
                val yWin = paddingTop + innerH - (data.winRate.toFloat() / 100f * innerH)
                val yAppear = paddingTop + innerH - (data.appearRate.toFloat() / 100f * innerH)

                canvas.drawLine(targetX, paddingTop, targetX, paddingTop + innerH, indicatorPaint)
                
                // プロット点
                canvas.drawCircle(targetX, yWin, 6f, circlePaint.apply { color = Color.WHITE })
                canvas.drawCircle(targetX, yWin, 4f, circlePaint.apply { color = winLinePaint.color })
                canvas.drawCircle(targetX, yAppear, 6f, circlePaint.apply { color = Color.WHITE })
                canvas.drawCircle(targetX, yAppear, 4f, circlePaint.apply { color = appearLinePaint.color })

                // ツールチップ表示ロジック（右側に配置）
                val labelWin = String.format(Locale.US, "勝:%.0f%%", data.winRate)
                val labelAppear = String.format(Locale.US, "出:%.0f%%", data.appearRate)
                val textSize = tooltipPaint.textSize
                
                // 左右どちらに描画するか判定 (右端付近なら左側に表示)
                val marginX = 12f
                val maxTextW = Math.max(tooltipPaint.measureText(labelWin), tooltipPaint.measureText(labelAppear))
                val isRightSide = (targetX + marginX + maxTextW <= w - 5f)
                
                if (isRightSide) {
                    tooltipPaint.textAlign = Paint.Align.LEFT
                } else {
                    tooltipPaint.textAlign = Paint.Align.RIGHT
                }
                val drawX = if (isRightSide) targetX + marginX else targetX - marginX
                
                // 上下位置の基本はプロットの中央高さ
                var winTextY = yWin + (textSize / 3f)
                var appTextY = yAppear + (textSize / 3f)
                
                // 近すぎる場合の重なり回避
                if (Math.abs(winTextY - appTextY) < textSize) {
                    if (yWin < yAppear) {
                        winTextY = yWin - (textSize / 2f)
                        appTextY = yAppear + (textSize * 0.8f)
                    } else {
                        winTextY = yWin + (textSize * 0.8f)
                        appTextY = yAppear - (textSize / 2f)
                    }
                }
                
                // グラフ上下端での見切れチェック
                val topLimit = paddingTop + textSize
                val bottomLimit = paddingTop + innerH
                winTextY = winTextY.coerceIn(topLimit, bottomLimit)
                appTextY = appTextY.coerceIn(topLimit, bottomLimit)

                tooltipPaint.color = winLinePaint.color
                canvas.drawText(labelWin, drawX, winTextY, tooltipPaint)
                tooltipPaint.color = appearLinePaint.color
                canvas.drawText(labelAppear, drawX, appTextY, tooltipPaint)
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
