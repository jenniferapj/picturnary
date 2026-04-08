package com.picturnary.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Set to true on the drawer's screen; false on the guesser's (view-only)
    var isDrawingMode: Boolean = false

    // Called on every touch point so the ViewModel can forward strokes to the server
    var onStrokePoint: ((x: Float, y: Float, newPath: Boolean, color: Int) -> Unit)? = null

    var selectedColor: Int = Color.BLACK

    private data class Stroke(val path: Path, val paint: Paint)

    private val strokes = mutableListOf<Stroke>()
    private var currentPath = Path()
    private var currentPaint = buildPaint(Color.BLACK)

    private fun buildPaint(color: Int) = Paint().apply {
        this.color = color
        strokeWidth = 10f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    fun clear() {
        strokes.clear()
        currentPath = Path()
        invalidate()
    }

    // Called by the Activity when it receives a remote draw_stroke event
    fun addRemoteStroke(x: Float, y: Float, newPath: Boolean, color: Int) {
        if (width == 0 || height == 0) return
        val px = x * width
        val py = y * height
        if (newPath) {
            currentPath = Path()
            currentPaint = buildPaint(color)
            strokes.add(Stroke(currentPath, currentPaint))
            currentPath.moveTo(px, py)
        } else {
            currentPath.lineTo(px, py)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        for (stroke in strokes) {
            canvas.drawPath(stroke.path, stroke.paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDrawingMode || width == 0 || height == 0) return false

        // Normalize to 0.0–1.0 so coordinates scale across different screen sizes
        val nx = event.x / width
        val ny = event.y / height

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = Path()
                currentPaint = buildPaint(selectedColor)
                strokes.add(Stroke(currentPath, currentPaint))
                currentPath.moveTo(event.x, event.y)
                onStrokePoint?.invoke(nx, ny, true, selectedColor)
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath.lineTo(event.x, event.y)
                onStrokePoint?.invoke(nx, ny, false, selectedColor)
            }
        }
        invalidate()
        return true
    }
}
