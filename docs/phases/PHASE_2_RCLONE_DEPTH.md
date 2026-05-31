# Phase 2 — Unlock the rclone Depth That's Already Wired

**Theme:** *Power on demand, safely.* **Goal:** realize the progressive-disclosure ladder ([`../UX_VISION.md`](../UX_VISION.md) §4)
and close the data-model gap. Most of this is **UI-only** — the engine already supports it.

> Prereqs: Phases 0–1. Read BRAND §13 (safety rails) — destructive power ships with its mitigation.
> Pre-production: add Room columns freely (bump `VirgaDatabase` version; destructive migration is acceptable per
> root `CLAUDE.md`). Add a real `MIGRATION_n_n+1` only if convenient.

**Key engine facts to know (verified):**
- `core/database/.../entity/Entities.kt · SyncTaskEntity` currently persists: `direction`, `intervalMinutes`,
  `scheduleDaysMask/Hour/Minute`, `filters` (newline string), `bwLimitWifi/Metered`, `transfers`, `checkers`,
  `bufferSize`, `wifiOnly`, `requiresCharging`, `enabled`, `createdAtEpochMs`.
- `feature/sync/.../SyncTaskEditViewModel` (form) + `SyncTaskEditAdvanced` (the 3-field accordion) only expose a subset;
  `filters`, `transfers`, `checkers`, `requiresCharging` have **no editor**.
- `sync-worker/.../SyncExecutor.run` builds `SyncOptions`/`BisyncOptions`; `filters` is split there; `transfers`/
  `checkers`/`bufferSize`/`bwLimit`/`deleteExtraneous`/`dryRun` flow into the RC `_config`/`_filter` blocks via
  `core/rclone/.../RcloneEngineImpl` (`putConfig`/`putFilters`). So these are wired end-to-end already.
- One-way syncs are **additive by default** (`allowDeletes = false` in `SyncWorker`); `deleteExtraneous`/`dryRun` are
  wired but currently never set true.
- `logPath` exists through entity→mapper→repo but `SyncWorker` never writes a log.

---

## WS2.0 — "Show advanced options" master toggle + default seeding

**Why:** UX_VISION §4 — one switch governs tier visibility; new tasks should inherit app defaults.
**Steps:**
1. Add `showAdvancedOptions: Boolean` to `core:datastore` `AppPreferences` (+ Settings toggle). Tier 2/3 sections in
   the editor are hidden unless on; Tier 1 is always present but collapsed for beginners.
2. New tasks seed Tier-0/1 defaults from app-level Settings (today the editor `load()` for a new task ignores prefs —
   wire `wifiOnlyByDefault`, `requiresChargingByDefault`, default bw limits, default performance preset).

**Files:** `AppPreferences`/`PreferencesRepository`, `SettingsScreen.kt`, `SyncTaskEditViewModel.load()`.
**Acceptance:** beginners see a compact editor; flipping the toggle reveals Tier 2/3; new tasks inherit defaults.
**Impact:** medium. **Effort:** S.

---

## WS2.1 — Tier-1: Filters editor

**Why:** highest-leverage unlock; `filters` is persisted + applied but uneditable. **Current:** `filters` is a
newline-joined glob string; `SyncExecutor` splits it into rclone `+`/`-` rules. **Target:** a friendly include/exclude
builder that produces that same string.

**Steps:**
1. UI (Tier 1): chip-based include/exclude rules (`+ *.jpg`, `- *.tmp`), plus quick presets (by extension, "skip files
   larger than N", "only modified in last N days" → rclone `--max-size`/`--min-age` style, surfaced as filter rules or
   config as appropriate). Show a live preview of the effective rule list.
2. Round-trip to the `filters` form field/entity column (newline string). Validate globs.
3. Keep raw-string editing available under "edit as text" for power users (typed UI wins on conflict).

**Files:** new `FilterEditor` composable in `feature/sync`; `SyncTaskEditViewModel` (form field already exists),
`SyncTaskEditAdvanced.kt` or a new Tier-1 section; strings.
**Acceptance:** a beginner adds "skip screenshots" via a chip; a power user writes raw globs; both persist and affect
the run (verify with a dry-run). **Impact:** high. **Effort:** M.

---

## WS2.2 — Tier-1: Mirror toggle (safety-railed)

**Why:** delete-extraneous mirroring is a legitimate power feature but currently force-disabled for safety
(`SyncWorker · allowDeletes = false`). **Target:** an explicit, clearly-labeled, dry-run-guarded **Mirror** toggle that
re-enables `deleteExtraneous` per task.

**Steps:**
1. Add `deleteExtraneous: Boolean` to `SyncTaskEntity` + form (default **false**). When on, `SyncWorker` passes
   `allowDeletes = true` (which routes to rclone `sync/sync` vs `sync/copy` in `SyncExecutor`/engine).
2. **Safety rails (BRAND §13):** the Mirror toggle sits next to "Keep deleted files" (`--backup-dir`, Phase 3 wiring or
   a Tier-1 simple version) and the **Dry-run** action (WS2.3). Turning Mirror on requires acknowledging a clear,
   error-tinted explanation that names the consequence ("Files removed from your phone will be deleted from the
   cloud").
3. Surface Mirror state on the summary/card (Two-way/Mirror is meaningfully different from additive).

**Files:** `Entities.kt` (+ migration), mapper, `SyncTask` domain model, `SyncTaskEditViewModel` (form + save),
`SyncWorker` (`allowDeletes` from task), editor Tier-1 section, strings.
**Acceptance:** Mirror is off by default; enabling it requires acknowledgement; a mirrored run deletes extraneous
destination files; a dry-run shows what would be deleted first. **Impact:** high. **Effort:** M.

---

## WS2.3 — Tier-1: Dry-run / preview changes

**Why:** trust differentiator; `dryRun` is wired through the engine but never set. **Target:** a "Preview changes"
action that runs rclone with `--dry-run` and shows the change set before any destructive real run.

**Steps:**
1. Add a "Preview changes (dry run)" action to the editor and to the summary's Run controls (and as the mandatory
   first step when Mirror/Two-way is enabled).
2. Run the task with `SyncOptions(dryRun = true)` (already supported by `RcloneEngineImpl`); capture the would-change
   set (counts, bytes, sample paths — from rclone stats/log output).
3. Present a diff-style summary (added/updated/deleted counts + bytes + a sample list), with a "Run for real" confirm
   that reuses the safety-rail confirm copy.

**Files:** `SyncExecutor`/engine (ensure dry-run results are observable — may need to surface rclone's planned actions),
`SyncTaskSummaryViewModel`/`Screen`, a `DryRunResult` model, strings.
**Acceptance:** preview shows accurate change counts/bytes without modifying anything; destructive runs offer it first.
**Impact:** high. **Effort:** M (engine result-surfacing is the unknown — spike first).

---

## WS2.4 — Tier-1: Performance preset (transfers/checkers)

**Why:** `transfers`/`checkers` are persisted + applied but uneditable. **Target:** a 3-way preset (and an advanced
custom).

**Steps:**
1. Tier-1 segmented control (Expressive button group): **Conservative / Balanced / Aggressive** →
   `{transfers,checkers}` ≈ `{2/4, 4/8, 16/32}` (tune for mobile). Advanced mode reveals raw steppers.
2. Round-trip to the existing `transfers`/`checkers` entity columns + form fields. Note `bufferSize` is also persisted
   — expose it in advanced (Tier 2) or alongside the preset.
3. Consider tying "Aggressive" to a Wi-Fi/charging hint (battery/data).

**Files:** `SyncTaskEditViewModel` (add form fields for transfers/checkers), editor Tier-1 section, strings.
**Acceptance:** preset changes affect throughput (verify a run uses the configured values); advanced exposes raw
numbers. **Impact:** high. **Effort:** S–M.

---

## WS2.5 — Log viewer

**Why:** UX_VISION finding #3 — `logPath` is plumbed but no log is written and no screen shows it. **Target:** per-run
verbose log, viewable from Run Detail.

**Steps:**
1. In `SyncWorker`, capture rclone verbose output for the run to a per-run file (the daemon logs JSON to stderr — the
   `RcloneDaemonManager` stderr drainer already sees it; tee the relevant lines to a file). Persist its path to
   `SyncRunEntity.logPath`.
2. A `LogViewerScreen` (route) opened from `RunDetailScreen`: scrollable, monospaced, searchable, **shareable** (share
   intent). Handle large logs (cap/stream).
3. Redact secrets defensively (the config is encrypted, but be careful not to log tokens).

**Files:** `SyncWorker.kt`, `RcloneDaemonManager` (log tee), new `LogViewerScreen`/route in `VirgaNavHost`,
`RunDetailScreen.kt`.
**Acceptance:** each run produces a log; Run Detail → "View log" shows it; it's searchable + shareable; no secrets
leak. **Impact:** high. **Effort:** M.

---

## WS2.6 — File-browser multi-select → "Create task from selection"

**Why:** UX_VISION finding #5 — `FileBrowserViewModel` has a full multi-select state machine that nothing renders.
**Target:** wire it to the contextual action mode (WS2.7) with a "Create sync task from selection" action that funnels
into the prefill editor (WS1.3).

**Steps:**
1. Render selection in `FileBrowserScreen`: long-press enters selection mode (haptic), checkboxes via `VirgaCard`
   `selected` state, a selection top bar (WS2.7).
2. Selection action: "Create sync task" → `TaskEditRoute` prefilled with the remote + chosen path(s). (For multiple
   paths, either create one task per folder or pre-fill an include-filter — pick the simpler: one task per selected
   folder, or single-folder selection to start.)
3. If the value isn't worth it, the fallback is to **remove** the dead machinery — but wiring it is the recommended
   high-value path.

**Files:** `FileBrowserScreen.kt` (consume the existing VM state), `VirgaNavHost.kt`, `SyncTaskEditViewModel`.
**Acceptance:** long-press in the browser selects folders and offers "Create sync task" that opens a prefilled editor;
no dead selection state remains. **Impact:** high. **Effort:** M.

---

## WS2.7 — Shared contextual-action-mode primitive + haptics

**Why:** UX_VISION §6 — sync tasks, conflicts, and the file browser each reinvent multi-select. **Target:** one
primitive + consistent haptics.

**Steps:**
1. Build a `core:designsystem` contextual-action-mode scaffold: a selection `TopAppBar` (count + actions + clear) and
   a `rememberSelectionState` helper; entering selection fires a `LongPress` haptic.
2. Adopt it in: Sync tasks (bulk run/enable/disable/delete — already exists, migrate), Conflicts (replace the
   permanent app-bar buttons — see WS3), and the file browser (WS2.6).
3. Add `LongPress` haptics to every `combinedClickable.onLongClick` selection entry; add a success tick on run
   completion (Phase 1 motion).

**Files:** new `core/designsystem/.../component/ContextualActionMode.kt`; `SyncTasksScreen`/`SyncTasksTopBars`,
`FileBrowserScreen`, `ConflictsScreen`.
**Acceptance:** all three surfaces share one selection UX with consistent haptics. **Impact:** high. **Effort:** M.

---

## Phase 2 exit criteria
- The 4-tier editor is real: beginners see Tier 0–1 collapsed; "Show advanced" reveals 2–3.
- Filters, Mirror (safety-railed), Dry-run preview, and the performance preset all work and affect runs.
- Every run writes a viewable/searchable/shareable log.
- File-browser selection is wired to task creation; one contextual-action-mode primitive everywhere.
- Definition of Done passes; destructive paths verified to preview-then-confirm on-device.
