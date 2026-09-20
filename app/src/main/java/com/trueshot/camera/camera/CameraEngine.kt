package com.trueshot.camera.camera

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import java.io.OutputStream
import java.util.concurrent.Executor

/**
 * Owns the Camera2 device, the capture session and the capture state machine.
 *
 * Two session shapes are used:
 *   PHOTO — preview (PRIVATE) + JPEG (MAXIMUM) + RAW_SENSOR (MAXIMUM).
 *           This exact three-stream combination is guaranteed by the Camera2
 *           spec on any device advertising the RAW capability, so it is safe
 *           on every Pixel's main sensor.
 *   VIDEO — preview (PRIVATE), plus a MediaRecorder surface while recording.
 *
 * THREADING: every public method hops onto the engine's own background
 * HandlerThread, and all internal state is touched only from there. Camera2
 * callbacks are delivered on the same thread, so no locking is needed. The
 * [Listener] is therefore also called on that thread; the UI layer must post
 * to the main thread itself.
 */
class CameraEngine(
    private val manager: CameraManager
) {

    companion object {
        private const val TAG = "CameraEngine"
        private const val AF_RESET_DELAY_MS = 4_000L

        /**
         * How long to wait for AE to settle after a precapture trigger before
         * giving up and shooting anyway. Some HALs answer an AUTO-flash trigger
         * without ever reporting the PRECAPTURE state, and without this the
         * shutter would hang forever.
         */
        private const val PRECAPTURE_TIMEOUT_MS = 1_500L

        /**
         * Three, not two: a RAW frame stays acquired until its ~25 MB DNG has
         * finished being written, which outlives the shutter being re-enabled.
         */
        private const val RAW_BUFFER_COUNT = 3

        /** How long a single still capture may stay in flight. */
        private const val CAPTURE_WATCHDOG_MS = 6_000L
    }

    enum class Flash { OFF, AUTO, ON }

    /**
     * A captured RAW frame plus the metadata describing how it was exposed.
     *
     * Holds a native image buffer, so the consumer MUST close it — `use {}` is
     * the intended idiom. Failing to close one permanently consumes a slot in
     * the RAW ImageReader, and exhausting the reader crashes the process.
     */
    class RawCapture internal constructor(
        private val characteristics: CameraCharacteristics,
        private val result: TotalCaptureResult,
        private val image: Image,
        private val exifOrientation: Int
    ) : Closeable {

        fun writeTo(out: OutputStream) {
            DngCreator(characteristics, result).use { creator ->
                creator.setOrientation(exifOrientation)
                creator.writeImage(out, image)
            }
        }

        override fun close() {
            runCatching { image.close() }
        }
    }

    interface Listener {
        fun onSessionReady(selection: CameraChooser.Selection)
        fun onJpegCaptured(bytes: ByteArray)
        /** Ownership of [raw] transfers to the callee, which must close it. */
        fun onRawCaptured(raw: RawCapture)
        fun onCaptureFinished()
        /** The device went away (unplugged, stolen by another app, HAL error). */
        fun onDeviceLost(message: String)
        fun onError(message: String, cause: Throwable?)
    }

    private enum class State { IDLE, PREVIEW, WAITING_PRECAPTURE, WAITING_CONVERGE, CAPTURING }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val executor = Executor { command -> post { command.run() } }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null

    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null

    /** Volatile because [orientationFor] reads it from the main thread. */
    @Volatile
    private var selection: CameraChooser.Selection? = null
    private var listener: Listener? = null

    private var state = State.IDLE
    private var flash = Flash.OFF
    private var pendingRotation = 0
    private var precaptureStartedAt = 0L

    /**
     * Applied to the preview's repeating request AND every still capture, so
     * the framing that gets written always matches what was on screen — the
     * same WYSIWYG guarantee the rest of the app carries for tuning. Reset to
     * 1x on every [open], so flipping cameras doesn't carry a stale zoom onto
     * a lens it was never set on.
     */
    private var zoomRatio = 1f

    // Still-capture bookkeeping. Camera-thread only.
    private var pendingResult: TotalCaptureResult? = null
    private var pendingRawImage: Image? = null
    private var awaitingJpeg = false
    private var awaitingRaw = false

    private val afResetRunnable = Runnable { resetFocusInternal() }

    /**
     * Last line of defence: if a buffer the capture is waiting on never
     * arrives, release the shutter anyway rather than leaving the app
     * permanently unable to take another photo.
     */
    private val captureWatchdog = Runnable {
        when (state) {
            State.CAPTURING -> {
                Log.w(TAG, "Capture watchdog fired — a buffer never arrived")
                awaitingJpeg = false
                awaitingRaw = false
                finishCapture()
            }
            // AE never reported back. Shoot anyway: a slightly mis-metered
            // photo beats a shutter button that no longer works.
            State.WAITING_PRECAPTURE, State.WAITING_CONVERGE -> {
                Log.w(TAG, "Precapture watchdog fired — capturing anyway")
                fireStillCapture()
            }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------
    // Thread plumbing
    // ------------------------------------------------------------------

    private fun post(block: () -> Unit) {
        val h = handler
        when {
            h == null -> Unit // engine stopped; drop the work
            Looper.myLooper() === h.looper -> block()
            else -> h.post { block() }
        }
    }

    fun start() {
        if (thread != null) return
        thread = HandlerThread("TrueShot-Camera").also {
            it.start()
            handler = Handler(it.looper)
        }
    }

    fun stop() {
        val h = handler ?: return
        val t = thread

        h.post {
            closeSessionInternal(h)
            device?.close()
            device = null
            state = State.IDLE
        }

        // Detach first so nothing new is queued, then quit (which still
        // dispatches the teardown above) and WAIT for it. Without the join, a
        // flip can start a second camera thread while this one is still inside
        // device.close(), and the old thread's `device = null` then wipes out
        // the newly opened device — leaking it and leaving a black preview.
        thread = null
        handler = null
        t?.quitSafely()
        runCatching { t?.join(1_500) }
    }

    private fun closeSessionInternal(h: Handler? = handler) {
        // Anything mid-flight is being abandoned; release the caller's shutter
        // rather than leaving it disabled forever. Covers the AE-wait states
        // too, which a mode switch or a flip can interrupt.
        if (state == State.CAPTURING ||
            state == State.WAITING_PRECAPTURE ||
            state == State.WAITING_CONVERGE
        ) {
            state = State.IDLE
            listener?.onCaptureFinished()
        }

        h?.removeCallbacks(afResetRunnable)
        h?.removeCallbacks(captureWatchdog)
        runCatching { session?.close() }
        session = null
        previewBuilder = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
        pendingRawImage?.close()
        pendingRawImage = null
        pendingResult = null
        awaitingJpeg = false
        awaitingRaw = false
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun open(selection: CameraChooser.Selection, listener: Listener, onOpened: () -> Unit) = post {
        this.selection = selection
        this.listener = listener
        // Some cameras' un-cropped, no-processing field of view sits below
        // 1x (the selfie camera's default resolution is often already a mild
        // crop of the sensor). Defaulting to 1x unconditionally would start
        // every such camera pre-zoomed-in; starting at the range's floor
        // (never above 1x) starts each lens at its own true widest view.
        zoomRatio = selection.zoomRange?.lower?.coerceAtMost(1f) ?: 1f

        closeSessionInternal()
        device?.close()
        device = null

        try {
            manager.openCamera(selection.cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    onOpened()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (device === camera) device = null
                    state = State.IDLE
                    listener.onDeviceLost("Camera was taken by another app")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (device === camera) device = null
                    state = State.IDLE
                    listener.onDeviceLost("Camera error $error")
                }
            })
        } catch (t: CameraAccessException) {
            listener.onError("Could not open camera", t)
        } catch (t: IllegalArgumentException) {
            listener.onError("Could not open camera", t)
        } catch (t: SecurityException) {
            listener.onError("Camera permission was revoked", t)
        }
    }

    // ------------------------------------------------------------------
    // Photo session
    // ------------------------------------------------------------------

    fun startPhotoSession(previewSurface: Surface) = post {
        val camera = device ?: return@post
        val sel = selection ?: return@post
        val cb = listener ?: return@post

        closeSessionInternal()

        val jpeg = ImageReader.newInstance(
            sel.jpegSize.width, sel.jpegSize.height, ImageFormat.JPEG, 2
        ).also { reader ->
            reader.setOnImageAvailableListener({ r -> drainJpeg(r) }, handler)
        }
        jpegReader = jpeg

        val raw = sel.rawSize?.let { size ->
            ImageReader.newInstance(
                size.width, size.height, ImageFormat.RAW_SENSOR, RAW_BUFFER_COUNT
            ).also { reader ->
                reader.setOnImageAvailableListener({ r -> drainRaw(r) }, handler)
            }
        }
        rawReader = raw

        val outputs = buildList {
            add(OutputConfiguration(previewSurface))
            add(OutputConfiguration(jpeg.surface))
            raw?.let { add(OutputConfiguration(it.surface)) }
        }

        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(previewSurface)
        builder.set(
            CaptureRequest.CONTROL_AF_MODE,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )
        CaptureTuning.applyNeutral(builder, sel.support)
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        previewBuilder = builder

        configureSession(camera, outputs, builder, sel, cb) { cb.onSessionReady(sel) }
    }

    // ------------------------------------------------------------------
    // Video session
    // ------------------------------------------------------------------

    /**
     * @param recorderSurface null while video mode is idle. Passing it only at
     *   the moment recording starts means the MediaRecorder is configured with
     *   the orientation the phone is *actually* being held at, rather than the
     *   one it had when the mode was entered.
     */
    fun startVideoSession(
        previewSurface: Surface,
        recorderSurface: Surface?,
        onReady: () -> Unit
    ) = post {
        val camera = device ?: return@post
        val sel = selection ?: return@post
        val cb = listener ?: return@post

        closeSessionInternal()

        val outputs = buildList {
            add(OutputConfiguration(previewSurface))
            recorderSurface?.let { add(OutputConfiguration(it)) }
        }

        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.addTarget(previewSurface)
        recorderSurface?.let { builder.addTarget(it) }
        builder.set(
            CaptureRequest.CONTROL_AF_MODE,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        )
        CaptureTuning.applyNeutral(builder, sel.support, forVideo = true)
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        previewBuilder = builder

        configureSession(camera, outputs, builder, sel, cb, onReady)
    }

    private fun configureSession(
        camera: CameraDevice,
        outputs: List<OutputConfiguration>,
        builder: CaptureRequest.Builder,
        sel: CameraChooser.Selection,
        cb: Listener,
        onReady: () -> Unit
    ) {
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    state = State.PREVIEW
                    applyFlashTo(builder)
                    try {
                        configured.setRepeatingRequest(builder.build(), previewCallback, handler)
                        onReady()
                    } catch (t: Throwable) {
                        cb.onError("Preview failed to start", t)
                    }
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    cb.onError("Camera could not be configured", null)
                }
            }
        )

        try {
            camera.createCaptureSession(config)
        } catch (t: Throwable) {
            cb.onError("Could not create capture session", t)
        }
    }

    // ------------------------------------------------------------------
    // Still capture
    // ------------------------------------------------------------------

    fun setFlash(mode: Flash) = post {
        flash = mode
        val builder = previewBuilder ?: return@post
        val active = session ?: return@post
        applyFlashTo(builder)
        runCatching { active.setRepeatingRequest(builder.build(), previewCallback, handler) }
    }

    /**
     * Live zoom, applied to the preview immediately. Below 1x and past the
     * telephoto's crossover point this is a genuine lens switch handled
     * inside the HAL; in between it is the same crop/fusion the stock app's
     * ruler does. Persists across a mode switch, resets to 1x on [open].
     *
     * Not applied to RAW capture: per the CONTROL_ZOOM_RATIO documentation,
     * RAW_SENSOR streams always cover the sensor's maximum field of view
     * regardless of this setting, so a zoomed shot's DNG will show more of
     * the scene than its JPEG. That's a platform guarantee, not a bug.
     */
    fun setZoom(ratio: Float) = post {
        zoomRatio = ratio
        val builder = previewBuilder ?: return@post
        val active = session ?: return@post
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
        runCatching { active.setRepeatingRequest(builder.build(), previewCallback, handler) }
    }

    private fun applyFlashTo(builder: CaptureRequest.Builder) {
        val hasFlash = selection?.characteristics
            ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        if (!hasFlash) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            return
        }
        val aeMode = when (flash) {
            Flash.OFF -> CameraMetadata.CONTROL_AE_MODE_ON
            Flash.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
            Flash.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
        }
        builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (flash == Flash.OFF) CameraMetadata.FLASH_MODE_OFF
            else CameraMetadata.FLASH_MODE_SINGLE
        )
    }

    /**
     * @param deviceRotationDegrees clockwise rotation of the device from its
     *   natural orientation, rounded to a multiple of 90.
     *
     * [Listener.onCaptureFinished] is always called exactly once per invocation,
     * including on every failure path, so the caller can re-enable its shutter
     * unconditionally.
     */
    fun takePhoto(deviceRotationDegrees: Int) = post {
        if (state != State.PREVIEW) {
            listener?.onCaptureFinished()
            return@post
        }
        pendingRotation = deviceRotationDegrees

        if (flash == Flash.OFF) {
            // AE is already converged from the repeating preview request.
            fireStillCapture()
        } else {
            runPrecapture()
        }
    }

    private fun runPrecapture() {
        val builder = previewBuilder
        val active = session
        if (builder == null || active == null) {
            listener?.onCaptureFinished()
            return
        }
        state = State.WAITING_PRECAPTURE
        precaptureStartedAt = SystemClock.elapsedRealtime()

        // The in-result timeout only advances when preview frames keep
        // arriving. Arm a wall-clock watchdog as well, so a stalled repeating
        // stream cannot park the state machine here forever.
        handler?.removeCallbacks(captureWatchdog)
        handler?.postDelayed(captureWatchdog, PRECAPTURE_TIMEOUT_MS)

        builder.set(
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START
        )
        val submitted = runCatching {
            active.capture(builder.build(), previewCallback, handler)
        }
        builder.set(
            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
            CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE
        )

        if (submitted.isFailure) {
            handler?.removeCallbacks(captureWatchdog)
            state = State.PREVIEW
            listener?.onCaptureFinished()
        }
    }

    private fun fireStillCapture() {
        val camera = device
        val sel = selection
        val active = session
        val jpeg = jpegReader
        if (camera == null || sel == null || active == null || jpeg == null) {
            state = State.PREVIEW
            listener?.onCaptureFinished()
            return
        }

        state = State.CAPTURING
        handler?.removeCallbacks(captureWatchdog)
        handler?.postDelayed(captureWatchdog, CAPTURE_WATCHDOG_MS)

        pendingResult = null
        pendingRawImage?.close()
        pendingRawImage = null
        awaitingJpeg = true
        awaitingRaw = rawReader != null

        // TEMPLATE_STILL_CAPTURE is the right starting point for exposure
        // behaviour, but it turns HIGH_QUALITY noise reduction and edge
        // enhancement on by default — applyNeutral then turns them back off.
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(jpeg.surface)
        rawReader?.let { builder.addTarget(it.surface) }

        CaptureTuning.applyNeutral(builder, sel.support)
        applyFlashTo(builder)
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(sel, pendingRotation))

        // Carry the preview's focus state over verbatim — including its AF mode,
        // so that a tap-to-focus lock is not undone by the still request kicking
        // off a fresh autofocus run.
        previewBuilder?.let { preview ->
            preview.get(CaptureRequest.CONTROL_AF_MODE)?.let {
                builder.set(CaptureRequest.CONTROL_AF_MODE, it)
            }
            preview.get(CaptureRequest.CONTROL_AF_REGIONS)?.let {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, it)
            }
            preview.get(CaptureRequest.CONTROL_AE_REGIONS)?.let {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, it)
            }
        }

        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                pendingResult = result
                tryEmitRaw()
            }

            override fun onCaptureFailed(
                s: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                listener?.onError("Capture failed (reason ${failure.reason})", null)
                awaitingJpeg = false
                awaitingRaw = false
                finishCapture()
            }
        }

        try {
            active.capture(builder.build(), callback, handler)
        } catch (t: Throwable) {
            listener?.onError("Could not submit capture", t)
            awaitingJpeg = false
            awaitingRaw = false
            finishCapture()
        }
    }

    private fun finishCapture() {
        if (state != State.CAPTURING) return
        handler?.removeCallbacks(captureWatchdog)
        state = State.PREVIEW
        listener?.onCaptureFinished()
        // Restore the plain preview stream.
        val builder = previewBuilder ?: return
        val active = session ?: return
        runCatching { active.setRepeatingRequest(builder.build(), previewCallback, handler) }
    }

    // ------------------------------------------------------------------
    // Reader drains
    // ------------------------------------------------------------------

    private fun drainJpeg(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (t: IllegalStateException) {
            Log.e(TAG, "JPEG reader exhausted", t)
            null
        }

        if (image == null) {
            awaitingJpeg = false
            maybeFinish()
            return
        }

        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            listener?.onJpegCaptured(bytes)
        } catch (t: Throwable) {
            Log.e(TAG, "JPEG drain failed", t)
        } finally {
            image.close()
            awaitingJpeg = false
            maybeFinish()
        }
    }

    private fun drainRaw(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (t: IllegalStateException) {
            // All buffers are still held by in-flight DNG writes. Dropping this
            // frame is far better than the uncaught throw that would otherwise
            // take down the process.
            Log.e(TAG, "RAW reader exhausted — dropping frame", t)
            null
        }

        if (image == null) {
            awaitingRaw = false
            maybeFinish()
            return
        }

        pendingRawImage?.close()
        pendingRawImage = image
        tryEmitRaw()
    }

    /**
     * A DNG needs both the Bayer frame and the [TotalCaptureResult] that
     * describes how it was exposed. They arrive on different callbacks with no
     * ordering guarantee, so whichever lands second does the work.
     */
    private fun tryEmitRaw() {
        val sel = selection ?: return
        val image = pendingRawImage ?: return
        val result = pendingResult ?: return

        pendingRawImage = null
        pendingResult = null

        val raw = RawCapture(
            characteristics = sel.characteristics,
            result = result,
            image = image,
            exifOrientation = exifOrientation(jpegOrientation(sel, pendingRotation))
        )

        val cb = listener
        if (cb == null) raw.close() else cb.onRawCaptured(raw)

        awaitingRaw = false
        maybeFinish()
    }

    private fun maybeFinish() {
        if (!awaitingJpeg && !awaitingRaw) finishCapture()
    }

    // ------------------------------------------------------------------
    // Preview callback / AE state machine
    // ------------------------------------------------------------------

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            s: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) = process(result)

        override fun onCaptureProgressed(
            s: CameraCaptureSession,
            request: CaptureRequest,
            partial: CaptureResult
        ) = process(partial)

        private fun process(result: CaptureResult) {
            if (state != State.WAITING_PRECAPTURE && state != State.WAITING_CONVERGE) return

            // Partial results routinely omit AE state. A missing value means
            // "no information yet", never "converged" — treating it as the
            // latter would skip the pre-flash entirely.
            val ae = result.get(CaptureResult.CONTROL_AE_STATE)

            if (SystemClock.elapsedRealtime() - precaptureStartedAt > PRECAPTURE_TIMEOUT_MS) {
                Log.w(TAG, "Precapture timed out in $state — capturing anyway")
                fireStillCapture()
                return
            }

            if (ae == null) return

            when (state) {
                State.WAITING_PRECAPTURE -> when (ae) {
                    CaptureResult.CONTROL_AE_STATE_PRECAPTURE ->
                        state = State.WAITING_CONVERGE
                    // Some HALs satisfy the trigger without ever passing
                    // through PRECAPTURE; those are already done.
                    CaptureResult.CONTROL_AE_STATE_CONVERGED,
                    CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ->
                        fireStillCapture()
                    else -> Unit
                }
                State.WAITING_CONVERGE ->
                    if (ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) fireStillCapture()
                else -> Unit
            }
        }
    }

    // ------------------------------------------------------------------
    // Tap to focus
    // ------------------------------------------------------------------

    /**
     * @param nx normalised horizontal position of the tap within the preview (0..1)
     * @param ny normalised vertical position of the tap within the preview (0..1)
     */
    fun focusAt(nx: Float, ny: Float) = post {
        val sel = selection ?: return@post
        val builder = previewBuilder ?: return@post
        val active = session ?: return@post
        if (state != State.PREVIEW) return@post

        val array = sel.characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return@post

        val regions = arrayOf(meteringRectangle(array, nx, ny, sel))

        val maxAf = sel.characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAe = sel.characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0

        if (maxAf > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
        if (maxAe > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions)

        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)

        runCatching {
            active.capture(builder.build(), previewCallback, handler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            active.setRepeatingRequest(builder.build(), previewCallback, handler)
        }

        // Hand focus back to continuous AF after a few seconds, like the stock
        // app. Re-tapping restarts the clock rather than stacking callbacks.
        handler?.removeCallbacks(afResetRunnable)
        handler?.postDelayed(afResetRunnable, AF_RESET_DELAY_MS)
    }

    private fun resetFocusInternal() {
        val builder = previewBuilder ?: return
        val active = session ?: return
        if (state != State.PREVIEW) return
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
        builder.set(
            CaptureRequest.CONTROL_AF_MODE,
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        runCatching {
            active.capture(builder.build(), previewCallback, handler)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            active.setRepeatingRequest(builder.build(), previewCallback, handler)
        }
    }

    private fun meteringRectangle(
        array: Rect,
        nx: Float,
        ny: Float,
        sel: CameraChooser.Selection
    ): MeteringRectangle {
        // The preview is portrait; the sensor array is in its own frame. Rotate
        // the normalised tap into sensor space.
        //
        // No front-camera mirroring here: TrueShot deliberately shows the front
        // preview un-mirrored, because the saved file is un-mirrored and the
        // whole promise of the app is that the two match. Flipping x would put
        // the focus point on the opposite side of what the user touched.
        val x = nx
        val (sx, sy) = when (sel.sensorOrientation) {
            90 -> ny to (1f - x)
            270 -> (1f - ny) to x
            180 -> (1f - x) to (1f - ny)
            else -> x to ny
        }

        val halfW = (array.width() * 0.08f).toInt().coerceAtLeast(1)
        val halfH = (array.height() * 0.08f).toInt().coerceAtLeast(1)
        val cx = (array.left + sx * array.width()).toInt()
        val cy = (array.top + sy * array.height()).toInt()

        val left = (cx - halfW).coerceIn(array.left, array.right - 1)
        val top = (cy - halfH).coerceIn(array.top, array.bottom - 1)
        val right = (cx + halfW).coerceIn(left + 1, array.right)
        val bottom = (cy + halfH).coerceIn(top + 1, array.bottom)

        return MeteringRectangle(
            left, top, right - left, bottom - top,
            MeteringRectangle.METERING_WEIGHT_MAX - 1
        )
    }

    // ------------------------------------------------------------------
    // Orientation helpers
    // ------------------------------------------------------------------

    /**
     * Rotation, in degrees, to stamp on a file captured while the device is
     * held [deviceRotationDegrees] clockwise from its natural orientation.
     * Used for MediaRecorder's orientation hint.
     *
     * Safe to call from any thread: it reads only immutable selection data.
     */
    fun orientationFor(deviceRotationDegrees: Int): Int {
        val sel = selection ?: return 0
        return jpegOrientation(sel, deviceRotationDegrees)
    }

    /**
     * The formula documented on [CaptureRequest.JPEG_ORIENTATION]: the device
     * orientation is negated for front-facing cameras and then added to the
     * sensor orientation. Getting the sign backwards is invisible in portrait
     * and rotates every landscape shot by 180°.
     */
    private fun jpegOrientation(sel: CameraChooser.Selection, deviceRotationDeg: Int): Int {
        val deviceOrientation =
            if (sel.isFrontFacing) -deviceRotationDeg else deviceRotationDeg
        return (sel.sensorOrientation + deviceOrientation + 360) % 360
    }

    private fun exifOrientation(degrees: Int): Int = when (degrees) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }
}
