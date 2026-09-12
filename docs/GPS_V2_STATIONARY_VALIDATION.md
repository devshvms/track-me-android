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
