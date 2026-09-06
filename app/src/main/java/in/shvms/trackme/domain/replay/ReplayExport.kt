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
import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.domain.UnitFormatter
import `in`.shvms.trackme.domain.export.gpsDistanceMeters
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.ui.history.OverlayContent
import `in`.shvms.trackme.ui.history.OverlayMetrics
import `in`.shvms.trackme.ui.history.StatsOverlayStyle
import `in`.shvms.trackme.ui.history.trimComparisonEndpoints
import `in`.shvms.trackme.theme.BrandThemeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.atan2

data class ReplayExportConfig(
    val width: Int = 1080,
    val height: Int = 1920,
    val fps: Int = 30,
    val targetDurationSeconds: Int = 20,
    val applyPrivacyTrim: Boolean = true,
    val privacyTrimDistanceMeters: Double = 200.0,
    val persona: RidePersona,
    val deepLink: String? = null,
    /**
     * Localized text baked into every frame. Defaulted so existing call sites and tests keep
     * compiling; UI call sites must supply it, otherwise the overlay falls back to English metric.
     */
    val overlay: ReplayOverlay = ReplayOverlay()
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

/**
 * Presentation text and chrome for the burned-in overlay. The renderer must never resolve strings
 * or preferences itself — the exported MP4 is a shared artifact, so its text has to match exactly
 * what the user sees in-app, in their language and their unit system.
 *
 * [personaLabel] must come from `AppStrings.personaLabel(persona)`. When null the renderer falls
 * back to the English `RidePersona.displayName`, which is a bug on any user-facing surface.
 *
 * ### TASK-305: why the style fields are here
 *
 * The still export and the video are made from the same preview, with the same settings, one button
 * apart — and until 1.8.7 the video honoured none of the panel settings. It drew distance and
 * duration in a fixed position, in a fixed dark treatment, whatever the user had chosen. Someone who
 * picked `StatsOverlayStyle.None` — *"No panel. The map alone."* — got a clean image and a video
 * with a stats panel welded on. That is the choice most likely to be made for something a person
 * actually intends to post, and it was the one choice the video could not honour.
 *
 * [figures] is the same already-formatted list the still export receives as `ExportOptions
 * .overlayFigures`, built once by `buildOverlayContent`. Handing the renderer finished strings is
 * the same rule as [personaLabel], for the same reason: two renderers deriving the same figures
 * independently is exactly how the file came to say something the preview did not.
 *
 * The defaults describe an *unstyled* frame rather than the old hard-coded one: a call site that
 * forgets to pass the user's choice now produces a clean video, never a wrong one.
 * `ReplayExportOverlayWiringTest` asserts the production call site does pass them.
 */
data class ReplayOverlay(
    val personaLabel: String? = null,
    val imperialUnits: Boolean = false,
    /** Where the panel sits, and — as `None` — whether it is drawn at all. */
    val statsStyle: StatsOverlayStyle = StatsOverlayStyle.None,
    /** Already-formatted figures, in display order. Empty means nothing to show. */
    val figures: List<String> = emptyList(),
    /** The user's theme choice, applied to the burned-in chrome as well as the basemap. */
    val darkTheme: Boolean = true
) {
    /** True when there is both a place to draw the panel and something to put in it. */
    val drawsPanel: Boolean get() = statsStyle.isVisible && figures.isNotEmpty()
}

interface ReplayFrameRenderer {
    fun renderFrame(
        canvas: Canvas,
        points: List<GPSPointEntity>,
        progress: Float,
        persona: RidePersona,
        stats: ReplayStats,
        deepLink: String?,
        mapSnapshot: Bitmap? = null,
        routeProjection: List<Pair<Float, Float>>? = null,
        overlay: ReplayOverlay = ReplayOverlay()
    )
}

interface ReplayExporter {
    suspend fun exportReplay(
        rideWithPoints: RideWithPoints,
        config: ReplayExportConfig,
        outputDirectory: File,
        mapSnapshot: Bitmap? = null,
        onProgress: (Float) -> Unit = {},
        routeProjection: List<Pair<Float, Float>>? = null
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
        mapSnapshot: Bitmap?,
        routeProjection: List<Pair<Float, Float>>?,
        overlay: ReplayOverlay
    ) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        val usableProjection = routeProjection?.takeIf { it.size == points.size }
        // A map is only safe when its SDK projection corresponds one-to-one with the points
        // being rendered. Never draw a letterboxed bitmap under an independently-derived route.
        val usableSnapshot = mapSnapshot?.takeIf {
            !it.isRecycled && usableProjection != null
        }
        // TASK-305: the scrim follows the user's theme, as the still export's does. A light
        // export used to get a dark video from the same preview.
        if (usableSnapshot != null) {
            drawMapSnapshot(canvas, usableSnapshot, width, height)
            // Keep the route and text legible without changing the captured map itself.
            canvas.drawColor(
                if (overlay.darkTheme) Color.argb(92, 18, 22, 28) else Color.argb(56, 255, 255, 255)
            )
        } else {
            canvas.drawColor(if (overlay.darkTheme) Color.rgb(18, 22, 28) else Color.rgb(244, 246, 249))
        }

        val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BrandThemeConfig.cyanBright.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = max(5f, width * 0.006f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val route = usableProjection?.map { projection ->
            if (usableSnapshot != null) {
                mapProjectionToFrame(
                    normalized = projection,
                    snapshotWidth = usableSnapshot.width,
                    snapshotHeight = usableSnapshot.height,
                    frameWidth = width,
                    frameHeight = height
                )
            } else {
                projection.first * width to projection.second * height
            }
        } ?: project(points, width, height, usableSnapshot != null)
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

        // The lockup and the link are not chrome the user opted into — they are what makes the
        // artifact traceable back to the app. They are drawn whatever the panel setting is.
        drawTrackMeLockup(canvas, width.toInt())

        if (overlay.drawsPanel) {
            drawStatsPanel(
                canvas = canvas,
                width = width,
                height = height,
                personaLabel = overlay.personaLabel?.takeIf { it.isNotBlank() } ?: persona.displayName,
                overlay = overlay
            )
        }

        deepLink?.takeIf { it.startsWith(AppConfig.REPLAY_DEEP_LINK_BASE_URL) }?.let {
            val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (overlay.darkTheme) Color.LTGRAY else Color.DKGRAY
                textSize = max(12f, width * 0.018f)
            }
            canvas.drawText(it, width * 0.06f, height * 0.965f, linkPaint)
        }
    }

    /**
     * The burned-in figures, in the placement the user chose — the same geometry the still export
     * uses, from the same [StatsOverlayStyle].
     *
     * Geometry is deliberately not re-derived here. `StatsOverlayStyle.rect(content)` is the one
     * place that decides where a panel sits and how tall it is for its line count; the still
     * exporter and the Compose preview already share it, and the video drawing its own rectangle is
     * how it came to disagree with both.
     */
    private fun drawStatsPanel(
        canvas: Canvas,
        width: Float,
        height: Float,
        personaLabel: String,
        overlay: ReplayOverlay
    ) {
        val content = OverlayContent(overlay.figures)
        val rect = overlay.statsStyle.rect(content) ?: return
        val shorterEdge = minOf(width, height)

        val panel = RectF(rect.left * width, rect.top * height, rect.right * width, rect.bottom * height)
        val radius = rect.inset * shorterEdge
        canvas.drawRoundRect(
            panel,
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (overlay.darkTheme) {
                    Color.argb(214, 18, 22, 28)
                } else {
                    Color.argb(214, 252, 253, 255)
                }
            }
        )

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (overlay.darkTheme) Color.WHITE else Color.rgb(18, 22, 28)
            textSize = OverlayMetrics.FIGURE_LINE_RATIO * shorterEdge * 0.72f
            typeface = watermarkTypeface
            textAlign = if (overlay.statsStyle.alignsTextEnd) Paint.Align.RIGHT else Paint.Align.LEFT
        }
        val padding = panel.width() * OverlayMetrics.HORIZONTAL_PADDING_FRACTION
        val textX = if (overlay.statsStyle.alignsTextEnd) panel.right - padding else panel.left + padding

        val lines = if (overlay.statsStyle.stacksFigures) {
            overlay.figures
        } else {
            listOf(overlay.figures.joinToString("  ·  "))
        }
        val lineHeight = OverlayMetrics.FIGURE_LINE_RATIO * shorterEdge
        var baseline = panel.top + OverlayMetrics.VERTICAL_PADDING_RATIO * shorterEdge + lineHeight * 0.78f
        lines.forEach { line ->
            canvas.drawText(line, textX, baseline, textPaint)
            baseline += lineHeight
        }

        // The activity is chrome, not a figure: it never appears in `buildOverlayContent`, so it is
        // drawn beside the panel rather than inside it -- and only when the panel exists, because
        // "the map alone" means the map alone.
        val personaPaint = Paint(textPaint).apply {
            textSize = textPaint.textSize * 0.82f
            textAlign = Paint.Align.LEFT
            color = if (overlay.darkTheme) Color.LTGRAY else Color.DKGRAY
        }
        val personaY = if (overlay.statsStyle == StatsOverlayStyle.BottomBar) height * 0.12f else height * 0.93f
        canvas.drawText(personaLabel, width * 0.06f, personaY, personaPaint)
    }

    private fun drawMapSnapshot(canvas: Canvas, snapshot: Bitmap, width: Float, height: Float) {
        val transform = snapshotTransform(snapshot.width, snapshot.height, width, height)
        val scale = transform.scale
        val drawnWidth = snapshot.width * scale
        val drawnHeight = snapshot.height * scale
        canvas.drawBitmap(
            snapshot,
            null,
            RectF(transform.left, transform.top, transform.left + drawnWidth, transform.top + drawnHeight),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        )
    }

    private fun drawEndpointPin(canvas: Canvas, point: Pair<Float, Float>, color: Int, ringed: Boolean, width: Float) {
        val radius = max(17f, width * 0.022f)
        if (ringed) {
            canvas.drawCircle(
                point.first,
                point.second,
                radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.WHITE
                    style = Paint.Style.FILL
                }
            )
            canvas.drawCircle(
                point.first,
                point.second,
                radius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    style = Paint.Style.STROKE
                    strokeWidth = max(4f, width * 0.005f)
                }
            )
            canvas.drawCircle(
                point.first,
                point.second,
                radius * 0.45f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    style = Paint.Style.FILL
                }
            )
            return
        }
        canvas.drawCircle(
            point.first,
            point.second,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
            }
        )
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = max(3f, width * 0.004f)
        }
        canvas.drawCircle(point.first, point.second, radius + ring.strokeWidth * 0.5f, ring)
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
}

// TASK-305 removed `formatReplayDistance` and `formatReplayDuration`. They were the video deriving
// its own figures — the exact duplicate derivation this file's own contract forbids — and they were
// the mechanism by which the MP4 could say something the preview did not. The video now receives
// `ReplayOverlay.figures`, already formatted by the same `buildOverlayContent` the still export
// uses. This also fixes a divergence nobody had listed: the video rendered `02:44:47` while the
// image rendered `2hr 44min`, because only the image went through `compactDuration`.

private data class SnapshotTransform(
    val scale: Float,
    val left: Float,
    val top: Float
)

private fun snapshotTransform(
    snapshotWidth: Int,
    snapshotHeight: Int,
    frameWidth: Float,
    frameHeight: Float
): SnapshotTransform {
    require(snapshotWidth > 0 && snapshotHeight > 0) { "Snapshot dimensions must be positive" }
    val scale = max(frameWidth / snapshotWidth.toFloat(), frameHeight / snapshotHeight.toFloat())
    return SnapshotTransform(
        scale = scale,
        left = (frameWidth - snapshotWidth * scale) / 2f,
        top = (frameHeight - snapshotHeight * scale) / 2f
    )
}

/** Maps SDK-normalized screen coordinates through the exact center-crop used for the bitmap. */
internal fun mapProjectionToFrame(
    normalized: Pair<Float, Float>,
    snapshotWidth: Int,
    snapshotHeight: Int,
    frameWidth: Float,
    frameHeight: Float
): Pair<Float, Float> {
    val transform = snapshotTransform(snapshotWidth, snapshotHeight, frameWidth, frameHeight)
    return (
        transform.left + normalized.first * snapshotWidth * transform.scale
    ) to (
        transform.top + normalized.second * snapshotHeight * transform.scale
    )
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
        onProgress: (Float) -> Unit,
        routeProjection: List<Pair<Float, Float>>?
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
            encode(rideWithPoints, points, config, output, mapSnapshot, routeProjection, onProgress)
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
        routeProjection: List<Pair<Float, Float>>?,
        onProgress: (Float) -> Unit
    ) {
        val frameCount = config.targetDurationSeconds * config.fps
        // Authoritative ride totals; replayStatsAtProgress scales these by route fraction so the
        // final frame equals what the History card shows. Duration is deliberately wall-clock
        // (endTime - startTime) and does NOT subtract postRideCalculation.pauseDuration, because
        // every other surface in the app (HistoryScreen, RideDetailScreen) shows wall-clock —
        // subtracting pause here would fix nothing and create a fresh app-vs-video mismatch.
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
                        // INFO_OUTPUT_BUFFERS_CHANGED was handled here explicitly. It is deprecated
                        // and, since API 21, never returned — the ByteBuffer array it announced was
                        // replaced by getOutputBuffer(index), which is what this drain already
                        // uses. minSdk is 24, so the case is unreachable. If some device returned
                        // it anyway the `else` below ignores any negative index and re-polls, which
                        // is exactly what the branch did.
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
                        mapSnapshot = mapSnapshot,
                        routeProjection = routeProjection,
                        overlay = config.overlay
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

/**
 * Computes the stats shown on the current replay frame.
 *
 * The rendered route is the *privacy-trimmed* subset (200 m removed at each end), so summing its
 * geometry directly produced a final frame whose distance and duration both undershot the ride's
 * own totals — the shared video contradicted the History card for the same ride. Instead the
 * animation interpolates *fractions* of the route and scales the ride's authoritative totals by
 * them, so the last frame lands exactly on the numbers shown in-app.
 *
 * Distance advances by distance travelled and duration by elapsed time, so a long mid-route stop
 * moves the clock without moving the odometer. Rides with no stored calculation (legacy imports,
 * recovered orphans) fall back to route geometry rather than rendering zeroes.
 */
internal fun replayStatsAtProgress(
    points: List<GPSPointEntity>,
    progress: Float,
    fallback: ReplayStats
): ReplayStats {
    if (points.size < 2) return fallback
    val totalDistance = fallback.distanceMeters.takeIf { it > 0.0 }
        ?: geometricRouteDistanceMeters(points)
    val totalDuration = fallback.durationMillis.takeIf { it > 0L }
        ?: (points.last().timestamp - points.first().timestamp).coerceAtLeast(0L)

    val distance = totalDistance * routeDistanceFraction(points, progress)
    val duration = (totalDuration * routeTimeFraction(points, progress)).toLong().coerceAtLeast(0L)
    val averageSpeed = if (duration > 0L) distance / (duration / 1000.0) else 0.0
    return ReplayStats(distance, duration, averageSpeed)
}

/** Total geometric length of the rendered (already trimmed) route. */
internal fun geometricRouteDistanceMeters(points: List<GPSPointEntity>): Double {
    if (points.size < 2) return 0.0
    var total = 0.0
    for (index in 0 until points.lastIndex) total += gpsDistanceMeters(points[index], points[index + 1])
    return total
}

/** Fraction of the route's length covered at [progress], in 0..1. */
internal fun routeDistanceFraction(points: List<GPSPointEntity>, progress: Float): Double {
    if (points.size < 2) return 0.0
    val total = geometricRouteDistanceMeters(points)
    if (total <= 0.0) return progress.coerceIn(0f, 1f).toDouble()
    val position = progress.coerceIn(0f, 1f) * points.lastIndex
    val lower = position.toInt().coerceIn(0, points.lastIndex)
    val fraction = (position - lower).coerceIn(0f, 1f)
    var travelled = 0.0
    for (index in 0 until lower) travelled += gpsDistanceMeters(points[index], points[index + 1])
    if (lower < points.lastIndex) travelled += gpsDistanceMeters(points[lower], points[lower + 1]) * fraction
    return (travelled / total).coerceIn(0.0, 1.0)
}

/** Fraction of the route's elapsed time covered at [progress], in 0..1. */
internal fun routeTimeFraction(points: List<GPSPointEntity>, progress: Float): Double {
    if (points.size < 2) return 0.0
    val span = points.last().timestamp - points.first().timestamp
    if (span <= 0L) return progress.coerceIn(0f, 1f).toDouble()
    val position = progress.coerceIn(0f, 1f) * points.lastIndex
    val lower = position.toInt().coerceIn(0, points.lastIndex)
    val fraction = (position - lower).coerceIn(0f, 1f)
    val elapsed = if (lower >= points.lastIndex) {
        span
    } else {
        (points[lower].timestamp - points.first().timestamp) +
            ((points[lower + 1].timestamp - points[lower].timestamp) * fraction).toLong()
    }
    return (elapsed.toDouble() / span).coerceIn(0.0, 1.0)
}
