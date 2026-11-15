package com.sheng.floattranslate.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.sheng.floattranslate.R
import com.sheng.floattranslate.data.TranslationDbHelper
import com.sheng.floattranslate.ocr.ScreenOcrManager
import com.sheng.floattranslate.service.FloatingTranslatorService
import com.sheng.floattranslate.util.PrefsManager

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager
    private lateinit var db: TranslationDbHelper

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenOcrManager.resultCode = result.resultCode
            ScreenOcrManager.resultData = result.data
            Toast.makeText(this, "截屏识图权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未授予截屏识图权限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PrefsManager(this)
        db = TranslationDbHelper(this)

        val btnOverlay = findViewById<Button>(R.id.btnOverlayPerm)
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibilityPerm)
        val btnScreenCapture = findViewById<Button>(R.id.btnScreenCapturePerm)
        val btnToggleFloating = findViewById<Button>(R.id.btnToggleFloating)

        val rgEngine = findViewById<RadioGroup>(R.id.rgEngine)
        val rbFree = findViewById<RadioButton>(R.id.rbFreeApi)
        val rbCustom = findViewById<RadioButton>(R.id.rbCustomApi)
        val layoutCustom = findViewById<LinearLayout>(R.id.layoutCustomApi)
        val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val etModel = findViewById<EditText>(R.id.etModel)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)

        etBaseUrl.setText(prefs.baseUrl)
        etApiKey.setText(prefs.apiKey)
        etModel.setText(prefs.modelName)
        if (prefs.useCustomApi) {
            rbCustom.isChecked = true
            layoutCustom.visibility = View.VISIBLE
        } else {
            rbFree.isChecked = true
            layoutCustom.visibility = View.GONE
        }

        rgEngine.setOnCheckedChangeListener { _, checkedId ->
            layoutCustom.visibility = if (checkedId == R.id.rbCustomApi) View.VISIBLE else View.GONE
        }

        btnSave.setOnClickListener {
            prefs.useCustomApi = rbCustom.isChecked
            prefs.baseUrl = etBaseUrl.text.toString()
            prefs.apiKey = etApiKey.text.toString()
            prefs.modelName = etModel.text.toString()
            Toast.makeText(this, "引擎配置已保存", Toast.LENGTH_SHORT).show()
        }

        btnOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "悬浮窗权限已开启", Toast.LENGTH_SHORT).show()
            }
        }

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnScreenCapture.setOnClickListener {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
        }

        btnToggleFloating.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val serviceIntent = Intent(this, FloatingTranslatorService::class.java)
            if (FloatingTranslatorService.isRunning) {
                stopService(serviceIntent)
                btnToggleFloating.text = "启动悬浮球"
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                btnToggleFloating.text = "停止悬浮球"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val tvHistory = findViewById<TextView>(R.id.tvHistory)
        val records = db.getRecentHistory(8)
        tvHistory.text = if (records.isEmpty()) {
            "暂无缓存记录"
        } else {
            records.joinToString("\n") { "• ${it.first} → ${it.second.replace("\n", " ").take(40)}" }
        }
    }
}
