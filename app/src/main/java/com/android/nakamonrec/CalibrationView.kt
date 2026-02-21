package com.android.nakamonrec

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

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
    private val HANDLE_RADIUS = 30f

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

        boxes.forEachIndexed { index, box ->
            val cx = imageRect.left + (imageRect.width() * box.centerX)
            val cy = imageRect.top + (imageRect.height() * box.centerY)
            
            val bw = (box.width * (imageRect.width() / 1080f)) / 2f
            val bh = (box.height * (imageRect.width() / 1080f)) / 2f

            val rect = RectF(cx - bw, cy - bh, cx + bw, cy + bh)
            
            if (index == activeBoxIndex) {
                paintRect.color = Color.YELLOW
                // リサイズハンドルを描画（右下）
                canvas.drawCircle(rect.right, rect.bottom, HANDLE_RADIUS, paintHandle)
            } else {
                paintRect.color = Color.RED
            }
            
            canvas.drawRect(rect, paintRect)
            canvas.drawText(box.label, rect.left, rect.top - 10f, paintText)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                performClick()
                activeBoxIndex = -1
                isResizing = false
                
                boxes.forEachIndexed { index, box ->
                    val cx = imageRect.left + (imageRect.width() * box.centerX)
                    val cy = imageRect.top + (imageRect.height() * box.centerY)
                    val bw = (box.width * (imageRect.width() / 1080f)) / 2f
                    val bh = (box.height * (imageRect.width() / 1080f)) / 2f
                    val rect = RectF(cx - bw, cy - bh, cx + bw, cy + bh)

                    // ハンドル（右下）の判定
                    val distToHandle = Math.sqrt(Math.pow((x - rect.right).toDouble(), 2.0) + Math.pow((y - rect.bottom).toDouble(), 2.0))
                    if (distToHandle < HANDLE_RADIUS * 2) {
                        activeBoxIndex = index
                        isResizing = true
                        return@forEachIndexed
                    }

                    // 枠内の判定
                    if (rect.contains(x, y)) {
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
                    val viewScale = imageRect.width() / 1080f

                    if (isResizing) {
                        // サイズ変更: 中心座標を固定し、端までの距離から幅・高さを逆算
                        val cx = imageRect.left + (imageRect.width() * box.centerX)
                        val cy = imageRect.top + (imageRect.height() * box.centerY)
                        box.width = ((Math.abs(x - cx) * 2) / viewScale).toInt().coerceAtLeast(20)
                        box.height = ((Math.abs(y - cy) * 2) / viewScale).toInt().coerceAtLeast(20)
                    } else {
                        // 位置移動
                        box.centerX = ((x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
                        box.centerY = ((y - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
                    }
                    invalidate()
                }
            }
        }
        return true
    }
}
