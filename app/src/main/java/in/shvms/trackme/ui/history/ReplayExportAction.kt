package `in`.shvms.trackme.ui.history

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import `in`.shvms.trackme.config.AppConfig
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.replay.MediaCodecReplayExporter
import `in`.shvms.trackme.domain.replay.ReplayExportConfig
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

@Composable
fun ReplayExportAction(
    rideWithPoints: RideWithPoints,
    context: Context,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    var exporting by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    val hasEnoughPoints = rideWithPoints.points.size >= 2

    TextButton(
        onClick = {
            if (exporting) {
                exportJob?.cancel()
                return@TextButton
            }
            if (!hasEnoughPoints) {
                android.widget.Toast.makeText(context, strings.replayExportNotEnoughGps, android.widget.Toast.LENGTH_SHORT).show()
                return@TextButton
            }
            exporting = true
            progress = 0f
            val lastPublishedProgress = AtomicReference(-1f)
            val persona = runCatching { RidePersona.valueOf(rideWithPoints.ride.persona) }
                .getOrDefault(RidePersona.AUTO)
            val deepLinkId = rideWithPoints.ride.firestoreId?.takeLast(12) ?: rideWithPoints.ride.id.toString()
            exportJob = scope.launch {
                try {
                    val result = withContext(Dispatchers.Default) {
                        MediaCodecReplayExporter().exportReplay(
                            rideWithPoints = rideWithPoints,
                            config = ReplayExportConfig(
                                persona = persona,
                                deepLink = "${AppConfig.REPLAY_DEEP_LINK_BASE_URL}$deepLinkId"
                            ),
                            outputDirectory = File(context.cacheDir, AppConfig.EXPORT_DIR_NAME),
                            onProgress = { value ->
                                val previous = lastPublishedProgress.get()
                                if ((value == 1f || value - previous >= 0.02f) &&
                                    lastPublishedProgress.compareAndSet(previous, value)
                                ) {
                                    scope.launch(Dispatchers.Main.immediate) { progress = value }
                                }
                            }
                        )
                    }
                    result.onSuccess { file ->
                        shareReplay(context, file)
                    }.onFailure {
                        android.widget.Toast.makeText(context, strings.replayExportFailed, android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (_: CancellationException) {
                    // Cancellation is user-controlled and intentionally silent.
                    throw CancellationException()
                } finally {
                    exporting = false
                    exportJob = null
                }
            }
        },
        enabled = !exporting || hasEnoughPoints,
        modifier = modifier
    ) {
        Icon(Icons.Default.Movie, contentDescription = strings.replayExportTitle)
        Text(
            text = when {
                exporting -> strings.replayExportProgress.format((progress * 100).roundToInt())
                else -> strings.replayExportButton
            },
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun shareReplay(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "TrackMe"))
}
