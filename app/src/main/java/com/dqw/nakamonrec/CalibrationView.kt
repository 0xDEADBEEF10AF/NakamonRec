package com.dqw.nakamonrec

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * 校正画面のプレビュー (スクショ + 緑枠 ROI + スコア) を描く表示専用 View。
 * 26.10.2: 手動ドラッグ/リサイズ (onTouchEvent) は廃止。校正は「校正する」(自動校正 / 詳細校正) 経由のみで、
 * iOS CalibrationView (ドラッグなし) と同じ構造にした。
 */
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
    private val imageRect = RectF()
    private val reusableRect = RectF()
    private val bufferRect = RectF()

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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val img = backgroundImage ?: return

        imageRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(img, null, imageRect, null)

        val bitmapW = img.width.toFloat()
        val bitmapH = img.height.toFloat()
        val viewW = imageRect.width()
        val viewH = imageRect.height()

        boxes.forEach { box ->
            val cx = imageRect.left + (viewW * box.centerX)
            val cy = imageRect.top + (viewH * box.centerY)
            
            val scaleX = viewW / bitmapW
            val scaleY = viewH / bitmapH
            
            val bw = (box.width * scaleX) / 2f
            val bh = (box.height * scaleY) / 2f

            reusableRect.set(cx - bw, cy - bh, cx + bw, cy + bh)
            
            // 探索範囲の可視化
            if (box.label.startsWith("P")) {
                // パーティ選択：BattleAnalyzer.ROI_PAD_PARTY_V/H と同期。
                // 実行時判定 (detectSelectedParty) はスクロール吸収分だけ上方向に
                // 非対称拡張されるため (上200/下100)、可視化も上側のみ広げる
                val padH = (BattleAnalyzer.ROI_PAD_PARTY_H * uiScale) * scaleX
                val padV = (BattleAnalyzer.ROI_PAD_PARTY_V * uiScale) * scaleY
                val padVUp = ((BattleAnalyzer.ROI_PAD_PARTY_V + BattleAnalyzer.ROI_PAD_PARTY_SCROLL_V) * uiScale) * scaleY
                bufferRect.set(reusableRect.left - padH, reusableRect.top - padVUp, reusableRect.right + padH, reusableRect.bottom + padV)
                canvas.drawRect(bufferRect, paintBufferFill)
                canvas.drawRect(bufferRect, paintBufferStroke)
            } else if (box.label.contains("自") || box.label.contains("敵")) {
                // モンスター：正方形（BattleAnalyzer.ROI_PAD_MONSTERと同期）
                val pad = (BattleAnalyzer.ROI_PAD_MONSTER * uiScale) * scaleX
                bufferRect.set(reusableRect.left - pad, reusableRect.top - pad, reusableRect.right + pad, reusableRect.bottom + pad)
                canvas.drawRect(bufferRect, paintBufferFill)
                canvas.drawRect(bufferRect, paintBufferStroke)
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
}
