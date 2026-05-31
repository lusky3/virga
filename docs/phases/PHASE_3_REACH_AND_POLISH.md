# Phase 3 — Depth, Reach & Large-Screen Craft

**Theme:** *Full capability + polish.* **Goal:** the long tail of rclone power, the differentiating surfaces, and
large-screen/adaptive craft — once the foundation (P0), the alive/onboarding core (P1), and the common power tier (P2)
are solid.

> Prereqs: Phases 0–2. Continue to honor BRAND §13 safety rails and the progressive-disclosure tiers.
> Pre-production: add Room columns freely (destructive migration acceptable).

---

## WS3.1 — Tier-2/3: typed rclone flag groups + raw passthrough

**Why:** UX_VISION §4.1 — power users need the flags that matter, plus an escape hatch. **Current:** Tier 0–1 only.
**Target:** advanced-mode typed groups + a validated free-text passthrough.

**Steps:**
1. Add entity columns + `SyncOptions`/`BisyncOptions` fields + `putConfig` extensions in `RcloneEngineImpl` for:
   - **Comparison:** `--checksum`, `--track-renames`, `--size-only`.
   - **Two-way:** `--conflict-resolve`, `--max-delete` guard, resync mode / "Re-establish baseline" button.
   - **Versioning:** `--backup-dir` (+ `--suffix`) = "Keep deleted/overwritten files" (the Mirror safety net from P2).
   - **Transfer limits:** `--max-transfer`, `--order-by`.
2. **Tier-3 raw passthrough:** `extraFlags` text column merged into the RC `_config`/args; validate tokens against a
   known-flag allowlist (reject unknown/dangerous flags with a clear message).
3. Group these as Tier-2 sections (advanced mode only) by rclone concept, each with plain-English labels + a one-line
   gloss of the underlying flag.

**Files:** `Entities.kt` (+columns), mapper, `SyncTask`, `SyncOptions`/`BisyncOptions` (`RcloneTypes`),
`RcloneEngineImpl.putConfig`, `SyncTaskEditAdvanced` (Tier-2/3 sections), strings.
**Acceptance:** advanced users can set checksum/track-renames/backup-dir/conflict-resolve/max-delete + raw flags; each
demonstrably changes the run; allowlist rejects garbage. **Impact:** high. **Effort:** L.

---

## WS3.2 — Introspection-driven remote config + crypt wizard

**Why:** UX_VISION §4.2 — today the only advanced remote path is the freeform `key=value` textarea in
`RemoteComponents · AddRemoteDialog`. **Target:** typed per-backend fields from rclone's own schema + a real crypt flow.

**Steps:**
1. Call rclone's `config/providers` RC (the engine already speaks RC via `RcApiClient`) to fetch each backend's option
   schema (name, type, help, required, examples). Render typed, labeled fields with required/optional markers; fall
   back to the raw textarea for unknown backends.
2. **Crypt-wrapping wizard:** pick a base remote + password(s) → create a `crypt:` remote layered on it (the `crypt`
   entry in the type dropdown currently implies this but doesn't deliver). Use rclone's crypt config flow via RC.
3. Keep bring-your-own OAuth keys (already shipped) in the advanced tier of the add-remote sheet.
4. Config export already exists; ensure import/export symmetry.

**Files:** `RemoteComponents.kt`/`RemotesScreen.kt`, `RemotesViewModel`, `core/rclone` (`config/providers` call +
crypt config), strings.
**Acceptance:** adding (e.g.) an S3/WebDAV remote shows typed labeled fields instead of raw text; a crypt remote can be
created end-to-end and used by a task. **Impact:** medium. **Effort:** L.

---

## WS3.3 — Container & destructive-action polish (sheets + conflicts)

**Why:** UX_VISION §6 — wrong containers + a conflicts footgun. **Target:** bottom sheets where appropriate; conflicts
moved into the safe selection model.

**Steps:**
1. Promote `AddRemoteDialog` (cramped `AlertDialog`) → `ModalBottomSheet`; convert per-card overflow `DropdownMenu`s →
   action sheets that visually separate destructive Delete (BRAND §11, §13).
2. **Conflicts (`ConflictsScreen`):** today three text buttons live permanently in the app bar and mean "resolve all"
   when nothing is selected — for an *irreversible* delete with **no undo**. Move bulk actions into the shared
   contextual-action-mode selection bar (P2 WS2.7); strengthen the confirm to name files + error-tinted button; default
   the per-conflict choice to the non-destructive option.

**Files:** `RemoteComponents.kt`, `SyncTaskCard.kt`/`SyncTasksTopBars.kt` (overflow), `ConflictsScreen.kt`/
`ConflictsViewModel.kt`.
**Acceptance:** Add Remote is a roomy bottom sheet; destructive items are visually separated; conflict bulk actions are
selection-gated with a blast-radius-naming confirm. **Impact:** high. **Effort:** M.

---

## WS3.4 — Notifications: deep links + Cancel/Retry actions

**Why:** UX_VISION finding #7 — `SyncNotifications` has no `addAction`/`contentIntent`. **Target:** actionable,
deep-linking notifications.

**Steps:**
1. Add a `contentIntent` deep-linking to the run's `RunDetailRoute`/`TaskSummaryRoute`; register deep links in
   `VirgaNavHost`.
2. Add notification actions: **Cancel** (running), **Retry** (failed) → routed to the scheduler.
3. Ensure the foreground-service notification (dataSync) still complies with the existing `foregroundServiceType`
   setup.

**Files:** `sync-worker/.../SyncNotifications.kt`, `SyncWorker.kt`, `VirgaNavHost.kt` (deep links), `MainActivity` if
needed for intent routing.
**Acceptance:** tapping a sync notification opens the right screen; Cancel/Retry work from the shade. **Impact:**
medium. **Effort:** M.

---

## WS3.5 — Adaptive list-detail panes (tablets/foldables)

**Why:** large-screen craft; the project already includes `material3-adaptive-navigation-suite`. **Target:** two-pane
layouts on expanded widths.

**Steps:**
1. Use `ListDetailPaneScaffold` for **Sync → Task Summary** and **Remotes → File Browser**: list on the left, detail
   on the right at medium/expanded widths; single-pane on compact (current behavior).
2. On tablets/foldables, the advanced editor or the live-progress panel can occupy a supporting pane.
3. Verify Navigation 3 back-stack integration with the pane scaffold.

**Files:** `VirgaNavHost.kt` (or per-feature adaptive wrappers), `SyncTasksScreen`/`SyncTaskSummaryScreen`,
`RemotesScreen`/`FileBrowserScreen`.
**Acceptance:** on a tablet/unfolded device, list+detail show side-by-side; compact unchanged; back behaves correctly.
**Impact:** high (large screens). **Effort:** L.

---

## WS3.6 — Signature polish: shared elements, widget, QS tile, remote health

**Why:** the differentiating "delight" surfaces. **Target:**
1. **Shared-element transitions** (BRAND §12): card → summary, history row → run detail (container transform).
2. **Glance home-screen widget**: at-a-glance task status + "Back up now"; **Quick Settings tile**: toggle/trigger a
   sync. (Use the monochrome small icon — BRAND §3.)
3. **Remote quota/health** on `RemoteCard` via `rclone about` (RC) — free/used storage, account health.

**Files:** new `feature` Glance module + QS `TileService`; `VirgaNavHost`/screens (shared elements); `RemotesViewModel`
+ engine (`about` RC); `RemoteComponents`.
**Acceptance:** card→detail morphs; a widget + QS tile exist and work; remotes show storage usage. **Impact:** medium.
**Effort:** L.

---

## Phase 3 exit criteria
- Full rclone power reachable in-app (typed Tier-2 groups + Tier-3 passthrough) with safety rails.
- Remote setup is schema-driven + crypt works.
- Sheets/conflicts follow the interaction + safety language.
- Notifications deep-link + act; large screens use list-detail panes.
- Signature polish (shared elements, widget, QS tile, remote health) shipped.
- Definition of Done passes across the board; on-device verified incl. a large-screen/foldable form factor.
