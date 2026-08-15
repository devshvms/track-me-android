package `in`.shvms.trackme.ui.catalog

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.theme.LocalTrackMeElevation
import `in`.shvms.trackme.theme.LocalTrackMeMotion
import `in`.shvms.trackme.theme.LocalTrackMeSemantics
import `in`.shvms.trackme.theme.LocalTrackMeSpacing

/**
 * Debug-only gallery of every design token and component state.
 *
 * This is the screenshot-test surface for phase 2. Most layout defects — clipped text at large
 * font scales, containers that collapse when a label runs long, states that look identical —
 * are visible here before any real screen exists, which is much cheaper than finding them
 * screen by screen.
 *
 * Registered in `Navigation.kt` behind `BuildConfig.DEBUG`; it is not present in release builds.
 *
 * See `docs/DESIGN_SYSTEM_1.8.md` §9.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignCatalogScreen(onBack: () -> Unit) {
  val spacing = LocalTrackMeSpacing.current
  val elevation = LocalTrackMeElevation.current
  val semantics = LocalTrackMeSemantics.current
  val motion = LocalTrackMeMotion.current
  val scheme = MaterialTheme.colorScheme

  var expanded by remember { mutableStateOf(false) }
  var switchOn by remember { mutableStateOf(true) }

  // Live proof that the motion tokens are wired: the same distance travelled by three springs.
  val fastWidth by animateDpAsState(
    targetValue = if (expanded) 220.dp else 40.dp,
    animationSpec = motion.spatialFast.spec(),
    label = "spatialFast",
  )
  val defaultWidth by animateDpAsState(
    targetValue = if (expanded) 220.dp else 40.dp,
    animationSpec = motion.spatialDefault.spec(),
    label = "spatialDefault",
  )
  val slowWidth by animateDpAsState(
    targetValue = if (expanded) 220.dp else 40.dp,
    animationSpec = motion.spatialSlow.spec(),
    label = "spatialSlow",
  )

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Design catalog") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { inner ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(inner),
      contentPadding = PaddingValues(spacing.screenMargin),
      verticalArrangement = Arrangement.spacedBy(spacing.betweenCards),
    ) {
      item { SectionHeader("Colour — Material roles") }
      item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          SwatchRow("primary", scheme.primary, scheme.onPrimary)
          SwatchRow("primaryContainer", scheme.primaryContainer, scheme.onPrimaryContainer)
          SwatchRow("secondary", scheme.secondary, scheme.onSecondary)
          SwatchRow("secondaryContainer", scheme.secondaryContainer, scheme.onSecondaryContainer)
          SwatchRow("tertiary", scheme.tertiary, scheme.onTertiary)
          SwatchRow("error", scheme.error, scheme.onError)
          SwatchRow("errorContainer", scheme.errorContainer, scheme.onErrorContainer)
        }
      }

      item { SectionHeader("Colour — surfaces (tonal elevation)") }
      item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          SwatchRow("surface", scheme.surface, scheme.onSurface)
          SwatchRow("surfaceContainerLowest", scheme.surfaceContainerLowest, scheme.onSurface)
          SwatchRow("surfaceContainerLow", scheme.surfaceContainerLow, scheme.onSurface)
          SwatchRow("surfaceContainer", scheme.surfaceContainer, scheme.onSurface)
          SwatchRow("surfaceContainerHigh", scheme.surfaceContainerHigh, scheme.onSurface)
          SwatchRow("surfaceContainerHighest", scheme.surfaceContainerHighest, scheme.onSurface)
        }
      }

      item { SectionHeader("Colour — outline tiers") }
      item {
        Text(
          "outline draws meaningful boundaries and holds 3:1. outlineVariant is a decorative " +
            "divider and reads quieter. On master these were the same colour.",
          style = MaterialTheme.typography.bodySmall,
          color = scheme.onSurfaceVariant,
        )
      }
      item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Box(
            Modifier
              .fillMaxWidth()
              .height(40.dp)
              .border(1.dp, scheme.outline, MaterialTheme.shapes.small),
          ) {
            Text(
              "outline",
              modifier = Modifier.align(Alignment.Center),
              style = MaterialTheme.typography.labelSmall,
              color = scheme.onSurface,
            )
          }
          Box(
            Modifier
              .fillMaxWidth()
              .height(40.dp)
              .border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.small),
          ) {
            Text(
              "outlineVariant",
              modifier = Modifier.align(Alignment.Center),
              style = MaterialTheme.typography.labelSmall,
              color = scheme.onSurfaceVariant,
            )
          }
        }
      }

      item { SectionHeader("Colour — semantics (not Material roles)") }
      item {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          SwatchRow("success", semantics.success, semantics.onSuccess)
          SwatchRow("successContainer", semantics.successContainer, semantics.onSuccessContainer)
          SwatchRow("warning", semantics.warning, semantics.onWarning)
          SwatchRow("warningContainer", semantics.warningContainer, semantics.onWarningContainer)
        }
      }

      item { SectionHeader("Type scale") }
      item {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text("Display small", style = MaterialTheme.typography.displaySmall)
          Text("Headline medium", style = MaterialTheme.typography.headlineMedium)
          Text("Title large", style = MaterialTheme.typography.titleLarge)
          Text("Body large — rides are saved to this device first.", style = MaterialTheme.typography.bodyLarge)
          Text("Label large", style = MaterialTheme.typography.labelLarge)
          Text("LABEL SMALL", style = MaterialTheme.typography.labelSmall)
        }
      }

      item { SectionHeader("Shape scale — 4 / 8 / 12 / 16 / 28") }
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ShapeChip("XS", MaterialTheme.shapes.extraSmall)
          ShapeChip("S", MaterialTheme.shapes.small)
          ShapeChip("M", MaterialTheme.shapes.medium)
          ShapeChip("L", MaterialTheme.shapes.large)
          ShapeChip("XL", MaterialTheme.shapes.extraLarge)
        }
      }

      item { SectionHeader("Elevation ladder — shadow only at level 3+") }
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ElevationTile("0", elevation.level0, scheme.surface)
          ElevationTile("1", elevation.level1, scheme.surfaceContainerLow)
          ElevationTile("2", elevation.level2, scheme.surfaceContainer)
          ElevationTile("3", elevation.level3, scheme.surfaceContainerHigh)
          ElevationTile("5", elevation.level5, scheme.surfaceContainerHighest)
        }
      }

      item { SectionHeader("Motion — tap to run all three") }
      item {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          MotionLane("spatialFast", fastWidth)
          MotionLane("spatialDefault", defaultWidth)
          MotionLane("spatialSlow", slowWidth)
          Button(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Collapse" else "Expand")
          }
        }
      }

      item { SectionHeader("Buttons — every tier") }
      item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {}) { Text("Filled") }
            FilledTonalButton(onClick = {}) { Text("Tonal") }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {}) { Text("Outlined") }
            TextButton(onClick = {}) { Text("Text") }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {}, enabled = false) { Text("Disabled") }
            FilledTonalButton(onClick = {}, enabled = false) { Text("Disabled") }
          }
        }
      }

      item { SectionHeader("List rows") }
      item {
        Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
          Column {
            ListItem(
              headlineContent = { Text("Auto-pause") },
              supportingContent = { Text("When you stop moving") },
              trailingContent = { Switch(checked = switchOn, onCheckedChange = { switchOn = it }) },
            )
            ListItem(
              headlineContent = { Text("Cloud sync") },
              supportingContent = { Text("Daily, on Wi-Fi") },
            )
            ListItem(
              headlineContent = {
                Text(
                  "A headline long enough to prove the row does not clip its supporting text",
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
              },
              supportingContent = { Text("Overflow probe") },
            )
          }
        }
      }

      item { Spacer(Modifier.height(spacing.sectionGap)) }
    }
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text = text.uppercase(),
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
  )
}

@Composable
private fun SwatchRow(name: String, container: Color, content: Color) {
  Surface(
    color = container,
    shape = MaterialTheme.shapes.small,
    modifier = Modifier.fillMaxWidth().height(44.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(name, style = MaterialTheme.typography.labelMedium, color = content)
      Spacer(Modifier.width(8.dp))
      Text("Aa", style = MaterialTheme.typography.bodySmall, color = content)
    }
  }
}

@Composable
private fun ShapeChip(label: String, shape: androidx.compose.ui.graphics.Shape) {
  Box(
    modifier = Modifier
      .size(52.dp)
      .clip(shape)
      .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    contentAlignment = Alignment.Center,
  ) {
    Text(label, style = MaterialTheme.typography.labelSmall)
  }
}

@Composable
private fun ElevationTile(label: String, elevation: Dp, container: Color) {
  Surface(
    color = container,
    tonalElevation = elevation,
    shadowElevation = if (elevation >= 6.dp) elevation else 0.dp,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.size(52.dp),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(label, style = MaterialTheme.typography.labelSmall)
    }
  }
}

@Composable
private fun MotionLane(label: String, width: Dp) {
  Column {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Box(
      Modifier
        .fillMaxWidth()
        .height(24.dp)
        .clip(MaterialTheme.shapes.extraSmall)
        .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
      Box(
        Modifier
          .width(width)
          .height(24.dp)
          .clip(MaterialTheme.shapes.extraSmall)
          .background(MaterialTheme.colorScheme.primary),
      )
    }
  }
}
