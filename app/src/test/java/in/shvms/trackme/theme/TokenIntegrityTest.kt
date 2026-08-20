package `in`.shvms.trackme.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Structural invariants of the token layer.
 *
 * `ThemeContrastTest` answers "is it readable". This answers "is it *coherent*" — the class of
 * defect where two roles that must differ are bound to the same value, or a ramp is not actually
 * ordered. Every assertion here corresponds to a real defect found on `master` during the 1.8.0
 * audit, so these are regression guards rather than hypotheticals.
 *
 * See `docs/DESIGN_SYSTEM_1.8.md` §7.
 */
class TokenIntegrityTest {

  private val schemes = listOf("light" to TrackMeLightScheme, "dark" to TrackMeDarkScheme)

  // ── colour role distinctness ───────────────────────────────────────────────────────────────

  @Test
  fun `outline and outline variant are different colours`() {
    // MASTER DEFECT: both were bound to one grey (Slate400 dark / Slate500 light), so a divider
    // and a border were indistinguishable. The two roles exist to be two emphasis tiers.
    schemes.forEach { (name, scheme) ->
      assertNotEquals(
        "$name outline and outlineVariant must not be the same colour",
        scheme.outline,
        scheme.outlineVariant,
      )
    }
  }

  @Test
  fun `background is not the same as the lowest container`() {
    // MASTER DEFECT: dark `background` and `surfaceContainerLow` were both Navy900, so nothing at
    // low elevation had anything to separate from.
    schemes.forEach { (name, scheme) ->
      assertNotEquals(
        "$name background and surfaceContainerLow must differ or low elevation is invisible",
        scheme.background,
        scheme.surfaceContainerLow,
      )
    }
  }

  @Test
  fun `surface container ramp is monotonic`() {
    // Each step must actually be a step. A ramp that reverses or plateaus produces elevation that
    // reads as lower than the surface beneath it.
    schemes.forEach { (name, scheme) ->
      val ramp =
        listOf(
          "containerLowest" to scheme.surfaceContainerLowest,
          "containerLow" to scheme.surfaceContainerLow,
          "container" to scheme.surfaceContainer,
          "containerHigh" to scheme.surfaceContainerHigh,
          "containerHighest" to scheme.surfaceContainerHighest,
        )
      // Light gets lighter as it rises; dark gets lighter as it rises too — in both schemes the
      // container ramp moves away from the ground, so luminance is strictly monotonic. Direction
      // differs, so derive it from the first step rather than assuming.
      val ascending = luminance(ramp[1].second) > luminance(ramp[0].second)
      ramp.zipWithNext { (aName, a), (bName, b) ->
        val la = luminance(a)
        val lb = luminance(b)
        assertTrue(
          "$name ramp must be monotonic: $aName ($la) -> $bName ($lb) reverses direction",
          if (ascending) lb > la else lb < la,
        )
      }
    }
  }

  @Test
  fun `tertiary is not bound to the warning hue`() {
    // MASTER DEFECT: `tertiary` was amber and used for warnings. `tertiary` is a brand accent
    // role, not a state role — the same category error as the 1.5.11 green Start button that
    // Color.kt already documents. Warning now lives in TrackMeSemantics.
    schemes.forEach { (name, scheme) ->
      assertNotEquals("$name tertiary must not be warning amber", AmberWarn, scheme.tertiary)
      assertTrue(
        "$name tertiary must not be warm (warning/error family) — was ${scheme.tertiary}",
        scheme.tertiary.red < scheme.tertiary.blue,
      )
    }
  }

  // ── semantic separation ────────────────────────────────────────────────────────────────────

  @Test
  fun `semantic colours are distinct from each other and from the brand`() {
    listOf("light" to TrackMeSemantics.Light, "dark" to TrackMeSemantics.Dark)
      .forEach { (name, semantics) ->
        val scheme = if (name == "light") TrackMeLightScheme else TrackMeDarkScheme
        assertNotEquals("$name success must not equal warning", semantics.success, semantics.warning)
        assertNotEquals("$name success must not equal brand primary", semantics.success, scheme.primary)
        assertNotEquals("$name warning must not equal brand primary", semantics.warning, scheme.primary)
        assertNotEquals("$name success must not equal error", semantics.success, scheme.error)
      }
  }

  @Test
  fun `warning is readable on its own surface in both schemes`() {
    // MASTER DEFECT: AmberWarn on white measures 2.15:1, well under AA. That is a live
    // accessibility bug, not a redesign preference.
    assertTrue(
      "light warning on light surface was ${ratio(TrackMeSemantics.Light.warning, TrackMeLightScheme.surface)}",
      ratio(TrackMeSemantics.Light.warning, TrackMeLightScheme.surface) >= 4.5,
    )
    assertTrue(
      "dark warning on dark surface was ${ratio(TrackMeSemantics.Dark.warning, TrackMeDarkScheme.surface)}",
      ratio(TrackMeSemantics.Dark.warning, TrackMeDarkScheme.surface) >= 4.5,
    )
    assertTrue(
      "the amber that shipped on master must still fail on a light surface, or the reason this " +
        "token moved has stopped being true",
      ratio(AmberWarn, TrackMeLightScheme.surface) < 4.5,
    )
  }

  // ── motion ─────────────────────────────────────────────────────────────────────────────────

  @Test
  fun `effects springs are critically damped and spatial springs are not`() {
    // The rule that prevents the flash-on-fade class of bug: a spring on alpha or colour that
    // overshoots past its bound clips. Effects must never overshoot; spatial may.
    listOf(
      "Standard" to TrackMeMotionScheme.Standard,
      "Expressive" to TrackMeMotionScheme.Expressive,
    ).forEach { (name, scheme) ->
      listOf(
        "effectsFast" to scheme.effectsFast,
        "effectsDefault" to scheme.effectsDefault,
        "effectsSlow" to scheme.effectsSlow,
      ).forEach { (token, value) ->
        assertEquals(
          "$name $token must be critically damped (1.0) so alpha cannot overshoot and clip",
          1.0f,
          value.dampingRatio,
          0.0001f,
        )
      }
      listOf(
        "spatialFast" to scheme.spatialFast,
        "spatialDefault" to scheme.spatialDefault,
        "spatialSlow" to scheme.spatialSlow,
      ).forEach { (token, value) ->
        assertTrue(
          "$name $token must be under-damped (< 1.0) or it is a tween with extra steps",
          value.dampingRatio < 1.0f,
        )
      }
    }
  }

  @Test
  fun `motion stiffness decreases from fast to slow`() {
    val s = TrackMeMotionScheme.Standard
    assertTrue("spatialFast must be stiffer than default", s.spatialFast.stiffness > s.spatialDefault.stiffness)
    assertTrue("spatialDefault must be stiffer than slow", s.spatialDefault.stiffness > s.spatialSlow.stiffness)
    assertTrue("effectsFast must be stiffer than default", s.effectsFast.stiffness > s.effectsDefault.stiffness)
    assertTrue("effectsDefault must be stiffer than slow", s.effectsDefault.stiffness > s.effectsSlow.stiffness)
  }

  // ── elevation ──────────────────────────────────────────────────────────────────────────────

  @Test
  fun `shadow policy starts at level three`() {
    val e = TrackMeElevation()
    assertTrue("level 0 must not cast", !e.castsShadow(e.level0))
    assertTrue("level 1 must not cast — tone separates it", !e.castsShadow(e.level1))
    assertTrue("level 2 must not cast — tone separates it", !e.castsShadow(e.level2))
    assertTrue("level 3 floats and must cast", e.castsShadow(e.level3))
    assertTrue("level 5 floats and must cast", e.castsShadow(e.level5))
  }

  @Test
  fun `elevation ladder ascends`() {
    val e = TrackMeElevation()
    val ladder = listOf(e.level0, e.level1, e.level2, e.level3, e.level4, e.level5)
    ladder.zipWithNext { a, b -> assertTrue("elevation ladder must ascend: $a -> $b", b > a) }
  }

  @Test
  fun `touch target meets the platform minimum`() {
    assertTrue(
      "minimum touch target must be at least 48dp",
      TrackMeSpacing().minTouchTarget >= 48.dp,
    )
  }

  // ── helpers ────────────────────────────────────────────────────────────────────────────────

  private fun ratio(a: Color, b: Color): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
  }

  private fun luminance(color: Color): Double {
    fun channel(value: Float): Double {
      val n = value.toDouble()
      return if (n <= 0.04045) n / 12.92 else ((n + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
  }
}
