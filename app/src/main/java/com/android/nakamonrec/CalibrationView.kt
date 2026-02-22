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
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val paintHandle = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }
    private val paintText = Paint().apply {
        color = Color.RED
        textSize = 40f
        isFakeBoldText = true
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
            // centerX/centerY は画面全体に対する比率なのでビューサイズに掛ける
            val cx = imageRect.left + (viewW * box.centerX)
            val cy = imageRect.top + (viewH * box.centerY)
            
            // width/height はビットマップの生ピクセルなので、表示倍率を掛けてビュー上のサイズに変換
            val bw = (box.width * (viewW / bitmapW)) / 2f
            val bh = (box.height * (viewH / bitmapH)) / 2f

            reusableRect.set(cx - bw, cy - bh, cx + bw, cy + bh)
            
            if (index == activeBoxIndex) {
                paintRect.color = Color.YELLOW
                canvas.drawCircle(reusableRect.right, reusableRect.bottom, handleRadius, paintHandle)
            } else {
                paintRect.color = Color.RED
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
                    val bw = (box.width * (viewW / bitmapW)) / 2f
                    val bh = (box.height * (viewH / bitmapH)) / 2f
                    
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
                        
                        val bitmapToViewScaleX = viewW / bitmapW
                        val bitmapToViewScaleY = viewH / bitmapH
                        
                        box.width = ((abs(x - cx) * 2) / bitmapToViewScaleX).toInt().coerceAtLeast(20)
                        box.height = ((abs(y - cy) * 2) / bitmapToViewScaleY).toInt().coerceAtLeast(20)
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
