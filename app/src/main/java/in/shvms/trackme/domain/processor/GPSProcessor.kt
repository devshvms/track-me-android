package `in`.shvms.trackme.domain.processor

import android.location.Location
import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.data.local.dao.RideDao
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import kotlinx.coroutines.flow.firstOrNull
import kotlin.math.max
import kotlin.math.sqrt

interface GPSProcessor {
    suspend fun processRide(rideId: Long, rideDao: RideDao, isEnabled: Boolean)
}

class DefaultGPSProcessor : GPSProcessor {
    override suspend fun processRide(rideId: Long, rideDao: RideDao, isEnabled: Boolean) {
        if (!isEnabled) return
        
        val rawPoints = rideDao.getPointsForRide(rideId).firstOrNull() ?: return
        if (rawPoints.isEmpty()) return

        val rawPointCount = rawPoints.size

        // Step A: Outlier Removal & Acceleration Tracking
        var maxAcceleration = 0f
        val validPoints = mutableListOf<GPSPointEntity>()
        
        for (i in rawPoints.indices) {
            val current = rawPoints[i]
            if (validPoints.isEmpty()) {
                validPoints.add(current)
                continue
            }
            
            val last = validPoints.last()
            val timeDiffSecs = (current.timestamp - last.timestamp) / 1000f
            if (timeDiffSecs <= 0f) continue // Prevent division by zero
            
            val results = FloatArray(1)
            Location.distanceBetween(last.latitude, last.longitude, current.latitude, current.longitude, results)
            val distance = results[0]
            
            val requiredSpeed = distance / timeDiffSecs // m/s
            val speedDiff = kotlin.math.abs(requiredSpeed - last.speed)
            val requiredAcceleration = speedDiff / timeDiffSecs // m/s^2
            
            // Check max G force (1G = 9.8 m/s^2). If it's more than limit, drop it.
            if (requiredAcceleration > (AppConfig.MAX_ACCELERATION_G * 9.8f)) {
                // Outlier, skip adding to validPoints
                continue
            }
            
            maxAcceleration = max(maxAcceleration, requiredAcceleration)
            validPoints.add(current)
        }

        if (validPoints.isEmpty()) return

        // Step B: Altitude & Speed Smoothing (Simple Moving Average, 5 points)
        val smoothedPoints = mutableListOf<GPSPointEntity>()
        val windowSize = 5
        val halfWindow = windowSize / 2
        
        for (i in validPoints.indices) {
            val start = max(0, i - halfWindow)
            val end = kotlin.math.min(validPoints.size - 1, i + halfWindow)
            
            var sumAlt = 0.0
            var sumSpeed = 0f
            var count = 0
            
            for (j in start..end) {
                sumAlt += validPoints[j].altitude
                sumSpeed += validPoints[j].speed
                count++
            }
            
            smoothedPoints.add(
                validPoints[i].copy(
                    altitude = sumAlt / count,
                    speed = sumSpeed / count
                )
            )
        }

        // Step C: Retroactive Auto-Pause Detection (15s sliding window, < 2.5km/h)
        val pauseThresholdMs = 2.5f / 3.6f // 2.5 km/h in m/s
        val timeWindowMs = 15000L
        
        val autoPausedPoints = smoothedPoints.map { point ->
            // Find points within 15s window around this point
            val windowPoints = smoothedPoints.filter { kotlin.math.abs(it.timestamp - point.timestamp) <= timeWindowMs / 2 }
            val avgSpeed = if (windowPoints.isNotEmpty()) windowPoints.map { it.speed }.average().toFloat() else point.speed
            
            point.copy(isPaused = avgSpeed < pauseThresholdMs)
        }

        // Step D: 4D RDP Compression
        val epsilon = 2.0 // Configurable threshold for deviation
        val compressedPoints = douglasPeucker(autoPausedPoints, epsilon)

        // Step E: Database Finalization
        val rideWithPoints = rideDao.getRideWithPointsById(rideId) ?: return
        val ride = rideWithPoints.ride
        
        // Recalculate stats
        var totalDistance = 0.0
        var finalMaxSpeed = 0f
        var pauseDurationMs = 0L
        var lastUnpausedPoint: GPSPointEntity? = null
        
        for (p in compressedPoints) {
            finalMaxSpeed = max(finalMaxSpeed, p.speed)
            if (p.isPaused) {
                // Approximate pause duration by adding gap from previous point
            }
            
            if (!p.isPaused) {
                if (lastUnpausedPoint != null) {
                    val results = FloatArray(1)
                    Location.distanceBetween(lastUnpausedPoint.latitude, lastUnpausedPoint.longitude, p.latitude, p.longitude, results)
                    totalDistance += results[0]
                }
                lastUnpausedPoint = p
            }
        }
        
        // Calculate pause duration accurately based on time between first and last points vs active time
        val activeTimeMs = compressedPoints.filter { !it.isPaused }.let { activePoints ->
            if (activePoints.size > 1) {
                var activeT = 0L
                for(i in 1 until activePoints.size) {
                    activeT += (activePoints[i].timestamp - activePoints[i-1].timestamp)
                }
                activeT
            } else 0L
        }
        val totalTimeMs = compressedPoints.last().timestamp - compressedPoints.first().timestamp
        pauseDurationMs = totalTimeMs - activeTimeMs
        
        val avgSpeed = if (activeTimeMs > 0) (totalDistance / (activeTimeMs / 1000f)).toFloat() else 0f

        // Handle case where we don't have an existing calculation
        val currentCalc = ride.postRideCalculation ?: `in`.shvms.trackme.data.local.entity.PostRideCalculation(0f, 0.0, 0f, 0L)
        
        val updatedCalc = currentCalc.copy(
            maxSpeed = finalMaxSpeed,
            distance = totalDistance,
            avgSpeed = avgSpeed,
            pauseDuration = pauseDurationMs,
            maxAcceleration = maxAcceleration,
            rawPointCount = rawPointCount
        )

        val updatedRide = ride.copy(postRideCalculation = updatedCalc)
        
        // Finalize in DB
        rideDao.deletePointsForRide(rideId)
        rideDao.insertGPSPoints(compressedPoints)
        rideDao.updateRide(updatedRide)
    }

    private fun douglasPeucker(points: List<GPSPointEntity>, epsilon: Double): List<GPSPointEntity> {
        if (points.size <= 2) return points

        var dmax = 0.0
        var index = 0
        val end = points.size - 1

        for (i in 1 until end) {
            val d = perpendicularDistance4D(points[i], points[0], points[end])
            if (d > dmax) {
                index = i
                dmax = d
            }
        }

        return if (dmax > epsilon) {
            val recResults1 = douglasPeucker(points.subList(0, index + 1), epsilon)
            val recResults2 = douglasPeucker(points.subList(index, points.size), epsilon)
            
            val result = mutableListOf<GPSPointEntity>()
            result.addAll(recResults1.dropLast(1))
            result.addAll(recResults2)
            result
        } else {
            listOf(points[0], points[end])
        }
    }

    private fun perpendicularDistance4D(pt: GPSPointEntity, lineStart: GPSPointEntity, lineEnd: GPSPointEntity): Double {
        // Calculate geographic distance deviation (meters)
        val results = FloatArray(1)
        Location.distanceBetween(lineStart.latitude, lineStart.longitude, lineEnd.latitude, lineEnd.longitude, results)
        val lineLengthGeo = results[0].toDouble()
        
        // If lineStart and lineEnd are exactly same geo point, just return distance from pt to lineStart
        if (lineLengthGeo == 0.0) {
            Location.distanceBetween(pt.latitude, pt.longitude, lineStart.latitude, lineStart.longitude, results)
            return results[0].toDouble()
        }

        // Cross-track distance (simplified approximation using area of triangle)
        Location.distanceBetween(lineStart.latitude, lineStart.longitude, pt.latitude, pt.longitude, results)
        val d1 = results[0].toDouble()
        Location.distanceBetween(pt.latitude, pt.longitude, lineEnd.latitude, lineEnd.longitude, results)
        val d2 = results[0].toDouble()
        
        // Heron's formula for area of triangle
        val s = (lineLengthGeo + d1 + d2) / 2.0
        val area = sqrt(max(0.0, s * (s - lineLengthGeo) * (s - d1) * (s - d2)))
        val geoDeviation = if (lineLengthGeo > 0) 2.0 * area / lineLengthGeo else d1

        // Interpolation factor t (0 to 1) along the line
        val t = if (lineEnd.timestamp > lineStart.timestamp) {
            (pt.timestamp - lineStart.timestamp).toDouble() / (lineEnd.timestamp - lineStart.timestamp).toDouble()
        } else 0.5

        val expectedAlt = lineStart.altitude + t * (lineEnd.altitude - lineStart.altitude)
        val altDeviation = kotlin.math.abs(pt.altitude - expectedAlt)

        val expectedSpeed = lineStart.speed + t * (lineEnd.speed - lineStart.speed)
        val speedDeviation = kotlin.math.abs(pt.speed - expectedSpeed).toDouble()

        // Combine deviations (Euclidean distance in normalized space)
        // Weight altitude and speed differently if desired
        return sqrt(geoDeviation * geoDeviation + altDeviation * altDeviation + speedDeviation * speedDeviation)
    }
}
