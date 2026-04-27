package com.android.nakamonrec

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

class CalibrationView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var backgroundImage: Bitmap? = null
    private val paintRect = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val paintBufferFill = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        alpha = 40
    }
    private val paintBufferStroke = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 120
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val paintHandle = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }
    private val paintText = Paint().apply {
        color = Color.GREEN
        textSize = 40f
        isFakeBoldText = true
    }
    private val paintTextOutline = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isFakeBoldText = true
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    data class CalibrationBox(
        val id: Int,
        var centerX: Float,
        var centerY: Float,
        var width: Int,
        var height: Int,
        val label: String,
        var score: Double = -1.0,
        var actualScore: Double = -1.0
    )

    private val boxes = mutableListOf<CalibrationBox>()
    private var activeBoxIndex = -1
    private var isResizing = false
    private val imageRect = RectF()
    private val reusableRect = RectF()
    private val bufferRect = RectF()
    private val handleRadius = 30f

    private var uiScale: Float = 1.0f

    fun setSourceImage(bitmap: Bitmap) {
        backgroundImage = bitmap
        invalidate()
    }

    fun setUiScale(scale: Float) {
        uiScale = scale
        invalidate()
    }

    fun setBoxes(newBoxes: List<CalibrationBox>) {
        boxes.clear()
        boxes.addAll(newBoxes)
        invalidate()
    }

    fun getBoxes(): List<CalibrationBox> = boxes

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val img = backgroundImage ?: return

        imageRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(img, null, imageRect, null)

        val bitmapW = img.width.toFloat()
        val bitmapH = img.height.toFloat()
        val viewW = imageRect.width()
        val viewH = imageRect.height()

        boxes.forEachIndexed { index, box ->
            val cx = imageRect.left + (viewW * box.centerX)
            val cy = imageRect.top + (viewH * box.centerY)
            
            val scaleX = viewW / bitmapW
            val scaleY = viewH / bitmapH
            
            val bw = (box.width * scaleX) / 2f
            val bh = (box.height * scaleY) / 2f

            reusableRect.set(cx - bw, cy - bh, cx + bw, cy + bh)
            
            // 探索範囲の可視化
            if (box.label.startsWith("P")) {
                // パーティ選択：垂直方向を広く（BattleAnalyzer.ROI_PAD_PARTY_V/Hと同期）
                val padH = (BattleAnalyzer.ROI_PAD_PARTY_H * uiScale) * scaleX
                val padV = (BattleAnalyzer.ROI_PAD_PARTY_V * uiScale) * scaleY
                bufferRect.set(reusableRect.left - padH, reusableRect.top - padV, reusableRect.right + padH, reusableRect.bottom + padV)
                canvas.drawRect(bufferRect, paintBufferFill)
                canvas.drawRect(bufferRect, paintBufferStroke)
            } else if (box.label.contains("自") || box.label.contains("敵")) {
                // モンスター：正方形（BattleAnalyzer.ROI_PAD_MONSTERと同期）
                val pad = (BattleAnalyzer.ROI_PAD_MONSTER * uiScale) * scaleX
                bufferRect.set(reusableRect.left - pad, reusableRect.top - pad, reusableRect.right + pad, reusableRect.bottom + pad)
                canvas.drawRect(bufferRect, paintBufferFill)
                canvas.drawRect(bufferRect, paintBufferStroke)
            }

            if (index == activeBoxIndex) {
                paintRect.color = Color.YELLOW
                canvas.drawCircle(reusableRect.right, reusableRect.bottom, handleRadius, paintHandle)
            } else {
                paintRect.color = Color.GREEN
            }
            
            canvas.drawRect(reusableRect, paintRect)
            
            // ラベルの描画（白縁取り + 緑）
            canvas.drawText(box.label, reusableRect.left, reusableRect.top - 10f, paintTextOutline)
            canvas.drawText(box.label, reusableRect.left, reusableRect.top - 10f, paintText)
            
            if (box.score >= 0) {
                val scoreText = String.format(java.util.Locale.US, "%.3f", box.score)
                // 校正スコアの描画（白縁取り + 緑）
                canvas.drawText(scoreText, reusableRect.right, reusableRect.bottom + 35f, paintTextOutline)
                canvas.drawText(scoreText, reusableRect.right, reusableRect.bottom + 35f, paintText)
            }

            // 本番スコアの表示（明るい蛍光ピンク + 白縁取り）
            if (box.actualScore >= 0) {
                val actualScoreText = String.format(java.util.Locale.US, "%.3f", box.actualScore)
                
                // 縁取り（白）を描画
                canvas.drawText(actualScoreText, reusableRect.right, reusableRect.bottom + 75f, paintTextOutline)
                
                // 本文（ピンク）を上書き
                val oldColor = paintText.color
                paintText.color = Color.parseColor("#FF33FF")
                canvas.drawText(actualScoreText, reusableRect.right, reusableRect.bottom + 75f, paintText)
                paintText.color = oldColor
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val img = backgroundImage ?: return super.onTouchEvent(event)
        val x = event.x
        val y = event.y
        
        val viewW = imageRect.width()
        val viewH = imageRect.height()
        val bitmapW = img.width.toFloat()
        val bitmapH = img.height.toFloat()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                performClick()
                activeBoxIndex = -1
                isResizing = false
                
                boxes.forEachIndexed { index, box ->
                    val cx = imageRect.left + (viewW * box.centerX)
                    val cy = imageRect.top + (viewH * box.centerY)
                    
                    val scaleX = viewW / bitmapW
                    val scaleY = viewH / bitmapH
                    
                    val bw = (box.width * scaleX) / 2f
                    val bh = (box.height * scaleY) / 2f
                    
                    val distToHandle = hypot(x - (cx + bw), y - (cy + bh))
                    if (distToHandle < handleRadius * 2) {
                        activeBoxIndex = index
                        isResizing = true
                        return@forEachIndexed
                    }

                    if (x in (cx - bw)..(cx + bw) && y in (cy - bh)..(cy + bh)) {
                        activeBoxIndex = index
                        isResizing = false
                        return@forEachIndexed
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeBoxIndex != -1) {
                    val box = boxes[activeBoxIndex]

                    if (isResizing) {
                        val cx = imageRect.left + (viewW * box.centerX)
                        val cy = imageRect.top + (viewH * box.centerY)
                        
                        val scaleX = viewW / bitmapW
                        val scaleY = viewH / bitmapH
                        
                        box.width = ((abs(x - cx) * 2) / scaleX).toInt().coerceAtLeast(20)
                        box.height = ((abs(y - cy) * 2) / scaleY).toInt().coerceAtLeast(20)
                    } else {
                        box.centerX = ((x - imageRect.left) / viewW).coerceIn(0f, 1f)
                        box.centerY = ((y - imageRect.top) / viewH).coerceIn(0f, 1f)
                    }
                    invalidate()
                }
            }
        }
        return true
    }
}
