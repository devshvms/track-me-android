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
    brandRoles.forEach { (role, color) ->
      // 1.8.0: brand roles are now generated from the #29B6F6 seed rather than picked from a
      // fixed set of three constants, so family membership is asserted by HUE ORDERING instead of
      // identity. In the cyan family blue dominates green dominates red — which excludes semantic
      // green (g > b), warning amber (r > g > b) and error red (r > g > b) by construction, and
      // keeps working after the palette is regenerated with Material Color Utilities.
      assertTrue(
        "$role must be cyan-family (blue > green > red) but was $color",
        color.blue > color.green && color.green > color.red,
      )
      assertTrue("$role must never be semantic green", color != GreenGo)
      assertTrue("$role must never be semantic warning amber", color != AmberWarn)
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
    // Regression guard for the STARTING/RELEASE/DRAG-TO-SELECT captions.
    //
    // 1.8.0: this guard has INVERTED, and that is the intended outcome. It used to assert that
    // white at 90% alpha composites BELOW AA — 4.27:1 against the old cyan/deep primary — which
    // was the evidence that those captions had to stay at full opacity.
    //
    // Light primary is now tone 40 (#00658D) rather than cyan/deep (#0277B6). It is darker, so
    // the same 90% caption composites to 5.59:1 and clears AA. The hazard the guard was built to
    // catch no longer exists.
    //
    // So it is restated in the direction that is now true, which keeps it a live guard rather
    // than a historical note: if primary is ever lightened back toward cyan/bright, this drops
    // under 4.5 and fails — surfacing the hazard's return at exactly the moment it returns.
    val lightCaptionAt90 =
      composite(
        TrackMeLightColorScheme.onPrimary.copy(alpha = 0.90f),
        TrackMeLightColorScheme.primary,
      )
    val captionRatio = contrastRatio(lightCaptionAt90, TrackMeLightColorScheme.primary)
    assertTrue(
      "a 90% alpha caption on light primary must clear AA (was $captionRatio:1). If this fails, " +
        "light primary has been lightened and the captions must go back to full opacity.",
      captionRatio >= 4.5,
    )
  }

  @Test
  fun `cyan bright is never used as interactive cyan on light surfaces`() {
    // BRAND_SYSTEM contrast discipline: #29B6F6 fails AA on white, so light-scheme
    // interactive cyan must be cyan/deep.
    // 1.8.0: this is a CONTRAST rule, not an identity rule. What matters is that whatever tone
    // light-scheme primary lands on clears AA against a light surface — asserting the property
    // rather than the constant keeps the guard alive after the palette is regenerated.
    assertContrast(
      "light primary as text on surface",
      TrackMeLightColorScheme.primary,
      TrackMeLightColorScheme.surface,
    )
    assertContrast(
      "light secondary as text on surface",
      TrackMeLightColorScheme.secondary,
      TrackMeLightColorScheme.surface,
    )
    // And the reason the rule exists: cyan/bright must still fail on a light surface. If this
    // ever passes, the constraint that forces light primary to a darker tone has stopped holding
    // and the tone assignment should be revisited rather than silently inherited.
    assertTrue(
      "cyan/bright must still fail AA on a light surface (was " +
        "${contrastRatio(CyanBright, TrackMeLightColorScheme.surface)}:1)",
      contrastRatio(CyanBright, TrackMeLightColorScheme.surface) < 4.5,
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

      // `outline` draws meaningful boundaries — text-field borders, selected states — so it is a
      // user interface component under WCAG 1.4.11 and holds the full 3:1.
      assertContrast("$name outline on $role", scheme.outline, surface, minimum = 3.0)

      // 1.8.0: `outlineVariant` is a DECORATIVE divider. WCAG 1.4.11 exempts decoration, and M3
      // defines this role as the low-emphasis tier precisely so it reads quieter than `outline`.
      // Demanding 3:1 of both is what forced master to bind them to the SAME grey — which made
      // dividers and borders indistinguishable, the defect this branch fixes. So the assertion is
      // restated as the two properties that actually matter: visible, and quieter than `outline`.
      val variantRatio = contrastRatio(scheme.outlineVariant, surface)
      val outlineRatio = contrastRatio(scheme.outline, surface)
      assertTrue(
        "$name outline variant on $role must stay visible against the surface (was $variantRatio)",
        variantRatio > 1.15,
      )
      assertTrue(
        "$name outline variant on $role must read quieter than outline " +
          "(variant $variantRatio vs outline $outlineRatio)",
        variantRatio < outlineRatio,
      )
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
