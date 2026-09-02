package `in`.shvms.trackme.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.data.local.dao.HomeDashboardRoutePoint
import `in`.shvms.trackme.domain.UnitFormatter
import `in`.shvms.trackme.domain.gamification.GamificationEngine
import `in`.shvms.trackme.domain.gamification.toGamificationFacts
import `in`.shvms.trackme.domain.gamification.GamificationSnapshot
import `in`.shvms.trackme.ui.gamification.levelName
import `in`.shvms.trackme.ui.gamification.formatMilestone
import `in`.shvms.trackme.domain.home.HomeDashboardSummary
import `in`.shvms.trackme.domain.home.HomeInsight
import `in`.shvms.trackme.domain.home.InsightDirection
import `in`.shvms.trackme.domain.home.InsightMetric
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.domain.model.usesPace
import `in`.shvms.trackme.ui.components.icon
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.max
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeDashboardScreen(
    summary: HomeDashboardSummary,
    routePoints: List<HomeDashboardRoutePoint>,
    isSummaryResolved: Boolean,
    isReconciling: Boolean,
    groupActive: Boolean,
    groupMemberCount: Int,
    syncNeedsAction: Boolean,
    isOffline: Boolean,
    locationPermissionRevoked: Boolean,
    imperial: Boolean,
    onOpenRecent: (Long, RidePersona) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenCommunity: () -> Unit,
    onOpenProgress: () -> Unit,
    onOpenGroupMap: () -> Unit,
    /** TASK-254: hands Community the sheet to open, then switches to it. The sheets are not duplicated. */
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissPermissionNotice: () -> Unit,
    scrollToTopRequest: Int = 0,
) {
    val strings = LocalAppStrings.current
    // A first Room emission can still be an empty projection while legacy metadata is being
    // reconciled. Treat that as unknown, not empty, so a rider with a large history never sees
    // first-run copy flash as though their data vanished.
    val deckResolved = isSummaryResolved && (!isReconciling || summary.lifetimeActivityCount > 0)

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(scrollToTopRequest) {
        if (scrollToTopRequest > 0) listState.animateScrollToItem(0)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 16.dp,
            end = 16.dp,
            // The radial control is fixed outside this scrolling deck and remains actionable at
            // every font scale. Cards may scroll behind its reserved dock, never under its touch.
            bottom = 252.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (deckResolved) {
            if (locationPermissionRevoked) {
                item {
                    PermissionRevokedCard(
                        strings = strings,
                        onOpenSettings = onOpenSettings,
                        onDismiss = onDismissPermissionNotice,
                    )
                }
            }

            item {
                ContextCard(
                    groupActive = groupActive,
                    groupMemberCount = groupMemberCount,
                    syncNeedsAction = syncNeedsAction,
                    isOffline = isOffline,
                    strings = strings,
                    onOpenCommunity = onOpenCommunity,
                    onOpenGroupMap = onOpenGroupMap,
                    onOpenHistory = onOpenHistory,
                )
            }

            item {
                GroupRideCard(
                    groupActive = groupActive,
                    groupMemberCount = groupMemberCount,
                    strings = strings,
                    onOpenCommunity = onOpenCommunity,
                    onOpenGroupMap = onOpenGroupMap,
                )
            }

            if (summary.lifetimeActivityCount > 0) {
                item { WeeklySummaryCard(summary, imperial, strings) }
            } else {
                item { EmptyDashboardCard(strings, hasSampleRide = summary.hasSampleRide) }
            }

            if (isReconciling && summary.lifetimeActivityCount > 0) {
                item {
                    Text(
                        strings.dashboardLoadingHistory,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            summary.insight?.let { insight ->
                item { InsightCard(insight, imperial, strings) }
            }

            item {
                val facts = summary.toGamificationFacts()
                val snapshot = GamificationEngine.deriveSnapshot(facts)
                ProgressCard(snapshot = snapshot, strings = strings, onOpenProgress = onOpenProgress)
            }

            summary.latestActivity?.let { recent ->
                item {
                    RecentActivityCard(
                        summary = summary,
                        routePoints = routePoints,
                        imperial = imperial,
                        strings = strings,
                        onOpen = { onOpenRecent(recent.localId, recent.persona) },
                        onOpenHistory = onOpenHistory,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeeklySummaryCard(summary: HomeDashboardSummary, imperial: Boolean, strings: AppStrings) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                strings.dashboardThisWeek,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Metric(strings.dashboardActivityCount.format(summary.currentWeek.activityCount), strings.dashboardThisWeek)
                Metric(formatDashboardDuration(summary.currentWeek.activeDurationMillis, strings), strings.duration)
                
                if (summary.currentWeek.distanceByPersona.isNotEmpty()) {
                    summary.currentWeek.distanceByPersona.forEach { personaDistance ->
                        Metric(
                            `in`.shvms.trackme.domain.UnitFormatter.rideDistance(personaDistance.distanceMeters, imperial),
                            strings.personaLabel(personaDistance.persona),
                        )
                    }
                } else {
                    Metric(`in`.shvms.trackme.domain.UnitFormatter.rideDistance(0.0, imperial), strings.distance)
                }
            }
            if (summary.displayStreakWeeks > 1) {
                Text(
                    String.format(Locale.getDefault(), strings.dashboardWeeklyStreak, summary.displayStreakWeeks),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            WeeklyDurationChart(summary, strings)
        }
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WeeklyDurationChart(summary: HomeDashboardSummary, strings: AppStrings) {
    val buckets = summary.weeklyBuckets.takeLast(4)
    val maxDuration = max(1L, buckets.maxOfOrNull { it.activeDurationMillis } ?: 1L)
    val barColor = MaterialTheme.colorScheme.primary
    val accessibleValues = buckets.map { formatDashboardDuration(it.activeDurationMillis, strings) }
    val accessibleLabel = String.format(
        Locale.getDefault(),
        strings.dashboardWeeklyChartValues,
        accessibleValues.getOrElse(0) { formatDashboardDuration(0L, strings) },
        accessibleValues.getOrElse(1) { formatDashboardDuration(0L, strings) },
        accessibleValues.getOrElse(2) { formatDashboardDuration(0L, strings) },
        accessibleValues.getOrElse(3) { formatDashboardDuration(0L, strings) },
    )
    Canvas(
        Modifier.fillMaxWidth().height(64.dp).semantics {
            contentDescription = accessibleLabel
        }
    ) {
        val gap = 12.dp.toPx()
        val width = (size.width - gap * 3) / 4
        buckets.forEachIndexed { index, bucket ->
            val ratio = (bucket.activeDurationMillis.toDouble() / maxDuration.toDouble()).toFloat().coerceIn(0f, 1f)
            val height = (size.height * ratio).coerceAtLeast(3.dp.toPx())
            drawRoundRect(
                color = barColor.copy(alpha = if (index == buckets.lastIndex) 1f else 0.45f),
                topLeft = Offset(index * (width + gap), size.height - height),
                size = androidx.compose.ui.geometry.Size(width, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()),
            )
        }
    }
}

@Composable
private fun EmptyDashboardCard(strings: AppStrings, hasSampleRide: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(strings.dashboardPrivateOffline, fontWeight = FontWeight.SemiBold)
            }
            // TASK-225: two different empty states used to read as one. A rider whose only ride is
            // the seeded sample sees a ride in History and nothing here, which looks like data
            // loss; the card made it worse by describing what the dashboard is for rather than why
            // it is empty. This says why. The true first-run state -- no rides at all -- is
            // unchanged and must stay unchanged.
            if (hasSampleRide) {
                Text(
                    strings.dashboardSampleRideOnly,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            listOf(
                Icons.Default.BarChart to strings.dashboardPreviewWeekly,
                Icons.Default.Insights to strings.dashboardPreviewComparison,
                Icons.Default.Route to strings.dashboardPreviewRoutes,
            ).forEach { (icon, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(label)
                }
            }
            Text(
                strings.dashboardLocationAfterStart,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionRevokedCard(
    strings: AppStrings,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(strings.locationPermissionRevokedTitle, fontWeight = FontWeight.Bold)
                    Text(strings.locationPermissionRevokedBody, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onDismiss) { Text(strings.close) }
                FilledTonalButton(onClick = onOpenSettings) { Text(strings.openSettings) }
            }
        }
    }
}

@Composable
private fun ContextCard(
    groupActive: Boolean,
    groupMemberCount: Int,
    syncNeedsAction: Boolean,
    isOffline: Boolean,
    strings: AppStrings,
    onOpenCommunity: () -> Unit,
    onOpenGroupMap: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    when {
        syncNeedsAction -> Card(
            modifier = Modifier.clickable(onClick = onOpenHistory),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudOff, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(strings.dashboardSyncAction)
            }
        }
        isOffline -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(strings.dashboardOfflineReady, Modifier.fillMaxWidth().padding(14.dp))
        }
    }
}

/**
 * TASK-254, shvm: group rides are entered from Home, not only from a tab most riders never open.
 *
 * The entry point was the whole problem. Creating or joining lived behind the Community tab, which
 * a rider has no reason to visit until they already know the feature exists — so the feature was
 * gated on discovering the feature. The card is the fix: it sits where riders already are.
 *
 * **The tab stays.** shvm's call, and the right one: TASK-232 gave Community real content — the
 * rider's own group rides — and removing it would orphan that. The card is the entry point, the tab
 * is the destination. Whether the tab still earns its place is a question for usage data later, not
 * a guess now.
 *
 * The Create and Join *sheets* are not duplicated here. They stay where they were built, on
 * Community; this records which one the rider asked for and switches tabs. Two implementations of a
 * consent-bearing sheet is exactly how the two drift apart.
 *
 * The privacy sentence is not decoration. `COMMUNITY_REDESIGN_SPEC.md` §2.3 keeps it at full
 * prominence on the empty state because it is the reason people trust this feature — so the entry
 * point carries it too, rather than making the promise only where the rider has already committed.
 */
@Composable
private fun GroupRideCard(
    groupActive: Boolean,
    groupMemberCount: Int,
    strings: AppStrings,
    onOpenCommunity: () -> Unit,
    onOpenGroupMap: () -> Unit,
) {
    var showHowItWorks by rememberSaveable { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (groupActive) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (groupActive) {
                        "${strings.dashboardGroupActive} • " +
                            String.format(Locale.getDefault(), strings.dashboardGroupMembers, groupMemberCount)
                    } else {
                        strings.dashboardGroupHeading
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (!groupActive) {
                    IconButton(onClick = { showHowItWorks = !showHowItWorks }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = strings.dashboardGroupHowItWorks,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!groupActive && showHowItWorks) {
                Text(
                    strings.dashboardGroupHowItWorks,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (groupActive) {
                    FilledTonalButton(onClick = onOpenGroupMap) { Text(strings.dashboardViewLiveMap) }
                } else {
                    FilledTonalButton(onClick = onOpenCommunity) { Text(strings.dashboardGroupHeading) }
                }
            }
        }
    }
}

@Composable
private fun InsightCard(insight: HomeInsight, imperial: Boolean, strings: AppStrings) {
    val text = when (insight) {
        is HomeInsight.Return -> String.format(
            Locale.getDefault(), strings.dashboardInsightReturn,
            strings.personaLabel(insight.persona), insight.inactiveDays,
        )
        is HomeInsight.PeriodComparison -> {
            val currentFormatted = if (insight.metric == InsightMetric.DISTANCE) {
                `in`.shvms.trackme.domain.UnitFormatter.rideDistance(insight.currentValue, imperial)
            } else {
                formatDashboardDuration(insight.currentValue.toLong(), strings)
            }
            val comparisonFormatted = if (insight.metric == InsightMetric.DISTANCE) {
                `in`.shvms.trackme.domain.UnitFormatter.rideDistance(insight.comparisonValue, imperial)
            } else {
                formatDashboardDuration(insight.comparisonValue.toLong(), strings)
            }
            val metricStr = if (insight.metric == InsightMetric.DISTANCE) strings.distance else strings.duration
            "$metricStr: $currentFormatted / $comparisonFormatted"
        }
        is HomeInsight.DominantPersona -> String.format(
            Locale.getDefault(), strings.dashboardInsightDominant, strings.personaLabel(insight.persona)
        )
    }
    val basis = if (insight is HomeInsight.PeriodComparison) {
        "${strings.dashboardInsightBasisWeeks}: " +
            "${formatInsightPeriod(insight.currentPeriod.startEpochDay, insight.currentPeriod.endEpochDay)} · " +
            formatInsightPeriod(insight.comparisonPeriod.startEpochDay, insight.comparisonPeriod.endEpochDay)
    } else strings.dashboardInsightBasisHistory
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(strings.dashboardInsights, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Text(basis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatInsightPeriod(startEpochDay: Long, endEpochDay: Long): String {
    val formatter = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
    val zone = ZoneId.systemDefault()
    fun date(epochDay: Long): Date = Date.from(LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant())
    return "${formatter.format(date(startEpochDay))}–${formatter.format(date(endEpochDay))}"
}

@Composable
private fun RecentActivityCard(
    summary: HomeDashboardSummary,
    routePoints: List<HomeDashboardRoutePoint>,
    imperial: Boolean,
    strings: AppStrings,
    onOpen: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val recent = summary.latestActivity ?: return
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.dashboardRecentActivity, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                DashboardRouteThumbnail(routePoints, Modifier.size(88.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(recent.persona.icon(), contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(strings.personaLabel(recent.persona), fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(recent.startedAtEpochMillis)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${UnitFormatter.rideDistance(recent.distanceMeters, imperial)} • ${formatDashboardDuration(recent.activeDurationMillis, strings)}",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (recent.persona.usesPace) UnitFormatter.pace(recent.avgSpeedMps, imperial)
                        else UnitFormatter.speed(recent.avgSpeedMps, imperial),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = onOpenHistory, modifier = Modifier.align(Alignment.End)) {
                Text(strings.dashboardViewAllHistory)
            }
        }
    }
}

@Composable
private fun DashboardRouteThumbnail(points: List<HomeDashboardRoutePoint>, modifier: Modifier = Modifier) {
    val routeColor = MaterialTheme.colorScheme.primary
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (points.size < 2) {
            Icon(Icons.Default.Route, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Box
        }
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val minLat = points.minOf { it.latitude }
            val maxLat = points.maxOf { it.latitude }
            val minLng = points.minOf { it.longitude }
            val maxLng = points.maxOf { it.longitude }
            val latSpan = (maxLat - minLat).takeIf { it > 0.00001 } ?: 0.001
            val lngSpan = (maxLng - minLng).takeIf { it > 0.00001 } ?: 0.001
            val path = Path()
            points.forEachIndexed { index, point ->
                val x = ((point.longitude - minLng) / lngSpan).toFloat() * size.width
                val y = (1.0 - (point.latitude - minLat) / latSpan).toFloat() * size.height
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, routeColor, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

private fun formatDashboardDuration(millis: Long, strings: AppStrings): String {
    val totalMinutes = millis.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0) {
        String.format(Locale.getDefault(), strings.dashboardDurationHours, hours, minutes)
    } else {
        String.format(Locale.getDefault(), strings.dashboardDurationMinutes, minutes)
    }
}

@Composable
private fun ProgressCard(snapshot: GamificationSnapshot, strings: AppStrings, onOpenProgress: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(strings.gamificationMyProgress, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(strings.levelName(snapshot.currentLevelId), style = MaterialTheme.typography.titleLarge)
            val progressRatio = if (snapshot.progressDenominatorMinutes > 0L) {
                (snapshot.progressNumeratorMinutes.toFloat() / snapshot.progressDenominatorMinutes.toFloat())
                    .coerceIn(0f, 1f)
            } else 1f
            val progressStr = snapshot.nextThresholdMinutes?.let { nextMinutes ->
                String.format(
                    Locale.getDefault(),
                    strings.gamificationProgress,
                    snapshot.currentMinutes.toString(),
                    nextMinutes.toString(),
                )
            } ?: String.format(
                Locale.getDefault(),
                strings.gamificationMaxProgress,
                snapshot.currentMinutes.toString(),
            )
            Column(modifier = Modifier.semantics(mergeDescendants = true) { 
                contentDescription = progressStr 
            }) {
                LinearProgressIndicator(progress = { progressRatio }, modifier = Modifier.fillMaxWidth().height(8.dp), color = MaterialTheme.colorScheme.primary)
                Text(progressStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }

            snapshot.latestUnlockedMilestoneId?.let { milestone ->
                Column {
                    Text(
                        strings.gamificationLatestMilestone,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(strings.formatMilestone(milestone), style = MaterialTheme.typography.bodyMedium)
                }
            }

            FilledTonalButton(onClick = onOpenProgress, modifier = Modifier.fillMaxWidth()) {
                Text(strings.gamificationViewProgress)
            }
        }
    }
}
