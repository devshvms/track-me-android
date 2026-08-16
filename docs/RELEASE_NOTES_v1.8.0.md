# TrackMe 1.8.0 — release notes

Opens the Material 3 redesign arc. This release is **phase 1 of 5**: the design system
foundations. No feature is added, removed or altered — what lands is the token layer every later
phase is built on, plus the accessibility defect that layer immediately exposed.

Full specification: [`DESIGN_SYSTEM_1.8.md`](DESIGN_SYSTEM_1.8.md).

---

## For the store listing

Groundwork, plus one fix people will actually notice.

- **Warning text is readable again.** The amber used for warnings failed the accessibility
  contrast minimum on light backgrounds. It now clears it comfortably in both themes.
- **Cleaner surfaces.** Cards, sheets and the navigation bar now sit at genuinely different
  levels instead of blending into the background.
- **Dividers and borders no longer look identical**, so lists and grouped settings are easier to
  scan.

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
