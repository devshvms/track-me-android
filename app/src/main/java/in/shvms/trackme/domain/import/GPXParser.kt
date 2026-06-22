package `in`.shvms.trackme.domain.import

import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.PostRideCalculation
import `in`.shvms.trackme.data.local.entity.RideEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class GPXParser {
    
    data class ParsedGPX(
        val rideWithPoints: RideWithPoints,
        val originalTrackMeId: String?
    )

    fun parse(inputStream: InputStream): ParsedGPX {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        
        var currentLat: Double? = null
        var currentLon: Double? = null
        var currentEle: Double? = null
        var currentTime: Long? = null
        var originalTrackMeId: String? = null
        var currentText = ""
        var rideName: String? = null
        
        val points = mutableListOf<GPSPointEntity>()

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "trkpt" -> {
                            currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            currentEle = null
                            currentTime = null
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    currentText = parser.text.trim()
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "name" -> {
                            if (rideName == null && currentText.isNotBlank()) {
                                rideName = currentText
                            }
                        }
                        "desc" -> {
                            if (currentText.startsWith("TrackMeID:")) {
                                originalTrackMeId = currentText.substringAfter("TrackMeID:")
                            }
                        }
                        "ele" -> {
                            currentEle = currentText.toDoubleOrNull()
                        }
                        "time" -> {
                            try {
                                currentTime = sdf.parse(currentText)?.time
                            } catch (e: Exception) {
                                // Ignore parse errors
                            }
                        }
                        "trkpt" -> {
                            if (currentLat != null && currentLon != null && currentTime != null) {
                                points.add(
                                    GPSPointEntity(
                                        rideId = 0, // Will be set on insert
                                        latitude = currentLat,
                                        longitude = currentLon,
                                        altitude = currentEle ?: 0.0,
                                        accuracy = 0f,
                                        speed = 0f, // Extrapolate later
                                        timestamp = currentTime,
                                        isPaused = false
                                    )
                                )
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        // Sort points by time just in case
        points.sortBy { it.timestamp }

        // Extrapolate speed and total distance
        var totalDistance = 0.0
        var maxSpeed = 0f
        
        for (i in 0 until points.size) {
            if (i > 0) {
                val prev = points[i - 1]
                val curr = points[i]
                
                val results = FloatArray(1)
                android.location.Location.distanceBetween(
                    prev.latitude, prev.longitude,
                    curr.latitude, curr.longitude,
                    results
                )
                val distance = results[0]
                totalDistance += distance
                
                val timeDiffMillis = curr.timestamp - prev.timestamp
                val speed = if (timeDiffMillis > 0) (distance / (timeDiffMillis / 1000f)) else 0f
                points[i] = curr.copy(speed = speed)
                if (speed > maxSpeed) maxSpeed = speed
            }
        }

        val startTime = points.firstOrNull()?.timestamp ?: System.currentTimeMillis()
        val endTime = points.lastOrNull()?.timestamp ?: startTime
        val durationMillis = endTime - startTime
        val avgSpeed = if (durationMillis > 0) (totalDistance / (durationMillis / 1000f)).toFloat() else 0f

        val calc = PostRideCalculation(
            maxSpeed = maxSpeed,
            distance = totalDistance,
            avgSpeed = avgSpeed,
            pauseDuration = 0L
        )

        val ride = RideEntity(
            startTime = startTime,
            endTime = endTime,
            sourceInfo = "Imported GPX",
            isSynced = false,
            title = rideName ?: "Imported Ride",
            postRideCalculation = calc
        )

        val rideWithPoints = RideWithPoints(ride, points)

        return ParsedGPX(rideWithPoints, originalTrackMeId)
    }
}
