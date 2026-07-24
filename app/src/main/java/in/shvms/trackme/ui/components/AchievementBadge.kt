package `in`.shvms.trackme.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A reusable animated "medal" surface for achievement moments — the post-ride reveal today,
 * and any future celebratory badge dialog (weekly recap milestones, streak badges, etc.). Not
 * tied to any single feature's copy or data; callers just pick an [icon].
 *
 * Visual language: a glowing, gently pulsing disc with a soft rotating shine sweep and a
 * bezel ring, built entirely from [MaterialTheme.colorScheme] (brand tokens only — no
 * hardcoded colors), so it inherits C1 cyan/dark-first automatically in both themes.
 *
 * Motion is deliberately calm (slow pulse, slow sweep) — a bounded, earned celebration for a
 * safety app, not a slot-machine flourish. Respects the system's "remove animations"
 * accessibility setting (Settings.Global.ANIMATOR_DURATION_SCALE == 0): when enabled, the
 * badge renders fully settled with no motion at all, including no ambient loop.
 */
@Composable
fun AchievementBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 128.dp
) {
    val context = LocalContext.current
    val animationsEnabled = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }

    // Entrance: a soft overshoot pop. Skipped entirely under reduced motion — render settled.
    val entranceScale = remember { Animatable(if (animationsEnabled) 0.5f else 1f) }
    val entranceAlpha = remember { Animatable(if (animationsEnabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!animationsEnabled) return@LaunchedEffect
        entranceAlpha.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        if (!animationsEnabled) return@LaunchedEffect
        entranceScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f))
    }

    // Ambient loops (glow pulse + shine sweep) — only created at all when motion is allowed, so
    // a reduced-motion session never even builds an infinite animation spec.
    val infiniteTransition = if (animationsEnabled) rememberInfiniteTransition(label = "achievement_badge") else null
    val glowPulse: Float = if (infiniteTransition != null) {
        val v by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glowPulse"
        )
        v
    } else 1f
    val shineAngle: Float = if (infiniteTransition != null) {
        val v by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(3400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shineAngle"
        )
        v
    } else 0f

    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val lighterPrimary = remember(primary) { lerp(primary, Color.White, 0.35f) }
    val darkerPrimary = remember(primary) { lerp(primary, Color.Black, 0.35f) }

    Box(
        // Keep the medal itself at the requested size while removing the large
        // invisible padding that made the reveal card unnecessarily tall.
        modifier = modifier.size(size * 1.32f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = entranceScale.value
                    scaleY = entranceScale.value
                    alpha = entranceAlpha.value
                }
        ) {
            val radius = this.size.minDimension / 2f
            val glowAlpha = 0.28f + glowPulse * 0.22f
            val glowRadius = radius * (1.35f + glowPulse * 0.15f)

            // Soft outer glow — a large, mostly-transparent radial wash behind the medal.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primary.copy(alpha = glowAlpha), primary.copy(alpha = 0f)),
                    center = center,
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = center
            )

            // Medal disc — a lit-sphere gradient (light source top-left) from the brand tokens.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(lighterPrimary, primary, darkerPrimary),
                    center = Offset(center.x - radius * 0.35f, center.y - radius * 0.35f),
                    radius = radius * 1.6f
                ),
                radius = radius,
                center = center
            )

            // Rotating shine sweep — additively blended so it only brightens the disc beneath.
            // The Canvas already renders into its own compositing layer (via the graphicsLayer
            // modifier above for the entrance animation), so the blend stays contained to the
            // medal and never bleeds into the icon or the dialog behind it.
            rotate(degrees = shineAngle, pivot = center) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.30f),
                            Color.Transparent,
                            Color.Transparent,
                            Color.Transparent
                        ),
                        center = center
                    ),
                    radius = radius,
                    center = center,
                    blendMode = BlendMode.Plus
                )
            }

            // Bezel ring for a "minted medal" edge.
            drawCircle(
                color = lighterPrimary.copy(alpha = 0.9f),
                radius = radius - 1.5.dp.toPx(),
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = onPrimary,
            modifier = Modifier
                .size(size * 0.42f)
                .graphicsLayer {
                    scaleX = entranceScale.value
                    scaleY = entranceScale.value
                    alpha = entranceAlpha.value
                }
        )
    }
}
