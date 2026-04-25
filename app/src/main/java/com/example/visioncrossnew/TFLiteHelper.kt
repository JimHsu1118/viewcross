package com.example.visioncrossnew

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
        "yolov8n_dynamic_range_quant.tflite"
    )

    init {
        for (fileName in modelFiles) {
            try {
                // 🌟 破案關鍵：建立模型設定檔，解鎖 4 核心運算！
                val options = Interpreter.Options()
                options.numThreads = 4 // 讓 4 個 CPU 核心同時幫忙算

                // 🌟 把 options 傳進去給 Interpreter
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
            // 這裡收集所有專家看出來的「原始」答案
            val allResults = mutableListOf<DetectionResult>()

            // 🌟 目前設定為 70% 嚴格門檻
            val threshold = 0.3f

            for ((index, interpreter) in interpreters.withIndex()) {
                try {
                    val inputShape = interpreter.getInputTensor(0).shape()
                    val expectedHeight = inputShape[1]
                    val expectedWidth = inputShape[2]

                    val resizedBitmap = Bitmap.createScaledBitmap(bitmap, expectedWidth, expectedHeight, true)
                    val byteBuffer = ByteBuffer.allocateDirect(1 * expectedWidth * expectedHeight * 3 * 4)
                    byteBuffer.order(ByteOrder.nativeOrder())
                    val intValues = IntArray(expectedWidth * expectedHeight)
                    resizedBitmap.getPixels(intValues, 0, expectedWidth, 0, 0, expectedWidth, expectedHeight)

                    var pixel = 0
                    for (y in 0 until expectedHeight) {
                        for (x in 0 until expectedWidth) {
                            val valPixel = intValues[pixel++]
                            // 🌟 破案關鍵 1：恢復 / 255.0f，並改成 BGR 順序 (解救色盲)
                            byteBuffer.putFloat((valPixel and 0xFF) / 255.0f)          // Blue 先塞
                            byteBuffer.putFloat(((valPixel shr 8) and 0xFF) / 255.0f)  // Green
                            byteBuffer.putFloat(((valPixel shr 16) and 0xFF) / 255.0f) // Red 最後
                        }
                    }
                    val outputShape = interpreter.getOutputTensor(0).shape()
                    val numBoxElements = outputShape[1]
                    val numBoxes = outputShape[2]
                    val outputArray = Array(1) { Array(numBoxElements) { FloatArray(numBoxes) } }

                    interpreter.run(byteBuffer, outputArray)

                    val numClasses = numBoxElements - 4

                    var hasLoggedThisFrame = false

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

                            // 🌟 破案關鍵 2：無敵座標換算
                            // 智慧判斷：如果數字大於 2，代表它是絕對像素(如 320)；如果是小數，代表它已經是比例(0.5)
                            val cx = if (rawCx > 2f) rawCx / expectedWidth else rawCx
                            val cy = if (rawCy > 2f) rawCy / expectedHeight else rawCy
                            val w = if (rawW > 2f) rawW / expectedWidth else rawW
                            val h = if (rawH > 2f) rawH / expectedHeight else rawH

                            val left = cx - (w / 2)
                            val top = cy - (h / 2)

                            // 放大回你的 1280 玻璃畫布
                            val outLeft = left * 1280f
                            val outTop = top * 1280f
                            val outW = w * 1280f
                            val outH = h * 1280f

                            val className = "專家${index + 1}-類別$classId"
                            allResults.add(DetectionResult(outLeft, outTop, outW, outH, className, maxScore))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AI_TEST", "第 ${index + 1} 個模型運算失敗: ${e.message}")
                }
            } // 結束專家的 for 迴圈

            // ==========================================
            // 🌟 核心過濾器：NMS (非極大值抑制)
            // ==========================================
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

                    // 只要重疊超過 40%，就刪除分數較低的那一個
                    if (iou > 0.4f) {
                        isDuplicate = true
                        break
                    }
                }
                if (!isDuplicate) {
                    nmsResults.add(result)
                }
            }

            // 將過濾後「最精華」的結果傳出去畫
            onResult(nmsResults)

        } catch (e: Exception) {
            Log.e("AI_TEST", "推論總崩潰: ${e.message}")
        }
    }

    fun close() {
        interpreters.forEach { it.close() }
    }
}