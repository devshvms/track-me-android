package `in`.shvms.trackme.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.ui.graphics.vector.ImageVector
import `in`.shvms.trackme.domain.model.RidePersona

/**
 * Single source of truth mapping each [RidePersona] to a Material icon, used everywhere a
 * persona is rendered (start-ride picker, active-ride HUD, ride history, ride detail). Replaces
 * the old `RidePersona.emoji` glyphs with clean vector icons — consistent rendering across
 * devices/fonts and themeable via `tint`, unlike an emoji glyph.
 *
 * Kept in the UI layer (not on the [RidePersona] enum itself) so the domain model stays free of
 * Compose/Android types — see the "pure Kotlin" convention already used by the rest of
 * `domain/model` and `domain/stats`.
 */
fun RidePersona.icon(): ImageVector = when (this) {
    RidePersona.AUTO -> Icons.Filled.AutoAwesome
    // AutoMirrored, not Filled. These glyphs face a direction of travel, so in an RTL layout the
    // non-mirrored ones point backwards — the same defect that was fixed for the back arrow.
    RidePersona.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
    RidePersona.RUN -> Icons.AutoMirrored.Filled.DirectionsRun
    RidePersona.CYCLING -> Icons.AutoMirrored.Filled.DirectionsBike
    RidePersona.BIKE_DRIVE -> Icons.Filled.TwoWheeler
    RidePersona.CAR_DRIVE -> Icons.Filled.DirectionsCar
}
