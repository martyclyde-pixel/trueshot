package com.trueshot.camera.camera

import android.content.Context
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.view.Surface
import com.trueshot.camera.io.MediaStoreWriter

/**
 * Wraps MediaRecorder and the MediaStore entry it writes into.
 *
 * Video on Android goes through the hardware encoder, so there is no RAW
 * equivalent — but the frames handed to the encoder still come from the ISP,
 * and [CaptureTuning] has already turned off noise reduction, sharpening and
 * the filmic tone curve on the recording request. The encoder is run at a high
 * bit rate so the neutral grain we deliberately kept is not smeared away by
 * compression instead.
 */
class VideoRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VideoRecorder"
        /** 1.5x the profile default: neutral, un-denoised frames compress worse. */
        private const val BITRATE_MULTIPLIER = 1.5
    }

    private var recorder: MediaRecorder? = null
    private var pending: MediaStoreWriter.PendingVideo? = null
    private var started = false

    val isRecording: Boolean get() = started

    /**
     * Build the recorder and return the Surface the camera should target.
     * Call before creating the video capture session.
     */
    fun prepare(
        writer: MediaStoreWriter,
        cameraId: String,
        orientationHint: Int,
        withAudio: Boolean
    ): Surface? {
        // A previous prepare() whose session never came up would otherwise leak
        // its MediaRecorder and orphan an IS_PENDING row in the gallery.
        releaseWithoutSaving(writer)

        val entry = writer.createVideo() ?: run {
            Log.e(TAG, "Could not create MediaStore video entry")
            return null
        }
        pending = entry

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            // Inside the try: CamcorderProfile.get throws for a profile the
            // device does not have, and letting that escape would crash the app
            // and orphan the pending MediaStore row created above.
            val profile = resolveProfile(cameraId)

            if (withAudio) rec.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            rec.setVideoEncodingBitRate((profile.videoBitRate * BITRATE_MULTIPLIER).toInt())
            rec.setVideoFrameRate(profile.videoFrameRate)
            rec.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight)
            rec.setVideoEncoder(profile.videoCodec)

            if (withAudio) {
                rec.setAudioEncodingBitRate(profile.audioBitRate)
                rec.setAudioSamplingRate(profile.audioSampleRate)
                rec.setAudioChannels(profile.audioChannels)
                rec.setAudioEncoder(profile.audioCodec)
            }

            rec.setOutputFile(entry.descriptor.fileDescriptor)
            rec.setOrientationHint(orientationHint)
            rec.prepare()
        } catch (t: Throwable) {
            Log.e(TAG, "MediaRecorder prepare failed", t)
            rec.release()
            writer.finishVideo(entry, success = false)
            pending = null
            return null
        }

        recorder = rec
        return rec.surface
    }

    fun start(): Boolean {
        val rec = recorder ?: return false
        return try {
            rec.start()
            started = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "MediaRecorder start failed", t)
            false
        }
    }

    /** @return true if a playable file was written. */
    fun stop(writer: MediaStoreWriter): Boolean {
        val rec = recorder
        val entry = pending
        var ok = false

        if (rec != null) {
            try {
                if (started) rec.stop()
                ok = started
            } catch (t: Throwable) {
                // stop() throws if fewer than ~1s of frames were captured; the
                // partial file is unplayable, so it gets discarded below.
                Log.w(TAG, "MediaRecorder stop failed — discarding clip", t)
                ok = false
            } finally {
                runCatching { rec.reset() }
                rec.release()
            }
        }

        entry?.let { writer.finishVideo(it, ok) }

        recorder = null
        pending = null
        started = false
        return ok
    }

    fun releaseWithoutSaving(writer: MediaStoreWriter) {
        recorder?.let { runCatching { it.reset() }; it.release() }
        pending?.let { writer.finishVideo(it, success = false) }
        recorder = null
        pending = null
        started = false
    }

    @Suppress("DEPRECATION")
    private fun resolveProfile(cameraId: String): CamcorderProfile {
        // Logical camera ids on a Pixel are "0", "1", … but physical
        // sub-camera ids are not numeric; fall back to the primary profile
        // rather than throwing.
        val idInt = cameraId.toIntOrNull() ?: 0

        // Prefer 4K where the device offers it, then 1080p, then whatever the
        // device calls "high", then its lowest. Every level is probed with
        // hasProfile first, because get() throws on an absent profile.
        val preferred = intArrayOf(
            CamcorderProfile.QUALITY_2160P,
            CamcorderProfile.QUALITY_1080P,
            CamcorderProfile.QUALITY_HIGH,
            CamcorderProfile.QUALITY_LOW
        )
        for (quality in preferred) {
            if (CamcorderProfile.hasProfile(idInt, quality)) {
                return CamcorderProfile.get(idInt, quality)
            }
        }
        throw IllegalStateException("Camera $cameraId advertises no camcorder profile")
    }
}
