package `in`.shvms.trackme.utils

import java.io.InputStream
import java.util.zip.ZipInputStream

private const val EXPORT_FAILURE_ENTRY = "EXPORT_FAILED.txt"

/** Returns true when the server marked a streamed export as incomplete. */
fun InputStream.containsExportFailureMarker(): Boolean {
    ZipInputStream(this).use { zipInput ->
        while (true) {
            val entry = zipInput.nextEntry ?: return false
            if (entry.name == EXPORT_FAILURE_ENTRY) return true
            zipInput.closeEntry()
        }
    }
}
