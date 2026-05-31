# Phase 1 — Make It Alive + Safe Onboarding

**Theme:** *The app moves, the beginner succeeds.* **Goal:** deliver the two things a sync app is judged on —
**visible progress** and a **working first sync** — on top of the Phase 0 design system.

> Prereqs: Phase 0 complete (tokens, `VirgaCard` `active` state, motion tokens, `LocalVirgaColors`).
> Read [`../../BRAND.md`](../../BRAND.md) §10 (status), §12 (motion), and [`../UX_VISION.md`](../UX_VISION.md) §3 (flows).

---

## WS1.1 — Live sync visualization (the signature feature)

**Why:** UX_VISION finding #1 — a running sync is invisible in-app today. **Current:** `SyncProgress` (bytes, totalBytes,
speed, files, totalFiles, ETA, errors — see `core/common/.../model` + `RcloneEngineImpl.runJobWithProgress`) is collected
in `sync-worker/.../SyncWorker.doWork` and pushed **only** to `SyncNotifications`. No Compose screen observes it; the
card shows a `SyncStatusBadge` pill + Cancel. **Target:** the home list and the task summary visibly show live transfer.

**Design (BRAND §10, §12 precipitation):**
- `SyncTaskCard` in `active` state: a **wavy `LinearProgressIndicator`** (Expressive) tinted `LocalVirgaColors.running`
  + a compact metrics line: `"37% · 12/40 files · 4.1 MB/s · ETA 2m"`. Indeterminate (listing phase) → Expressive
  loading indicator.
- `SyncTaskSummaryScreen`: a rich live panel (larger progress, current file if available, speed/ETA, Cancel).

**The hard part — getting `SyncProgress` to the UI.** The worker runs in WorkManager, not the VM. Options (pick one,
document it):
1. **WorkManager progress** — `setProgress(workDataOf(...))` in `SyncWorker`; observe via
   `WorkManager.getWorkInfosForUniqueWorkFlow(uniqueName)` in the VM, keyed by task id (the scheduler already uses
   `SyncWorker.UNIQUE_PREFIX + taskId`). Clean, survives process death. **Recommended.**
2. A shared in-memory `SyncProgressBus` (`@Singleton` `MutableStateFlow<Map<Long,SyncProgress>>`) written by the
   worker, read by the VM. Simpler but lost on process death.

**Steps:**
1. In `SyncWorker`, on each progress emission call `setProgress()` with bytes/files/speed/eta/percent + a phase enum
   (listing/transferring). Keep the notification path.
2. Add a `progressFor(taskId): Flow<SyncProgress?>` to a repository/use-case backed by the chosen mechanism.
3. `SyncTasksViewModel.uiState` joins `progressFor` per task → `SyncTaskCard` renders `active` state + metrics when a
   run is RUNNING.
4. `SyncTaskSummaryViewModel` exposes the live panel.
5. Use the precipitation motion token; degrade to a static determinate bar under reduce-motion (BRAND §12).

**Files:** `SyncWorker.kt`, new progress repository/bus, `SyncTasksViewModel.kt`, `SyncTaskCard.kt`,
`SyncTaskSummaryViewModel.kt`, `SyncTaskSummaryScreen.kt`, `VirgaCard` `active` state (Phase 0).

**Acceptance:** starting a sync shows a live wavy progress + accurate metrics on the card *and* summary, updating in
real time, ending in a success tick (BRAND §12) or an error state; survives backgrounding (if mechanism #1).
**Impact:** high. **Effort:** L.

---

## WS1.2 — Guided "Set up your first sync" wizard + returnable add-remote

**Why:** UX_VISION finding #4 — the first-run path dead-ends (empty Sync → blank editor → empty remote dropdown → tab
jump that abandons the form). **Current:** `startRoute = SyncRoute`; onboarding ends dumping the user on Sync;
`SyncTaskEditScreen.onNavigateToRemotes` jumps tabs to `RemotesRoute`. **Target:** a single guided funnel; "add a
remote" is a *returnable sub-flow*.

**Steps:**
1. New `FirstSyncWizardRoute` + screen: stepper (what is a sync → add account → source → destination → direction/
   schedule → done). Reuse Tier-0 editor controls; reuse the local/remote folder pickers.
2. **Returnable add-remote:** use the Navigation 3 result pattern (the codebase already has a precedent —
   `RemoteFolderPickStore` hands a picked folder back to the editor). Add an analogous `PendingRemoteResult` so adding
   a remote pops back into the wizard with the new remote preselected. Never lose wizard state.
3. Launch the wizard from (a) onboarding's final step (real CTA, not a dump on Sync) and (b) the Sync empty state's
   primary button.
4. On finish: create the task, navigate to its summary with a "Back up now" CTA.

**Files:** new `FirstSyncWizardScreen`/`ViewModel` in `feature/sync` (or `feature/onboarding`); `VirgaNavHost.kt`
(route + returnable result wiring); `OnboardingScreen.kt` (final CTA); `SyncTasksScreen.kt` (empty-state action);
result holder in `core:data`.

**Acceptance:** a cold-install user reaches a working, running first sync in < 1 minute without tab-jumping or losing
input; adding a remote mid-wizard returns with it preselected. **Impact:** high. **Effort:** M–L.

---

## WS1.3 — Unify task-creation entry points

**Why:** UX_VISION §3.4 — entry points diverge and drop context. **Current:** `RemoteCard` "New task"
(`VirgaNavHost.kt` → `onCreateTask`) navigates to a blank editor and **throws the remote away**; the good
prefill-capable path is browse → "Sync this folder" (`onSyncFolder` → `TaskEditRoute(prefillRemote, prefillRemotePath)`).
**Target:** every create affordance funnels through the prefill-capable editor with full context.

**Steps:**
1. Change `RemoteCard`'s "New task" to pass the remote name into `TaskEditRoute(prefillRemote = remote)`.
2. Audit all `TaskEditRoute(0)` / create entries (Sync FAB, empty state, browser) → ensure each passes available
   context; the editor's `load()` already applies `prefillRemote`/`prefillRemotePath` once.
3. The file-browser "Create task from selection" (Phase 2 WS) will also funnel here.

**Files:** `RemoteComponents.kt`/`RemotesScreen.kt`, `VirgaNavHost.kt`, `SyncTasksScreen.kt`.

**Acceptance:** creating a task from a remote pre-selects that remote; no entry point opens a fully blank editor when
context exists. **Impact:** high. **Effort:** M.

---

## WS1.4 — Navigation motion + predictive-back

**Why:** BRAND §12 — `NavDisplay` uses default crossfade; this is the highest visual-polish-per-line change.
**Current:** `VirgaNavHost.kt · NavDisplay` has no `transitionSpec`. **Target:** shared-axis transitions + predictive
back.

**Steps:**
1. Add `transitionSpec` + `popTransitionSpec` + `predictivePopTransitionSpec` to `NavDisplay` using the Phase 0 nav
   motion tokens (shared X-axis slide/fade).
2. Ensure `android:enableOnBackInvokedCallback` is set (it is) so predictive back animates.
3. Respect reduce-motion (fall back to fade).

**Files:** `VirgaNavHost.kt`, motion tokens (Phase 0).

**Acceptance:** forward/back navigation slides with predictive-back preview; reduce-motion falls back to fade.
**Impact:** high. **Effort:** S.

---

## WS1.5 — Teach the mental model in empty states + unify vocabulary

**Why:** UX_VISION §3.2 — Remote/Task/Run is never taught at the point of decision; vocabulary is inconsistent.
**Current:** empty states are minimal; "bisync" leaks; direction labels duplicated. **Target:** empty states teach;
one vocabulary (BRAND §2).

**Steps:**
1. Rewrite the Sync and Remotes empty states (via `EmptyState`) to connect the concepts and offer the next action
   (Sync empty → launch wizard; Remotes empty → add account). Hero empty states may use the §4.5 gradient.
2. Extend the editor's existing inline direction-hint pattern to also gloss Remote and Two-way.
3. Apply the vocabulary table everywhere: rename "Bisync" → "Two-way sync" in UI strings; standardize
   Source/Destination labels; reuse one direction label resource across card + summary + editor.

**Files:** `SyncTasksScreen.kt`, `RemotesScreen.kt`, `SyncTaskEditScreen.kt`/`SyncTaskEditAdvanced.kt`, sync/remotes
`res/values/strings.xml`, `SyncTaskCard.kt`, `SyncTaskSummaryScreen.kt`.

**Acceptance:** a new user can explain Remote vs Task vs Run from the empty states alone; "bisync" appears nowhere at
Tier 0–1; labels are consistent across screens. **Impact:** medium. **Effort:** S.

---

## Phase 1 exit criteria
- A running sync is visibly alive on the card and summary, with accurate live metrics and a success tick.
- A cold-install user reaches a working first sync in < 1 minute via the wizard, with a returnable add-remote.
- All create-task entries carry context (no blank editor when a remote/path is known).
- Navigation animates with predictive-back; reduce-motion respected.
- Empty states teach the model; one vocabulary throughout.
- Definition of Done (ROADMAP) passes; verified on-device.
