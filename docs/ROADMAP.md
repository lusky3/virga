# Virga — UI/UX Roadmap

> Execution plan for the vision in [`../BRAND.md`](../BRAND.md) + [`UX_VISION.md`](UX_VISION.md).
> Sequenced so **each phase ships a coherent improvement** — not a pile of quick wins.
>
> **Pre-production note:** Virga has no released users (see root `CLAUDE.md` → "Project Status"). DB schema changes
> may bump the version and rely on destructive migration; wiping app data between edits is acceptable. Do **not**
> burn effort on backward-compatible Room migrations unless convenient.

---

## The four highest-leverage moves

If only four things are done, do these — they address the four findings every review dimension flagged ("no
identity", "not alive", "broken new-user path", "unmet power-user promise"):

1. **Full branded color scheme + Material 3 Expressive theme** — Phase 0.
2. **Live sync visualization** (card strip + summary panel) — Phase 1.
3. **Guided first-sync wizard** (with returnable add-remote) — Phase 1.
4. **Tier-1 controls** the engine already supports: Filters, Mirror, Dry-run, transfers/checkers — Phase 2.

---

## Phases at a glance

| Phase | Theme | Goal | Detail |
|---|---|---|---|
| **0** | *One authoring hand* | Establish the design system everything builds on | [`phases/PHASE_0_DESIGN_SYSTEM.md`](phases/PHASE_0_DESIGN_SYSTEM.md) |
| **1** | *The app moves, the beginner succeeds* | Visible progress + a working first sync | [`phases/PHASE_1_ALIVE_AND_ONBOARDING.md`](phases/PHASE_1_ALIVE_AND_ONBOARDING.md) |
| **2** | *Power on demand, safely* | Realize progressive disclosure; close the data-model gap | [`phases/PHASE_2_RCLONE_DEPTH.md`](phases/PHASE_2_RCLONE_DEPTH.md) |
| **3** | *Full capability + polish* | Long-tail rclone depth, reach, large-screen craft | [`phases/PHASE_3_REACH_AND_POLISH.md`](phases/PHASE_3_REACH_AND_POLISH.md) |

**Ordering rationale:** Phase 0 is a hard prerequisite — every later screen pulls its tokens/components from
`core:designsystem`, so building features first means reworking them later. Phase 1 delivers the two things a sync
app is *judged* on (visible progress, successful onboarding). Phase 2 cashes in the capabilities already wired in the
engine. Phase 3 is the long tail and differentiating surfaces.

---

## Cross-cutting principles (apply in every phase)

- **Tokens only** — no raw colors/spacing/durations in features (BRAND §0, §15).
- **Status = color + glyph + text**, via `SyncStatusBadge` + `LocalVirgaColors` (BRAND §10).
- **Destructive = safety-railed** — dry-run-first, name the blast radius, error tint (BRAND §13).
- **One vocabulary** — Source/Destination/Remote/Task/Run/Two-way (BRAND §2).
- **Motion via tokens + respect reduce-motion** (BRAND §12).
- **A11y is done-criteria, not a follow-up** (BRAND §14).
- **Verify on a real device** in each flavor that matters; the project has a connected device workflow.

---

## Definition of done (every UI work item)

1. Uses design-system tokens only (no literals).
2. Light + dark + branded + dynamic schemes all verified.
3. Status shown as color + glyph + text.
4. Destructive paths follow BRAND §13 (preview/confirm/blast-radius).
5. Motion uses tokens and degrades under reduce-motion.
6. A11y per BRAND §14 (targets, semantics, TalkBack, large font).
7. `BRAND.md` updated in the same PR if the visual system changed.
8. Build + unit tests green; relevant flow verified on-device.

---

## How to work a phase doc

Each `phases/PHASE_N_*.md` lists **workstreams**. Each workstream has: **Why**, **Current state** (with `file · symbol`
refs), **Target**, **Steps**, **Files to create/modify**, **Acceptance criteria**, **BRAND refs**, and **impact/effort**.
A zero-knowledge agent should be able to pick a workstream and execute it end-to-end from that section alone. Do
workstreams roughly top-to-bottom within a phase; later ones often depend on earlier ones.

---

## Status tracking

Update this table as work lands (commit hash + date). Phases are not "done" until every workstream meets the
Definition of Done.

| Phase | Workstream | Status | Landed (commit) |
|---|---|---|---|
| 0 | Create `core:designsystem` module | ✅ done | 2026-05-31 |
| 0 | Full branded color schemes + dynamic-as-tint | ✅ done | 2026-05-31 |
| 0 | `LocalVirgaColors` semantic colors + status glyphs | ✅ done | 2026-05-31 |
| 0 | `VirgaSpacing` / `VirgaShapes` / `VirgaGradients` tokens | ✅ done | 2026-05-31 |
| 0 | `VirgaMotionScheme` tokens + shapes (Expressive wrap + font deferred) | ◐ partial | 2026-05-31 |
| 0 | `VirgaCard` + migrate existing cards | ✅ done | 2026-05-31 |
| 1 | Live sync visualization (card strip + summary panel) | ✅ done | 2026-05-31 |
| 1 | Guided first-sync wizard + returnable add-remote | ✅ done | 2026-06-01 |
| 1 | Unify task-creation entry points | ✅ done | 2026-05-31 |
| 1 | Nav transitions + predictive-back | ✅ done | 2026-05-31 |
| 1 | Teach the mental model in empty states + vocabulary | ✅ done | 2026-05-31 |
| 2 | Tier-1: Filters editor | ✅ done | 2026-05-31 |
| 2 | Tier-1: Mirror toggle (safety-railed) | ✅ done | 2026-05-31 |
| 2 | Tier-1: Dry-run / preview changes | ✅ done | 2026-06-01 |
| 2 | Tier-1: Performance preset (transfers/checkers) | ✅ done | 2026-05-31 |
| 2 | "Show advanced options" master toggle + default seeding | ✅ done | 2026-05-31 |
| 2 | Log viewer (write `logPath` + screen) | ✅ done | 2026-05-31 |
| 2 | File-browser multi-select → "Create task from selection" | ✅ done | 2026-05-31 |
| 2 | Shared contextual-action-mode primitive + haptics | ✅ done | 2026-05-31 |
| 3 | Tier-2 typed options (checksum/backup-dir/max-delete) + validated extraConfig passthrough | ✅ done | 2026-06-01 |
| 3 | config/providers typed remote fields + crypt-wrapping wizard | ✅ done | 2026-06-01 |
| 3 | Bottom-sheet Add Remote; conflicts selection bar (footgun removed) | ✅ done | 2026-06-01 |
| 3 | Notification Cancel/Retry actions + open-app intent (per-run deep link deferred) | ✅ done | 2026-06-01 |
| 3 | Adaptive list-detail panes (Sync→Summary; back-handler coordinated) | ✅ done | 2026-06-01 |
| 3 | Remote quota on cards (rclone about). Shared-elements / Glance widget / QS tile deferred (need device + new modules) | ◐ partial | 2026-06-01 |
