package `in`.shvms.trackme.data.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import `in`.shvms.trackme.domain.notifications.BroadcastTag
import `in`.shvms.trackme.domain.notifications.OperatorBroadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SCOPE_1.8.7 §6.3 — the durable half of a broadcast.
 *
 * A notification is something you swipe away at a traffic light. If the shade were the only place a
 * broadcast lived, "we told everyone" would mean "we told everyone who happened to be looking" —
 * not a claim worth making about a message saying the build someone is running has a defect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BroadcastStoreTest {

    private lateinit var store: BroadcastStore

    private fun broadcast(
        id: String,
        createdAt: Long,
        tag: BroadcastTag = BroadcastTag.URGENT,
        ceiling: Int? = null,
    ) = OperatorBroadcast(
        id = id,
        tag = tag,
        title = "Title $id",
        body = "Body $id",
        createdAtMillis = createdAt,
        appliesToVersionsAtOrBelow = ceiling,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("trackme_broadcasts", 0).edit().clear().commit()
        store = BroadcastStore(context)
    }

    @Test
    fun `a broadcast survives a restart`() {
        store.store(broadcast("a", 100))
        val reopened = BroadcastStore(ApplicationProvider.getApplicationContext())
        assertEquals(listOf("a"), reopened.broadcasts.value.map { it.id })
        assertEquals("Title a", reopened.broadcasts.value.single().title)
    }

    @Test
    fun `the same broadcast arriving twice does not interrupt twice`() {
        // It genuinely arrives twice: once by push, once by the foreground read of the collection.
        // The boolean is what the receiver uses to decide whether to post, so a second true here
        // would re-notify someone about a problem they have already been told about.
        assertTrue(store.store(broadcast("a", 100)))
        assertFalse(store.store(broadcast("a", 100)))
        assertFalse("a re-sent copy with different text is still the same broadcast",
            store.store(broadcast("a", 999)))
        assertEquals(1, store.broadcasts.value.size)
    }

    @Test
    fun `broadcasts are newest first`() {
        store.store(broadcast("old", 100))
        store.store(broadcast("new", 300))
        store.store(broadcast("middle", 200))
        assertEquals(listOf("new", "middle", "old"), store.broadcasts.value.map { it.id })
    }

    @Test
    fun `the store is bounded`() {
        // Operational messages go stale. An unbounded list turns a preference file into a log.
        repeat(BroadcastStore.MAX_RETAINED + 5) { store.store(broadcast("b$it", it.toLong())) }
        assertEquals(BroadcastStore.MAX_RETAINED, store.broadcasts.value.size)
        // The newest are what survive; dropping the newest would be the wrong direction entirely.
        assertEquals("b24", store.broadcasts.value.first().id)
    }

    @Test
    fun `unread respects both what was seen and what applies to this build`() {
        store.store(broadcast("seen", 100))
        store.store(broadcast("unseen", 300))
        store.store(broadcast("not-for-this-build", 400, BroadcastTag.UPDATE, ceiling = 50))

        store.markSeen(100)

        val unread = store.unread(versionCode = 187).map { it.id }
        assertEquals(listOf("unseen"), unread)
    }

    @Test
    fun `markSeen never moves backwards`() {
        store.markSeen(500)
        store.markSeen(100)
        assertEquals(500L, store.lastSeenCreatedAt.value)
    }

    @Test
    fun `a corrupted preference file yields nothing rather than crashing`() {
        // A downgrade, a restore from another build, or a hand-edited prefs file all land here. The
        // app must open; an unreadable broadcast list is not a reason to be unusable.
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("trackme_broadcasts", 0)
            .edit().putString("broadcasts", "{not json").commit()
        assertEquals(emptyList<OperatorBroadcast>(), BroadcastStore(ApplicationProvider.getApplicationContext()).broadcasts.value)
    }

    @Test
    fun `a stored row that no longer satisfies the contract is dropped on read`() {
        // Re-validated on read rather than trusted because we wrote it. The parser is the only
        // thing between a tampered preference file and a HIGH-importance notification, and it has
        // to run on the way out as well as the way in.
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("trackme_broadcasts", 0)
            .edit().putString(
                "broadcasts",
                """[{"id":"x","tag":"PROMO","title":"Half price","body":"b","created_at_millis":1}]""",
            ).commit()
        assertTrue(BroadcastStore(ApplicationProvider.getApplicationContext()).broadcasts.value.isEmpty())
    }
}
