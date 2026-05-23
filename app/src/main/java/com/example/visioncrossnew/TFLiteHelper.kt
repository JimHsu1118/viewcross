package com.example.visioncrossnew

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TFLiteHelper(private val context: Context, private val onResult: (List<DetectionResult>) -> Unit) {

    private val interpreters = mutableListOf<Interpreter>()

    // 專注測試這兩位健康的專家
    private val modelFiles = listOf(
        "traffic_1280_dynamic_range_quant.tflite",
        "yolov8n_dynamic_range_quant.tflite" // 假設這是包含綠色人行道的模型 (index = 1)
    )

    init {
        for (fileName in modelFiles) {
            try {
                val options = Interpreter.Options()
                options.numThreads = 4

                val interpreter = Interpreter(loadModelFile(fileName), options)

                interpreters.add(interpreter)
                val inShape = interpreter.getInputTensor(0).shape().contentToString()
                val outShape = interpreter.getOutputTensor(0).shape().contentToString()
                Log.d("AI_TEST", "✅ 載入 $fileName (輸入: $inShape, 輸出: $outShape)")
            } catch (e: Exception) {
                Log.e("AI_TEST", "❌ 載入 $fileName 失敗: ${e.message}")
            }
        }
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(modelName)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        return fileInputStream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFileDescriptor.startOffset,
            assetFileDescriptor.declaredLength
        )
    }

    fun detect(bitmap: Bitmap) {
        if (interpreters.isEmpty()) return

        try {
            val allResults = mutableListOf<DetectionResult>()
            val threshold = 0.3f

            // 🌟 裁切中心 60% 正方形，避免變形
            val minSize = minOf(bitmap.width, bitmap.height)
            val roiScale = 0.6f
            val roiSize = (minSize * roiScale).toInt()
            val roiX = (bitmap.width - roiSize) / 2
            val roiY = (bitmap.height - roiSize) / 2

            val croppedBitmap = Bitmap.createBitmap(bitmap, roiX, roiY, roiSize, roiSize)

            for ((index, interpreter) in interpreters.withIndex()) {
                var resizedBitmap: Bitmap? = null
                try {
                    val inputShape = interpreter.getInputTensor(0).shape()
                    val expectedHeight = inputShape[1]
                    val expectedWidth = inputShape[2]

                    resizedBitmap = Bitmap.createScaledBitmap(croppedBitmap, expectedWidth, expectedHeight, true)

                    val byteBuffer = ByteBuffer.allocateDirect(1 * expectedWidth * expectedHeight * 3 * 4)
                    byteBuffer.order(ByteOrder.nativeOrder())
                    val intValues = IntArray(expectedWidth * expectedHeight)
                    resizedBitmap.getPixels(intValues, 0, expectedWidth, 0, 0, expectedWidth, expectedHeight)

                    var pixel = 0
                    for (y in 0 until expectedHeight) {
                        for (x in 0 until expectedWidth) {
                            val valPixel = intValues[pixel++]
                            byteBuffer.putFloat(((valPixel shr 16) and 0xFF) / 255.0f) // R
                            byteBuffer.putFloat(((valPixel shr 8) and 0xFF) / 255.0f)  // G
                            byteBuffer.putFloat((valPixel and 0xFF) / 255.0f)          // B
                        }
                    }

                    val outputShape = interpreter.getOutputTensor(0).shape()
                    val numBoxElements = outputShape[1]
                    val numBoxes = outputShape[2]
                    val outputArray = Array(1) { Array(numBoxElements) { FloatArray(numBoxes) } }

                    interpreter.run(byteBuffer, outputArray)
                    val numClasses = numBoxElements - 4

                    for (i in 0 until numBoxes) {
                        var maxScore = 0f
                        var classId = -1
                        for (c in 0 until numClasses) {
                            val score = outputArray[0][4 + c][i]
                            if (score > maxScore) {
                                maxScore = score
                                classId = c
                            }
                        }

                        if (maxScore > threshold) {
                            val rawCx = outputArray[0][0][i]
                            val rawCy = outputArray[0][1][i]
                            val rawW = outputArray[0][2][i]
                            val rawH = outputArray[0][3][i]

                            val cxInRoi = if (rawCx > 2f) rawCx / expectedWidth else rawCx
                            val cyInRoi = if (rawCy > 2f) rawCy / expectedHeight else rawCy
                            val wInRoi = if (rawW > 2f) rawW / expectedWidth else rawW
                            val hInRoi = if (rawH > 2f) rawH / expectedHeight else rawH

                            val cxInImg = roiX + (cxInRoi * roiSize)
                            val cyInImg = roiY + (cyInRoi * roiSize)
                            val wInImg = wInRoi * roiSize
                            val hInImg = hInRoi * roiSize

                            val finalCx = cxInImg / bitmap.width
                            val finalCy = cyInImg / bitmap.height
                            val finalW = wInImg / bitmap.width
                            val finalH = hInImg / bitmap.height

                            val left = finalCx - (finalW / 2)
                            val top = finalCy - (finalH / 2)
                            val className = "專家${index + 1}-類別$classId"

                            // ==========================================
                            // 🌟 路線 B：攔截綠色人行道，啟動 OpenCV 方向計算
                            // ==========================================
                            // ⚠️ 假設模型 2 (index 1) 是路面模型，且 classId 0 是綠色人行道
                            // 如果你們的設定不同，請在這裡修改 classId 的數字！
                            if (index == 1 && classId == 0) {
                                try {
                                    // 1. 將比例還原成絕對像素，並加入安全邊界(coerceIn)防止裁切超出圖片範圍
                                    val cropX = (left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                                    val cropY = (top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                                    val cropW = (finalW * bitmap.width).toInt().coerceAtMost(bitmap.width - cropX)
                                    val cropH = (finalH * bitmap.height).toInt().coerceAtMost(bitmap.height - cropY)

                                    if (cropW > 0 && cropH > 0) {
                                        // 2. 從原始的高畫質照片中，剪下綠色人行道區塊
                                        val pathCrop = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)

                                        // 3. 送交 OpenCV 分析偏移量
                                        val directionWarning = analyzeGreenPathDeviation(pathCrop)
                                        Log.d("AI_TEST", "📍 人行道導航: $directionWarning")

                                        // ⚡ 用完立刻釋放，防止記憶體洩漏
                                        pathCrop.recycle()
                                    }
                                } catch (e: Exception) {
                                    Log.e("AI_TEST", "裁切綠色人行道失敗: ${e.message}")
                                }
                            }
                            // ==========================================

                            allResults.add(DetectionResult(left, top, finalW, finalH, className, maxScore))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AI_TEST", "模型運算失敗: ${e.message}")
                } finally {
                    resizedBitmap?.recycle()
                }
            }

            croppedBitmap.recycle()

            // 🌟 完整還原 NMS (非極大值抑制) 過濾邏輯
            val nmsResults = mutableListOf<DetectionResult>()
            val sortedResults = allResults.sortedByDescending { it.confidence }

            for (result in sortedResults) {
                var isDuplicate = false
                for (keptResult in nmsResults) {
                    val overlapX = maxOf(0f, minOf(result.x + result.w, keptResult.x + keptResult.w) - maxOf(result.x, keptResult.x))
                    val overlapY = maxOf(0f, minOf(result.y + result.h, keptResult.y + keptResult.h) - maxOf(result.y, keptResult.y))
                    val intersection = overlapX * overlapY

                    val area1 = result.w * result.h
                    val area2 = keptResult.w * keptResult.h
                    val union = area1 + area2 - intersection
                    val iou = if (union > 0) intersection / union else 0f

                    if (iou > 0.4f) {
                        isDuplicate = true
                        break
                    }
                }
                if (!isDuplicate) {
                    nmsResults.add(result)
                }
            }

            onResult(nmsResults)

        } catch (e: Exception) {
            Log.e("AI_TEST", "推論總崩潰: ${e.message}")
        }
    }

    // 🌟 專屬 OpenCV 演算法：計算綠色人行道重心偏移
    private fun analyzeGreenPathDeviation(cropBitmap: Bitmap): String {
        val mat = Mat()
        Utils.bitmapToMat(cropBitmap, mat)

        val hsvMat = Mat()
        Imgproc.cvtColor(mat, hsvMat, Imgproc.COLOR_RGBA2BGR)
        Imgproc.cvtColor(hsvMat, hsvMat, Imgproc.COLOR_BGR2HSV)

        // 💡 如果抓不到綠色，可以放寬這個區間 (例如 lower 變成 30)
        // 把下限的 35 調低到 25 (容忍偏黃的綠色)，50 調低到 30 (容忍比較暗的綠色)
        val lowerGreen = Scalar(25.0, 30.0, 30.0)
        val upperGreen = Scalar(85.0, 255.0, 255.0)

        val mask = Mat()
        Core.inRange(hsvMat, lowerGreen, upperGreen, mask)

        val moments = Imgproc.moments(mask)

        if (moments.m00 < 1000.0) {
            mat.release(); hsvMat.release(); mask.release()
            return "⚠️ 找不到清晰的綠色人行道"
        }

        val cx = (moments.m10 / moments.m00).toInt()
        val centerX = mask.cols() / 2
        val offsetPercentage = (cx - centerX).toFloat() / mask.cols()

        mat.release(); hsvMat.release(); mask.release()

        return when {
            offsetPercentage > 0.15f -> "⚠️ 偏右了！觸發左震動"
            offsetPercentage < -0.15f -> "⚠️ 偏左了！觸發右震動"
            else -> "✅ 走在正中間"
        }
    }

    fun close() {
        interpreters.forEach { it.close() }
    }
}