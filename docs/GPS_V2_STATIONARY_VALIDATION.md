# GPS V2 stationary repair — 1.8.9 candidate

The repair requires travel evidence before resuming: plausible pedestrian steps, coherent GPS
progress, or at least three speed samples spanning four seconds whose uncertainty clears the
persona's speed floor. An isolated coordinate jump cannot establish a coherent path. Handling the
phone alone cannot establish travel.

Fresh motion below 0.18 m/s² supports stationary dwell (WALK/AUTO six seconds, other personas five;
power-restricted modes at least ten). Short handling bursts retain recent quiet evidence; after a
confirmed stop, handling alone cannot resume. Absent/stale motion does not establish a new stop.
Actual entry depends on sample delivery and the rolling window, so physical latency must be measured.

Unconfirmed duration is held, bounded at fifteen seconds, and either committed when movement is
confirmed or discarded on stationary/unknown/gap boundaries. Stationary speed is zero. Distance
confirmation over a window cannot be divided by the last callback interval to create peak speed.
The debug auto-pause override counts observed non-manual time while retaining GPS noise rejection.
Canonical distance and raw coordinates remain independent of display geometry.

## Long-stop follow-up (12 September)

The user reported 10–12 actual walking minutes followed by a long stop; the app showed 24:31 active
in 1:57:33 total with 3,826 raw points and many overlapping pause markers. Installed commit is
uncertain (last Android Studio build). Treat it as an unresolved physical gate, not a verified run
of a particular commit. Daily OS resource totals are not per-ride battery measurements.

Two new synthetic scenarios failed against Android `003e4fe`: a two-hour correlated 18 m cloud
with optimistic accuracy/reported speed, and sustained phantom speed without coordinate travel.
The follow-up adds a fixed anchor after stationary confirmation. GPS-only resume requires coherent
outward progress beyond max(20 m, combined anchor/current accuracy); reported speed alone cannot
unlock that stop. Step-supported movement bypasses this guard. A confirmed stop survives stale
motion samples while GPS remains continuous; manual pause and a >15 s GPS gap still clear anchors.

GPS-only departure may be delayed, particularly for slow movement or poor accuracy. Its candidate
is capped at 60 seconds, resets on loss of coherence/inward movement, and backfills only its
observed departure distance/time after confirmation. Already-counted time (including debug-off)
cannot be counted again. A genuine GPS-only movement entirely inside the uncertainty region can
remain unconfirmed. This is an explicit conservative trade-off, not proof of arbitrary precision.

Repeat the physical check with a 110-minute stop, 10–20 m hand-held GPS drift, then both a small
step-supported walk and a GPS-only departure. Confirm one stable pause marker and frozen active
time, distance, and route during the stop. Check active-duration accounting after GPS-only resume.
Raw GPS points are retained for GPX/sync; there is no destructive compaction or retroactive rewrite
of old rides. No sampling-rate, wake-lock or permission change is included.

Android includes `tools/gps-trace-lab`: loopback-only GPX import/geometry inspection and an optional
actual-estimator sensitivity replay with explicitly assumed missing sensors. Original uploads and
replay output stay in ignored `local-traces/`; no precise route is committed or published.

## Physical check

1. Install this repair branch, start WALK with default settings, then sit for three minutes holding
   and occasionally using the phone. After confirmation, Auto-Paused should stay visible, active
   duration and distance should stay steady, and the trail should stop growing. Total elapsed time
   continues. The current-location marker/accuracy circle may still move independently.
2. Walk slowly, then normally. Auto-Paused should clear promptly and distance/time should resume
   without charging the seated interval. Repeat with step permission denied for GPS-only evidence.
3. Repeat stop/resume with RUN and a vehicle, then battery saver/screen off. Do not operate the
   phone while driving; inspect the result after stopping.
4. Manually pause, move, and resume: paused movement stays excluded. A real GPS outage must retain
   its gap without charging the recovery chord. Auto-pause remains one circle per interval.
5. With Debug Settings unlocked and auto-pause off, observed duration continues while stopped,
   the auto-pause badge is absent, and GPS drift still does not count as distance. Lock debug mode
   again to restore defaults; unrelated settings should survive.

Automated synthetic checks do not establish physical GPS accuracy, battery drain, or store readiness.
