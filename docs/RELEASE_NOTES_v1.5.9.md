# TrackMe v1.5.9 Release Notes

**Release theme:** Smoother maps, cleaner ride capture, and truthful ride summaries

Version name is `1.5.9`; local fallback version code is `20`. CI uses `GITHUB_RUN_NUMBER` for the Play release version code.

## User-visible changes

### Maps
- Fixed the visible map flicker where the Google Map briefly rendered a whole-world view at (0,0) before snapping to the correct position:
  - Returning to the Home tab from History/Settings now restores the previous camera position (bottom-nav state is saved/restored, and the camera is seeded from the last persisted location — country-level fallback before the first fix).
  - Ride Detail (and its export preview) now seeds the camera from the route bounds before the map composes and keeps the map hidden behind a neutral placeholder until tiles are loaded.
- Camera position on both screens survives rotation.

### Ride capture and summaries
- Near-empty rides (under 10 metres and under two minutes) now ask whether to discard or save them, so accidental starts do not clutter History.
- Generated ride titles now respect the selected activity persona. For example, BikeDrive rides no longer appear as walks or runs.
- Rides without meaningful recorded distance show the clear "not enough GPS data" state instead of an empty chart.
- Ride Detail now uses the correct localized "GPS Points" label in every supported language.

### Sign-in and system dialogs
- Cancelling Google sign-in is silent, and a scoping bug in the sign-in error handler was fixed so sign-in failures surface the intended message.
- The battery-optimization system dialog is no longer obscured by a competing toast.

## Carried over from v1.5.8 (not yet seen by alpha testers)

- Live Share sends longitude using the API's canonical `lng` field, keeping Android sessions compatible with the web service so shared locations keep updating for viewers.
- Removed the unused background-location permission; recording continues through the visible location foreground service.

## Highlights since v1.5.6 (last alpha build), from v1.5.7

- Tracking robustness: active rides survive process death and are reattached on relaunch; GPS-loss warnings distinguish signal gaps from disabled location services; rides pause safely on low storage with an actionable notification.
- SOS reliability: a high-priority notification reports how many emergency contacts accepted the alert and surfaces failed sends; revoked SMS permission triggers a Home warning with a recovery path.
- Accessibility: full TalkBack pass across activity picker, charts, history, settings, emergency setup, and account screens; offline banners; localized, actionable error messages in all 7 languages.
- Privacy: analytics events no longer include precise GPS coordinates; account deletion purges all cloud data (rides, GPS points, emergency configuration and logs, feedback).

## Developer notes

- Debug StrictMode is now opt-in: pass `-PstrictMode` (drives `BuildConfig.STRICT_MODE`, default off) instead of always-on `detectAll` in debug builds.

## Verification required before wider rollout

- On a device: no world-map flash on Home tab switches or when opening Ride Detail; camera lands directly on the route/location; rotation preserves the camera on both screens.
- Stop a zero-distance ride and test both Discard and Save anyway.
- Record a BikeDrive ride and confirm its generated title appears consistently in History and Ride Detail.
- Confirm the battery-optimization prompt, Google sign-in cancellation, chart guard, and localized GPS Points label on the signed build.
- Complete the existing TASK-005 signed-device matrix before any `master` promotion.
