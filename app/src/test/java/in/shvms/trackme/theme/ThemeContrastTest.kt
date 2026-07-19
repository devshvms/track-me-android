package `in`.shvms.trackme.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ThemeContrastTest {
  @Test
  fun `light color scheme maps every readable role with AA contrast`() {
    assertSchemeContrast("light", TrackMeLightColorScheme)
  }

  @Test
  fun `dark color scheme maps every readable role with AA contrast`() {
    assertSchemeContrast("dark", TrackMeDarkColorScheme)
  }

  @Test
  fun `small alpha text states remain readable after compositing`() {
    assertContrast(
      "start ride helper text",
      composite(Navy900.copy(alpha = 0.90f), GreenGo),
      GreenGo,
    )
    assertContrast(
      "active ride label",
      TrackMeLightColorScheme.onSurface,
      TrackMeLightColorScheme.surface,
    )
    assertContrast("SOS arrows", Color.White, RedSos)
  }

  private fun assertSchemeContrast(name: String, scheme: ColorScheme) {
    val textPairs =
      listOf(
        "primary" to (scheme.onPrimary to scheme.primary),
        "primary container" to (scheme.onPrimaryContainer to scheme.primaryContainer),
        "secondary" to (scheme.onSecondary to scheme.secondary),
        "secondary container" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
        "tertiary" to (scheme.onTertiary to scheme.tertiary),
        "tertiary container" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
        "background" to (scheme.onBackground to scheme.background),
        "surface" to (scheme.onSurface to scheme.surface),
        "surface variant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
        "inverse surface" to (scheme.inverseOnSurface to scheme.inverseSurface),
        "error" to (scheme.onError to scheme.error),
        "error container" to (scheme.onErrorContainer to scheme.errorContainer),
        "primary fixed strong on fixed" to (scheme.onPrimaryFixed to scheme.primaryFixed),
        "primary fixed strong on dim" to (scheme.onPrimaryFixed to scheme.primaryFixedDim),
        "primary fixed variant on fixed" to (scheme.onPrimaryFixedVariant to scheme.primaryFixed),
        "primary fixed variant on dim" to (scheme.onPrimaryFixedVariant to scheme.primaryFixedDim),
        "secondary fixed strong on fixed" to (scheme.onSecondaryFixed to scheme.secondaryFixed),
        "secondary fixed strong on dim" to (scheme.onSecondaryFixed to scheme.secondaryFixedDim),
        "secondary fixed variant on fixed" to (scheme.onSecondaryFixedVariant to scheme.secondaryFixed),
        "secondary fixed variant on dim" to (scheme.onSecondaryFixedVariant to scheme.secondaryFixedDim),
        "tertiary fixed strong on fixed" to (scheme.onTertiaryFixed to scheme.tertiaryFixed),
        "tertiary fixed strong on dim" to (scheme.onTertiaryFixed to scheme.tertiaryFixedDim),
        "tertiary fixed variant on fixed" to (scheme.onTertiaryFixedVariant to scheme.tertiaryFixed),
        "tertiary fixed variant on dim" to (scheme.onTertiaryFixedVariant to scheme.tertiaryFixedDim),
      )

    textPairs.forEach { (role, colors) ->
      assertDefined("$name $role foreground", colors.first)
      assertDefined("$name $role background", colors.second)
      assertContrast("$name $role", colors.first, colors.second)
    }

    val surfaces =
      listOf(
        "surface bright" to scheme.surfaceBright,
        "surface dim" to scheme.surfaceDim,
        "surface container lowest" to scheme.surfaceContainerLowest,
        "surface container low" to scheme.surfaceContainerLow,
        "surface container" to scheme.surfaceContainer,
        "surface container high" to scheme.surfaceContainerHigh,
        "surface container highest" to scheme.surfaceContainerHighest,
      )
    surfaces.forEach { (role, surface) ->
      assertDefined("$name $role", surface)
      assertContrast("$name text on $role", scheme.onSurface, surface)
      assertContrast("$name outline on $role", scheme.outline, surface, minimum = 3.0)
      assertContrast("$name outline variant on $role", scheme.outlineVariant, surface, minimum = 3.0)
    }
  }

  private fun assertDefined(name: String, color: Color) {
    assertTrue("$name must be explicitly defined", color != Color.Unspecified)
  }

  private fun assertContrast(
    name: String,
    foreground: Color,
    background: Color,
    minimum: Double = 4.5,
  ) {
    val ratio = contrastRatio(foreground, background)
    assertTrue("$name contrast was $ratio, expected at least $minimum", ratio >= minimum)
  }

  private fun composite(foreground: Color, background: Color): Color {
    val alpha = foreground.alpha
    return Color(
      red = foreground.red * alpha + background.red * (1f - alpha),
      green = foreground.green * alpha + background.green * (1f - alpha),
      blue = foreground.blue * alpha + background.blue * (1f - alpha),
      alpha = 1f,
    )
  }

  private fun contrastRatio(first: Color, second: Color): Double {
    val firstLuminance = luminance(first)
    val secondLuminance = luminance(second)
    return (max(firstLuminance, secondLuminance) + 0.05) /
      (min(firstLuminance, secondLuminance) + 0.05)
  }

  private fun luminance(color: Color): Double {
    fun channel(value: Float): Double {
      val normalized = value.toDouble()
      return if (normalized <= 0.04045) {
        normalized / 12.92
      } else {
        ((normalized + 0.055) / 1.055).pow(2.4)
      }
    }

    return 0.2126 * channel(color.red) +
      0.7152 * channel(color.green) +
      0.0722 * channel(color.blue)
  }
}
