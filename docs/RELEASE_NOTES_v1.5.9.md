# TrackMe v1.5.9 Release Notes

**Release theme:** Cleaner ride capture and truthful ride summaries

## User-visible changes

- Near-empty rides (under 10 metres and under two minutes) now ask whether to discard or save them, so accidental starts do not clutter History.
- Generated ride titles now respect the selected activity persona. For example, BikeDrive rides no longer appear as walks or runs.
- Rides without meaningful recorded distance show the clear "not enough GPS data" state instead of an empty chart.
- Ride Detail now uses the correct localized "GPS Points" label in every supported language.
- Cancelling Google sign-in is silent, and the battery-optimization system dialog is no longer obscured by a competing toast.

## Verification required before wider rollout

- On a device, stop a zero-distance ride and test both Discard and Save anyway.
- Record a BikeDrive ride and confirm its generated title appears consistently in History and Ride Detail.
- Confirm the battery-optimization prompt, Google sign-in cancellation, chart guard, and localized GPS Points label on the signed build.
- Complete the existing TASK-005 signed-device matrix before any `master` promotion or Play upload.
