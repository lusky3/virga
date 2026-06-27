# Failing-SD-Card Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Virga *bound, diagnose, and survive* a flaky/failing local source (an SD card that goes read-only on I/O errors), instead of silently hanging or failing an entire backup on one unreadable file.

**Architecture:** Three layers, fixed in order. **Phase 1 (diagnostics):** surface the in-flight filename rclone already reports and turn the opaque "stalled 120s" abort into a precise, source-aware message via a new `VirgaError.Stall`. **Phase 2 (preserve + bound):** a soft-stall on a copy/backup records *partial success* (keep the good files) instead of total failure, and the SAF staging copy gains a per-file read timeout so it can no longer hang forever. **Phase 3 (backstops + opt-ins):** a process-kill backstop for a truly wedged daemon, an allowlist for `MaxDuration`/`CutoffMode`, and a sample-read preflight that warns early.

**Tech Stack:** Kotlin, coroutines/Flow, Hilt/Dagger, rclone RC API (JSON over HTTP), WorkManager, kotlinx.serialization JSON. Tests: JUnit4 + MockK + Turbine (`:core:rclone`), JUnit5 + Truth (`:sync-worker` pure logic), Robolectric + Truth (`:sync-worker` SAF code), JUnit4 + Truth (`:core:common`).

## Global Constraints

- **Pre-production:** DB schema changes may use destructive migration (bump version; no clean `Migration` required). Wiping app data between edits is acceptable.
- **`:app` is flavored (`foss`→`github`/`fdroid`, plus `play`).** The modules touched here (`core:common`, `core:rclone`, `sync-worker`) are **not** flavored — their tests run via `:<module>:testDebugUnitTest`.
- **DI changes must be verified under the Hilt graph build:** `:app:hiltJavaCompileFossDebug` AND `:app:hiltJavaCompilePlayDebug` (plain `compile*Kotlin` skips Dagger aggregation). A default value on an `@Inject` constructor param does **not** exempt it from needing a binding.
- **After changing a shared interface** (`RcloneEngine`, `SyncProgress`, `VirgaError`): also build dependent test sources `:sync-worker:compileDebugUnitTestKotlin` and update hand-rolled test doubles.
- **Codacy (server-side detekt + Lizard, delta-scanned on changed lines):** Lizard nloc ≤ 50 / params ≤ 8 / CCN ≤ 8; detekt LongMethod ≤ 60, LongParameterList ≤ 6 (data-class ctors exempt), TooManyFunctions ≤ 11 per class, **no `return@label` on new/edited lines** (use a positive `if`/`?.let`), StringLiteralDuplication flags a ≥5-char literal repeated ≥3×. detekt `@Suppress` is honored by detekt but **not** Lizard.
- **`codecov/patch` is a blocking gate.** `LocalStaging.kt`, `RcloneEngineImpl.kt`, `RcloneJson.kt`, `SyncWorker.kt` are **NOT** in `codecov.yml` `ignore:` — new logic in them needs unit-test coverage. Keep new pure logic in testable suspend helpers with an injectable `DispatcherProvider` seam.
- **User-facing strings:** route any repeated literal through string resources or a single `const`/helper to avoid StringLiteralDuplication. No `passphrase`/`password`/`secret`-named identifiers; no realistic token fixtures in tests (`EXAMPLE_*`).
- **Commit message trailers:** do NOT add a `Co-Authored-By` trailer (this repo's `.claude/settings.json` has no `attribution.commit`).
- **Mirror/move safety is a HARD invariant:** a stall or any unread source file must STILL hard-fail a mirror (`deleteExtraneous`) or move (`deleteSource`) or bisync run — a "partial success" there could delete cloud files whose source was never read. Every soft-success path is gated on `tolerateFileErrors == true`.

---

## File Structure

**Phase 1 — diagnostics**
- `core/common/src/main/kotlin/app/lusk/virga/core/common/error/VirgaError.kt` — MODIFY: add `Stall` subtype.
- `core/common/src/main/kotlin/app/lusk/virga/core/common/model/Models.kt` — MODIFY: add `SyncProgress.transferringNames` + `SyncProgress.stalledFile`.
- `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneJson.kt` — MODIFY: parse `transferring[].name` in `toSyncProgress()`.
- `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngineImpl.kt` — MODIFY: track last in-flight name; throw `VirgaError.Stall(file=…)`.
- `sync-worker/src/main/kotlin/app/lusk/virga/sync/SyncWorker.kt` — MODIFY: source-aware stall message; keep local-source stalls non-retryable.

**Phase 2 — preserve + bound**
- `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngineImpl.kt` — MODIFY: soft-stall → partial-success terminal emission (copy/backup only).
- `sync-worker/src/main/kotlin/app/lusk/virga/sync/SyncWorker.kt` — MODIFY: fold `stalledFile` into the partial-success failed-files record.
- `sync-worker/src/main/kotlin/app/lusk/virga/sync/LocalStaging.kt` — MODIFY: inject `DispatcherProvider`; per-file read timeout w/ close-on-timeout; `CopyTally.timeouts`; `StagedSource.readTimeouts`.

**Phase 3 — backstops + opt-ins**
- `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/daemon/RcloneDaemonManager.kt` + `RcloneEngineImpl.kt` — MODIFY: process-kill backstop when a stopped job won't clear and this is the last lease.
- `sync-worker/src/main/kotlin/app/lusk/virga/sync/ExtraConfigParser.kt` — MODIFY: allowlist `MaxDuration` + `CutoffMode`.
- `sync-worker/src/main/kotlin/app/lusk/virga/sync/SourceHealthCheck.kt` — CREATE: sample-read preflight.
- `sync-worker/src/main/kotlin/app/lusk/virga/sync/SyncWorker.kt` — MODIFY: call the preflight before staging an upload.

---

## Phase 1 — Diagnose the stall (name the file, source-aware message)

### Task 1.1: Surface in-flight filenames on `SyncProgress`

**Files:**
- Modify: `core/common/src/main/kotlin/app/lusk/virga/core/common/model/Models.kt` (the `SyncProgress` data class, currently ends at the `statsGroup` field ~line 277)
- Modify: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneJson.kt:67-77` (`toSyncProgress()`)
- Test: `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/RcloneJsonTest.kt` (create if absent)

**Interfaces:**
- Produces: `SyncProgress.transferringNames: List<String>` (default `emptyList()`) — names of files rclone reports in-flight this tick. `SyncProgress.stalledFile: String?` (default `null`) — set later (Task 2.1) on a soft-stall terminal emission. `JsonObject.toSyncProgress()` keeps its signature; now also reads the `transferring` array.

- [ ] **Step 1: Write the failing test for parsing `transferring[].name`**

Create `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/RcloneJsonTest.kt`:

```kotlin
package app.lusk.virga.core.rclone

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class RcloneJsonTest {

    @Test
    fun `toSyncProgress extracts in-flight transferring names`() {
        val stats = buildJsonObject {
            put("bytes", 10L)
            put("transfers", 1)
            put("transferring", buildJsonArray {
                add(buildJsonObject { put("name", "DCIM/2019/IMG_4471.jpg") })
                add(buildJsonObject { put("name", "DCIM/2019/IMG_4472.jpg") })
            })
        }
        val progress = stats.toSyncProgress()
        assertThat(progress.transferringNames)
            .containsExactly("DCIM/2019/IMG_4471.jpg", "DCIM/2019/IMG_4472.jpg")
    }

    @Test
    fun `toSyncProgress yields empty names when transferring absent`() {
        val progress = buildJsonObject { put("bytes", 0L) }.toSyncProgress()
        assertThat(progress.transferringNames).isEmpty()
    }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile (field doesn't exist yet)**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "app.lusk.virga.core.rclone.RcloneJsonTest" -q`
Expected: FAIL — compilation error, `transferringNames` is not a member of `SyncProgress`.

- [ ] **Step 3: Add the fields to `SyncProgress`**

In `Models.kt`, add these two fields to `SyncProgress` immediately after the `statsGroup` field (keep them last so the data-class ctor stays exempt from LongParameterList):

```kotlin
    /** Names of files rclone reported in-flight on the tick this snapshot was built
     *  (rclone `core/stats.transferring[].name`). Empty on terminal/dry-run emissions.
     *  Used by the stall guard to name the file a wedged read was stuck on. */
    val transferringNames: List<String> = emptyList(),
    /** On a soft-stall partial-success terminal emission (copy/backup only), the file
     *  whose read wedged the run, so the worker can record it in the failed-files list.
     *  Null on every other emission. */
    val stalledFile: String? = null,
```

- [ ] **Step 4: Parse `transferring[].name` in `toSyncProgress()`**

In `RcloneJson.kt`, replace the `toSyncProgress()` body so it also reads the array. The new last argument:

```kotlin
/** Maps a `core/stats` (or job/status final stats) JSON object into [SyncProgress]. */
internal fun JsonObject.toSyncProgress(): SyncProgress = SyncProgress(
    bytesTransferred = this["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
    totalBytes = this["totalBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
    speedBytesPerSec = this["speed"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
    transferredFiles = this["transfers"]?.jsonPrimitive?.intOrNull ?: 0,
    totalFiles = this["totalTransfers"]?.jsonPrimitive?.intOrNull ?: 0,
    etaSeconds = this["eta"]?.jsonPrimitive?.longOrNull,
    errors = this["errors"]?.jsonPrimitive?.intOrNull ?: 0,
    deletes = this["deletes"]?.jsonPrimitive?.intOrNull ?: 0,
    transferringNames = (this["transferring"] as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
        ?: emptyList(),
)
```

Add the imports at the top of `RcloneJson.kt` if not already present: `import kotlinx.serialization.json.JsonArray` and `import kotlinx.serialization.json.contentOrNull`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "app.lusk.virga.core.rclone.RcloneJsonTest" -q`
Expected: PASS (2 tests).

- [ ] **Step 6: Rebuild dependent test sources (shared model changed)**

Run: `./gradlew :sync-worker:compileDebugUnitTestKotlin :core:rclone:compileDebugUnitTestKotlin -q`
Expected: BUILD SUCCESSFUL (the new fields have defaults, so existing `SyncProgress(...)` constructions and test doubles still compile).

- [ ] **Step 7: Commit**

```bash
git add core/common/src/main/kotlin/app/lusk/virga/core/common/model/Models.kt \
        core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneJson.kt \
        core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/RcloneJsonTest.kt
git commit -m "feat(sync): surface in-flight transferring filenames on SyncProgress"
```

---

### Task 1.2: Add the `VirgaError.Stall` error type

**Files:**
- Modify: `core/common/src/main/kotlin/app/lusk/virga/core/common/error/VirgaError.kt`
- Modify: `core/common/src/main/kotlin/app/lusk/virga/core/common/error/VirgaErrorExt.kt` (the `toUserMessage()` `when` is exhaustive over the sealed type — a new subtype requires a branch)
- Test: `core/common/src/test/kotlin/app/lusk/virga/core/common/error/VirgaErrorTest.kt` (already exists; APPEND; it has its own exhaustive `when` in `sealed when covers all subtypes`)

**Interfaces:**
- Produces: `VirgaError.Stall(val file: String? = null, message: String, cause: Throwable? = null) : VirgaError` — thrown by the engine when the stall guard fires on a non-tolerant run, carrying the in-flight filename (nullable).

> **Exhaustiveness note:** `VirgaError` is a sealed class with exhaustive `when` sites. Adding `Stall` breaks `core/common/.../error/VirgaErrorExt.kt:toUserMessage()` AND the test's `sealed when covers all subtypes` — both need an `is VirgaError.Stall ->` branch (Steps 3b/3c below) or `:core:common:compileDebugKotlin` fails before any test runs.

- [ ] **Step 1: Write the failing test**

Create `core/common/src/test/kotlin/app/lusk/virga/core/common/error/VirgaErrorTest.kt`:

```kotlin
package app.lusk.virga.core.common.error

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VirgaErrorTest {
    @Test
    fun `Stall carries the in-flight file and message`() {
        val e = VirgaError.Stall(file = "DCIM/IMG_1.jpg", message = "stalled")
        assertThat(e).isInstanceOf(VirgaError::class.java)
        assertThat(e.file).isEqualTo("DCIM/IMG_1.jpg")
        assertThat(e.message).isEqualTo("stalled")
    }

    @Test
    fun `Stall file defaults to null`() {
        assertThat(VirgaError.Stall(message = "stalled").file).isNull()
    }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `./gradlew :core:common:testDebugUnitTest --tests "app.lusk.virga.core.common.error.VirgaErrorTest" -q`
Expected: FAIL — `Stall` is not a member of `VirgaError`.

- [ ] **Step 3: Add the `Stall` subtype**

In `VirgaError.kt`, add this class inside the sealed class, immediately after the `Rclone` subtype:

```kotlin
    /** An in-flight transfer made zero progress past the stall window — typically a
     *  source read wedged on a failing disk/SD card. [file] is the file rclone was
     *  reading when it stalled, when known. Non-retryable: re-running hammers the same
     *  unreadable region. */
    class Stall(val file: String? = null, message: String, cause: Throwable? = null) :
        VirgaError(message, cause)
```

- [ ] **Step 3b: Add the `Stall` branch to `toUserMessage()`**

In `VirgaErrorExt.kt`, add a branch to the `when (this)` in `fun VirgaError.toUserMessage()` (place it just before `is VirgaError.Conflict`), matching the `Rclone` pattern of surfacing the carried message with a generic fallback:

```kotlin
    is VirgaError.Stall ->
        message.ifBlank { "The transfer stalled — the source may be slow or failing. Try again." }
```

- [ ] **Step 3c: Add the `Stall` case to the test's exhaustiveness check**

In `VirgaErrorTest.kt`, in `sealed when covers all subtypes`: add `VirgaError.Stall(message = "st")` to the `errors` list (after the `Rclone` entry), add `is VirgaError.Stall -> "stall"` to the inner `when` (after the `Rclone` branch), and add `"stall"` to the `containsExactly(...)` assertion in the matching position.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:common:testDebugUnitTest --tests "app.lusk.virga.core.common.error.VirgaErrorTest" -q`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add core/common/src/main/kotlin/app/lusk/virga/core/common/error/VirgaError.kt \
        core/common/src/test/kotlin/app/lusk/virga/core/common/error/VirgaErrorTest.kt
git commit -m "feat(error): add VirgaError.Stall carrying the wedged filename"
```

---

### Task 1.3: Throw `VirgaError.Stall` with the in-flight filename from the stall guard

**Files:**
- Modify: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngineImpl.kt:713-752` (the stall-guard loop)
- Test: `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/RcloneEngineImplTest.kt` (add a case near the existing stall-guard section, ~line 823)

**Interfaces:**
- Consumes: `SyncProgress.transferringNames` (Task 1.1), `VirgaError.Stall` (Task 1.2).
- Produces: on a stall with `tolerateFileErrors == false`, the flow throws `VirgaError.Stall(file = <first in-flight name or null>, message = "Sync stalled — no progress for 120s…")`.

> **Note on testing the wall-clock guard:** the existing suite documents (RcloneEngineImplTest ~line 823) that the guard uses `System.currentTimeMillis()`, so virtual time can't drive it without a real 120s sleep. We therefore make `STALL_TIMEOUT_MS` injectable for tests in this task so the timeout can be set to `0L`, letting a second flat poll trip the guard immediately.

- [ ] **Step 1: Make the stall timeout injectable (test seam) — write the failing test first**

Add to `RcloneEngineImplTest.kt` in the stall-guard section. This assumes the same `engine`, `apiClient`, `daemonManager`, `configManager`, `fakeDaemon`, `testDispatcher` fixtures the existing stall test uses:

```kotlin
    @Test fun `a real stall throws VirgaError_Stall naming the in-flight file`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "sync/copy", any()) } returns
            buildJsonObject { put("jobid", 9) }
        // Never finishes.
        coEvery { apiClient.call(any(), any(), any(), "job/status", any()) } returns
            buildJsonObject { put("finished", false) }
        // Flat stats (zero progress) but a named in-flight file each tick.
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } returns buildJsonObject {
            put("bytes", 0L); put("checks", 0)
            put("transferring", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("name", "DCIM/IMG_BAD.jpg") })
            })
        }
        // job/stop in the finally.
        coEvery { apiClient.call(any(), any(), any(), "job/stop", any()) } returns buildJsonObject {}

        engine.startDaemon()

        val error = engine.sync(
            "local:/x", "gdrive:x",
            SyncOptions(SyncDirection.UPLOAD), stallTimeoutMs = 0L,
        ).test {
            // First poll arms the clock; second poll (still flat, 0ms window) trips it.
            awaitItem() // first running emission
            awaitError()
        }
        assertThat(error).isInstanceOf(VirgaError.Stall::class.java)
        assertThat((error as VirgaError.Stall).file).isEqualTo("DCIM/IMG_BAD.jpg")
    }
```

Add imports if missing: `import app.lusk.virga.core.common.error.VirgaError` and `import com.google.common.truth.Truth.assertThat`.

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*RcloneEngineImplTest" -q`
Expected: FAIL — `sync(...)` has no `stallTimeoutMs` parameter; `VirgaError.Stall` not thrown.

- [ ] **Step 3: Thread a `stallTimeoutMs` parameter through `sync`/`bisync`/`check` → `runJobWithProgress`**

In `RcloneEngineImpl.kt`: add an internal default parameter so production keeps `STALL_TIMEOUT_MS` and only tests override it. Change `runJobWithProgress`'s signature and the public `sync`/`bisync`/`check` that call it.

`runJobWithProgress` signature becomes:

```kotlin
    private fun runJobWithProgress(
        tolerateFileErrors: Boolean = false,
        stallTimeoutMs: Long = STALL_TIMEOUT_MS,
        start: suspend (RcloneDaemon) -> JsonObject,
    ): Flow<SyncProgress> = flow {
```

For each public entry point that calls `runJobWithProgress`, add a `stallTimeoutMs: Long = STALL_TIMEOUT_MS` parameter and forward it. Example for `sync` (RcloneEngineImpl.kt ~line 413):

```kotlin
    override fun sync(
        source: String,
        dest: String,
        options: SyncOptions,
        stallTimeoutMs: Long,
    ): Flow<SyncProgress> =
        runJobWithProgress(
            tolerateFileErrors = !options.deleteExtraneous && !options.deleteSource,
            stallTimeoutMs = stallTimeoutMs,
        ) { d -> /* existing start lambda unchanged */ }
```

Add `stallTimeoutMs: Long = STALL_TIMEOUT_MS` to the `RcloneEngine` interface declarations for `sync`, `bisync`, and `check` (the interface needs the default so existing callers in `SyncExecutor` compile unchanged). In `RcloneEngine.kt`, reference the constant via the companion you expose, or simply declare the default inline as `120_000L` with a doc comment — keep ONE definition: prefer adding `STALL_TIMEOUT_MS` to a shared location. To avoid duplicating the literal `120_000L` (StringLiteralDuplication is about strings, not numbers, but DRY still applies), expose it:

In `RcloneEngine.kt` companion (create one if absent):

```kotlin
interface RcloneEngine {
    companion object {
        /** Default stall window: abort a job that makes zero progress this long. */
        const val DEFAULT_STALL_TIMEOUT_MS = 120_000L
    }
    // …existing members, with the three flows gaining:
    //   stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS
}
```

Then in `RcloneEngineImpl.kt` change the companion `const val STALL_TIMEOUT_MS = 120_000L` to reference the interface const:

```kotlin
        const val STALL_TIMEOUT_MS = RcloneEngine.DEFAULT_STALL_TIMEOUT_MS
```

- [ ] **Step 4: Track the last in-flight name and throw `Stall` with it**

In the stall-guard loop, add a tracker alongside the existing sentinels (after `var lastProgressAtMs = Long.MAX_VALUE`, ~line 717):

```kotlin
        var lastTransferringName: String? = null
```

Inside the loop, capture the current in-flight name on every poll (right after `val progress = stats.toSyncProgress()`, ~line 736):

```kotlin
                progress.transferringNames.firstOrNull()?.let { lastTransferringName = it }
```

Replace the stall throw (lines 748-752) and use the injected `stallTimeoutMs`:

```kotlin
                } else if (System.currentTimeMillis() - lastProgressAtMs > stallTimeoutMs) {
                    throw VirgaError.Stall(
                        file = lastTransferringName,
                        message = stallMessage(stallTimeoutMs, lastTransferringName),
                    )
                }
```

Add a small private helper near the companion (keeps the throw site short for Lizard CCN and centralises the string):

```kotlin
    private fun stallMessage(timeoutMs: Long, file: String?): String {
        val base = "Sync stalled — no progress for ${timeoutMs / 1000}s."
        return if (file != null) "$base Last read: $file" else base
    }
```

Add the import `import app.lusk.virga.core.common.error.VirgaError` if not already present.

- [ ] **Step 5: Run the new test plus the existing stall test to verify both pass**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*RcloneEngineImplTest" -q`
Expected: PASS — the new `…throws VirgaError_Stall naming the in-flight file` and the existing `a check-only phase does not trip the stall guard`.

- [ ] **Step 6: Update `SyncExecutor` call sites if the compiler requires it**

The interface defaults mean `SyncExecutor` should compile unchanged. Verify:

Run: `./gradlew :sync-worker:compileDebugUnitTestKotlin -q`
Expected: BUILD SUCCESSFUL. If it fails because a hand-rolled `RcloneEngine` test double doesn't implement the new default param, add `stallTimeoutMs: Long` to that double's overrides (search: `grep -rn "override fun sync(" sync-worker/src/test`).

- [ ] **Step 7: Commit**

```bash
git add core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngine.kt \
        core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngineImpl.kt \
        core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/RcloneEngineImplTest.kt
git commit -m "feat(sync): name the wedged file in stall aborts via VirgaError.Stall"
```

---

### Task 1.4: Source-aware stall message + keep local-source stalls non-retryable in `SyncWorker`

**Files:**
- Modify: `sync-worker/src/main/kotlin/app/lusk/virga/sync/SyncWorker.kt` (the `else` failure branch ~line 383-398, and `retryDecision` ~line 435-442)
- Test: `sync-worker/src/test/kotlin/app/lusk/virga/sync/SyncWorkerStallMessageTest.kt` (create) — exercises the pure message/retry helpers, not the framework worker.

**Interfaces:**
- Consumes: `VirgaError.Stall` (Task 1.2).
- Produces: two pure helpers usable without the Android worker runtime:
  - `fun stallUserMessage(error: VirgaError.Stall, direction: SyncDirection, sourceIsLocal: Boolean): String`
  - `retryDecision` treats `VirgaError.Stall` as non-retryable (returns `Result.failure()`), independent of `retryOnRclone`.

> **Why pure helpers:** `SyncWorker` is a framework-bound `CoroutineWorker` (hard to unit-test the orchestration). Extracting the message/retry logic into file-scope pure functions keeps them in the codecov patch *and* testable. This mirrors the codebase's "extract the pure logic, test that" rule.

- [ ] **Step 1: Write the failing test**

Create `sync-worker/src/test/kotlin/app/lusk/virga/sync/SyncWorkerStallMessageTest.kt`:

```kotlin
package app.lusk.virga.sync

import app.lusk.virga.core.common.error.VirgaError
import app.lusk.virga.core.common.model.SyncDirection
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SyncWorkerStallMessageTest {

    @Test
    fun `upload stall on a local source blames the card and names the file`() {
        val msg = stallUserMessage(
            VirgaError.Stall(file = "DCIM/IMG_BAD.jpg", message = "stalled"),
            direction = SyncDirection.UPLOAD,
            sourceIsLocal = true,
        )
        assertThat(msg).contains("DCIM/IMG_BAD.jpg")
        assertThat(msg).contains("card")
    }

    @Test
    fun `download stall points at the connection, not the card`() {
        val msg = stallUserMessage(
            VirgaError.Stall(file = null, message = "stalled"),
            direction = SyncDirection.DOWNLOAD,
            sourceIsLocal = false,
        )
        assertThat(msg).contains("connection")
        assertThat(msg).doesNotContain("card")
    }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `./gradlew :sync-worker:testDebugUnitTest --tests "app.lusk.virga.sync.SyncWorkerStallMessageTest" -q`
Expected: FAIL — `stallUserMessage` is unresolved.

- [ ] **Step 3: Add the file-scope `stallUserMessage` helper**

At the bottom of `SyncWorker.kt` (file scope, below the class), add:

```kotlin
/**
 * User-facing copy for a [VirgaError.Stall]. An upload that wedged reading a local/staged
 * source is almost always failing storage (e.g. an SD card going read-only), so we say so
 * and name the file; a download/remote-side stall points at the connection instead.
 */
internal fun stallUserMessage(
    error: VirgaError.Stall,
    direction: SyncDirection,
    sourceIsLocal: Boolean,
): String {
    val file = error.file
    val fileSuffix = if (file != null) " (last read: $file)" else ""
    return if (direction != SyncDirection.DOWNLOAD && sourceIsLocal) {
        "Couldn't read your source$fileSuffix — the card or drive may be failing. " +
            "Copy your files off and replace it."
    } else {
        "The transfer stalled$fileSuffix — check your connection and try again."
    }
}
```

- [ ] **Step 4: Run the message tests to verify they pass**

Run: `./gradlew :sync-worker:testDebugUnitTest --tests "app.lusk.virga.sync.SyncWorkerStallMessageTest" -q`
Expected: PASS (2 tests).

- [ ] **Step 5: Write the failing retry-decision test**

Append to `SyncWorkerStallMessageTest.kt` a test that drives `retryDecision`. Since `retryDecision` is currently `private`, this step also makes it `internal`. Add:

```kotlin
    @Test
    fun `a stall is never retried even when retryOnRclone is on`() {
        val task = sampleTask(retryOnRclone = true, maxRetries = 3)
        val decision = retryDecisionFor(
            VirgaError.Stall(message = "stalled"), attempt = 0, task = task,
        )
        assertThat(decision).isEqualTo(RetryOutcome.FAIL)
    }

    @Test
    fun `a network error still retries within the attempt budget`() {
        val task = sampleTask(retryOnRclone = false, maxRetries = 3)
        val decision = retryDecisionFor(
            VirgaError.Network("offline"), attempt = 0, task = task,
        )
        assertThat(decision).isEqualTo(RetryOutcome.RETRY)
    }
```

`sampleTask(...)` is a tiny builder you add to the test file producing a `SyncTask` with the named fields set and the rest defaulted — copy the field list from `core/common/.../model/SyncTask.kt`. (Do not reference production builders that need a DB.)

> **Refactor note for testability:** `retryDecision` returns a WorkManager `Result`, which isn't constructible in a plain JVM test. Extract the decision into a pure enum-returning helper and have `retryDecision` map it.

- [ ] **Step 6: Run it to confirm it fails to compile**

Run: `./gradlew :sync-worker:testDebugUnitTest --tests "app.lusk.virga.sync.SyncWorkerStallMessageTest" -q`
Expected: FAIL — `retryDecisionFor` / `RetryOutcome` unresolved.

- [ ] **Step 7: Extract the pure retry decision**

At file scope in `SyncWorker.kt`:

```kotlin
internal enum class RetryOutcome { RETRY, FAIL }

/** Pure retry policy. A [VirgaError.Stall] is never retried — re-running hammers the
 *  same unreadable region. Network errors (and rclone errors when the task opts in)
 *  retry within the attempt budget. Auth is handled by the caller before this. */
internal fun retryDecisionFor(failure: Throwable, attempt: Int, task: SyncTask): RetryOutcome {
    if (failure is VirgaError.Stall) return RetryOutcome.FAIL
    val isAuth = failure is VirgaError.Auth || isAuthError(failure.message ?: "")
    if (isAuth) return RetryOutcome.FAIL
    val retryable = failure is VirgaError.Network ||
        (task.retryOnRclone && failure is VirgaError.Rclone)
    return if (retryable && attempt < task.maxRetries - 1) RetryOutcome.RETRY else RetryOutcome.FAIL
}
```

Then rewrite the existing `private fun retryDecision(...)` to delegate:

```kotlin
    private fun retryDecision(failure: Throwable, attempt: Int, task: SyncTask): Result =
        when (retryDecisionFor(failure, attempt, task)) {
            RetryOutcome.RETRY -> Result.retry()
            RetryOutcome.FAIL -> Result.failure()
        }
```

- [ ] **Step 8: Wire the source-aware message into the failure branch**

In the `else` failure branch (~line 384), replace `val msg = failure.message ?: "Sync failed"` with a stall-aware version. `sourceIsLocal` is `!task.sourcePath.startsWith("content://")` OR `staged.isStaged` (a staged SAF upload is still reading local storage during staging, but at the rclone layer the source is the staged cache; for the stall message, treat the *original* task source — local path or SAF — as "local" since both mean on-device storage):

```kotlin
            val sourceIsLocal = !task.sourcePath.startsWith("content://") || staged.isStaged
            val msg = (failure as? VirgaError.Stall)
                ?.let { stallUserMessage(it, task.direction, sourceIsLocal) }
                ?: failure.message ?: "Sync failed"
```

- [ ] **Step 9: Run the full sync-worker suite to verify nothing regressed**

Run: `./gradlew :sync-worker:testDebugUnitTest -q`
Expected: PASS (all existing tests + the 4 new ones).

- [ ] **Step 10: Commit**

```bash
git add sync-worker/src/main/kotlin/app/lusk/virga/sync/SyncWorker.kt \
        sync-worker/src/test/kotlin/app/lusk/virga/sync/SyncWorkerStallMessageTest.kt
git commit -m "feat(sync): source-aware stall message; never retry a stall"
```

- [ ] **Step 11: Verify the Hilt graph still builds (no DI change, but a shared interface changed)**

Run: `./gradlew :app:hiltJavaCompileFossDebug :app:hiltJavaCompilePlayDebug -q`
Expected: BUILD SUCCESSFUL.

---

## Phase 2 — Preserve the good files + bound the SAF hang

### Task 2.1: Soft-stall → partial success for copy/backup (keep the files already transferred)

**Files:**
- Modify: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngineImpl.kt` (stall branch in `runJobWithProgress`)
- Test: `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/RcloneEngineImplTest.kt`

**Interfaces:**
- Consumes: `tolerateFileErrors` (already threaded into `runJobWithProgress`), `SyncProgress.stalledFile`/`errors`, `VirgaError.Stall`.
- Produces: when a stall fires AND `tolerateFileErrors == true` AND the job made progress at least once (`lastProgressAtMs != Long.MAX_VALUE`), the flow stops the job, fetches final stats, and **emits** a terminal `SyncProgress` with `errors = max(errors, 1)` and `stalledFile = <name>` instead of throwing. A mirror/move run (or a stall before any progress) still throws `VirgaError.Stall`.

- [ ] **Step 1: Write the failing test — copy/backup soft-stall emits partial success**

Add to `RcloneEngineImplTest.kt`:

```kotlin
    @Test fun `a soft stall on a copy emits partial success with the stalled file`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "sync/copy", any()) } returns
            buildJsonObject { put("jobid", 11) }
        coEvery { apiClient.call(any(), any(), any(), "job/status", any()) } returns
            buildJsonObject { put("finished", false) }
        // First tick: real progress (bytes move). Subsequent ticks: flat + a named file.
        var tick = 0
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } answers {
            tick++
            buildJsonObject {
                put("bytes", if (tick == 1) 100L else 100L) // moves on tick 1, then flat
                put("transfers", if (tick == 1) 1 else 1)
                put("checks", 0)
                put("transferring", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject { put("name", "DCIM/IMG_BAD.jpg") })
                })
            }
        }
        coEvery { apiClient.call(any(), any(), any(), "job/stop", any()) } returns buildJsonObject {}

        engine.startDaemon()

        // tolerateFileErrors is true for a copy (UPLOAD, no delete/move).
        engine.sync(
            "local:/x", "gdrive:x",
            SyncOptions(SyncDirection.UPLOAD), stallTimeoutMs = 0L,
        ).test {
            // tick 1 arms the clock with progress; tick 2 is flat → soft stall → terminal emit.
            val first = awaitItem()
            assertThat(first.transferredFiles).isEqualTo(1)
            val terminal = awaitItem()
            assertThat(terminal.errors).isAtLeast(1)
            assertThat(terminal.stalledFile).isEqualTo("DCIM/IMG_BAD.jpg")
            awaitComplete()
        }
    }

    @Test fun `a mirror stall still throws (no partial success)`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { apiClient.call(any(), any(), any(), "sync/sync", any()) } returns
            buildJsonObject { put("jobid", 12) }
        coEvery { apiClient.call(any(), any(), any(), "job/status", any()) } returns
            buildJsonObject { put("finished", false) }
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } returns buildJsonObject {
            put("bytes", 0L); put("checks", 0)
        }
        coEvery { apiClient.call(any(), any(), any(), "job/stop", any()) } returns buildJsonObject {}

        engine.startDaemon()

        engine.sync(
            "local:/x", "gdrive:x",
            SyncOptions(SyncDirection.UPLOAD, deleteExtraneous = true), stallTimeoutMs = 0L,
        ).test {
            awaitItem()
            assertThat(awaitError()).isInstanceOf(VirgaError.Stall::class.java)
        }
    }
```

- [ ] **Step 2: Run them to confirm the first fails (still throws today)**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*RcloneEngineImplTest" -q`
Expected: FAIL — `a soft stall on a copy…` errors instead of emitting; mirror test already passes.

- [ ] **Step 3: Implement the soft-stall branch**

Replace the stall `else if` branch (from Task 1.3) so it emits-and-returns on the tolerant path. The block becomes:

```kotlin
                } else if (System.currentTimeMillis() - lastProgressAtMs > stallTimeoutMs) {
                    val madeProgress = lastProgressAtMs != Long.MAX_VALUE
                    if (tolerateFileErrors && madeProgress) {
                        // Copy/backup: keep what already transferred. Stop the job, read
                        // final stats, and report a partial success naming the wedged file.
                        jobFinished = true
                        runCatching { rc(d, "job/stop", buildJsonObject { put("jobid", jobId) }) }
                        val finalStats = statsFor(d, group)
                        emit(
                            finalStats.copy(
                                errors = maxOf(finalStats.errors, 1),
                                stalledFile = lastTransferringName,
                                statsGroup = group,
                            ),
                        )
                        break
                    }
                    throw VirgaError.Stall(
                        file = lastTransferringName,
                        message = stallMessage(stallTimeoutMs, lastTransferringName),
                    )
                }
```

> Setting `jobFinished = true` before the `job/stop` here prevents the `finally` block from issuing a second `job/stop`. The explicit stop above is what halts the wedged job.

- [ ] **Step 4: Run the tests to verify both pass**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*RcloneEngineImplTest" -q`
Expected: PASS — soft-stall emits partial success; mirror stall still throws.

- [ ] **Step 5: Commit**

```bash
git add core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngineImpl.kt \
        core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/RcloneEngineImplTest.kt
git commit -m "feat(sync): soft-stall a copy into partial success, keeping transferred files"
```

---

### Task 2.2: Record the stalled file in the worker's partial-success failed-files list

**Files:**
- Modify: `sync-worker/src/main/kotlin/app/lusk/virga/sync/SyncWorker.kt` (the partial-success record path ~line 256-260 and ~314-319)
- Test: `sync-worker/src/test/kotlin/app/lusk/virga/sync/StalledFileRecordTest.kt` (create) — tests a pure merge helper.

**Interfaces:**
- Consumes: `SyncProgress.stalledFile` (Task 1.1/2.1), `captureFailedFiles` output (newline-joined `path\terror` rows).
- Produces: `fun mergeStalledFile(failedFiles: String, stalledFile: String?): String` — appends a `"<file>\tstalled: read timed out"` row when `stalledFile` is set and not already present.

> **Why:** a pure hang leaves rclone's `core/transferred` with `errors = 0` for that file (it never errored — it never returned), so `captureFailedFiles` won't list it. We add it explicitly from `SyncProgress.stalledFile`.

- [ ] **Step 1: Write the failing test**

Create `sync-worker/src/test/kotlin/app/lusk/virga/sync/StalledFileRecordTest.kt`:

```kotlin
package app.lusk.virga.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StalledFileRecordTest {
    @Test
    fun `appends the stalled file as a failed-files row`() {
        val merged = mergeStalledFile("a.txt\terror x", "DCIM/IMG_BAD.jpg")
        assertThat(merged).contains("a.txt\terror x")
        assertThat(merged).contains("DCIM/IMG_BAD.jpg\t")
        assertThat(merged.lines()).hasSize(2)
    }

    @Test
    fun `no-op when stalledFile is null`() {
        assertThat(mergeStalledFile("a.txt\terror x", null)).isEqualTo("a.txt\terror x")
    }

    @Test
    fun `does not duplicate an already-listed file`() {
        val merged = mergeStalledFile("DCIM/IMG_BAD.jpg\tboom", "DCIM/IMG_BAD.jpg")
        assertThat(merged.lines()).hasSize(1)
    }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `./gradlew :sync-worker:testDebugUnitTest --tests "app.lusk.virga.sync.StalledFileRecordTest" -q`
Expected: FAIL — `mergeStalledFile` unresolved.

- [ ] **Step 3: Add the pure merge helper**

At file scope in `SyncWorker.kt`:

```kotlin
/** Appends [stalledFile] to the newline-joined `path\terror` [failedFiles] record, unless
 *  null or already listed. Used so a soft-stalled file (which rclone never reports as an
 *  error — the read never returned) still shows up in the run's failed-files list. */
internal fun mergeStalledFile(failedFiles: String, stalledFile: String?): String {
    if (stalledFile.isNullOrBlank()) return failedFiles
    val alreadyListed = failedFiles.lineSequence().any { it.substringBefore('\t') == stalledFile }
    if (alreadyListed) return failedFiles
    val row = "$stalledFile\tstalled: read timed out"
    return if (failedFiles.isBlank()) row else "$failedFiles\n$row"
}
```

- [ ] **Step 4: Wire it into the worker's failed-files capture**

In `SyncWorker.kt`, after the existing `failedFiles = captureFailedFiles(...)` assignment (~line 258-260), fold in the stalled file from the terminal emission:

```kotlin
            val errorCount = last?.errors ?: 0
            val statsGroup = last?.statsGroup
            if (failure == null && errorCount > 0 && statsGroup != null) {
                failedFiles = captureFailedFiles(statsGroup, log)
            }
            failedFiles = mergeStalledFile(failedFiles, last?.stalledFile)
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :sync-worker:testDebugUnitTest --tests "app.lusk.virga.sync.StalledFileRecordTest" -q`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add sync-worker/src/main/kotlin/app/lusk/virga/sync/SyncWorker.kt \
        sync-worker/src/test/kotlin/app/lusk/virga/sync/StalledFileRecordTest.kt
git commit -m "feat(sync): record the soft-stalled file in the partial-success failed list"
```

---

### Task 2.3: Per-file read timeout (close-on-timeout) in `LocalStaging`

**Files:**
- Modify: `sync-worker/src/main/kotlin/app/lusk/virga/sync/LocalStaging.kt` (inject `DispatcherProvider`; `copyDocumentToFile`; `CopyTally`; `StagedSource`)
- Modify: `sync-worker/src/test/kotlin/app/lusk/virga/sync/LocalStagingPrepareTest.kt` (constructor now takes a `DispatcherProvider`)
- Test: `sync-worker/src/test/kotlin/app/lusk/virga/sync/LocalStagingTimeoutTest.kt` (create)

**Interfaces:**
- Consumes: `DispatcherProvider` (`core/common/.../dispatchers/DispatcherProvider.kt` — `main`/`default`/`io`).
- Produces: `LocalStaging` ctor gains `dispatchers: DispatcherProvider`. `CopyTally` gains `var timeouts: Int = 0`. `StagedSource` gains `val readTimeouts: Int = 0`. New private `suspend fun copyDocumentToFileTimed(uri: Uri, dest: File, timeoutMs: Long): CopyOutcome` where `enum class CopyOutcome { COPIED, ERROR, TIMEOUT }`. `prepare()` stays `suspend` with the same public signature.

> **HONEST feasibility note (put this verbatim in the eventual PR):** `withTimeoutOrNull` cancellation is cooperative. An `InputStream.read()` blocked in a `content://` Binder/JNI call will NOT unblock on cancellation alone — the unblock comes from calling `stream.close()` from the *outer* coroutine (a different thread), which most `FileInputStream`-backed providers honor. A truly wedged kernel I/O on a dead card may still not return promptly. So this **bounds** the staging hang and unblocks the common case; it does not *guarantee* an unblock. Do not oversell it.

- [ ] **Step 1: Write the failing test (a slow read is bounded and counted as a timeout)**

Create `sync-worker/src/test/kotlin/app/lusk/virga/sync/LocalStagingTimeoutTest.kt`. This tests the timed copy against an `InputStream` that blocks, using a real dispatcher provider so `withTimeoutOrNull` runs the read on a separate thread that the outer coroutine can `close()`:

```kotlin
package app.lusk.virga.sync

import android.content.Context
import android.net.Uri
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalStagingTimeoutTest {

    private lateinit var context: Context
    private lateinit var staging: LocalStaging

    private val realDispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.IO
        override val io = Dispatchers.IO
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        staging = LocalStaging(context, realDispatchers)
    }

    /** An InputStream whose read() blocks until close() is called from another thread. */
    private class BlockingStream(val closed: AtomicBoolean) : InputStream() {
        override fun read(): Int {
            while (!closed.get()) Thread.sleep(10)
            return -1
        }
        override fun close() { closed.set(true) }
    }

    @Test
    fun `a read that exceeds the timeout is closed and counted as a timeout`() = runBlocking {
        val closed = AtomicBoolean(false)
        val resolver = context.contentResolver
        val spyContext = mockk<Context>(relaxed = true)
        every { spyContext.contentResolver } returns resolver
        // Drive the helper directly via the test-only entry point.
        val dest = File(context.cacheDir, "out.bin")
        val outcome = staging.copyDocumentToFileTimedForTest(
            stream = BlockingStream(closed),
            dest = dest,
            timeoutMs = 50L,
        )
        assertThat(outcome).isEqualTo(LocalStaging.CopyOutcome.TIMEOUT)
        assertThat(closed.get()).isTrue() // outer coroutine closed the wedged stream
    }
}
```

> Because mocking a real `content://` provider under Robolectric is heavy, we expose a thin `internal` test entry point `copyDocumentToFileTimedForTest(stream, dest, timeoutMs)` that runs the same timeout+close logic against a supplied stream. The production `copyDocumentToFile` opens the stream from the resolver and delegates to the shared logic.

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `./gradlew :sync-worker:testDebugUnitTest --tests "app.lusk.virga.sync.LocalStagingTimeoutTest" -q`
Expected: FAIL — ctor arity / `copyDocumentToFileTimedForTest` / `CopyOutcome` unresolved.

- [ ] **Step 3: Inject `DispatcherProvider` and add the timeout logic**

In `LocalStaging.kt`:

Change the constructor:

```kotlin
@Singleton
class LocalStaging @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {
```

Add the import: `import app.lusk.virga.core.common.dispatchers.DispatcherProvider` and `import kotlinx.coroutines.withTimeoutOrNull` and `import kotlinx.coroutines.launch` and `import kotlinx.coroutines.coroutineScope` and `import java.io.InputStream`.

Add the outcome enum and a per-file timeout constant near the top of the class body:

```kotlin
    enum class CopyOutcome { COPIED, ERROR, TIMEOUT }

    private companion object {
        /** Max wall-clock a single staged file read may take before it's abandoned.
         *  Bounds a wedged read on a failing card so staging can't hang forever. */
        const val PER_FILE_READ_TIMEOUT_MS = 30_000L
    }
```

Replace `copyDocumentToFile` (currently the `runCatching` version at line 185) with a suspend, timeout-aware version plus the shared logic and the test entry point:

```kotlin
    /** Copies one SAF document to [dest] with a per-file read timeout. On timeout the
     *  stream is closed from this (outer) coroutine to unblock a wedged read where the
     *  provider honors close(); returns [CopyOutcome.TIMEOUT]. Open/IO failures →
     *  [CopyOutcome.ERROR]; success → [CopyOutcome.COPIED]. */
    private suspend fun copyDocumentToFileTimed(uri: Uri, dest: File): CopyOutcome {
        val stream = try {
            context.contentResolver.openInputStream(uri) ?: return CopyOutcome.ERROR
        } catch (e: Exception) {
            return CopyOutcome.ERROR
        }
        return copyStreamTimed(stream, dest, PER_FILE_READ_TIMEOUT_MS)
    }

    /** Shared copy-with-deadline logic. Runs the blocking copy on [dispatchers.io]; on
     *  timeout, closes the stream (from this coroutine, a different thread) to break a
     *  wedged read, then counts a TIMEOUT. */
    private suspend fun copyStreamTimed(stream: InputStream, dest: File, timeoutMs: Long): CopyOutcome =
        coroutineScope {
            val copy = launch(dispatchers.io) {
                stream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            }
            val finished = withTimeoutOrNull(timeoutMs) { copy.join() }
            if (finished == null) {
                runCatching { stream.close() } // unblock the wedged read where honored
                copy.cancel()
                CopyOutcome.TIMEOUT
            } else if (copy.isCancelled) {
                CopyOutcome.ERROR
            } else {
                CopyOutcome.COPIED
            }
        }

    /** Test-only entry point: run the timeout/close logic against a supplied stream. */
    internal suspend fun copyDocumentToFileTimedForTest(
        stream: InputStream,
        dest: File,
        timeoutMs: Long,
    ): CopyOutcome = copyStreamTimed(stream, dest, timeoutMs)
```

> The previous `copyDocumentToFile` returned `Boolean`; we now return `CopyOutcome`. Update the call site in the next step.

- [ ] **Step 4: Add `timeouts` to `CopyTally`, `readTimeouts` to `StagedSource`, and update `copyTreeToLocal`**

Change `CopyTally`:

```kotlin
    private class CopyTally(var copied: Int = 0, var errors: Int = 0, var timeouts: Int = 0)
```

Add to `StagedSource` (keep last so the data-class ctor stays exempt):

```kotlin
        /** Number of source files abandoned because a single read exceeded the per-file
         *  timeout (a strong "the card is failing" signal). Counted within [errors]-style
         *  accounting too: a timed-out file is NOT in the staged copy. */
        val readTimeouts: Int = 0,
```

`copyTreeToLocal` is currently synchronous and calls `copyDocumentToFile` (Boolean). Make it `suspend` and branch on `CopyOutcome`:

```kotlin
    private suspend fun copyTreeToLocal(dir: DocumentFile, dest: File, tally: CopyTally) {
        for (child in dir.listFiles()) {
            val target = safeChild(dest, child.name)
            if (target == null) {
                tally.errors++
                continue
            }
            if (child.isDirectory) {
                target.mkdirs()
                copyTreeToLocal(child, target, tally)
            } else {
                when (copyDocumentToFileTimed(child.uri, target)) {
                    CopyOutcome.COPIED -> tally.copied++
                    CopyOutcome.TIMEOUT -> { tally.timeouts++; tally.errors++ }
                    CopyOutcome.ERROR -> tally.errors++
                }
            }
        }
    }
```

In `prepare()`, thread `readTimeouts` into the returned `StagedSource` (the UPLOAD/BISYNC success branch, ~line 92):

```kotlin
                return@withContext StagedSource(
                    localPath = stageDir.absolutePath,
                    isStaged = true,
                    treeUriString = sourcePath,
                    cacheDir = stageDir,
                    sourceReadable = true,
                    stagedFileCount = tally.copied,
                    fullyStaged = tally.errors == 0,
                    readTimeouts = tally.timeouts,
                )
```

> `prepare()` already runs inside `withContext(Dispatchers.IO)`; making `copyTreeToLocal` `suspend` is fine within it. Leave that outer `withContext` as-is.

- [ ] **Step 5: Fix the existing staging test constructor**

In `LocalStagingPrepareTest.kt` `setUp()`, the `LocalStaging(context)` call now needs a dispatcher provider. Update it:

```kotlin
    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        staging = LocalStaging(
            context,
            object : app.lusk.virga.core.common.dispatchers.DispatcherProvider {
                override val main = kotlinx.coroutines.Dispatchers.Unconfined
                override val default = kotlinx.coroutines.Dispatchers.IO
                override val io = kotlinx.coroutines.Dispatchers.IO
            },
        )
    }
```

Do the same anywhere else `LocalStaging(` is constructed in tests — find them:

Run: `grep -rn "LocalStaging(" sync-worker/src/test`

- [ ] **Step 6: Verify the Hilt binding for `DispatcherProvider` exists (it's already injected by RcloneEngineImpl)**

Run: `grep -rn "DispatcherProvider" --include=*.kt | grep -iE "provides|binds|@Module" | grep -v /build/`
Expected: a `@Provides`/`@Binds` for `DispatcherProvider` (used by `core:rclone`). If `sync-worker` can't see it, add a `@Provides fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()` to a `sync-worker` Hilt module (e.g. the module that already provides `LocalStaging`'s peers). Confirm via the graph build in Step 8.

- [ ] **Step 7: Run the timeout test + the existing staging tests**

Run: `./gradlew :sync-worker:testDebugUnitTest --tests "app.lusk.virga.sync.LocalStaging*" -q`
Expected: PASS — the new timeout test plus the existing `LocalStagingPrepareTest` / `LocalStagingSafeChildTest`.

- [ ] **Step 8: Verify the Hilt graph builds (DI change: LocalStaging gained a ctor param)**

Run: `./gradlew :app:hiltJavaCompileFossDebug :app:hiltJavaCompilePlayDebug -q`
Expected: BUILD SUCCESSFUL. A `MissingBinding` here means Step 6's provider is needed.

- [ ] **Step 9: Commit**

```bash
git add sync-worker/src/main/kotlin/app/lusk/virga/sync/LocalStaging.kt \
        sync-worker/src/test/kotlin/app/lusk/virga/sync/LocalStagingPrepareTest.kt \
        sync-worker/src/test/kotlin/app/lusk/virga/sync/LocalStagingTimeoutTest.kt
git commit -m "feat(sync): per-file read timeout in SAF staging to bound a wedged card read"
```

---

### Task 2.4: Surface a "card may be failing" message when staging timed out

**Files:**
- Modify: `sync-worker/src/main/kotlin/app/lusk/virga/sync/SyncWorker.kt` (after `prepare()`, ~line 150-160)
- Modify: `sync-worker/src/main/res/values/strings.xml` (one new string, to dodge StringLiteralDuplication)
- Test: `sync-worker/src/test/kotlin/app/lusk/virga/sync/StagingTimeoutMessageTest.kt` (create)

**Interfaces:**
- Consumes: `StagedSource.readTimeouts` (Task 2.3).
- Produces: `fun stagingTimeoutWarning(readTimeouts: Int): String?` — returns a card-failure warning when `readTimeouts > 0`, else null. Logged to the run log; does not by itself fail a COPY (the partial-stage handling already gates mirror/bisync via `fullyStaged`).

- [ ] **Step 1: Write the failing test**

Create `sync-worker/src/test/kotlin/app/lusk/virga/sync/StagingTimeoutMessageTest.kt`:

```kotlin
package app.lusk.virga.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class StagingTimeoutMessageTest {
    @Test
    fun `warns when files timed out during staging`() {
        val msg = stagingTimeoutWarning(2)
        assertThat(msg).isNotNull()
        assertThat(msg!!).contains("2")
        assertThat(msg).contains("card")
    }

    @Test
    fun `no warning when nothing timed out`() {
        assertThat(stagingTimeoutWarning(0)).isNull()
    }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `./gradlew :sync-worker:testDebugUnitTest --tests "app.lusk.virga.sync.StagingTimeoutMessageTest" -q`
Expected: FAIL — `stagingTimeoutWarning` unresolved.

- [ ] **Step 3: Add the helper**

At file scope in `SyncWorker.kt`:

```kotlin
/** A run-log warning when [readTimeouts] source files were abandoned because their read
 *  exceeded the per-file timeout — a strong signal the source storage is failing. Null
 *  when nothing timed out. */
internal fun stagingTimeoutWarning(readTimeouts: Int): String? =
    if (readTimeouts > 0) {
        "$readTimeouts file(s) timed out while reading the source — the card or drive may " +
            "be failing. Copy your files off and replace it."
    } else {
        null
    }
```

- [ ] **Step 4: Log it after staging**

In `doWork`, right after `val staged = staging.prepare(...)` and the `effectiveTask` line (~line 150-151), add:

```kotlin
        stagingTimeoutWarning(staged.readTimeouts)?.let { log.line(it) }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :sync-worker:testDebugUnitTest --tests "app.lusk.virga.sync.StagingTimeoutMessageTest" -q`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add sync-worker/src/main/kotlin/app/lusk/virga/sync/SyncWorker.kt \
        sync-worker/src/test/kotlin/app/lusk/virga/sync/StagingTimeoutMessageTest.kt
git commit -m "feat(sync): log a failing-card warning when staging reads time out"
```

---

## Phase 3 — Backstops & opt-ins

### Task 3.1: Process-kill backstop for a job that won't stop (last-lease only)

**Files:**
- Modify: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngineImpl.kt` (the `finally` job-stop block ~line 756-764)
- Test: `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/RcloneEngineImplTest.kt`

**Interfaces:**
- Consumes: `RcloneDaemonManager.stop(daemon)` (already calls `process.destroy()` then `destroyForcibly()`), the engine's `leases` refcount.
- Produces: when a non-finished job is stopped in the `finally` but the daemon is held by no other lease (`leases <= 1`), and `job/stop` doesn't confirm within a short grace poll, the engine tears the daemon down via `daemonManager.stop(d)` (which force-kills the process) instead of leaving a wedged transfer thread alive.

> **Risk gate (HARD):** force-killing the daemon process kills ALL in-flight jobs sharing it. Only do this when no OTHER lease is active (a concurrent "sync all" would otherwise lose its run). Guard on the lease count.

- [ ] **Step 1: Write the failing test — last-lease wedged job triggers daemon stop**

Add to `RcloneEngineImplTest.kt`:

```kotlin
    @Test fun `a wedged job with no other lease force-stops the daemon`() = runTest(testDispatcher) {
        coEvery { configManager.decryptForDaemon() } returns File("/tmp/rclone.conf")
        coEvery { daemonManager.start(any()) } returns fakeDaemon
        every { daemonManager.isAlive(fakeDaemon) } returns true
        coEvery { daemonManager.stop(any()) } returns Unit
        coEvery { apiClient.call(any(), any(), any(), "sync/sync", any()) } returns
            buildJsonObject { put("jobid", 21) }
        coEvery { apiClient.call(any(), any(), any(), "job/status", any()) } returns
            buildJsonObject { put("finished", false) }
        coEvery { apiClient.call(any(), any(), any(), "core/stats", any()) } returns
            buildJsonObject { put("bytes", 0L); put("checks", 0) }
        // job/stop "succeeds" but the job never confirms finished (wedged thread).
        coEvery { apiClient.call(any(), any(), any(), "job/stop", any()) } returns buildJsonObject {}

        engine.startDaemon()

        engine.sync(
            "local:/x", "gdrive:x",
            SyncOptions(SyncDirection.UPLOAD, deleteExtraneous = true), stallTimeoutMs = 0L,
        ).test {
            awaitItem()
            awaitError() // VirgaError.Stall (mirror path)
        }
        // Backstop fired: the daemon was force-stopped.
        coVerify { daemonManager.stop(fakeDaemon) }
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*RcloneEngineImplTest" -q`
Expected: FAIL — `daemonManager.stop` is never invoked from the stall path today.

- [ ] **Step 3: Add a confirm-or-kill backstop in the `finally`**

Replace the `finally` block (~line 756-764) with a version that, when this is the last lease, confirms the stop and force-kills otherwise:

```kotlin
        } finally {
            if (!jobFinished) {
                withContext(NonCancellable) {
                    runCatching { rc(d, "job/stop", buildJsonObject { put("jobid", jobId) }) }
                    // If no other consumer holds the daemon, a job that won't confirm
                    // stopped is a wedged (kernel-blocked) transfer thread that job/stop
                    // can't reach. Force-kill the daemon process so we don't leak it.
                    if (leaseCount() <= 1 && !jobStopped(d, jobId)) {
                        runCatching { daemonManager.stop(d) }
                    }
                }
            }
        }
```

Add two small private helpers near `rc(...)`:

```kotlin
    /** Current daemon lease count, read under the lock. */
    private suspend fun leaseCount(): Int = lock.withLock { leases }

    /** Polls job/status briefly; true once the job reports finished, false if it stays
     *  unfinished through the grace window (a wedged thread job/stop can't reach). */
    private suspend fun jobStopped(d: RcloneDaemon, jobId: Int): Boolean {
        repeat(JOB_STOP_CONFIRM_POLLS) {
            val finished = runCatching {
                rc(d, "job/status", buildJsonObject { put("jobid", jobId) })
                    ["finished"]?.jsonPrimitive?.booleanOrNull
            }.getOrNull() ?: false
            if (finished) return true
            delay(JOB_STOP_CONFIRM_INTERVAL_MS)
        }
        return false
    }
```

Add the constants to the companion:

```kotlin
        const val JOB_STOP_CONFIRM_POLLS = 4
        const val JOB_STOP_CONFIRM_INTERVAL_MS = 250L
```

> Verify the field is named `leases` and the lock is `lock` (both used elsewhere in this file — `withExclusiveDaemon` reads `leases`). If `leases` isn't directly readable, reuse the existing accessor pattern those functions use.

- [ ] **Step 4: Run the test to verify it passes (and existing daemon-stop tests still pass)**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*RcloneEngineImplTest" -q`
Expected: PASS — backstop fires on the last-lease wedged path; the existing "does not tear down daemon while leased" cases stay green.

- [ ] **Step 5: Commit**

```bash
git add core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngineImpl.kt \
        core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/RcloneEngineImplTest.kt
git commit -m "feat(sync): force-kill the rclone daemon when a wedged last-lease job won't stop"
```

---

### Task 3.2: Allowlist `MaxDuration` + `CutoffMode` for power users / flaky sources

**Files:**
- Modify: `sync-worker/src/main/kotlin/app/lusk/virga/sync/ExtraConfigParser.kt:32` (the `ALLOWLIST` set + KDoc)
- Test: `sync-worker/src/test/kotlin/app/lusk/virga/sync/ExtraConfigParserTest.kt`

**Interfaces:**
- Consumes: existing `ExtraConfigParser.validateLine` / `parseToMap` pipeline → rclone `_config` (`RcloneJson.putConfig` forwards every parsed entry verbatim).
- Produces: `MaxDuration` (e.g. `MaxDuration=10m`) and `CutoffMode` (e.g. `CutoffMode=HARD`) accepted as valid passthrough keys, so a user can wall-clock-cap a run on a flaky source without new UI.

- [ ] **Step 1: Write the failing test**

Add to `ExtraConfigParserTest.kt`:

```kotlin
    @Test
    fun `MaxDuration is an allowlisted passthrough key`() {
        val result = ExtraConfigParser.validateLine("MaxDuration=10m")
        assertThat(result).isInstanceOf(ExtraConfigParser.ParseResult.Ok::class.java)
        assertThat((result as ExtraConfigParser.ParseResult.Ok).key).isEqualTo("MaxDuration")
    }

    @Test
    fun `CutoffMode is an allowlisted passthrough key`() {
        val result = ExtraConfigParser.validateLine("CutoffMode=HARD")
        assertThat(result).isInstanceOf(ExtraConfigParser.ParseResult.Ok::class.java)
    }
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `./gradlew :sync-worker:testDebugUnitTest --tests "app.lusk.virga.sync.ExtraConfigParserTest" -q`
Expected: FAIL — `MaxDuration`/`CutoffMode` returns `UnknownKey`.

- [ ] **Step 3: Add the keys to the allowlist**

In `ExtraConfigParser.kt`, add the two keys to `ALLOWLIST`:

```kotlin
    val ALLOWLIST: Set<String> = setOf(
        "CheckSum",
        "SizeOnly",
        "TrackRenames",
        "BackupDir",
        "Suffix",
        "MaxDelete",
        "MaxTransfer",
        "OrderBy",
        "IgnoreExisting",
        "IgnoreSize",
        "NoTraverse",
        "MaxDuration",
        "CutoffMode",
    )
```

Update the KDoc list of deferred-typed-toggle keys to mention `MaxDuration`/`CutoffMode` for flaky sources.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :sync-worker:testDebugUnitTest --tests "app.lusk.virga.sync.ExtraConfigParserTest" -q`
Expected: PASS (existing + 2 new).

- [ ] **Step 5: Commit**

```bash
git add sync-worker/src/main/kotlin/app/lusk/virga/sync/ExtraConfigParser.kt \
        sync-worker/src/test/kotlin/app/lusk/virga/sync/ExtraConfigParserTest.kt
git commit -m "feat(sync): allowlist MaxDuration + CutoffMode for flaky-source wall-clock caps"
```

---

### Task 3.3: Sample-read preflight (`SourceHealthCheck`) for staged uploads

**Files:**
- Create: `sync-worker/src/main/kotlin/app/lusk/virga/sync/SourceHealthCheck.kt`
- Modify: `sync-worker/src/main/kotlin/app/lusk/virga/sync/SyncWorker.kt` (call before staging an upload)
- Test: `sync-worker/src/test/kotlin/app/lusk/virga/sync/SourceHealthCheckTest.kt` (create)

**Interfaces:**
- Consumes: `DispatcherProvider`, `LocalStaging.CopyOutcome` semantics (reuse the timeout/close pattern).
- Produces: `class SourceHealthCheck @Inject constructor(context, dispatchers)` with `suspend fun probe(treeUri: String, sampleSize: Int = 3, perReadTimeoutMs: Long = 5_000L): HealthResult` and `enum class HealthResult { OK, TIMED_OUT, UNREADABLE }`. Returns `TIMED_OUT` if any sampled read exceeds the timeout, `UNREADABLE` if a sample open returns null/throws, else `OK`. Pure decision helper `fun preflightFailureMessage(result: HealthResult): String?` returns an actionable message for `TIMED_OUT`/`UNREADABLE`, null for `OK`.

> **Scope:** advisory and generous (5s/read, 3 files) to avoid false positives on a merely-slow card. It does NOT replace Task 2.3's per-file timeout — it's an early "don't start a doomed run" check.

- [ ] **Step 1: Write the failing test for the pure message helper**

Create `sync-worker/src/test/kotlin/app/lusk/virga/sync/SourceHealthCheckTest.kt`:

```kotlin
package app.lusk.virga.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SourceHealthCheckTest {
    @Test
    fun `timed-out probe yields a replace-the-card message`() {
        val msg = preflightFailureMessage(SourceHealthCheck.HealthResult.TIMED_OUT)
        assertThat(msg).isNotNull()
        assertThat(msg!!).contains("card")
    }

    @Test
    fun `unreadable probe yields a re-select message`() {
        assertThat(preflightFailureMessage(SourceHealthCheck.HealthResult.UNREADABLE)).isNotNull()
    }

    @Test
    fun `healthy probe yields no message`() {
        assertThat(preflightFailureMessage(SourceHealthCheck.HealthResult.OK)).isNull()
    }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `./gradlew :sync-worker:testDebugUnitTest --tests "app.lusk.virga.sync.SourceHealthCheckTest" -q`
Expected: FAIL — `SourceHealthCheck` / `preflightFailureMessage` unresolved.

- [ ] **Step 3: Create `SourceHealthCheck.kt`**

```kotlin
package app.lusk.virga.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.lusk.virga.core.common.dispatchers.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Advisory pre-sync probe: sample-reads a few files from a SAF tree under a tight,
 * generous timeout. A timeout on a sample read is a strong "the card is failing" signal,
 * so the worker can fail fast with an actionable message instead of starting a doomed,
 * minutes-long run. Generous on purpose (few files, multi-second budget) to avoid false
 * positives on a merely-slow card.
 */
@Singleton
class SourceHealthCheck @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {
    enum class HealthResult { OK, TIMED_OUT, UNREADABLE }

    suspend fun probe(
        treeUri: String,
        sampleSize: Int = 3,
        perReadTimeoutMs: Long = 5_000L,
    ): HealthResult {
        if (!treeUri.startsWith("content://")) return HealthResult.OK
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: return HealthResult.UNREADABLE
        if (!tree.canRead()) return HealthResult.UNREADABLE
        val files = firstFiles(tree, sampleSize)
        for (f in files) {
            when (readProbe(f.uri, perReadTimeoutMs)) {
                HealthResult.TIMED_OUT -> return HealthResult.TIMED_OUT
                HealthResult.UNREADABLE -> return HealthResult.UNREADABLE
                HealthResult.OK -> Unit
            }
        }
        return HealthResult.OK
    }

    /** Depth-first first [limit] regular files in the tree. */
    private fun firstFiles(dir: DocumentFile, limit: Int): List<DocumentFile> {
        val out = mutableListOf<DocumentFile>()
        fun walk(d: DocumentFile) {
            for (child in d.listFiles()) {
                if (out.size >= limit) return
                if (child.isDirectory) walk(child) else out.add(child)
            }
        }
        walk(dir)
        return out
    }

    /** Reads a small head of one file under [timeoutMs]; closes the stream on timeout. */
    private suspend fun readProbe(uri: Uri, timeoutMs: Long): HealthResult = coroutineScope {
        val stream: InputStream = try {
            context.contentResolver.openInputStream(uri) ?: return@coroutineScope HealthResult.UNREADABLE
        } catch (e: Exception) {
            return@coroutineScope HealthResult.UNREADABLE
        }
        val job = launch(dispatchers.io) {
            stream.use { it.read(ByteArray(64 * 1024)) }
        }
        val done = withTimeoutOrNull(timeoutMs) { job.join() }
        if (done == null) {
            runCatching { stream.close() }
            job.cancel()
            HealthResult.TIMED_OUT
        } else if (job.isCancelled) {
            HealthResult.UNREADABLE
        } else {
            HealthResult.OK
        }
    }
}

/** Actionable message for a failed preflight, or null when healthy. */
internal fun preflightFailureMessage(result: SourceHealthCheck.HealthResult): String? =
    when (result) {
        SourceHealthCheck.HealthResult.TIMED_OUT ->
            "Your source card or drive didn't respond — it may be failing. Copy your files " +
                "off and replace it."
        SourceHealthCheck.HealthResult.UNREADABLE ->
            "Can't read the selected folder — re-select it for this task."
        SourceHealthCheck.HealthResult.OK -> null
    }
```

- [ ] **Step 4: Run the message tests to verify they pass**

Run: `./gradlew :sync-worker:testDebugUnitTest --tests "app.lusk.virga.sync.SourceHealthCheckTest" -q`
Expected: PASS (3 tests).

- [ ] **Step 5: Inject + call the preflight in `SyncWorker` (upload/bisync, content:// only)**

Add `private val sourceHealthCheck: SourceHealthCheck` to the `SyncWorker` constructor (after `checkUseCase`). Then, before `staging.prepare(...)` (~line 150), for a SAF upload/bisync run a probe and fail fast:

```kotlin
        if (task.sourcePath.startsWith("content://") &&
            (task.direction == SyncDirection.UPLOAD || task.direction == SyncDirection.BISYNC)
        ) {
            val health = sourceHealthCheck.probe(task.sourcePath)
            preflightFailureMessage(health)?.let { warning ->
                log.line(warning)
                // A TIMED_OUT/UNREADABLE preflight aborts before a doomed run; an
                // UNREADABLE on bisync/mirror is already fatal downstream, so failing
                // here is strictly safer.
                if (health != SourceHealthCheck.HealthResult.OK) {
                    failure = VirgaError.Storage(warning)
                }
            }
        }
```

> Placement: this must set `failure` and then fall through to the existing `if (failure == null) { … } else { record failure }` epilogue. If the worker's structure runs staging unconditionally, guard `staging.prepare(...)` with `if (failure == null)`; otherwise put the probe inside the existing `try` before `acquireDaemon()` and let the `else` branch record it. Verify the control flow compiles and the failure is recorded (not swallowed).

- [ ] **Step 6: Verify the Hilt graph builds (DI change: new ctor param)**

Run: `./gradlew :app:hiltJavaCompileFossDebug :app:hiltJavaCompilePlayDebug -q`
Expected: BUILD SUCCESSFUL (`SourceHealthCheck` is `@Inject @Singleton`, deps already bound).

- [ ] **Step 7: Run the full sync-worker suite**

Run: `./gradlew :sync-worker:testDebugUnitTest -q`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add sync-worker/src/main/kotlin/app/lusk/virga/sync/SourceHealthCheck.kt \
        sync-worker/src/main/kotlin/app/lusk/virga/sync/SyncWorker.kt \
        sync-worker/src/test/kotlin/app/lusk/virga/sync/SourceHealthCheckTest.kt
git commit -m "feat(sync): sample-read preflight that fails fast on an unresponsive source"
```

---

## Final verification (run after all tasks)

- [ ] **Full module test pass**

Run: `./gradlew :core:common:testDebugUnitTest :core:rclone:testDebugUnitTest :sync-worker:testDebugUnitTest -q`
Expected: all green.

- [ ] **Both flavor Hilt graphs build**

Run: `./gradlew :app:hiltJavaCompileFossDebug :app:hiltJavaCompilePlayDebug -q`
Expected: BUILD SUCCESSFUL.

- [ ] **App assembles on both flavor families**

Run: `./gradlew :app:assembleGithubDebug :app:assemblePlayDebug -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Squash before PR** (secret-scan/GitGuardian scan the whole PR range): keep the branch history clean; squash if any intermediate commit tripped a scanner.

---

## Self-Review

**1. Spec coverage (the brainstorm's recommended bundle):**
- Phase 1 #1 "name the stuck file" → Tasks 1.1 + 1.3. ✓
- Phase 1 #2 "actionable + local/network message; non-retryable" → Task 1.4. ✓
- Phase 2 #3 "soft-stall → partial success" → Tasks 2.1 + 2.2. ✓
- Phase 2 #4 "SAF per-file read timeout" → Task 2.3 (+ 2.4 message). ✓
- Phase 3 #5 "process-kill backstop" → Task 3.1. ✓
- Phase 3 #6 "flaky-source preset / MaxDuration" → Task 3.2 (`Transfers`/`Checkers` are existing typed fields; the codeable gap was the `MaxDuration`/`CutoffMode` allowlist). ✓
- Phase 3 #7 "sample-read preflight" → Task 3.3. ✓
- Explicitly NOT shipped (per brainstorm): `IgnoreErrors` on mirror/move, `--timeout`/`--check-first` as fixes, raising `LowLevelRetries`, skip-and-restart-with-filter, `StorageManager` detection, staging resume. Not in any task. ✓

**2. Placeholder scan:** every code step shows complete code; every run step shows the command + expected outcome. No "TBD"/"add error handling"/"similar to Task N".

**3. Type consistency:** `SyncProgress.transferringNames`/`stalledFile` (1.1) consumed in 1.3/2.1/2.2; `VirgaError.Stall(file, message)` (1.2) thrown in 1.3/2.1 and matched in 1.4/2.1 tests; `stallTimeoutMs` default added to the `RcloneEngine` interface (1.3) and used in 2.1/3.1 tests; `CopyOutcome`/`CopyTally.timeouts`/`StagedSource.readTimeouts` (2.3) consumed in 2.4; `SourceHealthCheck.HealthResult` + `preflightFailureMessage` (3.3) consistent across helper and call site. `DispatcherProvider` ctor seam added to `LocalStaging` (2.3) and `SourceHealthCheck` (3.3), with the existing `LocalStaging(context)` test updated.

**Open verification points for the implementer (flagged inline, not placeholders):** confirm the engine's lease field is named `leases` and the mutex `lock` (Task 3.1 Step 3); confirm a `DispatcherProvider` Hilt binding is visible to `:sync-worker` (Task 2.3 Step 6); confirm `SyncWorker`'s control flow records a preflight `failure` rather than swallowing it (Task 3.3 Step 5). Each has a concrete grep/build check in its step.
