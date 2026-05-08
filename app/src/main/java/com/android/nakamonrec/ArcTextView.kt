package com.android.nakamonrec

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

class ArcTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 0.0: 下側・逆アーチ (アイコン風), 1.0: 上側・正アーチ (アプリ風)
    var animationProgress: Float = 1.0f
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 40f, resources.displayMetrics)
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.2f
        alpha = 210
    }

    private val path = Path()
    private val rectF = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        
        val density = resources.displayMetrics.density
        val radius = 115f * density 
        
        path.reset()
        rectF.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        
        // 進捗に応じて開始角度を遷移
        // 逆アーチ (0度) -> 正アーチ (-180度) に向かうことで反時計回りに
        val startAngle = 0f - (180f * animationProgress)
        path.addArc(rectF, startAngle, 180f)
        
        canvas.drawTextOnPath("NAKAMON", path, 0f, 0f, paint)
    }
}
