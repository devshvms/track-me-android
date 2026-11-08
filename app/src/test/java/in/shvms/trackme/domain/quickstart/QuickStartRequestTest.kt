package `in`.shvms.trackme.domain.quickstart

import `in`.shvms.trackme.domain.model.RidePersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK-357. A shortcut may never start a ride a normal tap would not, nor fire late or twice. */
class QuickStartRequestTest {
    private val now = 1_700_000_000_000L

    @Test fun `a well formed request parses`() {
        val r = QuickStartRequest.parse(QuickStartRequest.ACTION, "CAR_DRIVE", "SHORTCUT", now)
        assertNotNull(r)
        assertEquals(RidePersona.CAR_DRIVE, r!!.persona)
        assertEquals(QuickStartRequest.Source.SHORTCUT, r.source)
        assertEquals(now, r.createdAtMillis)
    }

    @Test fun `a foreign action is ignored`() {
        assertNull(QuickStartRequest.parse("android.intent.action.MAIN", "WALK", "TILE", now))
        assertNull(QuickStartRequest.parse(null, "WALK", "TILE", now))
    }

    @Test fun `unknown or missing persona or source never starts anything`() {
        assertNull(QuickStartRequest.parse(QuickStartRequest.ACTION, "TELEPORT", "TILE", now))
        assertNull(QuickStartRequest.parse(QuickStartRequest.ACTION, null, "TILE", now))
        assertNull(QuickStartRequest.parse(QuickStartRequest.ACTION, "WALK", "NOPE", now))
        assertNull(QuickStartRequest.parse(QuickStartRequest.ACTION, "WALK", null, now))
    }

    @Test fun `freshness window is thirty seconds and never negative`() {
        val r = QuickStartRequest(RidePersona.WALK, QuickStartRequest.Source.TILE, now)
        assertTrue(r.isFresh(now))
        assertTrue(r.isFresh(now + QuickStartRequest.TTL_MILLIS))
        assertFalse(r.isFresh(now + QuickStartRequest.TTL_MILLIS + 1))
        assertFalse("clock moved backwards", r.isFresh(now - 1))
    }

    @Test fun `home starts only a fresh request while idle`() {
        val r = QuickStartRequest(RidePersona.RUN, QuickStartRequest.Source.SHORTCUT, now)
        assertTrue(QuickStartRequest.shouldStart(r, now + 1_000, isTrackingIdle = true))
        assertFalse("ride already active", QuickStartRequest.shouldStart(r, now + 1_000, isTrackingIdle = false))
        assertFalse("stale", QuickStartRequest.shouldStart(r, now + 31_000, isTrackingIdle = true))
        assertFalse("none", QuickStartRequest.shouldStart(null, now, isTrackingIdle = true))
    }

    @Test fun `shortcut personas are real modes, unique, and at most four`() {
        val p = QuickStartRequest.SHORTCUT_PERSONAS
        assertTrue(p.size in 1..4)
        assertEquals(p.size, p.toSet().size)
        assertFalse(p.contains(RidePersona.AUTO))
    }

    @Test fun `source analytics values are stable`() {
        assertEquals("shortcut", QuickStartRequest.Source.SHORTCUT.analyticsValue)
        assertEquals("tile", QuickStartRequest.Source.TILE.analyticsValue)
    }
}
