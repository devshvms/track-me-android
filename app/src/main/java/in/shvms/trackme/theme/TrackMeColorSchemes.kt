package `in`.shvms.trackme.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic design tokens: Material 3 roles bound to tone positions.
 *
 * Every line names its tone in a comment. That is the whole review surface — if a role is at the
 * wrong tone, it is visible here without opening a colour picker.
 *
 * The Material role vocabulary is used unmodified. We do not invent a name where M3 has one, so
 * `surfaceContainerHigh` is called `surfaceContainerHigh`. Concepts M3 genuinely has no role for
 * — success and warning — live in [TrackMeSemantics] instead, kept deliberately separate so they
 * cannot be mistaken for Material roles.
 *
 * See `docs/DESIGN_SYSTEM_1.8.md` §4.2.
 */
internal val TrackMeLightScheme: ColorScheme = lightColorScheme(
  primary = Tone.Primary40,
  onPrimary = Tone.Primary100,
  primaryContainer = Tone.Primary90,
  onPrimaryContainer = Tone.Primary10,
  inversePrimary = Tone.Primary80,

  secondary = Tone.Secondary40,
  onSecondary = Tone.Secondary100,
  secondaryContainer = Tone.Secondary90,
  onSecondaryContainer = Tone.Secondary10,

  // On master `tertiary` was bound to amber and used for warnings. `tertiary` is a brand accent
  // role, not a state role — same category error as the 1.5.11 green Start button. Warning moved
  // to TrackMeSemantics; tertiary now takes the tone M3 actually derives (seed hue + 60°).
  tertiary = Tone.Tertiary40,
  onTertiary = Tone.Tertiary100,
  tertiaryContainer = Tone.Tertiary90,
  onTertiaryContainer = Tone.Tertiary10,

  error = Tone.Error40,
  onError = Tone.Error100,
  errorContainer = Tone.Error90,
  onErrorContainer = Tone.Error10,

  background = Tone.Neutral98,
  onBackground = Tone.Neutral10,
  surface = Tone.Neutral98,
  onSurface = Tone.Neutral10,
  surfaceVariant = Tone.NeutralVariant90,
  onSurfaceVariant = Tone.NeutralVariant30,
  surfaceTint = Tone.Primary40,

  inverseSurface = Tone.Neutral20,
  inverseOnSurface = Tone.Neutral95,

  // These were bound to the SAME grey on master, so dividers and borders were indistinguishable.
  outline = Tone.NeutralVariant50,
  outlineVariant = Tone.NeutralVariant80,
  scrim = Tone.Neutral0,

  surfaceBright = Tone.Neutral98,
  surfaceDim = Tone.Neutral87,
  surfaceContainerLowest = Tone.Neutral100,
  surfaceContainerLow = Tone.Neutral96,
  surfaceContainer = Tone.Neutral94,
  surfaceContainerHigh = Tone.Neutral92,
  surfaceContainerHighest = Tone.Neutral90,

  primaryFixed = Tone.Primary90,
  primaryFixedDim = Tone.Primary80,
  onPrimaryFixed = Tone.Primary10,
  onPrimaryFixedVariant = Tone.Primary30,
  secondaryFixed = Tone.Secondary90,
  secondaryFixedDim = Tone.Secondary80,
  onSecondaryFixed = Tone.Secondary10,
  onSecondaryFixedVariant = Tone.Secondary30,
  tertiaryFixed = Tone.Tertiary90,
  tertiaryFixedDim = Tone.Tertiary80,
  onTertiaryFixed = Tone.Tertiary10,
  onTertiaryFixedVariant = Tone.Tertiary30,
)

/**
 * The primary theme for this app — most users run dark.
 *
 * Compare to master: `Navy900 #12161C` → `Neutral6 #0E1418`, `Navy800 #181A20` →
 * `Neutral12 #1B2025`, `Navy700 #23272F` → `Neutral17 #252B2F`. Within 2–3 tone points, so the
 * app still looks like itself — but `background` and `surfaceContainerLow` are no longer the same
 * colour, which is what gives low elevation somewhere to separate from.
 */
internal val TrackMeDarkScheme: ColorScheme = darkColorScheme(
  primary = Tone.Primary80,
  onPrimary = Tone.Primary20,
  primaryContainer = Tone.Primary30,
  onPrimaryContainer = Tone.Primary90,
  inversePrimary = Tone.Primary40,

  secondary = Tone.Secondary80,
  onSecondary = Tone.Secondary20,
  secondaryContainer = Tone.Secondary30,
  onSecondaryContainer = Tone.Secondary90,

  tertiary = Tone.Tertiary80,
  onTertiary = Tone.Tertiary20,
  tertiaryContainer = Tone.Tertiary30,
  onTertiaryContainer = Tone.Tertiary90,

  error = Tone.Error80,
  onError = Tone.Error20,
  errorContainer = Tone.Error30,
  onErrorContainer = Tone.Error90,

  background = Tone.Neutral6,
  onBackground = Tone.Neutral90,
  surface = Tone.Neutral6,
  onSurface = Tone.Neutral90,
  surfaceVariant = Tone.NeutralVariant30,
  onSurfaceVariant = Tone.NeutralVariant80,
  surfaceTint = Tone.Primary80,

  inverseSurface = Tone.Neutral90,
  inverseOnSurface = Tone.Neutral20,

  outline = Tone.NeutralVariant60,
  outlineVariant = Tone.NeutralVariant30,
  scrim = Tone.Neutral0,

  surfaceBright = Tone.Neutral24,
  surfaceDim = Tone.Neutral6,
  surfaceContainerLowest = Tone.Neutral4,
  surfaceContainerLow = Tone.Neutral10,
  surfaceContainer = Tone.Neutral12,
  surfaceContainerHigh = Tone.Neutral17,
  surfaceContainerHighest = Tone.Neutral22,

  // "Fixed" roles are identical across schemes by definition — that is what fixed means.
  primaryFixed = Tone.Primary90,
  primaryFixedDim = Tone.Primary80,
  onPrimaryFixed = Tone.Primary10,
  onPrimaryFixedVariant = Tone.Primary30,
  secondaryFixed = Tone.Secondary90,
  secondaryFixedDim = Tone.Secondary80,
  onSecondaryFixed = Tone.Secondary10,
  onSecondaryFixedVariant = Tone.Secondary30,
  tertiaryFixed = Tone.Tertiary90,
  tertiaryFixedDim = Tone.Tertiary80,
  onTertiaryFixed = Tone.Tertiary10,
  onTertiaryFixedVariant = Tone.Tertiary30,
)

/**
 * State colours Material 3 has no role for.
 *
 * Kept out of [ColorScheme] on purpose. Binding "success" to `secondary` or "warning" to
 * `tertiary` collapses a brand slot into a state slot, and the comment in `Color.kt` already
 * records what that costs — a green Start button in 1.5.11 while every store asset was cyan.
 *
 * Interface segregation: a screen that needs a warning colour reads this, and does not acquire a
 * dependency on spacing, motion or elevation to get it.
 */
@Immutable
data class TrackMeSemantics(
  val success: Color,
  val onSuccess: Color,
  val successContainer: Color,
  val onSuccessContainer: Color,
  val warning: Color,
  val onWarning: Color,
  val warningContainer: Color,
  val onWarningContainer: Color,
) {
  companion object {
    val Light = TrackMeSemantics(
      success = Tone.Success40,
      onSuccess = Color.White,
      successContainer = Tone.Success90,
      onSuccessContainer = Tone.Success10,
      // Tone 40, not the tone-72 amber on master. That one measures 2.15:1 on white and
      // fails WCAG AA; this measures 6.17:1. See DESIGN_SYSTEM_1.8.md §7.
      warning = Tone.Warning40,
      onWarning = Color.White,
      warningContainer = Tone.Warning90,
      onWarningContainer = Tone.Warning10,
    )

    val Dark = TrackMeSemantics(
      success = Tone.Success80,
      onSuccess = Tone.Success20,
      successContainer = Tone.Success30,
      onSuccessContainer = Tone.Success90,
      warning = Tone.Warning80,
      onWarning = Tone.Warning20,
      warningContainer = Tone.Warning30,
      onWarningContainer = Tone.Warning90,
    )
  }
}

/** Ambient semantics. Static because the value is swapped by [TrackMeTheme], never mutated. */
val LocalTrackMeSemantics = staticCompositionLocalOf { TrackMeSemantics.Dark }
