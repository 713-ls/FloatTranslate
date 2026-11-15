package com.sheng.floattranslate.ocr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

class ScreenOcrManager(private val context: Context) {

    companion object {
        var resultCode: Int = Activity.RESULT_CANCELED
        var resultData: Intent? = null
        fun hasPermission(): Boolean = resultCode == Activity.RESULT_OK && resultData != null
    }

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val mainHandler = Handler(Looper.getMainLooper())

    fun captureAndRecognize(cropRect: RectF, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val data = resultData
        if (!hasPermission() || data == null) {
            onError("请先在主界面授予截屏识图权限")
            return
        }

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection = projectionManager.getMediaProjection(resultCode, data)
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        var virtualDisplay: VirtualDisplay? = null
        virtualDisplay = projection.createVirtualDisplay(
            "FloatTranslateCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            mainHandler
        )

        mainHandler.postDelayed({
            try {
                val image = imageReader.acquireLatestImage()
                if (image == null) {
                    virtualDisplay?.release()
                    projection.stop()
                    onError("未能捕获屏幕画面，请重试")
                    return@postDelayed
                }

                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val fullBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)
                image.close()
                virtualDisplay?.release()
                projection.stop()

                val left = cropRect.left.toInt().coerceIn(0, width - 1)
                val top = cropRect.top.toInt().coerceIn(0, height - 1)
                val cropW = cropRect.width().toInt().coerceIn(1, width - left)
                val cropH = cropRect.height().toInt().coerceIn(1, height - top)

                val cropped = Bitmap.createBitmap(fullBitmap, left, top, cropW, cropH)
                fullBitmap.recycle()

                val inputImage = InputImage.fromBitmap(cropped, 0)
                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        cropped.recycle()
                        val text = visionText.text.trim()
                        if (text.isEmpty()) onError("框选区域未识别到文字") else onResult(text)
                    }
                    .addOnFailureListener { e ->
                        cropped.recycle()
                        onError("OCR 识别失败: ${e.message}")
                    }
            } catch (e: Exception) {
                virtualDisplay?.release()
                projection.stop()
                onError("截屏异常: ${e.message}")
            }
        }, 180)
    }
}
