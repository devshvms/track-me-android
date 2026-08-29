package `in`.shvms.trackme.ui.gamification

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.domain.gamification.GamificationDefinitions
import `in`.shvms.trackme.domain.gamification.GamificationLevel

@Composable
fun GamificationProgressCard(
    currentLevel: GamificationLevel,
    totalActiveMinutes: Long,
    unlockedAchievements: List<String>,
    modifier: Modifier = Modifier
) {
    val nextLevel = GamificationDefinitions.LEVELS.firstOrNull { it.level == currentLevel.level + 1 }
    val progress = if (nextLevel != null) {
        val range = (nextLevel.requiredActiveMinutes - currentLevel.requiredActiveMinutes).toFloat()
        val current = (totalActiveMinutes - currentLevel.requiredActiveMinutes).toFloat()
        (current / range).coerceIn(0f, 1f)
    } else {
        1f
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Level ${currentLevel.level}: ${currentLevel.name}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            // Progress Bar
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "$totalActiveMinutes active minutes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (nextLevel != null) {
                        Text(
                            "${nextLevel.requiredActiveMinutes} for ${nextLevel.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Max level reached",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(8.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }

            // Achievements
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (unlockedAchievements.isNotEmpty()) Icons.Default.Star else Icons.Default.StarOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${unlockedAchievements.size} Achievements Unlocked",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
