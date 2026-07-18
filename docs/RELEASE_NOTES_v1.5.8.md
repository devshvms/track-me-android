# TrackMe v1.5.8 Release Notes

**Release theme:** Live Share interoperability and permission hygiene

## User-visible changes

- Live Share now sends longitude using the API's canonical `lng` field. This keeps new Android sessions compatible with the web service and ensures shared locations continue updating for viewers.
- Removed the unused background-location permission. TrackMe continues recording through its visible location foreground service, without requesting an unnecessary additional location permission.

## Reliability and release metadata

- Version name is `1.5.8`; local fallback version code is `19`.
- CI continues to use `GITHUB_RUN_NUMBER` for the Play release version code and runs release lint, unit tests, and the release emulator smoke test before an upload.

## Verification required before wider rollout

- Install the exact signed minified artifact and verify app launch, Google sign-in, live-share start, viewer updates, and stop.
- Complete the existing TASK-005 device matrix for process death, GPS recovery, SOS permission recovery, offline behavior, and TalkBack/font-scale checks.
