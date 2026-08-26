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

fun interface GeoDistanceCalculator {
    fun meters(from: GPSPointEntity, to: GPSPointEntity): Float
}

private object AndroidGeoDistanceCalculator : GeoDistanceCalculator {
    override fun meters(from: GPSPointEntity, to: GPSPointEntity): Float {
        val results = FloatArray(1)
        Location.distanceBetween(from.latitude, from.longitude, to.latitude, to.longitude, results)
        return results[0]
    }
}

class DefaultGPSProcessor(
    private val distanceCalculator: GeoDistanceCalculator = AndroidGeoDistanceCalculator
) : GPSProcessor {
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
            
            val distance = distanceCalculator.meters(last, current)
            
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

        // Step C: Preserve Real-Time Hardware/Adaptive Auto-Pause State
        val autoPausedPoints = smoothedPoints

        // Step D: Segment by GPS Signal Loss (> 15 seconds gap) and run 4D RDP Compression
        val epsilon = 2.0 // Configurable threshold for deviation
        val maxGapMs = 15_000L
        val chunks = mutableListOf<List<GPSPointEntity>>()
        var currentChunk = mutableListOf<GPSPointEntity>()
        for (pt in autoPausedPoints) {
            if (currentChunk.isEmpty()) {
                currentChunk.add(pt)
            } else {
                if (pt.timestamp - currentChunk.last().timestamp > maxGapMs) {
                    chunks.add(currentChunk)
                    currentChunk = mutableListOf(pt)
                } else {
                    currentChunk.add(pt)
                }
            }
        }
        if (currentChunk.isNotEmpty()) chunks.add(currentChunk)

        val compressedPoints = mutableListOf<GPSPointEntity>()
        for (chunk in chunks) {
            val compressedChunk = douglasPeucker(chunk, epsilon, maxGapMs)
            if (compressedPoints.isNotEmpty() && compressedChunk.isNotEmpty() &&
                compressedPoints.last().timestamp == compressedChunk.first().timestamp) {
                compressedPoints.addAll(compressedChunk.drop(1))
            } else {
                compressedPoints.addAll(compressedChunk)
            }
        }

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
                val gapMs = lastUnpausedPoint?.let { p.timestamp - it.timestamp }
                if (lastUnpausedPoint != null && gapMs != null && gapMs <= maxGapMs) {
                    totalDistance += distanceCalculator.meters(lastUnpausedPoint, p)
                }
                lastUnpausedPoint = p
            }
        }
        
        // Calculate accurate active time from smoothed/auto-paused points before compression
        var activeTimeMs = 0L
        for (i in 1 until autoPausedPoints.size) {
            val curr = autoPausedPoints[i]
            val prev = autoPausedPoints[i-1]
            val gapMs = curr.timestamp - prev.timestamp
            if (!curr.isPaused && !prev.isPaused && gapMs <= maxGapMs) {
                activeTimeMs += gapMs
            }
        }
        val totalTimeMs = autoPausedPoints.last().timestamp - autoPausedPoints.first().timestamp
        pauseDurationMs = max(0L, totalTimeMs - activeTimeMs)
        
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

        val updatedRide = ride.copy(postRideCalculation = updatedCalc).let { updated ->
            `in`.shvms.trackme.data.local.withDashboardMetadata(
                updated,
                activeTimeMs,
                compressedPoints.size,
            )
        }
        
        // Finalize in DB
        rideDao.deletePointsForRide(rideId)
        rideDao.insertGPSPoints(compressedPoints)
        rideDao.updateRide(updatedRide)
    }

    private fun douglasPeucker(points: List<GPSPointEntity>, epsilon: Double, maxSpanMs: Long = 15_000L): List<GPSPointEntity> {
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

        val timeSpan = points[end].timestamp - points[0].timestamp
        return if (dmax > epsilon || timeSpan > maxSpanMs) {
            val splitIndex = if (index > 0) index else points.size / 2
            val recResults1 = douglasPeucker(points.subList(0, splitIndex + 1), epsilon, maxSpanMs)
            val recResults2 = douglasPeucker(points.subList(splitIndex, points.size), epsilon, maxSpanMs)
            
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
        val lineLengthGeo = distanceCalculator.meters(lineStart, lineEnd).toDouble()
        
        // If lineStart and lineEnd are exactly same geo point, just return distance from pt to lineStart
        if (lineLengthGeo == 0.0) {
            return distanceCalculator.meters(pt, lineStart).toDouble()
        }

        // Cross-track distance (simplified approximation using area of triangle)
        val d1 = distanceCalculator.meters(lineStart, pt).toDouble()
        val d2 = distanceCalculator.meters(pt, lineEnd).toDouble()
        
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
