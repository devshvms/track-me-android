# TrackMe v1.5.11 Release Notes

**Release theme:** TrackMe cyan, with optional Material You color

Version name is `1.5.11`; CI assigns the Play version code.

## New

- Refreshed TrackMe with its production cyan, navy, and semantic safety palette across light and dark themes.
- Added an opt-in **Dynamic color** setting on Android 12 and newer for people who want TrackMe to match their wallpaper. The TrackMe cyan theme remains the default.

## Safety and readability

- Emergency red and warning amber remain fixed when Dynamic color is enabled, including their readable foreground and container colors.
- Expanded theme-role and contrast checks prevent unreadable foreground/background pairings and accidental fallback colors.

## Tester checklist

- Verify Home, active ride, History, Ride Detail, Settings, and Emergency SOS in light and dark modes.
- On Android 12 or newer, enable **Settings → Dynamic color**, confirm the surrounding interface follows the wallpaper, and confirm SOS remains red and warnings remain amber.
- Disable Dynamic color and confirm the production cyan palette returns immediately and remains selected after relaunch.
