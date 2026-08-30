package `in`.shvms.trackme.ui.gamification

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.domain.gamification.GamificationSnapshot
import `in`.shvms.trackme.domain.gamification.GamificationEngine
import `in`.shvms.trackme.ui.localization.AppStrings
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading

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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Text(
                    strings.gamificationLevels,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(8.dp))
                val currentIndex = GamificationEngine.levels.indexOfFirst { it.id == snapshot.currentLevelId }
                    .coerceAtLeast(0)
                
                GamificationEngine.levels.forEachIndexed { index, level ->
                    val isUnlocked = index <= currentIndex
                    val statusText = if (isUnlocked) strings.gamificationMilestoneUnlocked else strings.gamificationMilestoneLocked
                    ListItem(
                        headlineContent = { Text(strings.levelName(level.id)) },
                        supportingContent = {
                            Column {
                                Text(String.format(strings.gamificationUnlocksAt, level.thresholdMinutes.toString()))
                                Text(statusText)
                            }
                        },
                        modifier = Modifier.semantics(mergeDescendants = true) {},
                        colors = ListItemDefaults.colors(
                            containerColor = if (isUnlocked) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }
            
            item {
                Text(
                    strings.gamificationMilestones,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(8.dp))
                
                GamificationEngine.milestones.forEach { milestone ->
                    val isUnlocked = snapshot.unlockedMilestoneIds.contains(milestone.id)
                    val statusText = if (isUnlocked) strings.gamificationMilestoneUnlocked else strings.gamificationMilestoneLocked
                    ListItem(
                        headlineContent = { Text(strings.formatMilestone(milestone.id)) },
                        supportingContent = { Text(statusText) },
                        modifier = Modifier.semantics(mergeDescendants = true) {},
                        colors = ListItemDefaults.colors(
                            containerColor = if (isUnlocked) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }
        }
    }
}
