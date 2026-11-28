package `in`.shvms.trackme.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.ui.components.icon
import `in`.shvms.trackme.ui.localization.LocalAppStrings

/**
 * TASK-381: Ordered quick-select modes paired with [RadialStartRideButton].
 * Keeps `AUTO` first followed by explicit activity intents in a single scrollable strip.
 */
internal val QUICK_ACTIVITY_MODE_ORDER: List<RidePersona> = listOf(
    RidePersona.AUTO,
    RidePersona.CAR_DRIVE,
    RidePersona.BIKE_DRIVE,
    RidePersona.WALK,
    RidePersona.CYCLING,
    RidePersona.RUN,
)

/**
 * Resolves a chip tap against the active dashboard persona, suppressing redundant selections
 * so telemetry and preference updates fire only when the user actually changes mode.
 */
internal fun resolveQuickActivityModeTap(
    current: RidePersona,
    tapped: RidePersona,
): RidePersona? = tapped.takeIf { it != current }

/**
 * Compact, single-line quick mode strip directly above the radial start control.
 * Reads and writes the existing `selectedDashboardPersona` state without creating a parallel store.
 */
@Composable
fun QuickActivityModeStrip(
    selectedPersona: RidePersona,
    onSelectPersona: (RidePersona) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.CenterHorizontally),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(items = QUICK_ACTIVITY_MODE_ORDER, key = { it.name }) { persona ->
            val selected = persona == selectedPersona
            FilterChip(
                selected = selected,
                onClick = { onSelectPersona(persona) },
                leadingIcon = {
                    Icon(
                        imageVector = persona.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                label = {
                    Text(
                        text = strings.personaLabel(persona),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("quick_mode_chip_${persona.name.lowercase()}"),
            )
        }
    }
}
