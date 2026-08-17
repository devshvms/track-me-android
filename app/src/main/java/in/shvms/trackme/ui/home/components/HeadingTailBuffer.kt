package `in`.shvms.trackme.ui.home.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import `in`.shvms.trackme.data.remote.GroupWire
import `in`.shvms.trackme.domain.group.HeadingTail

/**
 * The per-session, in-memory store of heading tails — SCOPE_1.7.3 §3 and §0 contract 7.
 *
 * **Deliberately a plain object held by `remember`, and deliberately not any of the alternatives.**
 * The privacy promise this feature rests on is a property of *where the buffer lives*, not of the
 * shape of the data:
 *
 * - Not Room, not DataStore, not SharedPreferences — §5.1.4 forbids retaining location history and
 *   1.7.0 §2.7 says *"Nothing is saved… no position history."* A tail written to disk would breach
 *   the promise the whole Group Ride feature is built on.
 * - Not `rememberSaveable` — saved instance state is serialised to disk by the system on process
 *   death, which is persistence by a quieter route. This is the trap: `rememberSaveable` is the
 *   reflex for "survive a rotation", and reaching for it here would silently write other people's
 *   positions to disk.
 * - Not on `TrackMeApp` or `GroupSessionManager` — those outlive the screen, and §3 requires the
 *   buffer to *"die with the screen and reconstruct from live syncs"*.
 *
 * It is never transmitted either: nothing here is ever handed to `GroupSessionManager`, the relay,
 * or telemetry. The tail is drawn and then forgotten.
 */
class HeadingTailBuffer {

    private val tails = mutableMapOf<String, List<HeadingTail.Sample>>()

    /**
     * Folds one sync's worth of positions in, and returns the tails to draw.
     *
     * @param nowMillis the relay's clock (§4.4/§8: never the device's).
     */
    fun update(
        positions: List<GroupWire.MemberPosition>,
        nowMillis: Long,
    ): Map<String, List<HeadingTail.Sample>> {
        for (position in positions) {
            val sample = HeadingTail.Sample(position.lat, position.lng, position.serverTsMillis)
            tails[position.uid] = HeadingTail.append(
                existing = tails[position.uid].orEmpty(),
                sample = sample,
                nowMillis = nowMillis,
            )
        }
        // A member who left the group must not keep a tail. Their uid stops appearing in the sync,
        // so without this their trail would hang on the map until the screen was disposed.
        tails.keys.retainAll(positions.map { it.uid }.toSet())
        // Prune on read as well as on append: a member who stopped syncing must watch their tail
        // expire rather than keep a frozen one that still implies recent movement.
        return tails.mapValues { (_, samples) -> HeadingTail.prune(samples, nowMillis) }
    }

    /** Drops everything. Called when the group changes, so a uid can never carry across sessions. */
    fun clear() {
        tails.clear()
    }
}

/**
 * Remembers a [HeadingTailBuffer] for as long as this group session is on screen.
 *
 * Keyed by `groupId` for the same reason the avatar cache is (§3.3): a uid from a previous group is
 * never valid in the next one, and a tail that outlived its group would be exactly the retained
 * position history the feature promises not to keep.
 */
@Composable
fun rememberHeadingTailBuffer(groupId: String?): HeadingTailBuffer =
    remember(groupId) { HeadingTailBuffer() }
