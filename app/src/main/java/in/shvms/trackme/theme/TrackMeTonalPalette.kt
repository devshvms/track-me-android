package `in`.shvms.trackme.theme

import androidx.compose.ui.graphics.Color

/**
 * Primitive design tokens: the tonal ramps.
 *
 * **These are not semantic.** Nothing in the UI may reference a [Tone] directly — the UI reads
 * roles from `MaterialTheme.colorScheme` or [TrackMeSemantics]. A tone is a coordinate, not a
 * meaning, and wiring a screen to `Tone.Primary40` is how a palette becomes unchangeable.
 *
 * Every ramp holds one hue at one chroma, swept across tone (CIELAB L*, 0–100). Ramps were
 * generated from the single seed `#29B6F6` — the existing `CyanBright`, which is on the logo and
 * in the store listing. Changing the brand hue means editing [SEED_HUE] and regenerating; it
 * should touch no other file.
 *
 * ### Generation caveat
 * These were produced by holding hue and chroma in CIELCh and sweeping L*, gamut-mapping by
 * reducing chroma until each value fits sRGB. Material's generator uses CAM16-based HCT. The two
 * agree closely on tone and ordering and diverge a few points on chroma in the 70–90 band, where
 * gamut mapping bites hardest — which is why the primary ramp visibly desaturates from T70 to T80.
 *
 * Before 1.8.0 ships these should be regenerated with the official Material Color Utilities from
 * the same seed and diffed against this file. `ColorRoleContrastTest` re-validates whatever values
 * are present, so a regeneration that breaks contrast fails the build rather than shipping.
 *
 * See `docs/DESIGN_SYSTEM_1.8.md` §4.
 */
internal object Tone {

  /** Seed colour and the derived hues, kept as documentation of provenance. */
  const val SEED = "#29B6F6"
  const val SEED_HUE = 251.1f
  const val TERTIARY_HUE = 311.1f // seed + 60°, per M3's tertiary derivation

  // ── Primary — hue 251°, chroma 48 ─────────────────────────────────────────
  val Primary0 = Color(0xFF000000)
  val Primary10 = Color(0xFF001E2D)
  val Primary20 = Color(0xFF01344B)
  val Primary30 = Color(0xFF014C6B)
  val Primary40 = Color(0xFF00658D)
  val Primary50 = Color(0xFF037FB0)
  val Primary60 = Color(0xFF029BD4)
  val Primary70 = Color(0xFF08B7FA)
  val Primary80 = Color(0xFF84CFFF)
  val Primary90 = Color(0xFFC7E7FF)
  val Primary95 = Color(0xFFE4F3FF)
  val Primary100 = Color(0xFFFFFFFF)

  // ── Secondary — hue 251°, chroma 18 (the muted brand) ─────────────────────
  val Secondary10 = Color(0xFF001E2D)
  val Secondary20 = Color(0xFF083449)
  val Secondary30 = Color(0xFF264B61)
  val Secondary40 = Color(0xFF3F627A)
  val Secondary50 = Color(0xFF587B93)
  val Secondary60 = Color(0xFF7295AE)
  val Secondary70 = Color(0xFF8CB0CA)
  val Secondary80 = Color(0xFFA8CBE6)
  val Secondary90 = Color(0xFFC7E7FF)
  val Secondary100 = Color(0xFFFFFFFF)

  // ── Tertiary — hue 311°, chroma 32 (seed rotated +60°) ────────────────────
  val Tertiary10 = Color(0xFF25113C)
  val Tertiary20 = Color(0xFF3C2654)
  val Tertiary30 = Color(0xFF543C6C)
  val Tertiary40 = Color(0xFF6D5486)
  val Tertiary50 = Color(0xFF876CA0)
  val Tertiary60 = Color(0xFFA185BB)
  val Tertiary70 = Color(0xFFBDA0D7)
  val Tertiary80 = Color(0xFFD9BBF3)
  val Tertiary90 = Color(0xFFEFDBFF)
  val Tertiary100 = Color(0xFFFFFFFF)

  // ── Neutral — hue 251°, chroma 4. Every surface in the app. ───────────────
  // Note how close these land to the palette already shipping: Navy900 #12161C measures
  // chroma 4.8, which is already an M3-legal neutral. N10 is #171C20.
  val Neutral0 = Color(0xFF000000)
  val Neutral4 = Color(0xFF070F14)
  val Neutral6 = Color(0xFF0E1418)
  val Neutral10 = Color(0xFF171C20)
  val Neutral12 = Color(0xFF1B2025)
  val Neutral17 = Color(0xFF252B2F)
  val Neutral20 = Color(0xFF2B3136)
  val Neutral22 = Color(0xFF30353A)
  val Neutral24 = Color(0xFF343A3F)
  val Neutral30 = Color(0xFF41474C)
  val Neutral40 = Color(0xFF595F64)
  val Neutral50 = Color(0xFF71787D)
  val Neutral60 = Color(0xFF8B9297)
  val Neutral70 = Color(0xFFA5ACB2)
  val Neutral80 = Color(0xFFC0C7CD)
  val Neutral87 = Color(0xFFD4DBE1)
  val Neutral90 = Color(0xFFDCE3E9)
  val Neutral92 = Color(0xFFE2E9EF)
  val Neutral94 = Color(0xFFE7EFF5)
  val Neutral95 = Color(0xFFEAF2F8)
  val Neutral96 = Color(0xFFEDF5FB)
  val Neutral98 = Color(0xFFF4FAFF)
  val Neutral100 = Color(0xFFFFFFFF)

  // ── Neutral variant — hue 251°, chroma 8. Outlines and dividers. ──────────
  val NeutralVariant10 = Color(0xFF101D26)
  val NeutralVariant20 = Color(0xFF25323B)
  val NeutralVariant30 = Color(0xFF3B4852)
  val NeutralVariant40 = Color(0xFF53606A)
  val NeutralVariant50 = Color(0xFF6B7984)
  val NeutralVariant60 = Color(0xFF85939E)
  val NeutralVariant70 = Color(0xFF9FADB9)
  val NeutralVariant80 = Color(0xFFBAC8D4)
  val NeutralVariant90 = Color(0xFFD6E4F1)

  // ── Error — hue 35°, chroma 62 (derived from the existing RedSos) ─────────
  val Error10 = Color(0xFF390B00)
  val Error20 = Color(0xFF690003)
  val Error30 = Color(0xFF900C12)
  val Error40 = Color(0xFFAF2F27)
  val Error80 = Color(0xFFFFB4A6)
  val Error90 = Color(0xFFFFDAD3)
  val Error100 = Color(0xFFFFFFFF)

  // ── Success — hue 146°, chroma 50. SEMANTIC ONLY, never brand. ────────────
  // Color.kt already documents why: collapsing "brand accent" and "semantic go" into one
  // token is what shipped a green Start button in 1.5.11 while the logo was cyan.
  val Success10 = Color(0xFF002204)
  val Success20 = Color(0xFF003914)
  val Success30 = Color(0xFF005321)
  val Success40 = Color(0xFF046D2E)
  val Success80 = Color(0xFF81DA91)
  val Success90 = Color(0xFF9DF6AC)

  // ── Warning — hue 73°, chroma 60. SEMANTIC ONLY, never brand. ─────────────
  // On master this hue is bound to Material's `tertiary` role, which is a brand accent slot.
  // That is the same category error as the green Start button. See DESIGN_SYSTEM_1.8.md §8.
  val Warning10 = Color(0xFF281900)
  val Warning20 = Color(0xFF462B00)
  val Warning30 = Color(0xFF653E00)
  val Warning40 = Color(0xFF855300)
  val Warning80 = Color(0xFFFFB960)
  val Warning90 = Color(0xFFFFDDB9)
}
