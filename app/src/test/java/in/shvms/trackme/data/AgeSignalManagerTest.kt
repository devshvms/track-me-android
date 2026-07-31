package `in`.shvms.trackme.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AgeSignalManagerTest {
    @Test
    fun adultBandAllowsAccess() {
        assertEquals(AgeSignalCategory.ADULT, ageSignalCategoryForBounds(18))
        assertEquals(AgeSignalCategory.ADULT, ageSignalCategoryForBounds(42))
    }

    @Test
    fun minorBandsBlockAccess() {
        assertEquals(AgeSignalCategory.MINOR, ageSignalCategoryForBounds(0))
        assertEquals(AgeSignalCategory.MINOR, ageSignalCategoryForBounds(13))
        assertEquals(AgeSignalCategory.MINOR, ageSignalCategoryForBounds(17))
    }

    @Test
    fun missingSignalFailsOpenAsUnknown() {
        assertEquals(AgeSignalCategory.UNKNOWN, ageSignalCategoryForBounds(null))
    }
}
