package `in`.shvms.trackme.ui.gamification

import androidx.compose.ui.graphics.Color

/**
 * TASK-276 / LEVEL-THEME-01: one accent per level, so the ladder reads as a progression rather than
 * six identical dots.
 *
 * Two constraints shaped these values, and both came out of review rather than taste.
 *
 * **Level 1 is the colour the app ships today.** That is shvm's stated intent, and the palette the
 * radial version carried had it wrong: slate at level 1 and `#0277B6` at level 2, where `#0277B6`
 * *is* `BrandColor.cyanDeep`. Every existing rider would have been demoted to grey on upgrade.
 *
 * **The ladder stays out of the reserved semantic registers.** `BRAND_SYSTEM.md` pins green to
 * active/success, red to SOS/error and amber to warning, and the earlier palette put levels 4 and 5
 * in amber and level 3 dark in teal. On a screen whose whole job is locked-versus-unlocked, an amber
 * accent reads as caution. Cyan → blue → indigo → violet → magenta deepens visibly without ever
 * borrowing a colour that means something else in this app.
 *
 * Still a proposal: Product/CX owns the final hues, and this is what the ladder looks like if they
 * approve the shape rather than a description of it.
 */
object GamificationPalette {

    private val light = listOf(
        Color(0xFF0277B6), // Starter — BrandColor.cyanDeep, the app as it ships
        Color(0xFF1D63C4),
        Color(0xFF4B4FD1),
        Color(0xFF7139C9),
        Color(0xFF9B2FB4),
        Color(0xFFB32079),
    )

    private val dark = listOf(
        Color(0xFF29B6F6), // Starter — BrandColor.cyanBright, the dark-theme brand accent
        Color(0xFF5AA9FF),
        Color(0xFF8A9CFF),
        Color(0xFFB18BFF),
        Color(0xFFD583F0),
        Color(0xFFF27BC0),
    )

    /** Accent for a level index, clamped so an unexpected index cannot throw on a rendering path. */
    fun accent(levelIndex: Int, darkTheme: Boolean): Color {
        val palette = if (darkTheme) dark else light
        return palette[levelIndex.coerceIn(palette.indices)]
    }

    /** Readable ink on [accent]. Every hue above is dark enough in light theme to take white. */
    fun onAccent(darkTheme: Boolean): Color =
        if (darkTheme) Color(0xFF12161C) else Color.White
}
