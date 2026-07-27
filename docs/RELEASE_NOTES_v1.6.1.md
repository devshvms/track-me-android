# TrackMe v1.6.1 Release Notes

**Release theme:** safer sharing, clearer ride history, and a more trustworthy replay preview

Version name is `1.6.1`; CI assigns the Play version code from the workflow run number.

## Highlights

- Refined ride-detail sharing and export preview controls for single rides and aggregate comparisons.
- Added clearer route, distance, duration, and privacy-state presentation across history and export surfaces.
- Improved persona replay controls, localized accessibility labels, and long-text handling in the primary ride action controls.

## Safety and privacy

- Shared exports continue to trim route endpoints before rendering and keep precise location data out of telemetry.
- Replay and image exports use the canonical TrackMe lockup and privacy-aware map rendering contract.

## Tester checklist

- Verify the app reports version **1.6.1** in Settings/About and that the Play upload uses the expected version name.
- On the internal and alpha tracks, exercise Home, active ride, History, Ride Detail, Settings, SOS, and sign-out recovery.
- Create a single-ride export and an aggregate comparison; verify preview controls, map/route alignment, privacy trim, markers, stats, watermark, Share, and Save.
- Test the primary ride controls with German, Spanish, and French device languages at increased font scale; confirm labels wrap without clipping.
