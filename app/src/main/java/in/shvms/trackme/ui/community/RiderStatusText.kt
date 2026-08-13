package `in`.shvms.trackme.ui.community

import androidx.compose.ui.graphics.Color
import `in`.shvms.trackme.domain.group.PresenceAge
import `in`.shvms.trackme.domain.group.RiderStatus
import `in`.shvms.trackme.domain.group.RiderStatusCatalog
import `in`.shvms.trackme.domain.group.StatusSeverity
import `in`.shvms.trackme.theme.CyanBright
import `in`.shvms.trackme.theme.TrackMeAmber
import `in`.shvms.trackme.theme.TrackMeRed
import `in`.shvms.trackme.ui.localization.AppStrings
import java.util.Locale

/**
 * Turning a status code and an age into words — SCOPE_1.7.2 §3.1, §3.10.
 *
 * The wire carries the **code**; this is the only place it becomes a sentence. Keeping that boundary
 * sharp is what stops a Hindi rider's status rendering as Hindi text on a German rider's phone,
 * which is a failure that only ever shows up in a mixed-locale group.
 */

/**
 * §3.1's alert colour, aliased away from `RedSos`.
 *
 * `TrackMeRed` is itself an alias for `RedSos`, a name left over from the SOS feature `1.6.4`
 * removed. The colour is already reused for GPS-lost pills so the association is not new — but
 * given §5.1, status call sites reference this name instead. A reviewer grepping the codebase for
 * "SOS" should not land on this feature.
 */
val SeverityAlert: Color = TrackMeRed
val SeverityCaution: Color = TrackMeAmber
val SeverityInfo: Color = CyanBright

fun StatusSeverity.color(): Color = when (this) {
    StatusSeverity.ALERT -> SeverityAlert
    StatusSeverity.CAUTION -> SeverityCaution
    StatusSeverity.INFO -> SeverityInfo
}

/**
 * §3.1: colour is never the only carrier. The glyph is what a colour-blind rider reads, and it must
 * stay discriminable at 14dp on map tiles.
 */
fun StatusSeverity.glyph(): String = when (this) {
    StatusSeverity.ALERT -> "!"
    StatusSeverity.CAUTION -> "⚙"   // gear — "something mechanical is wrong"
    StatusSeverity.INFO -> "•"      // dot
}

/**
 * The label for a status, falling back by severity when the code is one this build has never seen.
 *
 * §4.2's whole argument lives here: a newer client's unknown code still renders at the right
 * urgency, with an honest generic word, instead of disappearing. Compare an opaque enum name, where
 * the only safe fallback is to render nothing and lose the information entirely.
 */
fun AppStrings.statusLabel(status: RiderStatus): String = statusLabelForCode(status.code)
    ?: when (status.severity) {
        StatusSeverity.ALERT -> groupSeverityAlert
        StatusSeverity.CAUTION -> groupSeverityCaution
        StatusSeverity.INFO -> groupSeverityInfo
    }

/**
 * Code to label, explicitly.
 *
 * A `when` rather than a key lookup into `AppStrings.overrides`: the override map holds only the
 * *translated* entries, so an English build — which has no overrides at all — would find nothing and
 * every status would fall back to its severity word. Spelling the mapping out also means the
 * compiler catches a code added to `RiderStatusCatalog` with no label to go with it.
 */
fun AppStrings.statusLabelForCode(code: String): String? = when (code) {
    RiderStatusCatalog.SHORT_BREAK -> groupStatus3GBR
    RiderStatusCatalog.TIRED -> groupStatus3GTI
    RiderStatusCatalog.VEHICLE_ISSUE -> groupStatus2GVI
    RiderStatusCatalog.NEED_HELP -> groupStatus1GNH
    RiderStatusCatalog.CRASHED -> groupStatus1GCR
    RiderStatusCatalog.FUEL_STOP_BIKE -> groupStatus3MFS
    RiderStatusCatalog.ENGINE_HEAT -> groupStatus2MEH
    RiderStatusCatalog.FUEL_STOP_CAR -> groupStatus3CFS
    RiderStatusCatalog.ON_A_CALL -> groupStatus3CIC
    RiderStatusCatalog.WATER_BREAK_CYCLE -> groupStatus3BWA
    RiderStatusCatalog.PUNCTURE -> groupStatus2BPU
    RiderStatusCatalog.WATER_BREAK_WALK -> groupStatus3WWA
    RiderStatusCatalog.WATER_BREAK_RUN -> groupStatus3RWA
    else -> null
}

/** "Riding · 8s ago" — the freshness half of a roster row (§2.2). */
fun AppStrings.ageText(bucket: PresenceAge.Bucket): String? = when (bucket) {
    PresenceAge.Bucket.Now -> groupAgeNow
    is PresenceAge.Bucket.Seconds -> String.format(Locale.getDefault(), groupAgeSeconds, bucket.value)
    is PresenceAge.Bucket.Minutes -> String.format(Locale.getDefault(), groupAgeMinutes, bucket.value)
    is PresenceAge.Bucket.Hours -> String.format(Locale.getDefault(), groupAgeHours, bucket.value)
    // §4.3's reboot case. Rendered as nothing at all — never a guessed number, and never "0s ago",
    // which would claim the status was set this instant.
    PresenceAge.Bucket.Unknown -> null
}

/**
 * "Engine heat · 12m" — how long a status has been standing.
 *
 * Deliberately a different phrasing from [ageText]: an age *since a fix* reads as "8s ago", but a
 * status is a duration someone is still in, so it reads as "12m" without the "ago".
 */
fun AppStrings.statusAgeText(bucket: PresenceAge.Bucket): String? = when (bucket) {
    PresenceAge.Bucket.Now -> null
    is PresenceAge.Bucket.Seconds -> String.format(Locale.getDefault(), groupAgeSetSeconds, bucket.value)
    is PresenceAge.Bucket.Minutes -> String.format(Locale.getDefault(), groupAgeSetMinutes, bucket.value)
    is PresenceAge.Bucket.Hours -> String.format(Locale.getDefault(), groupAgeSetHours, bucket.value)
    PresenceAge.Bucket.Unknown -> null
}

/**
 * A coarse age label for telemetry (§7).
 *
 * Deliberately coarser than the UI's own buckets: the question is "are riders routing to stale
 * points", and a bucket answers it while an exact age would be a needlessly fine-grained trace of
 * one person's session.
 */
fun PresenceAge.Bucket.telemetryBucket(): String = when (this) {
    PresenceAge.Bucket.Now -> "now"
    is PresenceAge.Bucket.Seconds -> "under_1m"
    is PresenceAge.Bucket.Minutes -> if (value < 5) "under_5m" else "over_5m"
    is PresenceAge.Bucket.Hours -> "over_1h"
    PresenceAge.Bucket.Unknown -> "unknown"
}
