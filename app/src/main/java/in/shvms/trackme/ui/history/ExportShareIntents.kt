package `in`.shvms.trackme.ui.history

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import `in`.shvms.trackme.analytics.AnalyticsManager
import `in`.shvms.trackme.analytics.ExportArtifactKind
import java.io.File

/**
 * TASK-305 — the one place an exported artifact reaches the share sheet.
 *
 * The image and the video each built their own `ACTION_SEND` chooser, and neither reported
 * anything. Two call sites meant two chances to instrument one of them and forget the other, which
 * is how a funnel ends up measuring the image path and quietly missing the video — the artifact the
 * whole task is about.
 *
 * Two events fire from here, and the split is the point:
 *
 * - `export_share_sheet_opened` when the sheet is presented, from this function.
 * - `export_shared` when a destination is actually chosen, from [ExportSharedReceiver].
 *
 * TASK-289 is why. `group_invite_sent` fired on presentation, so its 2-of-42 baseline counted
 * openings and could not separate "nobody shared" from "everybody opened the sheet and backed
 * out" — opposite problems with opposite fixes. `export_shared ÷ export_rendered` is this task's
 * stated success metric, so the numerator has to mean what it says from the first day it is
 * collected; there is no retrofitting a baseline.
 */
internal fun shareExportedArtifact(
    context: Context,
    file: File,
    kind: ExportArtifactKind,
    chooserTitle: String,
) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = if (kind == ExportArtifactKind.VIDEO) "video/mp4" else "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // MUTABLE is required: the system fills in EXTRA_CHOSEN_COMPONENT on this PendingIntent. Safe
    // because the receiver is not exported and reads only the kind we put here ourselves.
    //
    // A distinct request code per kind: extras are not part of Intent.filterEquals, so an image
    // and a video chooser would otherwise share one PendingIntent and FLAG_UPDATE_CURRENT would
    // let the second overwrite the first's kind.
    val callback = PendingIntent.getBroadcast(
        context,
        SHARE_REQUEST_CODE_BASE + kind.ordinal,
        Intent(context, ExportSharedReceiver::class.java)
            .setAction(ExportSharedReceiver.ACTION)
            .putExtra(ExportSharedReceiver.EXTRA_KIND, kind.value),
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
    )

    AnalyticsManager.trackExportShareSheetOpened(kind)
    context.startActivity(
        Intent.createChooser(send, chooserTitle, callback.intentSender)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private const val SHARE_REQUEST_CODE_BASE = 4300
