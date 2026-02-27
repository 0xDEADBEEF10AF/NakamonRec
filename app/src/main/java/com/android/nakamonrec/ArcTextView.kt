package com.android.nakamonrec

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

class ArcTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        // 32sp相当をベースに、さらに大きく(40sp相当)設定
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 40f, resources.displayMetrics)
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.2f
        alpha = 210 // 存在感を出しつつ、背景に馴染ませる
    }

    private val path = Path()
    private val rectF = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        
        val density = resources.displayMetrics.density
        // 文字が大きくなった分、半径を広げてボタンの外側に綺麗に配置
        val radius = 115f * density 
        
        path.reset()
        rectF.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        
        // 上半分の円弧 (180度から180度分)
        path.addArc(rectF, 180f, 180f)
        
        canvas.drawTextOnPath("NAKAMON", path, 0f, 0f, paint)
    }
}
