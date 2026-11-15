package com.sheng.floattranslate.ocr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

class RegionOverlayView(
    context: Context,
    private val onRegionSelected: (RectF) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {

    private val maskPaint = Paint().apply {
        color = Color.argb(110, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#3186FF")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val clearPaint = Paint().apply {
        color = Color.argb(40, 49, 134, 255)
        style = Paint.Style.FILL
    }
    private val tipPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var isDragging = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        if (isDragging) {
            val rect = currentRect()
            canvas.drawRect(rect, clearPaint)
            canvas.drawRect(rect, borderPaint)
        } else {
            canvas.drawText("拖动框选需要识别翻译的区域（轻点取消）", width / 2f, height * 0.15f, tipPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                endX = event.x
                endY = event.y
                isDragging = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x
                endY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                val rect = currentRect()
                if (rect.width() > 30f && rect.height() > 20f) {
                    onRegionSelected(rect)
                } else {
                    onCancel()
                }
            }
        }
        return true
    }

    private fun currentRect(): RectF {
        return RectF(
            minOf(startX, endX),
            minOf(startY, endY),
            maxOf(startX, endX),
            maxOf(startY, endY)
        )
    }
}
