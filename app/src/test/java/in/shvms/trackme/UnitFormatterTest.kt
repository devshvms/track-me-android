package `in`.shvms.trackme

import `in`.shvms.trackme.domain.UnitFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitFormatterTest {
    @Test fun metricDistance() = assertEquals("1.00 km", UnitFormatter.distance(1000.0, false))
    @Test fun imperialDistance() = assertEquals("1.00 mi", UnitFormatter.distance(1609.344, true))
    @Test fun metricSpeed() = assertEquals("36.0 km/h", UnitFormatter.speed(10.0, false))
    @Test fun imperialSpeed() = assertEquals("22.4 mph", UnitFormatter.speed(10.0, true))
}
