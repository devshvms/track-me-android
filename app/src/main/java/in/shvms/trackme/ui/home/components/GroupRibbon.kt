package `in`.shvms.trackme.ui.home.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.data.remote.GroupSessionState
import `in`.shvms.trackme.data.remote.GroupSessionStatus
import `in`.shvms.trackme.ui.community.deterministicTint
import `in`.shvms.trackme.ui.localization.LocalAppStrings
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The Group Ribbon — SCOPE_1.7.0 §3.2.1, the release's **primary signal**.
 *
 * §3.2 calls this *"the primary signal… This one element does most of the work: it is always
 * visible, it is unambiguous, it answers 'who can see me right now' without the user going
 * looking, and it satisfies the research requirement that a live session be visible, expiring, and
 * revocable at all times."*
 *
 * §15.3 is why this ships and the third `ColorScheme` does not: the ribbon is **additive** — a new
 * composable that cannot regress an existing screen — and it delivers ~80% of the "you are in a
 * different mode now" signal at a fraction of the risk of a theme swap that touches every screen
 * across 7 locales and 2 themes.
 *
 * Depth and motion, never a second hue (§3.1, §3.2): `BRAND_SYSTEM.md` locks one accent, so the
 * ribbon reads as elevated frosted navy with a cyan perimeter, not as a new colour.
 */
@Composable
fun GroupRibbon(
    session: GroupSessionState,
    memberCount: Int,
    onLeave: () -> Unit,
    onOpenRoster: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = isReduceMotionEnabled(),
) {
    if (!session.isActive) return

    val strings = LocalAppStrings.current
    val remaining = session.expiresAtMillis - System.currentTimeMillis()

    // §3.2.1: "a slow cyan perimeter glow — a 3-second breathing animation at low amplitude, not a
    // pulse." §3.6 requires it to respect reduce-motion; a pulsing edge is exactly the kind of
    // thing that triggers vestibular discomfort.
    val glowAlpha = if (reduceMotion) {
        STATIC_GLOW_ALPHA
    } else {
        val transition = rememberInfiniteTransition(label = "ribbonGlow")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.75f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3_000),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "ribbonGlowAlpha",
        ).value
    }

    val summary = ribbonSummary(session, memberCount, remaining, strings.groupTimeLeft, strings.groupVisibleUntil)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha),
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
            // §3.6: "The Group Ribbon is a single merged TalkBack element with a custom 'Leave
            // group' action." One node, one sentence, and the exit reachable without hunting for a
            // separate button — §5.1.3 requires leave to be reachable from the ribbon on any screen.
            .semantics(mergeDescendants = true) {
                contentDescription = summary
                customActions = listOf(
                    CustomAccessibilityAction(strings.groupLeave) { onLeave(); true },
                    CustomAccessibilityAction(strings.navCommunity) { onOpenRoster(); true },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarStack(session)
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                session.groupName ?: strings.groupSignedOutTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                // §3.5: "Always state duration and audience together." The ribbon repeats the
                // consent summary continuously (§5.1.7) rather than showing it once at join.
                ribbonSubtitle(session, memberCount, remaining, strings.groupTimeLeft, strings.groupDegraded),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.size(8.dp))
        // §5.1.3: the exit is sacred — one tap, always reachable, on every screen.
        Text(
            strings.groupLeave,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                // Clickable before padding, so the padding is inside the touch target rather than
                // a dead zone around it — the exit has to be easy to hit (§5.1.3).
                .clickable(onClickLabel = strings.groupLeave) { onLeave() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** §3.2.1: "live member count as a small avatar stack." */
@Composable
private fun AvatarStack(session: GroupSessionState) {
    val shown = session.roster.take(MAX_STACK)
    Box {
        shown.forEachIndexed { index, member ->
            Box(
                modifier = Modifier
                    .offset(x = (index * 14).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(deterministicTint(member.uid), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    member.initials?.take(1) ?: "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        // Reserve the stack's full width so the text beside it does not jump as people join.
        Spacer(Modifier.size((22 + (shown.size - 1).coerceAtLeast(0) * 14).dp))
    }
}

// --- Pure text builders, so the wording is testable without a composition -----------------------

internal const val MAX_STACK = 4
internal const val STATIC_GLOW_ALPHA = 0.55f

/**
 * The single sentence TalkBack reads for the whole ribbon.
 *
 * §5.1.7: consent is educational and continuous — it states who can see you and for how long,
 * every time it is read, not once at join.
 */
internal fun ribbonSummary(
    session: GroupSessionState,
    memberCount: Int,
    remainingMillis: Long,
    timeLeftFormat: String,
    visibleUntilFormat: String,
): String {
    val name = session.groupName.orEmpty()
    val visible = String.format(
        Locale.getDefault(),
        visibleUntilFormat,
        memberCount.coerceAtLeast(0),
        formatClock(session.expiresAtMillis),
    )
    val left = String.format(Locale.getDefault(), timeLeftFormat, formatRemaining(remainingMillis))
    return listOf(name, visible, left).filter { it.isNotBlank() }.joinToString(". ")
}

internal fun ribbonSubtitle(
    session: GroupSessionState,
    memberCount: Int,
    remainingMillis: Long,
    timeLeftFormat: String,
    degradedText: String,
): String = when {
    // §8: never a silent failure. If the relay is not answering, the ribbon says so rather than
    // continuing to imply the user is visible.
    session.status == GroupSessionStatus.DEGRADED -> degradedText
    else -> {
        val left = String.format(Locale.getDefault(), timeLeftFormat, formatRemaining(remainingMillis))
        "$memberCount • $left"
    }
}

internal fun formatRemaining(millis: Long): String {
    if (millis <= 0) return "0m"
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

internal fun formatClock(epochMillis: Long): String =
    if (epochMillis <= 0) "" else java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
        .format(java.util.Date(epochMillis))

@Composable
private fun isReduceMotionEnabled(): Boolean {
    val context = LocalContext.current
    return runCatching {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)
}
