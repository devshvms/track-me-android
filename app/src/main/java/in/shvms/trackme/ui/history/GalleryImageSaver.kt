package `in`.shvms.trackme.ui.history

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File

/** Shared API-24-compatible gallery writer for all export-preview surfaces. */
internal fun saveImageToGallery(context: Context, imageFile: File, nameHint: String): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "TrackMe_${nameHint}_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return runCatching {
        resolver.openOutputStream(uri)?.use { output -> imageFile.inputStream().use { input -> input.copyTo(output) } }
            ?: error("Unable to open gallery output")
        true
    }.getOrElse {
        resolver.delete(uri, null, null)
        false
    }
}
