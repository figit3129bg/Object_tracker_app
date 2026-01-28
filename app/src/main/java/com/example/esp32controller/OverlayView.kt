package com.example.esp32controller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
    }

    private var boxes: List<RectF> = emptyList()
    private var labels: List<String> = emptyList()
    private var classIndices: List<Int> = emptyList()
    private var fps: Int = 0  // store FPS

    fun setBoxes(boxes: List<RectF>, labels: List<String> = emptyList(), classIndices: List<Int> = emptyList(), fps: Int = 0) {
        this.boxes = boxes
        this.labels = labels
        this.classIndices = classIndices
        this.fps = fps
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw boxes + labels
        boxes.forEachIndexed { i, box ->
            val classColor = when (classIndices.getOrNull(i)) {
                0 -> Color.GREEN                   // people
                2 -> Color.BLUE                    // cars
                3 -> Color.YELLOW                  // motorcycles
                5 -> Color.CYAN                    // bus
                7 -> Color.MAGENTA                 // trucks
                else -> Color.RED
            }
            boxPaint.color = classColor
            canvas.drawRect(box, boxPaint)

            if (i < labels.size) {
                canvas.drawText(labels[i], box.left, box.top - 10, textPaint)
            }
        }

        // Draw FPS in top-left corner
        canvas.drawText("FPS: $fps", 10f, 50f, textPaint)
    }
}
