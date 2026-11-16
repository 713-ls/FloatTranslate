package com.sheng.floattranslate.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.sheng.floattranslate.model.ScreenTextBlock
import com.sheng.floattranslate.service.NodeAccessibilityService
import com.sheng.floattranslate.util.TextCleaner

class ScreenOcrManager(private val context: Context) {

    companion object {
        @Volatile
        var lastDetectedBlankSlotCount: Int = 0
            private set
    }

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    fun captureFullScreenAndRecognize(
        isEnToZh: Boolean,
        onSuccess: (List<ScreenTextBlock>) -> Unit,
        onError: (String) -> Unit
    ) {
        val acc = NodeAccessibilityService.instance
        if (acc == null) {
            onError("请先开启 FloatTranslate 无障碍服务")
            return
        }
        acc.takeSilentFullScreenshot(
            ContextCompat.getMainExecutor(context),
            onBitmap = { bmp -> runMlKitOcr(bmp, isEnToZh, onSuccess, onError) },
            onError = { onError("自动全屏截图失败，请稍后重试") }
        )
    }

    /**
     * 将浅灰色提示字母（如 #C0C4CC 的 fun - - - - / obs - - - -）加深对比度，
     * 防止 ML Kit 漏识别浅灰色前缀字母！
     */
    private fun enhanceLightGrayText(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // 增强对比度并将浅灰压暗为深灰，白底保持纯白
        val cm = ColorMatrix(
            floatArrayOf(
                2.6f, 0f, 0f, 0f, -380f,
                0f, 2.6f, 0f, 0f, -380f,
                0f, 0f, 2.6f, 0f, -380f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * 扫描题目上方区域的水平下划线数量（例如 3 条横线 ___ ___ ___ 或 2 行共 4 条横线），
     * 用于在选词组短语时严格填满对应数量的单词！
     */
    private fun countHorizontalBlankLines(bmp: Bitmap, maxScanBottom: Int): Int {
        val w = bmp.width
        val startY = (bmp.height * 0.17f).toInt().coerceAtLeast(320)
        val endY = maxScanBottom.coerceAtMost((bmp.height * 0.48f).toInt())
        if (endY <= startY + 20) return 0

        var totalSlots = 0
        var y = startY
        while (y < endY) {
            // 检测这一行是否有连续的灰色水平下划线线段
            var segCount = 0
            var currentRun = 0
            for (x in (w * 0.06f).toInt() until (w * 0.94f).toInt() step 3) {
                val pixel = bmp.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                // 下划线颜色通常为浅灰到深灰 (RGB 120..225 且接近中性灰)，且上下 6px 是纯白背景
                val isGrayLine = r in 110..228 && kotlin.math.abs(r - g) <= 12 && kotlin.math.abs(g - b) <= 12
                val aboveWhite = if (y >= 8) Color.red(bmp.getPixel(x, y - 7)) > 242 else true
                val belowWhite = if (y + 8 < bmp.height) Color.red(bmp.getPixel(x, y + 7)) > 242 else true

                if (isGrayLine && aboveWhite && belowWhite) {
                    currentRun += 3
                } else {
                    if (currentRun >= (w * 0.14f).toInt()) {
                        segCount++
                    }
                    currentRun = 0
                }
            }
            if (currentRun >= (w * 0.14f).toInt()) {
                segCount++
            }
            if (segCount >= 1) {
                totalSlots += segCount
                // 跳过当前下划线高度附近 45px，继续扫描下一行是否还有下划线（如第5张图两行共4条横线）
                y += 45
            } else {
                y += 3
            }
        }
        return totalSlots.coerceIn(0, 6)
    }

    private fun runMlKitOcr(
        bitmap: Bitmap,
        isEnToZh: Boolean,
        onSuccess: (List<ScreenTextBlock>) -> Unit,
        onError: (String) -> Unit
    ) {
        val screenH = bitmap.height
        val minContentTop = (screenH * 0.16f).toInt().coerceAtLeast(320)
        val maxContentBottom = (screenH * 0.88f).toInt()

        // 1. 先用原图增强对比度，让浅灰色的 "fun - - - -"、"cou - - - -" 变成清晰深黑色再送入 ML Kit
        val enhancedBitmap = enhanceLightGrayText(bitmap)
        val input = InputImage.fromBitmap(enhancedBitmap, 0)

        recognizer.process(input)
            .addOnSuccessListener { visionText ->
                enhancedBitmap.recycle()
                val list = mutableListOf<ScreenTextBlock>()
                var zhTop = (screenH * 0.45f).toInt()

                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val text = line.text.trim()
                        val rect: Rect? = line.boundingBox
                        if (rect != null &&
                            rect.top in minContentTop..maxContentBottom &&
                            !TextCleaner.isUiNoise(text)
                        ) {
                            if (TextCleaner.hasChinese(text) && rect.top < zhTop && rect.top > 360) {
                                zhTop = rect.top
                            }
                            val keep = if (isEnToZh) {
                                TextCleaner.shouldTranslate(text, true) || TextCleaner.hasChinese(text)
                            } else {
                                TextCleaner.hasChinese(text) || TextCleaner.hasEnglishOrBlankHint(text)
                            }
                            if (keep) {
                                list.add(ScreenTextBlock(text, rect))
                            }
                        }
                    }
                }

                // 统计中文题目上方的下划线横线槽数
                lastDetectedBlankSlotCount = countHorizontalBlankLines(bitmap, zhTop)
                bitmap.recycle()

                if (list.isEmpty()) {
                    onError("当前屏幕未识别到需要翻译的题目文字")
                } else {
                    onSuccess(list)
                }
            }
            .addOnFailureListener { e ->
                enhancedBitmap.recycle()
                bitmap.recycle()
                onError("OCR 识别出错: ${e.message}")
            }
    }
}
