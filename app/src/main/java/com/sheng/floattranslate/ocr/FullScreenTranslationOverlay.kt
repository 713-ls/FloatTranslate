package com.sheng.floattranslate.ocr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import com.sheng.floattranslate.model.ScreenTextBlock

/**
 * 原位覆盖翻译层：直接在原网页/PDF文字的坐标位置上绘制白底黑字译文，
 * 不弹任何输入框，轻触屏幕任意位置立即关闭译文层。
 */
class FullScreenTranslationOverlay(
    context: Context,
    private val blocks: List<ScreenTextBlock>,
    private val statusHint: String,
    private val onDismiss: () -> Unit
) : View(context) {

    private val boxBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(238, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private val boxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#93C5FD")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111827")
        textSize = 34f
    }

    private val topPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 17, 24, 39)
        style = Paint.Style.FILL
    }

    private val topPillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (b in blocks) {
            val text = b.translatedText.ifEmpty { b.originalText }
            val rect = b.bounds
            val pad = 8f
            val boxWidth = (rect.width() + pad * 2).coerceAtLeast(120f)

            textPaint.textSize = when {
                rect.height() < 45 -> 28f
                rect.height() > 110 -> 36f
                else -> 32f
            }

            val staticLayout = StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, boxWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()

            val boxHeight = maxOf(rect.height().toFloat(), staticLayout.height + pad * 2)
            val drawRect = RectF(
                rect.left - pad,
                rect.top - pad,
                rect.left - pad + boxWidth,
                rect.top - pad + boxHeight
            )

            canvas.drawRoundRect(drawRect, 10f, 10f, boxBgPaint)
            canvas.drawRoundRect(drawRect, 10f, 10f, boxBorderPaint)

            canvas.save()
            canvas.translate(drawRect.left + pad, drawRect.top + pad)
            staticLayout.draw(canvas)
            canvas.restore()
        }

        // 顶部小胶囊提示：轻触屏幕任意处退出译文覆盖
        val pillW = 620f
        val pillH = 68f
        val pillLeft = (width - pillW) / 2f
        val pillTop = 80f
        canvas.drawRoundRect(
            RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH),
            34f,
            34f,
            topPillPaint
        )
        canvas.drawText(
            "$statusHint · 轻触任意处关闭译文",
            width / 2f,
            pillTop + 45f,
            topPillTextPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            onDismiss()
            return true
        }
        return super.onTouchEvent(event)
    }
}
