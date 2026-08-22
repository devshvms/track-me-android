package `in`.shvms.trackme.ui.onboarding

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.TextureView
import androidx.annotation.RawRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/** Pure render policy used by both the composable and its forced-failure tests. */
internal fun shouldRenderOnboardingClip(
    reduceMotion: Boolean,
    playerFailed: Boolean,
): Boolean = !reduceMotion && !playerFailed

/**
 * Silent, looping onboarding media with the vector art permanently available underneath it.
 *
 * The texture stays transparent until Media3 reports its first rendered frame, which prevents
 * the video surface from replacing the composed poster art with a black attach frame. A clip
 * that is not the pager's settled page remains prepared but paused, and every player is released
 * with the composition that owns it.
 */
@Composable
internal fun OnboardingClip(
    @RawRes dark: Int,
    @RawRes light: Int,
    isActive: Boolean,
    fallback: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resourceId = if (isSystemInDarkTheme()) dark else light
    val reduceMotion = rememberAnimatorScaleDisabled()
    var playerFailed by remember(resourceId) { mutableStateOf(false) }
    val renderVideo = shouldRenderOnboardingClip(reduceMotion, playerFailed)

    Box(modifier) {
        fallback()
        if (renderVideo) {
            OnboardingVideoPlayer(
                resourceId = resourceId,
                isActive = isActive,
                onFailure = { playerFailed = true },
            )
        }
    }
}

@Composable
private fun OnboardingVideoPlayer(
    @RawRes resourceId: Int,
    isActive: Boolean,
    onFailure: () -> Unit,
) {
    val context = LocalContext.current
    var renderedFirstFrame by remember(resourceId) { mutableStateOf(false) }
    val currentOnFailure by rememberUpdatedState(onFailure)
    val player = remember(context, resourceId) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            val resourceUri = Uri.Builder()
                .scheme("android.resource")
                .authority(context.packageName)
                .appendPath(resourceId.toString())
                .build()
            setMediaItem(MediaItem.fromUri(resourceUri))
            prepare()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                renderedFirstFrame = true
            }

            override fun onPlayerError(error: PlaybackException) {
                currentOnFailure()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, isActive) {
        if (isActive) player.play() else player.pause()
    }

    key(player) {
        AndroidView(
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    // `isOpaque = false` is the whole transparency story here. Do NOT add a
                    // background: TextureView.setBackgroundDrawable throws
                    // UnsupportedOperationException unconditionally, and setBackgroundColor
                    // routes straight to it. Calling it crashed every first run on 1.8.2 the
                    // instant this view was constructed, before onboarding drew a single frame.
                    isOpaque = false
                    player.setVideoTextureView(this)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (renderedFirstFrame) 1f else 0f),
        )
    }
}

/** Tracks the system animation scale while onboarding is visible, including an in-place toggle. */
@Composable
private fun rememberAnimatorScaleDisabled(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    fun isDisabled(): Boolean = Settings.Global.getFloat(
        resolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f

    var disabled by remember(resolver) { mutableStateOf(isDisabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                disabled = isDisabled()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return disabled
}
