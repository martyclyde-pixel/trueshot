package com.trueshot.camera.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Crops a captured JPEG to a target aspect ratio, for the social-media aspect
 * buttons. This is the one place TrueShot re-encodes a pixel after the
 * sensor — deliberately gated behind an explicit user choice (the aspect
 * selector in MainActivity) and never applied to the RAW/DNG, which always
 * keeps the full uncropped sensor frame regardless of this setting. Callers
 * should skip this entirely when the target ratio already matches the
 * camera's native output — see MainActivity's fast path.
 *
 * Uses BitmapRegionDecoder so only the kept region is ever decoded — no need
 * to allocate a full-resolution bitmap just to discard most of it.
 */
@Suppress("DEPRECATION") // The non-deprecated newInstance(InputStream) needs API 31; minSdk here is 29.
object JpegCropper {

    class Result(val bytes: ByteArray, val exifOrientation: Int)

    /** @param targetRatio width/height in EXIF-corrected display space. */
    fun crop(bytes: ByteArray, targetRatio: Float): Result {
        val orientation = ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val bufferWidth = bounds.outWidth
        val bufferHeight = bounds.outHeight
        if (bufferWidth <= 0 || bufferHeight <= 0) return Result(bytes, orientation)

        val rotated = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270
        val displayWidth = if (rotated) bufferHeight else bufferWidth
        val displayHeight = if (rotated) bufferWidth else bufferHeight

        val displayRect = centeredCrop(displayWidth, displayHeight, targetRatio)
        val bufferRect = toBufferSpace(displayRect, orientation, bufferWidth, bufferHeight)

        val decoder = BitmapRegionDecoder.newInstance(ByteArrayInputStream(bytes), false)
            ?: return Result(bytes, orientation)
        val cropped = try {
            decoder.decodeRegion(bufferRect, BitmapFactory.Options())
        } finally {
            decoder.recycle()
        } ?: return Result(bytes, orientation)

        val out = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, 97, out)
        cropped.recycle()

        // The crop was taken directly from buffer space, so the result still
        // needs the same orientation tag as the original to display upright —
        // compress() doesn't carry EXIF over, so the caller must restamp it
        // onto the saved file afterward (see MainActivity.onJpegCaptured).
        return Result(out.toByteArray(), orientation)
    }

    private fun centeredCrop(width: Int, height: Int, targetRatio: Float): Rect {
        val currentRatio = width.toFloat() / height.toFloat()
        return if (targetRatio < currentRatio) {
            val keepWidth = (height * targetRatio).toInt()
            val left = (width - keepWidth) / 2
            Rect(left, 0, left + keepWidth, height)
        } else {
            val keepHeight = (width / targetRatio).toInt()
            val top = (height - keepHeight) / 2
            Rect(0, top, width, top + keepHeight)
        }
    }

    /** Maps a crop rect from EXIF-corrected display space back into the raw buffer's own space. */
    private fun toBufferSpace(rect: Rect, orientation: Int, bufferWidth: Int, bufferHeight: Int): Rect =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 ->
                Rect(rect.top, bufferHeight - rect.right, rect.bottom, bufferHeight - rect.left)
            ExifInterface.ORIENTATION_ROTATE_270 ->
                Rect(bufferWidth - rect.bottom, rect.left, bufferWidth - rect.top, rect.right)
            ExifInterface.ORIENTATION_ROTATE_180 ->
                Rect(
                    bufferWidth - rect.right, bufferHeight - rect.bottom,
                    bufferWidth - rect.left, bufferHeight - rect.top
                )
            else -> rect
        }
}
