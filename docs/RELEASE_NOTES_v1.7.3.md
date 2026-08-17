# TrackMe 1.7.3 — release notes

Camera, ride integrity, and heading (`SCOPE_1.7.3`). Five items from device testing on 1.7.2: two
defects, two features, and one decision recorded as closed. The storage shape of a cloud ride changes
here for the first time since rides were syncable.

---

## For the store listing

- **The map stays where you put it.** Pan or zoom while recording and it no longer drags you back.
  Tap recentre to follow yourself again.
- **Tap any rider in your group to see them on the map.**
- **A short trail behind each rider** shows which way they are heading.
- **Long rides are no longer split in two.** A ride of any length saves in one piece.
- **Deleting a ride now clears every part of it from the cloud.**

> Play caps release notes at 500 characters per language, and enforces it at the *final* publish
> step — after the AAB, lint, tests and the emulator smoke test. `PlayReleaseNotesTest` now pins it
> locally. `distribution/whatsnew/whatsnew-en-US` is the single source of truth and is shown verbatim
> in the in-app update dialog.

---

## The measurement that gated §2, and came out the other way

§2(a) estimated **~130 bytes per point**, putting the single-document ceiling near **8,000** — below
the 9,000-point auto-split. §9 made the whole priority of §2 conditional on that: if true, rides with
post-processing disabled were *already failing to sync, silently*, because `uploadRide` caught,
logged and returned.

Measured, by binary-searching the accepted point count against the Firestore emulator and
cross-checking against a hand-computation from Firestore's documented storage-size rules — the two
agreed to the byte across three document shapes:

| | |
|---|---|
| Fixed overhead, real path `users/{uid}/rides/{rideId}` | **273 B** |
| Per point (7 fields) | **exactly 100 B** |
| Single-document ceiling | **10,483 points** |
| A 9,000-point ride | 900,273 B — **86% of 1 MiB, fits** |

**The hypothesis did not hold.** The ceiling sits *above* the split, not below it, so nothing was
silently failing and §2 stayed the architecture improvement it was originally sequenced as. The
estimate was 30% high.

The measurement did surface a real silent-sync bug through a different door: **GPX import** inserts a
ride and uploads it with no split, no post-processing and no cap, so an import over 10,483 points
never synced and said nothing. Chunking is the only thing that could fix that path, because the split
never ran there.

---

## Two defects this fixes

**A ride kept recording with nothing on screen.** After an auto-split, `splitRide()` called
`trackingManager.reset()` — which publishes `IDLE` — while the location callback carried straight on
into Part 2. The service's own state stayed `TRACKING`, so the two halves of the same fact disagreed
until the user pressed start, which routed to `resumeTracking()`, republished `TRACKING`, and revealed
a ride already ten minutes and 300 m along. The app was recording a ride the user could not see,
pause or stop, and every second of it was location data collected without visible consent.

`RecordingVisibilityPolicy` now refuses to publish `IDLE` while a ride id is held, and the service
routes every state change through it. Two orderings changed to make the invariant *true* rather than
special-cased: `stopTracking` and `handleForegroundStartFailure` release the ride id before claiming
`IDLE`. A quieter half of the same bug went with it — `reset()` returned the persona to `AUTO` and
`splitRide` read it straight back, so a rider who chose CYCLING got an AUTO Part 2.

**The camera overrode every gesture.** `HomeScreen.kt:317` re-fired on every GPS fix and animated to
the newest point at zoom 17 unconditionally, overriding both a pan *and* a zoom within a second or
two. There was no manual-gesture detection anywhere in the file — 1.7.0 §3.4 described a free-look
mode that has never existed. Worst exactly where it was hit: in a group, zooming out to see everyone
and being yanked back before the screen could be read.

---

## What is in the app

- **Camera follow is a mode, not a behaviour.** Armed on entry to a ride, cleared by any user gesture,
  re-armed **only** by the recentre control — never on a timer (Q1.1), which would recreate the
  complaint every 30 seconds in a group. While following, the target moves and **zoom is left alone**,
  so a rider who zoomed out to 14 to see the group stays at 14. Follow survives backgrounding (Q1.2).
- **A cloud ride is a parent document plus N chunk documents** of ~1,000 points in a `points`
  subcollection. This removes the 1 MiB ceiling entirely and permanently, at any ride length, with
  post-processing left exactly where it is — at ride end, on preserved raw data. Live simplification
  was rejected; the reasoning is in §2 so it is not revisited.
- **Chunk ids are zero-padded** so lexical order is chronological order. Pinned in
  `track-me-web/tests/fixtures/ride-chunking-vectors.json` and executed by `RideChunkingFixtureTest`.
- **The parent is the commit marker and the tombstone** — written last on upload, deleted last on
  removal. `isSynced` is set only once the parent lands, so an interrupted upload leaves invisible
  chunks rather than a half-ride that reassembles into something plausible.
- **Deletion cascades, in one atomic batch.** `deleteRide` previously deleted only the parent, and
  Firestore does not cascade — the moment chunks existed that would have orphaned every one of them
  *unreachably*: no screen lists them, no query finds them, and the app could never delete them again.
  Location data the user believed erased would have persisted indefinitely.
- **Offline deletion is not an error.** Three states — acknowledged, queued, rejected — and only
  rejected is an error, restores the local row, and reaches Crashlytics. Awaiting the batch and
  treating the wait as failure would have said "couldn't delete, try again" for a deletion that was
  queued and would succeed.
- **Roster row tap** opens Home focused on that member and opens their marker, then clears. A row with
  no position stays tappable and says why (Q4.2) — a dead row is indistinguishable from a broken one.
- **A heading tail** behind each other rider: a tapering polyline (Q3.1, on marker-budget grounds),
  time-bounded rather than count-bounded (Q3.3), not drawn for yourself (Q3.2), hidden when
  stationary or stale.
- **The auto-split is deleted**, along with its 8,000-point warning and four notification strings.
  Chunking removed the ceiling it defended, so it had nothing left to protect.

---

## Why the tail does not break the privacy promise

§5.1.4 forbids retaining location history and 1.7.0 §2.7 is blunt: *"Nothing is saved. No group
record, no member list, no position history."* A tail is definitionally a short position history of
another person.

What makes it acceptable is only that it is **in-memory, per-session, and never persisted or
transmitted** — it dies with the screen and reconstructs from live syncs. That is a property of
*where the buffer is held*, so it is enforced structurally: `HeadingTailBuffer` is a plain
`remember(groupId)` object, and tests fail on `rememberSaveable`, DataStore, Room, or any handoff to
the session manager or telemetry. `rememberSaveable` is the specific trap — the reflex for surviving
a rotation, and the system serialises saved instance state to disk.

---

## Migration and compatibility

- **Room 10 → 11**, additive: `pendingDelete` on `rides`, defaulting to 0.
- **Both cloud shapes are read, permanently.** Every ride uploaded before 1.7.3 keeps its `points`
  array; the reader takes the array if present and falls back to chunks. This is permanent complexity,
  not a migration that ends — rewriting them all would be a mass re-upload of the user's history for
  no benefit they can see.
- **`firestore.rules` needed no change.** The existing `users/{userId}/{document=**}` recursive
  wildcard already authorises the subcollection. It is now commented as load-bearing, because
  narrowing it would *look* like tightening and would present as rides silently ceasing to sync.
- **Download cost rises as §2(a) predicted**: a page of 10 typical (post-processed, ~2-chunk) rides is
  ~30 reads instead of 10. Against the 50k/day free tier, restoring 1,000 rides is ~3,000 reads.

---

## Telemetry

One new event, `ride_delete_failed`, carrying a cause bucket (`permission` / `network` / `unknown`)
and whether the delete was single or bulk. Nothing else — not which ride, not when it was ridden, not
where, and not the point count, which would fingerprint a specific ride. Only a genuine rejection
fires it; an offline-queued delete is a normal outcome and would otherwise read as a permanent outage
every time someone deletes a ride in a tunnel. A delete is the one action where the user has said
"stop holding this."

---

## Known limits

- **Cross-platform chunk ids are not yet agreed.** Android formats 3 digits (`000`); iOS currently
  formats 6 (`000000`). Both readers fetch by constructed id, so until this is resolved a ride
  uploaded on one platform is **invisible on the other**. See
  `.ai/context/HANDOFF_1.7.3_ios_chunking.md`.
- **The shared fixture is executed by Android only.** Until iOS runs the same file, the two clients
  agree by inspection, which §8 explicitly rules out.
- **Deletion builds its batch from `chunkCount`, not from a query.** A re-upload whose surplus cleanup
  failed can leave chunks beyond the recorded count; those are inert to readers but are only swept by
  the account-deletion path.
- **A ride above 499 chunks (~499,000 points) pages across batches and loses atomicity.** The
  resumable path exists and will not run in practice.
- **Guest access stays closed** (§5). Sign-in remains required; the reasoning is recorded so the
  analysis is not repeated.
