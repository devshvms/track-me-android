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
| 3 | Accessibility | ✅ code | **Fixed in `b9599c4` and refined in the current Android follow-up:** `RadialStartRideButton` exposes a semantic Start Ride button, activity-selection state, and custom TalkBack actions for Walk/Run/Cycling/Bike/Car while preserving the touch drag gesture. The child play icon no longer contributes a duplicate description because the parent owns the merged semantics. Physical TalkBack walkthrough remains a device check. |
| 3b | Touch targets | ✅ | HUD buttons (SOS, pause/stop, share) explicitly sized 52dp — compliant. `MapControlCircleButton` also 52dp. |
| 4 | State preservation | ⚠️ | **Fixed 2026-07-18 (commit `26eb57e`), re-verified against code:** `TrackMeApp` now publishes a one-time `RecoverySummary` and `HomeScreen` shows a localized Snackbar distinguishing recovered rides from discarded empty records — closes the "silent" part of this gap. **Remaining gap, not yet spec'd or fixed: the live HUD itself still does not resume after process death** — a user who force-quits or is OS-killed mid-ride returns to idle Home (with the new explanatory Snackbar) rather than back into their in-progress ride. Full auto-resume is a larger change (re-binding to `TrackingService` state on cold start) and is reasonable to defer past this Snackbar fix, but should be tracked as its own follow-up rather than considered closed. |
| 5 | Empty/edge states | ⚠️ | No explicit "no active ride" empty-state message — the idle map + `RadialStartRideButton` functions as an implicit CTA, which is acceptable for a map-first app, but there's no first-run coach mark for a brand-new user who's never started a ride. **Spec: add a one-time first-run tooltip/hint** near the start button ("Tap and hold, then drag to choose an activity") — the 5-persona drag gesture is not self-evidently discoverable otherwise. |
| 6 | Permission choreography | ✅ mostly | Foreground location + notifications requested on load with a well-designed blocking dialog on denial (rationale text + re-request + Settings deep link) — this is the reference-quality flow in the app. **Gap**: `ACCESS_BACKGROUND_LOCATION` is declared in the manifest but never requested at runtime anywhere. **Spec: confirm with Antigravity whether background location is actually required** given the foreground-service-with-location-type exemption already in use; if not required, remove the unused manifest permission (cleaner Play Console permissions disclosure). If it IS required for some flow, add the proper in-context request per Google's separate-background-permission guidance. |
| 6b | Battery optimization prompt | ✅ | Requested in-context at ride start with rationale Toast — correct choreography. |
| 7 | Live GPS-loss feedback | ✅ | **Fixed (commits `49c7d12`, `63047ba`), re-verified against code:** `TrackingService` now drives `TrackingState.GPS_LOST`/`GPS_DISABLED` after a 15s callback gap, distinguishing a temporary signal gap from location services being off; `ActiveRideHudPanel` renders the corresponding warning, and the disabled-services case offers a tap-through to Location Settings. Runtime/device confirmation is the only remaining gate (tracked in the robustness matrix, not this doc). |
| 7c | Locale/polish | 🔍 | HUD text (speed/distance labels, persona names) at 45–75 char line length across 7 locales — verify Hindi/Japanese don't wrap awkwardly in the compact HUD panel. **Correction (re-audit 2026-07-18):** haptic feedback on ride start/stop/pause is already present — `RadialStartRideButton.kt`'s `triggerHaptic()` (persona hover/launch) plus `performHapticFeedback` calls in `ActiveRideHudPanel.kt`, `InteractiveShareLocationButton.kt`, and `MapControlButtons.kt`. The original "not found in audit" note only scanned `HomeScreen.kt` itself, not its `components/` subdirectory. No fix needed here. |

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
| 3 | Accessibility | ✅ code | **Fixed in `e0feadb`:** the Canvas chart now exposes a semantic summary covering duration, average speed, altitude range, and GPS-signal gaps. The scrubber also announces that it controls speed, altitude, and route position. Physical TalkBack verification remains a device check. |
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
| 3 | Accessibility | ✅ code | **Fixed in `f7794e1`:** Intelligent Auto-Pause and Disable GPS Post-Processing rows are single labeled `Role.Switch` semantics with the visual switches as indicators, so TalkBack announces the setting label and state together. Physical verification remains a device check. |
| 4 | State preservation | ✅ | Standard settings screen; no special concern. |
| 5 | Empty/edge states | ✅ code | **Fixed in `50d4411` and refined in `31a26a0`:** the shared connectivity monitor now drives a localized, single-node offline banner on Settings, explicitly explaining that changes remain on-device and sync when connected. |
| 6 | Permission choreography | N/A | Permissions are requested from Home/EmergencySetup, not here. |
| 7 | Sync status trustworthiness | ✅ code | **Fixed in the shared upload path:** `uploadRideInternal()` rethrows failures, so `syncAll()` and `syncPeriodic()` publish `SyncResult.Error` rather than showing false success when an upload fails. A real Firestore failure/runtime check remains recommended before production. |
| 7b | Locale/polish | 🔍 | Settings labels at longest-locale + largest font scale — verify no two-line wrap breaks alignment with toggles. |

## 5. Account Management (`ui/settings/AccountManagementScreen.kt`)

| # | Item | Status | Notes / Fix Spec |
|---|---|---|---|
| 1 | Notification channels | N/A | Export completion could optionally notify via `sync_channel` once background/async export exists — not currently applicable since export appears to be a foreground-initiated `DownloadManager` flow. |
| 2 | Dark theme | 🔍 | Account/export cards — lower visual-risk screen, still needs the pass. |
| 3 | Accessibility | ✅ code | **Fixed in `000b36a`:** the privacy disclosure row is one semantic button, the destructive confirmation field has an explicit label, and existing export/delete actions retain clear text labels and confirmation dialogs. Physical TalkBack verification remains a device check. |
| 4 | State preservation | ✅ | Standard screen; no special concern. |
| 5 | Empty/edge states | ✅ code | **Fixed in `50d4411` and refined in `31a26a0`:** Account Management reuses the localized, single-node offline banner, so export and account actions are presented with an explicit offline state before the user attempts cloud operations. Physical offline/export behavior remains a device/network check. |
| 6 | Permission choreography | N/A | No new permissions requested here. |
| 7 | Export auth pattern | ✅ *(corrects a prior assumption)* | Confirmed via audit: export uses a short-lived server-issued `token` query parameter on the download URL, not an `Authorization` header on `DownloadManager.Request` — this is a valid, intentional pattern (headers aren't reliable across `DownloadManager` OEM implementations). The export *request* call does correctly force-refresh its Bearer token. **No fix needed**; flagging so this doesn't get "re-fixed" by a future agent working from a stale inbox note. |
| 7b | Disk-full / large-history export | ⚠️ | Not exercised; generic exception handling only. See robustness matrix P2 item. |

## 6. Emergency Setup (`ui/settings/EmergencySetupScreen.kt`)

| # | Item | Status | Notes / Fix Spec |
|---|---|---|---|
| 1 | Notification channels | ✅ | **Fixed (commit `f315d25`), re-verified against code:** `EmergencyBroadcastWorker` now posts to `sos_channel` with accepted/partial/failed contact counts while SOS is active. Runtime notification-display verification remains a device-check item (see checklist below), not a code gap. |
| 2 | Dark theme | 🔍 | Contact list + permission step — standard risk. |
| 3 | Accessibility | ✅ code | **Fixed in `777607d`:** wizard titles are exposed as TalkBack headings, the Emergency Message Template already uses a real `OutlinedTextField` label, text buttons expose their actions, and delete actions now identify the contact being removed. Physical TalkBack verification remains a device check. |
| 4 | State preservation | ✅ | Standard form screen; no special concern beyond not losing in-progress contact entry on rotation (verify `rememberSaveable` is used for form fields — not confirmed in this pass). |
| 5 | Empty/edge states | ✅ | Setup flow itself functions as the "empty state" (no contacts yet → setup prompt) — appropriate for this screen type. |
| 6 | Permission choreography | ⚠️ | **Code-complete in the current Codex follow-up:** `PermissionAndTestStep` tracks a denied request, uses `shouldShowRequestPermissionRationale` to detect permanent denial, shows localized SMS-settings guidance, opens `ACTION_APPLICATION_DETAILS_SETTINGS`, and refreshes state on return. Verify first denial, permanent denial, Settings grant, and back-navigation on a physical device. |
| 6b | Post-setup revocation | ⚠️ | **Fixed in commit `8d59caf`, re-verified against current code:** `MainActivity.onResume()` persists a revocation signal when a configured user loses `SEND_SMS`; Home now shows a dismissible warning with a direct Settings path, and `EmergencySetupScreen` explains the failure and opens app settings. Runtime revoke/grant and cold-start verification remain under TASK-005. |
| 7 | SMS failure feedback | ⚠️ | **Partially fixed (commit `f315d25`), re-verified against code:** a live/just-completed SOS broadcast now reports its outcome via the `sos_channel` notification (accepted/partial/failed counts). **Remaining gap, unchanged from original finding:** this screen itself still has no persistent "last SOS attempt" history — the notification is the only record, and it's ephemeral once dismissed/cleared. **Spec (P2, not blocking):** surface the same summary (timestamp + per-contact outcome) as a small status row on this screen, sourced from the same data the notification already uses. |
| 7b | Locale/polish | 🔍 | Contact name/phone fields at largest font scale — verify no clipping in the list rows. |

---

## Cross-screen theme/font-scale verification checklist 🔍

The following require a running emulator or device — Codex or the user should walk this list once W1's smoke test exists to reuse as a regression baseline:

1. Toggle system dark theme; visit all 6 screens; confirm no white-on-white/black-on-black regions, especially: Home's HUD status pills (raw brand colors bypass theme contrast roles per audit), RideDetail's Canvas chart, History's route thumbnails.
2. Set system font scale to Large and then to the largest available (typically 200%); visit all 6 screens; confirm no text truncation/clipping, especially in HUD stat labels, Settings toggle rows, and EmergencySetup contact list rows.
3. Switch device language to Hindi and Japanese (2 of the 7 supported locales flagged as highest line-length risk in the guidelines); spot-check Home HUD and Settings for wrapping/overflow.
4. Run TalkBack through: Home → start a ride via the persona picker → Settings → EmergencySetup → add a contact → RideDetail → scrub the chart. Home, EmergencySetup, and RideDetail semantics are covered in source and need physical-device confirmation; remaining device checks include announcement order, font scale, and system permission flows.

## Sign-off status

Per-screen specs above were the completed W3 deliverable (spec side) as of 2026-07-17; **re-synced 2026-07-18 against Codex's landed commits** (`49c7d12`, `63047ba`, `2695ec5`, `b9599c4`, `e0feadb`, `777607d`, `f7794e1`, `50d4411`, `31a26a0`, `000b36a`) — all former P0 code gaps now have fixes in place, pending runtime/device verification. Home #3 persona-picker, RideDetail #3 chart semantics, EmergencySetup #3 labels, Settings #3 switch semantics, Settings/Account offline-state banners, and Account #3 action semantics are code-complete. Remaining W3 work is device verification plus higher-consequence action-flow runtime checks. 🔍 items require Codex or the user to execute the device checklist below; they cannot be closed by code review alone.

*Companion document: `FEATURE_ROBUSTNESS_MATRIX.md` (W2). Cross-references: `.ai/context/ANDROID_QUALITY_GUIDELINES.md`, decision log 2026-07-17 and 2026-07-18.*
