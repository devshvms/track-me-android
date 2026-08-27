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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import `in`.shvms.trackme.domain.home.HomeDashboardSummary
import `in`.shvms.trackme.domain.home.HomeInsight
import `in`.shvms.trackme.domain.home.InsightDirection
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
    onOpenGroupMap: () -> Unit,
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

            if (summary.lifetimeActivityCount > 0) {
                item { WeeklySummaryCard(summary, imperial, strings) }
            } else {
                item { EmptyDashboardCard(strings) }
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
                item { InsightCard(insight, strings) }
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
                Metric(UnitFormatter.rideDistance(summary.currentWeek.distanceMeters, imperial), strings.distance)
                Metric(formatDashboardDuration(summary.currentWeek.activeDurationMillis, strings), strings.duration)
            }
            if (summary.displayStreakWeeks > 1) {
                Text(
                    String.format(Locale.getDefault(), strings.dashboardWeeklyStreak, summary.displayStreakWeeks),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            WeeklyDistanceChart(summary, imperial, strings)
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
private fun WeeklyDistanceChart(summary: HomeDashboardSummary, imperial: Boolean, strings: AppStrings) {
    val buckets = summary.weeklyBuckets.takeLast(4)
    val maxDistance = max(1.0, buckets.maxOfOrNull { it.distanceMeters } ?: 1.0)
    val barColor = MaterialTheme.colorScheme.primary
    val accessibleValues = buckets.map { UnitFormatter.rideDistance(it.distanceMeters, imperial) }
    val accessibleLabel = String.format(
        Locale.getDefault(),
        strings.dashboardWeeklyChartValues,
        accessibleValues.getOrElse(0) { UnitFormatter.rideDistance(0.0, imperial) },
        accessibleValues.getOrElse(1) { UnitFormatter.rideDistance(0.0, imperial) },
        accessibleValues.getOrElse(2) { UnitFormatter.rideDistance(0.0, imperial) },
        accessibleValues.getOrElse(3) { UnitFormatter.rideDistance(0.0, imperial) },
    )
    Canvas(
        Modifier.fillMaxWidth().height(64.dp).semantics {
            contentDescription = accessibleLabel
        }
    ) {
        val gap = 12.dp.toPx()
        val width = (size.width - gap * 3) / 4
        buckets.forEachIndexed { index, bucket ->
            val ratio = (bucket.distanceMeters / maxDistance).toFloat().coerceIn(0f, 1f)
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
private fun EmptyDashboardCard(strings: AppStrings) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(strings.dashboardPrivateOffline, fontWeight = FontWeight.SemiBold)
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
        groupActive -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Groups, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${strings.dashboardGroupActive} • ${String.format(Locale.getDefault(), strings.dashboardGroupMembers, groupMemberCount)}",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenCommunity) { Text(strings.navCommunity) }
                    FilledTonalButton(onClick = onOpenGroupMap) { Text(strings.dashboardViewLiveMap) }
                }
            }
        }
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

@Composable
private fun InsightCard(insight: HomeInsight, strings: AppStrings) {
    val text = when (insight) {
        is HomeInsight.Return -> String.format(
            Locale.getDefault(), strings.dashboardInsightReturn,
            strings.personaLabel(insight.persona), insight.inactiveDays,
        )
        is HomeInsight.PeriodComparison -> when (insight.direction) {
            InsightDirection.HIGHER -> strings.dashboardInsightHigher
            InsightDirection.STABLE -> strings.dashboardInsightStable
            InsightDirection.LOWER -> strings.dashboardInsightLower
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
