package `in`.shvms.trackme.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
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
    RidePersona.WALK -> Icons.Filled.DirectionsWalk
    RidePersona.RUN -> Icons.Filled.DirectionsRun
    RidePersona.CYCLING -> Icons.Filled.DirectionsBike
    RidePersona.BIKE_DRIVE -> Icons.Filled.TwoWheeler
    RidePersona.CAR_DRIVE -> Icons.Filled.DirectionsCar
}
