package com.sheng.floattranslate.ocr

import android.content.Context
import android.graphics.Bitmap
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
        private val UI_CHROME_WORDS = setOf(
            "练习", "返回", "提交", "下一题", "确定", "完成", "跳过", "提示", "查看解析"
        )
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

    private fun runMlKitOcr(
        bitmap: Bitmap,
        isEnToZh: Boolean,
        onSuccess: (List<ScreenTextBlock>) -> Unit,
        onError: (String) -> Unit
    ) {
        val screenH = bitmap.height
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { visionText ->
                bitmap.recycle()
                val list = mutableListOf<ScreenTextBlock>()
                for (block in visionText.textBlocks) {
                    val text = block.text.trim().replace("\n", " ")
                    val rect: Rect? = block.boundingBox
                    if (rect != null && rect.top in 220..(screenH - 120) &&
                        text !in UI_CHROME_WORDS && !text.contains(".com")
                    ) {
                        val keep = if (isEnToZh) {
                            TextCleaner.shouldTranslate(text, true)
                        } else {
                            TextCleaner.hasChinese(text) || TextCleaner.hasEnglishOrBlankHint(text)
                        }
                        if (keep) {
                            list.add(ScreenTextBlock(text, rect))
                        }
                    }
                    if (list.size >= 40) break
                }
                if (list.isEmpty()) {
                    onError("当前屏幕未识别到需要翻译的文字")
                } else {
                    onSuccess(list)
                }
            }
            .addOnFailureListener { e ->
                bitmap.recycle()
                onError("OCR 识别出错: ${e.message}")
            }
    }
}
