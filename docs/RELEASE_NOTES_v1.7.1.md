# TrackMe Android v1.7.1 Release Notes 🩹

**Release Date:** August 9, 2026
**Version:** 1.7.1 (Build 25)

A patch release addressing the production issues open in Firebase Crashlytics against the 1.7.0
cycle. No feature changes, no behaviour changes beyond the crashes themselves.

---

## 🐛 Fixes

### The map could kill the app before it finished loading

**Fatal.** `NullPointerException: CameraUpdateFactory is not initialized`, reported on the home
screen.

`CameraUpdateFactory` only works once the Maps SDK has installed its delegate. Every camera move
in the app is triggered either by a screen effect or by a tap, and both can arrive first — a quick
first location fix on a slower device is enough to lose the race. The factory then throws, fatally,
from a path that was only trying to pan a map.

Fixed in two independent places: the Maps SDK is now initialised at app startup, and every camera
move is guarded so that a map which is not ready yet simply does not move instead of taking the
process down with it.

Applied to all ten camera calls across the home, ride detail and ride comparison screens — not
only the one that crashed. The reported line was just the one that happened to lose the race
first. This was not introduced in 1.7.0; the affected calls date back to v1.3.0.

### Crash reports were being drowned in coroutine noise

Cancelled coroutines were being recorded as non-fatal errors. Cancellation is not a fault — it is
how the app reports that it *successfully* stopped background work when you navigate away from a
screen, so normal use generated a constant stream of them.

They are no longer reported. Genuine failures, including ones that merely have a cancellation
somewhere underneath them, still are.

---

## ✅ Already fixed in the 1.7.0 cycle

Two foreground-service crashes also appear in Crashlytics against version 1.6.6 and are **already
resolved** in 1.7.0:

- `ForegroundServiceDidNotStartInTimeException` — group presence did not promote its service to
  the foreground before the system's deadline.
- `ForegroundServiceStartNotAllowedException` — a foreground service start was attempted from the
  background, which Android 12+ refuses.

> **A note on version numbers.** Version `1.6.6` (build 23) covered the *entire* 1.7.0 development
> period — the bump to 1.7.0 happened only at merge. Crash reports tagged 1.6.6 therefore span both
> pre-fix and post-fix builds and **cannot be attributed by version alone**, only by event
> timestamp. Bumping the build number alongside the name in this release restores that distinction
> for anything reported from here on.

A third Crashlytics entry — an ANR in the OpenGL pipe — is emulator-only (`qemu_pipe_write`) and
does not affect physical devices.

---

## 🔍 Verification

- 417 unit tests, 0 failures.
- Both fixes are covered by tests that were mutation-tested: reverting either fix fails its test.
