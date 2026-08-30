package `in`.shvms.trackme.ui.gamification

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.shvms.trackme.domain.gamification.GamificationEngine
import `in`.shvms.trackme.domain.gamification.GamificationLevel
import `in`.shvms.trackme.domain.gamification.GamificationMilestone
import `in`.shvms.trackme.domain.gamification.GamificationSnapshot
import `in`.shvms.trackme.ui.localization.AppStrings
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamificationCollectionScreen(
    snapshot: GamificationSnapshot,
    strings: AppStrings,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.gamificationMyProgress) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.back,
                        )
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            val fontScale = LocalDensity.current.fontScale
            val compact = maxHeight < 620.dp || fontScale >= 1.6f
            val reservedHeight = if (compact) 244.dp else 196.dp
            val orbitSize = minOf(
                maxWidth - if (compact) 16.dp else 28.dp,
                maxOf(184.dp, maxHeight - reservedHeight),
            )
            val darkTheme = isSystemInDarkTheme()
            val levelIndex = gamificationOrbitLevelIndex(snapshot)
            val currentTone = progressLevelTone(levelIndex, darkTheme)
            val progressSummary = if (snapshot.nextThresholdMinutes != null) {
                String.format(
                    Locale.getDefault(),
                    strings.gamificationProgress,
                    snapshot.currentMinutes.toString(),
                    snapshot.nextThresholdMinutes.toString(),
                )
            } else {
                String.format(
                    Locale.getDefault(),
                    strings.gamificationMaxProgress,
                    snapshot.currentMinutes.toString(),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                currentTone.accent.copy(alpha = if (darkTheme) 0.18f else 0.12f),
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.78f),
                            ),
                        ),
                    )
                    .padding(
                        horizontal = if (compact) 8.dp else 14.dp,
                        vertical = if (compact) 4.dp else 10.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 12.dp),
            ) {
                Text(
                    strings.gamificationLevels,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )

                ProgressOrbit(
                    snapshot = snapshot,
                    strings = strings,
                    levelIndex = levelIndex,
                    currentTone = currentTone,
                    compact = compact,
                    orbitSize = orbitSize,
                )

                Text(
                    progressSummary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.semantics {
                        contentDescription = progressSummary
                    },
                )

                MilestoneConstellation(
                    snapshot = snapshot,
                    strings = strings,
                    tone = currentTone,
                    compact = compact,
                )
            }
        }
    }
}

internal fun gamificationOrbitLevelIndex(snapshot: GamificationSnapshot): Int =
    GamificationEngine.levels.indexOfFirst { it.id == snapshot.currentLevelId }.coerceAtLeast(0)

internal fun gamificationOrbitProgress(snapshot: GamificationSnapshot): Float {
    if (snapshot.progressDenominatorMinutes <= 0L) return 1f
    return (
        snapshot.progressNumeratorMinutes.toDouble() /
            snapshot.progressDenominatorMinutes.toDouble()
        ).coerceIn(0.0, 1.0).toFloat()
}

@Composable
private fun ProgressOrbit(
    snapshot: GamificationSnapshot,
    strings: AppStrings,
    levelIndex: Int,
    currentTone: ProgressLevelTone,
    compact: Boolean,
    orbitSize: Dp,
) {
    val dialSize = orbitSize * if (compact) 0.47f else 0.50f
    val radius = orbitSize * if (compact) 0.385f else 0.39f
    val nodeSize = (orbitSize * 0.145f).coerceIn(34.dp, if (compact) 42.dp else 48.dp)
    val progress = gamificationOrbitProgress(snapshot)
    val darkTheme = isSystemInDarkTheme()
    val activeMinutes = String.format(
        Locale.getDefault(),
        strings.gamificationActiveMinutes,
        snapshot.currentMinutes.toString(),
    )

    Box(
        modifier = Modifier
            .size(orbitSize)
            .semantics { isTraversalGroup = true },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(orbitSize * 0.72f),
            shape = CircleShape,
            color = currentTone.accent.copy(alpha = if (darkTheme) 0.11f else 0.08f),
        ) {}

        Canvas(
            modifier = Modifier.size(dialSize),
        ) {
            val strokeWidth = if (compact) 11.dp.toPx() else 14.dp.toPx()
            drawCircle(
                color = Color.Gray.copy(alpha = if (darkTheme) 0.25f else 0.16f),
                style = Stroke(width = strokeWidth),
            )
            drawArc(
                color = currentTone.accent,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth(0.38f)
                .semantics(mergeDescendants = true) {
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    traversalIndex = 0f
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 3.dp),
        ) {
            Text(
                strings.levelName(snapshot.currentLevelId),
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Text(
                activeMinutes,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }

        GamificationEngine.levels.forEachIndexed { index, level ->
            val angle = -PI / 2.0 + index * (PI / 3.0)
            val state = when {
                index == levelIndex -> OrbitNodeState.Current
                index < levelIndex -> OrbitNodeState.Unlocked
                else -> OrbitNodeState.Locked
            }
            LevelOrbitNode(
                number = index + 1,
                level = level,
                state = state,
                tone = progressLevelTone(index, darkTheme),
                strings = strings,
                compact = compact,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(
                        x = radius * cos(angle).toFloat(),
                        y = radius * sin(angle).toFloat(),
                    )
                    .size(nodeSize),
            )
        }
    }
}

private enum class OrbitNodeState {
    Current,
    Unlocked,
    Locked,
}

@Composable
private fun LevelOrbitNode(
    number: Int,
    level: GamificationLevel,
    state: OrbitNodeState,
    tone: ProgressLevelTone,
    strings: AppStrings,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val locked = state == OrbitNodeState.Locked
    val status = if (locked) strings.gamificationMilestoneLocked else strings.gamificationMilestoneUnlocked
    val description = buildString {
        append(strings.levelName(level.id))
        append(", ")
        append(String.format(Locale.getDefault(), strings.gamificationUnlocksAt, level.thresholdMinutes.toString()))
        append(", ")
        append(status)
    }

    Surface(
        modifier = modifier
            .shadow(
                elevation = if (state == OrbitNodeState.Current) 7.dp else 0.dp,
                shape = CircleShape,
                ambientColor = tone.accent.copy(alpha = 0.34f),
                spotColor = tone.accent.copy(alpha = 0.34f),
            )
            .then(
                if (state == OrbitNodeState.Current) {
                    Modifier.border(3.dp, tone.onAccent.copy(alpha = 0.72f), CircleShape)
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape)
                },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                selected = state == OrbitNodeState.Current
                traversalIndex = number.toFloat()
            },
        shape = CircleShape,
        color = if (locked) MaterialTheme.colorScheme.surfaceContainerHighest else tone.accent,
        contentColor = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else tone.onAccent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                number.toString(),
                fontSize = if (compact) 12.sp else 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Icon(
                imageVector = if (locked) Icons.Filled.Lock else Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(if (compact) 3.dp else 4.dp)
                    .size(if (compact) 8.dp else 9.dp),
            )
        }
    }
}

@Composable
private fun MilestoneConstellation(
    snapshot: GamificationSnapshot,
    strings: AppStrings,
    tone: ProgressLevelTone,
    compact: Boolean,
) {
    val unlocked = snapshot.unlockedMilestoneIds.toSet()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp),
    ) {
        Text(
            strings.gamificationMilestones,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        repeat(2) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    if (compact) 12.dp else 18.dp,
                    Alignment.CenterHorizontally,
                ),
            ) {
                GamificationEngine.milestones
                    .subList(row * 4, row * 4 + 4)
                    .forEach { milestone ->
                        MilestoneNode(
                            milestone = milestone,
                            unlocked = milestone.id in unlocked,
                            latest = snapshot.latestUnlockedMilestoneId == milestone.id,
                            tone = tone,
                            strings = strings,
                            compact = compact,
                        )
                    }
            }
        }
    }
}

@Composable
private fun MilestoneNode(
    milestone: GamificationMilestone,
    unlocked: Boolean,
    latest: Boolean,
    tone: ProgressLevelTone,
    strings: AppStrings,
    compact: Boolean,
) {
    val status = if (unlocked) strings.gamificationMilestoneUnlocked else strings.gamificationMilestoneLocked
    val description = "${strings.formatMilestone(milestone.id)}, $status"
    Surface(
        modifier = Modifier
            .size(if (compact) 36.dp else 42.dp)
            .then(
                if (latest) Modifier.border(3.dp, tone.onAccent.copy(alpha = 0.72f), CircleShape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape)
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                selected = latest
            },
        shape = CircleShape,
        color = if (unlocked) tone.accent else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (unlocked) tone.onAccent else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                milestone.activityCount.toString(),
                fontWeight = FontWeight.Bold,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 5.sp,
                    maxFontSize = if (compact) 10.sp else 11.sp,
                    stepSize = 0.5.sp,
                ),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Icon(
                imageVector = if (unlocked) Icons.Filled.Check else Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(7.dp),
            )
        }
    }
}

private data class ProgressLevelTone(
    val accent: Color,
    val onAccent: Color,
)

private fun progressLevelTone(levelIndex: Int, dark: Boolean): ProgressLevelTone {
    val light = listOf(
        Color(0xFF475569),
        Color(0xFF0277B6),
        Color(0xFF0F766E),
        Color(0xFFB45309),
        Color(0xFFC2410C),
        Color(0xFF7E22CE),
    )
    val darkPalette = listOf(
        Color(0xFFCBD5E1),
        Color(0xFF29B6F6),
        Color(0xFF5EEAD4),
        Color(0xFFFBBF24),
        Color(0xFFFB923C),
        Color(0xFFD8B4FE),
    )
    val safeIndex = levelIndex.coerceIn(light.indices)
    return ProgressLevelTone(
        accent = if (dark) darkPalette[safeIndex] else light[safeIndex],
        onAccent = if (dark) Color(0xFF12161C) else Color.White,
    )
}
