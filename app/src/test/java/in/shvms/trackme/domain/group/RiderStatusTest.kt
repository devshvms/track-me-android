package `in`.shvms.trackme.domain.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCOPE_1.7.2 §4.2, amendment **A25** — the status code grammar and its fallbacks.
 *
 * Every fallback row in §4.2's parser table gets a case here, because the fallbacks *are* the design:
 * the encoding exists so that an unknown code degrades to the right colour and priority rather than
 * disappearing.
 *
 * Pure — no Robolectric, no Android.
 */
class RiderStatusTest {

    // --- The grammar ----------------------------------------------------------------------------

    @Test
    fun `a well-formed code parses into its four parts`() {
        val status = RiderStatusCodec.parse("2MEH")
        assertNotNull(status)
        assertEquals("2MEH", status!!.code)
        assertEquals(StatusSeverity.CAUTION, status.severity)
        assertEquals(StatusPersona.BIKE_DRIVE, status.persona)
        assertEquals("EH", status.message)
        assertNull(status.extension)
    }

    @Test
    fun `an extension is parsed and preserved, and is not part of the code`() {
        // D7's "structure now, display later": no consumer in 1.7.2, but the grammar is proven so a
        // later release does not need a breaking wire change.
        val status = RiderStatusCodec.parse("2MEH:T15")!!
        assertEquals("2MEH", status.code)
        assertEquals("T15", status.extension)
        assertEquals("2MEH:T15", status.raw)
    }

    @Test
    fun `malformed codes are ignored entirely rather than guessed`() {
        // §6.2 H9 — this runs every ~10s for hours. A guessed status is worse than none.
        listOf(
            null, "", "   ", "2ME", "2MEHX", "meh", "2meh", "22EH", "2M3H",
            "2MEH:", "2MEH:!!", "2MEH:TOOLONGEXT", "2MEH::T1", " 2MEH,",
        ).forEach {
            assertNull("expected null for '$it'", RiderStatusCodec.parse(it))
        }
    }

    @Test
    fun `whitespace is rejected, because the wire format is ours end to end`() {
        // We seal this field ourselves at the other end, so padding has no legitimate source.
        // Accepting it would only hide the bug that produced it — and leniency is how a format you
        // own starts drifting.
        assertNull(RiderStatusCodec.parse(" 2MEH"))
        assertNull(RiderStatusCodec.parse("2MEH "))
        assertNull(RiderStatusCodec.parse("2M EH"))
    }

    // --- The fallbacks, which are the whole point (§4.2) -----------------------------------------

    @Test
    fun `an unknown message still renders at the correct severity and persona`() {
        // A 1.7.3 client sends a code this build has never heard of. It must not be dropped.
        val status = RiderStatusCodec.parse("2MOH")!!
        assertEquals(StatusSeverity.CAUTION, status.severity)
        assertEquals(StatusPersona.BIKE_DRIVE, status.persona)
        assertFalse("no specific label exists for it", status.isKnown)
    }

    @Test
    fun `an unknown persona does not blank out a valid alert`() {
        val status = RiderStatusCodec.parse("1ZNH")!!
        assertEquals(StatusSeverity.ALERT, status.severity)
        assertNull(status.persona)
        assertTrue(status.isAlert)
    }

    @Test
    fun `an unknown severity fails quiet, never loud`() {
        // Digits 0 and 4-9 are reserved. `0` is reserved for a tier ABOVE alert — and it must STILL
        // demote to INFO here, because an unrecognised tier must never be able to make an old client
        // scream.
        listOf("0GNH", "4GNH", "9GNH").forEach { code ->
            val status = RiderStatusCodec.parse(code)!!
            assertEquals("$code must not be promoted", StatusSeverity.INFO, status.severity)
            assertFalse("$code must not alert", status.isAlert)
        }
    }

    // --- Severity ordering ----------------------------------------------------------------------

    @Test
    fun `severity digits sort ascending in severity order`() {
        // A31's attention-section pinning is a plain string comparison, and depends on this.
        assertTrue(StatusSeverity.ALERT.digit < StatusSeverity.CAUTION.digit)
        assertTrue(StatusSeverity.CAUTION.digit < StatusSeverity.INFO.digit)

        val sorted = listOf("3GTI", "1GNH", "2GVI")
            .mapNotNull { RiderStatusCodec.parse(it) }
            .sortedWith(RiderStatusCatalog.BY_SEVERITY)
            .map { it.code }
        assertEquals(listOf("1GNH", "2GVI", "3GTI"), sorted)
    }

    // --- Labels -----------------------------------------------------------------------------------

    @Test
    fun `the label key is derived from the code, so the wire never carries display text`() {
        // Otherwise a Hindi rider's status renders as Hindi on a German rider's phone — a failure
        // that only shows up in a mixed-locale group.
        assertEquals("groupStatus2MEH", RiderStatusCodec.parse("2MEH")!!.labelKey)
    }

    @Test
    fun `every catalogue code parses and is marked known`() {
        RiderStatusCatalog.KNOWN_CODES.forEach { code ->
            val status = RiderStatusCodec.parse(code)
            assertNotNull("catalogue code $code must parse", status)
            assertTrue("catalogue code $code must be known", status!!.isKnown)
        }
    }

    // --- The picker offer (§3.3) ------------------------------------------------------------------

    @Test
    fun `every persona is offered four to six options, always visible without scrolling`() {
        (StatusPersona.entries + null).forEach { persona ->
            val options = RiderStatusCatalog.optionsFor(persona)
            assertTrue(
                "$persona offered ${options.size} options, expected 4..6",
                options.size in 4..6,
            )
        }
    }

    @Test
    fun `no persona is offered more than two alert options`() {
        (StatusPersona.entries + null).forEach { persona ->
            val alerts = RiderStatusCatalog.optionsFor(persona)
                .mapNotNull { RiderStatusCodec.parse(it) }
                .count { it.isAlert }
            assertTrue("$persona offered $alerts alert options, expected <= 2", alerts <= 2)
        }
    }

    @Test
    fun `alert options come last, because mis-tap cost orders tapping`() {
        // Deliberately the opposite of display order: severity 1 sorts FIRST when displayed (the
        // attention section) and LAST when offered.
        (StatusPersona.entries + null).forEach { persona ->
            val severities = RiderStatusCatalog.optionsFor(persona)
                .mapNotNull { RiderStatusCodec.parse(it) }
                .map { it.severity.digit }
            assertEquals(
                "$persona's options must be ordered least-severe first",
                severities.sortedDescending(),
                severities,
            )
        }
    }

    @Test
    fun `walkers and runners are not offered Crashed`() {
        listOf(StatusPersona.WALK, StatusPersona.RUN).forEach { persona ->
            assertFalse(
                "$persona should not be offered Crashed",
                RiderStatusCatalog.CRASHED in RiderStatusCatalog.optionsFor(persona),
            )
        }
    }

    @Test
    fun `a member with no ride sees the core set only`() {
        val options = RiderStatusCatalog.optionsFor(null)
        assertTrue(options.all { RiderStatusCodec.parse(it)!!.persona == StatusPersona.GENERIC })
    }

    @Test
    fun `every offered option is a known code`() {
        (StatusPersona.entries + null).forEach { persona ->
            RiderStatusCatalog.optionsFor(persona).forEach { code ->
                assertTrue("$code offered to $persona but has no label", code in RiderStatusCatalog.KNOWN_CODES)
            }
        }
    }

    // --- Encoding ----------------------------------------------------------------------------------

    @Test
    fun `encoding round-trips through the parser`() {
        assertEquals("2MEH", RiderStatusCodec.encode("2MEH"))
        assertEquals("2MEH:T15", RiderStatusCodec.encode("2MEH", "T15"))
        assertEquals("2MEH", RiderStatusCodec.encode("2MEH", "  "))
        assertEquals("2MEH:T15", RiderStatusCodec.parse(RiderStatusCodec.encode("2MEH", "T15"))!!.raw)
    }
}
