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
    // メインの枠をライム色（Color.GREEN = #00FF00）に変更
    private val paintRect = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    // 探索バッファ用のペイント（半透明のシアン）
    private val paintBufferFill = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        alpha = 60
    }
    private val paintBufferStroke = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 180
    }
    private val paintHandle = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }
    // テキストもライム色に変更して統一感を出す
    private val paintText = Paint().apply {
        color = Color.GREEN
        textSize = 40f
        isFakeBoldText = true
        // 縁取りを追加してさらに読みやすく
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    data class CalibrationBox(
        val id: Int,
        var centerX: Float,
        var centerY: Float,
        var width: Int,
        var height: Int,
        val label: String
    )

    private val boxes = mutableListOf<CalibrationBox>()
    private var activeBoxIndex = -1
    private var isResizing = false
    private val imageRect = RectF()
    private val reusableRect = RectF()
    private val bufferRect = RectF()
    private val handleRadius = 30f

    fun setSourceImage(bitmap: Bitmap) {
        backgroundImage = bitmap
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
            
            // パーティ選択枠 (P1, P2, P3) の場合は、上下の探索範囲(計200px)を可視化
            if (box.label.startsWith("P")) {
                val marginY = 100f * scaleY
                bufferRect.set(reusableRect.left, reusableRect.top - marginY, reusableRect.right, reusableRect.bottom + marginY)
                canvas.drawRect(bufferRect, paintBufferFill)
                canvas.drawRect(bufferRect, paintBufferStroke)
            }

            if (index == activeBoxIndex) {
                paintRect.color = Color.YELLOW
                canvas.drawCircle(reusableRect.right, reusableRect.bottom, handleRadius, paintHandle)
            } else {
                paintRect.color = Color.GREEN // ライム色
            }
            
            canvas.drawRect(reusableRect, paintRect)
            canvas.drawText(box.label, reusableRect.left, reusableRect.top - 10f, paintText)
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
