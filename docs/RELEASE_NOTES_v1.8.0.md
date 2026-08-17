# TrackMe 1.8.0 — release notes

Opens the Material 3 redesign arc. This release is **phase 1 of 5**: the design system
foundations. No feature is added, removed or altered — what lands is the token layer every later
phase is built on, plus the accessibility defect that layer immediately exposed.

Full specification: [`DESIGN_SYSTEM_1.8.md`](DESIGN_SYSTEM_1.8.md).

---

## For the store listing

- **Pause or finish your ride from the notification.** No unlocking, no opening the app.
- **Messages are part of the app now.** Every confirmation and error was a system Toast floating
  over the app; they are Snackbars — themed, dismissable, and readable against the app's own
  surfaces.
- **Settings is rebuilt.** Grouped rows with consistent spacing and touch targets, section labels
  you can skim, and the whole row toggles rather than just the switch.
- **Warning text is readable again.** The amber used for warnings failed the accessibility
  contrast minimum on light backgrounds. It now clears it comfortably in both themes.
- **Cleaner surfaces.** Cards, sheets and the navigation bar now sit at genuinely different
  levels instead of blending into the background.
- **Dividers and borders no longer look identical**, so lists and grouped settings are easier to
  scan.
- **Deleting a ride animates** instead of making the list jump.

> Nothing about tracking, group rides, sync or export changes in this release.

---

## The defect this fixes, live since the palette was introduced

**Warning amber was unreadable on light backgrounds.**

`AmberWarn #F59E0B` was bound to Material's `tertiary` role and used for warnings. As a
foreground on a light surface it measures **2.15:1**. WCAG AA requires **4.5:1**. Any warning
text or icon drawn in that role on a light surface was effectively unreadable for low-vision
users, and had been since the token was introduced.

Warning is now its own semantic token at tone 40 — **6.17:1** on light, **10.92:1** on dark.

This also unbinds it from `tertiary`, which is a **brand accent** slot, not a state slot. That
conflation is the same category error `Color.kt` already documents for the 1.5.11 green Start
button that shipped against a cyan logo. Amber-as-tertiary was that bug, still present. It is now
gone, and `tertiary` takes the tone Material 3 actually derives from the brand seed.

---

## Two more defects fixed by construction

**`outline` and `outlineVariant` were the same colour.** Both were bound to one grey, so a
divider and a border were indistinguishable. Material 3 defines them as two emphasis tiers; they
now are two.

**`background` and `surfaceContainerLow` were the same colour.** Both were `Navy900` in dark
theme, so nothing at low elevation had anything to separate from. They are now two tone steps
apart.

---

## What actually changed on screen

Phase 1 aims to be visually near-identical, and mostly is. The differences are deliberate:

1. **Dark surfaces shift by 2–3 tone points.** `#12161C` → `#0E1418`, `#181A20` → `#1B2025`.
   Imperceptible side by side; the ramp is now evenly stepped rather than hand-picked.
2. **The two brand cyans converge.** `CyanBright` (hue 251°) and `CyanDeep` (hue 264°) were
   picked separately, 13° apart. They are now one hue at two tone positions, so the brand reads
   as one colour.
3. **Light-theme primary is slightly darker** — tone 40 rather than the previous cyan/deep. This
   raises its contrast on light surfaces from 4.86:1 to 6.15:1.

The brand colour itself is **unchanged**: `#29B6F6`, the same seed that is on the logo and in the
store listing. Every colour in the app is now generated from it rather than picked by hand.

---

## Phase 2 — components

**39 of 40 Toasts became Snackbars.** A `Toast` renders outside the app's theme, cannot be
dismissed, cannot carry an action, and on Android 12+ is rate-limited and stamped with the app
icon and name. The one remaining lives in `TrackingService`, which is a Service with no
composition to host a Snackbar — that wants a notification, and is phase 4.

The reason `Toast` kept winning was ergonomic, not architectural: `showSnackbar` is a `suspend`
function, so the correct surface cost four lines against Toast's one. `TrackMeMessenger` makes it
one line, replaces rather than queues, and is app-scoped so a message survives the screen that
sent it being popped.

**New components:** `SettingsGroup`, `SettingsSwitchRow`, `SettingsRow`, `SettingsDivider`.

## Phase 3 — Settings

**682 lines → 569.** Five hand-rolled `Card`s became five `SettingsGroup`s; fifteen hand-built
`Row`s became seven `ListItem`-backed rows. The app used `ListItem` exactly zero times, and each
of the fifteen hand-built copies had drifted — different padding, some toggleable across the whole
row and some only on the switch. That drift is what "the screens don't quite line up" was.

Settings went first because it is the lowest-risk screen and, per PostHog, the second most used:
**979 minutes across 36 of 41 Android users** in 90 days. The original ordering was a risk guess;
the usage data agreed with it.

One deliberate presentation change: **Help & Feedback** was a card wrapping a full-width button
and is now a navigating row with a chevron. Same destination, same single tap.

**History** gained `animateItem()` on its ride list. It already had stable keys, so placement
animation was one line — without the keys it would have animated the wrong rows.

## Phase 4 — notifications

The weakest surface in the audit, and previously untouched.

**A real icon.** All six `setSmallIcon` calls used `android.R.drawable` stock platform drawables.
Replaced with a flat white-on-transparent silhouette — that format is not a preference, since
Android tints the small icon and discards everything but the alpha channel.

**Ride controls in the shade.** `TrackingService` already had `ACTION_PAUSE_SERVICE`,
`ACTION_START_OR_RESUME_SERVICE` and `ACTION_STOP_SERVICE`; they were never surfaced, so pausing
mid-ride meant unlocking the phone and opening the app — the worst possible moment to ask that.
Pause and Resume are shown mutually exclusively, since they are one control in two states.

**Brand accent** via `setColor`, and `CATEGORY_WORKOUT` so the system ranks the ongoing
notification correctly.

Still outstanding in phase 4: the Android 16 promoted-ongoing status-bar chip, `ProgressStyle` for
group rides with a destination, adaptive layouts, and map camera work.

## The map follows the theme

The app had **no map styling at all** — no `MapStyleOptions`, no night style, no raw style
resource. It rendered Google's default light basemap regardless of theme, so a dark-theme user
opening the app at night got a full-screen sheet of white.

This was also what blocked Home. Route polylines, HUD pills and markers were tuned for a light
background — correctly, given the map they sat on — which is why they used fixed colours rather
than theme roles. Theming the basemap is what makes theming the overlays possible.

`rememberMapStyle()` reads the app's own `themeMode` preference, not just `isSystemInDarkTheme()`.
That setting has three values, and honouring only the system one would leave the map light for
someone who has forced dark inside the app. The night basemap is drawn from the same neutral ramp
as the app, so the map and the panel above it are one family rather than two unrelated darks;
business POIs and transit are off, being noise on a tracking map.

The route polyline now follows `colorScheme.primary` — the first Home overlay to become
theme-aware, and only correct once the basemap was.

## One truthful ride-end message

Ending a ride showed two contradictory messages at once: a Snackbar saying *"Saving ride…"* and a
Toast saying *"too short to save"*. Two systems describing one event from opposite ends — the UI
announcing an **intent** it had no standing to predict, the service announcing the **outcome** —
and neither could replace the other, because one was a Snackbar and one a Toast.

The service now reports a `RideEndOutcome` event and the UI says one true thing once.

**This removed the app's last Toast**, completing the conversion at 40 of 40. It lived in a
Service, which has no composition to host a Snackbar — turning it into an event rather than a
rendered message was the right answer. It also made that string localizable for the first time.

## Surfaces and elevation

- **History ride cards were invisible.** They painted themselves `colorScheme.surface` — the
  *screen background* role — so an unselected card was the same colour as the page behind it and
  depended entirely on a 1dp shadow, which does not render in dark theme.
- **Seven cards moved off `surfaceVariant`** onto `surfaceContainerLow`. `surfaceVariant` is M3's
  role for text-field backgrounds; containers belong on the `surfaceContainer*` ramp.
- **The ride HUD panel** is on the shape scale and elevation ladder. It was `RoundedCornerShape(20.dp)`
  — between `large` and `extraLarge`, on neither — with `shadowElevation = 8.dp`, which is level 4,
  reserved for hover and drag states where nothing rests.

## What was deliberately left alone on Home

Two things on Home look like token violations and are not. Recorded so a later pass does not
"fix" them:

- **The HUD pills** use fixed `TrackMeAmber` / `TrackMeRed` fills with hardcoded black or white
  text. They sit over map imagery rather than a themed surface, so they are their own surfaces and
  their foregrounds are chosen for those fills. Black on amber clears AA comfortably.
- **The route polyline** uses a dark blue. Pointing it at `colorScheme.primary` would make it pale
  `#84CFFF` in dark theme.

Both are correct because of a genuine gap: **the map has no theme-aware styling at all** — no
`MapStyleOptions`, no night style. It is always Google's default light map, so a dark-theme user
gets a bright white map at night and every overlay is correctly tuned for a light background.
Fixing the map style changes what the correct overlay colours are, so the two must be done
together, in phase 4.

## Under the hood

- **Two-tier design tokens.** Primitive tones (`Tone.Primary40`) carry no meaning and are never
  read by UI. Semantic roles (`colorScheme.primary`, `TrackMeSemantics.warning`) are the only
  thing screens may use.
- **Motion tokens replace ad-hoc durations.** Six spring tokens, split by a rule that prevents a
  whole class of bug: spatial springs move things and may overshoot; effects springs change
  colour and opacity and never may, because an alpha that overshoots clips and flashes.
- **Elevation has a shadow policy.** Shadow is reserved for surfaces the user can dismiss or
  drag. Levels 0–2 separate by tone alone — which matters because the primary theme is dark, and
  a black shadow on a near-black ground is invisible while still costing overdraw.
- **Material 3 Expressive is deliberately not depended on.** It lives in `material3:1.5.0-alpha`
  behind an experimental opt-in while this app is on 1.4.0 stable. Screens depend on our own
  motion abstraction instead, so adopting Expressive later is a value swap rather than a rewrite.
- **A debug-only design catalog** renders every token and component state. It is the
  screenshot-test surface for phase 2 and is stripped from release builds.

---

## Testing

| Gate | Result |
|---|---|
| `:app:compileDebugKotlin` | pass, 0 warnings |
| `:app:assembleDebug` | pass |
| `:app:testReleaseUnitTest` | pass — **556 tests, 0 failures** |
| `:app:lintRelease` | pass — 0 errors |

`TokenIntegrityTest` is new: 11 structural invariants, each mapping to a real defect found in the
audit. Every assertion is a regression guard rather than a hypothetical.

Two assertions in the existing `ThemeContrastTest` were **restated** rather than deleted, and both
are documented in `DESIGN_SYSTEM_1.8.md` §10.1:

- `outlineVariant` no longer requires 3:1 against every surface. It is a decorative divider and
  WCAG 1.4.11 exempts decoration — and the old assertion only passed because both outline roles
  were the same colour, which is the defect being fixed. `outline` keeps the full 3:1.
- The dimmed-caption guard **inverted**, correctly. It asserted a 90%-alpha caption composites
  *below* AA (4.27:1) as evidence captions had to stay at full opacity. The darker primary makes
  the same caption 5.59:1, so the hazard is gone; the guard now fires if primary is ever lightened
  back.

**Not covered:** the instrumented release smoke test (`ReleaseLaunchSmokeTest`) has not run
locally — it needs a device or emulator. CI runs it on every Play workflow execution.

---

## Phase map

| Phase | Scope | Status |
|---|---|---|
| **1** | **Token layer, catalog, integrity tests** | **this release** |
| 2 | ~22 components, lint rules banning raw `Surface`, `Toast`, hardcoded colours | next |
| 3 | Screens re-composed, low risk to high — Settings first, Home last | |
| 4 | Notifications, adaptive layouts, map camera, widgets | |
| 5 | Regenerate colour with Material Color Utilities; adopt Expressive if stable | |
