package `in`.shvms.trackme.ui.history

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import `in`.shvms.trackme.analytics.AnalyticsManager
import `in`.shvms.trackme.analytics.ExportArtifactKind

/**
 * TASK-305 / TASK-359 — fires `export_shared` and `route_card_shared` when the user commits to a destination for an exported artifact.
 *
 * The sibling of [in.shvms.trackme.ui.community.GroupInviteChosenReceiver], for the same reason and
 * with the same restraint. `Intent.EXTRA_CHOSEN_COMPONENT` is the closest signal Android offers that
 * a share actually happened: it arrives only once a target is picked, and dismissing the sheet
 * delivers nothing.
 *
 * Channel attribution maps the chosen package to coarse platform buckets (whatsapp, instagram, etc.)
 * without recording any recipient PII, message bodies, or raw targets.
 */
class ExportSharedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Explicit and non-exported, but still reject an unexpected action so a future
        // PendingIntent reuse cannot silently manufacture growth events.
        if (intent?.action != ACTION) return
        val kind = when (intent.getStringExtra(EXTRA_KIND)) {
            ExportArtifactKind.VIDEO.value -> ExportArtifactKind.VIDEO
            ExportArtifactKind.IMAGE.value -> ExportArtifactKind.IMAGE
            ExportArtifactKind.STICKER.value -> ExportArtifactKind.STICKER
            else -> return
        }

        val chosenComponent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        }
        val channel = resolveShareChannel(chosenComponent?.packageName)

        AnalyticsManager.trackExportShared(kind)
        AnalyticsManager.trackRouteCardShared(
            template = kind.value,
            channel = channel,
        )
    }

    companion object {
        const val ACTION = "in.shvms.trackme.EXPORT_SHARED"
        const val EXTRA_KIND = "kind"

        /** Coarse channel resolution without recipient PII. */
        fun resolveShareChannel(packageName: String?): String {
            if (packageName.isNullOrBlank()) return "unknown"
            val pkg = packageName.lowercase()
            return when {
                pkg.contains("whatsapp") -> "whatsapp"
                pkg.contains("instagram") -> "instagram"
                pkg.contains("telegram") || pkg.contains("challegram") -> "telegram"
                pkg.contains("twitter") || pkg == "com.x.android" -> "x"
                pkg.contains("strava") -> "strava"
                pkg.contains("facebook") || pkg.contains("orca") -> "facebook"
                pkg.contains("messaging") || pkg.contains("mms") || pkg.contains("sms") -> "sms"
                pkg.contains("mail") || pkg.contains("outlook") || pkg.contains("gmail") || pkg == "com.google.android.gm" -> "email"
                else -> "other"
            }
        }
    }
}

