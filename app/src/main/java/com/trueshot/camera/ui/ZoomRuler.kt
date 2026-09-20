package com.trueshot.camera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * A draggable zoom ruler modelled on the stock camera app's: a translucent
 * capsule with tick marks, a labelled reference stop at each round multiplier
 * in range, and a floating readout above the thumb while dragging.
 *
 * The underlying value is a continuous camera zoom ratio (CONTROL_ZOOM_RATIO),
 * not a fixed set of stops — below 1x and past the telephoto's crossover
 * point the HAL genuinely switches to a different physical lens, same as the
 * stock ruler this is modelled on; in between it is the same crop/fusion any
 * zoom ruler does. Positions are mapped on a log scale, since zoom is
 * multiplicative — linear spacing would crowd every interesting stop into a
 * sliver near the low end.
 *
 * Every drawing measurement is expressed in dp and converted once via [dp] —
 * mixing raw pixel literals with [onMeasure]'s density-scaled height was
 * exactly what clipped the floating bubble on high-density screens before.
 */
class ZoomRuler @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** The track sits this far above the view's bottom edge, leaving room below it for tick labels. */
    private val bottomAreaDp = 40f

    /** Reserved space above the track for the floating readout bubble while dragging. */
    private val bubbleAreaDp = 56f

    private var minRatio = 1f
    private var maxRatio = 1f

    var currentRatio: Float = 1f
        set(value) {
            field = value.coerceIn(minRatio, maxRatio)
            invalidate()
        }

    /** Fired continuously as the finger moves — cheap, since this never reopens the camera. */
    var onRatioChanged: ((Float) -> Unit)? = null

    private var dragging = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 0, 0, 0)
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD8C8")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD8C8")
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    init {
        minorTickPaint.strokeWidth = dp(1.2f)
        majorTickPaint.strokeWidth = dp(2f)
        labelPaint.textSize = dp(11f)
        bubbleTextPaint.textSize = dp(13f)
    }

    private val trackRect = RectF()

    /** Round multipliers worth labelling, if they fall inside the current range — kept sparse so labels don't crowd. */
    private val candidateStops = floatArrayOf(0.5f, 0.6f, 1f, 2f, 5f, 10f, 20f)

    /** @param min/max as reported by CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE. */
    fun setRange(min: Float, max: Float) {
        minRatio = min
        maxRatio = max.coerceAtLeast(min * 1.001f) // guard against a degenerate zero-width range
        currentRatio = currentRatio.coerceIn(minRatio, maxRatio)
        invalidate()
    }

    /**
     * Always ticks the range's true endpoints, not just round numbers — a
     * device's actual max zoom rarely lands on a candidate like 20x, and
     * stopping short of it left a lopsided stretch of bare track on one side
     * while the other sat flush against a candidate that happened to match.
     */
    private fun stopsInRange(): List<Float> {
        val rounded = candidateStops.filter { it > minRatio * 1.02f && it < maxRatio * 0.98f }
        return (listOf(minRatio) + rounded + listOf(maxRatio)).distinct().sorted()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = dp(bottomAreaDp + bubbleAreaDp).roundToInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    private fun trackCenterY() = height - dp(bottomAreaDp)

    /** Log-scale mapping: zoom is multiplicative, so equal ratios should occupy equal pixel spans. */
    private fun xFor(ratio: Float): Float {
        val sidePadding = dp(16f)
        val usable = width - sidePadding * 2
        val span = ln((maxRatio / minRatio).toDouble())
        if (usable <= 0f || span <= 0.0) return width / 2f
        val t = (ln((ratio / minRatio).toDouble()) / span).coerceIn(0.0, 1.0)
        return sidePadding + usable * t.toFloat()
    }

    private fun ratioForX(x: Float): Float {
        val sidePadding = dp(16f)
        val usable = width - sidePadding * 2
        val span = ln((maxRatio / minRatio).toDouble())
        if (usable <= 0f || span <= 0.0) return minRatio
        val t = ((x - sidePadding) / usable).toDouble().coerceIn(0.0, 1.0)
        return (minRatio * exp(t * span)).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = trackCenterY()

        trackRect.set(0f, cy - dp(10f), width.toFloat(), cy + dp(10f))
        canvas.drawRoundRect(trackRect, trackRect.height() / 2f, trackRect.height() / 2f, trackPaint)

        val stops = stopsInRange()
        for (i in stops.indices) {
            val x = xFor(stops[i])
            canvas.drawLine(x, cy - dp(7f), x, cy + dp(7f), majorTickPaint)
            canvas.drawText(zoomText(stops[i]), x, cy + dp(23f), labelPaint)

            if (i < stops.lastIndex) {
                val nextX = xFor(stops[i + 1])
                for (m in 1 until 4) {
                    val mx = x + (nextX - x) * m / 4
                    canvas.drawLine(mx, cy - dp(3.5f), mx, cy + dp(3.5f), minorTickPaint)
                }
            }
        }

        val thumbX = xFor(currentRatio)
        canvas.drawCircle(thumbX, cy, dp(8f), thumbPaint)

        if (dragging) {
            val text = zoomText(currentRatio)
            val bubbleCenterY = cy - dp(38f)
            val halfWidth = dp(14f) + bubbleTextPaint.measureText(text) / 2f
            val halfHeight = dp(16f)
            val clampedX = thumbX.coerceIn(halfWidth, width - halfWidth)
            canvas.drawRoundRect(
                clampedX - halfWidth, bubbleCenterY - halfHeight, clampedX + halfWidth, bubbleCenterY + halfHeight,
                dp(14f), dp(14f), bubblePaint
            )
            canvas.drawText(text, clampedX, bubbleCenterY + dp(5f), bubbleTextPaint)
        }
    }

    private fun zoomText(ratio: Float): String {
        val value = if (ratio == ratio.toInt().toFloat()) {
            ratio.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", ratio)
        }
        return "${value}×"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || maxRatio <= minRatio) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                currentRatio = ratioForX(event.x)
                onRatioChanged?.invoke(currentRatio)
            }
            MotionEvent.ACTION_MOVE -> {
                currentRatio = ratioForX(event.x)
                onRatioChanged?.invoke(currentRatio)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }
}
