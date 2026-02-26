package com.android.nakamonrec

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.toColorInt
import java.util.Locale

class WinRateGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var allDataPoints: List<Double> = emptyList()
    private var scrollOffset: Float = 0f // スライド量
    private var touchX: Float = -1f
    private val visibleCount = 20 // 画面に表示するデータ数

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#F09199".toColorInt()
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#F09199".toColorInt()
        alpha = 40
        style = Paint.Style.FILL
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#888888".toColorInt()
        textSize = 20f
        textAlign = Paint.Align.LEFT
    }

    private val path = Path()
    private val areaPath = Path()
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, dy: Float): Boolean {
            scrollOffset += distanceX
            val maxScroll = calculateMaxScroll()
            scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
            invalidate()
            return true
        }
    })

    fun setData(points: List<Double>) {
        this.allDataPoints = points
        // 最初は最新（一番右）を表示するようにオフセットを調整
        post {
            scrollOffset = calculateMaxScroll()
            invalidate()
        }
    }

    private fun calculateMaxScroll(): Float {
        if (allDataPoints.size <= visibleCount) return 0f
        val stepX = (width.toFloat() - 64f) / (visibleCount - 1)
        return (allDataPoints.size - visibleCount) * stepX
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (allDataPoints.size < 2) return

        val w = width.toFloat()
        val h = height.toFloat()
        val padding = 32f
        val innerW = w - padding * 2
        val innerH = h - padding * 2
        val stepX = innerW / (visibleCount - 1)

        // 現在の表示範囲でのY軸オートズーム
        val startIndex = (scrollOffset / stepX).toInt().coerceIn(0, allDataPoints.size - 1)
        val endIndex = (startIndex + visibleCount).coerceIn(0, allDataPoints.size - 1)
        val visiblePoints = allDataPoints.subList(startIndex, endIndex + 1)
        
        val maxVal = visiblePoints.maxOrNull() ?: 100.0
        val minVal = visiblePoints.minOrNull() ?: 0.0
        val range = (maxVal - minVal).coerceAtLeast(10.0)
        val yBase = minVal - range * 0.1
        val yRange = range * 1.2

        path.reset()
        areaPath.reset()

        allDataPoints.forEachIndexed { i, rate ->
            val x = padding + i * stepX - scrollOffset
            val y = padding + innerH - ((rate - yBase).toFloat() / yRange.toFloat() * innerH)
            
            // 画面外の描画をスキップ（パフォーマンス向上）
            if (x < -stepX || x > w + stepX) {
                if (i > 0 && x > w + stepX) return@forEachIndexed
            }

            if (path.isEmpty) {
                path.moveTo(x, y)
                areaPath.moveTo(x, padding + innerH)
                areaPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                areaPath.lineTo(x, y)
            }
            
            if (i == allDataPoints.size - 1 || x + stepX > w + stepX) {
                areaPath.lineTo(x, padding + innerH)
                // 閉じ方は最後の点によって変わる
            }
        }
        
        canvas.drawPath(areaPath, areaPaint)
        canvas.drawPath(path, linePaint)

        // ドラッグ中のインジケーター（タップ位置のデータを探す）
        if (touchX >= 0) {
            val i = ((touchX + scrollOffset - padding) / stepX + 0.5f).toInt().coerceIn(0, allDataPoints.size - 1)
            val targetX = padding + i * stepX - scrollOffset
            val rate = allDataPoints[i]
            val targetY = padding + innerH - ((rate - yBase).toFloat() / yRange.toFloat() * innerH)

            canvas.drawLine(targetX, 0f, targetX, h, indicatorPaint)
            val label = String.format(Locale.US, "Match %d: %.1f%%", i + 1, rate)
            canvas.drawText(label, w / 2, padding, textPaint)
            
            circlePaint.color = linePaint.color
            canvas.drawCircle(targetX, targetY, 8f, circlePaint)
        }
        
        // 過去ログへの案内ラベル
        if (allDataPoints.size > visibleCount) {
            canvas.drawText("◀ SLIDE TO SEE PAST TRENDS", padding, h - 5, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchX = event.x
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = event.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchX = -1f
                performClick()
            }
        }
        invalidate()
        return true
    }
}
