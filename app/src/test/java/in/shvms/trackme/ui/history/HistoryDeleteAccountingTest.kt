package `in`.shvms.trackme.ui.history

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryDeleteAccountingTest {
    @Test
    fun summarizeBatchDeleteReportsCloudAndLocalFailures() {
        val summary = summarizeBatchDelete(
            listOf(
                RideDeleteAttempt(localDeleted = true, cloudDeleted = true),
                RideDeleteAttempt(localDeleted = true, cloudDeleted = false),
                RideDeleteAttempt(localDeleted = false, cloudDeleted = false)
            )
        )

        assertEquals(BatchDeleteSummary(deletedCount = 1, failedCount = 2), summary)
    }

    @Test
    fun summarizeBatchDeleteHandlesEmptySelection() {
        assertEquals(BatchDeleteSummary(deletedCount = 0, failedCount = 0), summarizeBatchDelete(emptyList()))
    }
}
