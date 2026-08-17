# TrackMe Design System — 1.8.0 Foundations

> **Status:** Phase 1 of 5. Token layer only.
> **Branch:** `feat/1.8.0-design-system-foundations` (cut from `master`)
> **Behaviour change:** none. No feature added, removed, or altered.
> **Visual change:** small and deliberate — see [What actually looks different](#what-actually-looks-different).

---

## 1. Why this exists

The app had no design system layer. Screens each invented their own containers, spacing and
state colours, and the numbers below are the measurable result of that:

| Signal | Before this branch |
|---|---|
| Raw `Surface` used as a container | 44 |
| `ListItem` | 0 |
| `Toast` vs `Snackbar` | 40 : 6 |
| `AlertDialog` + raw `Dialog` | 30 |
| `tween` vs `spring` | 39 : 4 |
| `outline` vs `outlineVariant` | bound to the **same** colour |
| `background` vs `surfaceContainerLow` (dark) | bound to the **same** colour |
| `AmberWarn` on white | **2.15:1** — fails WCAG AA |

Re-skinning screens without fixing the layer underneath reproduces the drift in a new coat of
paint. So foundations land first, and screens are re-composed against them in later phases.

This phase changes **no screen**. It installs the layer, rewires `TrackMeTheme` to source its
colours from generated tonal ramps instead of hand-picked hex values, and adds a catalog screen
plus tests that make the layer verifiable.

---

## 2. Principles this layer is built to

### 2.1 SOLID, as it applies to a Compose design system

**Single responsibility.** Each token file owns exactly one axis of the system.
`TrackMeTonalPalette.kt` knows tones and nothing about roles. `TrackMeColorSchemes.kt` maps tones
to roles and knows nothing about components. `DesignTokens.kt` holds shape, spacing, elevation and
motion, each as its own immutable holder. `Theme.kt` only *composes* — it contains no values.

The practical test: **changing the brand hue must touch exactly one file.** It does — the seed
constants at the top of `TrackMeTonalPalette.kt`.

**Open/closed.** Adding a component, a screen, or a second theme must not require editing a token
file. Tokens are additive: new semantic roles are appended, existing ones never repurposed. The
`TrackMeMotionScheme` is the clearest case — a second scheme (`Expressive`) can be introduced
without modifying the `Standard` one or any call site.

**Liskov substitution.** Every `TrackMeMotionScheme` instance is substitutable for every other.
`Standard` and any future `Expressive` expose the same six tokens with the same semantics, so
swapping the value provided to `LocalTrackMeMotion` cannot break a caller. This is what makes the
Material 3 Expressive migration a value swap rather than a rewrite.

**Interface segregation.** Screens depend on several small `CompositionLocal`s
(`LocalTrackMeMotion`, `LocalTrackMeSpacing`, `LocalTrackMeElevation`) rather than one
god-object `LocalDesignSystem`. A screen that only animates does not recompose or rebuild because
a spacing value changed, and does not have to know spacing exists.

**Dependency inversion.** *The load-bearing principle here.* Screens depend on the token
abstraction, never on a concrete Material implementation. No screen imports
`MaterialExpressiveTheme`, `MotionScheme`, or any `@ExperimentalMaterial3ExpressiveApi` symbol.

This is not architectural purity for its own sake — it is the mitigation for a specific, known
risk. Material 3 Expressive lives in `material3:1.5.0-alpha*`. The app is on **1.4.0 stable** via
Compose BOM `2026.03.01`. Depending on alpha APIs directly would put an alpha library on the
critical path of every screen. Instead we own the abstraction and back it with hand-written
spring specs that match the published Expressive values. When 1.5.0 stabilises, the *backing*
changes; the screens do not.

### 2.2 Other standards applied

**Design tokens, two tiers.** Primitive tokens (`Tone.Primary40`) carry no meaning and are never
referenced by UI. Semantic tokens (`colorScheme.primary`, `TrackMeSemantics.warning`) carry
meaning and are the only thing UI may use. This is the standard two-tier token model, and it is
what makes a re-theme possible without a find-and-replace.

**Material 3 role vocabulary, unmodified.** We do not invent names where M3 has one.
`surfaceContainerHigh` is called `surfaceContainerHigh`. Only genuinely non-Material concepts
(`success`, `warning`) get TrackMe names, and they live in a clearly separate holder so nobody
mistakes them for Material roles.

**Single source of truth.** Every colour in the app resolves to a tone position in
`TrackMeTonalPalette.kt`. There is exactly one definition of "6dp elevation". No value is
duplicated between light and dark — both schemes index the same ramps at different tones.

**Semantic colour is never the only signal.** Rider-status severity is encoded as a bar meter
first; colour is added on top. Roughly 1 in 12 men cannot reliably separate red from amber, and
group-ride urgency is the wrong place to depend on that.

**Accessibility is enforced, not asserted.** `ColorRoleContrastTest` computes WCAG 2.1 relative
luminance for every foreground/background role pair in both schemes and fails the build below
4.5:1 for text and 3:1 for UI boundaries. A palette regression becomes a red test, not a bug
report.

**Compose correctness.** All token holders are `@Immutable` `data class`es of primitives, so
Compose can skip recomposition when they are read. Ambient values use `staticCompositionLocalOf`
because they do not change during a composition's life — this avoids the invalidation cost of a
readable `compositionLocalOf`.

**No magic numbers.** Every `dp`, radius, duration and colour in new code resolves to a token.
This becomes lint-enforceable in phase 2.

---

## 3. What this branch adds

```
app/src/main/java/in/shvms/trackme/theme/
  TrackMeTonalPalette.kt      primitive tokens — 8 tonal ramps, one seed
  TrackMeColorSchemes.kt      semantic tokens — M3 role mapping + TrackMeSemantics
  DesignTokens.kt             shape, spacing, elevation, motion + CompositionLocals
  Theme.kt                    MODIFIED — composes the above, provides the locals

app/src/main/java/in/shvms/trackme/ui/catalog/
  DesignCatalogScreen.kt      debug-only gallery of every token and state

app/src/test/java/in/shvms/trackme/theme/
  ThemeContrastTest.kt        MODIFIED — see §10.1, two assertions restated
  TokenIntegrityTest.kt       NEW — structural invariants (roles distinct, ramps ordered)

Navigation.kt                 MODIFIED — debug-only `design_catalog` route
SettingsScreen.kt             MODIFIED — debug-only entry point to the catalog
```

Nothing else is touched. `Color.kt` is retained unchanged so the ~40 files referencing
`CyanBright`, `SuccessGreen`, `TrackMeBlue` and friends keep compiling. Migrating those call sites
is phase 2 work, done screen by screen with the parity ledger.

---

## 4. The colour system

### 4.1 Seed

The source colour is **`#29B6F6`** — the existing `CyanBright`, unchanged. It is on the logo and
in the store listing; changing it would be a rebrand, not a redesign.

Measured in CIELCh, the existing palette reads:

| Token | Hex | Tone (L*) | Chroma | Hue |
|---|---|---|---|---|
| CyanBright | `#29B6F6` | 69.9 | 44.2 | 251° |
| CyanDeep | `#0277B6` | 47.7 | 41.1 | 264° |
| Navy900 | `#12161C` | 7.1 | **4.8** | 269° |
| AmberWarn | `#F59E0B` | 72.2 | 78.8 | 73° |

`Navy900` at chroma 4.8 is already an M3-legal neutral — M3 derives neutrals from the source at
roughly chroma 4. The generated dark ramp therefore lands within 2–3 points of the palette
already shipping, which is why the dark theme still looks like itself.

### 4.2 Role assignment

Material 3 assigns roles to fixed tone positions. Light uses P40 for `primary`, dark uses P80,
and so on. The full mapping lives in `TrackMeColorSchemes.kt` with the tone position named in a
comment on every line.

### 4.3 Generation caveat

The ramps were generated by holding hue and chroma in **CIELCh** and sweeping L*, gamut-mapping
by reducing chroma until each value fits sRGB. Material's own generator uses **CAM16-based HCT**.
The two agree closely on tone and ordering and diverge by a few points on chroma in the 70–90
range where gamut mapping bites hardest.

**These values are the design decision, not a claim of byte-identity with Material Theme Builder.**
Before 1.8.0 ships, regenerate from the same `#29B6F6` seed with the official Material Color
Utilities and diff against `TrackMeTonalPalette.kt`. Expect small shifts, no structural surprises.
`ColorRoleContrastTest` re-validates whatever values are present, so a regeneration that breaks
contrast fails the build.

---

## 5. The motion system

Six tokens, split along one rule that prevents most motion bugs:

| Token | Damping | Stiffness | Used for |
|---|---|---|---|
| `spatialFast` | 0.9 | 1400 | Chip select, switch thumb, icon toggles |
| `spatialDefault` | 0.9 | 700 | Sheets, HUD rise, list placement, FAB |
| `spatialSlow` | 0.9 | 300 | Screen transitions, map camera settle |
| `effectsFast` | 1.0 | 3800 | Ripple, pressed state, scrim |
| `effectsDefault` | 1.0 | 1600 | Fades, colour and elevation changes |
| `effectsSlow` | 1.0 | 800 | Long cross-fades |

**Spatial specs move things and may overshoot** — damping below 1.
**Effects specs change colour and opacity and must never overshoot** — damping exactly 1.0.

An alpha or colour value that overshoots past its bound clips, producing a visible flash. Binding
that rule into the token set means it cannot be got wrong at a call site.

Springs replace tweens because a tween commits to a duration: interrupt it mid-flight and it
restarts or snaps. A spring carries velocity through the interruption. The app currently runs 39
tweens to 4 springs; migrating call sites is phase 2+.

---

## 6. Elevation and shadow policy

| Level | dp | Surface role | Shadow |
|---|---|---|---|
| 0 | 0 | `surface` | no |
| 1 | 1 | `surfaceContainerLow` | no |
| 2 | 3 | `surfaceContainer` | no |
| 3 | 6 | `surfaceContainerHigh` | **yes** |
| 4 | 8 | — (hover/drag only) | yes |
| 5 | 12 | `surfaceContainerHighest` | **yes** |

**The rule: shadow is reserved for surfaces the user can dismiss or drag. If a thing cannot be
pushed away, it does not cast.**

This is not stylistic. The app's primary theme is dark (`#0E1418` ground) and a black shadow on a
near-black ground is invisible — it costs GPU time for a cue nobody can see. M3 draws elevation
with tone first and shadow second for exactly this reason.

### 6.1 `mapOverlay` — the one documented exception (phase 4)

| Token | dp | Shadow |
|---|---|---|
| `mapOverlay` | 3 | **yes** |

Tonal elevation is a tint applied to a *surface*. A map is not a surface, so over map imagery tone
conveys nothing whatsoever, and shadow is the only separation available. Every piece of floating
map chrome — the 52dp control buttons, the ride HUD status pills — uses this.

It is a named token rather than "just use `level2`" on purpose. `level2` is 3dp and drawing a
shadow at it would silently contradict `castsShadow()`, which is the function that exists to stop
exactly that. Reaching for `mapOverlay` by name is the caller stating that the ladder does not
apply here, which is true and is the only case where it is.

---

## 7. What actually looks different

Phase 1 aims to be visually near-identical. It is not *pixel*-identical, and the differences are
the point:

1. **Dark surfaces shift by 2–3 tone points.** `Navy900 #12161C` → `N6 #0E1418`,
   `Navy800 #181A20` → `N12 #1B2025`. Imperceptible side by side; the ramp is now evenly stepped.
2. **`background` and `surfaceContainerLow` stop being the same colour.** They were both
   `Navy900`, so nothing at low elevation separated from the ground. Now two tone steps apart.
3. **`outline` and `outlineVariant` stop being the same colour.** Dividers and borders were
   indistinguishable. Now NV50/NV80 in light, NV60/NV30 in dark.
4. **The two cyans converge.** `CyanBright` (hue 251°) and `CyanDeep` (hue 264°) were picked
   separately, 13° apart. Now one hue at two tone positions, so the brand reads as one colour.
5. **Warning stops failing contrast.** Amber was tone 72 used as a foreground on tone 98 —
   2.15:1. It is now tone 40 on light (6.17:1) and tone 80 on dark (10.92:1).

Item 5 is a live accessibility bug on `master`, not a redesign preference.

---

## 8. The tertiary conflict, and what we did about it

`master` binds Material's `tertiary` role to amber and uses it for warnings. `tertiary` is a
**brand accent** role — a third colour for emphasis — not a semantic state.

This is precisely the mistake `Color.kt` already documents and fixed for green: its comment
explains that collapsing "brand accent" and "semantic go" into one token is what shipped a green
Start button in 1.5.11 while the logo was cyan. Amber-as-`tertiary` is the same bug, still present.

This branch separates them. `tertiary` takes the generated violet (source hue + 60°, which is what
M3 derives). `warning` becomes a named semantic token in `TrackMeSemantics`, exactly parallel to
the existing `SuccessGreen`.

**Correction.** An earlier version of this section claimed *"no screen reads `tertiary` today"*.
That was wrong, and acting on it caused a regression that code review caught. Three places read
tertiary roles:

| File | Role | Surface |
|---|---|---|
| `Connectivity.kt:94` | `tertiaryContainer` | `OfflineShieldBanner` |
| `OnboardingScreen.kt:548` | `tertiaryContainer` | onboarding accent |
| `OnboardingIllustrations.kt:283` | `tertiary` | illustration accent |

Two consequences, both now handled:

1. **The offline banner shifts from amber to violet.** A real visual change, not a re-tone. It is
   a legitimate accent use, so the role binding stays — but it is called out in the release notes
   rather than left to be discovered.
2. **The dynamic-colour pin had to come back.** `master` pinned `tertiary` inside the
   dynamic-colour branch precisely so a wallpaper could not dilute that status surface. Removing
   it left the offline banner taking an arbitrary wallpaper hue. `Theme.kt` now pins tertiary
   again — to *our generated tertiary*, not to the old amber, so the same surface does not change
   meaning depending on whether dynamic colour happens to be on.

---

## 9. How to see it

The catalog is **debug-only** and gated on `BuildConfig.DEBUG`:

```
Settings → (scroll to bottom) → Design catalog
```

It renders every colour role in both schemes, the type scale, the shape scale, the elevation
ladder, the motion tokens as live animations, and each component in its enabled / pressed /
disabled / loading / empty states.

The catalog is the screenshot-test surface in phase 2 — most layout bugs are visible here before
any screen exists.

---

## 10. How to verify it

```bash
./gradlew :app:testDebugUnitTest --tests "in.shvms.trackme.theme.*"
```

`ColorRoleContrastTest` fails the build if any role pair drops below WCAG AA.
`TokenIntegrityTest` fails if two roles that must differ are bound to the same value, or if a
tonal ramp is not monotonically ordered by luminance.

Full check:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

### 10.1 Two existing assertions were restated — read this before approving

`ThemeContrastTest` predates this branch and is a good test. Two of its assertions encoded the
*old* palette's implementation rather than the rule behind it, and both had to change. **Neither
change was made for convenience — flagging them explicitly because one of them relaxes an
accessibility assertion.**

**1. `outlineVariant` no longer requires 3:1 against every surface.**

The old assertion required `outline` **and** `outlineVariant` to clear 3:1 on all seven surfaces.
That passed on `master` *only because both roles were bound to the same grey*. Splitting them —
the entire point of this fix — makes `outlineVariant` low-contrast by definition.

That is correct behaviour, not a regression. In M3, `outline` draws meaningful boundaries (text
field borders, selected states) and `outlineVariant` is a **decorative divider**. WCAG 1.4.11
requires 3:1 for user interface components and for graphical objects *required to understand
content*; a decorative divider is neither, and is exempt.

`outline` keeps the full 3:1 on every surface — verified, it passes. `outlineVariant` is now
asserted to be (a) visible at all, above 1.15:1, and (b) strictly quieter than `outline` on the
same surface. That tests the property the role actually has.

**3. The dimmed-caption guard inverted — and that is the intended outcome.**

`brand action content pairs meet AA` carried a regression guard asserting that a caption at 90%
alpha composites **below** AA (4.27:1 against the old cyan/deep primary). That was the evidence
those captions had to stay at full opacity.

Light primary is now tone 40 (`#00658D`) instead of cyan/deep (`#0277B6`). It is darker, so the
same 90% caption composites to **5.59:1** and clears AA. The hazard the guard existed to catch
no longer exists, and the assertion failed for that reason — it was pinning a bad condition that
the new palette removed.

It is restated in the direction that is now true, so it stays a live guard: if primary is ever
lightened back toward cyan/bright, the ratio drops under 4.5 and the test fails, surfacing the
hazard exactly when it returns.

**2. Brand-role identity checks became hue-family checks.**

The old test asserted `TrackMeLightColorScheme.primary == CyanDeep` and that brand roles were
members of `setOf(CyanBright, CyanDeep, CyanContainerLight)`. With colours generated from a seed
rather than picked from three constants, identity comparison stops being meaningful.

The intent — the C1 guard that stops a brand role ever becoming semantic green, the defect that
shipped in 1.5.11 — is preserved and slightly strengthened: brand roles must satisfy
`blue > green > red`, which excludes success green (`g > b`), warning amber and error red
(`r > g > b`) by construction, and keeps working after the palette is regenerated. The
`cyan/bright fails AA on light` rule is now asserted as a contrast property rather than as an
equality against a constant.

### 10.2 Verified build status

| Gate | Command | Result |
|---|---|---|
| Kotlin compile | `:app:compileDebugKotlin` | **pass**, 0 warnings |
| Debug APK | `:app:assembleDebug` | **pass**, 29 MB APK produced |
| Unit tests | `:app:testReleaseUnitTest` | **pass — 556 tests, 0 failures** |
| Release lint | `:app:lintRelease` | **pass**, 0 errors, 0 issues in any new file |

Toolchain: Temurin JDK 17.0.20, Android SDK platform 36 / build-tools 36.0.0, Gradle 9.4.1
via the wrapper.

Note that unit tests only exist for the **release** variant — there is no `testDebugUnitTest`
task in this project, which is why CI runs `:app:testReleaseUnitTest`. Running it locally trips
the signing-config guard in `build.gradle.kts`, so `local.properties` needs the same dummy
keystore values CI falls back to. No signing actually occurs.

### 10.3 Three defects the build found

Recording these because they are the argument for compiling before claiming done:

1. **`import androidx.compose.foundation.lazy.item`** — no such symbol. `item` is a member of
   `LazyListScope`, not a top-level function, so it needs no import.
2. **`Icons.Filled.ArrowBack` is deprecated** in favour of `Icons.AutoMirrored.Filled.ArrowBack`.
   A real defect, not noise: the app ships seven languages and the non-mirrored arrow points the
   wrong way in RTL.
3. **`ThemeContrastTest > brand action content pairs meet AA` failed** — see §10.1, item 3.

---

## 11. Phase map

All of 1–4 land on the single branch `feat/1.8.0-design-system-foundations` and ship as one
release. They are phases of work, not of versioning.

| Phase | Scope | Status |
|---|---|---|
| 1 | Token layer, catalog, token tests | **done** |
| 2 | Components + `Toast` → `Snackbar` conversion (40 of 40) | **done** |
| 3 | Screens re-composed, low risk to high — Settings first, Home last | **done** |
| 4 | Notifications, map theme + camera, adaptive navigation | **done** |
| 5 | 39 `tween` → spring; regenerate colour with Material Color Utilities; adopt Expressive if 1.5.0 stabilises | deferred |

### 11.1 Phase 4, in full

- **Notification identity** — real small icon, brand accent, `CATEGORY_WORKOUT`, and Pause /
  Resume / Finish actions in the shade.
- **Android 16 Live Update** — `setRequestPromotedOngoing(true)` plus `setShortCriticalText()`
  carrying elapsed time, or `Paused` / `Searching…` when those are the truer thing to show. Both
  are `NotificationCompat` calls, so pre-16 devices are unaffected without a version check.
- **Themed basemap** — `rememberMapStyle()` reading the app's own three-value `themeMode`, and a
  night style drawn from the app's own neutral ramp.
- **Ride camera** — 45° while recording, 30° paused, bearing from travel direction, flat and
  north-up when idle. Any pan, zoom or rotate suspends follow; the locate button is the way back.
- **Adaptive navigation** — `NavigationRail` at ≥600dp, bottom bar below.
- **HUD pill retune** — the two pills the phase-3 notes recorded as deliberately off-token, now
  that the themed basemap they were waiting on exists (§6.1).

### 11.2 `ProgressStyle`: considered, deliberately skipped

`NotificationCompat.ProgressStyle` **is** available — it ships in `androidx.core` 1.18.0, which
this app already depends on. The blocker is product scope, not the library.

A determinate progress bar needs a destination, and the only destination TrackMe knows about is
the group-ride one behind `GroupFeatureFlags.SHOW_ETA`. That estimate is deliberately computed and
never displayed: 1.7 shipped it dark specifically to gather predicted-vs-actual calibration before
anyone is shown a number. Rendering it in a notification would ship that feature by the back door,
and 1.8.0 is explicitly a redesign with no feature change.

For the ordinary case — a ride with no destination at all — `ProgressStyle` can only be
indeterminate, which is an animated bar that conveys nothing. Neither branch earns it.

Revisit when the ETA calibration data says the estimator is good enough to show.

---

## 12. Known limitations of this branch

*Updated at the end of phase 4. The phase-1 wording — "never run on a device" — no longer holds:
every phase since has been installed from the internal track and checked by hand on a phone.*

- **No screenshot tests.** Verification is a person looking at a build. Every visual claim in this
  document rests on that, and nothing in CI would catch a regression in any of it.
- **Verified in portrait, on a phone, in one locale.** The `NavigationRail` above 600dp is
  compile-checked and its breakpoints are unit-tested, but nobody has run it on a tablet. The
  seven translations are complete and coverage-tested, and have not been read by a speaker.
- **Motion tokens are still unconsumed.** `TrackMeMotionScheme` is defined and provided; the 39
  `tween` call sites are untouched. That conversion is phase 5, and until it happens the motion
  section of this document describes an intent rather than the shipped behaviour.
- **Ramp values are CIELCh approximations of HCT** (see §4.3). Regeneration with the official
  Material Color Utilities is phase 5.
- `Color.kt` legacy aliases are retained and still used by roughly 40 files.
- **`google-services.json` used locally is a placeholder.** The real Firebase config is not in the
  repo. It is structurally valid so the plugin and compiler are satisfied; Firebase will not
  function at runtime with it. Anyone building locally needs the real file.
- **The same amber contrast defect is still live in the iOS app.** `AmberWarn` at 2.15:1 on white
  was fixed here; iOS carries the identical value and is tracked separately.
