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

        private val UI_CHROME_WORDS = setOf(
            "练习", "返回", "提交", "下一题", "确定", "完成", "跳过", "提示", "查看解析"
        )
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

    /**
     * 抓取当前屏幕所有可见文字节点与坐标。
     * 在「中→英」模式下，同时抓取页面上的英文候选词与首字母提示（如 ext - - - -、observation、under），
     * 以便自动完成选词组合与首字母补全单词。
     */
    fun collectScreenTextBlocks(isEnToZh: Boolean): List<ScreenTextBlock> {
        val root = rootInActiveWindow ?: return emptyList()
        val blocks = mutableListOf<ScreenTextBlock>()
        val seenKeys = HashSet<String>()
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || blocks.size >= 40) return
            if (node.packageName?.toString() == packageName) return

            val text = (node.text ?: node.hintText ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && text !in UI_CHROME_WORDS && !text.contains(".com")) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                // 跳过顶部导航栏 (y < 240) 与底部按钮区
                if (rect.width() > 20 && rect.height() > 16 &&
                    rect.top in 220..(screenHeight - 120) &&
                    rect.left >= 0 && rect.right <= screenWidth
                ) {
                    val keep = if (isEnToZh) {
                        TextCleaner.shouldTranslate(text, true)
                    } else {
                        // 中→英模式：既保留中文题目，也保留英文候选词和填空提示
                        TextCleaner.hasChinese(text) || TextCleaner.hasEnglishOrBlankHint(text)
                    }
                    if (keep) {
                        val key = "${rect.top / 18}:${rect.left / 18}:${text.take(30)}"
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
