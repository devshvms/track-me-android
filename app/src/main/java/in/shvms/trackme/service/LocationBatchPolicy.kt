package `in`.shvms.trackme.service

/** Stable identity and monotonic ordering evidence for one platform location fix. */
internal data class LocationBatchKey(
    val elapsedRealtimeNanos: Long,
    val wallTimeMillis: Long,
    val latitudeBits: Long,
    val longitudeBits: Long,
) {
    val orderNanos: Long
        get() = elapsedRealtimeNanos.takeIf { it > 0L }
            ?: wallTimeMillis.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L
}

/**
 * Fused Location may deliver several fixes after batching or power restriction. Process every
 * unique fix oldest-first; callback list order and `lastLocation` are not a recording contract.
 */
internal fun <T> orderedDistinctLocationBatch(
    samples: List<T>,
    key: (T) -> LocationBatchKey,
): List<T> = samples
    .withIndex()
    .sortedWith(compareBy<IndexedValue<T>>({ key(it.value).orderNanos }, { it.index }))
    .distinctBy { key(it.value) }
    .map { it.value }
