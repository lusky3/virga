# Phase 0 — Design System Foundation

**Theme:** *One authoring hand.* **Goal:** stop assembling the app screen-by-screen. Establish the visual language
(`core:designsystem`) that every later phase consumes. Nothing user-facing dramatically changes yet, but afterward the
app has a real identity and a single place to evolve it.

> Prereqs: read [`../../BRAND.md`](../../BRAND.md) fully. This phase *implements* BRAND §4–§15.
> Pre-production: schema/version concerns don't apply here (no DB changes).

Existing seed to build on: `core/ui` already holds `EmptyState` and `ToggleRow`. The current theme lives in
`app/src/main/kotlin/app/lusk/virga/ui/theme/{Theme,Color,Type}.kt`. Compose stack: `compose-bom` (Expressive-capable),
`material3`, `material3-adaptive-navigation-suite`, `material-icons-extended` (see `gradle/libs.versions.toml`).

---

## WS0.1 — Create the `core:designsystem` module

**Why:** features must depend on one design module, not the `app` theme package (which they can't reach) or scattered
`core:ui`. **Current:** theme is in `:app`; shared components in `:core:ui`. **Target:** a `:core:designsystem` module
that owns theme + tokens + shared components; `:core:ui` is absorbed into it (or `:core:ui` re-exports it during
migration).

**Steps:**
1. Add module `core/designsystem/` with `build.gradle.kts` applying `virga.android.library` + `virga.android.compose`
   convention plugins (mirror `core/ui/build.gradle.kts`). Namespace `app.lusk.virga.core.designsystem`.
2. Register in `settings.gradle.kts`.
3. Move `app/.../ui/theme/{Theme,Color,Type}.kt` into `core/designsystem/.../theme/`. Move `EmptyState`/`ToggleRow`
   from `core/ui` into `core/designsystem/.../component/` (or keep `core:ui` depending on `core:designsystem` and
   re-exporting, to minimize churn — pick one and note it).
4. Update `:app` to depend on `:core:designsystem`; update every feature module's `build.gradle.kts` that used
   `:core:ui` to `:core:designsystem`. Fix imports (`app.lusk.virga.ui.theme.VirgaTheme` → new package; `core.ui.*`
   component imports).
5. `MainActivity` references `VirgaTheme` from the new package.

**Files:** new `core/designsystem/*`; modified `settings.gradle.kts`, `app/build.gradle.kts`, all `feature/*/build.gradle.kts`,
`MainActivity.kt`, every file importing the theme or `core.ui` components.

**Acceptance:** project builds; app renders identically; no feature imports `app.lusk.virga.ui.theme.*` or defines its
own theme. **Impact:** high (enabler). **Effort:** M.

---

## WS0.2 — Full branded color schemes + dynamic-color-as-tint

**Why:** BRAND §4.2/§4.3 — today `LightColors` is a 3-role stub and dynamic color (default on) erases the brand on
Android 12+, so Virga has no identity. **Current:** `theme/Theme.kt · LightColors` sets only `primary/secondary/error`;
`VirgaTheme(dynamicColor = true)` defaults dynamic on. **Target:** complete brand schemes for all roles; branded scheme
is the default; dynamic color is an opt-in *tint* that harmonizes, never replaces.

**Steps:**
1. Generate complete `lightColorScheme`/`darkColorScheme` from `VirgaBlue`(primary)/`VirgaTeal`(secondary) seeds
   (Material Theme Builder export, or programmatic tonal palettes). Fill every role: primary/secondary/tertiary +
   containers, surface + `surfaceContainer*`, outline, surfaceVariant, inverse*, error*. Keep the existing dark
   primary tones in `Color.kt` as the dark scheme's anchors.
2. Change `VirgaTheme` default to `dynamicColor = false` (brand-first). Read the user's "Match my wallpaper"
   preference (WS adds it to Settings) to enable dynamic.
3. When dynamic is enabled, **harmonize** rather than replace: keep brand primary, or blend wallpaper accents into
   secondary/tertiary via `Color.harmonize`/`MaterialColors.harmonize`. Document the exact policy chosen in `Theme.kt`
   KDoc + BRAND §4.3.
4. Add a Settings toggle "Match my wallpaper colors" (default off) persisted in `core:datastore` (`AppPreferences`),
   wired into `VirgaTheme` via `AppThemeViewModel`.

**Files:** `core/designsystem/.../theme/{Theme,Color}.kt`; `core/datastore/.../AppPreferences.kt` +
`PreferencesRepository`; `app/.../AppThemeViewModel.kt`; `feature/settings/.../SettingsScreen.kt`.

**Acceptance:** with dynamic off (default) the app is unmistakably blue/teal across all surfaces in light + dark; with
the toggle on, it harmonizes without losing brand primary; contrast passes (BRAND §4.7). **Impact:** high.
**Effort:** M.

---

## WS0.3 — Semantic colors via `LocalVirgaColors` + status glyphs

**Why:** BRAND §4.4/§10 — success is currently faked onto `tertiaryContainer` in `SyncStatusBadge`; no warning/running
roles. **Target:** first-class semantic colors + the canonical status mapping.

**Steps:**
1. Define `data class VirgaSemanticColors(success, onSuccess, successContainer, onSuccessContainer, warning, …,
   running, info, …)` and `val LocalVirgaColors = staticCompositionLocalOf<VirgaSemanticColors> { error(...) }`.
2. Build light + dark instances from `VirgaSuccess`/`VirgaSuccessDark` + new warning ambers + `VirgaTeal` (running) +
   `VirgaBlue` (info). Provide them in `VirgaTheme` via `CompositionLocalProvider`.
3. Rework `feature/sync/.../SyncStatusBadge` to the BRAND §10 table: each state → `LocalVirgaColors` color + glyph +
   label. This is the *only* status renderer.
4. Grep for any other ad-hoc status coloring (`tertiary`, `secondary` used for success/warning) and route through
   `LocalVirgaColors`.

**Files:** new `core/designsystem/.../theme/VirgaSemanticColors.kt`; `theme/Theme.kt`; `SyncStatusBadge.kt`; callers.

**Acceptance:** success is a true green (light+dark), conflicts are amber, running is teal — everywhere, with glyph +
text; no semantic color via M3 `tertiary`/`secondary` shoehorns. **Impact:** medium. **Effort:** S–M.

---

## WS0.4 — Spacing, shape, gradient tokens

**Why:** BRAND §7/§8/§4.5 — eliminate hardcoded `.dp` and inline gradients. **Target:** token objects consumed everywhere.

**Steps:**
1. `object VirgaSpacing { val xs=4.dp; sm=8; md=16; lg=24; xl=32 }` (BRAND §8).
2. `VirgaShapes` (or extend M3 `Shapes`) with the corner scale; expose hero-card shape.
3. `object VirgaGradients { val hero: Brush; val heroDark: Brush }` (blue→teal per BRAND §4.5) — provide light/dark via
   a `@Composable` accessor or `LocalVirgaColors`.
4. (Optional now, enforced later) a lint/detekt rule or PR-checklist note flagging raw `.dp` literals + `Color(` in
   feature modules.

**Files:** new `core/designsystem/.../theme/{VirgaSpacing,VirgaShapes,VirgaGradients}.kt`.

**Acceptance:** tokens exist and are used by WS0.6 + Phase 1; gradients defined once. **Impact:** medium. **Effort:** S.

---

## WS0.5 — Material 3 Expressive theme + motion scheme + display font

**Why:** BRAND §5/§12 — flip the whole app from default M3/crossfade to Expressive shapes + physics motion + a real
type voice in one move. **Current:** plain `MaterialTheme`; `Type.kt` has no external font (FontWeight-only) but a
documented single `fontFamily` hook. **Target:** `MaterialExpressiveTheme` wrapping the app with a `motionScheme`;
one display typeface for Display/Headline.

**Steps:**
1. Wrap content in `MaterialExpressiveTheme(colorScheme=…, typography=…, motionScheme=VirgaMotionScheme, shapes=…)`
   inside `VirgaTheme`. (Confirm the Expressive entry point in the pinned BOM; it's `androidx.compose.material3`
   Expressive API.)
2. Define `VirgaMotionScheme` (spring/physics defaults) + named motion tokens for nav, shared-element, list-enter,
   precipitation, success-tick (BRAND §12). Expose durations/easing so features never hardcode `tween`.
3. Add a variable display font to `app`/`core:designsystem` `res/font/`, wire it via the `fontFamily` hook in `Type.kt`
   for Display + Headline styles only; Body/Label stay default. Record the chosen font in BRAND §5.
4. Implement a `reduceMotion` helper reading the system animation setting; motion tokens degrade to near-instant fades.

**Files:** `theme/{Theme,Type}.kt`; new `theme/VirgaMotionScheme.kt`; `res/font/*`.

**Acceptance:** Expressive components are usable (e.g., wavy progress, button groups); a display font shows on hero/
headline text; motion tokens exist; reduce-motion respected. **Impact:** high. **Effort:** M.

---

## WS0.6 — `VirgaCard` + migrate existing cards

**Why:** BRAND §11 — today cards are inconsistent (`Surface` in `SyncTaskCard`, `ElevatedCard` in `RemoteCard`, raw
`Card`/rows elsewhere). **Target:** one `VirgaCard` with `default`/`selected`/`active` states; everything routes through
it.

**Steps:**
1. Build `VirgaCard(state: VirgaCardState = Default, onClick, onLongClick, modifier, content)` using `surfaceContainer*`
   tonal elevation, `VirgaShapes` medium, `VirgaSpacing.md` inner padding; `selected` → `secondaryContainer` tint +
   selection affordance; `active` → `running`-tinted leading strip/edge (used by Phase 1 live sync).
2. Migrate `SyncTaskCard`, `RemoteCard` (`RemoteComponents`), sync-history rows, conflict rows, run rows to `VirgaCard`.
3. Keep `combinedClickable` semantics (tap/long-press) and accessibility labels intact during migration.

**Files:** new `core/designsystem/.../component/VirgaCard.kt`; `SyncTaskCard.kt`, `RemoteComponents.kt`,
`SyncHistoryScreen.kt`, `ConflictsScreen.kt`, `SyncTaskSummaryScreen.kt`.

**Acceptance:** every list card in the app is a `VirgaCard`; selected/active states render consistently; no raw
`Surface`/`Card` card construction remains in features. **Impact:** medium. **Effort:** M.

---

## Phase 0 exit criteria
- `:core:designsystem` exists; no feature defines theme/color/spacing.
- App has a clear blue/teal identity in light + dark with dynamic-as-opt-in-tint.
- `LocalVirgaColors` + `SyncStatusBadge` are the only status-color path.
- Spacing/shape/gradient/motion tokens exist and are used.
- Expressive theme + display font + motion scheme live; reduce-motion respected.
- One `VirgaCard` powers every card.
- All Definition-of-Done items (ROADMAP) pass; on-device verified light + dark.
