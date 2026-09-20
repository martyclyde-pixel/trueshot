package com.trueshot.camera.io

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes finished JPEGs and video into the system gallery. DNGs go there too
 * only if the user has opted in (see [writeDng]) — by default they land in
 * app-private storage instead.
 *
 * JPEG/video go into DCIM/TrueShot so they show up in Google Photos and Files
 * alongside stock camera output. On API 29+ this needs no storage permission
 * at all, which keeps the Play data-safety declaration trivial.
 *
 * DNGs default to NOT going through MediaStore: most gallery apps render a
 * bare Bayer RAW_SENSOR frame's thumbnail as a flat grayscale mosaic (nothing
 * has demosaiced it yet), which reads as a broken duplicate photo next to the
 * real JPEG. The DNG is still written in full for every shot regardless — the
 * setting only controls whether it also gets a visible gallery entry.
 */
class MediaStoreWriter(private val context: Context) {

    companion object {
        private const val TAG = "MediaStoreWriter"
        const val RELATIVE_DIR = "DCIM/TrueShot"
        private const val RAW_SUBDIR = "RAW"
        private const val DNG_MIME = "image/x-adobe-dng"
    }

    private fun timestampName(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    /** Reserve a gallery entry and hand back a stream to fill it. */
    private fun createImage(displayName: String, mime: String): Pair<Uri, OutputStream>? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
        }

        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values) ?: run {
            Log.e(TAG, "MediaStore refused to create $displayName")
            return null
        }

        val stream = context.contentResolver.openOutputStream(uri) ?: run {
            context.contentResolver.delete(uri, null, null)
            return null
        }

        return uri to stream
    }

    private fun publish(uri: Uri) {
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, done, null, null)
    }

    private fun discard(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    /** Write a JPEG. Returns the gallery uri, or null on failure. */
    fun writeJpeg(bytes: ByteArray, baseName: String = timestampName()): Uri? {
        val (uri, stream) = createImage("TS_$baseName.jpg", "image/jpeg") ?: return null
        return try {
            stream.use { it.write(bytes) }
            publish(uri)
            uri
        } catch (t: Throwable) {
            Log.e(TAG, "JPEG write failed", t)
            discard(uri)
            null
        }
    }

    /**
     * Write a DNG. The caller supplies a lambda rather than bytes because
     * DngCreator streams directly and we never want a 25 MB array on the heap.
     *
     * @param toGallery false (the default) writes to app-private storage, per
     *   the class doc; true writes into DCIM/TrueShot like the JPEG.
     */
    fun writeDng(
        baseName: String = timestampName(),
        toGallery: Boolean = false,
        body: (OutputStream) -> Unit
    ): Boolean {
        if (toGallery) {
            val (uri, stream) = createImage("TS_$baseName.dng", DNG_MIME) ?: return false
            return try {
                stream.use(body)
                publish(uri)
                true
            } catch (t: Throwable) {
                Log.e(TAG, "DNG write failed", t)
                discard(uri)
                false
            }
        }

        val dir = File(context.getExternalFilesDir(null), RAW_SUBDIR).apply { mkdirs() }
        val file = File(dir, "TS_$baseName.dng")
        return try {
            FileOutputStream(file).use(body)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "DNG write failed", t)
            file.delete()
            false
        }
    }

    /** The most recently saved photo, or null if none exist yet. Used to seed the gallery thumbnail. */
    fun lastImageUri(): Uri? {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("TS_%.jpg")
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(collection, projection, selection, args, sort)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    /** Pending video entry — MediaRecorder writes into the descriptor itself. */
    class PendingVideo(
        val uri: Uri,
        val descriptor: ParcelFileDescriptor
    )

    fun createVideo(baseName: String = timestampName()): PendingVideo? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "TS_$baseName.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
        }

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values) ?: return null
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: run {
            discard(uri)
            return null
        }
        return PendingVideo(uri, pfd)
    }

    fun finishVideo(pending: PendingVideo, success: Boolean) {
        runCatching { pending.descriptor.close() }
        if (success) publish(pending.uri) else discard(pending.uri)
    }
}
