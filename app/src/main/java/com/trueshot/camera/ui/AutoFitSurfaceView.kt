package com.trueshot.camera.ui

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import kotlin.math.roundToInt

/**
 * A SurfaceView that letterboxes itself to the camera's aspect ratio.
 *
 * Using a SurfaceView rather than a TextureView means the preview buffers go
 * straight to the display compositor with no intermediate GPU copy — one less
 * place for the image to be resampled before the user sees it. It also means
 * the sensor-to-display rotation is applied by the camera service via the
 * surface's transform hint, so no manual matrix is needed.
 *
 * The activity is locked to portrait, so this view is always measured portrait
 * while the camera reports landscape buffer sizes; the measure pass inverts
 * accordingly. It fits rather than fills, because cropping the preview would
 * contradict the point of the app: the framing you see is the framing you get.
 */
class AutoFitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle) {

    private var aspectWidth = 0
    private var aspectHeight = 0

    /**
     * @param width buffer width as reported by the camera (sensor orientation)
     * @param height buffer height as reported by the camera
     */
    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Aspect ratio must be positive" }
        aspectWidth = width
        aspectHeight = height
        holder.setFixedSize(width, height)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        if (aspectWidth == 0 || aspectHeight == 0) {
            setMeasuredDimension(width, height)
            return
        }

        // The camera reports a landscape buffer (e.g. 1920x1080). After the
        // transform hint rotates it for a portrait display, the visible frame
        // is 1080x1920 — so the displayed height:width ratio is the buffer's
        // width:height, not the other way round.
        val ratio = aspectWidth.toFloat() / aspectHeight.toFloat()
        val heightFromWidth = (width * ratio).roundToInt()

        if (heightFromWidth <= height) {
            setMeasuredDimension(width, heightFromWidth)
        } else {
            setMeasuredDimension((height / ratio).roundToInt(), height)
        }
    }
}
