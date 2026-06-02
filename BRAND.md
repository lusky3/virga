# Virga — Brand & Design System

> **This is the single source of truth for how Virga looks, moves, and speaks.**
> Every screen, component, color, animation, and word must trace back to this document.
> If something on screen isn't covered here, that's a gap to fix here first — not an excuse to improvise.
>
> Audience: any contributor (human or agent) touching the UI. Read this before writing a Composable.
> Companion docs: [`docs/UX_VISION.md`](docs/UX_VISION.md) (product/IA), [`docs/ROADMAP.md`](docs/ROADMAP.md) (execution plan).

---

## 0. How to use this document

- **Tokens, not literals.** Never hardcode a `Color(0x…)`, a `dp` spacing value, a duration, or a raw hex in a feature
  module. Pull from the design-system tokens (see §11). If a token is missing, add it here + in code, then use it.
- **Material 3 Expressive is the baseline**, not classic M3. We target the Expressive APIs shipped in the
  `compose-bom` the project pins (currently `2026.05.01`).
- **Consistency beats cleverness.** A screen that follows these rules and is "boring" is correct. A bespoke
  one-off that ignores them is a bug, even if it looks nice in isolation.
- When this doc and a feature disagree, **this doc wins** — open a change here to evolve it deliberately.

---

## 1. Brand essence

**Virga is the rclone client people trust with the only copy of their photos.**

A *virga* is rain that evaporates before it reaches the ground — weather in motion, visible and atmospheric.
That is the brand metaphor: **"Weather for your data."** Data moving between your device and the cloud should
feel like a calm, legible, living atmospheric process — never a static spinner, never a scary technical wall.

### Personality
| We are | We are not |
|---|---|
| Calm, trustworthy, quietly confident | Loud, gamified, alarmist |
| Atmospheric, alive, in-motion | Flashy, busy, distracting |
| Beginner-safe by default | Dumbed-down or capped |
| Deeply capable on demand | Intimidating or jargon-first |
| Honest about what's happening to your files | Vague, hand-wavy, hiding state |

### The one-sentence promise
> *Connect an account, pick a folder, and have a working backup in under a minute — without ever seeing the word
> "bisync" — while every rclone capability remains one deliberate tap away.*

This duality (**beginner surface / power-user depth**) is the product's core tension. The visual system must make
both states feel like the *same* calm app, never two apps bolted together. See progressive disclosure in
[`docs/UX_VISION.md`](docs/UX_VISION.md).

---

## 2. Voice & UX writing

**Tone:** plain, reassuring, second person ("your files", "we'll back this up on Wi-Fi"). Explain consequences,
especially destructive ones, in human terms. Never expose rclone jargon at Tier 0–1; introduce it with a plain-English
gloss when power features are revealed.

### Product vocabulary (use these words everywhere — no synonyms)
| Concept | Say | Never say |
|---|---|---|
| A configured cloud account | **Remote** (beginners: "Cloud account") | "backend", "rclone remote" at Tier 0 |
| A sync configuration | **Sync task** (or just **Task**) | "job", "config" |
| One execution of a task | **Run** | "execution", "invocation" |
| Phone → cloud | **Upload** | "push" |
| Cloud → phone | **Download** | "pull" |
| Two directions | **Two-way sync** | "bisync" (Tier 0–1); gloss it at Tier 2+ |
| Delete-extraneous mirror | **Mirror** (with a warning) | "sync --delete" |
| Local folder | **Source** | "path" |
| Cloud folder | **Destination** | "remote path" |

### Microcopy rules
- **Buttons are verbs**: "Back up now", "Add cloud account", "Preview changes". Not "OK", "Submit".
- **Destructive copy names the blast radius**: "Delete 3 files on Google Drive that aren't on this phone?" — never
  "Are you sure?".
- **Empty states teach**: every empty state explains the concept *and* offers the next action (see §12).
- **Errors are actionable**: say what failed and the one thing to do next.

---

## 3. App icon & logo direction

Launcher: `app/src/main/res/mipmap-anydpi-v26/ic_launcher` (minSdk 26 → adaptive icon only, no density PNGs).
**Status: built.**
- A single **virga mark**: a soft downward gradient streak (blue→teal) that fades before a baseline — readable at
  small sizes, monochrome-safe for the notification small icon and Quick Settings tile. Drawn as a scalable
  `VectorDrawable` (`drawable/ic_launcher_foreground.xml`) using the §4.5 `VirgaBlue → VirgaTeal` stops, trailed by
  three fading teal droplets.
- Adaptive icon: foreground = the streak; background = deep brand navy (`VirgaBlueDark #0B3C7A`,
  `@color/ic_launcher_background`). Themed/monochrome layer = a dedicated flat opaque silhouette
  (`drawable/ic_launcher_monochrome.xml`), not the gradient foreground.
- Splash (`Theme.Virga`) shows the same mark via `windowSplashScreenAnimatedIcon`.
- Notification small icon is a flat single-color silhouette (`sync-worker .../drawable/ic_stat_virga.xml`; Android
  tints it) — used by all sync + watchdog notifications; never the gradient.

---

## 4. Color system

### 4.1 Brand seeds (defined in `core:designsystem/.../theme/Color.kt`)
| Token | Hex | Role |
|---|---|---|
| `VirgaBlue` | `#1E6FD9` | Primary seed |
| `VirgaTeal` | `#1FA8A0` | Secondary seed |
| `VirgaBlueDark` | `#0B3C7A` | Deep brand navy (icon bg, gradient end-dark) |
| `VirgaError` | `#BA1A1A` | Error |
| `VirgaSuccess` / `VirgaSuccessDark` | `#2E7D32` / `#81C784` | Success (light/dark) |

### 4.2 Tonal scheme strategy — **fix the #1 identity problem**
Today `LightColors` in `Theme.kt` is a **three-role stub** (`primary`/`secondary`/`error` only); every other role
falls back to M3 defaults, so the brand barely shows even when dynamic color is off. **Target:** generate a *complete*
`lightColorScheme`/`darkColorScheme` from the blue→teal seeds (use the Material Theme Builder / `ColorScheme`
tonal-palette generation) so all roles (containers, surfaces, outline, surfaceVariant, inverse, etc.) are brand-derived.

### 4.3 Dynamic color policy — **brand-first, not brand-erasing**
- Today `dynamicColor` defaults **on**, so on Android 12+ the wallpaper palette *replaces* the brand entirely — Virga
  has no recognizable identity on most devices. **This is the single biggest "no point of view" cause.**
- **Target policy:** the **branded scheme is the default**. Dynamic color becomes an **opt-in tint** in Settings
  ("Match my wallpaper") that *harmonizes* with — never erases — the brand (e.g., tint accents/containers while
  primary stays brand blue, or blend via `Color.harmonize`). A user who never touches Settings sees Virga's identity.

### 4.4 Semantic / status colors — **first-class, via CompositionLocal**
M3 has no `success`/`warning`/`running` roles, so today success is shoehorned onto `tertiaryContainer`
(`SyncStatusBadge.kt`) — semantically wrong and visually muddy. **Target:** a `VirgaSemanticColors` data class exposed
via `LocalVirgaColors` (a `CompositionLocal`), set inside `VirgaTheme`:

| Semantic role | Light | Dark | Used for |
|---|---|---|---|
| `success` | `VirgaSuccess` | `VirgaSuccessDark` | succeeded runs, "in sync" |
| `successContainer` / `onSuccess…` | derived | derived | success badges/strips |
| `warning` / `warningContainer` | amber `#B26A00` / derived | `#FFB868` / derived | conflicts, "needs attention", lost-permission |
| `running` | `VirgaTeal` | `VirgaTealDark` | active transfer (the "precipitation" accent) |
| `info` | `VirgaBlue` | `VirgaBlueDarkPrimary` | neutral notices |

Always pair fg/bg with WCAG-AA contrast (§4.7). Status mapping table is in §10.

### 4.5 Gradients — the atmosphere, used sparingly
- **Hero gradient:** vertical/diagonal `VirgaBlue → VirgaTeal` (light) / `VirgaBlueDark → VirgaTeal` (dark).
- **Where allowed:** onboarding hero, the first-run/empty-state hero, the run-detail header, and the live-sync
  "precipitation" progress treatment. **Nowhere else.** Gradients are punctuation, not wallpaper — never behind body
  content or lists, never under text without a scrim.
- Define as a token (`VirgaGradients.hero`), never inline `Brush.linearGradient(...)` in features.

### 4.6 Surfaces & containers
Use M3 tonal surfaces (`surface`, `surfaceContainerLow/High/Highest`) for elevation-by-tone. Cards use
`surfaceContainer*` per §11, not custom alpha overlays.

### 4.7 Accessibility (non-negotiable)
- Text/icon vs background ≥ **4.5:1** (normal) / **3:1** (large/≥24sp or bold ≥18sp).
- Never encode state by color alone — status always pairs color **+ glyph + text** (§10).
- Verify both light and dark, brand and dynamic schemes.

---

## 5. Typography

Use the existing intentional scale in `Type.kt` (display/headline tight + heavy; title medium; body workhorse; label
crisp). **Do not** re-tune per screen.

- **Add one real display typeface** (Phase 0) via the single `fontFamily` hook the file already anticipates. Candidate:
  a calm geometric/humanist sans for Display/Headline (e.g., a variable font shipped in `app/src/main/res/font/`),
  Body/Label stay on the platform default for legibility + size. **Status (Phase 0): DONE — typeface = Manrope (SIL OFL).** `VirgaDisplayFontFamily` in `Type.kt` feeds Display/Headline from the bundled variable font `core:designsystem/res/font/manrope.ttf` (`wght` axis pinned per weight via `FontVariation`, minSdk 26); license at `core:designsystem/licenses/Manrope-OFL.txt`. A bundled OFL font is used (not the GMS downloadable-font provider) so the F-Droid/foss flavor needs no Google Play Services.
- **Usage map:**
  - `displaySmall`/`headlineMedium` → hero moments only (onboarding, empty-state heading, run-detail header).
  - `titleLarge` → screen titles in the compact top bars.
  - `titleMedium` → list-item primary (task name, remote name).
  - `bodyMedium` → list-item secondary, descriptions.
  - `labelLarge` → buttons; `labelMedium`/`labelSmall` → chips, badges, nav labels, metadata lines.
- Never use `displayLarge`/`displayMedium` on a phone content screen — too large; reserved for splash/marketing.

---

## 6. Iconography

- **Material Symbols** (the project bundles `material-icons-extended`). Prefer **Filled** for primary/active,
  **Outlined** for inactive/secondary where a pair exists. Be consistent per surface.
- **Status glyphs** (always shown with status color + text — see §10): success = `CheckCircle`, error = `Error`,
  running = animated/`Sync`, queued = `Schedule`, paused/disabled = `PauseCircle`, conflict = `WarningAmber`.
- **Provider brand marks:** remotes show a recognizable provider mark (Drive/OneDrive/Dropbox/S3/Box/…) on
  `RemoteCard`, not the lowercase backend string. Store as tinted vector assets keyed by rclone backend `type`;
  fall back to a generic cloud glyph for unknown backends.
- Decorative icons get `contentDescription = null`; meaningful ones get a real description (a11y).

---

## 7. Shape & elevation

- **Shape scale:** M3 default rounded scale (`extraSmall`→`extraLarge`). Cards = `medium`; bottom sheets = `large`
  top corners; chips/buttons = full/pill. Adopt Expressive shape where it reads as intentional (e.g., larger corner
  radii on hero cards), defined as tokens, applied consistently.
- **Elevation by tone, not shadow.** Use `surfaceContainer*` tonal elevation; reserve real shadow for FAB and
  transient surfaces (sheets, menus). No ad-hoc `Modifier.shadow` in features.

---

## 8. Spacing & layout tokens

Introduce `VirgaSpacing` (Phase 0) and use **only** these:

| Token | dp | Use |
|---|---|---|
| `xs` | 4 | icon↔label gaps, tight inline |
| `sm` | 8 | intra-component, chip spacing |
| `md` | 16 | **default** screen/content inset, list-item padding, card inner padding |
| `lg` | 24 | section spacing, hero padding |
| `xl` | 32 | major separations, empty-state breathing room |

- Screen horizontal inset = `md` (16dp) everywhere unless a hero says otherwise.
- Vertical rhythm between cards in a list = `sm`→`md`. Don't mix arbitrary values.
- Respect `safeDrawingPadding`/insets (edge-to-edge is on); never hardcode status-bar offsets.

---

## 9. (reserved)

---

## 10. State & status visual language

Every task/run state has **exactly one** representation across the whole app (card, summary, history, notification):

| State | Color (semantic) | Glyph | Label | Motion |
|---|---|---|---|---|
| Idle / never run | `onSurfaceVariant` | none | — | none |
| Queued | `info` | `Schedule` | "Queued" | gentle pulse |
| **Running** | `running` (teal) | animated | "Backing up… 37% · 12/40 · 4.1 MB/s · ETA 2m" | **precipitation progress** (§ motion) |
| Success | `success` | `CheckCircle` | "Up to date" / "Done" | one-shot success tick + haptic |
| Failed | `error` | `Error` | plain-English reason | none (don't alarm; inform) |
| Paused / disabled | `onSurfaceVariant` | `PauseCircle` | "Paused" | none |
| Conflict | `warning` | `WarningAmber` | "Needs your decision" | none |

`SyncStatusBadge` is the *only* component that renders this; everything reuses it. Color **and** glyph **and** text —
never color alone.

---

## 11. Component design language

All shared components live in **`core:designsystem`** (created in Phase 0). Feature modules compose these; they do not
re-style primitives. The current `core:ui` (`EmptyState`, `ToggleRow`) is the seed of this module.

- **`VirgaCard`** — one card to rule the app. States: `default` (`surfaceContainerLow`), `selected`
  (`secondaryContainer` tint + check affordance), `active` (running — `running`-tinted edge/strip). Replaces today's
  inconsistent mix (`Surface` in `SyncTaskCard`, `ElevatedCard` in `RemoteCard`, raw `Card` elsewhere). `md` inner
  padding, `medium` shape.
- **Buttons:** primary action = filled; secondary = tonal/outlined; destructive = error-tinted (§13). Use Expressive
  **button groups** for segmented choices (direction, performance preset). One primary action per screen.
- **Chips:** `FilterChip` for filters (list filters, weekday picker, filter-rule chips); `AssistChip` for provider
  sign-in. Selected state uses `secondaryContainer`.
- **FAB / Extended FAB:** one per screen, collapses on scroll (already done on Sync/Remotes). Consider Expressive
  **FAB menu** where a screen has 2–3 primary creates.
- **Status pill:** `SyncStatusBadge` only (§10).
- **Progress:**
  - Determinate transfers → **wavy `LinearProgressIndicator`** (Expressive) tinted `running` — the "precipitation".
  - Indeterminate waits → Expressive **loading indicator**, not the legacy circular spinner, where space allows.
- **Containers — sheets vs dialogs:**
  - **`ModalBottomSheet`** for: add/edit-adjacent multi-field flows (Add Remote — currently a cramped `AlertDialog`),
    card action menus (replace per-card `DropdownMenu`), the local folder picker.
  - **`AlertDialog`** only for: short, blocking confirms (especially destructive — §13).
- **Empty states:** always `EmptyState` (icon + title + body + action). Body **teaches the concept**; action is the
  next step (§12 in UX_VISION). Hero empty states may use the §4.5 gradient.
- **Top bars:** the three bottom-nav destinations (Sync/Remotes/Settings) use a **compact** `TopAppBar` (decided —
  large collapsing bars wasted space and fought pull-to-refresh). Detail/hero screens (onboarding, run detail) may use
  a larger/gradient header deliberately.

---

## 12. Motion language

Motion is the brand's heartbeat. Default crossfades are forbidden where a token exists.

- **Adopt `MaterialExpressiveTheme` + a `VirgaMotionScheme`** (Phase 0). **Status: adopted** — `VirgaTheme` wraps content in `MaterialExpressiveTheme` with `MotionScheme.expressive()` (material3 pinned to 1.5.0-alpha18 above the BOM, since the APIs are `internal` in stable 1.4.0); the wavy `LinearWavyProgressIndicator` drives the live-sync "precipitation". The `VirgaMotion` tokens still drive nav/list motion. This flips the whole app from default
  to physics/spring motion in one move. Expose motion tokens; never hardcode `tween(300)` in features.
- **Named motions (use these, define once):**
  - **Nav forward/back:** `NavDisplay` `transitionSpec` + `predictivePopTransitionSpec` (shared X-axis slide/fade).
    Currently default crossfade — highest polish-per-line fix.
  - **Shared element:** card → summary, history row → run detail (container transform).
  - **List item enter:** subtle staggered fade/slide-in.
  - **Precipitation (signature):** a live transfer renders as gentle downward streaming motion on the progress
    surface — wavy indicator + softly animated gradient — evoking falling rain that fades. Calm, ~slow, never seizure-y.
  - **Success tick:** brief scale+check on run completion, paired with a success haptic.
- **Reduce-motion:** honor the system "remove animations" setting — fall back to fades, keep durations near-zero.
  The precipitation effect must degrade to a static determinate bar.

---

## 13. Safety-rail visual language (destructive actions)

Trust is the product. Every destructive capability ships **in the same UI moment as its mitigation.**

- **Color:** destructive controls/confirms use `error`/`errorContainer`; the safe/cancel path is the visually-default
  choice.
- **Dry-run first:** Mirror, two-way conflict resolution, and any delete-extraneous operation must offer/encourage a
  **"Preview changes (dry run)"** that shows the change set (counts + bytes + sample paths) *before* the real run.
- **Name the blast radius** in confirm copy (§2). No bare "Are you sure?".
- **Guards co-located:** the Mirror toggle sits beside "Keep deleted files" (`--backup-dir`) and the dry-run action;
  two-way conflict policy sits beside a max-delete guard and an explicit "Re-establish baseline" button.
- **Undo where reversible** (swipe-delete uses an Undo snackbar). Irreversible cloud deletes get a strong confirm, not
  an undo promise we can't keep.
- One-way Upload/Download are **additive by default** (never delete on the destination); mirroring is always an
  explicit, labeled opt-in. (This is current behavior — keep it.)

---

## 14. Accessibility commitments

- Touch targets ≥ 48×48dp. Merge label+control into one semantics node where they act as one (see `ToggleRow`).
- Every meaningful control has a `contentDescription`/`stateDescription`; decorative visuals are `null`.
- Live regions for async status (progress, errors) so TalkBack announces changes.
- Full keyboard/switch traversal order; visible focus.
- Test with TalkBack + large font + dark + dynamic scheme before calling a screen done.

---

## 15. Implementation & governance

- **Module:** `core:designsystem` (Phase 0) owns: `VirgaTheme`, `MaterialExpressiveTheme` setup, the full color
  schemes, `LocalVirgaColors`, `VirgaSpacing`, `VirgaShapes`, `VirgaMotionScheme`, `VirgaGradients`, typography, and
  shared components (`VirgaCard`, `SyncStatusBadge`, `EmptyState`, `ToggleRow`, progress, sheets). Feature modules
  depend on it and **never** define their own theme/color/spacing.
- **Anti-patterns (treat as review-blocking):** raw `Color(0x…)` or hex in a feature; hardcoded `.dp` spacing outside
  a token; `tween`/`spring` literals where a motion token exists; per-screen `MaterialTheme(...)`; success/warning via
  `tertiary`/`secondary` instead of `LocalVirgaColors`; new card built from raw `Surface`/`Card` instead of `VirgaCard`.
- **Definition of done for any UI work:** uses tokens only; light+dark+dynamic verified; status shown color+glyph+text;
  destructive paths follow §13; motion uses tokens + respects reduce-motion; a11y per §14.
- **Evolving the system:** change this doc in the same PR as the code; note material changes in `docs/ROADMAP.md`.

---

*Virga: weather for your data. Calm on the surface, powerful underneath, always honest about what's happening to your files.*
