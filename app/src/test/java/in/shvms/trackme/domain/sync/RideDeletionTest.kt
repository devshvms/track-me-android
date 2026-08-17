package `in`.shvms.trackme.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SCOPE_1.7.3 §2(a) and §0 contracts 5–6 — **cascade deletion, and the three states it can end in.**
 *
 * §2(a) names this the single thing most likely to be got wrong and most expensive to discover
 * late, *"because nothing visible breaks"*: Firestore does not cascade, so deleting only the parent
 * leaves every chunk behind, unreachable — no screen lists them, no query finds them, and the app
 * can never delete them again. Location data the user believes they erased persists indefinitely.
 *
 * So the wiring half of this file is not decoration. The rules could all be right while
 * `deleteRide` still deleted one document, and nothing would fail until a privacy audit.
 */
class RideDeletionTest {

    // --- The three states -----------------------------------------------------------------------

    @Test
    fun `an acknowledged delete removes the local row and says nothing`() {
        val outcome = RideDeletion.Outcome.Acknowledged
        assertTrue(RideDeletion.mayDeleteLocally(outcome))
        assertFalse(RideDeletion.mustRestoreLocally(outcome))
        assertFalse(RideDeletion.isError(outcome))
        // The row disappearing IS the feedback. A confirmation toast for the expected outcome of an
        // explicit action is noise.
        assertFalse(RideDeletion.needsUserNotice(outcome))
    }

    @Test
    fun `a queued delete removes the local row and is not an error`() {
        // THE TRAP. Firestore's offline persistence is on by default and the completion callback
        // does not fire until the server acknowledges — so treating the wait as failure would show
        // "couldn't delete, try again" for a deletion that is queued and will succeed, and the
        // retry would try to delete documents already pending deletion.
        val outcome = RideDeletion.Outcome.Queued
        assertTrue("a queued delete must still remove the local row", RideDeletion.mayDeleteLocally(outcome))
        assertFalse("queued is not a rejection", RideDeletion.mustRestoreLocally(outcome))
        assertFalse("queued must never reach Crashlytics", RideDeletion.isError(outcome))
        // Not an error, and not silent either: the local row is gone, so saying nothing would imply
        // the cloud copy already is too.
        assertTrue("queued must be told to the user", RideDeletion.needsUserNotice(outcome))
    }

    @Test
    fun `only a rejection keeps and restores the local row`() {
        val outcome = RideDeletion.Outcome.Rejected(RideDeletion.Cause.PERMISSION, null)
        assertFalse(
            "deleting locally after a rejection lets the next sign-in resurrect the ride",
            RideDeletion.mayDeleteLocally(outcome),
        )
        assertTrue(RideDeletion.mustRestoreLocally(outcome))
        assertTrue(RideDeletion.isError(outcome))
        assertTrue(RideDeletion.needsUserNotice(outcome))
    }

    // --- Cause bucketing ------------------------------------------------------------------------

    @Test
    fun `causes bucket into the three the telemetry allows`() {
        assertEquals(RideDeletion.Cause.PERMISSION, RideDeletion.causeOf("PERMISSION_DENIED"))
        assertEquals(RideDeletion.Cause.PERMISSION, RideDeletion.causeOf("UNAUTHENTICATED"))
        assertEquals(RideDeletion.Cause.NETWORK, RideDeletion.causeOf("UNAVAILABLE"))
        assertEquals(RideDeletion.Cause.NETWORK, RideDeletion.causeOf("DEADLINE_EXCEEDED"))
        assertEquals(RideDeletion.Cause.UNKNOWN, RideDeletion.causeOf("INTERNAL"))
        assertEquals(RideDeletion.Cause.UNKNOWN, RideDeletion.causeOf(null))
    }

    @Test
    fun `bucket names are the lowercase ones the spec names`() {
        // §2(a): "cause bucket (permission / network / unknown)".
        assertEquals("permission", RideDeletion.Cause.PERMISSION.bucket)
        assertEquals("network", RideDeletion.Cause.NETWORK.bucket)
        assertEquals("unknown", RideDeletion.Cause.UNKNOWN.bucket)
    }

    // --- The wiring -----------------------------------------------------------------------------

    @Test
    fun `deleting a ride deletes its chunks, children before the parent`() {
        // Firestore does not cascade. Deleting only the parent orphans every chunk permanently and
        // unreachably — this is the finding §2(a) calls "the single thing most likely to be got
        // wrong and the most expensive to discover late".
        val body = bodyOf(syncSource(), "suspend fun deleteRide(firestoreDocId: String)")
        assertTrue(
            "deleteRide must build its chunk references from chunkCount",
            body.contains("RideChunking.chunkIds(chunkCount)"),
        )
        assertTrue("deleteRide must use a WriteBatch", body.contains("firestore.batch()"))
        val deletesChunk = body.indexOf("batch.delete(pointsRef.document(")
        val deletesParent = body.indexOf("batch.delete(rideDocRef)")
        assertTrue("no chunk delete found", deletesChunk >= 0)
        assertTrue("no parent delete found", deletesParent >= 0)
        assertTrue(
            "the parent must be deleted after its chunks — reversing this is exactly how an " +
                "unreachable orphan is made",
            deletesChunk < deletesParent,
        )
    }

    @Test
    fun `deletion uses a batch and never a transaction`() {
        // §2(a) correction 1: "a Firestore transaction cannot enumerate a subcollection", so
        // "read all chunks, then delete them and the parent" is not expressible as one at all.
        val source = syncSource()
        assertFalse(
            "deletion must not use runTransaction — a transaction cannot enumerate a subcollection",
            source.contains("runTransaction"),
        )
    }

    @Test
    fun `an offline commit is reported as queued rather than failed`() {
        val body = bodyOf(syncSource(), "private suspend fun commitAwaitingAck(")
        assertTrue(
            "the commit must time-box the acknowledgement wait rather than awaiting forever",
            body.contains("withTimeoutOrNull(RideDeletion.ACK_TIMEOUT_MS)"),
        )
        assertTrue(
            "a timed-out acknowledgement is Queued, not an error",
            body.contains("RideDeletion.Outcome.Queued"),
        )
    }

    @Test
    fun `the uploader refuses a ride that is pending deletion`() {
        // §0 contract 5: without this, deleting a ride while an upload is in flight re-creates it
        // in the cloud after the batch has already removed it — the ride returns from the dead.
        val body = bodyOf(syncSource(), "private suspend fun uploadRideInternal(rideId: Long)")
        assertTrue(
            "uploadRideInternal must refuse a ride carrying pendingDelete",
            body.contains("pendingDelete"),
        )
    }

    @Test
    fun `the parent is written last, after every chunk`() {
        // The parent is the commit marker. isSynced may only be set once it lands, or an upload
        // interrupted halfway leaves a half-ride that reassembles into something plausible.
        val body = bodyOf(syncSource(), "private suspend fun uploadRideInternal(rideId: Long)")
        val writesChunks = body.indexOf("pointsRef.document(RideChunking.chunkId(index))")
        val writesParent = body.indexOf("rideDocRef.set(rideData)")
        val marksSynced = body.indexOf("isSynced = true")
        assertTrue("no chunk write found", writesChunks >= 0)
        assertTrue("no parent write found", writesParent >= 0)
        assertTrue("chunks must be written before the parent", writesChunks < writesParent)
        assertTrue(
            "isSynced must be set only after the parent lands — the parent is the commit marker",
            writesParent < marksSynced,
        )
    }

    @Test
    fun `the reader takes exactly chunkCount chunks and never queries the subcollection`() {
        // What makes a stale orphan from a re-upload inert before it is cleaned up, and what keeps
        // reassembly ordered by construction rather than by however the server happened to sort.
        val body = bodyOf(syncSource(), "private suspend fun readPointMaps(")
        assertTrue(
            "the reader must iterate RideChunking.chunkIds(chunkCount)",
            body.contains("RideChunking.chunkIds(chunkCount)"),
        )
        assertFalse(
            "the reader must not query the points subcollection — that would pick up orphans " +
                "beyond chunkCount and reassemble a corrupt ride",
            Regex("""pointsRef\s*\.\s*get\(""").containsMatchIn(body),
        )
    }

    @Test
    fun `a legacy array-shaped ride is still readable`() {
        // Permanent complexity, not a migration that ends: every ride uploaded before 1.7.3 keeps
        // its points array, and rewriting them all would be a mass re-upload of the user's entire
        // history for no benefit they can see.
        val body = bodyOf(syncSource(), "private suspend fun readPointMaps(")
        assertTrue(
            "the reader must accept the legacy points array before falling back to chunks",
            body.contains("doc.get(\"points\")"),
        )
    }

    @Test
    fun `the local delete order is flag, cloud, then delete`() {
        // Room's @Transaction is SQLite-only and a Firestore batch is server-only. No primitive
        // spans both, so the ordering carries the correctness.
        val body = bodyOf(historySource(), "private suspend fun deleteRideEverywhere(")
        val flags = body.indexOf("setPendingDelete(rideId, true)")
        val cloud = body.indexOf("firestoreSyncManager.deleteRide(")
        val local = body.indexOf("rideDao.deleteRide(rideId)", cloud)
        assertTrue("no pendingDelete flag found", flags >= 0)
        assertTrue("no cloud delete found", cloud >= 0)
        assertTrue("no local delete after the cloud delete found", local >= 0)
        assertTrue("the flag must be set before the cloud batch", flags < cloud)
        assertTrue("the local delete must follow the cloud batch", cloud < local)
    }

    @Test
    fun `a stranded pendingDelete is resumed rather than left forever`() {
        // "A cloud delete that exists only in memory is an orphan waiting for a process death."
        val body = bodyOf(syncSource(), "private suspend fun resumePendingDeletes()")
        assertTrue(
            "the sweep must read the flagged rides",
            body.contains("getPendingDeleteRides()"),
        )
        assertTrue("the sweep must retry the cascade", body.contains("deleteRide(docId)"))
    }

    @Test
    fun `the split is gone`() {
        // §6.5: "subcollection, then delete the split entirely." Chunking removes the ceiling the
        // split existed to defend, so a surviving splitRide would be cutting rides in half to solve
        // a problem that no longer exists — and would reintroduce §2(b)'s whole surface area.
        val service = serviceSource()
        assertFalse("splitRide still exists", service.contains("fun splitRide()"))
        assertFalse("the 9000-point split threshold survives", service.contains("9000"))
        assertFalse("the 8000-point warning threshold survives", service.contains("8000"))
    }

    private fun syncSource(): String = stripped("data/remote/FirestoreSyncManager.kt")
    private fun historySource(): String = stripped("ui/history/HistoryViewModel.kt")
    private fun serviceSource(): String = stripped("service/TrackingService.kt")

    private fun stripped(relative: String): String = read(relative)
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("//.*"), "")

    private fun read(relative: String): String {
        var dir: File? = File("").absoluteFile
        val rel = "app/src/main/java/in/shvms/trackme/$relative"
        while (dir != null) {
            File(dir, rel).takeIf { it.exists() }?.let { return it.readText() }
            File(dir, rel.removePrefix("app/")).takeIf { it.exists() }?.let { return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found")
    }

    /** Brace-matched body of the named declaration. */
    private fun bodyOf(source: String, declaration: String): String {
        val start = source.indexOf(declaration)
        require(start >= 0) { "\"$declaration\" not found — did it get renamed?" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("unbalanced braces in $declaration")
    }
}
