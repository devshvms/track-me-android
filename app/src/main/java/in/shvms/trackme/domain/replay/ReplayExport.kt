package `in`.shvms.trackme.domain.replay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import `in`.shvms.trackme.R
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.ui.history.trimComparisonEndpoints
import `in`.shvms.trackme.theme.BrandThemeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class ReplayExportConfig(
    val width: Int = 1080,
    val height: Int = 1920,
    val fps: Int = 30,
    val targetDurationSeconds: Int = 20,
    val applyPrivacyTrim: Boolean = true,
    val privacyTrimDistanceMeters: Double = 200.0,
    val persona: RidePersona,
    val deepLink: String? = null
) {
    init {
        require(width > 0 && height > 0) { "Replay dimensions must be positive" }
        require(fps in 1..60) { "Replay fps must be between 1 and 60" }
        require(targetDurationSeconds in 15..30) { "Replay duration must be between 15 and 30 seconds" }
        require(privacyTrimDistanceMeters >= 0.0) { "Privacy trim must not be negative" }
    }
}

data class ReplayStats(
    val distanceMeters: Double,
    val durationMillis: Long,
    val averageSpeedMetersPerSecond: Double
)

interface ReplayFrameRenderer {
    fun renderFrame(
        canvas: Canvas,
        points: List<GPSPointEntity>,
        progress: Float,
        persona: RidePersona,
        stats: ReplayStats,
        deepLink: String?,
        mapSnapshot: Bitmap? = null
    )
}

interface ReplayExporter {
    suspend fun exportReplay(
        rideWithPoints: RideWithPoints,
        config: ReplayExportConfig,
        outputDirectory: File,
        mapSnapshot: Bitmap? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<File>
}

/**
 * Lightweight Phase 1 renderer. Map tiles are supplied as one optional bitmap captured before
 * encoding; every frame remains a deterministic Canvas composition that can run on a worker
 * thread and be encoded offline.
 */
class CanvasReplayFrameRenderer(appContext: Context? = null) : ReplayFrameRenderer {
    private val watermarkDrawable: Drawable? = appContext?.let {
        ContextCompat.getDrawable(it, R.drawable.ic_trackme_logo_transparent)
    }
    private val watermarkTypeface: Typeface = appContext?.let {
        ResourcesCompat.getFont(it, R.font.inter_variable)
    } ?: Typeface.DEFAULT_BOLD

    override fun renderFrame(
        canvas: Canvas,
        points: List<GPSPointEntity>,
        progress: Float,
        persona: RidePersona,
        stats: ReplayStats,
        deepLink: String?,
        mapSnapshot: Bitmap?
    ) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        if (mapSnapshot != null && !mapSnapshot.isRecycled) {
            drawMapSnapshot(canvas, mapSnapshot, width, height)
            // Keep the route and text legible without changing the captured map itself.
            canvas.drawColor(Color.argb(92, 18, 22, 28))
        } else {
            canvas.drawColor(Color.rgb(18, 22, 28))
        }

        val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(41, 182, 246)
            style = Paint.Style.STROKE
            strokeWidth = max(5f, width * 0.006f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val route = project(points, width, height, mapSnapshot != null)
        if (route.size >= 2) {
            val path = Path().apply {
                moveTo(route.first().first, route.first().second)
                route.drop(1).forEach { lineTo(it.first, it.second) }
            }
            canvas.drawPath(path, routePaint)
            drawEndpointPin(canvas, route.first(), BrandThemeConfig.greenGo.toArgb(), false, width)
            drawEndpointPin(canvas, route.last(), BrandThemeConfig.cyanBright.toArgb(), true, width)
            val currentIndex = (progress.coerceIn(0f, 1f) * route.lastIndex).toInt()
            val moving = route[currentIndex]
            val personaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.drawCircle(moving.first, moving.second, max(14f, width * 0.018f), personaPaint)
            personaPaint.color = Color.rgb(2, 119, 182)
            val next = route[(currentIndex + 1).coerceAtMost(route.lastIndex)]
            val angle = Math.toDegrees(atan2((next.second - moving.second).toDouble(), (next.first - moving.first).toDouble())).toFloat() + 90f
            canvas.save()
            canvas.rotate(angle, moving.first, moving.second)
            val sprite = Path().apply {
                moveTo(moving.first, moving.second - max(11f, width * 0.014f))
                lineTo(moving.first - max(8f, width * 0.010f), moving.second + max(8f, width * 0.010f))
                lineTo(moving.first + max(8f, width * 0.010f), moving.second + max(8f, width * 0.010f))
                close()
            }
            canvas.drawPath(sprite, personaPaint)
            canvas.restore()
        }

        drawTrackMeLockup(canvas, width.toInt())

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            textSize = max(18f, width * 0.028f)
        }
        canvas.drawText(persona.displayName, width * 0.06f, height * 0.12f, bodyPaint)
        canvas.drawText(
            "${formatDistance(stats.distanceMeters)}  ·  ${formatDuration(stats.durationMillis)}",
            width * 0.06f,
            height * 0.93f,
            bodyPaint
        )
        deepLink?.takeIf { it.startsWith("https://trackme.shvms.in/r/") }?.let {
            bodyPaint.textSize = max(12f, width * 0.018f)
            canvas.drawText(it, width * 0.06f, height * 0.965f, bodyPaint)
        }
    }

    private fun drawMapSnapshot(canvas: Canvas, snapshot: Bitmap, width: Float, height: Float) {
        val scale = max(width / snapshot.width.toFloat(), height / snapshot.height.toFloat())
        val drawnWidth = snapshot.width * scale
        val drawnHeight = snapshot.height * scale
        val left = (width - drawnWidth) / 2f
        val top = (height - drawnHeight) / 2f
        canvas.drawBitmap(
            snapshot,
            null,
            RectF(left, top, left + drawnWidth, top + drawnHeight),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        )
    }

    private fun drawEndpointPin(canvas: Canvas, point: Pair<Float, Float>, color: Int, ringed: Boolean, width: Float) {
        val radius = max(17f, width * 0.022f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        if (ringed) {
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = max(4f, width * 0.005f)
        }
        canvas.drawCircle(point.first, point.second, radius, fill)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = max(3f, width * 0.004f)
        }
        canvas.drawCircle(point.first, point.second, radius, ring)
    }

    private fun drawTrackMeLockup(canvas: Canvas, width: Int) {
        val icon = watermarkDrawable ?: return
        val margin = (width * 0.045f).toInt().coerceAtLeast(8)
        val iconSize = (width * 0.075f).toInt().coerceAtLeast(32)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = iconSize * 0.42f
            typeface = watermarkTypeface
        }
        val textWidth = textPaint.measureText("TrackMe")
        val gap = (iconSize * 0.18f).toInt()
        val lockupWidth = iconSize + gap + textWidth
        val left = (width - margin - lockupWidth).coerceAtLeast(margin.toFloat())
        val top = margin.toFloat()
        val padding = (iconSize * 0.16f).toInt().coerceAtLeast(4)
        canvas.drawRoundRect(
            RectF(left - padding, top - padding, left + lockupWidth + padding, top + iconSize + padding),
            padding.toFloat(),
            padding.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 18, 22, 28) }
        )
        icon.setBounds(left.toInt(), top.toInt(), left.toInt() + iconSize, top.toInt() + iconSize)
        icon.draw(canvas)
        canvas.drawText("TrackMe", left + iconSize + gap, top + iconSize * 0.68f, textPaint)
    }

    private fun project(points: List<GPSPointEntity>, width: Float, height: Float, fullFrame: Boolean): List<Pair<Float, Float>> {
        if (points.isEmpty()) return emptyList()
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLng = points.minOf { it.longitude }
        val maxLng = points.maxOf { it.longitude }
        val latSpan = (maxLat - minLat).takeIf { it > 0.00001 } ?: 0.001
        val lngSpan = (maxLng - minLng).takeIf { it > 0.00001 } ?: 0.001
        val left = if (fullFrame) 0f else width * 0.08f
        val right = if (fullFrame) width else width * 0.92f
        val top = if (fullFrame) 0f else height * 0.18f
        val bottom = if (fullFrame) height else height * 0.84f
        return points.map { point ->
            val x = left + ((point.longitude - minLng) / lngSpan).toFloat() * (right - left)
            val y = bottom - ((point.latitude - minLat) / latSpan).toFloat() * (bottom - top)
            x to y
        }
    }

    private fun formatDistance(meters: Double): String = if (meters >= 1000) "%.1f km".format(meters / 1000.0) else "%.0f m".format(meters)

    private fun formatDuration(durationMillis: Long): String {
        val seconds = (durationMillis.coerceAtLeast(0L) / 1000L)
        return "%02d:%02d".format(seconds / 60L, seconds % 60L)
    }
}

/** MediaCodec/MediaMuxer implementation for the offline Phase 1 MVP. */
class MediaCodecReplayExporter(
    private val renderer: ReplayFrameRenderer = CanvasReplayFrameRenderer()
) : ReplayExporter {
    override suspend fun exportReplay(
        rideWithPoints: RideWithPoints,
        config: ReplayExportConfig,
        outputDirectory: File,
        mapSnapshot: Bitmap?,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.Default) {
        var output: File? = null
        try {
            coroutineContext.ensureActive()
            val points = if (config.applyPrivacyTrim) {
                trimComparisonEndpoints(rideWithPoints.points, config.privacyTrimDistanceMeters)
            } else {
                rideWithPoints.points
            }.sortedBy { it.timestamp }
            require(points.size >= 2) { "At least two GPS points are required" }
            outputDirectory.mkdirs()
            output = File(outputDirectory, "TrackMe_Replay_${UUID.randomUUID()}.mp4")
            encode(rideWithPoints, points, config, output, mapSnapshot, onProgress)
            Result.success(output)
        } catch (cancelled: CancellationException) {
            output?.delete()
            throw cancelled
        } catch (error: Throwable) {
            output?.delete()
            Result.failure(error)
        }
    }

    private suspend fun encode(
        rideWithPoints: RideWithPoints,
        points: List<GPSPointEntity>,
        config: ReplayExportConfig,
        output: File,
        mapSnapshot: Bitmap?,
        onProgress: (Float) -> Unit
    ) {
        val frameCount = config.targetDurationSeconds * config.fps
        val stats = ReplayStats(
            distanceMeters = rideWithPoints.ride.postRideCalculation?.distance ?: 0.0,
            durationMillis = ((rideWithPoints.ride.endTime ?: rideWithPoints.ride.startTime) - rideWithPoints.ride.startTime).coerceAtLeast(0L),
            averageSpeedMetersPerSecond = rideWithPoints.ride.postRideCalculation?.avgSpeed?.toDouble() ?: 0.0
        )
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var inputSurface: android.view.Surface? = null
        var muxerStarted = false
        var trackIndex = -1
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()

            fun drain(endOfStream: Boolean) {
                if (endOfStream) codec.signalEndOfInputStream()
                val info = MediaCodec.BufferInfo()
                while (true) {
                    when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder format changed twice" }
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        else -> if (index >= 0) {
                            if (info.size > 0 && muxerStarted) {
                                codec.getOutputBuffer(index)?.let { buffer ->
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    muxer.writeSampleData(trackIndex, buffer, info)
                                }
                            }
                            codec.releaseOutputBuffer(index, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                        }
                    }
                }
            }

            repeat(frameCount) { frame ->
                coroutineContext.ensureActive()
                val progress = progressForFrame(points, frame, frameCount)
                val canvas = inputSurface!!.lockCanvas(null)
                try {
                    renderer.renderFrame(
                        canvas = canvas,
                        points = points,
                        progress = progress,
                        persona = config.persona,
                        stats = replayStatsAtProgress(points, progress, stats),
                        deepLink = config.deepLink,
                        mapSnapshot = mapSnapshot
                    )
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }
                drain(false)
                onProgress((frame + 1).toFloat() / frameCount)
            }
            drain(true)
        } finally {
            inputSurface?.release()
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun progressForFrame(points: List<GPSPointEntity>, frame: Int, frameCount: Int): Float {
        if (points.size < 2 || frameCount <= 1) return 0f
        val firstTimestamp = points.first().timestamp
        val lastTimestamp = points.last().timestamp
        if (lastTimestamp <= firstTimestamp) return frame.toFloat() / (frameCount - 1)
        val targetTimestamp = firstTimestamp + ((lastTimestamp - firstTimestamp) * frame.toDouble() / (frameCount - 1)).toLong()
        val upper = points.indexOfFirst { it.timestamp >= targetTimestamp }.coerceAtLeast(1)
        val lower = upper - 1
        val span = (points[upper].timestamp - points[lower].timestamp).coerceAtLeast(1L)
        val fraction = (targetTimestamp - points[lower].timestamp).toFloat() / span
        return ((lower + fraction) / points.lastIndex).coerceIn(0f, 1f)
    }
}

/** Computes route stats for the currently visible fraction of the replay. */
internal fun replayStatsAtProgress(
    points: List<GPSPointEntity>,
    progress: Float,
    fallback: ReplayStats
): ReplayStats {
    if (points.size < 2) return fallback
    val position = progress.coerceIn(0f, 1f) * points.lastIndex
    val lower = position.toInt().coerceIn(0, points.lastIndex)
    val fraction = (position - lower).coerceIn(0f, 1f)
    var distance = 0.0
    for (index in 0 until lower) distance += distanceMeters(points[index], points[index + 1])
    if (lower < points.lastIndex) distance += distanceMeters(points[lower], points[lower + 1]) * fraction
    val duration = if (lower >= points.lastIndex) {
        (points.last().timestamp - points.first().timestamp).coerceAtLeast(0L)
    } else {
        val span = points[lower + 1].timestamp - points[lower].timestamp
        (points.first().timestamp.let { points[lower].timestamp - it } + (span * fraction).toLong()).coerceAtLeast(0L)
    }
    val averageSpeed = if (duration > 0L) distance / (duration / 1000.0) else 0.0
    return ReplayStats(distance, duration, averageSpeed)
}

private fun distanceMeters(a: GPSPointEntity, b: GPSPointEntity): Double {
    val earthRadius = 6_371_000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return earthRadius * 2.0 * atan2(sqrt(h), sqrt(1.0 - h))
}
