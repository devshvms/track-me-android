package `in`.shvms.trackme.ui.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.shvms.trackme.domain.group.RiderStatusCatalog
import `in`.shvms.trackme.domain.group.RiderStatusCodec
import `in`.shvms.trackme.domain.group.StatusPersona
import `in`.shvms.trackme.domain.group.StatusSeverity
import `in`.shvms.trackme.ui.localization.AppStrings

/**
 * Setting your own status — SCOPE_1.7.2 §3.3, amendments **A35** and **A36**.
 *
 * A **modal bottom sheet**, deliberately departing from `CreateGroupSheet` and `JoinGroupSheet`,
 * which are `AlertDialog`s. Those are typed into while stopped; this is tapped **while riding**,
 * one-handed, possibly with gloves. A bottom sheet puts the targets in the thumb arc and dismisses
 * by swipe.
 *
 * **One flat list, not tiered sections.** The rider is not browsing a taxonomy, they are picking the
 * one thing that is true. Severity is carried by colour and by a leading rule whose width varies —
 * §3.6 of 1.7.0 forbids conveying meaning by colour alone, and rule weight is the cheapest way to
 * satisfy that in a list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusPickerSheet(
    persona: StatusPersona?,
    currentCode: String?,
    strings: AppStrings,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    // The catalogue orders these: info first, alert last. That is the opposite of display order
    // everywhere else, and deliberately so — severity 1 sorts FIRST when displayed (the attention
    // section) because it is the most urgent thing to read, and LAST when offered because it is the
    // most expensive thing to mis-tap. Urgency orders reading; mis-tap cost orders tapping.
    val options = RiderStatusCatalog.optionsFor(persona)
    val firstAlertIndex = options.indexOfFirst {
        RiderStatusCodec.parse(it)?.severity == StatusSeverity.ALERT
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                strings.groupStatusTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            options.forEachIndexed { index, code ->
                val parsed = RiderStatusCodec.parse(code) ?: return@forEachIndexed

                // §5.1, and not negotiable. The disclaimer attaches to the alert rows rather than to
                // a section header, so it cannot be scrolled away from the thing it qualifies.
                if (index == firstAlertIndex) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        strings.groupStatusAlertDisclaimer,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }

                StatusRow(
                    label = strings.statusLabelForCode(code) ?: code,
                    severity = parsed.severity,
                    selected = code == currentCode,
                    onClick = { onSelect(code) },
                )
            }

            // §3.3: `None` is an option in the list, not a button below it — clearing is a choice
            // among the same choices. It appears only when something is set; an always-present
            // "None" on an unset picker is a row that does nothing.
            if (currentCode != null) {
                Spacer(Modifier.height(8.dp))
                StatusRow(
                    label = strings.groupStatusNone,
                    severity = null,
                    selected = false,
                    onClick = onClear,
                )
            }
        }
    }
}

/**
 * One row. Height is 56dp so the target clears the 48dp minimum comfortably — §3.4's touch-target
 * rule, and this sheet is used at speed.
 */
@Composable
private fun StatusRow(
    label: String,
    severity: StatusSeverity?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = severity?.color() ?: MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                this.selected = selected
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Colour AND width. A colour-blind rider gets nothing from hue alone, and thickness is
        // discriminable without it (§3.1).
        val ruleWidth = when (severity) {
            StatusSeverity.ALERT -> 6.dp
            StatusSeverity.CAUTION -> 4.dp
            StatusSeverity.INFO -> 2.dp
            null -> 2.dp
        }
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .width(ruleWidth)
                .height(32.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accent),
        )
        Spacer(Modifier.width(14.dp))

        if (severity != null) {
            Box(
                modifier = Modifier.size(22.dp).clip(CircleShape).background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    severity.glyph(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                )
            }
            Spacer(Modifier.width(12.dp))
        }

        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (severity == StatusSeverity.ALERT) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )

        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

/**
 * The chip a member's status renders as, on a roster row.
 *
 * `age` is null for the reboot case (§4.3) and for a status set moments ago — in both, the chip
 * simply carries no age rather than a fabricated or noisy one.
 */
@Composable
fun StatusChip(
    label: String,
    severity: StatusSeverity,
    age: String?,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    /**
     * Non-null on **your own** chip, where it reopens the picker.
     *
     * Device testing found the chip was a dead end: the "set status" affordance was an `else` to
     * this chip, so it disappeared the moment anything was set, and a severity-1 status moved you
     * into the attention section which wired no callback at all. "Need help" became unwithdrawable.
     * The chip itself is now the way back in.
     */
    onClick: (() -> Unit)? = null,
) {
    val accent = severity.color().let { if (dimmed) it.copy(alpha = 0.45f) else it }
    // Two calls rather than a conditional modifier: a `.clickable` above the Surface draws its
    // press indication on the square layout bounds, so a rounded chip flashed as a rectangle.
    // The onClick overload puts it inside the Surface's own clip.
    val chipContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                severity.glyph(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
            )
            Text(
                if (age != null) "$label · $age" else label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
            )
            if (onClick != null) {
                // Signals "you can change this" without a second control competing for the tap.
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            color = accent,
            modifier = modifier.heightIn(min = 32.dp),
            content = chipContent,
        )
    } else {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = accent,
            modifier = modifier,
            content = chipContent,
        )
    }
}
