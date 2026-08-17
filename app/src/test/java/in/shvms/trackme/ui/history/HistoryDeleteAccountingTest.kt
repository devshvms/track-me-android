package `in`.shvms.trackme.ui.history

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryDeleteAccountingTest {
    @Test
    fun cloudDocumentIdUsesDownloadedDocumentId() {
        assertEquals("ios-cloud-document", cloudDocumentIdForDelete(42L, "ios-cloud-document", true))
    }

    @Test
    fun cloudDocumentIdFallsBackForLegacySyncedRide() {
        assertEquals("42", cloudDocumentIdForDelete(42L, null, true))
    }

    @Test
    fun cloudDocumentIdIsAbsentForLocalOnlyRide() {
        assertEquals(null, cloudDocumentIdForDelete(42L, null, false))
    }

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
