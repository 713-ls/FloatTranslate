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
import kotlin.math.abs

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

    private fun enhanceLightGrayText(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
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

    private fun countHorizontalBlankLines(bmp: Bitmap, maxScanBottom: Int): Int {
        val w = bmp.width
        val startY = (bmp.height * 0.17f).toInt().coerceAtLeast(320)
        val endY = maxScanBottom.coerceAtMost((bmp.height * 0.48f).toInt())
        if (endY <= startY + 20) return 0

        var totalSlots = 0
        var y = startY
        while (y < endY) {
            var segCount = 0
            var currentRun = 0
            for (x in (w * 0.06f).toInt() until (w * 0.94f).toInt() step 3) {
                val pixel = bmp.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val isGrayLine = r in 110..228 && abs(r - g) <= 12 && abs(g - b) <= 12
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
                y += 45
            } else {
                y += 3
            }
        }
        return totalSlots.coerceIn(0, 6)
    }

    /**
     * 在「英→中」模式下，将同一段落中上下紧挨的多行英文合并为一个完整句子块！
     * 彻底解决长句跨行时被拆成两半（上半句不翻译或只翻下半句、译文框压住第二行）的问题。
     */
    private fun mergeAdjacentEnglishParagraphLines(rawLines: List<ScreenTextBlock>): List<ScreenTextBlock> {
        if (rawLines.size <= 1) return rawLines
        val sorted = rawLines.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val merged = mutableListOf<ScreenTextBlock>()

        for (item in sorted) {
            val prev = merged.lastOrNull()
            if (prev != null &&
                !TextCleaner.hasChinese(prev.originalText) &&
                !TextCleaner.hasChinese(item.originalText)
            ) {
                val verticalGap = item.bounds.top - prev.bounds.bottom
                val sameColumn = abs(item.bounds.left - prev.bounds.left) < 180
                // 如果两行英文垂直间距小于 1.2 倍行高且左边缘对齐（属于同一段长句换行），合并为完整一句话！
                val lineHeight = prev.bounds.height().coerceAtLeast(36)
                if (verticalGap in -15..(lineHeight * 1.3f).toInt() && sameColumn) {
                    val combinedText = "${prev.originalText.trim()} ${item.originalText.trim()}"
                    val unionRect = Rect(
                        minOf(prev.bounds.left, item.bounds.left),
                        minOf(prev.bounds.top, item.bounds.top),
                        maxOf(prev.bounds.right, item.bounds.right),
                        maxOf(prev.bounds.bottom, item.bounds.bottom)
                    )
                    merged[merged.lastIndex] = ScreenTextBlock(combinedText, unionRect)
                    continue
                }
            }
            merged.add(item)
        }
        return merged
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

        // 英→中模式直接用原始清晰图识别（避免彩色高亮单词如绿色的 arise / accorded 在高倍对比度下失真漏识别）；
        // 中→英模式才启用浅灰对比度加深（用于识别浅灰色的 fun - - - -）
        val targetBitmap = if (isEnToZh) bitmap else enhanceLightGrayText(bitmap)
        val input = InputImage.fromBitmap(targetBitmap, 0)

        recognizer.process(input)
            .addOnSuccessListener { visionText ->
                if (targetBitmap !== bitmap) targetBitmap.recycle()
                val rawList = mutableListOf<ScreenTextBlock>()
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
                                rawList.add(ScreenTextBlock(text, Rect(rect)))
                            }
                        }
                    }
                }

                lastDetectedBlankSlotCount = countHorizontalBlankLines(bitmap, zhTop)
                bitmap.recycle()

                val finalBlocks = if (isEnToZh) {
                    mergeAdjacentEnglishParagraphLines(rawList)
                } else {
                    rawList
                }

                if (finalBlocks.isEmpty()) {
                    onError("当前屏幕未识别到需要翻译的题目文字")
                } else {
                    onSuccess(finalBlocks)
                }
            }
            .addOnFailureListener { e ->
                if (targetBitmap !== bitmap) targetBitmap.recycle()
                bitmap.recycle()
                onError("OCR 识别出错: ${e.message}")
            }
    }
}
