# Feature Robustness Matrix (TASK-015 W2)

*Owner: Claude (Product/Design Agent). Written 2026-07-17 per `.ai/context/ANDROID_QUALITY_GUIDELINES.md` W2. Every cell below is grounded in a direct code audit of `track-me-android` (file:line references included), not assumption — this satisfies the "verified manually" half of the W2 acceptance criterion. Verification-by-test/device still belongs to Codex/TASK-005 before production. Statuses: ✅ Handled, ⚠️ Partially handled, ❌ Not handled (bug/gap).*

## How to read this

Each row is one feature under one worst-case condition. "Expected" is what a launch-quality GPS/safety app should do. "Actual" is what the current code does, cited by file:line. "Severity" reflects user impact if unfixed at launch: **P0** = safety/data-loss/silent-failure in a core promise (tracking, SOS), **P1** = degraded experience without warning, **P2** = polish gap.

---

## Tracking

| Failure condition | Expected | Actual (audited) | Status | Severity |
|---|---|---|---|---|
| 4h+ ride, sustained recording | No memory growth/crash, accurate distance/speed over full duration | Points stream to Room continuously (not held in memory); ride auto-splits at 8,000/9,000-point thresholds via `sync_channel` notifications (`TrackingService.kt:460-480`) | ✅ | — |
| GPS signal gap mid-ride (tunnel, urban canyon) | User sees a live "GPS lost" indicator while recording continues; gap is later shown clearly in ride detail | The service now transitions to `TrackingState.GPS_LOST` after a 15-second callback gap and the HUD shows the elapsed gap; the next valid fix returns to `TRACKING`. The existing post-ride chunk/graph behavior remains. Runtime behavior still needs emulator/device verification. | ⚠️ | **P0** |
| Airplane mode / GPS toggled off mid-ride | Clear in-app state ("GPS unavailable — check location settings") | `TrackingService` now distinguishes a callback gap from disabled location services using `LocationManager.isLocationEnabled` (or GPS/network providers on older Android), enters `GPS_DISABLED`, and the HUD offers a tap-through to Location Settings. Runtime/device verification remains pending. | ⚠️ | **P0 — runtime/device verification pending** |
| Speed spikes from noisy GPS fixes | Outliers rejected, no absurd speed/distance in results | `GPSProcessor.kt:38-67` rejects points exceeding `MAX_ACCELERATION_G = 2.0f`; live accuracy filter also discards fixes with `accuracy > 22.0f` (`TrackingService.kt:59-61`) | ✅ | — |
| Storage nearly full | Graceful warning before write failure; no corrupt ride data | No `StatFs`/free-space check anywhere in `TrackingService` or the Room layer. An `SQLiteFullException` mid-ride is unhandled — behavior on real low-storage devices is unverified and likely a raw crash or silent write failure. | ❌ | **P1** |
| Battery saver mode on | Tracking continues (with possible accuracy tradeoff), user warned if not | Foreground service + `FusedLocationProviderClient` is the standard battery-saver-resilient pattern; battery-optimization exemption is prompted once at ride start (`HomeScreen.kt:300-311`). No explicit test evidence of behavior *if user declines* the exemption and battery saver later kills background delivery — flag for device-level W1 manual matrix. | ⚠️ | P1 |
| Process death mid-ride (OS-killed) | User relaunches into a resumed live HUD, or is clearly told the ride was saved/ended | `TrackingService` shares process with the Activity (no `android:process` isolation) — both die together. `START_STICKY` (`TrackingService.kt:189`) restart delivers a null-action `Intent` that isn't handled, so tracking does not resume. Recovery is reactive only: `OrphanedRideRecoveryManager` (`domain/recovery/OrphanedRideRecoveryManager.kt`), run from `TrackMeApp.onCreate()`, finds any ride with no `endTime` and **silently auto-finalizes it** as a completed ride using whatever points were persisted, or deletes it if zero points exist. User lands on idle Home with no message that their ride was cut short by the OS. | ❌ | **P0** |
| Device has no accelerometer / linear-accel sensor | Auto-pause feature degrades gracefully or is disabled; tracking still records distance/speed | `MotionSensorManager` now requires both an available sensor and at least one received sample before reporting stationary. Devices without a sensor, and the startup period before the first sample, fall back to GPS speed/drift logic instead of forcing effective speed to `0f`. Runtime/device coverage remains pending. | ⚠️ | **P0 — runtime/device verification pending** |
| Wake-lock / CPU sleep during long ride | GPS callbacks keep arriving without a manual wake lock (Mar 2026 Play policy compliance) | Explicit 10h `PARTIAL_WAKE_LOCK` removed (decision log 2026-07-17); relies on `FusedLocationProviderClient` + location-typed foreground service (`LocationHelper.kt`, manifest `foregroundServiceType="location"`). Confirmed no wake lock exists anywhere in the codebase. | ✅ | — |

## Live Share

| Failure condition | Expected | Actual (audited) | Status | Severity |
|---|---|---|---|---|
| Session/token expires mid-share | Refresh + retry transparently, or clear "session expired" message | `LiveShareManager.pushLocation()` (line 142-144) transitions to `EXPIRED` state on HTTP 404 — handled. **401 (auth) is not specially handled** — falls into the generic `formatGracefulError()` else-branch, showing a network-style message rather than an auth-specific one (lines 200-223). | ⚠️ | P1 |
| Stale ID token at share start | Force-refreshed token used to avoid an avoidable 401 | `firebaseIdToken()` (`LiveShareManager.kt:39-45`) calls `getIdToken(false)` — **not force-refreshed**, unlike the export flow (`SettingsViewModel.kt:113-115`, which explicitly force-refreshes with a code comment about cached-token expiry). Live Share is more exposed to stale-token failures than export is. | ❌ | P1 |
| Offline start attempt | Clear, friendly error — not a hang or generic failure | `formatGracefulError()` maps `UnknownHostException`/`ConnectException`/`SocketTimeoutException`/`SSLException` to specific messages (e.g., "Unable to reach live share server...") | ✅ | — |
| Viewer opens link after session expiry | Clean "this share has ended" page, not a broken/blank viewer | Web-side (`track-me-web`) responsibility — not audited in this pass; flagged for a follow-up cross-check against `track-me-web/public/tracker.html`. | ⚠️ untested | P2 |

## SOS / Emergency

| Failure condition | Expected | Actual (audited) | Status | Severity |
|---|---|---|---|---|
| SOS triggered — notification to user that it's in progress/sent | User sees confirmation the alert was sent (or failed) | `EmergencyBroadcastWorker` now posts to the dedicated high-priority `sos_channel`, showing accepted/partial/failed contact counts while SOS is active. The notification reports SMS-stack submission, not carrier delivery. Runtime verification remains pending. | ⚠️ | **P0 — runtime verification pending** |
| SMS send fails (no signal, carrier reject, permission revoked) | User/app knows the alert didn't go out and can retry or is told to call emergency services directly | Each SMS now uses a sent-result `PendingIntent`; rejected or timed-out submissions are counted and surfaced in the SOS notification. Carrier delivery confirmation is still not claimed because delivery callbacks are not awaited. Runtime verification remains pending. | ⚠️ | **P0 — runtime verification pending** |
| SMS permission revoked after setup | Clear re-prompt/explanation before the user needs SOS again | `MainActivity.onResume()` (lines 58-69) correctly detects revocation and flips `isSetupComplete = false`, which disables the SOS button (`ActiveRideHudPanel.kt` `SosButton`, `clickable(enabled = isReady)`). **But the button just goes grey with no message explaining why** — a user in a real emergency would see an inert button with no explanation. | ❌ | **P0** |
| SMS permission permanently denied during initial setup | Rationale + deep link to Settings, same pattern as location | `EmergencySetupScreen.kt` `PermissionAndTestStep` (159-222) shows rationale before the first ask, but has **no `shouldShowRequestPermissionRationale` check and no Settings deep link** on permanent denial — weaker than the location-permission flow in `HomeScreen.kt`, which does have this. | ❌ | P1 |
| Emergency contact deleted from device Contacts app | Documented behavior (app should not silently rely on a stale reference, or should clearly state it uses a saved snapshot) | Contacts are copied into local Room storage at setup time (name+phone snapshot); no re-validation against live device Contacts before sending. This is defensible (SMS still reaches the saved number) but is **not documented to the user** — they may assume deleting a contact on their phone also removes it from TrackMe's SOS list. | ⚠️ | P2 — needs a one-line copy fix in EmergencySetupScreen, not a code fix |

## Cloud Sync

| Failure condition | Expected | Actual (audited) | Status | Severity |
|---|---|---|---|---|
| Conflict after restore-to-new-device | Merge or explicit conflict resolution, not silent data loss | `FirestoreSyncManager.uploadRideInternal()` (258-300) is last-write-wins with no version check; download dedupes only by `firestoreId` presence. No conflict UI. Acceptable for a single-user-per-account app *if* documented, but currently undocumented and unverified against a real two-device scenario. | ⚠️ | P1 |
| Partial sync interruption | Resumable, no duplicate uploads | Each ride is marked `isSynced = true` only after its own `.set().await()` succeeds (line 293-295) — genuinely resumable. | ✅ | — |
| Sync failure reporting accuracy | `SyncResult` reflects what actually happened | Periodic sync now propagates upload failures instead of counting a ride as uploaded after a swallowed exception. Interactive `syncAll()` still uses the existing coroutine-based flow and should be covered by a follow-up test/review. | ⚠️ | **P0 — runtime/failure-path verification pending** |
| Background periodic sync via WorkManager | `WorkManager` retries on real failure | `SyncWorker.doWork()` now awaits the suspend `syncPeriodic()` result, writes `last_sync_time` only on `SyncResult.Success`, and returns `Result.retry()` for sync errors. Runtime WorkManager/Firestore failure-path verification remains pending. | ⚠️ | **P0 — runtime/failure-path verification pending** |
| Sign-out mid-sync | No crash, no partial-state corruption | `user` is captured once at the top of `syncAll()`; mid-loop sign-out likely fails remaining writes server-side, but the swallowed-exception bug above means the UI won't reflect this accurately. Same root cause as the sync-count bug. | ⚠️ | P1 (tied to the P0 above) |

## Export (ZIP + GPX)

| Failure condition | Expected | Actual (audited) | Status | Severity |
|---|---|---|---|---|
| DownloadManager auth | Authenticated download without leaking long-lived credentials | **Correction to prior assumption**: does NOT use an HTTP `Authorization` header on the `DownloadManager.Request` (confirmed zero `addRequestHeader`/`setRequestHeader` calls). Instead the export *request* call properly uses a force-refreshed Bearer token (`SettingsViewModel.kt:105-168`), and the server issues a short-lived `token` query parameter embedded in the download URL, which `AccountManagementScreen.kt:392-435` validates client-side before enqueueing. This is a valid pattern for `DownloadManager` (which can't attach custom headers reliably across all OEMs) — no fix needed, but the architecture doc / prior inbox note describing this as a "missing auth header" regression is stale and should be corrected. | ✅ (verify token TTL server-side is short) | — |
| Huge history (500+ rides) export | Completes without timeout/OOM | Not exercised in this audit; matches the Vercel-timeout risk Antigravity flagged in `SYSTEM_DEPENDENCIES_AND_SCALABILITY.md` for heavy exports — server-side risk more than client-side. | ⚠️ untested | P2 |
| Disk full during GPX/image export | Friendly error, no crash | Generic `try/catch(Exception)` around GPX/image export (`RideDetailScreen.kt:585-589, 660-664`) surfaces raw `e.message` in a Toast — would catch an `IOException` but shows an unfriendly raw message rather than a designed error state. | ⚠️ | P2 |

## GPX Import

| Failure condition | Expected | Actual (audited) | Status | Severity |
|---|---|---|---|---|
| Malformed GPX file | Clear error, never crash | `HistoryViewModel.importGPX()` wraps the full parse+insert flow in try/catch, logs via `errorLogger`, emits `UiEvent.ShowError("Failed to import. Please ensure the file is a valid GPX format.")` (lines 116-120). `HistoryScreen.kt:73-98` pre-validates file extension and wraps content-resolver access separately. | ✅ | — |

---

## Severity summary (for W1/W2 triage)

**P0 — fix before production (safety, silent failure, or data-integrity risk):**
1. Live GPS-loss indicator is dead code — no in-ride warning on signal loss (Tracking)
2. No airplane-mode/GPS-disabled detection — fully silent tracking failure (Tracking)
3. Process-death mid-ride silently auto-finalizes the ride with no user message (Tracking)
4. Accelerometer-less devices can permanently zero distance/speed via stuck auto-pause (Tracking)
5. SOS has no user-facing send confirmation or failure state at all (SOS)
6. SOS SMS failures are silently swallowed with no delivery confirmation (SOS)
7. SOS button disables with no explanation when permission is revoked (SOS)
8. `syncAll()` can report `Success` on a partially failed sync (Cloud Sync)
9. `SyncWorker` never actually awaits sync completion — retry is dead code (Cloud Sync)

**P1 — fix before "polished," acceptable to launch without if triaged explicitly:**
storage-nearly-full unhandled; battery-saver decline path unverified; Live Share 401 not distinguished + non-forced token refresh; SMS permanent-denial fallback missing; sync conflict handling undocumented; sign-out-mid-sync UI accuracy.

**P2 — polish backlog:**
contact-deletion copy clarification; large-history export/disk-full friendlier errors; viewer-expired page cross-check on web.

*These findings are new since the ANDROID_QUALITY_GUIDELINES.md draft (which flagged the categories to check but not these specific bugs) — see companion inbox note to Codex and decision log entry dated 2026-07-17.*
