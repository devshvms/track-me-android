# TrackMe v1.6.1 Release Notes

**Release theme:** SOS emergency resilience, enhanced accessibility & i18n, refined replay exports, and robust celebration gating.

Version name is `1.6.1`; CI assigns the Play version code from the workflow run number.

## Highlights

- **SOS Emergency Resilience (TASK-120 & 116)**: Persists emergency start timestamps across process deaths to maintain bounded broadcast windows; user STOP calls now commit synchronously. Suppresses post-ride celebration reveal dialogs when an emergency is triggered.
- **SOS Accessibility & Localization (TASK-121)**: Full TalkBack and screen-reader accessibility for SOS controls using `Role.Button` semantics, state descriptions, and localized strings across 6 languages.
- **Replay Export Preview Settings (TASK-115)**: Replay video exports now strictly honor aspect ratio, map style, and privacy trim settings chosen in the export preview dialog.
- **Weekly Recap CalmMoment Gate (TASK-119)**: Weekly recaps surface only when the app is in a calm, idle state—never interrupting live/paused tracking, active SOS flows, or GPS/storage warnings.
- **Map Layer Drawer Localization (TASK-112)**: Localized map-layer drawer labels and proper radio button accessibility states.

## Safety and Privacy

- Shared exports continue to trim route endpoints before rendering and keep precise location data out of telemetry.
- Replay and image exports use the canonical TrackMe lockup and privacy-aware map rendering contract.

## Tester Checklist

- Verify the app reports version **1.6.1** in Settings/About and that the Play upload uses the expected version name.
- On the internal and alpha tracks, exercise Home, active ride, History, Ride Detail, Settings, SOS, and sign-out recovery.
- Create a single-ride export and an aggregate comparison; verify preview controls, map/route alignment, privacy trim, markers, stats, watermark, Share, and Save.
- Test the primary ride controls with German, Spanish, and French device languages at increased font scale; confirm labels wrap without clipping.
