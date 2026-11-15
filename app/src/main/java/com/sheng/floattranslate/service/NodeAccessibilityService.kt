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

    /**
     * 直接抓取当前屏幕所有可见文字节点及其在屏幕上的矩形坐标，无需手输或截图。
     */
    fun collectScreenTextBlocks(isEnToZh: Boolean): List<ScreenTextBlock> {
        val root = rootInActiveWindow ?: return emptyList()
        val blocks = mutableListOf<ScreenTextBlock>()
        val seenTexts = HashSet<String>()
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || blocks.size >= 35) return
            if (node.packageName?.toString() == packageName) return

            val raw = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (raw.length in 2..220 && TextCleaner.shouldTranslate(raw, isEnToZh)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 24 && rect.height() > 16 &&
                    rect.top >= 40 && rect.bottom <= screenHeight &&
                    rect.left >= 0 && rect.right <= screenWidth
                ) {
                    val key = "${rect.top / 20}:${raw.take(30)}"
                    if (seenTexts.add(key)) {
                        blocks.add(ScreenTextBlock(raw, rect))
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

    /**
     * Android 11 (API 30)+ 支持通过无障碍服务直接静默全屏截图，无需每次弹窗请求录屏权限。
     */
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
