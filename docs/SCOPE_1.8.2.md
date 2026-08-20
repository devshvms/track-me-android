# SCOPE 1.8.2 — proposal

*Draft for discussion. Nothing here is committed to. Every item is grounded in something already
in the repo — an orphaned string, a dark-shipped estimator, a matrix row, a defect found during
1.8.x — rather than in a wishlist.*

## The shape this release wants to be

1.8.1 merged two branches that had been developing in parallel for a month, and it has not been
ridden yet. Meanwhile the robustness matrix carries an entire column of **"code-complete, runtime
verification pending"** at P0, and two features are sitting half-built in the codebase with their
strings already translated into seven languages.

So the honest shape for 1.8.2 is **finish and verify**, not a new headline feature. Two things get
completed, three defects get closed, and the verification debt that 1.8.x accumulated gets paid
down. That is a smaller release than 1.8.0 and a more valuable one.

Sizing below is rough and relative, not calendar.

---

## §0 Contracts

What must be true when 1.8.2 ships, stated so they can be checked rather than felt.

1. **No feature ships with a translated label and no code behind it.** Either the label is wired or
   it is deleted — a string file that promises features the app does not have is a lie told in
   seven languages.
2. **Nothing claims "verified" that a person has not exercised on a device.** The matrix's status
   column means what it says.
3. **The map provider's attribution is unobstructed on every surface, including exported files.**
4. **A setting means the same thing on every screen that offers it.**

---

## §1 Elevation gain — finish what was started (M)

**Evidence.** `AppStrings.kt` defines `elevationGain` and `elevation`, both translated into all
seven locales. Neither is referenced anywhere in the app. `PostRideCalculation` carries maxSpeed,
distance, avgSpeed, pauseDuration, maxAcceleration and rawPointCount — no elevation. Every
`GPSPointEntity` has an `altitude`, and the ride-detail chart already plots it.

So the data is recorded, the axis is drawn, the labels are translated, and the number a rider
actually wants — *how much climbing was that?* — is nowhere in the app.

**What.** Total ascent and descent on `PostRideCalculation`, shown in the ride-detail stat grid and
available to the export panel.

**The part that is not trivial.** Naive summation of positive altitude deltas roughly doubles real
gain, because GPS altitude is far noisier than GPS position — metres of jitter while standing
still. A flat 10 km walk can report 150 m of climbing. This needs a threshold-and-smoothing pass
(ignore deltas below a noise floor, smooth before differencing), and it needs test vectors, or the
number will be confidently wrong. That is the whole engineering content of this item.

**Open question.** Does it belong in the six-cell grid, and if so what leaves? The grid is full.

---

## §2 The ETA decision — the release this was deferred *to* (M–L)

**Evidence.** `DestinationProgress` has been computing a group destination ETA since 1.7 and
emitting `AnalyticsManager.trackGroupEtaCalibration` with predicted-vs-actual samples on every
arrival. `GroupFeatureFlags.SHOW_ETA` gates the display, not the computation. The class doc is
explicit about why:

> *"After one release that gives a real error distribution, and 1.8's display can either ship with
> an honest confidence range or, if the estimator turns out to be poor, be redesigned before anyone
> ever saw a wrong number."*

That release has happened. **The data exists and nobody has looked at it.**

**What.** Read the calibration distribution in PostHog, then take one of three decisions and write
it down:

- **Ship it** with a confidence range honest about the measured error.
- **Redesign it** if the estimator is poor — the samples will say how it fails, not just that it does.
- **Delete it** if arrivals are too rare to calibrate against, rather than carrying dead code
  through another release.

**Why it matters beyond itself.** Notification `ProgressStyle` was skipped in 1.8.0 for exactly one
reason: a determinate progress bar needs a destination, and the only destination the app knows is
behind this flag. Deciding ETA unblocks that too.

**This item starts with an analysis, not a commit.** If the answer is "not enough arrivals yet",
that is a finding, and the item closes as "keep gathering" rather than dragging on.

---

## §3 Three defects carried out of 1.8.x (S each)

All three were found during 1.8.x, all three were deliberately not fixed there, and all three have
a reason to be fixed together.

**(a) Attribution under the stats panel.** The default `BottomBar` placement covers the map
provider's logo, in the preview and in the exported file. Terms require it visible. Four of the
five placements already clear it; the default does not. Fixing it changes the look of every
exported image, which is why it waited for a deliberate moment — this is that moment. Contract §0.3.

**(b) Aggregate privacy trim.** `prepareComparisonRoutes` bakes the trim in, where single-ride
applies it live from the chip. So the same control may not do the same thing on the two screens.
Contract §0.4.

**(c) Ride-detail marker snippets.** Tapping a point still reports `Speed: 3.3 km/h` on a walk,
where the rest of the screen now reports pace. Same surface, different code path, missed when pace
landed.

---

## §4 Screenshot tests (L)

**Evidence.** `DESIGN_SYSTEM_1.8.md` §12 names this as *"the single largest remaining gap"*, and
1.8.x proved it repeatedly: the flat pace line, the square ripple, and the misaligned wordmark were
all found by a person looking at a screenshot, and none of them would have failed a build.

**What.** A screenshot harness (Roborazzi or Paparazzi — both run on the JVM, no device) covering
the ride HUD, ride detail, both export previews, and the design catalog, in light and dark.

**Why it is worth the size.** Every visual claim in the design system document currently rests on
someone remembering to look. The three defects above each survived multiple review passes and
multiple builds.

**Open question.** Golden-image tests are famously noisy across renderer versions. Scope may need
to be "the catalog and two screens" rather than everything, to keep the failure rate honest.

---

## §5 The spacing lint rule (S)

**Evidence.** §12 again: a rule was scoped in phase 2 and never written. The entire export preview
was then built on raw `dp` and nothing failed — it was caught by someone asking whether the screen
used the design system, not by the build.

Motion has `MotionTokenAdoptionTest` and it works: it has already forced two deliberate exemptions
rather than silent drift. Spacing has nothing.

**What.** The same pattern — a source-reading test with an explicit, documented exemption list.
Component-internal metrics stay exempt; screen margins and inter-element gaps do not.

---

## §6 Pay down the runtime-verification debt (M)

**Evidence.** `FEATURE_ROBUSTNESS_MATRIX.md` carries these at **P0**, all "code-complete, runtime
verification pending":

| Scenario | Status |
|---|---|
| Process death mid-ride (OS-killed) | ⚠️ P0 |
| GPS signal gap mid-ride | ⚠️ P0 |
| Airplane mode / GPS off mid-ride | ⚠️ P0 |
| No accelerometer — auto-pause degradation | ⚠️ P0 |
| SOS notification and SMS failure paths | ⚠️ P0 ×4 |
| Storage nearly full | ⚠️ P1 |

**What.** A written device-test matrix, executed once, with results recorded against each row. Some
of these are automatable (process death via `adb shell am kill`); the SOS ones are not.

**Why now.** These have been P0 through several releases. Either they get verified or the matrix
should stop calling them P0, because a permanent P0 is not a priority, it is a decoration.

---

## §7 Smaller refinements (S each, pick opportunistically)

- **Splits for cycling.** Per-km splits are meaningful on a bike; they are currently walk/run only
  because pace is. Cycling would want per-km *speed* bars, same chart, different unit.
- **Rotation on Home.** The compass is now conditional. If rotation gestures are not wanted on
  Home at all, turning them off makes the compass unreachable and it can be deleted outright — a
  cleaner end state than a conditional control. One decision, two files.
- **Orphaned-label audit.** `elevationGain` and `elevation` are not alone; a first pass found
  around a dozen labels with no `strings.` reference. Some are false positives reached through
  helper functions, but not all. Contract §0.1.

---

## Explicitly not in 1.8.2

Recorded so they are decisions rather than oversights.

- **Material 3 Expressive.** `material3:1.5.0` is still at `alpha26`. The condition has not fired
  and the dependency-inversion layer means waiting costs nothing.
- **`Color.kt` legacy alias migration.** Roughly 40 files. Large diff, zero user-visible change,
  and a real regression surface. Worth doing when something else is already touching those files.
- **Palette regeneration with Material Color Utilities.** Settled in 1.8.0 §4.3 — the tone axis is
  measured exact and the values are not moving.
- **Segments, personal records, leaderboards.** `RideStats` and `WeeklyRecapSelector` are a
  plausible spine for these, but each is a feature release on its own, not a point release.

---

## Recommended cut

If 1.8.2 should be small: **§1, §3, §5** — one finished feature, three closed defects, one guard
that stops the class of drift that produced them.

If there is room for one large item: add **§4**, because it is what stops the next three defects
reaching a device before a person does.

**§2 should happen regardless of the cut**, because it is an analysis rather than a build, and
because the longer the ETA data goes unread the weaker the argument for having shipped it dark.
