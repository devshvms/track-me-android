package `in`.shvms.trackme.ui.home.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberMarkerState
import `in`.shvms.trackme.domain.group.StatusSeverity
import androidx.core.graphics.ColorUtils

/**
 * A member's severity, drawn **beside** their avatar and never inside it — SCOPE_1.7.2 §3.5,
 * amendment **A37**.
 *
 * §3.3 of 1.7.0 is emphatic: *"The avatar bitmap must be built once per member per session, cached
 * by member id… regenerating eight `BitmapDescriptor`s every 5–10 seconds will jank the map."*
 *
 * A severity-tinted **ring** was considered and rejected for exactly that reason: the ring is part
 * of the avatar bitmap, so tinting it by status makes the bitmap status-dependent and forces a
 * rebuild on every status change. A badge composited as a **second marker** leaves that invariant
 * untouched — the avatar cache does not learn that status exists.
 *
 * Google Maps needs a `BitmapDescriptor` and cannot compose a marker icon at draw time, so a second
 * marker is the only way to do this without touching the avatar. The badge bitmaps are cached by
 * **severity alone**, so there are at most six for the entire session (three severities × dimmed or
 * not) shared by every member — not three per member. A status change swaps which cached bitmap is
 * drawn; nothing is rasterised.
 *
 * The badge carries **no semantics**: the avatar marker already announces the member's whole
 * sentence including their status (§3.5), and a second announced node beside it would make TalkBack
 * read every member twice.
 */
@Composable
fun SeverityBadgeMarker(
    position: LatLng,
    severity: StatusSeverity,
    dimmed: Boolean,
) {
    val density = LocalDensity.current.density
    Marker(
        // A distinct key per member is the caller's job via `position`; the state is keyed on the
        // same LatLng the avatar uses, in the same composition pass, so the two cannot drift apart
        // by a frame while a member is moving at speed.
        state = rememberMarkerState(key = "badge-${severity.name}-$position", position = position),
        icon = badgeDescriptor(severity, dimmed, density),
        // Top-right of the 40dp avatar, which is anchored at its centre.
        anchor = Offset(-0.6f, 1.4f),
        zIndex = 1f,
        // Decorative. The avatar marker owns the member's description.
        flat = true,
    )
}

private val cache = mutableMapOf<String, BitmapDescriptor>()

/**
 * Three severities, two dim states, one bitmap each — for the whole session, however many members
 * are in the group.
 */
private fun badgeDescriptor(
    severity: StatusSeverity,
    dimmed: Boolean,
    density: Float,
): BitmapDescriptor = cache.getOrPut("${severity.name}-$dimmed") {
    val size = (BADGE_DP * density).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = size / 2f

    // Freshness outranks status (§3.5): a stale member's badge desaturates with them, because a
    // confidently-red badge on a nine-minute-old position would be the §6.3 defect in a new costume.
    val base = severityArgb(severity)
    val fill = if (dimmed) ColorUtils.setAlphaComponent(base, 110) else base

    // White ring so the badge stays legible on satellite imagery as well as pale streets — the same
    // problem `destinationFlagDescriptor` solves the same way.
    canvas.drawCircle(
        radius,
        radius,
        radius - density,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            setShadowLayer(2f * density, 0f, 1f * density, Color.argb(120, 0, 0, 0))
        },
    )
    canvas.drawCircle(
        radius,
        radius,
        radius - 2f * density,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill },
    )

    // §3.1: colour is never the only carrier. The glyph is what a colour-blind rider reads, and it
    // has to stay discriminable at this size.
    val glyph = when (severity) {
        StatusSeverity.ALERT -> "!"
        StatusSeverity.CAUTION -> "⚙"
        StatusSeverity.INFO -> "•"
    }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = size * 0.62f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    val metrics = text.fontMetrics
    canvas.drawText(glyph, radius, radius - (metrics.ascent + metrics.descent) / 2f, text)

    BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun severityArgb(severity: StatusSeverity): Int = when (severity) {
    // Deliberately the raw ARGB rather than the Compose colours: this runs on a `Canvas`, outside
    // any composition, and reaching for a theme token here would be a lie about where it came from.
    StatusSeverity.ALERT -> Color.rgb(0xD3, 0x2F, 0x2F)
    StatusSeverity.CAUTION -> Color.rgb(0xFF, 0xB3, 0x00)
    StatusSeverity.INFO -> Color.rgb(0x26, 0xC6, 0xDA)
}

private const val BADGE_DP = 16f
