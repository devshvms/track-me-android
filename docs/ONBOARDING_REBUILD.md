# Onboarding Rebuild — Android

*Platform slice. Cross-platform source of truth: `.ai/context/ONBOARDING_REBUILD_SPEC.md` in the workspace root, with cards in `.ai/tasks/ONBOARDING_REBUILD_TASKS.md` and ownership in `.ai/tasks/active_tasks.md`. Claim work there, not here. This file is the Android detail, kept in-repo so it travels with the code.*

Baseline: `aa5aa05`, level with `origin/master`.

---

## The decision

Four Remotion clips were produced for the walkthrough (`assets/onboarding_videos/` in the workspace). **None can ship as rendered** — nine measured blockers, four of them properties of the files rather than the animation. Rather than fix all four:

| Page | `OnboardingScreen.kt` | Today | Becomes |
|---|---|---|---|
| 0 Welcome | `PAGE_WELCOME` | `WelcomeMark` | Rebuilt clip — persona hook |
| 1 Ride | `PAGE_RIDE` | `RideGestureArt` | **4-step guided demo** |
| 2 History | `PAGE_HISTORY` | `HistoryArt` | **4-step guided demo** |
| 3 Together | `PAGE_TOGETHER` | `TogetherArt` | Rebuilt clip — group creation → proximity alert |

Video where the user cannot act; interaction everywhere else.

## Why the current art is not simply replaced

`OnboardingIllustrations.kt` says it in its own header: the art *"reads its colours from the theme, so the same drawings work on the light surface as on navy"*, and is *"drawn rather than shipped as bitmaps so it themes with the app and adds nothing to the APK."* It also takes localized strings as parameters (`RideGestureArt(selectedLabel = strings.personaCycling)`, `HistoryArt(primaryStat = …)`).

**Do not delete it.** On pages 0 and 3 it becomes the reduced-motion path and the decode-failure fallback for `OnboardingClip`. That is the cheapest correct answer to accessibility here, and it is already written, themed and localized.

---

## Pages 1 and 2: reuse the controls, not the screens

`HomeScreen` takes a `HomeViewModel` from a factory pulling `trackingManager`, `emergencyManager`, `authManager`, `liveShareManager` and `preferencesManager` off `TrackMeApp`. `HistoryScreen` and `RideDetailScreen(rideId, …)` are ViewModel- and DB-bound. Embedding any of them would boot tracking, auth and live-share before the user has granted anything.

Every control the demos need is already state-hoisted — plain parameters and callbacks, no ViewModel:

| Step | Component | Location |
|---|---|---|
| p1 1/4 pick persona | `RadialStartRideButton(onStartRide, onAbortRideStart, modifier)` | `ui/home/components/RadialStartRideButton.kt:132` |
| p1 2/4 start live share | `InteractiveShareLocationButton(liveShareState, isAuthenticated, onStartShare, onStopShare, onSendShare, onCopyShare, modifier)` | `ui/home/components/InteractiveShareLocationButton.kt:63` |
| p1 3/4 copy link | same component, `onCopyShare` | ↑ |
| p1 4/4 slide to stop | `UnifiedPauseStopPill(isPaused, strings, onPauseToggle, onStopRide, modifier)` — `internal`, same module | `ui/home/components/ActiveRideHudPanel.kt:383` |
| p1 HUD readouts | `ActiveRideHudPanel(…)` — every readout is a `String` parameter | `ui/home/components/ActiveRideHudPanel.kt:81` |
| p2 1/4 open demo ride | `RideHistoryCard(rideWithPoints, onClick, …)` | `ui/history/HistoryScreen.kt:447` |
| p2 2/4 scrub | `CombinedMetricLineChart(points, …, scrubIndex, imperial, modifier)` | `ui/history/RideDetailScreen.kt:997` |
| p2 map surface | `RoutePreviewThumbnail(points, modifier)` | `ui/history/HistoryScreen.kt:623` |
| p2 3–4/4 share + save | `ExportPreviewDialog(…)` | `ui/history/ExportPreviewDialog.kt:85` |

Two things make this fit better than expected:

- **`CombinedMetricLineChart` already accepts `scrubIndex`.** The slider → chart → map interaction is what `RideDetailScreen` does at lines 346 (state), 563 (chart), 590 (slider). The pieces are separable even though the block is not.
- **`ExportPreviewDialog` takes its preview as a composable slot** — `preview: @Composable (Modifier, ExportPreviewSettings) -> Unit` at line 103. Hand it `RoutePreviewThumbnail` and the ratio chips, toggle rows and Share/Save buttons all come for free with no map.

### Never use `GoogleMap` in onboarding

`RideDetailScreen` and `HomeScreen` use `maps-compose`. In the walkthrough that means network tiles on first launch, before location permission exists, with a grey-tile flash. Use `RoutePreviewThumbnail` — pure `Canvas`, no tiles, themed — and draw the scrub marker on top.

### Demo fixture

`RideWithPoints` is a plain data class wrapping `RideEntity` + `List<GPSPointEntity>`. Room's annotations do not prevent direct instantiation, so the fixture is built entirely in memory with **no database access**. The same fixture feeds page 2, the page-1 HUD readouts, and the seeded sample ride (TASK-188).

---

## Constraints that will bite

- **The demo must not reach the real system.** No callback may touch `TrackingService` or `liveShareManager`. Assert this in a test — it is the kind of thing that regresses silently.
- **Copy-link is a no-op with a toast** (spec decision D1). The user is not authenticated and there is no backend call, so a real `onCopyShare` writes a dead URL to the actual clipboard. An "example" URL is no better — people paste before reading.
- **4 s inactivity auto-advance per step** (D2), plus an explicit per-step skip. Eight forced interactions across two pages is a gate, not a tour.
- **Every new string goes into all seven catalogs** in `AppStrings.kt`. ⚠️ This is additive alongside TASK-150 S2 and TASK-151 B2 — sequence with them.
- **`ActiveRideHudPanel.kt`, `RideDetailScreen.kt` and `HistoryScreen.kt` are read-only for this work** — call them, do not edit them. TASK-151 B1 and TASK-150 S2 have claims there.
- **HUD stats come from the fixture, not zeros.** The current clip shows `0.00 km / 00:00:01 / 0.0 km/h`, which is the least persuasive state a ride can be in.

---

## Pages 0 and 3: the clip component

New `ui/onboarding/OnboardingClip.kt`:

```kotlin
OnboardingClip(
    dark  = R.raw.onboarding_welcome_dark,
    light = R.raw.onboarding_welcome_light,
    fallback = { WelcomeMark(Modifier.fillMaxSize()) },
    modifier = Modifier.fillMaxWidth().aspectRatio(2f).clearAndSetSemantics { },
)
```

- Assets go in **`res/raw`**, not `assets/` — they become `R.raw.*`, stay visible to the already-enabled `shrinkResources`, and need no `AssetFileDescriptor` handling.
- Add `androidx.media3:media3-exoplayer` (≈1.5–2.5 MB post-R8). The zero-dependency `MediaPlayer` + `SurfaceView` route flashes black on first surface attach — which is the exact defect the Welcome clip is already being fixed for.
- Theme picks the file via `isSystemInDarkTheme()`.
- Render `fallback` when `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` or the player errors.
- Release on `onDispose`; pause when the pager settles on another page so three decoders are not running off-screen.
- **Slot changes from `.height(180.dp)` to `.aspectRatio(2f)`.** The slot is 2.02:1 and the clips are 1.33:1 — today that would mean 34% dead bar or 34% cropped. Moving both ends together stops it recurring.
- `clearAndSetSemantics { }` stays. Meaning is carried by the localized copy beneath, which is why stripping the burned-in text from the clips costs nothing in accessibility terms.

---

## Tasks

| ID | Title | Depends on |
|---|---|---|
| TASK-183 | Shared demo-ride fixture (+ iOS control extraction) | — |
| TASK-184 | Page 1 guided ride demo | 183 |
| TASK-185 | Page 2 guided history demo | 183 |
| TASK-186 | Re-render clips 0 and 3 (`remotion-videos`) | — |
| TASK-187 | `OnboardingClip` player, fallback, slot aspect | 186 |
| TASK-188 | Seed a sample ride into History on first run | 183 |
| TASK-189 | Per-page dwell + finish on an action | 184/185 |

Full acceptance criteria and verification steps are in `.ai/tasks/ONBOARDING_REBUILD_TASKS.md`.

Before merging: clean build, full `testReleaseUnitTest`, `lintRelease` and `lintVitalRelease` locally — and again before dispatching a release. Record the APK size delta in the task's discussion file.
