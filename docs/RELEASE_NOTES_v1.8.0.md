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

**A status-bar chip on Android 16.** `setRequestPromotedOngoing(true)` asks the system to promote
the ongoing notification into the status bar, and `setShortCriticalText()` supplies what fits
there — elapsed time normally, `Paused` or `Searching…` when one of those is the truer thing to
say. A recording ride is the textbook case for this: user-initiated, ongoing, and something you
want to glance at without unlocking. Both are `NotificationCompat` calls, so pre-16 devices get
exactly the notification they got before, with no version check to get wrong.

**`ProgressStyle` was considered and skipped.** It is available — it ships in `androidx.core`
1.18.0, which this app already depends on; an earlier note in this branch claiming otherwise was
wrong. What stops it is scope, not the library. A determinate bar needs a destination, and the
only destination TrackMe knows is the group one behind `GroupFeatureFlags.SHOW_ETA` — an estimate
1.7 deliberately shipped dark to collect calibration data before showing anyone a number. Putting
it in a notification would ship that feature sideways, and 1.8.0 is a redesign with no feature
change. For an ordinary ride with no destination, `ProgressStyle` can only be indeterminate: an
animated bar that says nothing. Neither branch earns it.

## The ride camera

The map followed the rider by recentring, flat and north-up, which is an overview of where you
are rather than a view of where you are going.

It now pitches to **45° while recording** — what is ahead occupies more screen than what is
behind, which is the correct priority when moving — and rotates to the direction of travel. Paused
eases back to 30° rather than snapping flat, because a hard reset mid-ride reads as a glitch. When
a ride ends the camera returns to flat and north-up, so the app does not appear to have
permanently changed its map.

Heading comes from `RideCameraPolicy.headingOf()`, which walks back from the newest fix until it
finds one at least 12m away. Taking the last two points instead would steer the camera by GPS
noise and make the map wobble while the rider goes perfectly straight. It is a pure function in
its own file for the same reason `MemberMarkerPolicy` is: the decision is arithmetic on positions
and can be tested without a device. Seven tests cover it, including the case that motivated the
threshold — a long eastward run whose final two fixes happen to jitter north.

**Any pan, zoom or rotate suspends follow**, and the locate button is the way back — an auto-follow
camera animating while the user is panning is the worst feeling in any map app (G8 in the motion
audit). That is observed through `snapshotFlow` rather than keyed on `isMoving`, because a gesture
that begins *during* a programmatic animation only changes the reason, not the flag, and an effect
keyed on the flag would never see it. The compass button suspends follow too — otherwise north-up
would be undone by the next GPS fix and the button would appear not to work.

## Navigation adapts above 600dp

A bottom bar spends vertical space, which is exactly what is scarce on a wide short window, and on
a tablet it strands the destinations at the far edge of the screen. Both are the `NavigationRail`'s
case, and 600dp is where M3 puts the switch.

Below that nothing changes — a phone in portrait renders the same bar it always has. The
breakpoints live in `TrackMeWindowClass` rather than being read inline, for the same reason the
colour and motion layers have their own types: screens depend on the app's abstraction, never on a
windowing library. Six tests pin the boundaries, since an off-by-one there moves the whole layout
by one device class.

## The HUD pills, now that the map is themed

The phase-3 notes recorded two pills as deliberately off-token, pending the themed basemap. It
exists now, so:

- **The persona pill** was a fixed amber fill with black text. Amber is the warning role, and
  "you are riding as Motorbike" is not a warning — the colour made a neutral fact look like a
  caution. It is floating chrome over the map, so it joins the map control buttons on the surface
  ramp and follows the theme.
- **The GPS-lost pill** stays at full `error` emphasis rather than dropping to `errorContainer`.
  It is the thing that tells you the ride is not being recorded, which is the one message on that
  screen that must not be missable. It is now on the token, so it adapts.

Both use a new `mapOverlay` elevation token. Tonal elevation is a tint applied to a surface, and a
map is not a surface — over imagery, tone conveys nothing and shadow is the only separation
available. It is named rather than reusing `level2`, which is the same 3dp but is documented as
tone-only; drawing a shadow at `level2` would quietly contradict `castsShadow()`, the function that
exists to prevent precisely that.

**Three strings were never translatable.** The GPS-lost, location-disabled and storage-full pills
were hardcoded English in an app that ships seven languages — the last untranslated user-facing
strings in the app. They are now in `AppStrings` across all seven, and the literal `⚠` that was
baked into each one is an `Icon`, which screen readers handle predictably and translators no
longer have to carry.

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

## Phase 3 complete — the remaining screens

Community, Onboarding, Compare, Account and Help audited and corrected. The audit mattered more
than the volume: most apparent violations turned out to be correct, and three were real.

- **Account** exported three literal hex values for export status, duplicating what the semantic
  tokens already mean — and being literals, they did not adapt to theme at all.
- **Compare's live map** was never given the themed basemap, so it stayed light while Home and
  Ride detail went dark. Its export-preview map is deliberately untouched: that one carries a
  user-configured style, and `darkTheme` there is an export option, not the app theme.
- **Map control buttons** animated to `colorScheme.surface` when closed but a hardcoded light grey
  when open, so on the night basemap they flashed pale. Only visible once the map was themed.

### What was deliberately left alone

Compare's route-label chips, its aggregate legend, and Community's member avatars all paint white
on **fixed fills** — a route palette, a navy panel, a per-member tint. They are their own surfaces
with foregrounds chosen for them.

The remaining off-scale radii are **pills** (14dp), **circular buttons** (32dp) and **indicator
bars** (2–6dp). The shape scale governs *containers*; a 32dp radius on a circular button and a 2dp
radius on a 4dp-tall bar are geometry, not violations. Forcing them onto the container scale would
be the same error as tokenizing the map overlays.

**App-wide after this: 0 Toasts, 0 `surfaceVariant` containers, 0 literal colours outside
deliberate fixed-fill surfaces.**

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

## Phase 5 — the motion the design system had been describing

For four phases this document described a spring-based motion scheme that no screen actually used.
`TrackMeMotionScheme` was defined, provided through a `CompositionLocal`, and read by nothing. That
is now closed.

**23 of 30 `tween` call sites read the scheme.** *(The count was previously reported as 39 — that
grep matched `between(` too, including `computeDistanceBetween`. It was wrong; the real figure was
always 30.)*

Springs replace durations because a duration is a commitment: interrupt a tween mid-flight and it
restarts or snaps, where a spring carries its velocity through. Most of what "unglitchy" means in
the hand is that one property.

**The conversion found a gap in the token set.** The scheme split motion into *spatial* (may
overshoot) and *effects* (never overshoots, because an alpha past 1 clips into a flash). The ride
HUD's stop slider fits neither: it genuinely moves, so it is spatial, but its offset is clamped to
its track and the parent clips — a rubber-band that crosses the bound is drawn outside the
container and cut off, which reads as a rendering bug rather than as physics. Rather than borrow an
effects token and lie about what was being animated, the scheme gained a seventh: **`spatialBounded`**,
critically damped at spatial speed. A test asserts the damping ratios in both schemes, so the split
cannot decay into decoration.

**Seven call sites are deliberately still tweens**, and the reasons are type-level or physical:

- Four are `infiniteRepeatable`, which requires a spec with a duration. A spring has none.
- Two are the launch pulse on the start button, a duration-**coupled** pair: the ring must reach
  full size at the instant it reaches zero opacity. Spring settle time depends on distance
  travelled, and these travel 0.65 of scale against 0.45 of alpha, so no stiffness holds them
  together. A shared duration is what expresses the constraint.
- One is the slide-to-stop on the ride HUD, which is timed **choreography**: the slide, the 350ms
  the acknowledgement stays readable, and the commit are one sequence, and the total is what a
  rider experiences as how long stopping takes.

That last one is the interesting failure. Converting it looked entirely correct and pushed
`onStopRide()` from 500ms after the gesture to roughly **880ms** — because a spring settles as a
function of how far it travels, and that distance is half the screen width, so the ride would stop
at a different moment on a tablet than on a phone. Nothing in the code read as wrong.
`RideControlAccessibilityComposeTest`, which drives the clock by hand and asserts the ride has
stopped by 600ms, is what caught it.

**A second-order bug came out of the same conversion.** `Animatable`'s default visibility threshold
is 0.01 — a sensible default for a normalised 0..1 value, and the wrong one for a pixel offset,
where it asks the spring to land within a hundredth of a pixel. That tail is a few hundred
milliseconds of animation that finished *looking* finished long before it finished. Pixel-valued
`Animatable`s now declare `visibilityThreshold = 1f`.

`MotionTokenAdoptionTest` fails the build if any other file calls `tween`. The exemption list is
data in the test, not a comment, and a second test fails when an entry on it no longer has a
`tween` behind it — so stale permissions cannot quietly accumulate.

## The colour question was the wrong question

Every version of this document since phase 1 carried an open item: *regenerate the ramps with the
official Material Color Utilities, because ours are CIELCh approximations of HCT.*

Measuring first turned out to answer it. **HCT's tone *is* CIELAB L*** — the two systems differ in
how they model hue and chroma, not lightness. So the tone axis, which is the axis the entire scheme
indexes on, was never an approximation. `TonalRampAccuracyTest` measures all 80 ramp entries: the
worst deviation from nominal tone is **0.21 points**, against roughly 0.2 for 8-bit sRGB
quantisation itself. The ramps are as accurate as sRGB can represent.

The same test also asserts each ramp is monotonic in lightness — the one failure a contrast test
cannot see. A single inverted pair would silently break every "one step lighter" decision in the
scheme while changing no measured ratio.

What does remain approximate is chroma above T70, where gamut mapping bites hardest: a CAM16
chroma of 48 and a CIELCh chroma of 48 are not the same quantity, so the primary ramp desaturates
slightly earlier than Material's generator would. That is saturation, not lightness or ordering,
and every pair the app renders is measured empirically. Regenerating would move a handful of
high-tone values by a few points of chroma and is not worth re-tuning a palette already proven
correct on both axes anyone reasons about.

**Material 3 Expressive stays deferred, and the check is recorded rather than assumed.** The plan
was always "adopt if `material3:1.5.0` is stable". Against the Google Maven index in August 2026 the
newest published version is `1.5.0-alpha26` — no beta, no rc. That is the dependency inversion
paying off rather than failing: every animated component now depends on `TrackMeMotionScheme`, and
not one line references Material Expressive, so re-backing `Standard` when 1.5.0 lands changes no
screen.

## Deprecation debt, actually cleared

The build emitted eight deprecation warnings through most of this branch — including one I twice
reported as fixed when it was not. All are gone:

- **Three direction icons** (`DirectionsWalk`, `DirectionsRun`, `DirectionsBike`) moved to
  `AutoMirrored`. These glyphs face a direction of travel, so the non-mirrored forms point
  backwards in an RTL layout — the same defect already fixed for the back arrow.
- **A hand-rolled vibrator call** in `RadialStartRideButton` used `Context.VIBRATOR_SERVICE`,
  deprecated since API 31. `HapticFeedbackUtils` had already been written with the `VibratorManager`
  path, so the duplicate was both deprecated and worse. Deleted in favour of the shared helper.
- **`MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED`** in the video export drain. Deprecated, and never
  returned above API 21 — the buffer array it announced was replaced by `getOutputBuffer(index)`,
  which the drain already used. `minSdk` is 24, so the branch was unreachable.

The remaining warnings in CI logs come from GitHub's own Node runtime (`punycode`, `url.parse`) and
are not ours to fix.

## The export preview, rebuilt

Reported against both previews: the options should read like an editing app, the map should get
real space and behave the same at every ratio, and pinching or dragging the map moved the whole
page instead.

All three came from one decision. **The preview and every control lived in the same
`verticalScroll` column.**

**Controls are now two tiers.** A rail of six category icons — Ratio, Map, Privacy, Markers, Stats,
Legend — and above it the values for whichever is selected. Legend is aggregate-only, as before.
Every value is a chip, including the ones that were switches: a switch inside a horizontally
scrolling row is a drag target inside a drag surface. What was wrong with the old layout was not
its style but that up to eighteen controls shared a scroll container with the preview, so reaching
anything below the fold scrolled the preview out of sight — you could not see what you were
changing.

**The preview is a fixed stage.** It takes everything the chrome leaves rather than a hardcoded
`420.dp`, which is where the ratio inconsistency came from: at 16:9 that constant wasted about
190dp of vertical space, at 9:16 it left wide side gutters, and because the container wrapped its
height inside a scroller, changing ratio reflowed the whole page.

**The gesture fight is gone by construction, not by arbitration.** The preview hosts a live
`GoogleMap` — an `AndroidView` — and a map inside `verticalScroll` competes with the scroll
container for every vertical drag, with no `nestedScroll` or `requestDisallowInterceptTouchEvent`
anywhere to settle it. Whichever won was timing-dependent, which is exactly "sometimes the map
pans, sometimes the page moves". Taking the scroller out of the map's ancestry ends it. Rotation
and tilt gestures are now off as well: a rotated export frame is not something these screens offer,
and every extra gesture the map claims is one more way a pan can be misread.

**The camera re-fits when the ratio changes.** It was keyed on the route bounds alone, so changing
ratio reshaped the viewport and left the route framed for the previous shape. Fit padding is now a
fraction of the shorter edge rather than a fixed 80 or 72 pixels, which was a different proportion
of a tall frame than of a wide one.

### The export was a screenshot of the preview

Underneath all of it: `NativeSnapshotImageExporterImpl` accepted `ratioWidth, ratioHeight` and
**discarded them** — `finalW = mapSnapshot.width`. The exported PNG was a screenshot of the
on-screen preview map, so:

- **The ratio chips never set the export ratio.** They resized the on-screen box and the file
  inherited whatever that box measured. `AppConfig.HQ_IMAGE_WIDTH` and `HQ_IMAGE_RATIO_9_16` were
  passed in and thrown away.
- **Export resolution depended on screen density.** A "1080×1920" story came out around 708×1260 on
  a 3× phone and 472×840 on a 2×. Same setting, different file per device, always below the
  intended size.
- **The gesture bug corrupted output rather than merely annoying.** Camera position at snapshot
  time *was* the framing, so a drag the page stole half of shipped that framing. Scrolling to reach
  a toggle could silently re-frame an export.
- **The stats banner was sized from the image *width*** — 11% of the height on a 9:16 story, 36% on
  a 16:9 — while the on-screen preview drew it as `fillMaxHeight(0.2f)`. Preview and export
  disagreed at every ratio except square, and disagreed in opposite directions.
- The aggregate exporter had **no ratio parameter at all**.

Exports now render **offscreen at the ratio's true pixel size** (`exportPixelSize`: 1:1 → 1080×1080,
4:3 → 1440×1080, 16:9 → 1920×1080, 9:16 → 1080×1920), reusing the framing the user composed. The
framing travels as **geographic bounds, not zoom** — zoom means nothing without a viewport size,
since the same zoom on a larger surface shows more ground, so bounds are what reproduce a framing
at a different resolution.

`captureOffscreenMap` is the shared harness. The non-obvious part is that the `MapView` must be
attached to a real window: Maps' GL surface only renders once it joins the view hierarchy, and a
view that is merely measured and laid out calls back from `snapshot()` perfectly happily with blank
tiles. So it is added to the decor view and pushed outside the visible frame with `translationX` —
not `alpha = 0` and not `GONE`, both of which stop the surface rendering on some devices.

**Stroke widths and marker sizes are now fractions of the render width, not pixel constants.** A
10px polyline and a 64px marker are a hairline on a dense surface and a band on a sparse one, and
the screenshot export inherited whichever the device produced. As fractions, the preview and the
export draw the same picture at different resolutions, which is what a preview is for.

### Three defects the review of this change found

Worth recording, because two of them looked entirely correct in the diff.

**A crash on short screens.** Moving the preview out of the scrolling column and onto `weight(1f)`
introduced a case the old layout could not reach: the chrome above and below is fixed-height, so on
a short window at a large font scale the weighted child resolves to **zero**. `boundedPreviewSize`
requires positive bounds — correctly, a zero-sized preview is meaningless — and a `require` that
fires during composition is a crash, not a blank space. The call site now guards. The regression
test was checked by reverting the guard and confirming it fails, because a test that cannot fail
is not coverage.

**A silently blank export.** `captureOffscreenMap` attached the `MapView` to the host activity when
it could find one, and carried on without a window when it could not. That path does not throw:
`snapshot()` succeeds and returns blank tiles, so the user gets a white PNG and no error. It now
fails fast so the caller's retry path shows.

**The same O(pauses × points) scan twice, once on the main thread.** Filtering pause markers to
those on the trimmed route ran in the preview's `remember` and again inside `handleExport`, the
second time synchronously on the tap. Hoisted to one memoised function both use.

**Known, not fixed here:** the stats and legend banners sit flush to the bottom of the map and
partially cover the Google attribution logo, in both the preview and the exported file. Maps
Platform terms require it to stay visible. Tracked separately — the fix is to inset the banner, and
it changes the look of every exported image, so it is a deliberate design decision rather than a
side effect of this work.

**Also noticed, deliberately left:** `GoogleStaticApiImageExporterImpl` is dead code. It is the one
exporter that always honoured the requested ratio, and nothing has ever called it. Its unused
import is gone; whether to delete the class is a separate decision from this change.

### Three switches that should have been option sets

Each of these was a control whose only answer was yes or no, where the interesting question was
*which*.

**Markers.** One "Show markers" switch became five styles: **None**, **Start & finish** (the
existing green/cyan dots plus amber pause rings, still the default), **Finish only** for a shot
that is about where you ended up, **Black & white** which reads on satellite imagery and in print,
and **Pins** — Google's standard teardrop, the shape every map app has trained people to read.

The reductions are real reductions: pause rings draw only on Start & finish, because a monochrome
or finish-only picture that still sprinkled amber down the route would be neither. On the aggregate
preview the styles reduce differently — markers there carry a letter identifying *which* ride
starts at that point, so Finish only falls back to the lettered marker rather than vanishing and
leaving the legend referring to letters that appear nowhere on the map.

Collapsing this also removed a duplicate: `markerCircleIcon`, `markerPauseIcon` and
`letterMarkerIcon` were three separate builders across two files drawing the same shapes. There is
now one definition, used by the in-app maps as well as the exports, so the symbols really are the
same everywhere.

**"Hide places" did not hide places.** The style it applied turned off `poi` and transit label
icons and nothing else. In the Maps SDK's vocabulary a *POI* is a business or landmark — a mall, a
hospital, a park. Town and neighbourhood names are `administrative` labels and road numbers are
`road` labels, and neither is a POI. So the switch did exactly what it said in the SDK's terms
while leaving "Varanasi", "Harhua", "Rajatalab" and every highway shield on the picture.

Now three levels: **All labels**, **No place names** (which covers administrative names as well,
and is what the old switch was always trying to be), and **No text at all** — one global
`elementType: labels` rule rather than an enumeration of feature types, because an enumeration
keeps missing one. They are disabled rather than absent on satellite and terrain: the SDK only
applies styling to the normal basemap, so a style handed to those is silently ignored.

**The stats overlay** became a placement rather than a switch: **No panel**, **Bottom bar** (the
existing full-width band), **Bottom half**, **Top left** and **Top right**. "Off" is one of the
placements, so one decision is one control instead of a switch plus a position.

The geometry is defined once, as fractions of the frame, and both the Compose preview and the
`Canvas` exporter read the same rectangle — which is the direct fix for those two having drifted
into 20%-of-height versus 20%-of-width. `Bottom bar` keeps its exact historical geometry, since
changing it would silently alter the look of every export anyone has already made.

**Bottom half is anchored right, and the corner cards are inset**, which means four of the five
placements leave the Google attribution in the bottom-left corner clear. That does not close the
attribution issue — Bottom bar is still the default and still covers it — but it does give a way
out today.

### Design-system conformance

Audited after the fact rather than assumed, and it had gaps. Colour, typography, components and
motion were on the system — the tier crossfade uses effects tokens, correctly, since an alpha that
overshoots past 1 clips into a flash. Spacing was raw `dp` and the category rail had no explicit
minimum touch target, which the system states as 48dp with no exceptions; a short label in one
locale would have shrunk it below that with nothing failing.

Both are fixed. The remaining raw values (2, 6, 10, 12, 22dp) are component-internal metrics with
no token, and the spacing scale is deliberately four values — inventing tokens for them would
launder numbers rather than encode a rule. There is still **no lint rule banning hardcoded
spacing**; one was scoped in phase 2 and never written, which is exactly why this gap survived
until someone asked.

---

## Phase map

All five phases ship in 1.8.0 on one branch. They are phases of work, not of versioning.

| Phase | Scope | Status |
|---|---|---|
| 1 | Token layer, catalog, integrity tests | **done** |
| 2 | Components, `Toast` → `Snackbar` (40 of 40) | **done** |
| 3 | Screens re-composed, low risk to high — Settings first, Home last | **done** |
| 4 | Notifications, map theme + camera, adaptive navigation | **done** |
| 5 | Motion adoption, colour verification, Expressive decision, deprecation debt | **done** |

**What is still not covered:** there are no screenshot tests. Every visual claim in this document
rests on a person looking at a build — which is exactly why phase 5, changing the feel of two dozen
animations at once, needed a human to sign it off.
