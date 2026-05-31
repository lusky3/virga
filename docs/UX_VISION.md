# Virga — UX Vision (Product, IA & Progressive Disclosure)

> The "what and why" companion to [`../BRAND.md`](../BRAND.md) (the "how it looks") and
> [`ROADMAP.md`](ROADMAP.md) (the "in what order"). Read all three before doing UI work.
>
> This document is derived from a 6-dimension UI/UX review whose every claim was cross-checked against the
> real source. File references use `file · symbol` form (line numbers drift; symbols don't).

---

## 1. Who we're building for

Two users, **one app** — never a "simple mode" and an "expert mode" as separate products:

- **Bea (beginner).** Wants her phone photos backed up to Google Drive and to stop thinking about it. Should never
  see "bisync", "transfers", "checkers", or a glob pattern unless she goes looking. Success = a working backup in
  < 1 minute from a cold install.
- **Pax (power user).** Runs rclone on a server, knows `--backup-dir` and `--track-renames`, wants Virga to expose
  the knobs that matter and to *not lie to him* about what a run will do. Success = he can reach every meaningful
  rclone capability — including raw flag passthrough and per-remote config — without leaving the task editor.

The whole design challenge is reconciling these two on the same surfaces. The mechanism is **progressive disclosure**
(§4).

---

## 2. Current-state verdict (where we are honestly)

Virga has **excellent bones and no point of view yet, and it under-delivers on its own data model.**

What's genuinely good (keep it): type-safe Navigation 3 with per-tab back stacks (`app/.../navigation/VirgaNavHost.kt`),
a clean task lifecycle (list → read-only summary → edit/run/history → run detail), real craft in spots
(swipe-to-delete with threshold haptics in `SyncTasksTopBars.kt · SwipeToDeleteCard`, animated onboarding page dots),
strong accessibility semantics throughout.

What's wrong (the through-lines every reviewer hit):
1. **No identity.** Dynamic color defaults on (`ui/theme/Theme.kt · VirgaTheme`), so the brand seeds are invisible on
   modern devices; the non-dynamic `LightColors` is a 3-role stub; zero Material 3 Expressive despite the Expressive
   BOM. Looks like "stock M3 circa 2023."
2. **Not alive.** A running sync is never visualized in-app. `SyncProgress` (bytes/files/speed/ETA) is collected in
   `sync-worker/.../SyncWorker.kt` and pushed **only** to the notification. The task card shows a text pill +
   Cancel. This is the biggest perceived-quality gap vs FolderSync/RoundSync.
3. **Capabilities stranded one TextField away.** `filters`, `transfers`, `checkers`, `requiresCharging` exist on
   `core/database/.../entity/Entities.kt · SyncTaskEntity` with **no editor UI**. `dryRun` is fully wired through
   `core/rclone/.../RcloneEngineImpl` but never set. `logPath` is plumbed through entity→repo but **never written**.
   `deleteExtraneous` (Mirror) is wired but intentionally forced off for safety — it needs a guarded opt-in, not to
   stay hidden.
4. **Broken first-run path.** App opens on an empty Sync list; "new task" funnels a beginner into a blank editor whose
   remote dropdown is empty; the only escape jumps tabs to Remotes and *abandons the half-filled form*.
5. **A literal dead end.** `feature/explorer/.../FileBrowserViewModel` has a complete multi-select state machine
   (`selectedPaths`, `enterSelectionMode`, `toggleSelection`) that **nothing in `FileBrowserScreen` renders or
   consumes**.

---

## 3. Information architecture & flows

### 3.1 Navigation (keep the structure)
Bottom-nav destinations: **Sync** (start), **Remotes**, **Settings**. Stacked screens: Task Summary, Task Editor
(+ advanced), File Browser, Sync History, Run Detail, Conflicts, Onboarding. Per-tab back stacks via Navigation 3.
This structure is sound — the problems are in the *flows between* screens, not the tabs.

### 3.2 The mental model (must be taught at the point of decision)
Three concepts: **Remote** (a cloud account) → **Task** (what to sync where, how) → **Run** (one execution, with
history + logs). Today this is explained only in one onboarding paragraph and a buried Settings dialog. **Target:**
teach it inline where the user first needs it — empty states, the editor's existing inline direction hint pattern
extended to Remote/Two-way, and the first-run wizard (§ Phase 1).

### 3.3 Target first-run flow (fixes finding #4)
`Onboarding → "Set up your first sync" wizard` as the single funnel, launched from both onboarding's final step and
the Sync empty state:
1. Pick/learn what a sync is (one calm screen).
2. **Add a cloud account** — as a *returnable sub-flow* (Navigation 3 result pattern): pops back into the wizard with
   the new remote preselected, never abandoning progress.
3. Pick source folder (browse-first) → destination (browse-first).
4. Direction defaulted to Upload with plain explanation; schedule defaulted to a sane Wi-Fi auto.
5. Land on the new task with a "Back up now" CTA. Done < 1 min.

### 3.4 Unify task-creation entry points
Today multiple entry points diverge; the prefill-capable path (browse → "Sync this folder") is the good one. All
"create task" affordances (Sync FAB, empty state, RemoteCard "New task", file browser selection) must funnel through
the **same** prefill-capable editor and pass their context (remote, path) in — `RemoteCard`'s "New task" currently
throws the remote away.

---

## 4. The progressive-disclosure model (the core mechanism)

**One capability ladder. Never a separate expert app.** Governed by a single persisted **"Show advanced options"**
preference (Settings). Typed UI controls always win over raw text where both exist, so layers stay coherent.

### 4.1 Task editor — 4 tiers
Replaces today's flat form + the 3-field accordion in `feature/sync/.../SyncTaskEditAdvanced.kt`.

| Tier | Shown to | Controls | rclone backing / status |
|---|---|---|---|
| **0 · Essentials** | Everyone, always | name, source, destination (browse-first), direction, schedule (day/time builder exists), Wi-Fi only | already wired |
| **1 · Common power** | Beginners: collapsed "More options"; experts: expanded | **Filters editor** (include/exclude glob chips + size/age presets), **Mirror toggle** + safety net, **Dry-run / Preview changes**, **Performance preset** (Conservative/Balanced/Aggressive → transfers/checkers) | `filters` split in `SyncExecutor`; `deleteExtraneous`, `dryRun`, `transfers`/`checkers` **already flow to the RC `_config`/`_filter` blocks** — UI-only work |
| **2 · rclone flags** | Advanced mode only | typed groups by rclone concept: Comparison (`--checksum`/`--track-renames`), Two-way (`--conflict-resolve`, `--max-delete`, resync mode), Versioning (`--backup-dir`/`--suffix`), Transfer limits (`--max-transfer`, `--order-by`) | needs new entity columns + `putConfig` extensions |
| **3 · Raw passthrough** | Advanced mode only | "Extra rclone flags" free-text, validated against a known-flag allowlist | new `extraFlags` column merged into `_config` |

A beginner sees ~6 fields + a collapsed "More options". A power user flips one Settings switch and sees everything,
all on the same screen.

### 4.2 Remote config — same ladder
Today the only escape hatch is the freeform `key=value` textarea in `feature/remotes/.../RemoteComponents.kt ·
AddRemoteDialog`. **Target:** drive remote setup from rclone's own `config/providers` RC response (the engine already
speaks RC) to render **typed, labeled, per-backend fields** with required/optional markers; fall back to the raw
textarea for unknown backends. Add a real **crypt-wrapping flow** (pick base remote + password → create a `crypt:`
layer), which the `crypt` entry in the type dropdown implies but doesn't deliver. Plus bring-your-own OAuth keys
(already shipped) stays in the "advanced" disclosure tier of the add-remote sheet.

### 4.3 Highest-leverage, lowest-effort unlocks
The engine already supports these — **UI-only**: **Filters editor**, **transfers/checkers preset**, **Mirror toggle**,
**Dry-run preview**. These four close most of the "full rclone capability" gap by themselves. Do them in Phase 2.

---

## 5. Missing functionality (prioritized)

| # | Gap | Why it matters | Phase |
|---|---|---|---|
| 1 | **Live sync visualization** in-app (card strip + summary live panel) | Biggest perceived-quality gap; data must *move* | 1 |
| 2 | **Guided first-sync wizard** + returnable add-remote | Fixes the broken new-user path | 1 |
| 3 | **Log viewer** (write `logPath`, surface a scrollable/searchable/shareable screen from Run Detail) | Power-user trust + debuggability; already half-plumbed | 2 |
| 4 | **Dry-run / preview changes** before destructive runs | Trust differentiator; already wired in engine | 2 |
| 5 | **Filters / Mirror / transfers** controls (Tier 1) | Unmet power-user promise; UI-only | 2 |
| 6 | **File-browser → "Create task from selection"** (wire the dead multi-select) | Turns the explorer into the task gateway | 2 |
| 7 | Notification **deep links + Cancel/Retry actions**; deep links for RunDetail/TaskSummary | Standard expectation; none today (`SyncNotifications` has no `addAction`/`contentIntent`) | 3 |
| 8 | Adaptive **list-detail** panes (tablets/foldables) | Large-screen craft | 3 |
| 9 | **Glance widget + Quick Settings tile**; remote **quota/health** via `rclone about` | Reach + at-a-glance value | 3 |

---

## 6. Gestures & interaction patterns (fixes)

- **Wire or remove the invisible file-browser multi-select** (finding #5). High-value path: wire it to a contextual
  "Create sync task from selection" action bar.
- **Build one app-wide "contextual action mode" primitive** (selection top bar + entry haptic) and use it everywhere
  multi-select exists (sync tasks, conflicts, file browser). Today each reinvents it (interactive `Checkbox` vs
  `CheckBox` icon vs app-bar buttons).
- **Haptics:** today only swipe-delete fires one. Add `LongPress` haptics to every `combinedClickable.onLongClick`
  that enters selection, and a success tick on run completion.
- **Right container:** promote `AddRemoteDialog` (cramped `AlertDialog`) and per-card overflow `DropdownMenu`s to
  **`ModalBottomSheet`/action sheets** that visually separate destructive Delete.
- **Conflicts bulk actions are a footgun:** three text buttons live permanently in the app bar
  (`feature/sync/.../ConflictsScreen`) and mean "resolve all" when nothing is selected — for an *irreversible* delete
  with no undo. Move into the shared selection top bar; strengthen the confirm to name files and use an error-tinted
  button (§13 of BRAND).
- **Nav motion / shared elements / predictive-back:** `NavDisplay` uses default crossfade — add transitions
  (Phase 1).

---

## 7. Framework decision (settled)

**Stay on Jetpack Compose + Material 3 Expressive. Do not switch frameworks for visual benefit — there is none.**
Compose Multiplatform = same renderer on Android (visual no-op). Flutter raises the theoretical ceiling but means
rewriting every screen *and* re-bridging the rclone-Go daemon, Hilt, WorkManager, SAF/`MANAGE_EXTERNAL_STORAGE`,
OAuth/PKCE — while losing native Material You. Native Views = regression. **Virga isn't constrained by Compose; it's
under-using it.** The entire visual ceiling in BRAND.md is reachable in-place on the current BOM. Spend the budget on
the design system + Expressive adoption, not a rewrite. (Full reasoning in the review; this is final.)
