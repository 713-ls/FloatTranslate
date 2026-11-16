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

class FullScreenTranslationOverlay(
    context: Context,
    private val blocks: List<ScreenTextBlock>,
    private val statusHint: String,
    private val onDismiss: () -> Unit
) : View(context) {

    private val boxBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(242, 255, 255, 255)
        style = Paint.Style.FILL
    }

    private val boxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3B82F6")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
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

    private val screenOffset = IntArray(2)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 获取当前覆盖层在物理屏幕上的真实起点偏移（消除状态栏/挖孔屏导致的 ~100px 向下偏移！）
        getLocationOnScreen(screenOffset)
        val offsetX = screenOffset[0].toFloat()
        val offsetY = screenOffset[1].toFloat()

        for (b in blocks) {
            val text = b.translatedText.ifEmpty { b.originalText }
            val rect = b.bounds
            val pad = 8f
            val boxWidth = (rect.width() + pad * 2).coerceAtLeast(140f).coerceAtMost(width - 32f)

            textPaint.textSize = when {
                rect.height() < 45 -> 28f
                rect.height() > 120 -> 34f
                else -> 31f
            }

            val staticLayout = StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, boxWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()

            val boxHeight = maxOf(rect.height().toFloat(), staticLayout.height + pad * 2)
            val left = (rect.left - offsetX - pad).coerceAtLeast(12f)
            val top = (rect.top - offsetY - pad).coerceAtLeast(12f)

            val drawRect = RectF(
                left,
                top,
                left + boxWidth,
                top + boxHeight
            )

            canvas.drawRoundRect(drawRect, 12f, 12f, boxBgPaint)
            canvas.drawRoundRect(drawRect, 12f, 12f, boxBorderPaint)

            canvas.save()
            canvas.translate(drawRect.left + pad, drawRect.top + pad)
            staticLayout.draw(canvas)
            canvas.restore()
        }

        val pillW = 620f
        val pillH = 68f
        val pillLeft = (width - pillW) / 2f
        val pillTop = (90f - offsetY).coerceAtLeast(24f)
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
