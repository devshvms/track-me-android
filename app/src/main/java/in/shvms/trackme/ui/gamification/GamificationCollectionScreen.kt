package `in`.shvms.trackme.ui.gamification

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.domain.gamification.GamificationSnapshot
import `in`.shvms.trackme.ui.localization.AppStrings
import `in`.shvms.trackme.ui.gamification.levelName
import `in`.shvms.trackme.ui.gamification.formatMilestone

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
                        Icon(Icons.Default.ArrowBack, contentDescription = strings.back)
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
                Text(strings.gamificationLevels, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                // Hardcoded the levels for the ladder
                val allLevels = listOf("level_1", "level_2", "level_3", "level_4", "level_5", "level_6")
                val currentIndex = allLevels.indexOf(snapshot.currentLevelId).coerceAtLeast(0)
                
                allLevels.forEachIndexed { index, level ->
                    val isUnlocked = index <= currentIndex
                    val statusText = if (isUnlocked) strings.gamificationMilestoneUnlocked else strings.gamificationMilestoneLocked
                    ListItem(
                        headlineContent = { Text(strings.levelName(level)) },
                        supportingContent = { Text(statusText) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (isUnlocked) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }
            
            item {
                Text(strings.gamificationMilestones, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                // Hardcoded milestones
                val allMilestones = listOf("milestone_1", "milestone_10", "milestone_25", "milestone_50", "milestone_100", "milestone_250", "milestone_500", "milestone_1000")
                
                allMilestones.forEach { milestone ->
                    val isUnlocked = snapshot.unlockedMilestoneIds.contains(milestone)
                    val statusText = if (isUnlocked) strings.gamificationMilestoneUnlocked else strings.gamificationMilestoneLocked
                    ListItem(
                        headlineContent = { Text(strings.formatMilestone(milestone)) },
                        supportingContent = { Text(statusText) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (isUnlocked) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface
                        )
                    )
                }
            }
        }
    }
}
