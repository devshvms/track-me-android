# TrackMe local trace lab

Run from this app checkout:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tools/gps-trace-lab/server.py
```

Open `http://127.0.0.1:8765` **on this Mac**, choose an exported GPX, choose the persona, and optionally
mark the minute after which you remained stationary. AirDrop/copy the file from the phone first.
The page shows raw relative geometry and saves the original plus `report.json` under
`local-traces/<random-id>/` in this checkout. Tell Codex that the import is ready. No account,
cloud, external map, analytics, public hosting, or phone/LAN endpoint is used. The inbox is ignored
by Git; do not force-add traces or attach them to a public PR. Delete an individual import folder
only when the user asks. Stop the server with Ctrl-C. `--port 0` selects an available loopback port.

Raw chord distance is NOT activity distance. The annotated tail is NOT an automatic stationary
classification. No old ride is rewritten. The GPX exporter preserves raw position/time/elevation
but omits accuracy, motion, steps, original pause flags, power mode and original speed evidence.

## Actual-estimator sensitivity replay

`TrackingV2LocalTraceReplayTest` is intentionally skipped unless `TRACKME_REPLAY_REPORT` is set.
It runs the production Kotlin session against locally imported points. Accuracy must be explicitly
assumed; motion is absent unless you explicitly assume quiet motion for the annotated tail.
It saves a uniquely named `replay-*.json` beside the original report, with the hypotheses and a
per-fix state/distance/active-time timeline. It never presents hypotheses as observed device data.

```sh
TRACKME_REPLAY_REPORT=/absolute/path/to/local-traces/id/report.json \
TRACKME_REPLAY_ACCURACY_METERS=10 \
TRACKME_REPLAY_STATIONARY_ENERGY=0.04 \
./gradlew :app:testReleaseUnitTest --tests '*TrackingV2LocalTraceReplayTest' \
  --rerun-tasks --no-configuration-cache --no-daemon --max-workers=1
```

Use the normal local release-test configuration. Run one platform at a time. Repeat with motion
omitted and different explicit accuracy hypotheses; do not treat any run as exact sensor replay.
Before making a fixture public, replace real coordinates with synthetic test geometry and remove
identifying timestamps. Two-hour regression fixtures already use synthetic coordinates.

## Verification

`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/gps-trace-lab` covers parsing,
timing, segment separation, entity rejection, annotations, local persistence and request-origin/host
checks. The server serves only three allowlisted public assets, never the private inbox.

The optional browser WebMCP read-back returns the already imported summary without coordinates;
unsupported browsers work normally. Its browser integration is not a requirement for GPX import.
