package com.dqw.nakamonrec

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.PI

/**
 * REC ボタンの周りに弧状のタイトル文字を描く View。
 * - 通常: 上弧に「NAKAMON」
 * - グランプリモード ON (26.10.2): 上弧「MONSTER」+ 下弧「GRANDPRIX」。どちらもボタンと同心・同じ半径で、
 *   文字のボタン側の縁 (上弧は基線、下弧は文字の天) が円周上に乗る。iOS ContentView の CurvedText と同じ見た目。
 */
class ArcTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 0.0: 下側・逆アーチ (アイコン風), 1.0: 上側・正アーチ (アプリ風)
    var animationProgress: Float = 1.0f
        set(value) {
            field = value
            invalidate()
        }

    /** グランプリモード表示 (MONSTER / GRANDPRIX の 2 弧) にするか。MainActivity.onResume で設定 */
    var grandPrixMode: Boolean = false
        set(value) {
            if (field == value) return
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

    // GP 表示用: 9 文字の GRANDPRIX を半円に収めるため NAKAMON より少し小さめ・字間も控えめ
    private val gpBaseTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 34f, resources.displayMetrics)
    private val gpPaint = Paint(paint).apply {
        textSize = gpBaseTextSize
        letterSpacing = 0.15f
    }

    private val path = Path()
    private val rectF = RectF()
    private val bounds = Rect()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f

        val density = resources.displayMetrics.density
        val radius = 115f * density

        rectF.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        if (!grandPrixMode) {
            path.reset()
            // 進捗に応じて開始角度を遷移
            // 逆アーチ (0度) -> 正アーチ (-180度) に向かうことで反時計回りに
            val startAngle = 0f - (180f * animationProgress)
            path.addArc(rectF, startAngle, 180f)
            canvas.drawTextOnPath("NAKAMON", path, 0f, 0f, paint)
            return
        }

        // --- グランプリモード: 上弧 MONSTER / 下弧 GRANDPRIX ---
        // 長い方 (GRANDPRIX) が半円の 80% に収まるよう文字サイズを自動調整 (小画面・大フォント設定の保険)
        gpPaint.textSize = gpBaseTextSize
        val arcLength = (PI * radius).toFloat()
        val longest = gpPaint.measureText("GRANDPRIX")
        if (longest > arcLength * 0.8f) gpPaint.textSize = gpBaseTextSize * (arcLength * 0.8f / longest)

        // 上弧: 左 → 上 → 右 (時計回り)。基線が円周に乗り、文字は外側へ立つ (NAKAMON と同じ)
        path.reset()
        path.addArc(rectF, 0f - 180f * animationProgress, 180f)
        canvas.drawTextOnPath("MONSTER", path, 0f, 0f, gpPaint)

        // 下弧: 左 → 下 → 右 を反時計回りにたどると、文字の天が常に円の中心を向き正立のまま読める。
        // そのままだと文字が円の内側 (ボタン側) に食い込むので、文字の高さぶん基線を外側へ押し出し
        // (vOffset > 0 = パスの進行方向右手 = 外側)、文字の天が円周に乗るようにする
        gpPaint.getTextBounds("GRANDPRIX", 0, 9, bounds)
        val capHeight = bounds.height().toFloat()
        path.reset()
        path.addArc(rectF, 180f - 180f * animationProgress, -180f)
        canvas.drawTextOnPath("GRANDPRIX", path, 0f, capHeight, gpPaint)
    }
}
