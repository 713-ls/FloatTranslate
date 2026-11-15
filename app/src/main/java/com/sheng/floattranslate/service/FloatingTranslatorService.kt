package com.sheng.floattranslate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.sheng.floattranslate.R
import com.sheng.floattranslate.data.TranslationRepository
import com.sheng.floattranslate.model.CaptureMode
import com.sheng.floattranslate.model.TranslateDirection
import com.sheng.floattranslate.ocr.RegionOverlayView
import com.sheng.floattranslate.ocr.ScreenOcrManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FloatingTranslatorService : Service() {

    companion object {
        var isRunning = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var panelView: View
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: TranslationRepository
    private lateinit var ocrManager: ScreenOcrManager

    private var direction = TranslateDirection.EN_TO_ZH
    private var captureMode = CaptureMode.NODE
    private var regionOverlay: RegionOverlayView? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repository = TranslationRepository(this)
        ocrManager = ScreenOcrManager(this)

        startForegroundNotification()
        initBubbleView()
        initPanelView()
    }

    private fun startForegroundNotification() {
        val channelId = "float_translate_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "FloatTranslate 悬浮服务", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("FloatTranslate 运行中")
            .setContentText("点击悬浮球即可取词翻译")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
        startForeground(1001, notification)
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
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 300
        }

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false

        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = bubbleParams.x
                    initY = bubbleParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    bubbleParams.x = initX + dx
                    bubbleParams.y = initY + dy
                    windowManager.updateViewLayout(bubbleView, bubbleParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) showPanel()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, bubbleParams)
    }

    private fun initPanelView() {
        panelView = LayoutInflater.from(this).inflate(R.layout.layout_floating_panel, null)
        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val btnDirection = panelView.findViewById<TextView>(R.id.btnDirection)
        val btnCaptureMode = panelView.findViewById<TextView>(R.id.btnCaptureMode)
        val btnCollapse = panelView.findViewById<TextView>(R.id.btnCollapse)
        val btnClose = panelView.findViewById<TextView>(R.id.btnClose)
        val etSource = panelView.findViewById<EditText>(R.id.etSourceText)
        val btnCapture = panelView.findViewById<Button>(R.id.btnCapture)
        val btnTranslate = panelView.findViewById<Button>(R.id.btnTranslate)
        val tvMeta = panelView.findViewById<TextView>(R.id.tvSourceMeta)
        val tvResult = panelView.findViewById<TextView>(R.id.tvResult)
        val btnCopy = panelView.findViewById<TextView>(R.id.btnCopyResult)

        btnDirection.setOnClickListener {
            direction = direction.toggle()
            btnDirection.text = direction.label
        }

        btnCaptureMode.setOnClickListener {
            captureMode = captureMode.toggle()
            btnCaptureMode.text = captureMode.label
            btnCapture.text = if (captureMode == CaptureMode.NODE) "读取节点" else "框选识图"
        }

        btnCollapse.setOnClickListener { hidePanel() }
        btnClose.setOnClickListener { stopSelf() }

        btnCapture.setOnClickListener {
            if (captureMode == CaptureMode.NODE) {
                val acc = NodeAccessibilityService.instance
                if (acc == null) {
                    tvResult.text = "请先在主界面开启「无障碍节点取词」服务"
                    return@setOnClickListener
                }
                val text = acc.captureCurrentPageText()
                if (text.isEmpty()) {
                    tvResult.text = "未读取到当前页面文本，可切换为「框选识图」"
                } else {
                    etSource.setText(text)
                    performTranslate(text, tvMeta, tvResult)
                }
            } else {
                startRegionOcrCapture(etSource, tvMeta, tvResult)
            }
        }

        btnTranslate.setOnClickListener {
            performTranslate(etSource.text.toString(), tvMeta, tvResult)
        }

        btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("FloatTranslate", tvResult.text))
            Toast.makeText(this, "已复制翻译结果", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRegionOcrCapture(etSource: EditText, tvMeta: TextView, tvResult: TextView) {
        if (!ScreenOcrManager.hasPermission()) {
            tvResult.text = "请先在主界面授予「截屏识图」权限"
            return
        }
        hidePanel()
        bubbleView.visibility = View.GONE

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        regionOverlay = RegionOverlayView(
            this,
            onRegionSelected = { rect ->
                removeRegionOverlay()
                ocrManager.captureAndRecognize(
                    cropRect = rect,
                    onResult = { recognized ->
                        bubbleView.visibility = View.VISIBLE
                        showPanel()
                        etSource.setText(recognized)
                        performTranslate(recognized, tvMeta, tvResult)
                    },
                    onError = { err ->
                        bubbleView.visibility = View.VISIBLE
                        showPanel()
                        tvResult.text = err
                    }
                )
            },
            onCancel = {
                removeRegionOverlay()
                bubbleView.visibility = View.VISIBLE
                showPanel()
            }
        )
        windowManager.addView(regionOverlay, overlayParams)
    }

    private fun removeRegionOverlay() {
        regionOverlay?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        regionOverlay = null
    }

    private fun performTranslate(input: String, tvMeta: TextView, tvResult: TextView) {
        tvResult.text = "正在联网查询..."
        tvMeta.visibility = View.GONE
        scope.launch {
            val res = repository.translate(input, direction)
            val metaParts = listOfNotNull(
                res.phonetic?.let { "音标: $it" },
                "来源: ${res.sourceLabel}"
            )
            tvMeta.text = metaParts.joinToString("  |  ")
            tvMeta.visibility = View.VISIBLE
            tvResult.text = res.translatedText
        }
    }

    private fun showPanel() {
        if (!panelView.isAttachedToWindow) {
            bubbleView.visibility = View.GONE
            windowManager.addView(panelView, panelParams)
        }
    }

    private fun hidePanel() {
        if (panelView.isAttachedToWindow) {
            windowManager.removeView(panelView)
            bubbleView.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        removeRegionOverlay()
        if (bubbleView.isAttachedToWindow) windowManager.removeView(bubbleView)
        if (panelView.isAttachedToWindow) windowManager.removeView(panelView)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
