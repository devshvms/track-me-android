# TrackMe v1.5.6 Release Notes

**Release theme:** Clear authentication state for live location sharing

## User-visible changes

- Live location sharing now clearly explains that sign-in is required before a session can be created.
- The live-share control is disabled for signed-out users and shows a blocked-share icon instead of opening an action drawer that cannot succeed.
- Signed-in users retain the existing start, copy, send, and stop actions.
- Added localized sign-in guidance for all supported app languages.

## Reliability and release metadata

- Added a defensive authentication check before starting a live-share session.
- Version name is `1.5.6`; local fallback version code is `17`. CI continues to use `GITHUB_RUN_NUMBER` for release version codes.
