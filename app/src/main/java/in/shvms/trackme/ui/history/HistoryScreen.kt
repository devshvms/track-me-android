package `in`.shvms.trackme.ui.history

import `in`.shvms.trackme.ui.components.rememberMessenger
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import `in`.shvms.trackme.domain.model.RidePersona
import `in`.shvms.trackme.ui.components.icon
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
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
    onNavigateToComparison: (List<Long>) -> Unit = {},
    viewModel: HistoryViewModel = viewModel()
) {
    val strings = LocalAppStrings.current
    val groupedRides by viewModel.groupedRides.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val syncFilter by viewModel.syncFilter.collectAsState()
    val distanceFilter by viewModel.distanceFilter.collectAsState()
    val collapsedGroups by viewModel.collapsedGroups.collectAsState()
    val selectedRideIds by viewModel.selectedRideIds.collectAsState()
    val selectionMode = selectedRideIds.isNotEmpty()
    val visibleRideIds = remember(groupedRides) {
        groupedRides.values.flatten().map { it.ride.id }.toSet()
    }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val messenger = rememberMessenger()
    val app = context.applicationContext as `in`.shvms.trackme.TrackMeApp
    val unitSystem by app.preferencesManager.unitSystem.collectAsState()
    val imperial = unitSystem == "imperial"
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
                    messenger.show("Please select a valid .gpx file.")
                    return@rememberLauncherForActivityResult
                }

                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    viewModel.importGPX(inputStream)
                }
            } catch (e: Exception) {
                messenger.show("Error opening file. Please ensure it's a valid GPX format.")
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.uiEvent.collect { event ->
            when (event) {
                is HistoryViewModel.UiEvent.ShowError -> messenger.show(event.message)
                is HistoryViewModel.UiEvent.Success -> messenger.show(event.message)
                is HistoryViewModel.UiEvent.BatchDeleteCompleted -> {
                    val message = if (event.failedCount == 0) {
                        // §0 contract 6: a queued delete is not a plain success. Reporting it as
                        // one would claim the cloud copy is already gone when it is not.
                        if (event.queuedOffline) {
                            strings.rideDeleteQueuedOffline
                        } else {
                            String.format(Locale.getDefault(), strings.deleteSelectedRidesSuccess, event.deletedCount)
                        }
                    } else {
                        String.format(
                            Locale.getDefault(),
                            strings.deleteSelectedRidesPartialFailure,
                            event.deletedCount,
                            event.failedCount
                        )
                    }
                    messenger.show(message)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) {
                            String.format(Locale.getDefault(), strings.selectedCount, selectedRideIds.size)
                        } else {
                            strings.rideHistoryTitle
                        }
                    )
                },
                navigationIcon = if (selectionMode) {
                    {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = strings.clearSelection)
                        }
                    }
                } else {
                    {}
                },
                actions = {
                    if (selectionMode) {
                        TextButton(onClick = { viewModel.toggleSelectAll(visibleRideIds) }) {
                            Text(
                                if (selectedRideIds.containsAll(visibleRideIds)) strings.clearSelection else strings.selectAll,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = { showDeleteConfirmation = true }) {
                            Icon(Icons.Default.Delete, contentDescription = strings.deleteSelectedRides)
                        }
                        IconButton(
                            onClick = { onNavigateToComparison(selectedRideIds.toList()) },
                            enabled = selectedRideIds.size in 2..8
                        ) {
                            Icon(Icons.Default.Share, contentDescription = strings.shareImage)
                        }
                    } else {
                        TextButton(onClick = { launcher.launch("*/*") }) {
                            Icon(Icons.Default.Download, contentDescription = strings.importGpx, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(strings.importGpx, color = MaterialTheme.colorScheme.primary)
                        }
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
                        DistanceFilterOption.SHORT -> if (imperial) "< 3 mi" else "< 5 km"
                        DistanceFilterOption.MEDIUM -> if (imperial) "3-12 mi" else "5-20 km"
                        DistanceFilterOption.LONG -> if (imperial) "> 12 mi" else "> 20 km"
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
                                DistanceFilterOption.SHORT -> if (imperial) "< 3 mi" else "< 5 km"
                                DistanceFilterOption.MEDIUM -> if (imperial) "3-12 mi" else "5-20 km"
                                DistanceFilterOption.LONG -> if (imperial) "> 12 mi" else "> 20 km"
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
                                imperial = imperial,
                                onToggleCollapse = { viewModel.toggleGroupCollapse(timeGroup) }
                            )
                        }

                        if (!isCollapsed) {
                            items(rideList, key = { it.ride.id }) { rideWithPoints ->
                                RideHistoryCard(
                                    // G4 in the motion audit: the list had stable keys but no
                                    // placement animation, so deleting a ride made the ones below
                                    // jump. Keys make this correct; without them it would animate
                                    // the wrong rows.
                                    modifier = Modifier.animateItem(),
                                    rideWithPoints = rideWithPoints,
                                    imperial = imperial,
                                    selectionMode = selectionMode,
                                    selected = selectedRideIds.contains(rideWithPoints.ride.id),
                                    onClick = {
                                        if (selectionMode) {
                                            viewModel.toggleRideSelection(rideWithPoints.ride.id)
                                        } else {
                                            onNavigateToDetail(rideWithPoints.ride.id)
                                        }
                                    },
                                    onLongClick = { viewModel.toggleRideSelection(rideWithPoints.ride.id) }
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

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(strings.deleteSelectedRides) },
            text = {
                Text(
                    String.format(
                        Locale.getDefault(),
                        strings.deleteSelectedRidesMessage,
                        selectedRideIds.size
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteRides(selectedRideIds)
                    }
                ) { Text(strings.delete) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text(strings.cancel) }
            }
        )
    }
}

@Composable
fun SectionHeader(
    group: TimeGroup,
    rideList: List<RideWithPoints>,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    imperial: Boolean = false
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

    val totalDistanceMeters = rideList.sumOf { (it.ride.postRideCalculation?.distance ?: 0.0) }

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
                text = "${rideList.size} rides • ${`in`.shvms.trackme.domain.UnitFormatter.distance(totalDistanceMeters, imperial, decimals = 1)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun RideHistoryCard(
    rideWithPoints: RideWithPoints,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    selectionMode: Boolean = false,
    selected: Boolean = false,
    imperial: Boolean = false
) {
    val strings = LocalAppStrings.current
    val ride = rideWithPoints.ride
    val points = rideWithPoints.points
    val personaObj = remember(ride.persona) {
        runCatching { RidePersona.valueOf(ride.persona) }.getOrDefault(RidePersona.AUTO)
    }
    // The persona is now conveyed by an icon next to the title (see the header Row below), not
    // an emoji baked into the title string. The accessibility description still needs it in
    // words though — a screen reader can't see the icon — so it's added there explicitly
    // instead of riding along inside rideTitle.
    val rideTitle = ride.title ?: formatDateTime(ride.startTime)
    val distanceText = `in`.shvms.trackme.domain.UnitFormatter.distance(ride.postRideCalculation?.distance ?: 0.0, imperial, decimals = 1)
    val durationText = formatDuration((ride.endTime ?: ride.startTime) - ride.startTime)
    val avgSpeedText = `in`.shvms.trackme.domain.UnitFormatter.speed(ride.postRideCalculation?.avgSpeed?.toDouble() ?: 0.0, imperial)
    val cardDescription = String.format(
        Locale.getDefault(),
        strings.rideCardAccessibilityDescription,
        "${strings.personaLabel(personaObj)} - $rideTitle",
        formatDateTime(ride.startTime),
        distanceText,
        durationText,
        avgSpeedText
    )
    val selectionDescription = if (selected) strings.selectionSelected else strings.selectionNotSelected

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .clearAndSetSemantics {
                contentDescription = "$cardDescription. $selectionDescription"
                stateDescription = selectionDescription
                role = Role.Button
                onClick(label = strings.rideDetailsTitle) {
                    onClick()
                    true
                }
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                // Use the semantic selected surface so the cyan state remains crisp in both
                // light and dark themes instead of blending into a grey wash.
                MaterialTheme.colorScheme.primaryContainer
            } else {
                // surface is the SCREEN BACKGROUND role, so an unselected card was painting
                // itself the same colour as the page behind it and relying entirely on a 1dp
                // shadow to be visible — which in dark theme is invisible. containerLow is the
                // level-1 role and separates by tone, which works in both themes.
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = personaObj.icon(),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = rideTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
                    Text(
                        text = distanceText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = durationText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = avgSpeedText,
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
