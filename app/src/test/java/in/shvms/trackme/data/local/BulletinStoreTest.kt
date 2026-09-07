package `in`.shvms.trackme.data.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import `in`.shvms.trackme.domain.bulletin.BulletinEntry
import `in`.shvms.trackme.domain.bulletin.BulletinKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SCOPE_1.8.7 §6.1.7 — the bulletin's storage.
 *
 * The bulletin is what makes §6.0's one-per-week notification cap survivable: everything the budget
 * refuses lands here instead. If the feed loses rows, duplicates them, or lies about what is
 * unread, the cap stops being a trade and becomes a loss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BulletinStoreTest {

    private lateinit var store: BulletinStore

    private fun entry(id: String, at: Long, kind: BulletinKind = BulletinKind.WEEKLY_RECAP) =
        BulletinEntry(
            id = id,
            kind = kind,
            createdAtMillis = at,
            facts = mapOf(BulletinEntry.FACT_RIDE_COUNT to "3"),
        )

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("trackme_bulletin", 0).edit().clear().commit()
        store = BulletinStore(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `entries survive a restart, with their facts`() {
        store.add(entry("a", 100))
        val reopened = BulletinStore(ApplicationProvider.getApplicationContext())
        assertEquals(listOf("a"), reopened.entries.value.map { it.id })
        assertEquals("3", reopened.entries.value.single().fact(BulletinEntry.FACT_RIDE_COUNT))
    }

    @Test
    fun `the same fact arriving twice appears once`() {
        // A broadcast arrives by push AND by the foreground reconcile; a recap is notified AND read
        // in-app. A duplicated row would make the unread badge lie, and the badge is the only thing
        // that makes an un-notified fact discoverable at all.
        assertTrue(store.add(entry("a", 100)))
        assertFalse(store.add(entry("a", 100)))
        assertFalse(store.add(entry("a", 999)))
        assertEquals(1, store.entries.value.size)
    }

    @Test
    fun `the feed is newest first`() {
        store.add(entry("old", 100))
        store.add(entry("new", 300))
        store.add(entry("middle", 200))
        assertEquals(listOf("new", "middle", "old"), store.entries.value.map { it.id })
    }

    @Test
    fun `the feed is capped and keeps the newest`() {
        // A bulletin that grows forever becomes a log nobody scrolls, and the facts at the bottom
        // are ones the user could not act on months ago either.
        repeat(BulletinEntry.MAX_RETAINED + 10) { store.add(entry("e$it", it.toLong())) }
        assertEquals(BulletinEntry.MAX_RETAINED, store.entries.value.size)
        assertEquals("e59", store.entries.value.first().id)
    }

    @Test
    fun `everything is unread until the bulletin is opened`() {
        store.add(entry("a", 100))
        store.add(entry("b", 200))
        assertEquals(2, store.unread().size)

        store.markAllSeen()
        assertEquals(0, store.unread().size)

        // ...and a later fact is unread again.
        store.add(entry("c", 300))
        assertEquals(listOf("c"), store.unread().map { it.id })
    }

    @Test
    fun `markAllSeen never moves backwards`() {
        store.add(entry("new", 500))
        store.markAllSeen()
        store.add(entry("backdated", 100))
        // A row that arrives with an older timestamp than what was already seen must not reopen
        // the badge — a reconcile that back-fills history would otherwise mark the feed unread.
        assertEquals(0, store.unread().size)
    }

    @Test
    fun `clearing empties the feed and the badge`() {
        store.add(entry("a", 100))
        store.clear()
        assertTrue(store.entries.value.isEmpty())
        assertEquals(0, store.unread().size)
        assertTrue(BulletinStore(ApplicationProvider.getApplicationContext()).entries.value.isEmpty())
    }

    @Test
    fun `a corrupted feed yields nothing rather than crashing`() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("trackme_bulletin", 0)
            .edit().putString("entries", "{not json").commit()
        assertTrue(BulletinStore(ApplicationProvider.getApplicationContext()).entries.value.isEmpty())
    }

    @Test
    fun `a row with an unknown kind is dropped on read`() {
        // A downgrade after a future release added a kind. The row is meaningless to this build and
        // rendering it would be a blank line in the feed.
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("trackme_bulletin", 0)
            .edit().putString(
                "entries",
                """[{"id":"x","kind":"FROM_THE_FUTURE","created_at_millis":1,"facts":{}}]""",
            ).commit()
        assertTrue(BulletinStore(ApplicationProvider.getApplicationContext()).entries.value.isEmpty())
    }
}
