package `in`.shvms.trackme.ui.gamification

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.shvms.trackme.domain.gamification.GamificationEngine
import `in`.shvms.trackme.ui.localization.AppStrings

/**
 * TASK-277: a rider's level, worn on their own avatar.
 *
 * An energised ring in the level's accent, and the level number in the corner. The ring is the
 * reward made visible in the one place a person looks at themselves; the number is there because a
 * ring alone is a colour, and a colour alone is not a fact anyone can read out.
 *
 * **This is earned, not bought.** A supporter or "pro" treatment was considered and is not built:
 * Apple does not permit IAP for collecting donations, but giving a donor a visible badge makes it a
 * digital good that requires IAP, and the two rules together turn a contribution page back into the
 * paywall `LEVEL-THEME-01` deliberately removed. If that is ever decided differently, it is a second
 * trigger on this same component rather than a second component.
 *
 * **Own avatar only.** Putting a level on other riders' markers would mean adding it to the
 * encrypted roster envelope, re-declaring shared data, and letting everyone in a group read
 * everyone's rank -- which is the soft leaderboard `GAMIFICATION.md` §9 rules out. That needs a
 * product decision, not a component.
 */
@Composable
fun LevelAvatar(
    levelIndex: Int,
    strings: AppStrings,
    modifier: Modifier = Modifier,
    diameter: androidx.compose.ui.unit.Dp = 100.dp,
    avatar: @Composable BoxScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val accent = GamificationPalette.accent(levelIndex, dark)
    val level = GamificationEngine.levels.getOrNull(levelIndex)
    val context = LocalContext.current

    // Same gate AchievementBadge uses: under reduced motion the infinite spec is never built at
    // all, rather than built and then ignored.
    val animationsEnabled = remember {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) != 0f
    }
    val transition = if (animationsEnabled) rememberInfiniteTransition(label = "levelRing") else null
    val energy: Float = if (transition != null) {
        val v by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(RING_PULSE_MILLIS),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ringEnergy",
        )
        v
    } else {
        0.5f
    }

    val ringWidth = diameter * 0.055f
    val glowReach = diameter * 0.16f
    val description = level?.let {
        "${strings.levelName(it.id)}, ${strings.gamificationLevels} ${levelIndex + 1}"
    } ?: strings.gamificationMyProgress

    Box(
        modifier = modifier
            .size(diameter + glowReach * 2)
            // One label for the whole assembly. A screen reader announcing a ring, a photo and a
            // number as three things would be three ways of saying one.
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter + glowReach * 2)) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val ringRadius = (diameter.toPx() + ringWidth.toPx()) / 2f
            // The glow breathes outward from the ring rather than the ring itself changing size:
            // a ring that grows and shrinks drags the avatar's edge with it and reads as wobble.
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.72f to Color.Transparent,
                        0.86f to accent.copy(alpha = 0.10f + 0.22f * energy),
                        1.0f to Color.Transparent,
                    ),
                    center = centre,
                    radius = ringRadius + glowReach.toPx(),
                ),
                radius = ringRadius + glowReach.toPx(),
                center = centre,
            )
            drawCircle(
                color = accent.copy(alpha = 0.75f + 0.25f * energy),
                radius = ringRadius,
                center = centre,
                style = Stroke(width = ringWidth.toPx()),
            )
        }

        Box(Modifier.size(diameter).clip(CircleShape), contentAlignment = Alignment.Center) {
            avatar()
        }

        if (level != null) {
            Surface(
                shape = CircleShape,
                color = accent,
                border = androidx.compose.foundation.BorderStroke(
                    diameter * 0.022f,
                    MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // Pulled inside the glow ring so the badge sits on the avatar's edge rather
                    // than floating clear of it.
                    .size(diameter * 0.30f)
                    .offset(x = -glowReach * 0.55f, y = -glowReach * 0.55f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        (levelIndex + 1).toString(),
                        fontSize = (diameter.value * 0.15f).sp,
                        fontWeight = FontWeight.Bold,
                        color = GamificationPalette.onAccent(dark),
                    )
                }
            }
        }
    }
}

/** Slow enough to read as energy rather than a blink; one full breath is about three seconds. */
private const val RING_PULSE_MILLIS = 1500
