package `in`.shvms.trackme.ui.onboarding

import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reads the demo ride out of `demo_ride.gpx`.
 *
 * The file is a real recording, not a drawing: a Cycling ride captured on the iOS Simulator against
 * Apple's "City Bicycle Ride" location scenario in Cupertino. The **same file** ships on both
 * platforms, so the walkthrough and the first-run sample ride show the same route everywhere.
 *
 * It lives in `src/main/resources` rather than `res/raw` deliberately. A Java resource is reachable
 * through the class loader, so the fixture keeps its Context-free API — `res/raw` would have forced
 * a `Context` through `OnboardingDemoFixture.create()` and every call site that uses it, including
 * the Compose demos and the sample-ride seeder.
 *
 * Parsed once, on first touch. The file has ~1,300 points, and re-reading it per recomposition
 * would be wasteful in a flow that is already animating.
 */
internal object DemoRideGpx {

    /** One recorded fix. Speed is the value the recorder wrote, not a re-derivation. */
    internal data class Point(
        val latitude: Double,
        val longitude: Double,
        val altitudeMeters: Double,
        val speedMetersPerSecond: Float,
        val accuracyMeters: Float,
        /** Milliseconds from the first fix, so the caller can rebase onto any start time. */
        val offsetMillis: Long,
    )

    internal data class Track(
        val points: List<Point>,
        val durationMillis: Long,
        val distanceMeters: Double,
        val maxSpeedMetersPerSecond: Float,
    ) {
        val averageSpeedMetersPerSecond: Double =
            if (durationMillis > 0) distanceMeters / (durationMillis / 1_000.0) else 0.0
    }

    private const val RESOURCE = "/demo_ride.gpx"

    val track: Track by lazy { parse(requireNotNull(openResource()) { "$RESOURCE missing from the APK" }) }

    private fun openResource(): InputStream? =
        DemoRideGpx::class.java.getResourceAsStream(RESOURCE)

    /**
     * SAX rather than `android.util.Xml`: the latter is a stub in JVM unit tests and returns a null
     * parser, so the fixture could not be covered without an instrumentation test. `SAXParserFactory`
     * behaves the same on device and on the JVM.
     */
    internal fun parse(stream: InputStream): Track = stream.use { input ->
        val raw = mutableListOf<Raw>()

        val handler = object : DefaultHandler() {
            private val text = StringBuilder()
            private var lat = 0.0
            private var lon = 0.0
            private var ele = 0.0
            private var hdop = 0f
            private var speed = 0f
            private var epochMillis = 0L

            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                text.setLength(0)
                if (qName == "trkpt") {
                    lat = attributes.getValue("lat")?.toDoubleOrNull() ?: 0.0
                    lon = attributes.getValue("lon")?.toDoubleOrNull() ?: 0.0
                    ele = 0.0; hdop = 0f; speed = 0f; epochMillis = 0L
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                text.appendRange(ch, start, start + length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String) {
                val value = text.toString().trim()
                when (qName) {
                    "ele" -> ele = value.toDoubleOrNull() ?: 0.0
                    "hdop" -> hdop = value.toFloatOrNull() ?: 0f
                    // The recorded speed, carried in the Garmin TrackPointExtension the exporter
                    // writes. Without it a consumer would have to re-derive speed from geometry.
                    "gpxtpx:speed", "speed" -> speed = value.toFloatOrNull() ?: 0f
                    "time" -> epochMillis = parseTime(value)
                    "trkpt" -> if (epochMillis > 0L) raw += Raw(lat, lon, ele, speed, hdop, epochMillis)
                }
                text.setLength(0)
            }
        }

        SAXParserFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newSAXParser()
            .parse(input, handler)

        require(raw.size >= 2) { "demo_ride.gpx needs at least two track points, found ${raw.size}" }
        val sorted = raw.sortedBy { it.epochMillis }
        val base = sorted.first().epochMillis

        var distance = 0.0
        for (i in 1 until sorted.size) {
            distance += haversineMeters(sorted[i - 1], sorted[i])
        }

        val points = sorted.map {
            Point(
                latitude = it.lat,
                longitude = it.lon,
                altitudeMeters = it.ele,
                speedMetersPerSecond = it.speed,
                accuracyMeters = it.hdop,
                offsetMillis = it.epochMillis - base,
            )
        }

        Track(
            points = points,
            durationMillis = sorted.last().epochMillis - base,
            distanceMeters = distance,
            maxSpeedMetersPerSecond = points.maxOf { it.speedMetersPerSecond },
        )
    }

    private data class Raw(
        val lat: Double,
        val lon: Double,
        val ele: Double,
        val speed: Float,
        val hdop: Float,
        val epochMillis: Long,
    )

    /** GPX times are ISO-8601 UTC, with or without fractional seconds. */
    private fun parseTime(value: String): Long {
        if (value.isEmpty()) return 0L
        val patterns = if (value.contains('.')) {
            listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        } else {
            listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        }
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(value)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0L
    }

    private fun haversineMeters(a: Raw, b: Raw): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadius * asin(min(1.0, sqrt(h)))
    }
}
