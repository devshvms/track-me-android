package `in`.shvms.trackme.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * Backwards-compatible aliases.
 *
 * `ThemeContrastTest` and any future caller reference these names. The schemes themselves now
 * live in [TrackMeColorSchemes.kt] and are generated from tone positions rather than hand-picked
 * hex values — see `docs/DESIGN_SYSTEM_1.8.md`.
 */
internal val TrackMeLightColorScheme = TrackMeLightScheme
internal val TrackMeDarkColorScheme = TrackMeDarkScheme

/**
 * The app theme.
 *
 * This function **composes and provides**; it holds no values. Every colour, shape, spacing,
 * elevation and motion constant lives in a token file with a single responsibility, and changing
 * one of them must never require editing this file.
 *
 * Ambient tokens are provided as separate [CompositionLocalProvider] entries rather than one
 * bundled object, so a screen that reads motion does not acquire a dependency on spacing.
 *
 * @param themeMode 0 = follow system, 1 = light, 2 = dark. Unchanged from 1.7.x.
 * @param dynamicColor opt-in only. Wallpaper palettes replace the brand hues by design.
 */
@Composable
fun TrackMeTheme(
  themeMode: Int = 0,
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val isDark =
    when (themeMode) {
      1 -> false
      2 -> true
      else -> darkTheme
    }

  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        val wallpaperScheme =
          if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        // A wallpaper palette must never dilute the error affordance. Only the error family is
        // pinned now — warning no longer lives in ColorScheme at all (it moved to
        // TrackMeSemantics, because `tertiary` is a brand accent slot, not a state slot), which
        // makes it wallpaper-proof by construction rather than by patching.
        if (isDark) {
          wallpaperScheme.copy(
            error = Tone.Error80,
            onError = Tone.Error20,
            errorContainer = Tone.Error30,
            onErrorContainer = Tone.Error90,
          )
        } else {
          wallpaperScheme.copy(
            error = Tone.Error40,
            onError = Tone.Error100,
            errorContainer = Tone.Error90,
            onErrorContainer = Tone.Error10,
          )
        }
      }
      isDark -> TrackMeDarkScheme
      else -> TrackMeLightScheme
    }

  val semantics = if (isDark) TrackMeSemantics.Dark else TrackMeSemantics.Light

  CompositionLocalProvider(
    LocalTrackMeSemantics provides semantics,
    LocalTrackMeSpacing provides TrackMeSpacing(),
    LocalTrackMeElevation provides TrackMeElevation(),
    LocalTrackMeMotion provides TrackMeMotionScheme.Standard,
  ) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      shapes = TrackMeShapes,
      content = content,
    )
  }
}
