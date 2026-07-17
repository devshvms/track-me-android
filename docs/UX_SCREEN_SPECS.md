# Per-Screen UX Specs (TASK-015 W3)

*Owner: Claude (Product/Design Agent). Written 2026-07-17 per `.ai/context/ANDROID_QUALITY_GUIDELINES.md` W3. Six screens (Home, History, RideDetail, Settings, AccountManagement, EmergencySetup) checked against items 1–7 of the W3 punch list. Findings are code-audited (file:line cited) where the answer is determinable from source; items that require a running app (dark-theme rendering, font-scale rendering, TalkBack walkthrough) are marked **[DEVICE CHECK]** with the exact spec Codex/tester should verify against — these cannot be closed from source review alone.*

Legend: ✅ Meets spec · ⚠️ Partial / needs fix · ❌ Missing · 🔍 [DEVICE CHECK] required

---

## 1. Home (`ui/home/HomeScreen.kt`)

This is also the live-HUD screen during an active ride (`ActiveRideHudPanel`).

| # | Item | Status | Notes / Fix Spec |
|---|---|---|---|
| 1 | Notification channels | ✅ | Foreground tracking notification correctly uses `tracking_channel` (low importance, non-intrusive). |
| 2 | Dark theme | 🔍 | Map tile overlay, HUD status pills (amber/orange with black text, red/green with white text — raw brand colors, not theme-contrast-managed, per audit) need explicit dark-mode visual check. |
| 3 | Accessibility | ❌ | **`RadialStartRideButton` (the primary Start Ride control) is a custom `Box` with raw `pointerInput`/gesture handling and only ONE `contentDescription` covering the idle state.** A TalkBack user can only ever trigger the default `RidePersona.AUTO` — the other 4 activity choices (Walk/Run/Cycling/Bike/Car) are unreachable without sighted drag gestures. **Spec: add a TalkBack-accessible alternate path** — e.g., long-press or double-tap opens an accessible list/menu of the 5 personas with individual `contentDescription`s and a `stateDescription` announcing the currently-hovered persona during drag. This is the single highest-priority accessibility fix in the app — it gates the core action screen-reader users need most. |
| 3b | Touch targets | ✅ | HUD buttons (SOS, pause/stop, share) explicitly sized 52dp — compliant. `MapControlCircleButton` also 52dp. |
| 4 | State preservation | ❌ | **Process death mid-ride does not resume the live HUD.** `OrphanedRideRecoveryManager` silently auto-finalizes the interrupted ride on next cold start and the user lands on idle Home with no explanation. **Spec: on cold start, if `OrphanedRideRecoveryManager` auto-finalizes a ride, show a one-time dismissible banner/snackbar**: "Your last ride was saved after the app closed unexpectedly — [View ride]." This turns a silent, confusing event into a transparent one. Rotation/config-change preservation during a live ride is correctly handled via the `Application`-scoped `TrackingManager` — no fix needed there. |
| 5 | Empty/edge states | ⚠️ | No explicit "no active ride" empty-state message — the idle map + `RadialStartRideButton` functions as an implicit CTA, which is acceptable for a map-first app, but there's no first-run coach mark for a brand-new user who's never started a ride. **Spec: add a one-time first-run tooltip/hint** near the start button ("Tap and hold, then drag to choose an activity") — the 5-persona drag gesture is not self-evidently discoverable otherwise. |
| 6 | Permission choreography | ✅ mostly | Foreground location + notifications requested on load with a well-designed blocking dialog on denial (rationale text + re-request + Settings deep link) — this is the reference-quality flow in the app. **Gap**: `ACCESS_BACKGROUND_LOCATION` is declared in the manifest but never requested at runtime anywhere. **Spec: confirm with Antigravity whether background location is actually required** given the foreground-service-with-location-type exemption already in use; if not required, remove the unused manifest permission (cleaner Play Console permissions disclosure). If it IS required for some flow, add the proper in-context request per Google's separate-background-permission guidance. |
| 6b | Battery optimization prompt | ✅ | Requested in-context at ride start with rationale Toast — correct choreography. |
| 7 | Live GPS-loss feedback | ❌ | Not a W3 checklist line item verbatim, but directly a Home-screen UX gap surfaced by the W2 matrix: `timeSinceLastGps` is computed and passed to `ActiveRideHudPanel` but never rendered. **Spec: add a subtle HUD indicator** (e.g., a pulsing "Searching for GPS…" label replacing the speed readout) when `timeSinceLastGps` exceeds ~10s, using the already-existing `TrackingState.GPS_LOST` enum value (currently unused) as the state to drive it. |
| 7c | Locale/polish | 🔍 | HUD text (speed/distance labels, persona names) at 45–75 char line length across 7 locales — verify Hindi/Japanese don't wrap awkwardly in the compact HUD panel. Haptic on start/stop — not found in audit; **spec: add haptic feedback via `HapticFeedback` on ride start/stop/pause actions** if not already present (grep did not find `performHapticFeedback` calls in Home). |

## 2. History (`ui/history/HistoryScreen.kt`)

| # | Item | Status | Notes / Fix Spec |
|---|---|---|---|
| 1 | Notification channels | N/A | This screen doesn't trigger notifications. |
| 2 | Dark theme | 🔍 | Route-preview thumbnails and ride-card charts need a dark-mode contrast check. |
| 3 | Accessibility | ⚠️ | 5 `contentDescription`s present — reasonable for a list screen, but not itemized in this audit pass; **spec: verify each ride-list-item's route thumbnail and any icon-only affordance (delete, share) has a label**, and that ride cards announce ride name/date/distance as a single semantic unit for TalkBack (avoid reading fragmented sub-elements). |
| 4 | State preservation | ✅ | Standard list screen; no special preservation need beyond scroll position, which Compose's `LazyColumn` + `rememberLazyListState` handles by default if wired (verify wiring, not confirmed in audit). |
| 5 | Empty/edge states | ✅ | Explicit `Text(strings.noRidesRecorded)` empty state exists (`HistoryScreen.kt:217-225`). **Minor spec**: currently text-only — consider a light illustration/icon per Google's Empty_States guidance for a more "delightful" first-run feel, but this is P2 polish, not a gap. |
| 5b | Route thumbnail with <2 points | ✅ | Falls back to a plain "GPS" text label (`HistoryScreen.kt:482-488`) rather than a broken/blank thumbnail — correctly handled. |
| 6 | Permission choreography | N/A | No permissions requested from this screen. |
| 7 | GPX import error handling | ✅ | Malformed-file import shows a clear `UiEvent.ShowError` message; file-extension pre-validation with its own try/catch. Reference-quality failure path — no fix needed. |
| 7b | Locale/polish | 🔍 | Ride names/dates at long locale strings (German, Japanese) — verify no truncation in the list-item layout. |

## 3. Ride Detail (`ui/history/RideDetailScreen.kt`)

| # | Item | Status | Notes / Fix Spec |
|---|---|---|---|
| 1 | Notification channels | N/A | — |
| 2 | Dark theme | 🔍 | Chart (`CombinedMetricLineChart`, Canvas-drawn) colors, gap-markers (red dashed lines), and map overlay all need explicit dark-mode verification — Canvas-drawn content doesn't automatically inherit M3 theme tokens the way Composables do, so this is a real risk area, not a formality. |
| 3 | Accessibility | ❌ | **The chart (`CombinedMetricLineChart`, lines 883-1084) is drawn entirely on `Canvas` with zero semantic content** — speed/altitude curves and GPS-gap markers are completely invisible to TalkBack. The paired `Slider` driving `scrubIndex` (lines 487-497) has default Material3 a11y support for its own thumb position, but doesn't describe *what* is being scrubbed. **Spec: add a `Modifier.semantics` block on the chart container that exposes a text summary** (e.g., "Speed chart, 0 to 45 minutes, average 12 km/h, one GPS gap at 22 minutes") so a screen-reader user gets the same information sighted users get visually, even without per-point granularity. |
| 4 | State preservation | ⚠️ | Rotation should preserve chart scrub position per the W3 spec — not confirmed in this audit pass (would need `rememberSaveable` on `scrubIndex` or equivalent). **Spec: verify `scrubIndex` survives rotation; if it resets to 0, wrap in `rememberSaveable`.** |
| 5 | Empty/edge states | ⚠️ | Zero-point rides show "No GPS data available" (line 413-415) — good. **Gap**: exactly 1 point renders the map fine but the chart section (`if (points.size > 1)`, line 420) is simply omitted with no message. **Spec: add a "Not enough GPS data to chart" fallback text** in the chart's slot when `points.size == 1`, matching the tone of the existing empty-state message. |
| 6 | Permission choreography | N/A | — |
| 7 | Export failure handling | ⚠️ | GPX/image export try/catch surfaces raw `e.message` in a Toast (lines 585-589, 660-664) — functionally non-crashing but not a designed error state. **Spec: replace raw exception text with a friendly message** ("Couldn't export this ride — check available storage and try again") consistent with the GPX-import error pattern already used elsewhere in the app. |
| 7b | Locale/polish | 🔍 | Metric labels and units (km vs mi, locale decimal separators) at 2 font scales — verify no overlap in the compact stat row. |

## 4. Settings (`ui/settings/SettingsScreen.kt`)

| # | Item | Status | Notes / Fix Spec |
|---|---|---|---|
| 1 | Notification channels | N/A | — |
| 2 | Dark theme | 🔍 | Standard list/toggle screen — lower risk than chart/map screens, but still needs the pass. |
| 3 | Accessibility | ⚠️ | 6 `contentDescription`s present; not itemized here — **spec: verify every toggle/switch row has an accessible label distinct from adjacent rows** (a common TalkBack pitfall on settings screens is ambiguous "Switch" announcements). |
| 4 | State preservation | ✅ | Standard settings screen; no special concern. |
| 5 | Empty/edge states | ❌ | **No offline-state UI at all.** `rememberIsOffline()` exists but is only consumed on `HomeScreen`; Settings shows sync failures only as a generic `SyncResult.Error` text (line 190-196) after the fact, not a proactive "you're offline, sync will resume when connected" banner. **Spec: surface the existing offline signal here too** — a small persistent banner when offline, consistent with (or reusing) the "Offline Shield Active" pattern already built for Home's HUD. |
| 6 | Permission choreography | N/A | Permissions are requested from Home/EmergencySetup, not here. |
| 7 | Sync status trustworthiness | ❌ | Tied to the W2 sync-count bug: `SyncResult.Success` can be shown here even when some rides failed to upload, because `uploadRideInternal()` swallows its own exceptions. **This is a Codex/implementation fix (see robustness matrix), not a copy fix** — flagged here because it's this screen's UI that will display the wrong status to the user until it's fixed. |
| 7b | Locale/polish | 🔍 | Settings labels at longest-locale + largest font scale — verify no two-line wrap breaks alignment with toggles. |

## 5. Account Management (`ui/settings/AccountManagementScreen.kt`)

| # | Item | Status | Notes / Fix Spec |
|---|---|---|---|
| 1 | Notification channels | N/A | Export completion could optionally notify via `sync_channel` once background/async export exists — not currently applicable since export appears to be a foreground-initiated `DownloadManager` flow. |
| 2 | Dark theme | 🔍 | Account/export cards — lower visual-risk screen, still needs the pass. |
| 3 | Accessibility | ⚠️ | 6 `contentDescription`s present; **spec: verify the export/delete-account action buttons (higher-consequence actions) have unambiguous labels and, ideally, a confirmation step that's also TalkBack-friendly** (not just a visual dialog). |
| 4 | State preservation | ✅ | Standard screen; no special concern. |
| 5 | Empty/edge states | ❌ | Same offline-state gap as Settings — no proactive messaging when export/account actions are attempted offline beyond the generic download-failure Toast. **Spec: reuse the same offline-banner fix as Settings.** |
| 6 | Permission choreography | N/A | No new permissions requested here. |
| 7 | Export auth pattern | ✅ *(corrects a prior assumption)* | Confirmed via audit: export uses a short-lived server-issued `token` query parameter on the download URL, not an `Authorization` header on `DownloadManager.Request` — this is a valid, intentional pattern (headers aren't reliable across `DownloadManager` OEM implementations). The export *request* call does correctly force-refresh its Bearer token. **No fix needed**; flagging so this doesn't get "re-fixed" by a future agent working from a stale inbox note. |
| 7b | Disk-full / large-history export | ⚠️ | Not exercised; generic exception handling only. See robustness matrix P2 item. |

## 6. Emergency Setup (`ui/settings/EmergencySetupScreen.kt`)

| # | Item | Status | Notes / Fix Spec |
|---|---|---|---|
| 1 | Notification channels | ❌ | The dedicated `sos_channel` (`IMPORTANCE_HIGH`) exists but **nothing on this screen or its downstream worker ever posts to it.** This is the same P0 finding as the robustness matrix — a triggered SOS produces no notification confirming it was sent. **Spec: post to `sos_channel` when `EmergencyBroadcastWorker` completes (success or failure per-contact), so there's a durable, high-priority record the user (or a bystander who picks up their phone) can see.** |
| 2 | Dark theme | 🔍 | Contact list + permission step — standard risk. |
| 3 | Accessibility | ⚠️ | 2 `contentDescription`s only — the lowest count of any settings-adjacent screen for what is arguably the highest-stakes flow in the app (setting up how the user gets help in an emergency). **Spec: audit this screen specifically for icon-only buttons (add contact, remove contact, test SMS) and ensure each has a label** — given the stakes, this should not be treated as P2 polish. |
| 4 | State preservation | ✅ | Standard form screen; no special concern beyond not losing in-progress contact entry on rotation (verify `rememberSaveable` is used for form fields — not confirmed in this pass). |
| 5 | Empty/edge states | ✅ | Setup flow itself functions as the "empty state" (no contacts yet → setup prompt) — appropriate for this screen type. |
| 6 | Permission choreography | ❌ | SMS permission rationale shown before first ask (good), but **no `shouldShowRequestPermissionRationale` check and no Settings deep link on permanent denial** — the screen just keeps showing "Grant SMS Permission" indefinitely with no explanation that the system dialog won't reappear. **Spec: mirror the HomeScreen location-permission pattern exactly** — on permanent denial, show a dialog/banner explaining the permission must now be granted via system Settings, with a direct deep link (`ACTION_APPLICATION_DETAILS_SETTINGS`). This is a straightforward reuse of an existing, working pattern elsewhere in the app. |
| 6b | Post-setup revocation | ❌ | SOS button on Home goes silently inert when SMS permission is later revoked — **spec belongs to this screen too**: consider a proactive check when the user opens Settings/EmergencySetup that surfaces "SOS is currently disabled — SMS permission was removed" rather than only discovering it via a dead button on Home. |
| 7 | SMS failure feedback | ❌ | No user-facing indication anywhere (including this screen) of whether a past SOS broadcast succeeded or failed per-contact. **Spec: this screen is the natural home for a lightweight "last SOS attempt" status/history** (even just the timestamp + per-contact send result) since SOS itself has no notification surface today. |
| 7b | Locale/polish | 🔍 | Contact name/phone fields at largest font scale — verify no clipping in the list rows. |

---

## Cross-screen theme/font-scale verification checklist 🔍

The following require a running emulator or device — Codex or the user should walk this list once W1's smoke test exists to reuse as a regression baseline:

1. Toggle system dark theme; visit all 6 screens; confirm no white-on-white/black-on-black regions, especially: Home's HUD status pills (raw brand colors bypass theme contrast roles per audit), RideDetail's Canvas chart, History's route thumbnails.
2. Set system font scale to Large and then to the largest available (typically 200%); visit all 6 screens; confirm no text truncation/clipping, especially in HUD stat labels, Settings toggle rows, and EmergencySetup contact list rows.
3. Switch device language to Hindi and Japanese (2 of the 7 supported locales flagged as highest line-length risk in the guidelines); spot-check Home HUD and Settings for wrapping/overflow.
4. Run TalkBack through: Home → start a ride via the persona picker (currently broken per above) → Settings → EmergencySetup → add a contact → RideDetail → scrub the chart (currently unlabeled per above). This walkthrough will fail at two known points until the accessibility fixes above land — that's expected and is precisely what this checklist is for catching.

## Sign-off status

Per-screen specs above are the completed W3 deliverable (spec side). None of the ❌/⚠️ items are implemented yet — they are handed to Codex as the next W3 implementation slice, sequenced after W1 per the guidelines' ownership table. 🔍 items require Codex or the user to execute the device checklist above; they cannot be closed by code review alone.

*Companion document: `FEATURE_ROBUSTNESS_MATRIX.md` (W2). Cross-references: `.ai/context/ANDROID_QUALITY_GUIDELINES.md`, decision log 2026-07-17.*
