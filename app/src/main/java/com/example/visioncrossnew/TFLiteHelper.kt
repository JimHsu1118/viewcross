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

class TFLiteHelper(
    private val context: Context,
    private val onResult: (List<DetectionResult>) -> Unit,
    private val onNavigationResult: (String) -> Unit
) {

    private val interpreters = mutableListOf<Interpreter>()
    private val modelFiles = listOf(
        "traffic_1280_dynamic_range_quant.tflite",
        "yolov8n_dynamic_range_quant.tflite"
    )

    init {
        for (fileName in modelFiles) {
            try {
                val options = Interpreter.Options()
                options.numThreads = 4
                val interpreter = Interpreter(loadModelFile(fileName), options)
                interpreters.add(interpreter)
                Log.d("AI_TEST", "✅ 載入 $fileName")
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

    // ==========================================================
    // 📖 🌟 新增：AI 代號翻譯字典
    // ==========================================================
    private fun getReadableClassName(modelIndex: Int, classId: Int): String {
        return when (modelIndex) {
            // 🚥 模型 1：traffic_1280 (交通號誌)
            0 -> {
                when (classId) {
                    0 -> "🚦 紅綠燈"
                    // 💡 如果你們的模型有細分紅綠燈顏色，可以在這裡擴充，例如：
                    // 1 -> "🔴 紅燈"
                    // 2 -> "🟢 綠燈"
                    else -> "🚦 號誌"
                }
            }
            // 🚶‍♂️ 模型 2：yolov8n (日常障礙物 - 擷取最常見的危險)
            1 -> {
                when (classId) {
                    0 -> "🚶 行人"
                    1 -> "🚲 腳踏車"
                    2 -> "🚗 汽車"
                    3 -> "🏍️ 機車"
                    5 -> "🚌 公車"
                    7 -> "🚚 卡車"
                    9 -> "🚦 紅綠燈" // COCO 內建的紅綠燈
                    15 -> "🐈 貓咪"
                    16 -> "🐕 狗狗"
                    else -> "⚠️ 障礙物" // 其餘不重要的物品統稱障礙物
                }
            }
            else -> "未知"
        }
    }
    // ==========================================================

    fun detect(bitmap: Bitmap) {
        if (interpreters.isEmpty()) return

        try {
            val allResults = mutableListOf<DetectionResult>()
            val threshold = 0.3f

            val minSize = minOf(bitmap.width, bitmap.height)
            val roiScale = 0.6f
            val roiSize = (minSize * roiScale).toInt()
            val roiX = (bitmap.width - roiSize) / 2
            val roiY = (bitmap.height - roiSize) / 2

            val croppedBitmap = Bitmap.createBitmap(bitmap, roiX, roiY, roiSize, roiSize)

            // 🟢 軌道一：獨立的 OpenCV 綠色人行道辨識
            try {
                val directionWarning = detectGreenPathByColor(croppedBitmap)
                onNavigationResult(directionWarning)
            } catch (e: Exception) {
                Log.e("AI_TEST", "OpenCV 辨識失敗: ${e.message}")
            }

            // 🔴 軌道二：YOLO 尋找紅綠燈與障礙物
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
                            byteBuffer.putFloat(((valPixel shr 16) and 0xFF) / 255.0f)
                            byteBuffer.putFloat(((valPixel shr 8) and 0xFF) / 255.0f)
                            byteBuffer.putFloat((valPixel and 0xFF) / 255.0f)
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

                            // ==========================================
                            // 🌟 核心修改：呼叫翻譯字典，取得真實名稱！
                            // ==========================================
                            val className = getReadableClassName(index, classId)

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

    private fun detectGreenPathByColor(sourceBitmap: Bitmap): String {
        val mat = Mat()
        Utils.bitmapToMat(sourceBitmap, mat)

        val hsvMat = Mat()
        Imgproc.cvtColor(mat, hsvMat, Imgproc.COLOR_RGBA2BGR)
        Imgproc.cvtColor(hsvMat, hsvMat, Imgproc.COLOR_BGR2HSV)

        val lowerGreen = Scalar(30.0, 20.0, 20.0)
        val upperGreen = Scalar(95.0, 255.0, 255.0)

        val mask = Mat()
        Core.inRange(hsvMat, lowerGreen, upperGreen, mask)
        Imgproc.medianBlur(mask, mask, 7)

        val contours = ArrayList<org.opencv.core.MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        if (contours.isEmpty()) {
            mat.release(); hsvMat.release(); mask.release(); hierarchy.release()
            return "⚠️ 完全找不到綠色"
        }

        var maxArea = 0.0
        var maxContour: org.opencv.core.MatOfPoint? = null
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > maxArea) {
                maxArea = area
                maxContour = contour
            }
        }

        val w = mask.cols()
        val h = mask.rows()
        val percentage = (maxArea / (w * h)) * 100

        if (maxArea <= 3000 || percentage < 30.0) {
            contours.forEach { it.release() }
            mat.release(); hsvMat.release(); mask.release(); hierarchy.release()
            return "⚠️ 偏離人行道"
        }

        val moments = Imgproc.moments(maxContour)
        val cx = (moments.m10 / moments.m00).toInt()

        contours.forEach { it.release() }
        mat.release(); hsvMat.release(); mask.release(); hierarchy.release()

        val centerThreshold = w * 0.1

        return when {
            cx < (w / 2) - centerThreshold -> "⚠️ 請向左回正"
            cx > (w / 2) + centerThreshold -> "⚠️ 請向右回正"
            else -> "✅ 導航正常"
        }
    }

    fun close() {
        interpreters.forEach { it.close() }
    }
}