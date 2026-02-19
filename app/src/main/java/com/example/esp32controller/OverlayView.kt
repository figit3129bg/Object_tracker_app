package com.example.esp32controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.DashPathEffect
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val trackedBoxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val centerCrosshairPaint = Paint().apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
    }

    private val textBackgroundPaint = Paint().apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private var boxes: List<RectF> = emptyList()
    private var labels: List<String> = emptyList()
    private var classIndices: List<Int> = emptyList()
    private var fps: Int = 0
    private var trackedBox: RectF? = null

    fun setBoxes(
        boxes: List<RectF>,
        labels: List<String> = emptyList(),
        classIndices: List<Int> = emptyList(),
        fps: Int = 0,
        trackedBox: RectF? = null
    ) {
        this.boxes = boxes
        this.labels = labels
        this.classIndices = classIndices
        this.fps = fps
        this.trackedBox = trackedBox
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw center crosshair
        val centerX = width / 2f
        val centerY = height / 2f
        val crosshairSize = 30f

        canvas.drawLine(
            centerX - crosshairSize, centerY,
            centerX + crosshairSize, centerY,
            centerCrosshairPaint
        )
        canvas.drawLine(
            centerX, centerY - crosshairSize,
            centerX, centerY + crosshairSize,
            centerCrosshairPaint
        )

        // Draw all detected boxes
        boxes.forEachIndexed { i, box ->
            val isTracked = trackedBox != null && isSameBox(box, trackedBox!!)

            val classColor = when (classIndices.getOrNull(i)) {
                0 -> Color.GREEN                   // people
                2 -> Color.BLUE                    // cars
                3 -> Color.YELLOW                  // motorcycles
                5 -> Color.CYAN                    // bus
                                // trucks
                else -> Color.RED
            }

            if (isTracked) {
                // Draw tracked box with special highlighting
                trackedBoxPaint.color = Color.MAGENTA
                canvas.drawRect(box, trackedBoxPaint)

                // Draw corner markers for tracked box
                val cornerSize = 20f
                trackedBoxPaint.strokeWidth = 4f
                // Top-left
                canvas.drawLine(box.left, box.top, box.left + cornerSize, box.top, trackedBoxPaint)
                canvas.drawLine(box.left, box.top, box.left, box.top + cornerSize, trackedBoxPaint)
                // Top-right
                canvas.drawLine(box.right, box.top, box.right - cornerSize, box.top, trackedBoxPaint)
                canvas.drawLine(box.right, box.top, box.right, box.top + cornerSize, trackedBoxPaint)
                // Bottom-left
                canvas.drawLine(box.left, box.bottom, box.left + cornerSize, box.bottom, trackedBoxPaint)
                canvas.drawLine(box.left, box.bottom, box.left, box.bottom - cornerSize, trackedBoxPaint)
                // Bottom-right
                canvas.drawLine(box.right, box.bottom, box.right - cornerSize, box.bottom, trackedBoxPaint)
                canvas.drawLine(box.right, box.bottom, box.right, box.bottom - cornerSize, trackedBoxPaint)

                trackedBoxPaint.strokeWidth = 8f

                // Draw line from box center to screen center
                val boxCenterX = box.centerX()
                val boxCenterY = box.centerY()
                val linePaint = Paint().apply {
                    color = Color.argb(150, 0, 255, 0)
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
                }
                canvas.drawLine(boxCenterX, boxCenterY, centerX, centerY, linePaint)

            } else {
                // Draw regular detection box
                boxPaint.color = classColor
                canvas.drawRect(box, boxPaint)
            }

            // Draw label with background
            if (i < labels.size) {
                val label = labels[i]
                val textWidth = textPaint.measureText(label)
                val textHeight = textPaint.textSize
                val padding = 8f

                // Draw background rectangle
                canvas.drawRect(
                    box.left,
                    box.top - textHeight - padding * 2,
                    box.left + textWidth + padding * 2,
                    box.top,
                    textBackgroundPaint
                )

                // Draw text
                textPaint.color = if (isTracked) Color.GREEN else Color.WHITE
                canvas.drawText(label, box.left + padding, box.top - padding - 5f, textPaint)
            }
        }

        // Draw FPS in top-left corner
        val fpsText = "FPS: $fps"
        val fpsTextWidth = textPaint.measureText(fpsText)
        canvas.drawRect(5f, 5f, 15f + fpsTextWidth, 55f, textBackgroundPaint)
        textPaint.color = Color.WHITE
        canvas.drawText(fpsText, 10f, 45f, textPaint)
    }

    private fun isSameBox(box1: RectF, box2: RectF): Boolean {
        val threshold = 10f // Pixel threshold for considering boxes the same
        return kotlin.math.abs(box1.left - box2.left) < threshold &&
                kotlin.math.abs(box1.top - box2.top) < threshold &&
                kotlin.math.abs(box1.right - box2.right) < threshold &&
                kotlin.math.abs(box1.bottom - box2.bottom) < threshold
    }
}