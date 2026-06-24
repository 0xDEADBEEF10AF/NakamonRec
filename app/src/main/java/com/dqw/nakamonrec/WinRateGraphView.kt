package com.dqw.nakamonrec

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

    private var dataPoints: List<PointData> = emptyList()
    private var scrollOffset: Float = 0f
    private var touchX: Float = -1f
    private var selectedIndex: Int = -1
    var visibleCount = 20
    var isDynamicScale = false

    // 固定パディング
    private val paddingLeft = 60f
    private val paddingRight = 30f
    private val paddingTop = 40f
    private val paddingBottom = 45f // 少し広げる

    data class PointData(val rate: Double, val label: String)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#F09199".toColorInt()
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#F09199".toColorInt()
        alpha = 30
        style = Paint.Style.FILL
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
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#888888".toColorInt()
        alpha = 120
    }

    private val path = Path()
    private val areaPath = Path()
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, dy: Float): Boolean {
            val effectiveVisibleCount = if (visibleCount > 0) visibleCount else dataPoints.size
            if (dataPoints.size <= effectiveVisibleCount) return false
            scrollOffset += distanceX
            val maxScroll = calculateMaxScroll()
            scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
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

    fun setData(points: List<PointData>) {
        this.dataPoints = points
        // 右端（最新値）をデフォルトで選択状態にする
        selectedIndex = if (points.isNotEmpty()) points.size - 1 else -1
        post {
            scrollOffset = calculateMaxScroll()
            invalidate()
        }
    }

    @JvmName("setDataRates")
    fun setData(rates: List<Double>) {
        setData(rates.map { PointData(it, "") })
    }

    private fun calculateStepX(): Float {
        val w = width.toFloat()
        val innerW = w - paddingLeft - paddingRight
        val effectiveVisibleCount = if (visibleCount > 0) visibleCount else dataPoints.size
        return if (effectiveVisibleCount > 1) innerW / (effectiveVisibleCount - 1) else 0f
    }

    private fun calculateMaxScroll(): Float {
        val effectiveVisibleCount = if (visibleCount > 0) visibleCount else dataPoints.size
        if (dataPoints.size <= effectiveVisibleCount) return 0f
        val stepX = calculateStepX()
        return (dataPoints.size - effectiveVisibleCount) * stepX
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dataPoints.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val innerW = w - paddingLeft - paddingRight
        val innerH = h - paddingTop - paddingBottom
        
        val stepX = calculateStepX()

        // ダイナミックスケールの計算（現在表示されている範囲に基づいて計算）
        var displayMin = 0f
        var displayMax = 100f
        if (isDynamicScale && dataPoints.isNotEmpty()) {
            var minVal = Float.MAX_VALUE
            var maxVal = Float.MIN_VALUE
            var hasVisiblePoint = false

            dataPoints.forEachIndexed { i, data ->
                val x = paddingLeft + i * stepX - scrollOffset
                // 画面内に見える点のみを対象にする
                if (x >= paddingLeft - 1f && x <= w - paddingRight + 1f) {
                    val r = data.rate.toFloat()
                    if (r < minVal) minVal = r
                    if (r > maxVal) maxVal = r
                    hasVisiblePoint = true
                }
            }
            
            if (hasVisiblePoint) {
                // 10%単位で丸める（マージンを持たせる）
                displayMin = (Math.floor(minVal / 10.0) * 10.0).toFloat().coerceAtLeast(0f)
                displayMax = (Math.ceil(maxVal / 10.0) * 10.0).toFloat().coerceAtMost(100f)
                
                // 最低20%の幅を確保
                if (displayMax - displayMin < 20f) {
                    if (displayMax <= 80f) displayMax = displayMin + 20f
                    else displayMin = displayMax - 20f
                }
            }
        }
        val range = displayMax - displayMin

        // Y軸目盛り
        val steps = 5
        for (i in 0..steps) {
            val rate = displayMin + (range * i / steps)
            val y = paddingTop + innerH - ((rate - displayMin) / range * innerH)
            canvas.drawLine(paddingLeft, y, w - paddingRight, y, gridPaint)
            canvas.drawText(String.format(Locale.US, "%d%%", rate.toInt()), 5f, y + 6f, textPaint)
        }

        // X軸目盛り (1Matchごと)
        if (visibleCount >= 20 && stepX > 0) {
            canvas.save()
            canvas.clipRect(paddingLeft, 0f, w - paddingRight, h)
            dataPoints.forEachIndexed { i, _ ->
                val x = paddingLeft + i * stepX - scrollOffset
                if (x >= paddingLeft - 1f && x <= w - paddingRight + 1f) {
                    canvas.drawLine(x, paddingTop, x, paddingTop + innerH, gridPaint)
                }
            }
            canvas.restore()
        }

        path.reset()
        areaPath.reset()

        dataPoints.forEachIndexed { i, data ->
            val x = paddingLeft + i * stepX - scrollOffset
            val y = paddingTop + innerH - ((data.rate.toFloat() - displayMin) / range * innerH)
            
            if (x < paddingLeft - stepX || x > w + stepX) return@forEachIndexed

            if (path.isEmpty) {
                path.moveTo(x, y)
                areaPath.moveTo(x, paddingTop + innerH)
                areaPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                areaPath.lineTo(x, y)
            }

            // X軸ラベル
            if (data.label.isNotEmpty() && 
                (i % ((dataPoints.size / 4).coerceAtLeast(1)) == 0 || i == dataPoints.size - 1)) {
                
                val displayLabel = if (data.label.contains("(") && data.label.endsWith(")")) {
                    data.label.substringAfterLast("(").substringBeforeLast(")")
                } else if (!data.label.contains("Matches")) {
                    data.label
                } else {
                    ""
                }

                if (displayLabel.isNotEmpty()) {
                    canvas.drawText(displayLabel, x, h - 20f, textPaint) // 少し上に移動
                }
            }
        }
        
        if (!path.isEmpty) {
            canvas.save()
            // グラフ領域でクリップ（Y軸ラベルエリアへの侵入を防ぐ）
            canvas.clipRect(paddingLeft, 0f, w - paddingRight, h)
            
            val lastX = paddingLeft + (dataPoints.size - 1) * stepX - scrollOffset
            areaPath.lineTo(lastX, paddingTop + innerH)
            canvas.drawPath(areaPath, areaPaint)
            canvas.drawPath(path, linePaint)
            
            canvas.restore()
        }

        // SLIDE TO SEE 表示
        if (calculateMaxScroll() > 0) {
            labelPaint.textSize = 10f * resources.displayMetrics.density
            canvas.drawText("SLIDE TO SEE", paddingLeft, h, labelPaint) // 下端に配置
        }

        // インジケーター表示 (touchX または selectedIndex)
        val activeIndex = if (touchX != -1f) {
            // パディングの少し外側でも反応するようにマージンを持たせる（右端は特に広めに50f）
            if (touchX >= paddingLeft - 20f && touchX <= w - paddingRight + 50f) {
                ((touchX + scrollOffset - paddingLeft) / stepX + 0.5f).toInt().coerceIn(0, dataPoints.size - 1)
            } else -1
        } else if (selectedIndex != -1) {
            selectedIndex
        } else {
            -1
        }

        if (activeIndex != -1 && stepX > 0) {
            val targetX = paddingLeft + activeIndex * stepX - scrollOffset
            val data = dataPoints[activeIndex]
            val targetY = paddingTop + innerH - ((data.rate.toFloat() - displayMin) / range * innerH)

            // 描画位置がパディング内にあるかチェック（誤差や遊びを考慮して±5f）
            if (targetX >= paddingLeft - 5f && targetX <= w - paddingRight + 5f) {
                canvas.drawLine(targetX, paddingTop, targetX, paddingTop + innerH, indicatorPaint)
                canvas.drawCircle(targetX, targetY, 8f, circlePaint.apply { color = Color.WHITE })
                canvas.drawCircle(targetX, targetY, 6f, circlePaint.apply { color = linePaint.color })
                
                val tooltip = if (data.label.contains("Matches")) data.label else String.format(Locale.US, "%.1f%%：%s", data.rate, data.label)
                
                val textWidth = tooltipPaint.measureText(tooltip)
                var drawX = targetX
                if (drawX - textWidth / 2 < paddingLeft) {
                    drawX = paddingLeft + textWidth / 2
                } else if (drawX + textWidth / 2 > w - paddingRight) {
                    drawX = w - paddingRight - textWidth / 2
                }
                
                canvas.drawText(tooltip, drawX, paddingTop - 12f, tooltipPaint)
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchX = -1f
            }
        }
        invalidate()
        return true
    }
}
