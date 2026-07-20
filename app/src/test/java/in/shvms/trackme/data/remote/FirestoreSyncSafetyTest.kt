package `in`.shvms.trackme.data.remote

import `in`.shvms.trackme.utils.logger.ErrorLogger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreSyncSafetyTest {

    private class RecordingErrorLogger : ErrorLogger {
        val messages = mutableListOf<String>()
        val exceptions = mutableListOf<Throwable>()
        override fun init() {}
        override fun setUserId(userId: String?) {}
        override fun setCustomKey(key: String, value: String) {}
        override fun recordException(throwable: Throwable) {
            exceptions.add(throwable)
        }
        override fun log(message: String) {
            messages.add(message)
        }
    }

    @Test
    fun launchSyncTaskSwallowsFailuresInsteadOfCrashing() = runTest {
        // v1.5.11 P0: "Not signed in" thrown out of a bare syncScope.launch killed
        // the process. If launchSyncTask stops swallowing, the exception propagates
        // to the test scope and this test fails.
        val logger = RecordingErrorLogger()

        launchSyncTask(logger, "uploadRide") {
            throw IllegalStateException("Not signed in")
        }.join()

        assertTrue(logger.messages.any { it.contains("uploadRide") && it.contains("Not signed in") })
    }

    @Test
    fun launchSyncTaskRunsTheTaskAndLogsNothingOnSuccess() = runTest {
        val logger = RecordingErrorLogger()
        var ran = false

        launchSyncTask(logger, "uploadRide") { ran = true }.join()

        assertTrue(ran)
        assertEquals(emptyList<String>(), logger.messages)
    }

    @Test
    fun launchSyncTaskDoesNotTreatCancellationAsFailure() = runTest {
        val logger = RecordingErrorLogger()

        val job = launchSyncTask(logger, "uploadRide") { awaitCancellation() }
        testScheduler.runCurrent()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(emptyList<String>(), logger.messages)
    }
}
