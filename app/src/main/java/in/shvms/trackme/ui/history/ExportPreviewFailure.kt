package `in`.shvms.trackme.ui.history

/** Keeps render/capture guidance distinct from destination-write failures. */
internal enum class ExportPreviewFailure {
    Render,
    Save
}
