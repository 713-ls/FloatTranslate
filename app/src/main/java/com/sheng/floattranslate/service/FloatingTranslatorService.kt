package com.sheng.floattranslate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.sheng.floattranslate.R
import com.sheng.floattranslate.data.TranslationRepository
import com.sheng.floattranslate.model.CaptureMode
import com.sheng.floattranslate.model.ScreenTextBlock
import com.sheng.floattranslate.model.TranslateDirection
import com.sheng.floattranslate.ocr.FullScreenTranslationOverlay
import com.sheng.floattranslate.ocr.ScreenOcrManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingTranslatorService : Service() {

    companion object {
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var tvBubbleDir: TextView
    private lateinit var tvBubbleMode: TextView
    private lateinit var bubbleParams: WindowManager.LayoutParams

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var repository: TranslationRepository
    private lateinit var ocrManager: ScreenOcrManager

    private var direction = TranslateDirection.EN_TO_ZH
    private var captureMode = CaptureMode.NODE
    private var activeOverlay: FullScreenTranslationOverlay? = null
    private var isBusy = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repository = TranslationRepository(this)
        ocrManager = ScreenOcrManager(this)

        startForegroundNotification()
        initBubbleView()
    }

    private fun startForegroundNotification() {
        val channelId = "float_translate_v2"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "FloatTranslate 全屏翻译", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("FloatTranslate 运行中")
            .setContentText("单击翻译全屏 | 长按切换英/中 | 双击切换节点/识图")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1002, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1002, notification)
        }
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun initBubbleView() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null)
        tvBubbleDir = bubbleView.findViewById(R.id.tvBubbleDir)
        tvBubbleMode = bubbleView.findViewById(R.id.tvBubbleMode)
        updateBubbleLabels()

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 420
        }

        var initX = 0
        var initY = 0
        var downRawX = 0f
        var downRawY = 0f
        var isDragging = false

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (activeOverlay != null) {
                    removeTranslationOverlay()
                } else {
                    triggerFullScreenTranslate()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                captureMode = captureMode.toggle()
                updateBubbleLabels()
                Toast.makeText(
                    this@FloatingTranslatorService,
                    "已切换为：${captureMode.shortLabel}全屏模式",
                    Toast.LENGTH_SHORT
                ).show()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!isDragging) {
                    direction = direction.toggle()
                    updateBubbleLabels()
                    Toast.makeText(
                        this@FloatingTranslatorService,
                        "已切换方向：${direction.shortLabel}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })

        bubbleView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = bubbleParams.x
                    initY = bubbleParams.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        isDragging = true
                        bubbleParams.x = initX + dx
                        bubbleParams.y = initY + dy
                        windowManager.updateViewLayout(bubbleView, bubbleParams)
                    }
                    true
                }
                else -> true
            }
        }

        windowManager.addView(bubbleView, bubbleParams)
    }

    private fun updateBubbleLabels() {
        tvBubbleDir.text = direction.shortLabel
        tvBubbleMode.text = captureMode.shortLabel
    }

    private fun triggerFullScreenTranslate() {
        if (isBusy) return
        val acc = NodeAccessibilityService.instance
        if (acc == null) {
            Toast.makeText(
                this,
                "请先在系统设置中开启「FloatTranslate 全屏自动取词」无障碍服务",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        isBusy = true
        removeTranslationOverlay()

        val isEnToZh = (direction == TranslateDirection.EN_TO_ZH)
        tvBubbleMode.text = "翻译中"

        // 在「中→英」模式下，因为网页/小程序里的灰色填空提示（如 fun - - - -）和横线槽数需要高对比度截屏才能完整识别，
        // 如果节点模式只抓到了 1 个英文词（漏掉了输入框里的灰色提示），自动无缝结合增强截屏 OCR！
        if (captureMode == CaptureMode.NODE) {
            val nodeBlocks = acc.collectScreenTextBlocks(isEnToZh)
            val hasEnoughClues = if (isEnToZh) {
                nodeBlocks.isNotEmpty()
            } else {
                // 检查节点是否已经包含横线提示或下方多选按钮
                val enCount = nodeBlocks.count { !it.originalText.any { ch -> ch in '\u4e00'..'\u9fa5' } }
                val hasDashHint = nodeBlocks.any { it.originalText.contains("-") || it.originalText.contains("_") }
                enCount >= 4 || hasDashHint
            }
            if (hasEnoughClues) {
                translateAndRenderBlocks(nodeBlocks, "${direction.shortLabel}·节点")
                return
            }
        }

        bubbleView.visibility = View.INVISIBLE
        mainHandler.postDelayed({
            ocrManager.captureFullScreenAndRecognize(
                isEnToZh = isEnToZh,
                onSuccess = { ocrBlocks ->
                    bubbleView.visibility = View.VISIBLE
                    translateAndRenderBlocks(ocrBlocks, "${direction.shortLabel}·全屏识图")
                },
                onError = { errMsg ->
                    bubbleView.visibility = View.VISIBLE
                    isBusy = false
                    updateBubbleLabels()
                    Toast.makeText(this, errMsg, Toast.LENGTH_SHORT).show()
                }
            )
        }, 35)
    }

    private fun translateAndRenderBlocks(blocks: List<ScreenTextBlock>, modeTag: String) {
        scope.launch {
            val translated = repository.translateBlocks(blocks, direction)
            isBusy = false
            updateBubbleLabels()
            showTranslationOverlay(translated, modeTag)
        }
    }

    private fun showTranslationOverlay(blocks: List<ScreenTextBlock>, statusHint: String) {
        removeTranslationOverlay()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        activeOverlay = FullScreenTranslationOverlay(
            this,
            blocks = blocks,
            statusHint = statusHint,
            onDismiss = { removeTranslationOverlay() }
        )
        windowManager.addView(activeOverlay, params)
    }

    private fun removeTranslationOverlay() {
        activeOverlay?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        activeOverlay = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        removeTranslationOverlay()
        if (bubbleView.isAttachedToWindow) windowManager.removeView(bubbleView)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
