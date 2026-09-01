package `in`.shvms.trackme.ui.gamification

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.gamification.GamificationEngine
import `in`.shvms.trackme.domain.gamification.GamificationLedger
import `in`.shvms.trackme.domain.gamification.GamificationSnapshot
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.domain.UnitFormatter
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import `in`.shvms.trackme.theme.LocalTrackMeMotion

/**
 * TASK-276: My Progress as a route rather than a dial.
 *
 * The radial version encoded progress twice -- an arc for progress *within* the level and a ring of
 * six nodes for progress *across* levels -- both circular, concentric, and left to the eye to
 * reconcile. A trail collapses that to one reading: where the marker sits is how far you are.
 *
 * Two rules shape everything below.
 *
 * **Nothing the rider needs is behind a gesture.** The block under the trail permanently states
 * where they are and exactly what the next level costs and gives. Tapping a waypoint is
 * *supplementary* detail about a level they did not ask about, which is what keeps this compatible
 * with `GAMIFICATION.md` §2.1's ban on gesture-only disclosure.
 *
 * **The page does not scroll.** Regions are proportional and the trail shrinks to fit, so landscape
 * and split-screen shorten the climb instead of pushing the milestones off the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamificationCollectionScreen(
    snapshot: GamificationSnapshot,
    achievements: List<GamificationLedger.LevelAchievement>,
    strings: AppStrings,
    imperial: Boolean = false,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.gamificationMyProgress) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight(), Alignment.Center) {
                        TrailPanel(snapshot, achievements, strings, imperial, Modifier.fillMaxHeight())
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // Also in landscape: without it the waypoints are tappable and undiscoverable,
                        // which is a feature nobody finds.
                        Text(
                            strings.gamificationTapHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        Readout(snapshot, strings)
                        Spacer(Modifier.height(12.dp))
                        MilestoneRail(snapshot, strings)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                        TrailPanel(snapshot, achievements, strings, imperial, Modifier.fillMaxSize())
                    }
                    Text(
                        strings.gamificationTapHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                    Readout(snapshot, strings)
                    Spacer(Modifier.height(12.dp))
                    MilestoneRail(snapshot, strings)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** The climb, its waypoints, the rider's marker, and the tap-anchored level card. */
@Composable
private fun TrailPanel(
    snapshot: GamificationSnapshot,
    achievements: List<GamificationLedger.LevelAchievement>,
    strings: AppStrings,
    imperial: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val nodes = remember(snapshot) { GamificationTrail.nodes(snapshot) }
    val markerPos = remember(snapshot) { GamificationTrail.markerPosition(snapshot) }
    val markerFraction = remember(snapshot) { GamificationTrail.markerFraction(snapshot) }

    var selected by rememberSaveable { mutableIntStateOf(-1) }
    var hintSeen by rememberSaveable { mutableStateOf(false) }

    // One entry sequence, played once, then still -- §2.1 bans *ambient* animation, not a response.
    // Reduce Motion is honoured by the animation system, which collapses the duration to zero.
    var entered by remember(snapshot) { mutableStateOf(false) }
    LaunchedEffect(snapshot) { entered = true }
    // Through the motion scheme, not a hand-picked duration: MotionTokenAdoptionTest polices that,
    // and a single property with nothing to stay in step with has no reason to be a tween.
    val motion = LocalTrackMeMotion.current
    val drawn by animateFloatAsState(
        targetValue = if (entered) markerFraction else 0f,
        animationSpec = motion.spatialSlow.spec(),
        label = "trailDraw",
    )

    // The card dismisses itself. A rider who taps a level to satisfy a passing curiosity should not
    // have to tidy up after it, and leaving it open would obscure the trail it describes.
    LaunchedEffect(selected) {
        if (selected >= 0) {
            kotlinx.coroutines.delay(4_200)
            selected = -1
        }
    }

    BoxWithConstraints(modifier) {
        val scale = minOf(
            maxWidth.value / GamificationTrail.WIDTH,
            maxHeight.value / GamificationTrail.HEIGHT,
        )
        val boardW = GamificationTrail.WIDTH * scale
        val boardH = GamificationTrail.HEIGHT * scale
        val accent = MaterialTheme.colorScheme.primary
        val ahead = MaterialTheme.colorScheme.outlineVariant
        val surface = MaterialTheme.colorScheme.surface

        Box(
            Modifier
                .width(boardW.dp)
                .height(boardH.dp)
                .align(Alignment.Center)
                .semantics { isTraversalGroup = true }
                .clickable(
                    // Tapping the empty scene dismisses the card early; it is not a control itself.
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                ) { selected = -1 }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // DrawScope is in pixels, not dp. Deriving the factor from the canvas's own width
                // keeps the drawn trail and the dp-positioned waypoints in the same coordinate
                // system -- the first version scaled by a dp number here and drew the path at 1/3
                // size on a density-3 screen while every node sat correctly.
                val pxScale = size.width / GamificationTrail.WIDTH
                scale(pxScale, pxScale, pivot = Offset.Zero) {
                    drawPath(
                        path = trailPath(1f),
                        color = ahead,
                        style = Stroke(
                            width = 5f,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 11f)),
                        ),
                    )
                    // TASK-276 / LEVEL-THEME-01: one accent today. The gradient through the palettes
                    // a rider has passed lands with the level palette, which is still with
                    // Product/CX -- shipping an invented ladder here would pre-empt that decision.
                    if (drawn > 0f) {
                        drawPath(
                            path = trailPath(drawn),
                            color = accent,
                            style = Stroke(width = 5f, cap = StrokeCap.Round),
                        )
                    }
                }
            }

            nodes.forEach { node ->
                if (node.state == GamificationTrail.NodeState.CURRENT) return@forEach
                LevelNode(
                    node = node,
                    scale = scale,
                    strings = strings,
                    selected = selected == node.levelIndex,
                    onClick = { hintSeen = true; selected = node.levelIndex },
                )
            }

            RiderMarker(
                position = markerPos,
                scale = scale,
                label = (GamificationTrail.levelIndexOf(snapshot) + 1).toString(),
                description = "${strings.gamificationYouAreHere}, " +
                    "${strings.levelName(snapshot.currentLevelId)}, " +
                    String.format(Locale.getDefault(), strings.gamificationActiveMinutes, snapshot.currentMinutes.toString()),
                onClick = { hintSeen = true; selected = GamificationTrail.levelIndexOf(snapshot) },
            )

            if (selected >= 0) {
                LevelCard(
                    node = nodes[selected],
                    achievement = achievements.getOrNull(selected),
                    snapshot = snapshot,
                    strings = strings,
                    imperial = imperial,
                    scale = scale,
                    boardWidth = boardW,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.LevelNode(
    node: GamificationTrail.Node,
    scale: Float,
    strings: AppStrings,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val passed = node.state == GamificationTrail.NodeState.PASSED
    val size = (26f * scale).coerceIn(20f, 34f)
    val level = GamificationEngine.levels[node.levelIndex]
    val status = if (passed) strings.gamificationMilestoneUnlocked else strings.gamificationMilestoneLocked

    Surface(
        shape = CircleShape,
        color = if (passed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 2.dp else 1.5.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier
            .offset(
                x = (node.position.x * scale - size / 2f).dp,
                y = (node.position.y * scale - size / 2f).dp,
            )
            .size(size.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${strings.levelName(level.id)}, " +
                    String.format(Locale.getDefault(), strings.gamificationUnlocksAt, level.thresholdMinutes.toString()) +
                    ", $status"
                this.selected = selected
                traversalIndex = (node.levelIndex + 1).toFloat()
            }
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                (node.levelIndex + 1).toString(),
                // Centred, so a circular surface cannot clip it the way BottomEnd clipped the
                // radial version's lock and check icons.
                fontSize = (size * 0.42f).sp,
                fontWeight = FontWeight.Bold,
                color = if (passed) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The rider. Deliberately the largest and most detailed thing on the trail: in the radial version
 * every node wore its own level's colour, so an already-passed level could out-shout the current
 * one and the eye landed on the wrong dot.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.RiderMarker(
    position: GamificationTrail.Point,
    scale: Float,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    val size = (38f * scale).coerceIn(30f, 48f)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        border = androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .offset(x = (position.x * scale - size / 2f).dp, y = (position.y * scale - size / 2f).dp)
            .size(size.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                selected = true
                traversalIndex = 0f
            }
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                fontSize = (size * 0.36f).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** Detail for a tapped level, anchored in the column the serpentine path leaves empty. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.LevelCard(
    node: GamificationTrail.Node,
    achievement: GamificationLedger.LevelAchievement?,
    snapshot: GamificationSnapshot,
    strings: AppStrings,
    imperial: Boolean,
    scale: Float,
    boardWidth: Float,
) {
    val level = GamificationEngine.levels[node.levelIndex]
    val cardWidth = (boardWidth * 0.46f).coerceAtLeast(120f)
    val x = if (node.cardOnRight) node.position.x * scale + 18f
    else node.position.x * scale - 18f - cardWidth
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    Surface(
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .offset(
                x = x.coerceIn(0f, boardWidth - cardWidth).dp,
                y = (node.position.y * scale - 46f).coerceAtLeast(0f).dp,
            )
            .width(cardWidth.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Text(
                strings.levelName(level.id),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val at = achievement?.achievedAtEpochMillis
            val meta = when {
                node.levelIndex == 0 && at != null ->
                    String.format(Locale.getDefault(), strings.gamificationJoinedOn, dateFormat.format(Date(at)))
                at != null ->
                    String.format(Locale.getDefault(), strings.gamificationReachedOn, dateFormat.format(Date(at)))
                else -> String.format(
                    Locale.getDefault(),
                    strings.gamificationMinutesToGo,
                    level.thresholdMinutes.toString(),
                    (level.thresholdMinutes - snapshot.currentMinutes).coerceAtLeast(0L).toString(),
                )
            }
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // How it was earned. Only for levels already behind the rider -- an unreached level has
            // no history to describe, and inventing a projection would be a forecast, not a fact.
            achievement?.personaSplit?.take(3)?.forEach { part ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        strings.personaLabel(personaOf(part.personaRaw)),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "${UnitFormatter.rideDistance(part.distanceMeters, imperial)} · " +
                            "${part.activeDurationMillis / 60_000L}m",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Where the rider is, and what the next level costs. Permanent, never behind a gesture. */
@Composable
private fun Readout(snapshot: GamificationSnapshot, strings: AppStrings) {
    val index = GamificationTrail.levelIndexOf(snapshot)
    val next = GamificationEngine.levels.getOrNull(index + 1)
    Column(Modifier.fillMaxWidth()) {
        Text(
            strings.levelName(snapshot.currentLevelId),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            String.format(Locale.getDefault(), strings.gamificationActiveMinutes, snapshot.currentMinutes.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (next != null) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(
                        "${strings.gamificationNextLabel} · ${strings.levelName(next.id)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        String.format(
                            Locale.getDefault(),
                            strings.gamificationToNextLevel,
                            (next.thresholdMinutes - snapshot.currentMinutes).coerceAtLeast(0L).toString(),
                            strings.levelName(next.id),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Milestones count *activities*; the trail counts *minutes*. Two axes, so two instruments -- putting
 * both on one path would imply they advance together, and they do not.
 */
@Composable
private fun MilestoneRail(snapshot: GamificationSnapshot, strings: AppStrings) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    strings.gamificationActivities,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    String.format(
                        Locale.getDefault(),
                        strings.gamificationActivitiesRecorded,
                        snapshot.currentActivityCount.toString(),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                GamificationEngine.milestones.forEach { milestone ->
                    val unlocked = snapshot.unlockedMilestoneIds.contains(milestone.id)
                    val isNext = !unlocked &&
                        GamificationEngine.milestones.firstOrNull { it.id !in snapshot.unlockedMilestoneIds }?.id == milestone.id
                    Column(
                        Modifier.weight(1f).semantics(mergeDescendants = true) {
                            contentDescription = strings.formatMilestone(milestone.id) + ", " +
                                if (unlocked) strings.gamificationMilestoneUnlocked else strings.gamificationMilestoneLocked
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .background(
                                    color = when {
                                        unlocked -> MaterialTheme.colorScheme.primary
                                        isNext -> MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
                                        else -> MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = RoundedCornerShape(3.dp),
                                )
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            compactCount(milestone.activityCount),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = if (unlocked) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun compactCount(count: Int): String = if (count >= 1_000) "${count / 1_000}K" else count.toString()

private fun personaOf(raw: String): RidePersona =
    RidePersona.entries.firstOrNull { it.name == raw } ?: RidePersona.AUTO

/**
 * The trail as a drawable path, trimmed to [fraction] of its length. Built by walking the same
 * sampled table the geometry uses, so what is drawn and what is measured can never disagree.
 */
private fun trailPath(fraction: Float): Path {
    val path = Path()
    val steps = 220
    val end = fraction.coerceIn(0f, 1f)
    val start = GamificationTrail.pointAt(0f)
    path.moveTo(start.x, start.y)
    if (end <= 0f) return path
    for (step in 1..steps) {
        val point = GamificationTrail.pointAt(end * step / steps)
        path.lineTo(point.x, point.y)
    }
    return path
}
