package com.sheng.floattranslate.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sheng.floattranslate.model.ScreenTextBlock
import com.sheng.floattranslate.util.TextCleaner
import java.util.concurrent.Executor
import kotlin.math.abs

class NodeAccessibilityService : AccessibilityService() {

    companion object {
        var instance: NodeAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    fun collectScreenTextBlocks(isEnToZh: Boolean): List<ScreenTextBlock> {
        val root = rootInActiveWindow ?: return emptyList()
        val blocks = mutableListOf<ScreenTextBlock>()
        val seenKeys = HashSet<String>()
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels

        val minContentTop = (screenHeight * 0.16f).toInt().coerceAtLeast(320)
        val maxContentBottom = (screenHeight * 0.88f).toInt()

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || blocks.size >= 45) return
            if (node.packageName?.toString() == packageName) return

            val text = (node.text ?: node.hintText ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && !TextCleaner.isUiNoise(text)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() in 20..(screenWidth - 10) &&
                    rect.height() in 16..550 &&
                    rect.top in minContentTop..maxContentBottom &&
                    rect.left >= 0 && rect.right <= screenWidth
                ) {
                    val keep = if (isEnToZh) {
                        TextCleaner.shouldTranslate(text, true)
                    } else {
                        TextCleaner.hasChinese(text) || TextCleaner.hasEnglishOrBlankHint(text)
                    }
                    if (keep) {
                        val key = "${rect.top / 20}:${rect.left / 20}:${text.take(35)}"
                        if (seenKeys.add(key)) {
                            blocks.add(ScreenTextBlock(text, rect))
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
        return if (isEnToZh) mergeInlineAndAdjacentEnglishNodes(blocks) else blocks
    }

    /**
     * 网页/小程序常把一句话里有绿色下划线的词（如 "The punishments" + "accorded" + "with the current code of discipline."）
     * 拆成多个相邻的 Span 节点，导致一句话只翻了一半！这里把同一段落内同行或上下紧挨的英文节点自动拼接成完整句子。
     */
    private fun mergeInlineAndAdjacentEnglishNodes(raw: List<ScreenTextBlock>): List<ScreenTextBlock> {
        if (raw.size <= 1) return raw
        val sorted = raw.sortedWith(compareBy({ it.bounds.top / 45 }, { it.bounds.left }))
        val merged = mutableListOf<ScreenTextBlock>()

        for (item in sorted) {
            val prev = merged.lastOrNull()
            if (prev != null && !TextCleaner.hasChinese(prev.originalText) && !TextCleaner.hasChinese(item.originalText)) {
                val sameLine = abs(item.bounds.centerY() - prev.bounds.centerY()) < 55
                val nextLine = item.bounds.top - prev.bounds.bottom in -20..75
                if (sameLine || nextLine) {
                    val combined = if (prev.originalText.contains(item.originalText)) {
                        prev.originalText
                    } else {
                        "${prev.originalText.trim()} ${item.originalText.trim()}"
                    }
                    val union = Rect(
                        minOf(prev.bounds.left, item.bounds.left),
                        minOf(prev.bounds.top, item.bounds.top),
                        maxOf(prev.bounds.right, item.bounds.right),
                        maxOf(prev.bounds.bottom, item.bounds.bottom)
                    )
                    merged[merged.lastIndex] = ScreenTextBlock(combined, union)
                    continue
                }
            }
            merged.add(item)
        }
        return merged
    }

    fun takeSilentFullScreenshot(
        mainExecutor: Executor,
        onBitmap: (Bitmap) -> Unit,
        onError: () -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hwBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                        hwBuffer.close()
                        if (hwBitmap != null) {
                            val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap.recycle()
                            if (softBitmap != null) {
                                onBitmap(softBitmap)
                                return
                            }
                        }
                        onError()
                    }

                    override fun onFailure(errorCode: Int) {
                        onError()
                    }
                }
            )
        } else {
            onError()
        }
    }
}
