package `in`.shvms.trackme.utils

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportArchiveValidatorTest {
    @Test
    fun detectsServerFailureMarker() {
        val archive = zipWithEntry("EXPORT_FAILED.txt")

        assertTrue(ByteArrayInputStream(archive).containsExportFailureMarker())
    }

    @Test
    fun acceptsCompleteArchiveWithoutFailureMarker() {
        val archive = zipWithEntry("metadata.json")

        assertFalse(ByteArrayInputStream(archive).containsExportFailureMarker())
    }

    private fun zipWithEntry(name: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write("test".toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
