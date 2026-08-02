package `in`.shvms.trackme.ui.history

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryImageSaverTest {
    @Test
    fun `document launch failure is reported instead of thrown`() {
        val launched = tryLaunchGalleryDocument {
            throw IllegalStateException("no document provider")
        }

        assertFalse(launched)
    }

    @Test
    fun `document launch success is reported`() {
        assertTrue(tryLaunchGalleryDocument { })
    }

    @Test
    fun `media store security failure is reported instead of thrown`() {
        val writer = GalleryImageWriter { _, _, _ -> throw SecurityException("provider denied insert") }

        val saved = saveImageToGallery(
            imageFile = File("unused.png"),
            nameHint = "Ride",
            sdkInt = 29,
            writer = writer,
            nowMillis = 1_000L
        )

        assertFalse(saved)
    }

    @Test
    fun `pre Q routing never invokes media store writer`() {
        var writeCalls = 0
        val writer = GalleryImageWriter { _, _, _ -> writeCalls += 1 }

        val saved = saveImageToGallery(
            imageFile = File("unused.png"),
            nameHint = "Ride",
            sdkInt = 24,
            writer = writer,
            nowMillis = 1_000L
        )

        assertFalse(saved)
        assertEquals(0, writeCalls)
    }
}
