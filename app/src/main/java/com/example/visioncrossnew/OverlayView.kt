package com.example.visioncrossnew

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

// 這是用來畫紅色框框的透明玻璃
class OverlayView(context: Context) : View(context) {
    private var results: List<DetectionResult> = emptyList()

    // 設定畫筆：紅色、空心、粗細 8
    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    // 設定文字：黃色、填滿、大小 60
    private val textPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
        textSize = 35f // 🌟 從 60f 改成 35f 左右
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }
    // 接收 AI 傳來的新座標，並要求重新畫圖
    fun updateResults(newResults: List<DetectionResult>) {
        results = newResults
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        results.forEach { result ->
            // 🌟 改這裡：直接用手機當下的真實寬度(width)與高度(height)來還原
            val left = result.x * width
            val top = result.y * height
            val right = (result.x + result.w) * width
            val bottom = (result.y + result.h) * height

            canvas.drawRect(left, top, right, bottom, boxPaint)
            canvas.drawText("${result.className} ${(result.confidence * 100).toInt()}%", left, top - 10, textPaint)
        }
    }
}

// 裝載座標資料的容器
data class DetectionResult(val x: Float, val y: Float, val w: Float, val h: Float, val className: String, val confidence: Float)