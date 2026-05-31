# Virga — Design & Planning Docs

Start here. These documents define what Virga should look like, feel like, and become, in enough detail that a
contributor (human or agent) with **zero prior context** can pick up a workstream and execute it.

## Read in this order

1. **[`../BRAND.md`](../BRAND.md)** — the design system & brand bible. Identity ("Weather for your data"), color, type,
   motion, voice, components, status language, safety rails, accessibility, and the token/governance rules. *Read this
   before writing any Composable.*
2. **[`UX_VISION.md`](UX_VISION.md)** — product vision, the two users (beginner + power user), the honest current-state
   verdict, information architecture & flows, the **progressive-disclosure model** (the core mechanism), the missing-
   functionality and gesture findings, and the (settled) framework decision.
3. **[`ROADMAP.md`](ROADMAP.md)** — the phased execution plan, the four highest-leverage moves, cross-cutting
   principles, the Definition of Done, and a status-tracking table.
4. **Phase detail** (each is self-contained and actionable):
   - [`phases/PHASE_0_DESIGN_SYSTEM.md`](phases/PHASE_0_DESIGN_SYSTEM.md) — *foundation; build first.*
   - [`phases/PHASE_1_ALIVE_AND_ONBOARDING.md`](phases/PHASE_1_ALIVE_AND_ONBOARDING.md)
   - [`phases/PHASE_2_RCLONE_DEPTH.md`](phases/PHASE_2_RCLONE_DEPTH.md)
   - [`phases/PHASE_3_REACH_AND_POLISH.md`](phases/PHASE_3_REACH_AND_POLISH.md)

## How these were produced

A 6-dimension UI/UX review (information architecture & flows, visual/Material 3, missing functionality, rclone
power-user exposure, gestures/interaction patterns, framework evaluation) read the real source; every claim was
cross-checked against code before synthesis. References use `file · symbol` form because line numbers drift.

## Conventions in the phase docs

Each **workstream** has: **Why** · **Current state** (with file/symbol refs) · **Target** · **Steps** · **Files to
create/modify** · **Acceptance criteria** · **BRAND refs** · **impact/effort**. Work them roughly top-to-bottom within
a phase. Update the status table in `ROADMAP.md` (with commit hash) as items land, and update `BRAND.md` in the same
PR whenever the visual system changes.

## Project reality (don't forget)

Virga is **pre-production** (see root `CLAUDE.md` → "Project Status"): no released users, so DB schema changes can bump
the version and rely on destructive migration — wiping app data between edits is acceptable. Don't spend effort on
backward-compatible Room migrations unless convenient.
