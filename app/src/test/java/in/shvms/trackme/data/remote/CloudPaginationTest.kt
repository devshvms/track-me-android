package `in`.shvms.trackme.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the History "load next 10" paginator.
 *
 * Background: `startTime` is mixed-type in Firestore — Android uploads a Long (Number), iOS
 * uploads a Date (Timestamp). The paginator therefore walks with a DocumentSnapshot cursor
 * instead of a `whereLessThan("startTime", ...)` range filter, which is type-scoped and could
 * never reach an iOS-written ride. These tests pin the page-advance decisions around that walk.
 */
class CloudPaginationTest {

    private val batchSize = 10
    private val maxPages = 25

    @Test
    fun shortPageEndsTheWalk() {
        val outcome = cloudPageOutcome(
            documentsOnPage = 4,
            batchSize = batchSize,
            insertedSoFar = 4,
            pagesFetched = 1,
            maxPagesPerCall = maxPages
        )
        assertEquals(CloudPageResult(insertedCount = 4, reachedEnd = true), outcome)
    }

    @Test
    fun fullPageWithNewRidesStopsButDoesNotClaimTheEnd() {
        val outcome = cloudPageOutcome(
            documentsOnPage = batchSize,
            batchSize = batchSize,
            insertedSoFar = 10,
            pagesFetched = 1,
            maxPagesPerCall = maxPages
        )
        assertEquals(CloudPageResult(insertedCount = 10, reachedEnd = false), outcome)
    }

    @Test
    fun fullPageOfAlreadyLocalRidesKeepsSkippingAhead() {
        // The regression this protects: a page that inserts nothing must not be mistaken for the
        // end of the collection, or one scroll gesture is wasted on a silent no-op.
        val outcome = cloudPageOutcome(
            documentsOnPage = batchSize,
            batchSize = batchSize,
            insertedSoFar = 0,
            pagesFetched = 1,
            maxPagesPerCall = maxPages
        )
        assertNull(outcome)
    }

    @Test
    fun skippingIsBoundedByThePageBudget() {
        val outcome = cloudPageOutcome(
            documentsOnPage = batchSize,
            batchSize = batchSize,
            insertedSoFar = 0,
            pagesFetched = maxPages,
            maxPagesPerCall = maxPages
        )
        assertEquals(CloudPageResult(insertedCount = 0, reachedEnd = false), outcome)
    }

    @Test
    fun budgetExhaustionNeverReportsTheEnd() {
        // reachedEnd latches hasMoreCloudRides to false for the whole session, so it must only be
        // reported when the collection is genuinely exhausted — never because we ran out of budget.
        val outcome = cloudPageOutcome(
            documentsOnPage = batchSize,
            batchSize = batchSize,
            insertedSoFar = 0,
            pagesFetched = maxPages + 5,
            maxPagesPerCall = maxPages
        )
        assertEquals(false, outcome?.reachedEnd)
    }
}
