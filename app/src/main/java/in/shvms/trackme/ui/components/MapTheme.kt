package `in`.shvms.trackme.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.maps.model.MapStyleOptions
import `in`.shvms.trackme.R
import `in`.shvms.trackme.TrackMeApp

/**
 * The map style that matches the app's current theme.
 *
 * ### Why this exists
 * Until 1.8.0 the map had no styling at all — no `MapStyleOptions`, no night style, no raw style
 * resource. It rendered Google's default light basemap regardless of theme, so a dark-theme user
 * opening the app at night got a full-screen sheet of white.
 *
 * It also quietly constrained everything drawn on top. Route polylines, HUD pills and markers were
 * all tuned for a light background, which is why they use fixed colours rather than theme roles —
 * correctly, given the map they sat on. Fixing the basemap is what makes theming those overlays
 * possible at all, which is why it comes first.
 *
 * ### Why it reads the app's own theme setting, not just the system
 * `themeMode` is a user preference with three values — follow system, always light, always dark.
 * Reading `isSystemInDarkTheme()` alone would leave the map light for someone who has forced dark
 * inside the app, which is precisely the mismatch this is meant to remove.
 *
 * Returns `null` for the light theme, which is what `MapProperties.mapStyleOptions` expects for
 * "use the default basemap" — the light default is Google's own and needs no override.
 */
@Composable
fun rememberMapStyle(): MapStyleOptions? {
  val context = LocalContext.current
  val app = context.applicationContext as TrackMeApp
  val themeMode by app.preferencesManager.themeMode.collectAsState()
  val systemDark = isSystemInDarkTheme()

  val isDark = when (themeMode) {
    1 -> false
    2 -> true
    else -> systemDark
  }

  return remember(isDark) {
    if (isDark) MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_night) else null
  }
}
