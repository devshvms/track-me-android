# TrackMe 1.7.2 — release notes

Continues the Group Ride arc (`SCOPE_1.7.2`, amendments A25–A38). 1.7.0 answered *where is
everyone*. This answers *what is happening to them*, and *can they still see me*.

---

## For the store listing

Ride Together now shows more than dots on a map.

- **Say what's happening.** Set a status — fuel stop, vehicle issue, need help — and the group sees
  it on the roster and as a badge on your marker.
- **Know how fresh it is.** Every rider's row says when the group last heard from them.
- **Get to someone who has stopped.** Open a route preview to their last known point.
- **Know when *you* have gone quiet.** If your connection drops, your own screen says so, instead of
  quietly reassuring you.

> Listing copy must stay silent on safety, emergency, SOS, crash and help. The alert tier is a peer
> status flag inside an ephemeral group; it contacts no emergency service. See `SCOPE_1.7.2` §5.1
> and `GROUP_RIDE_DATA_SAFETY.md` §3.1.

---

## Two defects this fixes, both live in 1.7.0 and 1.7.1

**A frozen GPS looked permanently fresh.** The client resends the same encrypted position when no
new fix arrives, and the relay re-stamped it every time — so a rider whose GPS had stalled stayed
bright on everyone's map reading "8s ago" while their phone had not known where it was for twenty
minutes. The relay now keeps the original timestamp for an unchanged envelope, and that member ages
into stale honestly.

**This half shipped as a relay-only hotfix, ahead of the app release**, and corrected every
already-installed 1.7.0 and 1.7.1 client with no update required.

**Freshness was measured against your own phone's clock.** Timestamps came from the relay, but the
comparison did not — so a phone a few minutes fast greyed out the whole group, and one a few minutes
slow made everyone look fresher than they were. Ages are now anchored to a relay-supplied clock and
advanced on a monotonic one, so no device's wall clock enters the answer.

---

## What is in the app

- Rider status: a structured code carrying severity, activity and message. An older client meeting a
  newer status renders it at the right urgency rather than dropping it.
- Status works with **no position** — revoked location permission, no fix yet, GPS off. That
  independence is the point: the rider who cannot share a position is the one most likely to need it.
- Severity-1 statuses pin to their own section above the roster, raise one heads-up notification and
  a haptic, and **notify again when cleared** — an alarm with no resolution leaves the group riding
  back to a problem that ended.
- A four-second undo on severity-1 holds the outbound write, so a mis-tap never reaches anyone.
- Home carries one composed pill whenever the group can't hear you. In Group Mode it **replaces** the
  green "Offline Shield" pill rather than sitting beside it — that pill is correct about your ride
  and wrong about your group.

## Known limits

- Statuses reach members whose app is syncing. There is no push backend; the picker says so.
- Directions route to a *last known point*. The action is hidden entirely for a stale member.
