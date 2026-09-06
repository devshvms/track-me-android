package `in`.shvms.trackme.domain.notifications

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SCOPE_1.8.7 §6.0 — the interruption budget, proved against the frozen cross-platform vectors.
 *
 * The vectors are the contract, not this file: `notification-budget-v1.json` is canonical in
 * `track-me-web/tests/fixtures` and copied verbatim to both clients. A divergence here is invisible
 * until it is a review — one platform notifying weekly where the other notifies daily is the
 * difference between a differentiator and an uninstall, and neither platform's own test suite would
 * notice on its own.
 */
class NotificationBudgetVectorsTest {

    private val vectors: JSONObject =
        JSONObject(File("src/test/resources/notification-budget-v1.json").readText())

    private fun klass(id: String) = NotificationBudget.Klass.valueOf(id)
    private fun kind(id: String) = NotificationBudget.ProactiveKind.valueOf(id)
    private fun longOrNull(o: JSONObject, key: String): Long? =
        if (o.isNull(key)) null else o.getLong(key)

    @Test
    fun `the constants in the vector file are the constants in the code`() {
        // The vectors carry the intervals so the other platform cannot quietly pick its own. If
        // this drifts, every timing case below still passes on each platform separately.
        val constants = vectors.getJSONObject("constants")
        assertEquals(
            constants.getLong("proactive_interval_millis"),
            NotificationBudget.PROACTIVE_INTERVAL_MILLIS,
        )
        assertEquals(
            constants.getLong("return_notice_interval_millis"),
            NotificationBudget.RETURN_NOTICE_INTERVAL_MILLIS,
        )
    }

    @Test
    fun `every class declares the same budget participation on both platforms`() {
        val classes = vectors.getJSONArray("classes")
        assertEquals(NotificationBudget.Klass.entries.size, classes.length())
        for (i in 0 until classes.length()) {
            val vector = classes.getJSONObject(i)
            val k = klass(vector.getString("id"))
            assertEquals(
                "${k.name} spends_proactive_budget",
                vector.getBoolean("spends_proactive_budget"),
                k.spendsProactiveBudget,
            )
        }
    }

    @Test
    fun `allows matches every vector`() {
        val cases = vectors.getJSONArray("allows")
        assertTrue("the vector file lost its allows cases", cases.length() >= 9)
        for (i in 0 until cases.length()) {
            val vector = cases.getJSONObject(i)
            val actual = NotificationBudget.allows(
                klass = klass(vector.getString("class")),
                nowMillis = vector.getLong("now"),
                lastProactiveSentAtMillis = longOrNull(vector, "last_proactive_at"),
            )
            assertEquals(
                vector.optString("note", vector.getString("class")),
                vector.getBoolean("expected"),
                actual,
            )
        }
    }

    @Test
    fun `the ledger moves only for proactive sends, and never backwards`() {
        val cases = vectors.getJSONArray("ledger")
        assertTrue(cases.length() >= 6)
        for (i in 0 until cases.length()) {
            val vector = cases.getJSONObject(i)
            val actual = NotificationBudget.recordSent(
                klass = klass(vector.getString("class")),
                sentAtMillis = vector.getLong("sent_at"),
                lastProactiveSentAtMillis = longOrNull(vector, "last_proactive_at_before"),
            )
            assertEquals(
                vector.getString("description"),
                longOrNull(vector, "expected_last_proactive_at_after"),
                actual,
            )
        }
    }

    @Test
    fun `the declared priority order matches the vector ranks`() {
        val ranks = vectors.getJSONArray("proactive_priority")
        assertEquals(NotificationBudget.ProactiveKind.entries.size, ranks.length())
        for (i in 0 until ranks.length()) {
            val vector = ranks.getJSONObject(i)
            assertEquals(
                "rank of ${vector.getString("id")}",
                vector.getInt("rank"),
                kind(vector.getString("id")).ordinal,
            )
        }
    }

    @Test
    fun `choose matches every vector, whatever order the eligible set arrives in`() {
        val cases = vectors.getJSONArray("choose")
        for (i in 0 until cases.length()) {
            val vector = cases.getJSONObject(i)
            val eligibleArray = vector.getJSONArray("eligible")
            val eligible = buildSet {
                for (j in 0 until eligibleArray.length()) add(kind(eligibleArray.getString(j)))
            }
            val actual = NotificationBudget.choose(eligible)
            if (vector.isNull("expected")) {
                assertNull(vector.optString("note", "case $i"), actual)
            } else {
                assertEquals(
                    vector.optString("note", "case $i"),
                    kind(vector.getString("expected")),
                    actual,
                )
            }
        }
    }

    @Test
    fun `the return notice matches every vector`() {
        val cases = vectors.getJSONArray("return_notice")
        assertTrue(cases.length() >= 6)
        for (i in 0 until cases.length()) {
            val vector = cases.getJSONObject(i)
            val actual = NotificationBudget.allowsReturnNotice(
                nowMillis = vector.getLong("now"),
                lastReturnNoticeAtMillis = longOrNull(vector, "last_return_notice_at"),
                daysSinceLastActivity = vector.getInt("days_since_activity"),
            )
            assertEquals(
                vector.optString("note", "case $i"),
                vector.getBoolean("expected"),
                actual,
            )
        }
    }

    @Test
    fun `a refused proactive notification is not consumed`() {
        // The property the whole cap rests on, and the one no single vector states: querying the
        // budget must never change it. A cap that lost notifications instead of deferring them
        // would make one-per-week a real restriction rather than an honest one.
        val ledger = 1_000L
        repeat(5) {
            NotificationBudget.allows(NotificationBudget.Klass.PROACTIVE, 1_500L, ledger)
        }
        assertEquals(
            ledger,
            NotificationBudget.recordSent(NotificationBudget.Klass.OPERATOR, 9_999L, ledger),
        )
        assertTrue(
            "the week must still open on schedule after any number of refusals",
            NotificationBudget.allows(
                NotificationBudget.Klass.PROACTIVE,
                ledger + NotificationBudget.PROACTIVE_INTERVAL_MILLIS,
                ledger,
            ),
        )
    }

    @Test
    fun `an operator broadcast can never mute the product's own voice`() {
        // §6.3: Class D sits outside the C budget in both directions. If a broadcast advanced the
        // ledger, "send a broadcast" would become a lever on engagement — which is exactly what
        // the promotional ban exists to prevent, expressed as arithmetic rather than as a rule.
        var ledger: Long? = null
        repeat(10) { ledger = NotificationBudget.recordSent(NotificationBudget.Klass.OPERATOR, 5_000L, ledger) }
        assertNull(ledger)
        assertTrue(NotificationBudget.allows(NotificationBudget.Klass.PROACTIVE, 5_000L, ledger))
    }
}
