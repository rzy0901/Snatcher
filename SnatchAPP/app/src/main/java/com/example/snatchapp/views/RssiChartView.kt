package com.example.snatchapp.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
//import com.example.snatchapp.viewmodel.RssiDataPoint
import com.example.snatchapp.model.RssiDataPoint
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class RssiChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var rssiData: List<RssiDataPoint> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val margin = 80f
    private val drawableWidth get() = width - 2 * margin
    private val drawableHeight get() = height - 2 * margin

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        // Line paint
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 3f
        linePaint.color = Color.BLUE

        // Point paint
        pointPaint.style = Paint.Style.FILL
        pointPaint.color = Color.RED

        // Axis paint
        axisPaint.style = Paint.Style.STROKE
        axisPaint.strokeWidth = 2f
        axisPaint.color = Color.BLACK

        // Grid paint
        gridPaint.style = Paint.Style.STROKE
        gridPaint.strokeWidth = 1f
        gridPaint.color = Color.LTGRAY

        // Text paint
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 16f
        textPaint.color = Color.BLACK
    }

    fun updateRssiData(newData: List<RssiDataPoint>) {
        rssiData = newData
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas ?: return

        if (rssiData.isEmpty()) {
            drawEmptyState(canvas)
            return
        }

        // Calculate data range
        val (minTime, maxTime, minRssi, maxRssi) = calculateDataRange()

        // Draw grid and axes
        drawGrid(canvas, minTime, maxTime, minRssi, maxRssi)
        drawAxes(canvas, minTime, maxTime, minRssi, maxRssi)

        // Draw RSSI line
        drawRssiLine(canvas, minTime, maxTime, minRssi, maxRssi)

        // Draw labels
        drawLabels(canvas, minTime, maxTime, minRssi, maxRssi)
    }

    private fun calculateDataRange(): Array<Long> {
        if (rssiData.isEmpty()) return arrayOf(0L, 1L, -100L, 0L)

        val minTime = rssiData.minOfOrNull { it.timestamp } ?: 0L
        val maxTime = rssiData.maxOfOrNull { it.timestamp } ?: 1L
        // Fix the Y axis range to -20 to -100 dBm
        val minRssi = -100L
        val maxRssi = -20L

        return arrayOf(minTime, maxTime, minRssi, maxRssi)
    }

    private fun drawGrid(canvas: Canvas, minTime: Long, maxTime: Long, minRssi: Long, maxRssi: Long) {
        val timeRange = maxTime - minTime
        val rssiRange = maxRssi - minRssi

        if (timeRange <= 0 || rssiRange <= 0) return

        // Vertical grid lines (time)
        val timeInterval = max(timeRange / 5, 1000L) // At least a 1-second interval
        var time = minTime
        while (time <= maxTime) {
            val x = margin + ((time - minTime).toFloat() / timeRange.toFloat()) * drawableWidth
            canvas.drawLine(x, margin, x, height - margin, gridPaint)
            time += timeInterval
        }

        // Horizontal grid lines (RSSI)
        val rssiInterval = max(rssiRange / 5, 5L) // At least a 5 dBm interval
        var rssi = minRssi
        while (rssi <= maxRssi) {
            val y = height - margin - ((rssi - minRssi).toFloat() / rssiRange.toFloat()) * drawableHeight
            canvas.drawLine(margin, y, width - margin, y, gridPaint)
            rssi += rssiInterval
        }
    }

    private fun drawAxes(canvas: Canvas, minTime: Long, maxTime: Long, minRssi: Long, maxRssi: Long) {
        // X axis
        canvas.drawLine(margin, height - margin, width - margin, height - margin, axisPaint)

        // Y axis
        canvas.drawLine(margin, margin, margin, height - margin, axisPaint)
    }

    private fun drawRssiLine(canvas: Canvas, minTime: Long, maxTime: Long, minRssi: Long, maxRssi: Long) {
        if (rssiData.size < 2) {
            // If there is only one point, draw just that point
            if (rssiData.size == 1) {
                val point = rssiData[0]
                val x = margin + drawableWidth / 2f
                val y = height - margin - ((point.rssi - minRssi).toFloat() / (maxRssi - minRssi).toFloat()) * drawableHeight
                canvas.drawCircle(x, y, 6f, pointPaint)
            }
            return
        }

        val timeRange = maxTime - minTime
        val rssiRange = maxRssi - minRssi

        if (timeRange <= 0 || rssiRange <= 0) return

        val path = Path()
        rssiData.forEachIndexed { index, point ->
            val x = margin + ((point.timestamp - minTime).toFloat() / timeRange.toFloat()) * drawableWidth
            val y = height - margin - ((point.rssi - minRssi).toFloat() / rssiRange.toFloat()) * drawableHeight

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        // Draw the line
        canvas.drawPath(path, linePaint)

        // Draw all data points
        rssiData.forEach { point ->
            val x = margin + ((point.timestamp - minTime).toFloat() / timeRange.toFloat()) * drawableWidth
            val y = height - margin - ((point.rssi - minRssi).toFloat() / rssiRange.toFloat()) * drawableHeight

            // Draw data point
            canvas.drawCircle(x, y, 4f, pointPaint)
        }
    }

    private fun drawLabels(canvas: Canvas, minTime: Long, maxTime: Long, minRssi: Long, maxRssi: Long) {
        textPaint.textAlign = Paint.Align.CENTER

        // X axis labels (time)
        textPaint.textSize = 12f
        val timeRange = maxTime - minTime
        if (timeRange > 0) {
            for (i in 0..4) {
                val time = minTime + (timeRange * i / 4)
                val x = margin + (drawableWidth * i / 4)
                val timeStr = timeFormat.format(Date(time))
                canvas.drawText(timeStr, x, height - margin + 20f, textPaint)
            }
        }

        // Y axis labels (RSSI)
        textPaint.textAlign = Paint.Align.RIGHT
        val rssiRange = maxRssi - minRssi
        if (rssiRange > 0) {
            for (i in 0..4) {
                val rssi = minRssi + (rssiRange * i / 4)
                val y = height - margin - (drawableHeight * i / 4) + 5f
                canvas.drawText("${rssi}dBm", margin - 10f, y, textPaint)
            }
        }

        // Axis titles
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 16f
        canvas.drawText("Time", width / 2f, height - 10f, textPaint)

        canvas.save()
        canvas.rotate(-90f, 20f, height / 2f)
        canvas.drawText("RSSI (dBm)", 20f, height / 2f + 5f, textPaint)
        canvas.restore()
    }

    private fun drawEmptyState(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.GRAY
        textPaint.textSize = 18f
        canvas.drawText("No RSSI data available", width / 2f, height / 2f, textPaint)
        textPaint.color = Color.BLACK
        textPaint.textSize = 16f
    }

    fun clearRSSIData() {
        rssiData = emptyList()
        invalidate()
    }

}