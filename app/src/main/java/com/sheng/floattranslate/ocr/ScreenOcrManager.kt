package com.sheng.floattranslate.ocr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.sheng.floattranslate.model.ScreenTextBlock
import com.sheng.floattranslate.service.NodeAccessibilityService
import com.sheng.floattranslate.util.TextCleaner

class ScreenOcrManager(private val context: Context) {

    companion object {
        var resultCode: Int = Activity.RESULT_CANCELED
        var resultData: Intent? = null
        fun hasMediaProjection(): Boolean = resultCode == Activity.RESULT_OK && resultData != null
    }

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 自动截取整张手机屏幕并识别所有文字块与坐标：
     * 优先走 Android 11+ 无障碍静默截屏；若不支持则走 MediaProjection 全屏截图。
     */
    fun captureFullScreenAndRecognize(
        isEnToZh: Boolean,
        onSuccess: (List<ScreenTextBlock>) -> Unit,
        onError: (String) -> Unit
    ) {
        val acc = NodeAccessibilityService.instance
        if (acc != null) {
            acc.takeSilentFullScreenshot(
                ContextCompat.getMainExecutor(context),
                onBitmap = { bmp -> runMlKitOcr(bmp, isEnToZh, onSuccess, onError) },
                onError = { captureWithMediaProjection(isEnToZh, onSuccess, onError) }
            )
        } else {
            captureWithMediaProjection(isEnToZh, onSuccess, onError)
        }
    }

    private fun captureWithMediaProjection(
        isEnToZh: Boolean,
        onSuccess: (List<ScreenTextBlock>) -> Unit,
        onError: (String) -> Unit
    ) {
        val data = resultData
        if (!hasMediaProjection() || data == null) {
            onError("请先在主界面开启「无障碍服务」或授予「全屏截图权限」")
            return
        }

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        try {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection: MediaProjection = mpm.getMediaProjection(resultCode, data)
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            var virtualDisplay: VirtualDisplay? = null
            virtualDisplay = projection.createVirtualDisplay(
                "FloatFullOcr",
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
                        onError("未能截取屏幕画面，请重试")
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

                    val cropped = Bitmap.createBitmap(fullBitmap, 0, 0, width, height)
                    fullBitmap.recycle()

                    runMlKitOcr(cropped, isEnToZh, onSuccess, onError)
                } catch (e: Exception) {
                    virtualDisplay?.release()
                    projection.stop()
                    onError("截图失败: ${e.message}")
                }
            }, 150)
        } catch (e: Exception) {
            onError("录屏权限已失效，请回主界面重新开启无障碍或截图权限")
        }
    }

    private fun runMlKitOcr(
        bitmap: Bitmap,
        isEnToZh: Boolean,
        onSuccess: (List<ScreenTextBlock>) -> Unit,
        onError: (String) -> Unit
    ) {
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { visionText ->
                bitmap.recycle()
                val list = mutableListOf<ScreenTextBlock>()
                for (block in visionText.textBlocks) {
                    val text = block.text.trim().replace("\n", " ")
                    val rect: Rect? = block.boundingBox
                    if (rect != null && rect.top > 40 && TextCleaner.shouldTranslate(text, isEnToZh)) {
                        list.add(ScreenTextBlock(text, rect))
                    }
                    if (list.size >= 35) break
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
