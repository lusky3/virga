# Ruflo — Claude Code Configuration

## Project Status

- **Pre-production / not released.** There are no real users and no published data.
- DB schema changes do **not** need clean Room migrations or backward compatibility.
  It's fine to bump the version and rely on destructive migration (wiping app data
  between edits is acceptable). Write a real `Migration` only when convenient, not
  out of necessity.

## Build, CI & code style (virga-specific)

Hard-won notes — these gate every PR and are expensive to rediscover. (The generic
`npm run build && npm test` in "Build & Test" below is boilerplate; this is the real
build.)

- **`:app` is flavored (`foss` / `play`).** Use `:app:compileFossDebugKotlin` /
  `:app:assembleFossDebug` (and `…Play…`), never the ambiguous `compileDebug`.
- **DI changes must be verified with `:app:hiltJavaCompileFossDebug` +
  `:app:hiltJavaCompilePlayDebug`.** `compile*Kotlin` does NOT run Hilt/Dagger graph
  aggregation, so a `MissingBinding` passes local Kotlin compile and only fails ~7min
  into CI's `build`. A **default value on an `@Inject` constructor param does not
  exempt it from Hilt** — it still needs a binding (use a `@Qualifier` + `@Provides`,
  e.g. for an injected `CoroutineDispatcher` test seam).
- **After changing a shared interface** (e.g. `RcloneEngine`, `WatchdogPlatform`),
  also build the dependent modules' test sources (`:sync-worker:compileDebugUnitTestKotlin`)
  and update hand-rolled test doubles.
- **Codacy = server-side detekt + Lizard, delta-scanned on changed lines** (no in-repo
  config; a line untouched for years is fine until you edit it). Limits: Lizard nloc 50
  / params >8 / CCN 8; detekt LongMethod 60, LongParameterList >6 (data-class ctors are
  exempt — bundle a composable's params into a state/callbacks holder), `LabeledExpression`
  (no `return@label` on new/edited lines — use a positive `if`/`?.let`), TooManyFunctions
  >11 per class (extract to file-scope funcs or a helper class; don't `@Suppress`),
  StringLiteralDuplication ≥3. detekt `@Suppress` is honored by detekt but **not** by Lizard.
  Decompose large Compose screens/dialogs into sub-composables to stay under nloc 50.
- **`codecov/patch` is a blocking gate.** Framework-bound Android entry points that unit
  tests can't reach without the runtime (Service / Activity / Glance widget / FileObserver
  / NetworkCallback / BroadcastReceiver hosts) are listed in `codecov.yml` `ignore:`.
  The rule: extract the pure logic (predicates, ViewModels, `resolve*`/format helpers) and
  unit-test *that*; ignore only the framework wiring. Validate edits via
  `curl --data-binary @codecov.yml https://codecov.io/validate`.
- **secret-scan (ggshield) + GitGuardian scan every commit in the PR range**, not just the
  head — fixing a flagged item in a later commit doesn't clear it; squash the branch first.
  No `passphrase`/`password`/`secret`-named identifiers (false positives) and no realistic
  token/key fixtures in tests (use `EXAMPLE_*` / obviously-fake values).
- The semgrep CWE-926 "exported activity/receiver" finding is a known false positive for
  the launcher/AppWidget/QS-tile/share entry points — justify with a SEC comment +
  `tools:ignore` (it has shipped this way before).

## Rules

- Do what has been asked; nothing more, nothing less
- NEVER create files unless absolutely necessary — prefer editing existing files
- NEVER create documentation files unless explicitly requested
- NEVER save working files or tests to root — use `/src`, `/tests`, `/docs`, `/config`, `/scripts`
- ALWAYS read a file before editing it
- NEVER commit secrets, credentials, or .env files
- NEVER add a `Co-Authored-By` trailer to user commits unless this project's `.claude/settings.json` has `attribution.commit` set (#2078). The Claude Code Bash tool may suggest one in its default commit-message template — ignore it. `Co-Authored-By` is semantic authorship attribution under git/GitHub convention; the tool is the facilitator, not a co-author.
- Keep files under 500 lines
- Validate input at system boundaries

## Agent Comms (SendMessage-First Coordination)

Named agents coordinate via `SendMessage`, not polling or shared state.

```
Lead (you) ←→ architect ←→ developer ←→ tester ←→ reviewer
              (named agents message each other directly)
```

### Spawning a Coordinated Team

```javascript
// ALL agents in ONE message, each knows WHO to message next
Agent({ prompt: "Research the codebase. SendMessage findings to 'architect'.",
  subagent_type: "researcher", name: "researcher", run_in_background: true })
Agent({ prompt: "Wait for 'researcher'. Design solution. SendMessage to 'coder'.",
  subagent_type: "system-architect", name: "architect", run_in_background: true })
Agent({ prompt: "Wait for 'architect'. Implement it. SendMessage to 'tester'.",
  subagent_type: "coder", name: "coder", run_in_background: true })
Agent({ prompt: "Wait for 'coder'. Write tests. SendMessage results to 'reviewer'.",
  subagent_type: "tester", name: "tester", run_in_background: true })
Agent({ prompt: "Wait for 'tester'. Review code quality and security.",
  subagent_type: "reviewer", name: "reviewer", run_in_background: true })

// Kick off the pipeline
SendMessage({ to: "researcher", summary: "Start", message: "[task context]" })
```

### Patterns

| Pattern | Flow | Use When |
|---------|------|----------|
| **Pipeline** | A → B → C → D | Sequential dependencies (feature dev) |
| **Fan-out** | Lead → A, B, C → Lead | Independent parallel work (research) |
| **Supervisor** | Lead ↔ workers | Ongoing coordination (complex refactor) |

### Rules

- ALWAYS name agents — `name: "role"` makes them addressable
- ALWAYS include comms instructions in prompts — who to message, what to send
- Spawn ALL agents in ONE message with `run_in_background: true`
- After spawning: STOP, tell user what's running, wait for results
- NEVER poll status — agents message back or complete automatically

## Swarm & Routing

### Config
- **Topology**: hierarchical-mesh (anti-drift)
- **Max Agents**: 15
- **Memory**: hybrid
- **HNSW**: Enabled
- **Neural**: Enabled

```bash
npx ruflo@latest swarm init --topology hierarchical --max-agents 8 --strategy specialized
```

### Agent Routing

| Task | Agents | Topology |
|------|--------|----------|
| Bug Fix | researcher, coder, tester | hierarchical |
| Feature | architect, coder, tester, reviewer | hierarchical |
| Refactor | architect, coder, reviewer | hierarchical |
| Performance | perf-engineer, coder | hierarchical |
| Security | security-architect, auditor | hierarchical |

### When to Swarm
- **YES**: 3+ files, new features, cross-module refactoring, API changes, security, performance
- **NO**: single file edits, 1-2 line fixes, docs updates, config changes, questions

### 3-Tier Model Routing

| Tier | Handler | Use Cases |
|------|---------|-----------|
| 1 | Agent Booster (WASM) | Simple transforms — skip LLM, use Edit directly |
| 2 | Haiku | Simple tasks, low complexity |
| 3 | Sonnet/Opus | Architecture, security, complex reasoning |

## Memory & Learning

### Before Any Task
```bash
npx ruflo@latest memory search --query "[task keywords]" --namespace patterns
npx ruflo@latest hooks route --task "[task description]"
```

### After Success
```bash
npx ruflo@latest memory store --namespace patterns --key "[name]" --value "[what worked]"
npx ruflo@latest hooks post-task --task-id "[id]" --success true --store-results true
```

### MCP Tools (use `ToolSearch("keyword")` to discover)

| Category | Key Tools |
|----------|-----------|
| **Memory** | `memory_store`, `memory_search`, `memory_search_unified` |
| **Bridge** | `memory_import_claude`, `memory_bridge_status` |
| **Swarm** | `swarm_init`, `swarm_status`, `swarm_health` |
| **Agents** | `agent_spawn`, `agent_list`, `agent_status` |
| **Hooks** | `hooks_route`, `hooks_post-task`, `hooks_worker-dispatch` |
| **Security** | `aidefence_scan`, `aidefence_is_safe`, `aidefence_has_pii` |
| **Hive-Mind** | `hive-mind_init`, `hive-mind_consensus`, `hive-mind_spawn` |

### Background Workers

| Worker | When |
|--------|------|
| `audit` | After security changes |
| `optimize` | After performance work |
| `testgaps` | After adding features |
| `map` | Every 5+ file changes |
| `document` | After API changes |

```bash
npx ruflo@latest hooks worker dispatch --trigger audit
```

## Agents

**Core**: `coder`, `reviewer`, `tester`, `planner`, `researcher`
**Architecture**: `system-architect`, `backend-dev`, `mobile-dev`
**Security**: `security-architect`, `security-auditor`
**Performance**: `performance-engineer`, `perf-analyzer`
**Coordination**: `hierarchical-coordinator`, `mesh-coordinator`, `adaptive-coordinator`
**GitHub**: `pr-manager`, `code-review-swarm`, `issue-tracker`, `release-manager`

Any string works as a custom agent type.

## Build & Test

- ALWAYS run tests after code changes
- ALWAYS verify build succeeds before committing

```bash
npm run build && npm test
```

## CLI Quick Reference

```bash
npx ruflo@latest init --wizard           # Setup
npx ruflo@latest swarm init --v3-mode     # Start swarm
npx ruflo@latest memory search --query "" # Vector search
npx ruflo@latest hooks route --task ""    # Route to agent
npx ruflo@latest doctor --fix             # Diagnostics
npx ruflo@latest security scan            # Security scan
npx ruflo@latest performance benchmark    # Benchmarks
```

26 commands, 140+ subcommands. Use `--help` on any command for details.

## Setup

```bash
claude mcp add ruflo -- npx -y ruflo@latest mcp start
npx ruflo@latest daemon start
npx ruflo@latest doctor --fix
```

**Agent tool** handles execution (agents, files, code, git). **MCP tools** handle coordination (swarm, memory, hooks). **CLI** is the same via Bash.
