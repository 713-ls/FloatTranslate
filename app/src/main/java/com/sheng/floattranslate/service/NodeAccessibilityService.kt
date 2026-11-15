package com.sheng.floattranslate.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class NodeAccessibilityService : AccessibilityService() {

    companion object {
        var instance: NodeAccessibilityService? = null
            private set

        @Volatile
        var lastSelectedText: String = ""
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            val text = event.text?.joinToString(" ")?.trim().orEmpty()
            if (text.isNotEmpty()) {
                lastSelectedText = text
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    /**
     * 读取当前窗口节点文本（跳过本应用悬浮窗自身文本），优先返回选中内容或代码块/段落。
     */
    fun captureCurrentPageText(): String {
        val root = rootInActiveWindow ?: return ""
        val collected = LinkedHashSet<String>()
        traverseNode(root, collected)
        return collected.take(8).joinToString(" ").take(400)
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, out: MutableSet<String>) {
        if (node == null) return
        if (node.packageName?.toString() == packageName) return

        val text = node.text?.toString()?.trim().orEmpty()
        if (text.length in 2..300 && !text.startsWith("http")) {
            out.add(text)
        }
        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), out)
            if (out.size >= 12) break
        }
    }
}
