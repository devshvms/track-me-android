package `in`.shvms.trackme.ui.history

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import `in`.shvms.trackme.analytics.AnalyticsManager
import `in`.shvms.trackme.analytics.ExportArtifactKind

/**
 * TASK-305 — fires `export_shared` when the user commits to a destination for an exported artifact.
 *
 * The sibling of [in.shvms.trackme.ui.community.GroupInviteChosenReceiver], for the same reason and
 * with the same restraint. `Intent.EXTRA_CHOSEN_COMPONENT` is the closest signal Android offers that
 * a share actually happened: it arrives only once a target is picked, and dismissing the sheet
 * delivers nothing. It cannot observe whether the user then pressed Send inside the other app, so
 * the event is named after the observable action rather than pretending it proves delivery.
 *
 * The chosen component is **not** read. It is the whole reason this receiver can exist without
 * being a tracker: it records that a share was committed to, not where it went.
 *
 * `export_shared ÷ export_rendered` is the ratio the spec names as the success metric, and it is
 * only meaningful if the numerator counts commitments rather than sheet openings — the TASK-289
 * defect, which had a 2-of-42 baseline that turned out to count nothing of the kind.
 */
class ExportSharedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Explicit and non-exported, but still reject an unexpected action so a future
        // PendingIntent reuse cannot silently manufacture growth events.
        if (intent?.action != ACTION) return
        val kind = when (intent.getStringExtra(EXTRA_KIND)) {
            ExportArtifactKind.VIDEO.value -> ExportArtifactKind.VIDEO
            ExportArtifactKind.IMAGE.value -> ExportArtifactKind.IMAGE
            else -> return
        }
        AnalyticsManager.trackExportShared(kind)
    }

    companion object {
        const val ACTION = "in.shvms.trackme.EXPORT_SHARED"
        const val EXTRA_KIND = "kind"
    }
}
