package `in`.shvms.trackme.ui.history

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/** The storage seam keeps API routing and provider failures executable in local unit tests. */
internal fun interface GalleryImageWriter {
    fun write(imageFile: File, displayName: String, dateAddedSeconds: Long)
}

private class MediaStoreGalleryImageWriter(
    private val resolver: ContentResolver
) : GalleryImageWriter {
    override fun write(imageFile: File, displayName: String, dateAddedSeconds: Long) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.DATE_ADDED, dateAddedSeconds)
        }
        var insertedUri: Uri? = null
        try {
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create gallery destination")
            insertedUri = uri
            resolver.openOutputStream(uri)?.use { output ->
                imageFile.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Unable to open gallery output")
        } catch (error: Throwable) {
            insertedUri?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
            throw error
        }
    }
}

internal fun shouldUseGalleryDocumentPicker(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt < Build.VERSION_CODES.Q

internal fun galleryImageDisplayName(nameHint: String, nowMillis: Long = System.currentTimeMillis()): String =
    "TrackMe_${nameHint}_${nowMillis}.png"

/** Activity-result contracts can throw when no document provider handles the intent. */
internal inline fun tryLaunchGalleryDocument(launch: () -> Unit): Boolean =
    runCatching(launch).isSuccess

/**
 * Writes through MediaStore on Android 10+. Older releases must use the SAF document picker,
 * which avoids adding legacy external-storage permissions.
 */
internal fun saveImageToGallery(context: Context, imageFile: File, nameHint: String): Boolean =
    saveImageToGallery(
        imageFile = imageFile,
        nameHint = nameHint,
        sdkInt = Build.VERSION.SDK_INT,
        writer = MediaStoreGalleryImageWriter(context.contentResolver)
    )

internal fun saveImageToGallery(
    imageFile: File,
    nameHint: String,
    sdkInt: Int,
    writer: GalleryImageWriter,
    nowMillis: Long = System.currentTimeMillis()
): Boolean {
    if (shouldUseGalleryDocumentPicker(sdkInt)) return false
    return runCatching {
        writer.write(
            imageFile = imageFile,
            displayName = galleryImageDisplayName(nameHint, nowMillis),
            dateAddedSeconds = nowMillis / 1000
        )
        true
    }.getOrDefault(false)
}

/** Copies an exported image to the user-selected SAF destination. */
internal fun saveImageToDocument(context: Context, imageFile: File, destination: Uri): Boolean =
    runCatching {
        context.contentResolver.openOutputStream(destination)?.use { output ->
            imageFile.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Unable to open image destination")
        true
    }.getOrDefault(false)
