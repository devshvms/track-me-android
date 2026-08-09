package `in`.shvms.trackme.utils.logger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException as KotlinxCancellation

/**
 * What reaches Crashlytics as a non-fatal — v1.7.1 Crashlytics fix.
 *
 * Crashlytics was collecting `JobCancellationException` on every navigation. The danger of that
 * kind of noise is not volume, it is that it teaches everyone to ignore the non-fatal list — so
 * the tests that matter most here are the ones proving a *real* failure still gets through.
 *
 * [isReportable] is a pure function precisely so this can be a plain JVM test: the alternative is
 * asserting against `FirebaseCrashlytics.getInstance()`, which needs a live Firebase and would
 * verify the mock rather than the rule.
 */
class ErrorLoggerFilterTest {

    @Test
    fun `a cancelled coroutine is not a fault`() {
        // The reported noise. kotlinx's JobCancellationException is internal, so this constructs
        // the public supertype the filter actually keys on.
        assertFalse(isReportable(KotlinxCancellation("StandaloneCoroutine was cancelled")))
        assertFalse(isReportable(java.util.concurrent.CancellationException("cancelled")))
        assertFalse(isReportable(kotlin.coroutines.cancellation.CancellationException("cancelled")))
    }

    @Test
    fun `real failures still get through`() {
        // The point of the filter is a list worth reading, so this is the half that matters.
        val realFailures = listOf(
            IOException("relay unreachable"),
            IllegalStateException("service started without a session"),
            NullPointerException("CameraUpdateFactory is not initialized"),
            SecurityException("location permission revoked"),
            RuntimeException("unexpected"),
        )
        for (failure in realFailures) {
            assertTrue(
                "${failure.javaClass.simpleName} was dropped — the non-fatal list must keep real bugs",
                isReportable(failure),
            )
        }
    }

    @Test
    fun `a real failure that merely has a cancellation underneath is still reported`() {
        // Only the throwable itself is examined. Walking the cause chain would drop a genuine
        // failure because of what is underneath it, which is exactly the bug this list exists for.
        val wrapped = IllegalStateException("upload aborted", KotlinxCancellation("job cancelled"))
        assertTrue(isReportable(wrapped))
    }

    @Test
    fun `a cancellation subclass is dropped too`() {
        // JobCancellationException is itself a subclass; the filter must be an `is` check rather
        // than an exact-class comparison, or the one exception it was written for slips past.
        class CustomCancellation : KotlinxCancellation("custom")
        assertFalse(isReportable(CustomCancellation()))
    }

    @Test
    fun `the Crashlytics logger applies the filter rather than reimplementing it`() {
        // A second copy of this rule would drift. The logger is the only choke point — nothing in
        // the app calls FirebaseCrashlytics.recordException directly — so this is the one place
        // the filter has to be honoured.
        val source = sourceFile("utils/logger/CrashlyticsErrorLogger.kt").readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//.*"), "")
        assertTrue(
            "CrashlyticsErrorLogger.recordException must consult isReportable",
            source.contains("isReportable(throwable)"),
        )
    }

    private fun sourceFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        val rel = "app/src/main/java/in/shvms/trackme/$relative"
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it }
            File(dir, rel.removePrefix("app/")).takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found")
    }
}
