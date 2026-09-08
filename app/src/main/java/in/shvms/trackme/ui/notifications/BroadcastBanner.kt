package `in`.shvms.trackme.ui.notifications

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import `in`.shvms.trackme.domain.notifications.BroadcastTag
import `in`.shvms.trackme.domain.notifications.OperatorBroadcast
import `in`.shvms.trackme.theme.BrandThemeConfig
import `in`.shvms.trackme.ui.localization.LocalAppStrings

/**
 * SCOPE_1.8.7 §6.3 — the in-app half of an operator broadcast.
 *
 * shvm's decision was two surfaces per broadcast, and this is the one that survives: a notification
 * is something you swipe away at a traffic light, so if the shade were the only place a broadcast
 * lived, "we told everyone" would mean "we told everyone who happened to be looking".
 *
 * It is deliberately **not** a dialog. A modal over the first screen someone opens is the shape of
 * an ad, and using that shape for an operational notice would spend exactly the goodwill that makes
 * the operational notice work. A banner is dismissible, non-blocking, and still unmissable.
 *
 * Dismissal is per-broadcast and permanent — `markSeen` — because a message the user has read and
 * understood should not follow them around. The push already interrupted once; this is the record,
 * not a second interruption.
 */
@Composable
fun BroadcastBanner(
    broadcast: OperatorBroadcast,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val context = LocalContext.current

    // Urgent is the only tag that earns the destructive colour. Painting a maintenance notice red
    // would make every broadcast look like an emergency, which is how a channel stops being read.
    val accent = if (broadcast.tag == BroadcastTag.URGENT) {
        BrandThemeConfig.redDestructive
    } else {
        BrandThemeConfig.cyanBright
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Text(
            text = broadcast.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = broadcast.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            broadcast.learnMoreUrl?.let { url ->
                TextButton(onClick = {
                    // The parser has already refused anything that is not https, so this cannot
                    // open a scheme the operator did not intend. The check lives at the boundary
                    // rather than here, where it would be easy to forget on the next surface.
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, url.toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text(strings.broadcastLearnMore) }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(strings.broadcastDismiss) }
        }
    }
}
