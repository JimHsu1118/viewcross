package com.example.visioncrossnew

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tfLiteHelper: TFLiteHelper
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) startCamera()
        else Toast.makeText(this, "需要相機權限", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ==========================================
        // 🌟 破案關鍵：在這裡喚醒 OpenCV 引擎！
        // ==========================================
        if (!org.opencv.android.OpenCVLoader.initDebug()) {
            Log.e("AI_TEST", "❌ OpenCV 引擎啟動失敗！")
        } else {
            Log.d("AI_TEST", "✅ OpenCV 引擎啟動成功！準備接手人行道運算")
        }
        // ==========================================

        // 1. 建立根佈局 (可以讓多層畫面疊在一起)
        val rootLayout = FrameLayout(this)

        // 2. 底層：相機畫面
        previewView = PreviewView(this)

        // 🌟 強制畫面不要放大裁切，保留相機的完整視野
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        rootLayout.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 3. 上層：透明的畫圖玻璃
        overlayView = OverlayView(this)
        rootLayout.addView(overlayView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(rootLayout)

        // 4. 初始化 AI (當 AI 找到紅綠燈，就會呼叫 setResults 畫出來)
        tfLiteHelper = TFLiteHelper(this) { results ->
            // 🌟 修正：對應 OverlayView.kt 中的 setResults 函數
            runOnUiThread { overlayView.setResults(results) }
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) startCamera()
        else requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            // 🌟 修正：正確呼叫 imageAnalyzer
            imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                var bitmap: Bitmap? = null
                var rotatedBitmap: Bitmap? = null
                try {
                    bitmap = imageProxy.toBitmap() // 取得相機原始畫面
                    if (bitmap != null) {
                        // 抓取物理旋轉角度，用 Matrix 把相機畫面轉正
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees.toFloat()
                        val matrix = android.graphics.Matrix()
                        matrix.postRotate(rotationDegrees)

                        rotatedBitmap = Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                        )

                        // 傳遞「轉正後」的真實影像寬高給畫布，用來計算 FIT_CENTER 的黑邊
                        overlayView.setCameraFrameSize(rotatedBitmap.width, rotatedBitmap.height)

                        // 執行 AI 偵測
                        tfLiteHelper.detect(rotatedBitmap)
                    }
                } catch (e: Exception) {
                    Log.e("AI_TEST", "影像分析失敗: ${e.message}")
                } finally {
                    // ⚡ 終極記憶體釋放：手動回收 Bitmap
                    bitmap?.recycle()
                    rotatedBitmap?.recycle()

                    // 必須確保關閉 imageProxy，下一個影格才能進來
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("AI_TEST", "相機綁定失敗: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tfLiteHelper.close()
    }
}