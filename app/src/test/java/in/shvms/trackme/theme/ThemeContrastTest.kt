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

  // --- C1: brand-action vs semantic-success separation -------------------------------------
  // The 1.5.11 bug was a GREEN brand accent shipping against a CYAN logo. These tests pin the
  // classification from the C1 audit so the two roles can never be conflated again.

  @Test
  fun `brand action roles are cyan in both static schemes`() {
    // Brand-action surfaces (Start button, persona control, bottom-nav indicator) resolve
    // through primary/secondary. All of them must be cyan-family, never green.
    val brandRoles =
      listOf(
        "light primary" to TrackMeLightColorScheme.primary,
        "light secondary" to TrackMeLightColorScheme.secondary,
        "light secondary container" to TrackMeLightColorScheme.secondaryContainer,
        "light secondary fixed dim" to TrackMeLightColorScheme.secondaryFixedDim,
        "dark primary" to TrackMeDarkColorScheme.primary,
        "dark secondary" to TrackMeDarkColorScheme.secondary,
        "dark secondary container" to TrackMeDarkColorScheme.secondaryContainer,
        "dark secondary fixed dim" to TrackMeDarkColorScheme.secondaryFixedDim,
      )
    val cyanFamily = setOf(CyanBright, CyanDeep, CyanContainerLight)
    brandRoles.forEach { (role, color) ->
      assertTrue(
        "$role must be a cyan brand token but was $color",
        color in cyanFamily,
      )
      assertTrue("$role must never be semantic green", color != GreenGo)
    }
  }

  @Test
  fun `semantic success stays green and is not bound to any brand role`() {
    // Semantic success keeps GreenGo...
    assertTrue("SuccessGreen must remain the go/success hue", SuccessGreen == GreenGo)
    assertTrue("ChartSpeed series must remain green", ChartSpeed == GreenGo)
    // ...but must NOT be reachable through a Material brand role in either scheme, which is
    // exactly the coupling that caused the green Start button.
    listOf("light" to TrackMeLightColorScheme, "dark" to TrackMeDarkColorScheme).forEach {
      (name, scheme) ->
      assertTrue("$name secondary must not be success green", scheme.secondary != SuccessGreen)
      assertTrue(
        "$name secondary container must not be success green",
        scheme.secondaryContainer != SuccessGreen,
      )
      assertTrue("$name primary must not be success green", scheme.primary != SuccessGreen)
    }
  }

  @Test
  fun `brand action content pairs meet AA in both schemes`() {
    // The Start button paints content with onPrimary over primary. Light theme uses cyan/deep
    // (needs white), dark uses cyan/bright (needs navy) — the reason the old hardcoded
    // Navy900 content had to move to onPrimary.
    assertContrast(
      "light start button content",
      TrackMeLightColorScheme.onPrimary,
      TrackMeLightColorScheme.primary,
    )
    assertContrast(
      "dark start button content",
      TrackMeDarkColorScheme.onPrimary,
      TrackMeDarkColorScheme.primary,
    )
    // Regression guard: the STARTING/RELEASE/DRAG-TO-SELECT captions must stay at full
    // opacity. At the 90% alpha they used to carry, white on cyan/deep composites to 4.27:1,
    // which fails AA for 8-9sp text. If someone reintroduces the alpha, this fails.
    val lightCaptionAt90 =
      composite(
        TrackMeLightColorScheme.onPrimary.copy(alpha = 0.90f),
        TrackMeLightColorScheme.primary,
      )
    assertTrue(
      "start button captions must not be dimmed on light cyan (90% alpha = " +
        "${contrastRatio(lightCaptionAt90, TrackMeLightColorScheme.primary)}:1, below AA)",
      contrastRatio(lightCaptionAt90, TrackMeLightColorScheme.primary) < 4.5,
    )
  }

  @Test
  fun `cyan bright is never used as interactive cyan on light surfaces`() {
    // BRAND_SYSTEM contrast discipline: #29B6F6 fails AA on white, so light-scheme
    // interactive cyan must be cyan/deep.
    assertTrue(
      "light primary must be cyan/deep, not cyan/bright",
      TrackMeLightColorScheme.primary == CyanDeep,
    )
    assertTrue(
      "light secondary must be cyan/deep, not cyan/bright",
      TrackMeLightColorScheme.secondary == CyanDeep,
    )
  }

  @Test
  fun `dynamic color keeps safety pins and leaves semantic success unbound`() {
    // Material You replaces brand hues by design (TASK-026: dynamic color is opt-in, default
    // OFF, so cyan stays the out-of-box brand). What must survive a wallpaper palette is the
    // SOS/warning safety pinning — assert the pinned values Theme.kt copies in.
    listOf(true, false).forEach { isDark ->
      val pinnedError = RedSos
      val pinnedWarning = AmberWarn
      val pinnedErrorContainer = if (isDark) ErrorContainerDark else ErrorContainerLight
      val pinnedWarningContainer = if (isDark) AmberContainerDark else AmberContainerLight
      val label = if (isDark) "dynamic dark" else "dynamic light"

      assertContrast("$label SOS", Color.White, pinnedError)
      assertTrue("$label SOS must stay red/sos", pinnedError == RedSos)
      assertTrue("$label warning must stay amber/warn", pinnedWarning == AmberWarn)
      assertContrast("$label error container", pinnedErrorContainer.let {
        if (isDark) ErrorContainerLight else ErrorContainerDark
      }, pinnedErrorContainer)
      assertContrast("$label warning container", pinnedWarningContainer.let {
        if (isDark) AmberContainerLight else AmberContainerDark
      }, pinnedWarningContainer)
      // Safety colors must never collide with the brand accent or with success.
      assertTrue("$label SOS must not equal brand cyan", pinnedError != CyanBright)
      assertTrue("$label SOS must not equal success green", pinnedError != SuccessGreen)
      assertTrue("$label warning must not equal success green", pinnedWarning != SuccessGreen)
    }
    // Semantic success is a fixed token, so it is wallpaper-proof by construction.
    assertTrue("success token must be scheme-independent", SuccessGreen == GreenGo)
  }

  @Test
  fun `small alpha text states remain readable after compositing`() {
    assertContrast(
      "start ride helper text",
      TrackMeLightColorScheme.onPrimary,
      TrackMeLightColorScheme.primary,
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
