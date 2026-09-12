package `in`.shvms.trackme.domain.export.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SCOPE_1.8.9 §7 and §11 gate 3 — a place label is never a finer disclosure than the trimmed route. */
class PlaceLabelPolicyTest {

    @Test
    fun `the neighbourhood is preferred and coarser names are the fallback`() {
        assertEquals("Koramangala", PlaceLabelPolicy.label(PlaceParts(subLocality = "Koramangala", locality = "Bengaluru")))
        assertEquals("Bengaluru", PlaceLabelPolicy.label(PlaceParts(locality = "Bengaluru", adminArea = "Karnataka")))
        assertEquals("Karnataka", PlaceLabelPolicy.label(PlaceParts(adminArea = "Karnataka")))
    }

    @Test
    fun `a sub-locality that is really the street is rejected`() {
        val parts = PlaceParts(subLocality = "80 Feet Road", locality = "Bengaluru", thoroughfare = "80 feet road")
        assertEquals("Bengaluru", PlaceLabelPolicy.label(parts))
    }

    @Test
    fun `the street itself is never a candidate`() {
        assertNull(PlaceLabelPolicy.label(PlaceParts(thoroughfare = "MG Road")))
    }

    @Test
    fun `blank parts are nothing, and a long name is shortened rather than overflowing`() {
        assertNull(PlaceLabelPolicy.label(PlaceParts(subLocality = "  ", locality = "")))
        val long = PlaceLabelPolicy.label(PlaceParts(locality = "A".repeat(60)))!!
        assertEquals(PlaceLabelPolicy.MAX_LENGTH, long.length)
        assertEquals('…', long.last())
    }

    @Test
    fun `the printed line follows the toggle`() {
        assertNull(PlaceLabelPolicy.line(PlaceReference.OFF, "Koramangala", "Indiranagar"))
        assertEquals("Indiranagar", PlaceLabelPolicy.line(PlaceReference.FINISH_ONLY, "Koramangala", "Indiranagar"))
        assertEquals("Koramangala → Indiranagar", PlaceLabelPolicy.line(PlaceReference.START_AND_FINISH, "Koramangala", "Indiranagar"))
    }

    @Test
    fun `a loop names its place once and a missing end is not an error`() {
        assertEquals("Koramangala", PlaceLabelPolicy.line(PlaceReference.START_AND_FINISH, "Koramangala", "koramangala"))
        assertEquals("Indiranagar", PlaceLabelPolicy.line(PlaceReference.START_AND_FINISH, null, "Indiranagar"))
        assertNull(PlaceLabelPolicy.line(PlaceReference.FINISH_ONLY, "Koramangala", null))
    }
}
