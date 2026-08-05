package com.eggprice.calc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min

/** 간단한 시세 추세 스파크라인 */
class TrendSparkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent_soft)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent_press)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1f
        color = ContextCompat.getColor(context, R.color.separator)
    }

    private var values: List<Double> = emptyList()

    fun setSeries(prices: List<Double>) {
        values = prices.filter { it > 0 }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val pad = resources.displayMetrics.density * 8f
        canvas.drawLine(pad, h - pad, w - pad, h - pad, gridPaint)
        if (values.size < 2) {
            val cx = w / 2f
            val cy = h / 2f
            canvas.drawCircle(cx, cy, pad / 2f, dotPaint)
            return
        }
        val minV = values.minOrNull() ?: return
        val maxV = values.maxOrNull() ?: return
        val span = max(maxV - minV, 1.0)
        val left = pad
        val right = w - pad
        val top = pad
        val bottom = h - pad
        val path = Path()
        val fill = Path()
        values.forEachIndexed { i, v ->
            val x = left + (right - left) * i / (values.size - 1).toFloat()
            val y = bottom - ((v - minV) / span).toFloat() * (bottom - top)
            if (i == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, bottom)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
            if (i == values.lastIndex) {
                fill.lineTo(x, bottom)
                fill.close()
            }
        }
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, linePaint)
        val lastX = left + (right - left)
        val lastY =
            bottom - ((values.last() - minV) / span).toFloat() * (bottom - top)
        canvas.drawCircle(lastX, lastY, resources.displayMetrics.density * 4f, dotPaint)
    }
}
