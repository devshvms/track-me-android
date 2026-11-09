package `in`.shvms.trackme.ui.history

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import `in`.shvms.trackme.analytics.ExportArtifactKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ExportSharedReceiverTest {

    @Test
    fun resolveShareChannel_mapsKnownPackagesCoarsely() {
        assertEquals("whatsapp", ExportSharedReceiver.resolveShareChannel("com.whatsapp"))
        assertEquals("whatsapp", ExportSharedReceiver.resolveShareChannel("com.whatsapp.w4b"))
        assertEquals("instagram", ExportSharedReceiver.resolveShareChannel("com.instagram.android"))
        assertEquals("telegram", ExportSharedReceiver.resolveShareChannel("org.telegram.messenger"))
        assertEquals("telegram", ExportSharedReceiver.resolveShareChannel("org.thunderdog.challegram"))
        assertEquals("x", ExportSharedReceiver.resolveShareChannel("com.twitter.android"))
        assertEquals("x", ExportSharedReceiver.resolveShareChannel("com.x.android"))
        assertEquals("strava", ExportSharedReceiver.resolveShareChannel("com.strava"))
        assertEquals("facebook", ExportSharedReceiver.resolveShareChannel("com.facebook.katana"))
        assertEquals("sms", ExportSharedReceiver.resolveShareChannel("com.google.android.apps.messaging"))
        assertEquals("email", ExportSharedReceiver.resolveShareChannel("com.google.android.gm"))
        assertEquals("other", ExportSharedReceiver.resolveShareChannel("com.some.random.app"))
        assertEquals("unknown", ExportSharedReceiver.resolveShareChannel(null))
        assertEquals("unknown", ExportSharedReceiver.resolveShareChannel(""))
    }

    @Test
    fun onReceive_handlesValidIntentWithoutCrash() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val receiver = ExportSharedReceiver()
        val intent = Intent(ExportSharedReceiver.ACTION).apply {
            putExtra(ExportSharedReceiver.EXTRA_KIND, ExportArtifactKind.IMAGE.value)
            putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName("com.whatsapp", "com.whatsapp.ContactPicker"))
        }

        receiver.onReceive(context, intent)
    }

    @Test
    fun onReceive_ignoresInvalidActionOrKind() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val receiver = ExportSharedReceiver()

        // Wrong action
        receiver.onReceive(context, Intent("wrong.action"))

        // Wrong kind
        val badKindIntent = Intent(ExportSharedReceiver.ACTION).apply {
            putExtra(ExportSharedReceiver.EXTRA_KIND, "invalid_kind")
        }
        receiver.onReceive(context, badKindIntent)
    }
}
