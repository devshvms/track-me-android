package `in`.shvms.trackme.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.theme.LocalTrackMeElevation
import `in`.shvms.trackme.theme.LocalTrackMeSpacing

/**
 * Grouped settings rows, built on `ListItem`.
 *
 * ### Why these exist
 * `SettingsScreen` hand-built the same row fifteen times — a `Row` with `SpaceBetween`, a weighted
 * `Column` holding two `Text`s, and a trailing control — and the app used `ListItem` exactly zero
 * times. Each hand-built copy drifted: different vertical padding, different `end` padding, some
 * toggleable on the whole row and some only on the switch. That inconsistency is what "the screens
 * don't quite line up" actually was.
 *
 * `ListItem` already encodes the M3 list metrics: 56dp single-line, 72dp with supporting text,
 * correct leading/trailing alignment, and the text styles for each slot. Using it deletes the
 * drift rather than re-tidying it.
 *
 * ### Structure
 * A [SettingsGroup] is a labelled card at elevation level 1 — tone only, no shadow, per the
 * elevation policy in `docs/DESIGN_SYSTEM_1.8.md` §6. Its label sits *outside* the card, which is
 * the platform convention and lets the eye skim group names without entering each container.
 */
@Composable
fun SettingsGroup(
  title: String,
  modifier: Modifier = Modifier,
  /** An affordance beside the group label — an explainer, typically. */
  titleAction: (@Composable () -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val spacing = LocalTrackMeSpacing.current
  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.weight(1f),
      )
      titleAction?.invoke()
    }
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.medium,
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
      ),
      // Level 1: separated by tone, casts nothing. A shadow here would be invisible on the
      // dark theme's near-black ground while still costing overdraw.
      elevation = CardDefaults.cardElevation(defaultElevation = LocalTrackMeElevation.current.level1),
    ) {
      Column(content = content)
    }
    androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = spacing.betweenCards))
  }
}

/** A hairline between rows inside a [SettingsGroup]. Uses the low-emphasis outline tier. */
@Composable
fun SettingsDivider() {
  HorizontalDivider(
    modifier = Modifier.padding(horizontal = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant,
  )
}

/**
 * A row carrying a switch, toggleable across its whole width.
 *
 * The entire row is the target rather than just the switch — a 48dp switch inside a 72dp row
 * wastes most of the touch area, and reaching for a small control while moving is exactly the
 * case this app should not make harder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSwitchRow(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  supportingText: String? = null,
  enabled: Boolean = true,
  /** Renders a small info affordance beside the title. Its own target, nested inside the row. */
  onInfoClick: (() -> Unit)? = null,
  infoDescription: String? = null,
) {
  ListItem(
    modifier = modifier.toggleable(
      value = checked,
      enabled = enabled,
      role = Role.Switch,
      onValueChange = onCheckedChange,
    ),
    headlineContent = {
      if (onInfoClick == null) {
        Text(title)
      } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(title)
          IconButton(
            onClick = onInfoClick,
            modifier = Modifier.size(24.dp).padding(start = 4.dp),
          ) {
            Icon(
              Icons.Default.Info,
              contentDescription = infoDescription,
              modifier = Modifier.size(16.dp),
            )
          }
        }
      }
    },
    supportingContent = supportingText?.let { { Text(it) } },
    // null so the switch does not become a second, competing target — the row owns the gesture.
    trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
  )
}

/**
 * A plain row, optionally navigating somewhere or carrying its own trailing control.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRow(
  title: String,
  modifier: Modifier = Modifier,
  supportingText: String? = null,
  leadingContent: (@Composable () -> Unit)? = null,
  trailingContent: (@Composable () -> Unit)? = null,
  onClick: (() -> Unit)? = null,
) {
  ListItem(
    modifier = if (onClick != null) {
      modifier.clickable(role = Role.Button, onClick = onClick)
    } else {
      modifier
    },
    headlineContent = { Text(title) },
    supportingContent = supportingText?.let { { Text(it) } },
    leadingContent = leadingContent,
    trailingContent = trailingContent,
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
  )
}
