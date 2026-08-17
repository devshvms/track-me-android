package `in`.shvms.trackme.domain.sync

/**
 * SCOPE_1.7.3 §2(a) and §0 contracts 3–4 — **how a ride is split across cloud documents.**
 *
 * > *Cloud rides are a parent document plus N ordered chunk documents. The parent carries
 * > `chunkCount` and is written last on upload and deleted last on removal. Readers honour
 * > `chunkCount` and ignore anything beyond it.*
 * >
 * > *Chunk ids are zero-padded so lexical order is chronological order.*
 *
 * ### Why this is its own object
 *
 * §8 names chunk id formatting and ordering as the one thing in 1.7.3 most worth pinning as a
 * shared cross-platform contract: *"byte-identical in both clients, with a test on each side that
 * executes it."* iOS is being built from the same document in parallel, and a client that formats
 * `1` where the other formats `001` produces rides that upload fine and are unreadable by the
 * other platform — the exact class of bug that is invisible until someone switches phone.
 *
 * The vectors live in `track-me-web/tests/fixtures/ride_chunking.json`;
 * [in.shvms.trackme.domain.sync.RideChunkingFixtureTest] executes them against this object.
 *
 * ### Why zero-padding is not decoration
 *
 * §2(a) calls it *"a one-line decision that is very annoying to change later"*. Lexical order is
 * what the Firestore console, any `orderBy(documentId())` query, and any future export tool will
 * use. Unpadded, chunk `10` sorts between `1` and `2`, so a ride reassembled by any of those paths
 * is silently shuffled rather than obviously broken.
 *
 * The reader does not depend on it — [chunkIds] builds the exact id list from `chunkCount`, so
 * reassembly is ordered by construction. Padding is what keeps every *other* way of looking at the
 * data honest.
 */
object RideChunking {

    /**
     * Points per chunk document.
     *
     * §2(a) specifies ~1,000. At the measured 100 bytes per point (§9) a full chunk is ≈100 KB,
     * roughly a tenth of Firestore's 1 MiB document ceiling — enough headroom that no plausible
     * change to the per-point field set can push a chunk over it, and few enough documents that a
     * long ride is still a handful of reads rather than hundreds.
     */
    const val CHUNK_SIZE = 1000

    /**
     * Width of a chunk id, in digits.
     *
     * Three, matching §2(a)'s worked example (`000`, `001`, … not `1`, `10`, `2`) — this is a
     * cross-platform wire format and matching the document iOS is also reading from matters more
     * than defending against a ride nobody will ever record. 999 chunks is 999,000 points, about
     * eleven days of continuous 1 Hz recording.
     *
     * Past that the id simply grows a fourth digit. Reassembly stays correct because [chunkIds]
     * constructs the list from `chunkCount` rather than sorting; only console readability and
     * hypothetical `orderBy(documentId())` queries would degrade. That is the right thing to trade
     * away here, and it is written down so nobody later "fixes" the width and breaks the contract.
     */
    const val CHUNK_ID_DIGITS = 3

    /** The Firestore subcollection under a ride document that holds its chunks. */
    const val POINTS_SUBCOLLECTION = "points"

    /** The parent field naming how many chunks belong to this ride. The commit marker. */
    const val CHUNK_COUNT_FIELD = "chunkCount"

    /** The field inside each chunk document holding that chunk's slice of the ride. */
    const val CHUNK_POINTS_FIELD = "points"

    /**
     * Zero-padded id for the chunk at [index].
     *
     * @throws IllegalArgumentException on a negative index — a caller computing one has a bug, and
     *   silently producing `-01` would write a document that nothing ever reads again.
     */
    fun chunkId(index: Int): String {
        require(index >= 0) { "chunk index must not be negative, was $index" }
        return index.toString().padStart(CHUNK_ID_DIGITS, '0')
    }

    /**
     * How many chunk documents [pointCount] points occupy.
     *
     * Zero points is zero chunks, not one empty one: a ride with no points is a ride with nothing
     * to store, and an empty chunk document would be a read that always returns nothing.
     */
    fun chunkCount(pointCount: Int): Int {
        require(pointCount >= 0) { "point count must not be negative, was $pointCount" }
        return (pointCount + CHUNK_SIZE - 1) / CHUNK_SIZE
    }

    /**
     * Every chunk id for a ride with [chunkCount] chunks, in chronological order.
     *
     * This is what both the reader and the deleter iterate. Building the ids from the count rather
     * than querying the subcollection is deliberate and load-bearing in two places:
     *
     * - **Reading**: §2(a) requires the reader to take *exactly* `chunkCount` chunks and ignore
     *   anything beyond, so a stale orphan left by a re-upload is inert before it is cleaned up.
     * - **Deleting**: a Firestore `WriteBatch` needs its document references up front and cannot
     *   run a collection query, so the count is what makes the cascade expressible as a batch at
     *   all. See [DELETE_BATCH_LIMIT].
     */
    fun chunkIds(chunkCount: Int): List<String> {
        require(chunkCount >= 0) { "chunk count must not be negative, was $chunkCount" }
        return (0 until chunkCount).map(::chunkId)
    }

    /** Splits [points] into the chunks that will be written, in order. */
    fun <T> partition(points: List<T>): List<List<T>> =
        if (points.isEmpty()) emptyList() else points.chunked(CHUNK_SIZE)

    /**
     * Firestore's hard limit on operations in one [com.google.firebase.firestore.WriteBatch].
     *
     * §2(a): at ~1,000 points per chunk this is ~499,000 points in a single atomic delete — beyond
     * any real ride, so **every realistic ride deletes atomically in one batch**. Rides above it
     * must page across batches and lose atomicity, so the resumable path still has to exist; it
     * simply never runs.
     */
    const val DELETE_BATCH_LIMIT = 500

    /**
     * How many batches a cascade delete of [chunkCount] chunks needs.
     *
     * The `+ 1` is the parent document, which shares the last batch. Contract 5 wants all of it in
     * one batch whenever it fits, and it always does in practice.
     */
    fun deleteBatchCount(chunkCount: Int): Int {
        require(chunkCount >= 0) { "chunk count must not be negative, was $chunkCount" }
        val operations = chunkCount + 1
        return (operations + DELETE_BATCH_LIMIT - 1) / DELETE_BATCH_LIMIT
    }

    /** Whether a cascade delete of [chunkCount] chunks is atomic — i.e. fits in one batch. */
    fun deletesAtomically(chunkCount: Int): Boolean = deleteBatchCount(chunkCount) <= 1
}
