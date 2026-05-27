package com.example.visioncrossnew

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
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
    private lateinit var statusTextView: TextView // 🌟 新增：畫面的文字顯示狀態欄

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) startCamera()
        else Toast.makeText(this, "需要相機權限", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!org.opencv.android.OpenCVLoader.initDebug()) {
            Log.e("AI_TEST", "❌ OpenCV 引擎啟動失敗！")
        } else {
            Log.d("AI_TEST", "✅ OpenCV 引擎啟動成功！")
        }

        val rootLayout = FrameLayout(this)

        previewView = PreviewView(this)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        rootLayout.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        overlayView = OverlayView(this)
        rootLayout.addView(overlayView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // ==========================================
        // 🌟 新增：建立最上層的智慧導航文字橫幅
        // ==========================================
        statusTextView = TextView(this).apply {
            textSize = 26f // 大字體方便觀看
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#A0000000")) // 高級感半透明黑底
            gravity = Gravity.CENTER
            text = "正在初始化系統..."
            setPadding(20, 30, 20, 30)
        }
        val textParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP // 釘在手機螢幕最上方
            topMargin = 60 // 往下移一點點避開手機瀏海與狀態欄
        }
        rootLayout.addView(statusTextView, textParams)
        // ==========================================

        setContentView(rootLayout)

        // 🌟 修正：配合 TFLiteHelper 的雙回調架構
        tfLiteHelper = TFLiteHelper(
            this,
            onResult = { results ->
                // YOLO 框框畫在玻璃墊上
                runOnUiThread { overlayView.setResults(results) }
            },
            onNavigationResult = { status ->
                // OpenCV 顏色導航字串直接洗在畫面的 TextView 上！
                runOnUiThread {
                    statusTextView.text = status
                    // 根據不同狀態動態變換顏色，測試時一目了然
                    when {
                        status.contains("NORMAL") -> statusTextView.setTextColor(Color.GREEN)
                        status.contains("OUT") -> statusTextView.setTextColor(Color.RED)
                        else -> statusTextView.setTextColor(Color.YELLOW) // SHIFT LEFT/RIGHT
                    }
                }
            }
        )

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
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

            imageAnalyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                var bitmap: Bitmap? = null
                var rotatedBitmap: Bitmap? = null
                try {
                    bitmap = imageProxy.toBitmap()
                    if (bitmap != null) {
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees.toFloat()
                        val matrix = android.graphics.Matrix()
                        matrix.postRotate(rotationDegrees)

                        rotatedBitmap = Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                        )

                        overlayView.setCameraFrameSize(rotatedBitmap.width, rotatedBitmap.height)
                        tfLiteHelper.detect(rotatedBitmap)
                    }
                } catch (e: Exception) {
                    Log.e("AI_TEST", "影像分析失敗: ${e.message}")
                } finally {
                    bitmap?.recycle()
                    rotatedBitmap?.recycle()
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