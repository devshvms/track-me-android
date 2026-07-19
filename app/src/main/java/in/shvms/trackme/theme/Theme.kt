package `in`.shvms.trackme.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

internal val TrackMeDarkColorScheme =
  darkColorScheme(
    primary = CyanBright,
    onPrimary = Navy900,
    primaryContainer = CyanDeep,
    onPrimaryContainer = Color.White,
    secondary = GreenGo,
    onSecondary = Navy900,
    secondaryContainer = GreenContainerDark,
    onSecondaryContainer = GreenContainerLight,
    tertiary = AmberWarn,
    onTertiary = Navy900,
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = AmberContainerLight,
    error = RedTextDark,
    onError = Navy900,
    errorContainer = ErrorContainerDark,
    onErrorContainer = ErrorContainerLight,
    background = Navy900,
    onBackground = Slate50,
    surface = Navy800,
    onSurface = Slate50,
    surfaceVariant = Navy700,
    onSurfaceVariant = Slate200,
    outline = Slate400,
    outlineVariant = Slate400,
    surfaceTint = CyanBright,
    inverseSurface = Slate50,
    inverseOnSurface = Slate800,
    inversePrimary = CyanDeep,
    scrim = Color.Black,
    surfaceBright = Slate700,
    surfaceDim = Navy900,
    surfaceContainerLowest = SurfaceDarkLowest,
    surfaceContainerLow = Navy900,
    surfaceContainer = Navy800,
    surfaceContainerHigh = Navy700,
    surfaceContainerHighest = Slate800,
    primaryFixed = CyanContainerLight,
    primaryFixedDim = CyanBright,
    onPrimaryFixed = Slate800,
    onPrimaryFixedVariant = Slate800,
    secondaryFixed = GreenContainerLight,
    secondaryFixedDim = GreenGo,
    onSecondaryFixed = Navy900,
    onSecondaryFixedVariant = Navy900,
    tertiaryFixed = AmberContainerLight,
    tertiaryFixedDim = AmberWarn,
    onTertiaryFixed = Slate800,
    onTertiaryFixedVariant = Slate800,
  )

internal val TrackMeLightColorScheme =
  lightColorScheme(
    primary = CyanDeep,
    onPrimary = Color.White,
    primaryContainer = CyanContainerLight,
    onPrimaryContainer = Slate800,
    secondary = GreenGo,
    onSecondary = Navy900,
    secondaryContainer = GreenContainerLight,
    onSecondaryContainer = GreenContainerDark,
    tertiary = AmberWarn,
    onTertiary = Slate800,
    tertiaryContainer = AmberContainerLight,
    onTertiaryContainer = AmberContainerDark,
    error = RedSos,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = ErrorContainerDark,
    background = Slate50,
    onBackground = Slate800,
    surface = Color.White,
    onSurface = Slate800,
    surfaceVariant = Slate200,
    onSurfaceVariant = Slate600,
    outline = Slate500,
    outlineVariant = Slate500,
    surfaceTint = CyanDeep,
    inverseSurface = Slate800,
    inverseOnSurface = Slate50,
    inversePrimary = CyanBright,
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Slate200,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Slate50,
    surfaceContainer = Slate100,
    surfaceContainerHigh = Slate200,
    surfaceContainerHighest = Slate300,
    primaryFixed = CyanContainerLight,
    primaryFixedDim = CyanBright,
    onPrimaryFixed = Slate800,
    onPrimaryFixedVariant = Slate800,
    secondaryFixed = GreenContainerLight,
    secondaryFixedDim = GreenGo,
    onSecondaryFixed = Navy900,
    onSecondaryFixedVariant = Navy900,
    tertiaryFixed = AmberContainerLight,
    tertiaryFixedDim = AmberWarn,
    onTertiaryFixed = Slate800,
    onTertiaryFixedVariant = Slate800,
  )

@Composable
fun TrackMeTheme(
  themeMode: Int = 0,
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Opt-in only: dynamic colors replace the locked TrackMe brand palette.
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

        // Wallpaper palettes must never dilute emergency or warning affordances.
        wallpaperScheme.copy(
          error = RedSos,
          onError = Color.White,
          errorContainer = if (isDark) ErrorContainerDark else ErrorContainerLight,
          onErrorContainer = if (isDark) ErrorContainerLight else ErrorContainerDark,
          tertiary = AmberWarn,
          onTertiary = Navy900,
          tertiaryContainer = if (isDark) AmberContainerDark else AmberContainerLight,
          onTertiaryContainer = if (isDark) AmberContainerLight else AmberContainerDark,
        )
      }
      isDark -> TrackMeDarkColorScheme
      else -> TrackMeLightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
