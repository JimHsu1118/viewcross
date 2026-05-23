package com.example.visioncrossnew

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

// 這是用來畫紅色框框的透明玻璃
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var results: List<DetectionResult> = listOf()

    // 用來記錄從 MainActivity 傳過來的真實轉正影像大小
    private var cameraFrameWidth = 1
    private var cameraFrameHeight = 1

    // 🌟 補上漏掉的紅色目標框畫筆
    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    // 🌟 補上漏掉的黃色文字畫筆
    private val textPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
        textSize = 35f // 字體大小已調小，避免畫面太亂
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    // 🌟 補上漏掉的白色半透明「狙擊瞄準框」畫筆
    private val zonePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        alpha = 150
    }

    fun setCameraFrameSize(width: Int, height: Int) {
        this.cameraFrameWidth = width
        this.cameraFrameHeight = height
        postInvalidate() // 重新繪製
    }

    fun setResults(newResults: List<DetectionResult>) {
        this.results = newResults
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cameraFrameWidth == 1 || cameraFrameHeight == 1) return

        // 🌟 解決致命傷二：精準計算 FIT_CENTER 縮放比例與黑邊大小
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // 計算縮放係數 (看是寬度先撞牆還是高度先撞牆)
        val scale = minOf(viewWidth / cameraFrameWidth, viewHeight / cameraFrameHeight)

        // 影像在螢幕上實際佔用的範圍 (扣除黑邊後的真正畫面)
        val actualVideoWidth = cameraFrameWidth * scale
        val actualVideoHeight = cameraFrameHeight * scale

        // 計算上下或左右黑邊的寬度
        val offsetX = (viewWidth - actualVideoWidth) / 2f
        val offsetY = (viewHeight - actualVideoHeight) / 2f

        // 🌟 畫出與 TFLiteHelper 100% 同步的中央正方形狙擊框
        val roiScale = 0.6f // 必須跟 TFLiteHelper 裁切比例一致
        val minSize = minOf(actualVideoWidth, actualVideoHeight)
        val roiSize = minSize * roiScale

        val roiRectLeft = offsetX + (actualVideoWidth - roiSize) / 2f
        val roiRectTop = offsetY + (actualVideoHeight - roiSize) / 2f
        canvas.drawRect(roiRectLeft, roiRectTop, roiRectLeft + roiSize, roiRectTop + roiSize, zonePaint)

        // 🌟 繪製偵測紅框：將純比例還原到「實際影像範圍」上，並補上黑邊偏移量
        results.forEach { result ->
            val left = offsetX + (result.x * actualVideoWidth)
            val top = offsetY + (result.y * actualVideoHeight)
            val right = offsetX + ((result.x + result.w) * actualVideoWidth)
            val bottom = offsetY + ((result.y + result.h) * actualVideoHeight)

            // 這樣畫出來的框，無論直拿橫拿、螢幕比例如何，都會死死黏在物體上！
            canvas.drawRect(left, top, right, bottom, boxPaint)
            canvas.drawText("${result.className} ${(result.confidence * 100).toInt()}%", left, top - 10, textPaint)
        }
    }
}

// 裝載座標資料的容器
data class DetectionResult(val x: Float, val y: Float, val w: Float, val h: Float, val className: String, val confidence: Float)