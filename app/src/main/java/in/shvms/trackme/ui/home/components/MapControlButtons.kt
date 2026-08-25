package `in`.shvms.trackme.ui.home.components

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import `in`.shvms.trackme.ui.components.HapticFeedbackUtils.triggerPhysicalVibrate
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.google.maps.android.compose.MapType
import `in`.shvms.trackme.theme.LocalTrackMeElevation
import `in`.shvms.trackme.theme.LocalTrackMeMotion

/**
 * Modular 52.dp circular map control button with tactile haptics and spring bounce animation.
 *
 * @param icon The [ImageVector] displayed inside the circular button.
 * @param contentDescription Accessibility description for screen readers.
 * @param onClick Action invoked when the button is tapped.
 * @param modifier Optional layout modifier.
 * @param iconTint Color tint applied to [icon].
 */
@Composable
fun MapControlCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val buttonScale = remember { Animatable(1f) }

    // `Surface(onClick = …)` rather than a `.clickable` in the modifier chain. A clickable passed
    // in from outside sits above Surface's own `clip(shape)`, so its ripple and press highlight
    // are drawn against the un-clipped layout bounds — a square flash behind a circular button.
    // The onClick overload puts the indication inside the clip, where the shape applies to it, and
    // still outside the shadow, which must not be clipped or it disappears.
    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            triggerPhysicalVibrate(context, 35L)
            onClick()
        },
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = LocalTrackMeElevation.current.mapOverlay,
        modifier = modifier
            .size(52.dp)
            .scale(buttonScale.value)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint.copy(alpha = if (enabled) 1f else 0.38f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * Interactive Map Layer selector button with smooth morphing close icon and horizontal options drawer.
 *
 * Expands horizontally from right to left (matching Live Share button theme and 52.dp circular size)
 * displaying icon-only options: Normal, Satellite, Terrain, and Traffic toggle.
 *
 * @param currentMapType Currently selected [MapType].
 * @param onMapTypeSelected Callback when a new [MapType] is chosen.
 * @param isTrafficEnabled Whether live traffic layer is active.
 * @param onTrafficToggle Callback to toggle live traffic overlay.
 * @param modifier Optional layout modifier.
 */
@Composable
fun MapLayerHorizontalDrawerButton(
    currentMapType: MapType,
    onMapTypeSelected: (MapType) -> Unit,
    isTrafficEnabled: Boolean,
    onTrafficToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isDrawerOpen by remember { mutableStateOf(false) }
    var lastDismissTime by remember { mutableStateOf(0L) }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val strings = LocalAppStrings.current
    val motion = LocalTrackMeMotion.current

    // Alphas take effects tokens and scales take spatial ones. That split is the whole reason the
    // scheme separates them: a spring that overshoots is what makes motion feel physical, and an
    // alpha that overshoots past 1 clips flat and reads as a flash.
    val layersAlpha by animateFloatAsState(
        targetValue = if (isDrawerOpen) 0f else 1f,
        animationSpec = motion.effectsDefault.spec(),
        label = "layersAlpha"
    )
    val layersScale by animateFloatAsState(
        targetValue = if (isDrawerOpen) 0.65f else 1f,
        animationSpec = motion.spatialFast.spec(),
        label = "layersScale"
    )
    val crossAlpha by animateFloatAsState(
        targetValue = if (isDrawerOpen) 1f else 0f,
        animationSpec = motion.effectsDefault.spec(),
        label = "crossAlpha"
    )
    val crossScale by animateFloatAsState(
        targetValue = if (isDrawerOpen) 1f else 0.65f,
        animationSpec = motion.spatialFast.spec(),
        label = "crossScale"
    )
    val crossRotation by animateFloatAsState(
        targetValue = if (isDrawerOpen) 90f else -45f,
        animationSpec = motion.spatialFast.spec(),
        label = "crossRotation"
    )

    val animatedBgColor by animateColorAsState(
        // The closed state was already themed; the open state was a hardcoded light grey, so on
        // the night basemap the button flashed pale when opened. surfaceContainerHighest is the
        // raised tone and works in both themes.
        targetValue = if (isDrawerOpen) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = motion.effectsDefault.spec(),
        label = "mapLayerBgColor"
    )

    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        // Main 52dp circular button
        Surface(
            onClick = {
                val now = System.currentTimeMillis()
                if (now - lastDismissTime > 100L) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    isDrawerOpen = !isDrawerOpen
                }
            },
            shape = CircleShape,
            color = animatedBgColor,
            shadowElevation = LocalTrackMeElevation.current.mapOverlay,
            modifier = Modifier
                .size(52.dp)
                .semantics(mergeDescendants = true) {
                    this.contentDescription = if (isDrawerOpen) strings.close else strings.mapLayers
                    this.stateDescription = if (isDrawerOpen) strings.mapLayersExpanded else strings.mapLayersCollapsed
                    this.role = Role.Button
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (layersAlpha > 0.01f) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer {
                                alpha = layersAlpha
                                scaleX = layersScale
                                scaleY = layersScale
                            }
                    )
                }
                if (crossAlpha > 0.01f) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = Color.Black.copy(alpha = 0.85f),
                        modifier = Modifier
                            .size(26.dp)
                            .graphicsLayer {
                                alpha = crossAlpha
                                scaleX = crossScale
                                scaleY = crossScale
                                rotationZ = crossRotation
                            }
                    )
                }
            }
        }

        // Horizontal pill drawer opening left-to-right next to the button
        if (isDrawerOpen) {
            val popupOffsetX = remember(density) {
                with(density) { -58.dp.roundToPx() }
            }
            Popup(
                alignment = Alignment.CenterEnd,
                offset = IntOffset(popupOffsetX, 0),
                onDismissRequest = {
                    isDrawerOpen = false
                    lastDismissTime = System.currentTimeMillis()
                },
                properties = PopupProperties(focusable = true)
            ) {
                // 90% transparent background container hugging the circular 52dp option buttons horizontally
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = Color(0xFFCFD8DC).copy(alpha = 0.10f),
                    shadowElevation = 0.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        MapLayerOptionButton(
                            icon = Icons.Default.Map,
                            contentDescription = strings.mapLayerNormal,
                            isActive = currentMapType == MapType.NORMAL,
                            role = Role.RadioButton,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onMapTypeSelected(MapType.NORMAL)
                                isDrawerOpen = false
                            }
                        )
                        MapLayerOptionButton(
                            icon = Icons.Default.Public,
                            contentDescription = strings.mapLayerSatellite,
                            isActive = currentMapType == MapType.SATELLITE,
                            role = Role.RadioButton,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onMapTypeSelected(MapType.SATELLITE)
                                isDrawerOpen = false
                            }
                        )
                        MapLayerOptionButton(
                            icon = Icons.Default.Terrain,
                            contentDescription = strings.mapLayerTerrain,
                            isActive = currentMapType == MapType.TERRAIN,
                            role = Role.RadioButton,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onMapTypeSelected(MapType.TERRAIN)
                                isDrawerOpen = false
                            }
                        )
                        MapLayerOptionButton(
                            icon = Icons.Default.Layers,
                            contentDescription = strings.mapLayerHybrid,
                            isActive = currentMapType == MapType.HYBRID,
                            role = Role.RadioButton,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onMapTypeSelected(MapType.HYBRID)
                                isDrawerOpen = false
                            }
                        )
                        VerticalDivider(
                            modifier = Modifier.height(42.dp),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                        )
                        MapLayerOptionButton(
                            icon = Icons.Default.Traffic,
                            contentDescription = strings.mapLayerTraffic,
                            isActive = isTrafficEnabled,
                            role = Role.Switch,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onTrafficToggle()
                                isDrawerOpen = false
                            }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MapLayerOptionButton(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean,
    role: Role,
    onClick: () -> Unit
) {
    val bgColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val iconColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    // Same square-ripple fix as the buttons above, via the selected/checked Surface overloads.
    // Those do not take a `role`, so it is set in the semantics block instead — losing the
    // RadioButton/Switch role would change what a screen reader says about a control whose whole
    // job is showing which map layer is active.
    val semantics = Modifier
        .size(42.dp)
        .semantics(mergeDescendants = true) {
            this.contentDescription = contentDescription
            this.role = role
        }
    val content: @Composable () -> Unit = {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }

    when (role) {
        Role.RadioButton -> Surface(
            selected = isActive,
            onClick = onClick,
            shape = CircleShape,
            color = bgColor,
            shadowElevation = 2.dp,
            modifier = semantics,
            content = content,
        )
        Role.Switch -> Surface(
            checked = isActive,
            onCheckedChange = { onClick() },
            shape = CircleShape,
            color = bgColor,
            shadowElevation = 2.dp,
            modifier = semantics,
            content = content,
        )
        else -> Surface(
            onClick = onClick,
            shape = CircleShape,
            color = bgColor,
            shadowElevation = 2.dp,
            modifier = semantics,
            content = content,
        )
    }
}
