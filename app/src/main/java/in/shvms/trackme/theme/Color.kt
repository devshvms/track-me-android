package `in`.shvms.trackme.theme

import androidx.compose.ui.graphics.Color

/**
 * Single local configuration point for TrackMe Brand System v1.
 *
 * Keep this compile-time for the production launch. A future remote theme must
 * validate contrast and fall back atomically to this palette before activation.
 */
object BrandThemeConfig {
  val cyanBright = Color(0xFF29B6F6)
  val cyanDeep = Color(0xFF0277B6)
  val navy900 = Color(0xFF12161C)
  val navy800 = Color(0xFF181A20)
  val navy700 = Color(0xFF23272F)
  val surfaceDarkLowest = Color(0xFF0B0F14)
  val greenGo = Color(0xFF16A34A)
  val greenContainerLight = Color(0xFFDCFCE7)
  val greenContainerDark = Color(0xFF14532D)
  val redSos = Color(0xFFDC2626)
  // Accessible error text on dark surfaces; redSos remains the SOS/background token.
  val redTextDark = Color(0xFFF87171)
  val errorContainerLight = Color(0xFFFEE2E2)
  val errorContainerDark = Color(0xFF7F1D1D)
  val amberWarn = Color(0xFFF59E0B)
  val amberContainerLight = Color(0xFFFEF3C7)
  val amberContainerDark = Color(0xFF78350F)
  val cyanContainerLight = Color(0xFFD7F1FD)

  val slate50 = Color(0xFFF8FAFC)
  val slate100 = Color(0xFFF1F5F9)
  val slate200 = Color(0xFFE2E8F0)
  val slate300 = Color(0xFFCBD5E1)
  val slate400 = Color(0xFF94A3B8)
  val slate500 = Color(0xFF64748B)
  val slate600 = Color(0xFF475569)
  val slate700 = Color(0xFF334155)
  val slate800 = Color(0xFF1E293B)
}

val CyanBright = BrandThemeConfig.cyanBright
val CyanDeep = BrandThemeConfig.cyanDeep
val Navy900 = BrandThemeConfig.navy900
val Navy800 = BrandThemeConfig.navy800
val Navy700 = BrandThemeConfig.navy700
val SurfaceDarkLowest = BrandThemeConfig.surfaceDarkLowest
val GreenGo = BrandThemeConfig.greenGo
val GreenContainerLight = BrandThemeConfig.greenContainerLight
val GreenContainerDark = BrandThemeConfig.greenContainerDark
val RedSos = BrandThemeConfig.redSos
val RedTextDark = BrandThemeConfig.redTextDark
val ErrorContainerLight = BrandThemeConfig.errorContainerLight
val ErrorContainerDark = BrandThemeConfig.errorContainerDark
val AmberWarn = BrandThemeConfig.amberWarn
val AmberContainerLight = BrandThemeConfig.amberContainerLight
val AmberContainerDark = BrandThemeConfig.amberContainerDark
val CyanContainerLight = BrandThemeConfig.cyanContainerLight
val Slate50 = BrandThemeConfig.slate50
val Slate100 = BrandThemeConfig.slate100
val Slate200 = BrandThemeConfig.slate200
val Slate300 = BrandThemeConfig.slate300
val Slate400 = BrandThemeConfig.slate400
val Slate500 = BrandThemeConfig.slate500
val Slate600 = BrandThemeConfig.slate600
val Slate700 = BrandThemeConfig.slate700
val Slate800 = BrandThemeConfig.slate800

// --- Semantic tokens (C1) -----------------------------------------------------------------
// Brand accent is CYAN and lives on MaterialTheme.colorScheme.primary/secondary. Green is
// NEVER brand — it only ever means "success / active / positive state". Bind semantic green
// through these explicitly named tokens, never through Material `secondary` and never through
// a generic "TrackMeGreen" alias: that ambiguity is what shipped a green Start button in
// 1.5.11 while the logo and store assets were cyan.

/** Semantic success/active state (live share running, offline shield engaged). NOT brand. */
val SuccessGreen = GreenGo
/** Container tint for success surfaces on light/dark respectively. */
val SuccessContainerLight = GreenContainerLight
val SuccessContainerDark = GreenContainerDark

/** Data-visualization series colors. Chart hues are data encoding, not brand or state. */
val ChartSpeed = GreenGo
val ChartAltitude = AmberWarn

// Compatibility names keep the token migration scoped and low-risk. New UI should
// use the semantic names above or MaterialTheme.colorScheme.
// NOTE (C1): the former `TrackMeGreen`/`TrackMeGreenLight`/`TrackMeGreenDark` aliases were
// removed. They all collapsed to `GreenGo`, which made "brand accent" and "semantic go"
// indistinguishable at the call site. Use `colorScheme.primary` for brand actions or
// `SuccessGreen`/`ChartSpeed` for the semantic cases.
val TrackMeBlue = CyanDeep
val TrackMeBlueDark = CyanDeep
val TrackMeRed = RedSos
val TrackMeRedLight = RedSos
val TrackMeAmber = AmberWarn
val TrackMeOrange = AmberWarn
val TrackMeGrey = Slate400
val TrackMeGreyLight = Slate200
