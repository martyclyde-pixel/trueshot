package com.trueshot.camera.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Size
import kotlin.math.abs

/**
 * Picks which physical camera to open and which output sizes to request.
 *
 * On a Pixel, [CameraManager.getCameraIdList] exposes one logical camera per
 * facing. The logical rear camera is the one that supports RAW and full manual
 * control; the ultrawide and telephoto are usually reachable only as physical
 * sub-cameras and frequently do NOT support RAW. We therefore stick to the
 * logical cameras, which is also what the stock app shows by default.
 */
object CameraChooser {

    data class Selection(
        val cameraId: String,
        val characteristics: CameraCharacteristics,
        val support: CaptureTuning.Support,
        val jpegSize: Size,
        val rawSize: Size?,
        val previewSize: Size,
        val sensorOrientation: Int,
        val isFrontFacing: Boolean,
        /** Null (or a degenerate range) means this camera has nothing to zoom to — hide the control. */
        val zoomRange: Range<Float>?
    )

    /** Preview is capped at 1080p; anything larger wastes bandwidth. */
    private const val MAX_PREVIEW_WIDTH = 1920
    private const val MAX_PREVIEW_HEIGHT = 1080

    /**
     * The front and rear cameras' largest-by-area JPEG size can be a different
     * shape (e.g. the selfie camera's biggest output is 16:9 while the main
     * sensor's is 4:3). Since the preview is sized from the JPEG's aspect
     * ratio, that mismatch made the whole letterboxed frame visibly jump in
     * size on every flip. Preferring 4:3 (or 3:4) when a camera offers it
     * keeps both cameras rendering at the same shape — still a native,
     * uncropped sensor output, just a consistently-chosen one.
     */
    private const val PREFERRED_ASPECT = 4f / 3f
    private const val ASPECT_TOLERANCE = 0.02f

    fun findCameraId(manager: CameraManager, front: Boolean): String? {
        val wanted = if (front) {
            CameraMetadata.LENS_FACING_FRONT
        } else {
            CameraMetadata.LENS_FACING_BACK
        }

        var fallback: String? = null
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) != wanted) continue

            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: IntArray(0)

            // Prefer a camera that can give us true sensor RAW.
            if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }

    fun select(manager: CameraManager, cameraId: String): Selection {
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("Camera $cameraId has no stream configuration map")

        val support = CaptureTuning.Support(chars)

        val jpegSize = bestJpegSize(map)
            ?: throw IllegalStateException("Camera $cameraId cannot produce JPEG")

        val rawSize = if (support.supportsRaw) {
            largest(map, ImageFormat.RAW_SENSOR)
        } else {
            null
        }

        val sensorOrientation =
            chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        // Match the preview's aspect ratio to the still's, so the framing in
        // the viewfinder is exactly the framing that gets written.
        val targetRatio = jpegSize.width.toFloat() / jpegSize.height.toFloat()
        val previewSize = choosePreviewSize(map, targetRatio)

        // Pixel's ultrawide/telephoto are physical sub-cameras of this one
        // logical id, not separate entries in getCameraIdList() — so zoom is
        // driven by CONTROL_ZOOM_RATIO on this camera, not by opening a
        // different id. Below 1x and past the telephoto's crossover point the
        // HAL genuinely switches to a different physical lens; in between it
        // crops/fuses, same as the stock app's own zoom ruler.
        val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)

        return Selection(
            cameraId = cameraId,
            characteristics = chars,
            support = support,
            jpegSize = jpegSize,
            rawSize = rawSize,
            previewSize = previewSize,
            sensorOrientation = sensorOrientation,
            isFrontFacing =
                chars.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT,
            zoomRange = zoomRange
        )
    }

    private fun largest(map: StreamConfigurationMap, format: Int): Size? =
        map.getOutputSizes(format)?.maxByOrNull { it.width.toLong() * it.height.toLong() }

    /** The largest JPEG size at [PREFERRED_ASPECT] (either orientation), falling back to the largest overall. */
    private fun bestJpegSize(map: StreamConfigurationMap): Size? {
        val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return null
        val preferred = sizes.filter {
            val ratio = it.width.toFloat() / it.height.toFloat()
            abs(ratio - PREFERRED_ASPECT) < ASPECT_TOLERANCE ||
                abs(ratio - 1f / PREFERRED_ASPECT) < ASPECT_TOLERANCE
        }
        val pool = preferred.ifEmpty { sizes.toList() }
        return pool.maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    /**
     * Largest SurfaceHolder-compatible size that matches the capture aspect
     * ratio and fits inside the 1080p cap.
     */
    private fun choosePreviewSize(map: StreamConfigurationMap, targetRatio: Float): Size {
        val candidates = map.getOutputSizes(android.view.SurfaceHolder::class.java)
            ?: return Size(1920, 1080)

        val inBudget = candidates.filter {
            it.width <= MAX_PREVIEW_WIDTH && it.height <= MAX_PREVIEW_HEIGHT
        }.ifEmpty { candidates.toList() }

        // Exact aspect match first, then the largest of those.
        val ratioMatched = inBudget.filter {
            abs(it.width.toFloat() / it.height.toFloat() - targetRatio) < 0.02f
        }

        val pool = ratioMatched.ifEmpty { inBudget }
        return pool.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: Size(1920, 1080)
    }
}
