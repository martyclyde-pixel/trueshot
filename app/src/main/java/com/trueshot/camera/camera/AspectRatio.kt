package com.trueshot.camera.camera

/**
 * A social-media-friendly output shape for the saved JPEG, expressed as
 * width:height in portrait terms (e.g. 0.75 for 3:4).
 *
 * [FULL] has no fixed number — it's resolved at runtime from the device's own
 * screen dimensions, so the preview fills the screen the same way stock
 * camera apps do. That's still a crop of the sensor's 4:3 frame (phones don't
 * have extra vertical field of view to spend), it just makes the shape match
 * the display instead of leaving letterbox bars.
 *
 * None of this touches RAW capture: the DNG always keeps the full, uncropped
 * sensor frame regardless of which ratio is selected here — see JpegCropper.
 */
enum class AspectRatio(val label: String) {
    FULL("Full"),
    R3_4("3:4"),
    R1_1("1:1"),
    R4_5("4:5");

    /** @param screenRatio the device's own width/height in portrait, used only by [FULL]. */
    fun ratio(screenRatio: Float): Float = when (this) {
        FULL -> screenRatio
        R3_4 -> 3f / 4f
        R1_1 -> 1f
        R4_5 -> 4f / 5f
    }
}
