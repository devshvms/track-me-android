package `in`.shvms.trackme.ui.home

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import `in`.shvms.trackme.domain.stats.WeeklyRecap
import `in`.shvms.trackme.ui.localization.getAppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeeklyRecapCardRendererTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val strings = getAppStrings("en")

    @Test
    fun render_standardCard_creates1080SquareBitmap() {
        val recap = WeeklyRecap(
            weekKey = "2026-W45",
            weekStartEpochDay = 20760L,
            rideCount = 5,
            distanceMeters = 42_800.0,
            streakWeeks = 3
        )

        val bitmap = WeeklyRecapCardRenderer.render(
            context = context,
            recap = recap,
            strings = strings,
            imperial = false
        )

        assertNotNull(bitmap)
        assertEquals(1080, bitmap.width)
        assertEquals(1080, bitmap.height)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
    }

    @Test
    fun render_scaledCard_createsProportionalDimensions() {
        val recap = WeeklyRecap(
            weekKey = "2026-W45",
            weekStartEpochDay = 20760L,
            rideCount = 2,
            distanceMeters = 15_000.0,
            streakWeeks = 1
        )

        val bitmap = WeeklyRecapCardRenderer.render(
            context = context,
            recap = recap,
            strings = strings,
            imperial = true,
            widthPx = 540
        )

        assertNotNull(bitmap)
        assertEquals(540, bitmap.width)
        assertEquals(540, bitmap.height)
    }

    @Test
    fun writePng_createsValidPngFile() {
        val recap = WeeklyRecap(
            weekKey = "2026-W45",
            weekStartEpochDay = 20760L,
            rideCount = 4,
            distanceMeters = 25_000.0,
            streakWeeks = 0
        )

        val bitmap = WeeklyRecapCardRenderer.render(
            context = context,
            recap = recap,
            strings = strings,
            imperial = false
        )

        val file = WeeklyRecapCardRenderer.writePng(context, bitmap, recap.weekKey)
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
        assertTrue(file.name.contains("2026-W45"))
    }
}
