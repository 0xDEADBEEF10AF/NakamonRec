package com.dqw.nakamonrec

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.PI

/**
 * REC ボタンの周りに弧状のタイトル文字を描く View。
 * - 通常: 上弧に「NAKAMON」(drawTextOnPath)
 * - グランプリモード ON (26.10.2): 上弧「MONSTER」+ 下弧「GRANDPRIX」。どちらもボタンと同心・同じ半径で、
 *   文字のボタン側の縁 (上弧は基線、下弧は文字の天) が円周上に乗る。iOS ContentView の CurvedText と同じく
 *   **1 文字ずつ等角で回転配置**する (パスの向きに依存する drawTextOnPath は端末により下弧が上側に
 *   展開される事象があったため不採用。26.10.2 クローズドテストで判明)。
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

    // GP 表示用: 1 文字ずつ配置するので字間は角度ステップで決まる (letterSpacing は使わない)。
    // 9 文字の GRANDPRIX を半円に収めるため NAKAMON より少し小さめ
    private val gpBaseTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 34f, resources.displayMetrics)
    private val gpPaint = Paint(paint).apply {
        textSize = gpBaseTextSize
        letterSpacing = 0f
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

        if (!grandPrixMode) {
            path.reset()
            rectF.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
            // 進捗に応じて開始角度を遷移
            // 逆アーチ (0度) -> 正アーチ (-180度) に向かうことで反時計回りに
            val startAngle = 0f - (180f * animationProgress)
            path.addArc(rectF, startAngle, 180f)
            canvas.drawTextOnPath("NAKAMON", path, 0f, 0f, paint)
            return
        }

        // --- グランプリモード: 上弧 MONSTER / 下弧 GRANDPRIX (1 文字 18° の等角配置、iOS と同じ) ---
        // 一番幅のある "M" が 1 ステップ分の弧長の 90% を超えるなら文字サイズを縮小 (大フォント設定の保険)
        gpPaint.textSize = gpBaseTextSize
        val arcPerStep = radius * (GP_STEP_DEG * PI / 180.0).toFloat()
        val widest = gpPaint.measureText("M")
        if (widest > arcPerStep * 0.9f) gpPaint.textSize = gpBaseTextSize * (arcPerStep * 0.9f / widest)
        gpPaint.getTextBounds("GRANDPRIX", 0, 9, bounds)
        val capHeight = bounds.height().toFloat()

        // 起動アニメ: 2 弧をまとめて 180° (逆アーチ) → 0° まで反時計回りに回す (NAKAMON と同じ動き)
        canvas.save()
        canvas.rotate(180f * (1f - animationProgress), centerX, centerY)
        drawArcWord(canvas, "MONSTER", centerX, centerY, radius, capHeight, top = true)
        drawArcWord(canvas, "GRANDPRIX", centerX, centerY, radius, capHeight, top = false)
        canvas.restore()
    }

    /**
     * 単語を 1 文字ずつ円周上に等角配置する。
     * - 上弧: 左→右が時計回り (角度 +)。基線を円周に置き、文字は外側へ立つ
     * - 下弧: 左→右が反時計回り (角度 −)。文字の天が円周に乗るよう基線を文字高ぶん外側へ置く。
     *   canvas.rotate で文字自体も同じ角度だけ回るので、天は常に円の中心を向き正立のまま読める
     */
    private fun drawArcWord(canvas: Canvas, word: String, cx: Float, cy: Float, radius: Float, capHeight: Float, top: Boolean) {
        val n = word.length
        val baselineY = if (top) cy - radius else cy + radius + capHeight
        for (i in 0 until n) {
            val offsetSteps = i - (n - 1) / 2f
            val angle = (if (top) 1f else -1f) * GP_STEP_DEG * offsetSteps
            canvas.save()
            canvas.rotate(angle, cx, cy)   // 正 = 時計回り (y 軸下向きの画面座標)
            canvas.drawText(word, i, i + 1, cx, baselineY, gpPaint)
            canvas.restore()
        }
    }

    private companion object {
        /** GP 表示の 1 文字あたりの角度 (NAKAMON の約 20° より少し詰めて GRANDPRIX 9 文字 = 144° に収める) */
        const val GP_STEP_DEG = 18f
    }
}
