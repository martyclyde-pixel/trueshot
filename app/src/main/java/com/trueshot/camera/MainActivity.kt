package com.trueshot.camera

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.exifinterface.media.ExifInterface
import com.trueshot.camera.camera.AspectRatio
import com.trueshot.camera.camera.CameraChooser
import com.trueshot.camera.camera.CameraEngine
import com.trueshot.camera.camera.VideoRecorder
import com.trueshot.camera.databinding.ActivityMainBinding
import com.trueshot.camera.io.JpegCropper
import com.trueshot.camera.io.MediaStoreWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private enum class Mode { PHOTO, VIDEO }

    companion object {
        private const val PREFS_NAME = "trueshot"
        private const val PREF_RAW_IN_GALLERY = "raw_in_gallery"
        private const val PREF_ASPECT = "aspect_ratio"

        /** How close a target ratio must be to the native capture to skip cropping entirely. */
        private const val ASPECT_MATCH_TOLERANCE = 0.01f
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var writer: MediaStoreWriter
    private lateinit var engine: CameraEngine
    private lateinit var recorder: VideoRecorder

    private val main = Handler(Looper.getMainLooper())

    /**
     * Deliberately NOT lifecycleScope: a DNG write holds a native image buffer
     * that must be released whether or not the activity survives long enough to
     * care about the result. A plain executor keeps running to completion.
     */
    private lateinit var saveExecutor: ExecutorService

    private var selection: CameraChooser.Selection? = null
    private var mode = Mode.PHOTO
    private var useFrontCamera = false
    private var flash = CameraEngine.Flash.OFF

    private var surfaceReady = false
    private var cameraOpen = false
    private var capturing = false
    private var warnedNoRaw = false

    /**
     * Backs the gallery button's thumbnail; null until the first photo of the
     * session (or a prior one) loads. Written from the save executor thread,
     * read from the main thread.
     */
    @Volatile
    private var lastPhotoUri: Uri? = null

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    /**
     * Whether the RAW DNG also gets a visible gallery entry (default: no —
     * see MediaStoreWriter's class doc). Read fresh from the executor thread
     * into a local val at capture time, so a mid-capture toggle can't produce
     * a half-applied result.
     */
    @Volatile
    private var rawInGallery = false

    /** The device's own portrait width/height — FULL's target shape, computed once. */
    private val screenRatio: Float by lazy {
        resources.displayMetrics.let { it.widthPixels.toFloat() / it.heightPixels.toFloat() }
    }

    /** Saved-photo shape; see AspectRatio's doc. Read fresh into a local val at capture time. */
    @Volatile
    private var selectedAspect = AspectRatio.FULL

    /** Device rotation in degrees clockwise from natural, snapped to 90. */
    @Volatile
    private var deviceRotation = 0

    /**
     * One timestamp per shutter press, shared by the JPEG and the DNG so the
     * pair has a matching basename in the gallery.
     */
    @Volatile
    private var captureBaseName = ""

    private var recordStartMs = 0L
    private val timerTick = object : Runnable {
        override fun run() {
            if (!recorder.isRecording) return
            val elapsed = (System.currentTimeMillis() - recordStartMs) / 1000
            binding.recordTimer.text =
                String.format(Locale.US, "%d:%02d", elapsed / 60, elapsed % 60)
            main.postDelayed(this, 250)
        }
    }

    private lateinit var orientationListener: OrientationEventListener

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            configureCamera()
        } else {
            toast(getString(R.string.permission_denied))
        }
    }

    // ------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyWindowInsets()

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        writer = MediaStoreWriter(this)
        engine = CameraEngine(cameraManager)
        recorder = VideoRecorder(this)
        saveExecutor = Executors.newSingleThreadExecutor()
        rawInGallery = prefs.getBoolean(PREF_RAW_IN_GALLERY, false)
        selectedAspect = runCatching {
            AspectRatio.valueOf(prefs.getString(PREF_ASPECT, AspectRatio.FULL.name)!!)
        }.getOrDefault(AspectRatio.FULL)

        binding.preview.holder.addCallback(surfaceCallback)

        binding.shutterButton.setOnClickListener { onShutter() }
        binding.flipButton.setOnClickListener { onFlip() }
        binding.flashButton.setOnClickListener { onFlashCycle() }
        binding.modePhoto.setOnClickListener { setMode(Mode.PHOTO) }
        binding.modeVideo.setOnClickListener { setMode(Mode.VIDEO) }
        binding.preview.setOnTouchListener { view, event -> onPreviewTouch(view, event) }
        binding.galleryButton.setOnClickListener { openGallery() }
        binding.settingsButton.setOnClickListener { showSettingsDialog() }
        binding.zoomRuler.onRatioChanged = { ratio -> engine.setZoom(ratio) }
        binding.aspectFull.setOnClickListener { setAspect(AspectRatio.FULL) }
        binding.aspect34.setOnClickListener { setAspect(AspectRatio.R3_4) }
        binding.aspect11.setOnClickListener { setAspect(AspectRatio.R1_1) }
        binding.aspect45.setOnClickListener { setAspect(AspectRatio.R4_5) }
        updateAspectUi()

        loadInitialGalleryThumbnail()

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                deviceRotation = when {
                    degrees >= 315 || degrees < 45 -> 0
                    degrees < 135 -> 90
                    degrees < 225 -> 180
                    else -> 270
                }
            }
        }
    }

    /**
     * Android 15+ draws every app edge to edge whether it asks to or not, so
     * the controls would sit under the status bar and the gesture handle. The
     * chrome alone is inset; the preview is left to fill the window.
     *
     * On API 29-34 the app is not edge to edge, the insets arrive as zero and
     * this is a no-op — which is the correct result there.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bars: Insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.topChrome.updatePadding(top = bars.top)
            binding.shutterButton.updateLayoutParams<ConstraintLayout.LayoutParams> {
                bottomMargin = bars.bottom + resources.getDimensionPixelSize(R.dimen.shutter_margin)
            }
            windowInsets
        }
    }

    override fun onResume() {
        super.onResume()
        orientationListener.enable()

        // Recover any UI state that a mid-capture or mid-recording pause left
        // disabled, so the app is never stuck after coming back.
        capturing = false
        binding.shutterButton.isEnabled = true
        setChromeEnabled(true)
        updateShutterIcon()

        if (hasCameraPermission()) {
            configureCamera()
        } else {
            requestPermissions()
        }
    }

    override fun onPause() {
        orientationListener.disable()
        main.removeCallbacks(timerTick)
        if (recorder.isRecording) {
            recorder.stop(writer)
            binding.recordTimer.visibility = View.GONE
        } else {
            recorder.releaseWithoutSaving(writer)
        }
        engine.stop()
        cameraOpen = false
        super.onPause()
    }

    override fun onDestroy() {
        // Already-submitted saves still run to completion — the executor's
        // thread is non-daemon. Deliberately NOT awaited: a 25 MB DNG write
        // takes about a second, and blocking the main thread on it here is an
        // ANR waiting to happen.
        saveExecutor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
    }

    // ------------------------------------------------------------------
    // Camera bring-up
    // ------------------------------------------------------------------

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = Unit

        override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
            surfaceReady = true
            openIfReady()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            engine.stop()
            cameraOpen = false
        }
    }

    /**
     * Resolves which camera and which output sizes to use, then sizes the
     * preview to match. Sizing the preview triggers a surfaceChanged, which is
     * what actually opens the device.
     */
    private fun configureCamera() {
        if (!hasCameraPermission()) return

        val sel = try {
            val id = CameraChooser.findCameraId(cameraManager, useFrontCamera)
                ?: run { toast(getString(R.string.no_camera)); return }
            CameraChooser.select(cameraManager, id)
        } catch (t: Throwable) {
            toast(getString(R.string.no_camera))
            return
        }

        selection = sel
        warnedNoRaw = false
        binding.preview.setAspectRatio(sel.previewSize.width, sel.previewSize.height)
        updateZoomUi(sel.zoomRange)

        // If the surface was already the right size, surfaceChanged will not
        // fire again — kick the open ourselves on the next frame.
        binding.preview.post { openIfReady() }
    }

    private fun updateZoomUi(range: Range<Float>?) {
        if (range == null || range.lower >= range.upper) {
            binding.zoomRuler.visibility = View.GONE
            return
        }
        binding.zoomRuler.visibility = View.VISIBLE
        binding.zoomRuler.setRange(range.lower, range.upper)
        // Matches CameraEngine.open()'s default: this lens's own widest point,
        // never pre-zoomed past 1x.
        binding.zoomRuler.currentRatio = range.lower.coerceAtMost(1f)
    }

    private fun openIfReady() {
        val sel = selection ?: return
        if (!surfaceReady || cameraOpen) return
        cameraOpen = true

        engine.start()
        engine.open(sel, engineListener) {
            // Called on the camera background thread once the device is open.
            startSessionForMode()
        }
    }

    private fun startSessionForMode() {
        val surface = binding.preview.holder.surface
        if (!surface.isValid) return
        when (mode) {
            Mode.PHOTO -> engine.startPhotoSession(surface)
            Mode.VIDEO -> engine.startVideoSession(surface, null) {}
        }
        engine.setFlash(flash)
    }

    // ------------------------------------------------------------------
    // Engine callbacks (camera background thread)
    // ------------------------------------------------------------------

    private val engineListener = object : CameraEngine.Listener {

        override fun onSessionReady(selection: CameraChooser.Selection) {
            // Kept in logcat rather than the status pill (which now just shows
            // the app name) — this is the signal from CLAUDE.md §7 that the HAL
            // actually honoured the processing overrides.
            Log.i("MainActivity", "Tuning: ${selection.support.describe()}")
            main.post {
                // Warn once per camera selection, not on every mode switch.
                if (mode == Mode.PHOTO && selection.rawSize == null && !warnedNoRaw) {
                    warnedNoRaw = true
                    toast(getString(R.string.raw_unavailable))
                }
            }
        }

        override fun onJpegCaptured(bytes: ByteArray) {
            val name = captureBaseName
            // The DNG is the slower write, so when RAW is available the
            // confirmation is reported from there instead — that way the toast
            // means "both files are on disk", not "one of them might be".
            val expectRaw = selection?.rawSize != null
            val target = selectedAspect.ratio(screenRatio)
            val nativeRatio = selection?.jpegSize
                ?.let { it.width.toFloat() / it.height.toFloat() }
                ?: target
            submitSave {
                // Skip the crop entirely when the chosen ratio already matches
                // the sensor's native shape (e.g. 3:4) — no pixel is touched.
                var restampOrientation: Int? = null
                val finalBytes = if (abs(target - nativeRatio) < ASPECT_MATCH_TOLERANCE) {
                    bytes
                } else {
                    val result = JpegCropper.crop(bytes, target)
                    restampOrientation = result.exifOrientation
                    result.bytes
                }

                val uri = writer.writeJpeg(finalBytes, name)
                if (uri != null && restampOrientation != null) {
                    // compress() drops EXIF entirely; the crop is still in the
                    // original buffer orientation, so the tag must be restored
                    // or galleries will show it sideways/upside down.
                    runCatching {
                        contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                            val exif = ExifInterface(pfd.fileDescriptor)
                            exif.setAttribute(ExifInterface.TAG_ORIENTATION, restampOrientation.toString())
                            exif.saveAttributes()
                        }
                    }
                }

                if (!expectRaw) {
                    main.post {
                        toast(getString(if (uri != null) R.string.saved_photo else R.string.save_failed))
                    }
                }
                if (uri != null) refreshGalleryThumbnail(uri)
            }
        }

        override fun onRawCaptured(raw: CameraEngine.RawCapture) {
            val name = captureBaseName
            val toGallery = rawInGallery
            val submitted = submitSave {
                // use{} guarantees the native buffer is released even if the
                // MediaStore write throws; leaking one permanently costs a slot
                // in the RAW reader.
                raw.use { capture ->
                    val ok = writer.writeDng(name, toGallery) { out -> capture.writeTo(out) }
                    main.post {
                        toast(
                            getString(
                                when {
                                    !ok -> R.string.save_failed
                                    toGallery -> R.string.saved_photo_with_raw_gallery
                                    else -> R.string.saved_photo_with_raw
                                }
                            )
                        )
                    }
                }
            }
            if (!submitted) raw.close()
        }

        override fun onCaptureFinished() {
            main.post {
                capturing = false
                binding.shutterButton.isEnabled = true
            }
        }

        override fun onDeviceLost(message: String) {
            main.post {
                // Clear the guard so a later resume or surface event can reopen;
                // without this the preview stays black for the rest of the
                // process lifetime.
                cameraOpen = false
                capturing = false
                binding.shutterButton.isEnabled = true
                toast(message)
            }
        }

        override fun onError(message: String, cause: Throwable?) {
            main.post {
                // Same as onDeviceLost: leaving cameraOpen set would make
                // openIfReady() a no-op for the rest of the session.
                cameraOpen = false
                capturing = false
                binding.shutterButton.isEnabled = true
                toast(message)
            }
        }
    }

    /** @return false if the executor has shut down and the task will not run. */
    private fun submitSave(task: () -> Unit): Boolean = try {
        saveExecutor.execute(task)
        true
    } catch (t: RejectedExecutionException) {
        false
    }

    // ------------------------------------------------------------------
    // Controls
    // ------------------------------------------------------------------

    private fun onShutter() {
        when (mode) {
            Mode.PHOTO -> {
                // Without the cameraOpen guard, a shutter press before the
                // engine exists is silently dropped and the button never
                // comes back.
                if (capturing || !cameraOpen) return
                capturing = true
                binding.shutterButton.isEnabled = false
                captureBaseName =
                    SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                flashShutterFeedback()
                // The engine guarantees onCaptureFinished on every path,
                // including its early returns, so the button always comes back.
                engine.takePhoto(deviceRotation)
            }
            Mode.VIDEO -> {
                if (recorder.isRecording) stopRecording() else startRecording()
            }
        }
    }

    private fun startRecording() {
        val sel = selection ?: return
        val surface = binding.preview.holder.surface
        if (!surface.isValid) return

        val recorderSurface = try {
            recorder.prepare(
                writer = writer,
                cameraId = sel.cameraId,
                orientationHint = engine.orientationFor(deviceRotation),
                withAudio = hasAudioPermission()
            )
        } catch (t: Throwable) {
            null
        } ?: run { toast(getString(R.string.record_failed)); return }

        engine.startVideoSession(surface, recorderSurface) {
            main.post {
                if (recorder.start()) {
                    recordStartMs = System.currentTimeMillis()
                    binding.recordTimer.visibility = View.VISIBLE
                    binding.recordTimer.text = "0:00"
                    main.post(timerTick)
                    updateShutterIcon()
                    setChromeEnabled(false)
                } else {
                    recorder.releaseWithoutSaving(writer)
                    toast(getString(R.string.record_failed))
                }
            }
        }
    }

    private fun stopRecording() {
        val ok = recorder.stop(writer)
        main.removeCallbacks(timerTick)
        binding.recordTimer.visibility = View.GONE
        updateShutterIcon()
        setChromeEnabled(true)
        toast(getString(if (ok) R.string.saved_video else R.string.video_discarded))

        // Back to an idle video preview for the next clip.
        val surface = binding.preview.holder.surface
        if (surface.isValid) engine.startVideoSession(surface, null) {}
    }

    private fun setMode(next: Mode) {
        if (mode == next || recorder.isRecording) return
        mode = next

        binding.modePhoto.setTextColor(
            ContextCompat.getColor(this, if (next == Mode.PHOTO) R.color.accent else R.color.chrome)
        )
        binding.modeVideo.setTextColor(
            ContextCompat.getColor(this, if (next == Mode.VIDEO) R.color.accent else R.color.chrome)
        )
        updateShutterIcon()
        startSessionForMode()
    }

    private fun updateShutterIcon() {
        binding.shutterButton.setBackgroundResource(
            when {
                mode == Mode.PHOTO -> R.drawable.shutter_photo
                recorder.isRecording -> R.drawable.shutter_video_active
                else -> R.drawable.shutter_video_idle
            }
        )
        binding.shutterButton.contentDescription = getString(
            if (mode == Mode.PHOTO) R.string.cd_shutter else R.string.cd_record
        )
    }

    private fun setChromeEnabled(enabled: Boolean) {
        binding.flipButton.isEnabled = enabled
        binding.flashButton.isEnabled = enabled
        binding.modePhoto.isEnabled = enabled
        binding.modeVideo.isEnabled = enabled
        binding.zoomRuler.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.35f
        binding.flipButton.alpha = alpha
        binding.flashButton.alpha = alpha
        binding.modeBar.alpha = alpha
        binding.zoomRuler.alpha = alpha
    }

    private fun setAspect(aspect: AspectRatio) {
        if (aspect == selectedAspect) return
        selectedAspect = aspect
        prefs.edit().putString(PREF_ASPECT, aspect.name).apply()
        updateAspectUi()
    }

    private fun updateAspectUi() {
        binding.aspectMask.targetRatio = selectedAspect.ratio(screenRatio)
        val buttons = mapOf(
            AspectRatio.FULL to binding.aspectFull,
            AspectRatio.R3_4 to binding.aspect34,
            AspectRatio.R1_1 to binding.aspect11,
            AspectRatio.R4_5 to binding.aspect45
        )
        val accent = ContextCompat.getColor(this, R.color.accent)
        val chrome = ContextCompat.getColor(this, R.color.chrome)
        buttons.forEach { (aspect, view) -> view.setTextColor(if (aspect == selectedAspect) accent else chrome) }
    }

    private fun showSettingsDialog() {
        val options = arrayOf(getString(R.string.raw_private_option), getString(R.string.raw_gallery_option))
        AlertDialog.Builder(this)
            .setCustomTitle(layoutInflater.inflate(R.layout.dialog_title_settings, null))
            .setSingleChoiceItems(options, if (rawInGallery) 1 else 0) { dialog, which ->
                rawInGallery = which == 1
                prefs.edit().putBoolean(PREF_RAW_IN_GALLERY, rawInGallery).apply()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Gallery thumbnail button
    // ------------------------------------------------------------------

    private fun openGallery() {
        val uri = lastPhotoUri
        val intent = if (uri != null) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "vnd.android.cursor.dir/image")
        }
        try {
            startActivity(intent)
        } catch (t: ActivityNotFoundException) {
            toast(getString(R.string.no_gallery_app))
        }
    }

    /** Must be called from the save executor thread — decodes a downsized bitmap, which is not free. */
    private fun refreshGalleryThumbnail(uri: Uri) {
        lastPhotoUri = uri
        val bitmap = try {
            contentResolver.loadThumbnail(uri, Size(160, 160), null)
        } catch (t: Throwable) {
            null
        }
        if (bitmap != null) main.post { binding.galleryButton.setImageBitmap(bitmap) }
    }

    /** Seeds the thumbnail from a prior session's last photo, so the button isn't empty on a cold start. */
    private fun loadInitialGalleryThumbnail() {
        submitSave {
            writer.lastImageUri()?.let { refreshGalleryThumbnail(it) }
        }
    }

    private fun onFlip() {
        if (recorder.isRecording) return
        useFrontCamera = !useFrontCamera
        engine.stop()
        cameraOpen = false
        configureCamera()
    }

    private fun onFlashCycle() {
        flash = when (flash) {
            CameraEngine.Flash.OFF -> CameraEngine.Flash.AUTO
            CameraEngine.Flash.AUTO -> CameraEngine.Flash.ON
            CameraEngine.Flash.ON -> CameraEngine.Flash.OFF
        }
        binding.flashButton.setImageResource(
            when (flash) {
                CameraEngine.Flash.OFF -> R.drawable.ic_flash_off
                CameraEngine.Flash.AUTO -> R.drawable.ic_flash_auto
                CameraEngine.Flash.ON -> R.drawable.ic_flash_on
            }
        )
        binding.flashButton.contentDescription = getString(
            when (flash) {
                CameraEngine.Flash.OFF -> R.string.flash_off
                CameraEngine.Flash.AUTO -> R.string.flash_auto
                CameraEngine.Flash.ON -> R.string.flash_on
            }
        )
        engine.setFlash(flash)
    }

    private fun onPreviewTouch(view: View, event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        view.performClick()

        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0f || h <= 0f) return true

        val nx = (event.x / w).coerceIn(0f, 1f)
        val ny = (event.y / h).coerceIn(0f, 1f)
        engine.focusAt(nx, ny)
        showFocusRing(view.left + event.x, view.top + event.y)
        return true
    }

    private fun showFocusRing(x: Float, y: Float) {
        val ring = binding.focusRing
        ring.translationX = x - ring.width / 2f
        ring.translationY = y - ring.height / 2f
        ring.visibility = View.VISIBLE
        ring.alpha = 1f
        ring.animate().cancel()
        ring.animate().alpha(0f).setStartDelay(900).setDuration(250)
            .withEndAction { ring.visibility = View.INVISIBLE }
            .start()
    }

    private fun flashShutterFeedback() {
        ObjectAnimator.ofFloat(binding.preview, View.ALPHA, 1f, 0.35f, 1f).apply {
            duration = 160
        }.start()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
