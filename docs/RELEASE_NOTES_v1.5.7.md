# TrackMe v1.5.7 Release Notes

**Release theme:** Day-1 quality — tracking robustness, SOS reliability, accessibility, and privacy alignment

## User-visible changes

- Active rides now survive process death: relaunching the app reattaches to the live ride with route and stats intact, and recovered rides are announced instead of silently saved.
- Live GPS status during a ride: a visible warning distinguishes a temporary signal gap from location services being turned off, with a shortcut to Location Settings.
- SOS now confirms outcomes: a dedicated high-priority notification reports how many emergency contacts accepted the alert and surfaces failed sends. If SMS permission is revoked after setup, Home shows a warning with a direct recovery path.
- Rides pause safely when device storage runs low, with an actionable notification instead of silent data loss.
- Full TalkBack pass: activity picker, ride charts, history cards, settings switches, emergency setup, and account screens now have proper screen-reader semantics.
- Offline banners on Settings and Account screens explain that changes stay on-device until reconnected.
- First-run hint explains the Start Ride hold-and-drag gesture.
- Export failures now show clear, localized guidance (all 7 languages) instead of raw errors; incomplete downloaded archives are detected and rejected.

## Privacy

- Analytics events no longer include precise GPS coordinates.
- Account deletion now purges all cloud data: rides, GPS points, emergency configuration, emergency logs, and feedback.

## Reliability and release metadata

- Sync results are now truthful: partial upload failures report an error and background sync retries instead of falsely reporting success.
- Live-share sessions force-refresh authentication tokens and explain session-expiry errors.
- Resolved all Android lint errors; release CI now gates Play upload on release lint + unit tests.
- Version name is `1.5.7`; local fallback version code is `18`. CI continues to use `GITHUB_RUN_NUMBER` for release version codes.
