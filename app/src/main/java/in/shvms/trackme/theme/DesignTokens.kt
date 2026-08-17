package `in`.shvms.trackme.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ═══════════════════════════════════════════════════════════════════════════════════════════════
// SHAPE
// ═══════════════════════════════════════════════════════════════════════════════════════════════

/**
 * The Material 3 shape scale, unmodified: 4 / 8 / 12 / 16 / 28.
 *
 * These are M3's actual values, not preferences. An earlier draft of this work proposed
 * 4/8/14/22 — those middle values were invented, and "pure Material 3" means using the scale
 * Compose's own `ShapeDefaults` ships.
 *
 * Radii are assigned by *role*, not per call site: a card is `medium` everywhere, a sheet is
 * `extraLarge` everywhere. The one documented exception is the ride HUD, which applies
 * `extraLarge` to its **top corners only** — it is structurally a sheet rising from the bottom
 * edge, not a floating card.
 */
val TrackMeShapes = Shapes(
  extraSmall = RoundedCornerShape(4.dp),
  small = RoundedCornerShape(8.dp),
  medium = RoundedCornerShape(12.dp),
  large = RoundedCornerShape(16.dp),
  extraLarge = RoundedCornerShape(28.dp),
)

// ═══════════════════════════════════════════════════════════════════════════════════════════════
// SPACING
// ═══════════════════════════════════════════════════════════════════════════════════════════════

/**
 * The 4dp grid. Four values, not twelve.
 *
 * Master uses 8, 12, 16, 20 and 24dp roughly interchangeably, which is why nothing quite lines up
 * between tabs. Constraining the set is the fix; the values themselves are unremarkable.
 */
@Immutable
data class TrackMeSpacing(
  /** Screen edge margin. 24dp on tablets — phase 4. */
  val screenMargin: Dp = 16.dp,
  /** Between sibling cards in a list. */
  val betweenCards: Dp = 8.dp,
  /** Inside a card or sheet. */
  val cardPadding: Dp = 16.dp,
  /** Between labelled sections. */
  val sectionGap: Dp = 24.dp,
  /** Single-line list row. */
  val rowHeight: Dp = 56.dp,
  /** List row with supporting text. */
  val rowHeightWithSupport: Dp = 72.dp,
  /** Minimum touch target. No exceptions — enforced in review, lint in phase 2. */
  val minTouchTarget: Dp = 48.dp,
)

val LocalTrackMeSpacing = staticCompositionLocalOf { TrackMeSpacing() }

// ═══════════════════════════════════════════════════════════════════════════════════════════════
// ELEVATION
// ═══════════════════════════════════════════════════════════════════════════════════════════════

/**
 * The Material 3 elevation ladder, plus the rule that decides whether a level draws a shadow.
 *
 * **Shadow is reserved for surfaces the user can dismiss or drag. If a thing cannot be pushed
 * away, it does not cast.**
 *
 * That is not a style preference. The primary theme here is dark — a `#0E1418` ground — and a
 * black shadow on a near-black ground is invisible while still costing overdraw every frame. M3
 * draws elevation with tone first and shadow second for exactly this reason, which is why levels
 * 0–2 map to `surfaceContainer*` roles and draw nothing.
 *
 * See `docs/DESIGN_SYSTEM_1.8.md` §6.
 */
@Immutable
data class TrackMeElevation(
  /** Map ground, screen background. */
  val level0: Dp = 0.dp,
  /** Cards at rest, list rows. Tone only. */
  val level1: Dp = 1.dp,
  /** Navigation bar, app bar over scrolled content. Tone only. */
  val level2: Dp = 3.dp,
  /** HUD panel, sheets, dialogs, snackbar. Casts. */
  val level3: Dp = 6.dp,
  /** Hover and drag states only. Nothing rests here. Casts. */
  val level4: Dp = 8.dp,
  /** Dragged sheet, open menu, pressed FAB. Casts. */
  val level5: Dp = 12.dp,

  /**
   * Floating chrome over map imagery: control buttons, HUD status pills.
   *
   * Off the tone-first ladder deliberately. Tonal elevation is a tint applied to a *surface*, and
   * a map is not a surface — over imagery, tone conveys nothing at all, so a shadow is the only
   * separation available. This is the one place a low elevation legitimately casts, which is why
   * it is a named token rather than a `level2` that quietly breaks the rule above.
   */
  val mapOverlay: Dp = 3.dp,
) {
  /**
   * Whether a level draws a shadow, encoding the rule above so it cannot be got wrong at a call
   * site. Anything at or above [level3] floats and is dismissible; anything below is separated by
   * tone alone.
   *
   * [mapOverlay] is exempt by construction: it is not a rung on this ladder, and callers reach
   * for it by name precisely because the ladder does not apply over a map.
   */
  fun castsShadow(elevation: Dp): Boolean = elevation >= level3
}

val LocalTrackMeElevation = staticCompositionLocalOf { TrackMeElevation() }

// ═══════════════════════════════════════════════════════════════════════════════════════════════
// MOTION
// ═══════════════════════════════════════════════════════════════════════════════════════════════

/**
 * One spring, described by its physics rather than a duration.
 *
 * Durations are the problem being solved: a tween commits to one, so interrupting it mid-flight
 * restarts or snaps it. A spring carries velocity through the interruption, which is most of what
 * "unglitchy" means in the hand. Master runs 39 `tween` calls to 4 `spring` calls.
 */
@Immutable
data class MotionToken(val dampingRatio: Float, val stiffness: Float) {
  /** Materialise this token as a Compose spec. Generic so one token serves Float, Dp, Color… */
  fun <T> spec(): SpringSpec<T> = spring(dampingRatio = dampingRatio, stiffness = stiffness)
}

/**
 * The motion scheme — and the single most important abstraction in this branch.
 *
 * **Dependency inversion, applied to a concrete risk.** Material 3 Expressive's `MotionScheme`
 * lives in `material3:1.5.0-alpha*` behind `@ExperimentalMaterial3ExpressiveApi`. This app is on
 * **1.4.0 stable**. Depending on that alpha directly would put an alpha library on the critical
 * path of every animated screen.
 *
 * So screens depend on *this* type instead, and it is backed today by hand-written springs that
 * match the values Expressive publishes. When 1.5.0 stabilises, [Standard] is re-backed by the
 * real `MotionScheme` — roughly forty lines — and no screen changes. Liskov holds: any scheme is
 * substitutable for any other because all of them expose these same six tokens with these same
 * meanings.
 *
 * ### The rule that prevents most motion bugs
 * **Spatial** tokens move things and may overshoot — damping below 1.
 * **Effects** tokens change colour and opacity and must never overshoot — damping exactly 1.0.
 *
 * An alpha or colour that overshoots past its bound clips, producing a visible flash. Binding
 * that distinction into the token set means it cannot be got wrong at a call site.
 */
@Immutable
data class TrackMeMotionScheme(
  /** Chip select, switch thumb, icon toggles. */
  val spatialFast: MotionToken,
  /** Sheets, HUD rise, list placement, FAB. The default for anything that moves. */
  val spatialDefault: MotionToken,
  /** Screen transitions, map camera settle. */
  val spatialSlow: MotionToken,
  /** Ripple, pressed state, scrim. */
  val effectsFast: MotionToken,
  /** Fades, colour and elevation changes. */
  val effectsDefault: MotionToken,
  /** Long cross-fades. */
  val effectsSlow: MotionToken,
) {
  companion object {
    /**
     * Damping 0.9 throughout: settles with a single almost-imperceptible overshoot. Reads as
     * physical without reading as toy-like, which matters for a tool people use at 25km/h.
     */
    val Standard = TrackMeMotionScheme(
      spatialFast = MotionToken(dampingRatio = 0.9f, stiffness = 1400f),
      spatialDefault = MotionToken(dampingRatio = 0.9f, stiffness = 700f),
      spatialSlow = MotionToken(dampingRatio = 0.9f, stiffness = 300f),
      effectsFast = MotionToken(dampingRatio = 1.0f, stiffness = 3800f),
      effectsDefault = MotionToken(dampingRatio = 1.0f, stiffness = 1600f),
      effectsSlow = MotionToken(dampingRatio = 1.0f, stiffness = 800f),
    )

    /**
     * Springier, for when the app is allowed more personality. Effects stay critically damped —
     * that constraint is not negotiable regardless of how expressive the spatial motion gets.
     *
     * Not wired to anything yet; it exists to prove the open/closed claim, since adding it
     * required editing no other file.
     */
    val Expressive = TrackMeMotionScheme(
      spatialFast = MotionToken(dampingRatio = 0.7f, stiffness = 1400f),
      spatialDefault = MotionToken(dampingRatio = 0.6f, stiffness = 700f),
      spatialSlow = MotionToken(dampingRatio = 0.6f, stiffness = 300f),
      effectsFast = MotionToken(dampingRatio = 1.0f, stiffness = 3800f),
      effectsDefault = MotionToken(dampingRatio = 1.0f, stiffness = 1600f),
      effectsSlow = MotionToken(dampingRatio = 1.0f, stiffness = 800f),
    )
  }
}

val LocalTrackMeMotion = staticCompositionLocalOf { TrackMeMotionScheme.Standard }
