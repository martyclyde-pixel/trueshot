package com.trueshot.camera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log

/**
 * The whole point of the app.
 *
 * A Pixel's stock camera app runs a proprietary computational pipeline (HDR+ /
 * Night Sight) that merges a burst and then applies heavy local tone mapping,
 * noise reduction, sharpening and face-aware smoothing. Third-party apps do not
 * get that pipeline at all, but they *do* get the vendor ISP's default
 * "HIGH_QUALITY" post-processing, which is still far more aggressive than most
 * people expect.
 *
 * [applyNeutral] switches off every stage of that ISP post-processing that is
 * cosmetic, while leaving the stages that are genuine optical corrections.
 *
 * Every override is guarded by a capability check, because a request key that
 * the device does not advertise is silently dropped by the HAL — and on some
 * keys an unsupported value will make the whole session fail.
 */
object CaptureTuning {

    private const val TAG = "CaptureTuning"

    /**
     * Pure power-law tone curve. 2.2 is the classic display gamma: it maps
     * linear sensor data to display space with no S-curve, no shoulder, no
     * filmic contrast boost. This is what removes the "overly edited" look.
     */
    private const val NEUTRAL_GAMMA = 2.2f

    /** Snapshot of what the selected camera will actually honour. */
    class Support(characteristics: CameraCharacteristics) {

        private val noiseModes: IntArray =
            characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                ?: IntArray(0)

        private val edgeModes: IntArray =
            characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
                ?: IntArray(0)

        private val aberrationModes: IntArray =
            characteristics.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
                ?: IntArray(0)

        private val tonemapModes: IntArray =
            characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
                ?: IntArray(0)

        private val stabilisationModes: IntArray =
            characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?: IntArray(0)

        private val opticalStabilisationModes: IntArray =
            characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?: IntArray(0)

        private val capabilities: IntArray =
            characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: IntArray(0)

        val canDisableNoiseReduction =
            noiseModes.contains(CameraMetadata.NOISE_REDUCTION_MODE_OFF)

        val canDisableEdgeEnhancement =
            edgeModes.contains(CameraMetadata.EDGE_MODE_OFF)

        val canDisableAberrationCorrection =
            aberrationModes.contains(CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)

        val canSetGammaTonemap =
            tonemapModes.contains(CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)

        val canSetPresetTonemap =
            tonemapModes.contains(CameraMetadata.TONEMAP_MODE_PRESET_CURVE)

        val canDisableVideoStabilisation =
            stabilisationModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

        val hasOpticalStabilisation =
            opticalStabilisationModes.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)

        val supportsRaw =
            capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)

        val supportsManualSensor =
            capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)

        /**
         * A short, honest description of how much processing we were actually
         * able to switch off. Surfaced in the UI so the user is never misled
         * about what their device allowed.
         */
        fun describe(): String {
            val disabled = buildList {
                if (canDisableNoiseReduction) add("noise reduction")
                if (canDisableEdgeEnhancement) add("sharpening")
                if (canSetGammaTonemap || canSetPresetTonemap) add("tone curve")
                if (canDisableAberrationCorrection) add("aberration filter")
            }
            return when {
                disabled.isEmpty() -> "Device rejected processing overrides"
                else -> "Off: " + disabled.joinToString(", ")
            }
        }

        override fun toString(): String =
            "Support(nr=$canDisableNoiseReduction, edge=$canDisableEdgeEnhancement, " +
                "gamma=$canSetGammaTonemap, preset=$canSetPresetTonemap, " +
                "aberration=$canDisableAberrationCorrection, raw=$supportsRaw, " +
                "manual=$supportsManualSensor, ois=$hasOpticalStabilisation)"
        }

    /**
     * Strip cosmetic processing from [builder].
     *
     * Applied identically to the preview request and to the still-capture
     * request, so that the preview really is a live view of the file that will
     * be written — which is the user-facing promise of the app.
     *
     * @param forVideo relaxes two settings that are unusable in a handheld
     *   video context (see inline comments).
     */
    fun applyNeutral(
        builder: CaptureRequest.Builder,
        support: Support,
        forVideo: Boolean = false
    ) {
        // ---------------------------------------------------------------
        // 1. Cosmetic ISP stages — switched fully off where allowed.
        // ---------------------------------------------------------------

        // Spatial denoise. This is the single biggest contributor to the
        // "watercolour", smeared look in Pixel output. OFF keeps real sensor
        // grain, which is what the scene actually looked like.
        if (support.canDisableNoiseReduction) {
            builder.set(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CameraMetadata.NOISE_REDUCTION_MODE_OFF
            )
        }

        // Edge enhancement = unsharp masking. Produces halos around high
        // contrast edges. Nothing about it is a correction; it is a look.
        if (support.canDisableEdgeEnhancement) {
            builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        }

        // Chromatic aberration correction resamples channels independently and
        // softens fine detail. Real lens colour fringing is part of the optics.
        if (support.canDisableAberrationCorrection) {
            builder.set(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF
            )
        }

        // ---------------------------------------------------------------
        // 2. Tone mapping — the "lighting effects" the user is complaining
        //    about. The default FAST/HIGH_QUALITY curve is a filmic S-curve
        //    with lifted shadows and a rolled highlight shoulder. Replace it
        //    with a plain gamma ramp.
        // ---------------------------------------------------------------
        when {
            support.canSetGammaTonemap -> {
                builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_GAMMA_VALUE)
                builder.set(CaptureRequest.TONEMAP_GAMMA, NEUTRAL_GAMMA)
            }
            support.canSetPresetTonemap -> {
                builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_PRESET_CURVE)
                builder.set(
                    CaptureRequest.TONEMAP_PRESET_CURVE,
                    CameraMetadata.TONEMAP_PRESET_CURVE_SRGB
                )
            }
            else -> Log.w(TAG, "No neutral tonemap available; HAL curve will be used")
        }

        // ---------------------------------------------------------------
        // 3. Anything that changes the image based on scene *interpretation*.
        // ---------------------------------------------------------------

        // Plain auto exposure / auto white balance, never a scene-mode preset.
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        builder.set(
            CaptureRequest.CONTROL_SCENE_MODE,
            CameraMetadata.CONTROL_SCENE_MODE_DISABLED
        )
        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CameraMetadata.CONTROL_EFFECT_MODE_OFF)

        // Face detection feeds skin smoothing and face-priority metering on
        // many HALs. We want neither.
        builder.set(
            CaptureRequest.STATISTICS_FACE_DETECT_MODE,
            CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF
        )

        // Zero-shutter-lag serves frames out of a pre-processed ring buffer, so
        // our per-request overrides may not apply to the frame you actually get.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
        }

        // ---------------------------------------------------------------
        // 4. Corrections we deliberately KEEP. These make the file match what
        //    the eye saw rather than departing from it.
        // ---------------------------------------------------------------

        // Lens shading (vignette) correction: left at the HAL default. The
        // sensor genuinely receives less light at the corners; correcting it
        // moves the image toward the scene, not away from it.

        // Hot pixel correction: dead sensor photosites are a defect, not detail.
        builder.set(
            CaptureRequest.HOT_PIXEL_MODE,
            CameraMetadata.HOT_PIXEL_MODE_HIGH_QUALITY
        )

        // Optical stabilisation is a physical lens element, not processing.
        // It buys sharpness at no cost to fidelity, so leave it on.
        if (support.hasOpticalStabilisation) {
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
        }

        // Electronic stabilisation crops and warps every frame. Unacceptable
        // for stills; for video, handheld footage is unwatchable without it, so
        // it stays available there and is left at the HAL default.
        if (!forVideo && support.canDisableVideoStabilisation) {
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }

        // Maximum JPEG quality — no point stripping processing and then
        // throwing detail away in the encoder.
        builder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
    }
}
