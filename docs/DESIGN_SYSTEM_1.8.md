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
the existing `SuccessGreen`. Nothing in the UI changes yet — no screen reads `tertiary` today —
but the roles are now honest and phase 2 can bind warnings correctly.

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

### 10.2 What was verified without a JVM

All 104 colour assertions in `ThemeContrastTest.assertSchemeContrast` were replicated in pure
colour math and run against the proposed schemes before the code was written. Result: **104/104
pass.** That covers every text pair, every fixed-role pair, and every outline pair across both
schemes and all seven surfaces.

That is genuinely verified. **Everything else in this branch is not** — see §12.

---

## 11. Phase map

| Phase | Scope | Status |
|---|---|---|
| **1** | **Token layer, catalog, token tests** | **this branch** |
| 2 | ~22 components + lint rules banning raw `Surface`, `Toast`, hardcoded `Color(0x…)` | next |
| 3 | Screens re-composed, low risk to high — Settings first, Home last | |
| 4 | Notifications, adaptive layouts, map camera, widgets | |
| 5 | Regenerate colour with Material Color Utilities; adopt Expressive if 1.5.0 is stable | |

Each phase ends somewhere a release could be cut.

---

## 12. Known limitations of this branch

- **Not compiled.** The environment this was authored in has no JDK and no Android SDK. Every
  line is unverified. First compile is expected to surface import and signature errors.
- Ramp values are CIELCh approximations of HCT (see §4.3).
- `Color.kt` legacy aliases are retained and still used by ~40 files. That migration is phase 2.
- No component library yet — the catalog renders raw Material components against the new tokens.
- Motion tokens are defined and provided but not yet consumed by existing screens; the 39 `tween`
  call sites are untouched.
