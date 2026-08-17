package `in`.shvms.trackme.domain.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The cross-platform chunking contract — SCOPE_1.7.3 §2(a), §0 contracts 3–4, and §8.
 *
 * §8: *"Do not let the two implementations agree only by inspection."* iOS is being built from the
 * same document in parallel, and the failure mode here is quiet and expensive — a client that
 * formats `1` where the other formats `001` uploads rides the other platform reassembles in the
 * wrong order or cannot find at all, and nothing surfaces until someone switches phone.
 *
 * The fixture is the canonical copy in `track-me-web/tests/fixtures/ride-chunking-vectors.json`,
 * copied verbatim into `src/test/resources`. The same file must be executed by the iOS suite;
 * until it is, this proves only that Android agrees with the written contract.
 */
class RideChunkingFixtureTest {

    private val fixture: JSONObject by lazy {
        JSONObject(File("src/test/resources/ride-chunking-vectors.json").readText())
    }

    @Test
    fun `the fixture is the same file the web repo holds`() {
        // The copy is the thing that rots. A drifted local copy would let this whole file pass
        // while the contract it claims to pin had already changed underneath it.
        val local = File("src/test/resources/ride-chunking-vectors.json")
        val canonical = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "track-me-web/tests/fixtures/ride-chunking-vectors.json") }
            .firstOrNull { it.exists() }
        if (canonical == null) {
            // The web repo is not checked out beside this one. Not a failure — CI for this module
            // does not clone it — but the comparison is the point of the test, so say so.
            println("track-me-web not found alongside this repo; skipped the byte-identity check")
            return
        }
        assertEquals(
            "src/test/resources/ride-chunking-vectors.json has drifted from the canonical copy in " +
                "track-me-web/tests/fixtures — §8 requires them byte-identical",
            canonical.readText(),
            local.readText(),
        )
    }

    @Test
    fun `the shape constants match the contract`() {
        val shape = fixture.getJSONObject("shape")
        assertEquals(shape.getInt("chunkSize"), RideChunking.CHUNK_SIZE)
        assertEquals(shape.getInt("chunkIdDigits"), RideChunking.CHUNK_ID_DIGITS)
        assertEquals(shape.getInt("deleteBatchLimit"), RideChunking.DELETE_BATCH_LIMIT)
        assertEquals(shape.getString("chunkCountField"), RideChunking.CHUNK_COUNT_FIELD)
        assertEquals(shape.getString("chunkPointsField"), RideChunking.CHUNK_POINTS_FIELD)
        assertEquals(shape.getString("pointsSubcollection"), RideChunking.POINTS_SUBCOLLECTION)
    }

    @Test
    fun `every chunk id vector formats exactly as the contract says`() {
        val vectors = fixture.getJSONArray("chunkIds")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val index = v.getInt("index")
            assertEquals(
                "chunk id for index $index${v.optString("note").let { if (it.isEmpty()) "" else " — $it" }}",
                v.getString("id"),
                RideChunking.chunkId(index),
            )
        }
    }

    @Test
    fun `every chunk count vector matches`() {
        val vectors = fixture.getJSONArray("chunkCounts")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val points = v.getInt("pointCount")
            assertEquals(
                "chunk count for $points points${v.optString("note").let { if (it.isEmpty()) "" else " — $it" }}",
                v.getInt("chunkCount"),
                RideChunking.chunkCount(points),
            )
        }
    }

    @Test
    fun `every delete-batching vector matches`() {
        val vectors = fixture.getJSONArray("deleteBatching")
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val chunks = v.getInt("chunkCount")
            assertEquals(
                "batch count for $chunks chunks",
                v.getInt("batches"),
                RideChunking.deleteBatchCount(chunks),
            )
            assertEquals(
                "atomicity for $chunks chunks",
                v.getBoolean("atomic"),
                RideChunking.deletesAtomically(chunks),
            )
        }
    }

    // --- Properties the fixture implies but does not enumerate -----------------------------------

    @Test
    fun `ids are lexically ordered across the whole padded range`() {
        // The entire reason for padding. Unpadded, "10" sorts between "1" and "2", so a ride
        // reassembled by the console or by orderBy(documentId()) is silently shuffled rather than
        // obviously broken.
        val ids = RideChunking.chunkIds(1000)
        assertEquals(
            "zero-padded ids must already be in lexical order up to the 3-digit ceiling",
            ids.take(1000).sorted(),
            ids.take(1000),
        )
    }

    @Test
    fun `partitioning covers every point exactly once, in order`() {
        for (count in listOf(0, 1, 999, 1000, 1001, 2500)) {
            val points = (0 until count).toList()
            val chunks = RideChunking.partition(points)
            assertEquals("chunk count for $count points", RideChunking.chunkCount(count), chunks.size)
            assertEquals("flattening $count points must reproduce the ride", points, chunks.flatten())
            assertTrue(
                "no chunk may exceed CHUNK_SIZE",
                chunks.all { it.size <= RideChunking.CHUNK_SIZE },
            )
            assertTrue("no chunk may be empty", chunks.all { it.isNotEmpty() })
        }
    }

    @Test
    fun `a negative index or count is a caller bug rather than a silent id`() {
        // Producing "-01" would write a document nothing ever reads again — an orphan by
        // construction, which is the failure this whole design exists to prevent.
        for (bad in listOf(-1, -1000)) {
            runCatching { RideChunking.chunkId(bad) }
                .onSuccess { throw AssertionError("chunkId($bad) returned \"$it\" instead of throwing") }
            runCatching { RideChunking.chunkCount(bad) }
                .onSuccess { throw AssertionError("chunkCount($bad) returned $it instead of throwing") }
            runCatching { RideChunking.chunkIds(bad) }
                .onSuccess { throw AssertionError("chunkIds($bad) returned $it instead of throwing") }
        }
    }
}
