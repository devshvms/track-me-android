# TrackMe v1.5.10 Release Notes

**Release theme:** Restored map reliability

Version name is `1.5.10`; CI assigns the Play version code.

## Fixed

- Restored Google Maps in signed release builds. The release build now receives the Maps API key from the secured CI environment, exactly as configured in GitHub Actions.
- Release builds now fail before signing when no Maps API key is supplied, preventing a build with an unusable map from reaching testers.

## Verification

- Confirm the Home map loads tiles and shows the current-location indicator after updating from Alpha.
- Start and stop a short ride, then reopen it from History to confirm the route map loads in Ride Detail.
- Confirm map snapshots render when sharing a ride image.
