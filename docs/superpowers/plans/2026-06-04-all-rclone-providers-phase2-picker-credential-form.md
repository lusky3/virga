# All rclone providers — Phase 2: Picker + schema-driven credential form

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hardcoded `RcloneBackendTypes` dropdown with a searchable provider picker backed by `ProviderCatalog.pickerEntries()`, grouped by `SetupKind`. Wire the picker into the add-remote flow so selecting a provider routes to the correct creation path. Derive `sensitiveKeys` from the schema's `isPassword` options and pass them through `addRemote`. Add a connectivity test (`operations/about`) after successful remote creation.

**Architecture:** The picker Composable consumes `ProviderCatalog` entries, grouped into sections (OAuth pinned → Credential → Wrappers). Selecting a Credential/OAuth provider transitions to the existing credential form or OAuth flow. Selecting a Wrapper shows a placeholder (Phase 3). The `addRemote` VM function gains automatic `sensitiveKeys` derivation from the schema. After every successful `config/create`, a bounded `operations/about` (with `listDir` fallback) validates connectivity before confirming the save.

**Tech stack:** Kotlin, JUnit 5, MockK, Truth, Turbine, kotlinx.serialization JSON, Hilt, Jetpack Compose, Gradle (AGP 9.2.1, JDK 21).

**Scope note — this is plan 2 of 4.** See Phase 1 (`docs/superpowers/plans/2026-06-04-all-rclone-providers-phase1-foundation-box.md`) for context. Phases 3 (wrapper sub-flow) and 4 (BYOK rclone-delegated OAuth) follow.

**Conventions used below**
- Per-module unit tests: `./gradlew :<module>:testDebugUnitTest`. The whole suite (release gate): `./gradlew test`.
- Test stack matches the existing files: JUnit 5 (`org.junit.jupiter`), MockK, Truth (`assertThat`), Turbine, `MockWebServer`.
- Commit after every green task. Commit messages here omit any `Co-Authored-By` trailer (project rule).

---

## File structure for Phase 2

**Create:**
- `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/ProviderPicker.kt` — the searchable picker Composable.
- `feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/SensitiveKeysTest.kt` — unit test for `sensitiveKeys` derivation logic.

**Modify:**
- `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt` — expose `ProviderCatalog` state; `sensitiveKeys` derivation in `addRemote`; connectivity test after creation.
- `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/AddRemoteDialog.kt` — replace the `RcloneBackendTypes` dropdown with the `ProviderPicker`; route selection by `SetupKind`.
- `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/AddRemoteScreen.kt` — pass new callbacks.
- `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesScreen.kt` — pass new callbacks.
- `core/data/src/main/kotlin/app/lusk/virga/core/data/RemoteRepository.kt` — add `testConnectivity(remoteName)`.
- `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngine.kt` — add `testConnectivity` to interface.
- `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngineImpl.kt` — implement `testConnectivity` (`about` with `listDir` fallback).

**Touched tests:**
- `feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt`
- `core/data/src/test/kotlin/app/lusk/virga/core/data/RemoteRepositoryTest.kt`
- `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/RcloneEngineImplTest.kt`

---

## Task 1: `testConnectivity` in RcloneEngine — `about` with `listDir` fallback

**Why:** The design requires a connectivity test after `config/create`. `operations/about` is the lightest probe, but some backends (e.g. SFTP, local) don't support it. Falling back to a bounded root `listDir` gives universal coverage. This goes in the engine/repository layers first so the VM can consume it.

**Files:**
- Modify: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngine.kt`
- Modify: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngineImpl.kt`
- Modify: `core/data/src/main/kotlin/app/lusk/virga/core/data/RemoteRepository.kt`
- Test: `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/RcloneEngineImplTest.kt`
- Test: `core/data/src/test/kotlin/app/lusk/virga/core/data/RemoteRepositoryTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `RcloneEngineImplTest`:

```kotlin
@Test
fun testConnectivity_succeeds_when_about_works() = runTest {
    server.enqueue(MockResponse().setBody("""{"total":1000,"used":500}"""))

    val result = engine.testConnectivity("myremote")

    assertThat(result.isSuccess).isTrue()
    val req = server.takeRequest()
    assertThat(req.path).contains("operations/about")
}

@Test
fun testConnectivity_falls_back_to_listDir_when_about_fails() = runTest {
    // about fails with a 500
    server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"not supported"}"""))
    // listDir succeeds
    server.enqueue(MockResponse().setBody("""{"list":[]}"""))

    val result = engine.testConnectivity("myremote")

    assertThat(result.isSuccess).isTrue()
    assertThat(server.requestCount).isEqualTo(2)
}

@Test
fun testConnectivity_fails_when_both_fail() = runTest {
    server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"nope"}"""))
    server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"also nope"}"""))

    val result = engine.testConnectivity("myremote")

    assertThat(result.isFailure).isTrue()
}
```

Add to `RemoteRepositoryTest`:

```kotlin
@Test
fun testConnectivity_delegates_to_engine() = runTest {
    coEvery { engine.testConnectivity("r") } returns Result.success(Unit)

    val result = repository.testConnectivity("r")

    assertThat(result.isSuccess).isTrue()
    coVerify { engine.testConnectivity("r") }
}
```

- [ ] **Step 2: Run, verify compile failure**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*RcloneEngineImplTest*testConnectivity*"`
Expected: compile error — `testConnectivity` does not exist.

- [ ] **Step 3: Add `testConnectivity` to the interface**

In `RcloneEngine.kt`, after `about`:

```kotlin
    /**
     * Lightweight connectivity probe for [remoteName]. Attempts `operations/about`
     * first; if that fails (backend doesn't support it), falls back to a root
     * `operations/list` limited to 1 item. Returns [Result.success] if either
     * succeeds; [Result.failure] with the underlying error otherwise.
     */
    suspend fun testConnectivity(remoteName: String): Result<Unit>
```

- [ ] **Step 4: Implement in `RcloneEngineImpl`**

```kotlin
    override suspend fun testConnectivity(remoteName: String): Result<Unit> = runCatching {
        val d = ensureDaemon()
        try {
            rc(d, "operations/about", buildJsonObject { put("fs", "$remoteName:") })
            return@runCatching
        } catch (_: Exception) { /* fall through to listDir */ }
        // Fallback: list root with a 1-item limit to confirm the remote responds.
        rc(d, "operations/list", buildJsonObject {
            put("fs", "$remoteName:")
            put("remote", "")
            putJsonObject("opt") { put("recurse", false) }
        })
    }
```

- [ ] **Step 5: Add `testConnectivity` to `RemoteRepository`**

```kotlin
    /** Lightweight connectivity check — verifies the remote responds. */
    suspend fun testConnectivity(remoteName: String): Result<Unit> =
        runCatching { engine.testConnectivity(remoteName).getOrThrow() }
```

- [ ] **Step 6: Run tests, verify pass**

Run: `./gradlew :core:rclone:testDebugUnitTest :core:data:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngine.kt \
        core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngineImpl.kt \
        core/data/src/main/kotlin/app/lusk/virga/core/data/RemoteRepository.kt \
        core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/RcloneEngineImplTest.kt \
        core/data/src/test/kotlin/app/lusk/virga/core/data/RemoteRepositoryTest.kt
git commit -m "Add testConnectivity (about with listDir fallback) to engine and repository"
```

---

## Task 2: `sensitiveKeys` derivation from schema + wiring into `addRemote`

**Why:** The credential form submits password fields (where `RemoteOption.isPassword == true`) that must be obscured by rclone. The VM needs to derive `sensitiveKeys` from the schema and pass them through `repository.addRemote`. Phase 1 delivered the `sensitiveKeys` parameter; this task wires the derivation logic.

**Files:**
- Modify: `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt`
- Create: `feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/SensitiveKeysTest.kt`
- Modify: `feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `SensitiveKeysTest.kt` — pure unit test for the derivation function:

```kotlin
package app.lusk.virga.feature.remotes

import app.lusk.virga.core.common.model.RemoteOption
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SensitiveKeysTest {

    private fun opt(name: String, isPassword: Boolean = false) = RemoteOption(
        name = name, help = "", type = "string", required = false,
        isPassword = isPassword, default = null, examples = emptyList(), advanced = false,
    )

    @Test fun `derives sensitive keys from isPassword options present in values`() {
        val options = listOf(opt("host"), opt("pass", isPassword = true), opt("key_file"))
        val values = mapOf("host" to "example.com", "pass" to "secret", "key_file" to "/path")

        val keys = sensitiveKeysFrom(options, values)

        assertThat(keys).containsExactly("pass")
    }

    @Test fun `ignores password options not present in values`() {
        val options = listOf(opt("pass", isPassword = true), opt("pass2", isPassword = true))
        val values = mapOf("pass" to "x")

        val keys = sensitiveKeysFrom(options, values)

        assertThat(keys).containsExactly("pass")
    }

    @Test fun `ignores password options with blank values`() {
        val options = listOf(opt("pass", isPassword = true))
        val values = mapOf("pass" to "")

        val keys = sensitiveKeysFrom(options, values)

        assertThat(keys).isEmpty()
    }

    @Test fun `returns empty set when no password options exist`() {
        val options = listOf(opt("host"), opt("port"))
        val values = mapOf("host" to "x", "port" to "22")

        val keys = sensitiveKeysFrom(options, values)

        assertThat(keys).isEmpty()
    }
}
```

Add to `RemotesViewModelTest`:

```kotlin
@Test
fun addRemote_passes_sensitiveKeys_derived_from_schema() = runTest(mainDispatcher.dispatcher) {
    val options = listOf(
        RemoteOption("host", "", "string", true, false, null, emptyList(), false),
        RemoteOption("pass", "", "string", false, true, null, emptyList(), false),
    )
    coEvery { repository.providers() } returns listOf(
        RemoteProvider(name = "sftp", description = "SFTP", options = options),
    )
    coEvery { repository.addRemote(any(), any(), any(), any()) } returns Result.success(Unit)
    val vm = viewModel()
    val collector = backgroundScope.launch { vm.uiState.collect {} }
    vm.ensureProvidersLoaded()
    advanceUntilIdle()

    vm.addRemote("mysftp", "sftp", "host=example.com\npass=secret") { _, _ -> }
    advanceUntilIdle()

    val sensitiveSlot = slot<Set<String>>()
    coVerify { repository.addRemote("mysftp", "sftp", any(), capture(sensitiveSlot)) }
    assertThat(sensitiveSlot.captured).containsExactly("pass")
    collector.cancel()
}
```

- [ ] **Step 2: Run, verify failure**

Run: `./gradlew :feature:remotes:testDebugUnitTest --tests "*SensitiveKeysTest*"`
Expected: compile error — `sensitiveKeysFrom` does not exist.

- [ ] **Step 3: Implement the derivation function**

In `RemotesViewModel.kt`, add an `internal` top-level function (testable without the VM):

```kotlin
/**
 * Derives the set of option names that carry sensitive values (passwords) from the
 * schema and the user-entered values. Only options where [RemoteOption.isPassword]
 * is true AND the value is non-blank are included — rclone's `opt.obscure` applies
 * to all params when set, but naming the keys documents intent and keeps the
 * contract explicit for callers.
 */
internal fun sensitiveKeysFrom(
    options: List<RemoteOption>,
    values: Map<String, String>,
): Set<String> = options
    .filter { it.isPassword && values[it.name]?.isNotBlank() == true }
    .map { it.name }
    .toSet()
```

- [ ] **Step 4: Wire it into `addRemote`**

In `RemotesViewModel.addRemote`, after building `params` from the `key=value` lines, derive and pass `sensitiveKeys`:

```kotlin
    fun addRemote(
        name: String,
        type: String,
        paramsText: String,
        onResult: (success: Boolean, error: String?) -> Unit,
    ) {
        val params = paramsText.lines()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .toMap()
        // Derive which params carry passwords from the loaded schema — tells rclone
        // to obscure only those values (not the OAuth token or other safe fields).
        val options = allOptionsForBackend(type.trim().lowercase())
        val sensitiveKeys = if (options != null) sensitiveKeysFrom(options, params) else emptySet()
        viewModelScope.launch {
            val result = repository.addRemote(name.trim(), type.trim().lowercase(), params, sensitiveKeys)
            if (result.isSuccess) pendingRemoteResult.created(name.trim())
            onResult(result.isSuccess, result.exceptionOrNull()?.toUserMessage())
        }
    }
```

- [ ] **Step 5: Run tests, verify pass**

Run: `./gradlew :feature:remotes:testDebugUnitTest --tests "*SensitiveKeysTest*" --tests "*RemotesViewModelTest*addRemote_passes*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt \
        feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/SensitiveKeysTest.kt \
        feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt
git commit -m "Derive sensitiveKeys from schema isPassword and pass to addRemote"
```

---

## Task 3: Connectivity test after remote creation in the ViewModel

**Why:** The design requires a post-creation connectivity test — success finalizes; failure warns but keeps the config so the user can retry or fix credentials.

**Files:**
- Modify: `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt`
- Modify: `feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `RemotesViewModelTest`:

```kotlin
@Test
fun addRemote_runs_connectivity_test_after_creation() = runTest(mainDispatcher.dispatcher) {
    coEvery { repository.addRemote(any(), any(), any(), any()) } returns Result.success(Unit)
    coEvery { repository.testConnectivity("r1") } returns Result.success(Unit)
    val vm = viewModel()
    val collector = backgroundScope.launch { vm.uiState.collect {} }
    advanceUntilIdle()

    var success = false
    vm.addRemote("r1", "s3", "access_key_id=x") { s, _ -> success = s }
    advanceUntilIdle()

    assertThat(success).isTrue()
    coVerify { repository.testConnectivity("r1") }
    collector.cancel()
}

@Test
fun addRemote_warns_but_keeps_remote_when_connectivity_test_fails() = runTest(mainDispatcher.dispatcher) {
    coEvery { repository.addRemote(any(), any(), any(), any()) } returns Result.success(Unit)
    coEvery { repository.testConnectivity("r1") } returns Result.failure(Exception("timeout"))
    val vm = viewModel()
    val collector = backgroundScope.launch { vm.uiState.collect {} }
    advanceUntilIdle()

    var success = false
    var errorMsg: String? = null
    vm.addRemote("r1", "s3", "access_key_id=x") { s, e -> success = s; errorMsg = e }
    advanceUntilIdle()

    // Creation itself succeeded — the remote is kept.
    assertThat(success).isTrue()
    // But the VM surfaces a warning message about connectivity.
    assertThat(vm.uiState.value.message).contains("could not verify")
    collector.cancel()
}
```

- [ ] **Step 2: Run, verify failure**

Run: `./gradlew :feature:remotes:testDebugUnitTest --tests "*RemotesViewModelTest*connectivity*"`
Expected: FAIL — `repository.testConnectivity` is never called; no warning message emitted.

- [ ] **Step 3: Add the connectivity test to `addRemote`**

In `RemotesViewModel.addRemote`, after the successful `repository.addRemote`, add:

```kotlin
            if (result.isSuccess) {
                pendingRemoteResult.created(name.trim())
                // Run a connectivity test; warn (but don't fail) if it doesn't pass.
                val testResult = repository.testConnectivity(name.trim())
                if (testResult.isFailure) {
                    transient.update {
                        it.copy(message = context.getString(R.string.remotes_msg_connectivity_warning, name.trim()))
                    }
                }
            }
            onResult(result.isSuccess, result.exceptionOrNull()?.toUserMessage())
```

Add the string resource (will need to be added to `strings.xml`):

```xml
<string name="remotes_msg_connectivity_warning">Remote \"%1$s\" was saved, but could not verify connectivity. Check your credentials.</string>
```

- [ ] **Step 4: Add the mock context getString stub for the new string**

In `RemotesViewModelTest`, in the mock `context` setup, add:

```kotlin
R.string.remotes_msg_connectivity_warning ->
    "Remote \"${args[0]}\" was saved, but could not verify connectivity. Check your credentials."
```

- [ ] **Step 5: Run tests, verify pass**

Run: `./gradlew :feature:remotes:testDebugUnitTest --tests "*RemotesViewModelTest*connectivity*"`
Expected: PASS.

- [ ] **Step 6: Also add connectivity test to the OAuth success path**

In `onOAuthResult` → `OAuthResult.Success`, after the successful `addRemote` + `pendingRemoteResult.created(remoteName)`:

```kotlin
                // Connectivity test (warn, don't block).
                val testResult = repository.testConnectivity(remoteName)
                val connectWarning = if (testResult.isFailure) {
                    " " + context.getString(R.string.remotes_msg_connectivity_warning, remoteName)
                } else ""
```

And append `connectWarning` to the success message.

- [ ] **Step 7: Run full VM tests, verify pass**

Run: `./gradlew :feature:remotes:testDebugUnitTest --tests "*RemotesViewModelTest*"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt \
        feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt \
        feature/remotes/src/main/res/values/strings.xml
git commit -m "Run connectivity test after remote creation; warn on failure"
```

---

## Task 4: Expose `ProviderCatalog` from ViewModel for the picker

**Why:** The picker Composable needs a list of entries grouped by `SetupKind`. The VM already holds the provider schema in `_providers`; exposing a `ProviderCatalog` (or its `pickerEntries()` result) lets the UI render without knowing classification logic.

**Files:**
- Modify: `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt`
- Modify: `feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `RemotesViewModelTest`:

```kotlin
@Test
fun pickerEntries_returns_catalog_entries_after_providers_loaded() = runTest(mainDispatcher.dispatcher) {
    coEvery { repository.providers() } returns listOf(
        RemoteProvider("drive", "Google Drive", listOf(
            RemoteOption("client_id", "", "string", false, false, null, emptyList(), false),
            RemoteOption("token", "", "string", false, false, null, emptyList(), true),
        )),
        RemoteProvider("sftp", "SFTP", listOf(
            RemoteOption("host", "", "string", true, false, null, emptyList(), false),
        )),
        RemoteProvider("crypt", "Encrypt/Decrypt", listOf(
            RemoteOption("remote", "", "string", true, false, null, emptyList(), false),
        )),
    )
    val vm = viewModel()
    vm.ensureProvidersLoaded()
    advanceUntilIdle()

    val entries = vm.pickerEntries()
    assertThat(entries).isNotNull()
    assertThat(entries!!.map { it.type }).containsAtLeast("drive", "sftp", "crypt")
    // local should never appear (but it's not in this fixture anyway)
}

@Test
fun setupKind_classifies_providers_correctly() = runTest(mainDispatcher.dispatcher) {
    coEvery { repository.providers() } returns listOf(
        RemoteProvider("drive", "Google Drive", listOf(
            RemoteOption("client_id", "", "string", false, false, null, emptyList(), false),
            RemoteOption("token", "", "string", false, false, null, emptyList(), true),
        )),
        RemoteProvider("sftp", "SFTP", listOf(
            RemoteOption("host", "", "string", true, false, null, emptyList(), false),
        )),
        RemoteProvider("crypt", "Encrypt/Decrypt", listOf(
            RemoteOption("remote", "", "string", true, false, null, emptyList(), false),
        )),
    )
    val vm = viewModel()
    vm.ensureProvidersLoaded()
    advanceUntilIdle()

    assertThat(vm.setupKindFor("drive")).isEqualTo(SetupKind.OAuth(bundled = true))
    assertThat(vm.setupKindFor("sftp")).isEqualTo(SetupKind.Credential)
    assertThat(vm.setupKindFor("crypt")).isEqualTo(SetupKind.Wrapper)
}
```

- [ ] **Step 2: Run, verify compile failure**

Run: `./gradlew :feature:remotes:testDebugUnitTest --tests "*RemotesViewModelTest*picker*"`
Expected: compile error — `pickerEntries()` and `setupKindFor()` do not exist on the VM.

- [ ] **Step 3: Expose catalog accessors**

In `RemotesViewModel.kt`, add:

```kotlin
    /** The [ProviderCatalog] built from the loaded schema, or null before load. */
    private val catalog: ProviderCatalog?
        get() = _providers.value?.let { if (it.isEmpty()) null else ProviderCatalog(it) }

    /** Picker entries for the Add-remote picker, or null when schema is not loaded. */
    fun pickerEntries(): List<PickerEntry>? = catalog?.pickerEntries()

    /** Classification of a backend type for routing the Add-remote flow. */
    fun setupKindFor(type: String): SetupKind = catalog?.setupKind(type) ?: SetupKind.Credential
```

Add the import:

```kotlin
import app.lusk.virga.core.rclone.PickerEntry
import app.lusk.virga.core.rclone.ProviderCatalog
import app.lusk.virga.core.rclone.SetupKind
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :feature:remotes:testDebugUnitTest --tests "*RemotesViewModelTest*picker*" --tests "*RemotesViewModelTest*setupKind*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt \
        feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt
git commit -m "Expose ProviderCatalog picker entries and setupKind from RemotesViewModel"
```

---

## Task 5: `ProviderPicker` Composable — searchable, grouped

**Why:** The design spec calls for a searchable picker, with popular cloud pinned first, then all providers, then wrappers in a separate section. This replaces the static `RcloneBackendTypes` dropdown.

**Files:**
- Create: `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/ProviderPicker.kt`

- [ ] **Step 1: Create the ProviderPicker Composable**

```kotlin
package app.lusk.virga.feature.remotes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.lusk.virga.core.designsystem.theme.VirgaSpacing
import app.lusk.virga.core.rclone.PickerEntry
import app.lusk.virga.core.rclone.ProviderCatalog
import app.lusk.virga.core.rclone.SetupKind

/**
 * Searchable provider picker backed by [ProviderCatalog.pickerEntries]. Entries
 * are displayed in catalog order (pinned first). Wrappers are separated into their
 * own section at the bottom. The search field filters by type name and description.
 */
@Composable
internal fun ProviderPicker(
    entries: List<PickerEntry>,
    setupKindFor: (String) -> SetupKind,
    onSelect: (PickerEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(entries, query) {
        if (query.isBlank()) entries
        else {
            val q = query.trim().lowercase()
            entries.filter { it.type.lowercase().contains(q) || it.description.lowercase().contains(q) }
        }
    }
    // Partition into main providers and wrappers.
    val (wrappers, providers) = filtered.partition { setupKindFor(it.type) == SetupKind.Wrapper }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.remotes_picker_search)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(VirgaSpacing.xs),
        ) {
            items(providers, key = { it.type }) { entry ->
                ProviderRow(entry = entry, onClick = { onSelect(entry) })
            }
            if (wrappers.isNotEmpty()) {
                item(key = "__wrapper_divider") {
                    Column {
                        HorizontalDivider(Modifier.padding(vertical = VirgaSpacing.sm))
                        Text(
                            stringResource(R.string.remotes_picker_wrappers_header),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = VirgaSpacing.xs),
                        )
                    }
                }
                items(wrappers, key = { it.type }) { entry ->
                    ProviderRow(entry = entry, onClick = { onSelect(entry) })
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(entry: PickerEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = VirgaSpacing.sm, horizontal = VirgaSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
    ) {
        RemoteProviderMark(
            type = entry.type,
            contentDescription = entry.description,
        )
        Column {
            Text(entry.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                entry.type,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 2: Add string resources**

In `feature/remotes/src/main/res/values/strings.xml`:

```xml
<string name="remotes_picker_search">Search providers…</string>
<string name="remotes_picker_wrappers_header">Advanced · wrap a remote</string>
```

- [ ] **Step 3: Verify the feature module compiles**

Run: `./gradlew :feature:remotes:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/ProviderPicker.kt \
        feature/remotes/src/main/res/values/strings.xml
git commit -m "Add ProviderPicker searchable Composable backed by ProviderCatalog"
```

---

## Task 6: Wire the picker into `AddRemoteDialog` — replace the static dropdown

**Why:** The current dialog uses `RcloneBackendTypes` (14 hardcoded entries) as a searchable dropdown. This task replaces it with the `ProviderPicker` backed by the live schema, and routes selection by `SetupKind`: OAuth → existing OAuth flow, Credential → typed form, Wrapper → placeholder (Phase 3).

**Files:**
- Modify: `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/AddRemoteDialog.kt`
- Modify: `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/AddRemoteScreen.kt`
- Modify: `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesScreen.kt`

- [ ] **Step 1: Redesign `AddRemoteDialog` state machine**

The dialog transitions through these steps:
1. **Picker** — user sees the provider list (when schema is loaded) or the legacy dropdown (fallback).
2. **Credential form** — user fills typed fields, confirms → `addRemote`.
3. **OAuth** — user clicks the bundled chip → `onOAuth` (no form state change needed).
4. **Wrapper placeholder** — "Coming soon" text for Phase 3.

Add a sealed interface for the dialog step, at the top of `AddRemoteDialog.kt`:

```kotlin
private sealed interface AddStep {
    data object Picker : AddStep
    data class CredentialForm(val type: String, val description: String) : AddStep
    data class WrapperPlaceholder(val type: String) : AddStep
}
```

- [ ] **Step 2: Refactor `AddRemoteDialog` to use the picker**

Add new parameters to `AddRemoteDialog`:

```kotlin
    /** Picker entries from the loaded ProviderCatalog, or null when not yet ready. */
    pickerEntries: List<PickerEntry>?,
    /** Classifies a backend type for routing. */
    setupKindFor: (String) -> SetupKind,
```

Replace the `ExposedDropdownMenuBox` for type selection with a conditional:
- When `step == AddStep.Picker` and `pickerEntries != null`, show `ProviderPicker`.
- When `pickerEntries` is null (schema failed to load), fall back to the legacy `RcloneBackendTypes` dropdown.
- On picker selection, route by `setupKindFor`:
  - `SetupKind.OAuth(bundled = true)` → call `onOAuth` with the matching `OAuthProvider` and the entered name.
  - `SetupKind.OAuth(bundled = false)` or `SetupKind.Credential` → transition to `AddStep.CredentialForm(type)`.
  - `SetupKind.Wrapper` → transition to `AddStep.WrapperPlaceholder(type)`.

The name field stays at the top across all steps. A "Back" button on the form/placeholder returns to the picker.

- [ ] **Step 3: Update callers to pass new params**

In `AddRemoteScreen.kt` and `RemotesScreen.kt`, pass:

```kotlin
pickerEntries = viewModel.pickerEntries(),
setupKindFor = viewModel::setupKindFor,
```

(Both already have `viewModel` in scope and call `ensureProvidersLoaded` via the dialog's `LaunchedEffect`.)

- [ ] **Step 4: Handle the legacy fallback**

When `pickerEntries` is null (providers failed to load or are still loading), the existing `RcloneBackendTypes` dropdown + freeform flow remains available. The UX degrades gracefully: users can still type a backend type and get the freeform editor.

- [ ] **Step 5: Verify compile**

Run: `./gradlew :feature:remotes:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Run all feature:remotes tests to check nothing regressed**

Run: `./gradlew :feature:remotes:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/AddRemoteDialog.kt \
        feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/AddRemoteScreen.kt \
        feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesScreen.kt
git commit -m "Replace hardcoded backend dropdown with ProviderPicker; route by SetupKind"
```

---

## Task 7: Required-field validation on the credential form

**Why:** The schema marks options as `required`. The "Create" button should be disabled when any required field is blank, and required fields should show a visual indicator. `TypedOptionFields` already appends a " *" suffix to required labels; this task adds the validation gate to the confirm button.

**Files:**
- Modify: `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/AddRemoteDialog.kt`

- [ ] **Step 1: Add validation logic**

In the `AddStep.CredentialForm` branch of the dialog, compute form validity:

```kotlin
val requiredOptions = schemaOptions?.filter { it.required }.orEmpty()
val formValid = requiredOptions.all { opt ->
    val value = typedValues[opt.name] ?: opt.default.orEmpty()
    value.isNotBlank()
}
```

- [ ] **Step 2: Gate the Create button**

Change the `enabled` condition of the Create `TextButton` for the credential form step:

```kotlin
enabled = nameUsable && formValid,
```

- [ ] **Step 3: Show error state on empty required fields**

In `TypedOptionFields` → `OptionField`, add `isError` to `OutlinedTextField` when the option is required and its value is blank:

```kotlin
isError = opt.required && current.isBlank(),
```

- [ ] **Step 4: Verify compile**

Run: `./gradlew :feature:remotes:compileFossDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/AddRemoteDialog.kt \
        feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemoteOptionFields.kt
git commit -m "Disable Create until required schema fields are filled; show error state"
```

---

## Task 8: Full-suite gate + lint

- [ ] **Step 1: Run the whole unit-test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — every module's unit tests pass.

- [ ] **Step 2: Run lint on the foss debug variant**

Run: `./gradlew lintFossDebug`
Expected: no new errors.

- [ ] **Step 3: Final commit if lint auto-touched anything**

```bash
git add -A
git commit -m "Phase 2: pass full unit-test suite and lint" || echo "nothing to commit"
```

---

## Self-review

**Spec coverage for Phase 2's slice:**
- "Searchable picker backed by ProviderCatalog; popular cloud pinned, then all providers, then wrappers" → Tasks 4-6.
- "Schema-driven credential form for any credential backend with TypedOptionFields" → already exists from Phase 1 Task 8; Task 6 routes to it; Task 7 adds validation.
- "sensitiveKeys from IsPassword → config/create" → Task 2.
- "Connectivity test after save" → Tasks 1, 3.
- "Required first; Advanced expander" → already delivered in Phase 1 Task 8 (`TypedOptionFields` partitions basic/advanced).
- Wrapper sub-flow → **deferred to Phase 3** (placeholder only in Task 6).
- BYOK rclone-delegated OAuth → **deferred to Phase 4**.

**Incremental buildability:** Each task is independently compilable and testable. Tasks 1-3 are backend/VM work with no UI changes. Tasks 4-5 build the picker. Task 6 wires everything together. Task 7 is a polish pass on validation. Task 8 is the gate.

**What's NOT changed:** The OAuth chips and BYO keys UI stay for now — they work for the bundled four. Phase 4 handles the BYOK long-tail flow. The crypt flow stays — Phase 3 folds it into the wrapper sub-flow. The `RcloneBackendTypes` list remains in `RemoteComponents.kt` for the `RemoteCard` friendly-name display (the picker doesn't need it).
