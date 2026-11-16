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

        // 顶部状态栏+微信/网页导航栏高度约占屏幕前 16%，严格跳过顶部「< 返回」与「练习」
        val minContentTop = (screenHeight * 0.16f).toInt().coerceAtLeast(320)
        val maxContentBottom = (screenHeight * 0.88f).toInt()

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || blocks.size >= 40) return
            if (node.packageName?.toString() == packageName) return

            val text = (node.text ?: node.hintText ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && !TextCleaner.isUiNoise(text)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() in 20..(screenWidth - 10) &&
                    rect.height() in 16..450 &&
                    rect.top in minContentTop..maxContentBottom &&
                    rect.left >= 0 && rect.right <= screenWidth
                ) {
                    val keep = if (isEnToZh) {
                        TextCleaner.shouldTranslate(text, true)
                    } else {
                        TextCleaner.hasChinese(text) || TextCleaner.hasEnglishOrBlankHint(text)
                    }
                    if (keep) {
                        val key = "${rect.top / 20}:${rect.left / 20}:${text.take(30)}"
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
        return blocks
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
