package `in`.shvms.trackme.ui.gamification

import `in`.shvms.trackme.ui.localization.AppStrings

fun AppStrings.levelName(levelId: String): String = when (levelId) {
    "level_1" -> this.gamificationLevel1
    "level_2" -> this.gamificationLevel2
    "level_3" -> this.gamificationLevel3
    "level_4" -> this.gamificationLevel4
    "level_5" -> this.gamificationLevel5
    "level_6" -> this.gamificationLevel6
    else -> this.gamificationLevel1
}

fun AppStrings.formatMilestone(milestoneId: String): String {
    val count = milestoneId.removePrefix("milestone_").toIntOrNull() ?: return milestoneId
    return String.format(java.util.Locale.getDefault(), this.gamificationMilestoneTitle, count)
}
