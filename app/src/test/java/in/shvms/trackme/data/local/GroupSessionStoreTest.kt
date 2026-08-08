package `in`.shvms.trackme.data.local

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SCOPE_1.7.0 §6.1 **B6** — a blocker, not a nicety.
 *
 * `LiveShareState` lives only in memory, so an OS kill leaves a member *invisible to the group
 * with no way back*, and the server-side session alive until its TTL. The sticky service restores
 * the ride; nothing restores the sharing. This store is what fixes that, so the restore path is
 * the thing that has to be right.
 *
 * Robolectric because `SharedPreferences` needs a real `Context` — everything else in this feature
 * is a plain JVM test on purpose.
 */
@RunWith(RobolectricTestRunner::class)
// Same @Config as RideControlAccessibilityComposeTest, for the same two reasons:
//   sdk = 34       — Robolectric refuses compileSdk 36 under this project's Java 17 toolchain
//                    ("Android SDK 36 requires Java 21"). Nothing here is SDK-sensitive.
//   application    — the real TrackMeApp initialises Crashlytics in onCreate, which needs a
//                    FirebaseApp this test has no business standing up. A bare Application keeps
//                    the store the only thing under test.
@Config(application = Application::class, sdk = [34])
class GroupSessionStoreTest {

    private lateinit var context: Context
    private lateinit var store: GroupSessionStore

    private val hourFromNow = System.currentTimeMillis() + 3_600_000L

    private fun record(
        expiresAt: Long = hourFromNow,
        rev: Int = 3,
        isLeader: Boolean = false,
    ) = GroupSessionStore.Record(
        groupId = "11111111-2222-4333-8444-555555555555",
        token = "Zm9vYmFyYmF6cXV4MTIzNA",
        joinCode = "ABC123",
        isLeader = isLeader,
        expiresAtMillis = expiresAt,
        maxMembers = 5,
        rev = rev,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(GroupSessionStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = GroupSessionStore(context)
    }

    @Test
    fun `no session means no record`() {
        assertNull(store.load())
    }

    @Test
    fun `a saved session survives a new store instance`() {
        // The actual B6 scenario: the process died and everything in memory went with it.
        store.save(record(isLeader = true))

        val restored = GroupSessionStore(context).load()
        assertNotNull("session did not survive process death", restored)
        assertEquals("11111111-2222-4333-8444-555555555555", restored!!.groupId)
        assertEquals("Zm9vYmFyYmF6cXV4MTIzNA", restored.token)
        assertEquals("ABC123", restored.joinCode)
        assertEquals(true, restored.isLeader)
        assertEquals(5, restored.maxMembers)
        assertEquals(3, restored.rev)
    }

    @Test
    fun `the token survives, because without it a restored session is blind`() {
        // Worth asserting explicitly: the token is the group's key material, and a restored
        // session without it would be present in the group but unable to decrypt anyone.
        store.save(record())
        assertEquals("Zm9vYmFyYmF6cXV4MTIzNA", GroupSessionStore(context).load()!!.token)
    }

    @Test
    fun `an expired session is discarded, not restored`() {
        // §5.1.2, expiring by default. The relay would 404 it anyway, and holding the token any
        // longer serves nobody.
        store.save(record(expiresAt = System.currentTimeMillis() - 1))
        assertNull(store.load())
    }

    @Test
    fun `discarding an expired session also wipes it from disk`() {
        store.save(record(expiresAt = System.currentTimeMillis() - 1))
        store.load()
        val raw = context.getSharedPreferences(GroupSessionStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("session", null)
        assertNull("the expired token was left on disk", raw)
    }

    @Test
    fun `clear removes the session and the token with it`() {
        store.save(record())
        store.clear()
        assertNull(store.load())
        val raw = context.getSharedPreferences(GroupSessionStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString("session", null)
        assertNull("token left behind after clear", raw)
    }

    @Test
    fun `a corrupt record fails closed rather than restoring half a session`() {
        // A half-parsed session would leave the user believing they are visible when they are
        // not — the worst direction for this feature to be wrong in.
        context.getSharedPreferences(GroupSessionStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("session", "{not json").commit()
        assertNull(store.load())
    }

    @Test
    fun `a record missing a required field fails closed`() {
        context.getSharedPreferences(GroupSessionStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("session", """{"v":1,"groupId":"g"}""").commit()
        assertNull(store.load())
    }

    @Test
    fun `a record from a different schema version is discarded, not guessed`() {
        context.getSharedPreferences(GroupSessionStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("session", """{"v":99,"groupId":"g","token":"t","expiresAt":${hourFromNow}}""")
            .commit()
        assertNull(store.load())
    }

    @Test
    fun `updateRev persists without disturbing the rest of the session`() {
        store.save(record(rev = 3))
        store.updateRev(9)

        val restored = GroupSessionStore(context).load()!!
        assertEquals(9, restored.rev)
        assertEquals("Zm9vYmFyYmF6cXV4MTIzNA", restored.token)
        assertEquals("ABC123", restored.joinCode)
    }

    @Test
    fun `updateRev on no session is a no-op rather than a crash`() {
        store.updateRev(4)
        assertNull(store.load())
    }

    @Test
    fun `saving twice replaces rather than accumulating`() {
        store.save(record(rev = 1))
        store.save(record(rev = 2).copy(groupId = "22222222-2222-4333-8444-555555555555"))
        val restored = store.load()!!
        assertEquals("22222222-2222-4333-8444-555555555555", restored.groupId)
        assertEquals(2, restored.rev)
    }

    @Test
    fun `group state lives in its own prefs file`() {
        // So it is trivial to inspect and to clear, and so wiping a group can never disturb ride
        // or settings state.
        store.save(record())
        val main = context.getSharedPreferences("trackme_prefs", Context.MODE_PRIVATE)
        assertNull("group session leaked into the main prefs file", main.getString("session", null))
    }
}
