package `in`.shvms.trackme.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPreviewDialogTest {
    @Test
    fun portraitPreviewIsBoundedWithoutChangingAspectRatio() {
        val size = boundedPreviewSize(maxWidth = 360f, maxHeight = 420f, ratio = 9f / 16f)

        assertTrue(size.width <= 360f)
        assertTrue(size.height <= 420f)
        assertEquals(9f / 16f, size.width / size.height, 0.0001f)
    }

    @Test
    fun landscapePreviewUsesAvailableWidth() {
        val size = boundedPreviewSize(maxWidth = 360f, maxHeight = 420f, ratio = 16f / 9f)

        assertEquals(360f, size.width, 0.0001f)
        assertEquals(16f / 9f, size.width / size.height, 0.0001f)
    }
}
