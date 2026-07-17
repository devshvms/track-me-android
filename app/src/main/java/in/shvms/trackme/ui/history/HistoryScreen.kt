package `in`.shvms.trackme.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import `in`.shvms.trackme.domain.model.RidePersona
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import `in`.shvms.trackme.data.local.entity.GPSPointEntity
import `in`.shvms.trackme.data.local.entity.RideWithPoints
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    onNavigateToDetail: (Long) -> Unit,
    viewModel: HistoryViewModel = viewModel()
) {
    val strings = LocalAppStrings.current
    val groupedRides by viewModel.groupedRides.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val syncFilter by viewModel.syncFilter.collectAsState()
    val distanceFilter by viewModel.distanceFilter.collectAsState()
    val collapsedGroups by viewModel.collapsedGroups.collectAsState()

    val context = LocalContext.current
    val snackbarHostState = `in`.shvms.trackme.LocalSnackbarHostState.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItemsCount = listState.layoutInfo.totalItemsCount
            lastVisibleItemIndex >= totalItemsCount - 2 && totalItemsCount > 0
        }.collect { isNearEnd ->
            if (isNearEnd) {
                viewModel.loadMoreRides()
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                var name = ""
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            name = cursor.getString(nameIndex)
                        }
                    }
                }

                if (name.isNotEmpty() && !name.lowercase(Locale.ROOT).endsWith(".gpx")) {
                    android.widget.Toast.makeText(context, "Please select a valid .gpx file.", android.widget.Toast.LENGTH_SHORT).show()
                    return@rememberLauncherForActivityResult
                }

                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    viewModel.importGPX(inputStream)
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Error opening file. Please ensure it's a valid GPX format.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is HistoryViewModel.UiEvent.ShowError -> android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
                is HistoryViewModel.UiEvent.Success -> android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings.rideHistoryTitle) },
                actions = {
                    TextButton(onClick = { launcher.launch("*/*") }) {
                        Icon(Icons.Default.Download, contentDescription = strings.importGpx, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(strings.importGpx, color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Compact Inline Filter Bar on page open (No Time strip, No Sort)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Sync Filter Dropdown Chip
                Box {
                    var showSyncMenu by remember { mutableStateOf(false) }
                    val syncLabel = when (syncFilter) {
                        SyncFilterOption.ALL -> strings.syncStatusAll
                        SyncFilterOption.SYNCED -> strings.syncStatusSynced
                        SyncFilterOption.LOCAL_ONLY -> strings.syncStatusLocal
                    }
                    FilterChip(
                        selected = syncFilter != SyncFilterOption.ALL,
                        onClick = { showSyncMenu = true },
                        label = { Text(syncLabel, style = MaterialTheme.typography.labelMedium) },
                        trailingIcon = {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                    DropdownMenu(expanded = showSyncMenu, onDismissRequest = { showSyncMenu = false }) {
                        SyncFilterOption.values().forEach { opt ->
                            val label = when (opt) {
                                SyncFilterOption.ALL -> strings.syncStatusAll
                                SyncFilterOption.SYNCED -> strings.syncStatusSynced
                                SyncFilterOption.LOCAL_ONLY -> strings.syncStatusLocal
                            }
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    viewModel.setSyncFilter(opt)
                                    showSyncMenu = false
                                }
                            )
                        }
                    }
                }

                // Distance Filter Dropdown Chip
                Box {
                    var showDistMenu by remember { mutableStateOf(false) }
                    val distLabel = when (distanceFilter) {
                        DistanceFilterOption.ALL -> strings.distanceAll
                        DistanceFilterOption.SHORT -> "< 5 km"
                        DistanceFilterOption.MEDIUM -> "5-20 km"
                        DistanceFilterOption.LONG -> "> 20 km"
                    }
                    FilterChip(
                        selected = distanceFilter != DistanceFilterOption.ALL,
                        onClick = { showDistMenu = true },
                        label = { Text(distLabel, style = MaterialTheme.typography.labelMedium) },
                        trailingIcon = {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                    DropdownMenu(expanded = showDistMenu, onDismissRequest = { showDistMenu = false }) {
                        DistanceFilterOption.values().forEach { opt ->
                            val label = when (opt) {
                                DistanceFilterOption.ALL -> strings.distanceAll
                                DistanceFilterOption.SHORT -> "< 5 km"
                                DistanceFilterOption.MEDIUM -> "5-20 km"
                                DistanceFilterOption.LONG -> "> 20 km"
                            }
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    viewModel.setDistanceFilter(opt)
                                    showDistMenu = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Reset Button when any filter is active
                if (syncFilter != SyncFilterOption.ALL || distanceFilter != DistanceFilterOption.ALL) {
                    TextButton(onClick = { viewModel.resetFilters() }) {
                        Text(strings.resetFilter, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Ride List with Mutually Exclusive Chronological Grouping
            if (groupedRides.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(strings.noRidesRecorded, style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    groupedRides.forEach { (timeGroup, rideList) ->
                        val isCollapsed = collapsedGroups.contains(timeGroup)

                        stickyHeader(key = "header_${timeGroup.name}") {
                            SectionHeader(
                                group = timeGroup,
                                rideList = rideList,
                                isCollapsed = isCollapsed,
                                onToggleCollapse = { viewModel.toggleGroupCollapse(timeGroup) }
                            )
                        }

                        if (!isCollapsed) {
                            items(rideList, key = { it.ride.id }) { rideWithPoints ->
                                RideHistoryCard(
                                    rideWithPoints = rideWithPoints,
                                    onClick = { onNavigateToDetail(rideWithPoints.ride.id) }
                                )
                            }
                        }
                    }

                    if (isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(
    group: TimeGroup,
    rideList: List<RideWithPoints>,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit
) {
    val strings = LocalAppStrings.current
    val groupTitle = when (group) {
        TimeGroup.TODAY -> strings.groupToday
        TimeGroup.YESTERDAY -> strings.groupYesterday
        TimeGroup.THIS_WEEK -> strings.groupThisWeek
        TimeGroup.THIS_MONTH -> strings.groupThisMonth
        TimeGroup.THIS_YEAR -> strings.groupThisYear
        TimeGroup.EARLIER -> strings.groupEarlier
    }

    val totalDistanceKm = rideList.sumOf { (it.ride.postRideCalculation?.distance ?: 0.0) } / 1000.0

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleCollapse() },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 2.dp,
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isCollapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = groupTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = "${rideList.size} rides • ${String.format(Locale.getDefault(), "%.1f km", totalDistanceKm)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RideHistoryCard(
    rideWithPoints: RideWithPoints,
    onClick: () -> Unit
) {
    val strings = LocalAppStrings.current
    val ride = rideWithPoints.ride
    val points = rideWithPoints.points

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Sleek Vector Route Preview Thumbnail (compact 52dp x 52dp)
            RoutePreviewThumbnail(
                points = points,
                modifier = Modifier.size(52.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            // Right Details Column
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Header Row: Ride Title + Sync Icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val personaObj = remember(ride.persona) {
                        runCatching { RidePersona.valueOf(ride.persona) }.getOrDefault(RidePersona.AUTO)
                    }
                    Text(
                        text = "${personaObj.emoji} " + (ride.title ?: formatDateTime(ride.startTime)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(6.dp))

                    val isSyncedToCloud = ride.firestoreId != null
                    Icon(
                        imageVector = if (isSyncedToCloud) Icons.Default.CloudDone else Icons.Default.PhoneAndroid,
                        contentDescription = if (isSyncedToCloud) strings.syncStatusSynced else strings.syncStatusLocal,
                        tint = if (isSyncedToCloud) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }

                // Subtitle: Date & Time
                Text(
                    text = formatDateTime(ride.startTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Bottom Compact Metrics Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val distanceKm = (ride.postRideCalculation?.distance ?: 0.0) / 1000.0
                    Text(
                        text = String.format(Locale.getDefault(), "%.1f km", distanceKm),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    val durationMillis = (ride.endTime ?: ride.startTime) - ride.startTime
                    Text(
                        text = formatDuration(durationMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val avgSpeedKmh = (ride.postRideCalculation?.avgSpeed ?: 0f) * 3.6f
                    Text(
                        text = String.format(Locale.getDefault(), "%.1f km/h", avgSpeedKmh),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun RoutePreviewThumbnail(
    points: List<GPSPointEntity>,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(trackBackground),
        contentAlignment = Alignment.Center
    ) {
        if (points.size >= 2) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
            ) {
                val minLat = points.minOf { it.latitude }
                val maxLat = points.maxOf { it.latitude }
                val minLng = points.minOf { it.longitude }
                val maxLng = points.maxOf { it.longitude }

                val latSpan = (maxLat - minLat).takeIf { it > 0.00001 } ?: 0.001
                val lngSpan = (maxLng - minLng).takeIf { it > 0.00001 } ?: 0.001

                val path = Path()
                points.forEachIndexed { idx, p ->
                    val x = ((p.longitude - minLng) / lngSpan).toFloat() * size.width
                    val y = (1.0 - (p.latitude - minLat) / latSpan).toFloat() * size.height
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                drawPath(
                    path = path,
                    color = primaryColor,
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        } else {
            Text(
                text = "GPS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatDateTime(timestamp: Long): String {
    if (timestamp == 0L) return "Unknown Date"
    val sdf = SimpleDateFormat("MMM dd, yyyy • h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatDuration(durationMillis: Long): String {
    if (durationMillis <= 0) return "00:00:00"
    val totalSeconds = durationMillis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
}
