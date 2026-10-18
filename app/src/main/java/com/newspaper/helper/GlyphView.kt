package com.newspaper.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

enum class Glyph { BACK, PASTE, PLAY, PAUSE, STOP, PREVIOUS, NEXT, QUEUE, RETRY, GEAR }
class GlyphView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var glyph = Glyph.BACK; set(value) { field = value; invalidate() }
    var ink = 0xff666666.toInt(); set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { isClickable = true; isFocusable = true; minimumWidth = (48 * resources.displayMetrics.density).toInt(); minimumHeight = minimumWidth }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.translate(width / 2f - 12 * resources.displayMetrics.density, height / 2f - 12 * resources.displayMetrics.density)
        canvas.scale(resources.displayMetrics.density, resources.displayMetrics.density)
        paint.color = ink; paint.strokeWidth = 1.8f; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        fun line(vararg points: Float) { val p = Path(); p.moveTo(points[0], points[1]); for (i in 2 until points.size step 2) p.lineTo(points[i], points[i + 1]); canvas.drawPath(p, paint) }
        when (glyph) {
            Glyph.BACK -> { line(18f,12f,5f,12f); line(11f,5f,4f,12f,11f,19f) }
            Glyph.PASTE -> { canvas.drawRoundRect(6f,5f,20f,22f,2f,2f,paint); canvas.drawRoundRect(9f,2f,17f,7f,1f,1f,paint); line(9f,12f,17f,12f); line(9f,16f,17f,16f) }
            Glyph.PLAY -> line(7f,4f,20f,12f,7f,20f,7f,4f)
            Glyph.PAUSE -> { line(8f,4f,8f,20f); line(16f,4f,16f,20f) }
            Glyph.STOP -> canvas.drawRect(6f,6f,18f,18f,paint)
            Glyph.PREVIOUS -> { line(5f,5f,5f,19f); line(19f,5f,8f,12f,19f,19f,19f,5f) }
            Glyph.NEXT -> { line(19f,5f,19f,19f); line(5f,5f,16f,12f,5f,19f,5f,5f) }
            Glyph.QUEUE -> { line(4f,6f,18f,6f); line(4f,11f,14f,11f); line(4f,16f,11f,16f); line(18f,13f,18f,21f); line(14f,17f,22f,17f) }
            Glyph.RETRY -> { canvas.drawArc(4f,4f,20f,20f,45f,285f,false,paint); line(20f,4f,20f,10f,14f,10f) }
            Glyph.GEAR -> { canvas.drawCircle(12f,12f,4f,paint); canvas.drawCircle(12f,12f,8f,paint); for (i in 0..7) { canvas.save(); canvas.rotate(i*45f,12f,12f); line(12f,1f,12f,4f); canvas.restore() } }
        }
        canvas.restore()
    }
}
