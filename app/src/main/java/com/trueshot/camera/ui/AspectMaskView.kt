package com.trueshot.camera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Dims the parts of the preview that a non-native [AspectRatio][com.trueshot.camera.camera.AspectRatio]
 * selection will crop away, so the undimmed center always shows exactly what
 * will be saved.
 *
 * Sized via layout to match [AutoFitSurfaceView]'s own resolved bounds
 * exactly (see activity_main.xml — all four edges constrained to the preview,
 * not computed independently), so this view's own width/height already *is*
 * the fitted, native-aspect preview rectangle; only the target ratio needs
 * comparing against it.
 */
class AspectMaskView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Width/height in portrait terms. <= 0 means "no crop" — nothing is drawn. */
    var targetRatio: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val dimPaint = Paint().apply { color = Color.argb(165, 0, 0, 0) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (targetRatio <= 0f || width <= 0 || height <= 0) return

        val viewRatio = width.toFloat() / height.toFloat()
        when {
            targetRatio < viewRatio - 0.001f -> {
                // Target is narrower than this native frame — dim the sides.
                val keepWidth = height * targetRatio
                val excess = (width - keepWidth) / 2f
                canvas.drawRect(0f, 0f, excess, height.toFloat(), dimPaint)
                canvas.drawRect(width - excess, 0f, width.toFloat(), height.toFloat(), dimPaint)
            }
            targetRatio > viewRatio + 0.001f -> {
                // Target is wider/shorter than this native frame — dim top and bottom.
                val keepHeight = width / targetRatio
                val excess = (height - keepHeight) / 2f
                canvas.drawRect(0f, 0f, width.toFloat(), excess, dimPaint)
                canvas.drawRect(0f, height - excess, width.toFloat(), height.toFloat(), dimPaint)
            }
            // else: target already matches the native frame — nothing to dim.
        }
    }
}
