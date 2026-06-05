# All rclone providers — Phase 1: classification foundation + Box bundled OAuth

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lay the classification + obscuring foundation the rest of 0.2.0 builds on, and ship Box as a fourth bundled OAuth provider, without touching the picker/wrapper UI yet.

**Architecture:** Add a pure `ProviderCatalog` classification layer over the existing `RcloneEngine.providers()` schema in `core:rclone`. Give `createRemote` a `sensitiveKeys` parameter so it asks rclone to obscure password values without ever touching OAuth token JSON. Teach the OAuth chain (`OAuthProvider` → `PendingAuth` → `OAuthTokenExchanger.exchange` → `OAuthConfig`/DI → `RemotesViewModel.startOAuth`) to carry a `client_secret`, which Box's token exchange needs and the PKCE three do not. Fix the one accessor that hides advanced options from the existing credential form.

**Tech stack:** Kotlin, JUnit 5, MockK, Truth, Turbine, OkHttp `MockWebServer`, kotlinx.serialization JSON, Hilt, Jetpack Compose, Gradle (AGP 9.2.1, JDK 21).

**Scope note — this is plan 1 of 4.** The full 0.2.0 design (`docs/superpowers/specs/2026-06-04-all-rclone-providers-design.md`) is too large for one plan. The sequence:

1. **This plan** — classification foundation, `sensitiveKeys` obscuring, full-options accessor, Box bundled OAuth.
2. **Picker + schema-driven credential form** — searchable provider picker backed by `ProviderCatalog`; the dynamic credential form for any credential backend (reuses the now-fixed `TypedOptionFields`).
3. **Wrapper sub-flow + crypt fold-in** — "wrap an existing remote" flow for crypt/union/alias/combine/chunker/compress/cache/hasher; remove the standalone crypt screen.
4. **BYOK rclone-delegated OAuth (the spike)** — drive rclone's daemon OAuth over the RC API for the long tail, with the BYOK paste-token fallback. This is the spec's primary risk; it is isolated here because the bundled four and the forms do not depend on it.

Each later phase gets its own plan written via this same skill when we reach it.

**Conventions used below**
- Per-module unit tests: `./gradlew :<module>:testDebugUnitTest`. The whole suite (release gate): `./gradlew test`.
- Test stack matches the existing files: JUnit 5 (`org.junit.jupiter`), MockK, Truth (`assertThat`), Turbine, `MockWebServer`.
- Commit after every green task. Commit messages here omit any `Co-Authored-By` trailer (project rule).

---

## File structure for Phase 1

**Create:**
- `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/ProviderCatalog.kt` — pure classification (`SetupKind`, `classify`, picker ordering, the curated override map). No Android deps, no daemon.
- `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/ProviderCatalogTest.kt`
- `core/rclone/src/test/resources/config-providers-sample.json` — a trimmed real `config/providers` fixture (drive, s3, sftp, crypt, union, box, local) for classification tests.

**Modify:**
- `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/OAuthProvider.kt` — add `Box`, add `requiresClientSecret`, update the stale "no secret" comment, extend `All`.
- `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/OAuthTokenExchanger.kt` — `PendingAuth.clientSecret`; send `client_secret` in `exchange` when present.
- `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/OAuthConfig.kt` — `clientSecrets` map + `clientSecret(id)`.
- `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngine.kt` — `createRemote` gains `sensitiveKeys: Set<String> = emptySet()`.
- `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngineImpl.kt` — set `opt.obscure` when `sensitiveKeys` is non-empty.
- `core/data/src/main/kotlin/app/lusk/virga/core/data/RemoteRepository.kt` — thread `sensitiveKeys` through `addRemote`.
- `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt` — add `allOptionsForBackend`; carry the Box client secret in `startOAuth`; pass sensitive keys when creating from the form.
- `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/AddRemoteDialog.kt` — read the full option list so the advanced expander appears.
- `app/src/main/kotlin/app/lusk/virga/di/OAuthConfigModule.kt` — wire Box client id + secret and Box into the maps.
- `app/build.gradle.kts` — `OAUTH_CLIENT_ID_BOX` + `OAUTH_CLIENT_SECRET_BOX` BuildConfig fields and the secret helper.
- `.github/workflows/release.yml` — pass the Box id/secret env into the release build step.

**Touched tests (existing):**
- `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/RcloneEngineImplTest.kt`
- `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/oauth/OAuthTokenExchangerTest.kt`
- `core/data/src/test/kotlin/app/lusk/virga/core/data/RemoteRepositoryTest.kt`
- `feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt`

---

## Task 1: `createRemote` gains `sensitiveKeys`; obscure only when present

**Why:** Today `createRemote` sets only `opt.nonInteractive`; only `createCryptRemote` adds `opt.obscure`. The schema credential form (Phase 2) will submit password fields that must be obscured, but the same `createRemote` also creates OAuth remotes whose `token` is pre-formatted JSON that must never be re-obscured. A `sensitiveKeys` set lets a caller signal "this create includes secret values, ask rclone to obscure" while OAuth creates (empty set) leave `obscure` off.

**Files:**
- Modify: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngine.kt:65`
- Modify: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/RcloneEngineImpl.kt:147-167`
- Test: `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/RcloneEngineImplTest.kt`

- [x] **Step 1: Write the failing tests**

- [x] **Step 2: Run the tests, verify they fail to compile**

- [x] **Step 3: Add the parameter to the interface**

- [x] **Step 4: Implement in the engine**

- [x] **Step 5: Run the tests, verify they pass**

All 33 tests pass.

- [x] **Step 6: Commit**

`6ac5051 Add sensitiveKeys to createRemote so only password creates obscure`

---

## Task 2: Thread `sensitiveKeys` through `RemoteRepository.addRemote`

**Files:**
- Modify: `core/data/src/main/kotlin/app/lusk/virga/core/data/RemoteRepository.kt:52-54`
- Test: `core/data/src/test/kotlin/app/lusk/virga/core/data/RemoteRepositoryTest.kt`

- [x] **Step 1: Write the failing test**

- [x] **Step 2: Run it, verify failure**

- [x] **Step 3: Update the repository**

- [x] **Step 4: Fix the two existing stubs**

- [x] **Step 5: Run tests, verify pass**

All 15 tests pass.

- [x] **Step 6: Commit**

`668fa81 Thread sensitiveKeys through RemoteRepository.addRemote`

---

## Task 3: `client_secret` support in `OAuthProvider` + `PendingAuth` + `exchange`

**Why:** Box's authorization-code token exchange requires a `client_secret` even with PKCE; Drive/Dropbox/OneDrive are public PKCE clients that send none. Model the requirement on the provider and send the secret only when present.

**Files:**
- Modify: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/OAuthProvider.kt`
- Modify: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/OAuthTokenExchanger.kt:36-48,72-79`
- Test: `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/oauth/OAuthTokenExchangerTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `OAuthTokenExchangerTest`. It enqueues a token response, exchanges with a `clientSecret`, and reads the recorded request body.

```kotlin
@Test
fun exchange_includesClientSecretWhenPresent() = runTest {
    server.enqueue(
        MockResponse().setBody("""{"access_token":"a","refresh_token":"r","expires_in":3600}"""),
    )
    val p = pending(provider(id = "box", type = "box")).copy(clientSecret = "shh-secret")

    exchanger.exchange(p, "auth-code").getOrThrow()

    val body = server.takeRequest().body.readUtf8()
    assertThat(body).contains("client_secret=shh-secret")
}

@Test
fun exchange_omitsClientSecretWhenAbsent() = runTest {
    server.enqueue(
        MockResponse().setBody("""{"access_token":"a","expires_in":3600}"""),
    )

    exchanger.exchange(pending(), "auth-code").getOrThrow()

    val body = server.takeRequest().body.readUtf8()
    assertThat(body).doesNotContain("client_secret")
}
```

- [ ] **Step 2: Run it, verify failure**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*OAuthTokenExchangerTest"`
Expected: compile error — `PendingAuth` has no `clientSecret`.

- [ ] **Step 3: Add `clientSecret` to `PendingAuth`**

In `OAuthTokenExchanger.kt`, add the field to `PendingAuth` (after `redirectUri`, around line 41):

```kotlin
        val redirectUri: String,
        /** Sent as `client_secret` in the token exchange when the provider needs one
         *  (Box). Null for public PKCE clients (Drive/Dropbox/OneDrive). */
        val clientSecret: String? = null,
```

- [ ] **Step 4: Send it in `exchange`**

In `exchange` (lines 72-79), build the form body conditionally:

```kotlin
    suspend fun exchange(p: PendingAuth, code: String): Result<String> = withContext(dispatchers.io) {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", p.redirectUri)
            .add("client_id", p.clientId)
            .add("code_verifier", p.verifier)
            .apply { if (!p.clientSecret.isNullOrBlank()) add("client_secret", p.clientSecret) }
            .build()
```

- [ ] **Step 5: Run tests, verify pass**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*OAuthTokenExchangerTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/OAuthTokenExchanger.kt \
        core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/oauth/OAuthTokenExchangerTest.kt
git commit -m "Send client_secret in OAuth token exchange when the provider needs one"
```

---

## Task 4: Add Box to the bundled OAuth providers

**Files:**
- Modify: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/OAuthProvider.kt`
- Test: create `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/oauth/OAuthProvidersTest.kt`

- [ ] **Step 1: Write the failing test**

Create `OAuthProvidersTest.kt`:

```kotlin
package app.lusk.virga.core.rclone.oauth

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OAuthProvidersTest {
    @Test fun `Box is a bundled provider that requires a client secret`() {
        val box = OAuthProviders.byId("box")
        assertThat(box).isNotNull()
        assertThat(box!!.type).isEqualTo("box")
        assertThat(box.requiresClientSecret).isTrue()
    }

    @Test fun `the PKCE three do not require a client secret`() {
        listOf("gdrive", "onedrive", "dropbox").forEach { id ->
            assertThat(OAuthProviders.byId(id)!!.requiresClientSecret).isFalse()
        }
    }

    @Test fun `All lists exactly the four bundled providers`() {
        assertThat(OAuthProviders.All.map { it.id })
            .containsExactly("gdrive", "onedrive", "dropbox", "box")
    }
}
```

- [ ] **Step 2: Run it, verify failure**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*OAuthProvidersTest"`
Expected: compile error — `requiresClientSecret` does not exist; `byId("box")` returns null.

- [ ] **Step 3: Add the field and the Box provider**

In `OAuthProvider.kt`, replace the data class and update the comment, then add Box and extend `All`:

```kotlin
/**
 * Configuration for an OAuth provider Virga supports out of the box. [type] is
 * the corresponding rclone backend name used when calling `config/create`.
 *
 * Drive, Dropbox, and OneDrive are public PKCE clients — no client secret ships
 * in the APK. Box is the exception: its token exchange requires a client_secret,
 * so [requiresClientSecret] is true and the secret is supplied from BuildConfig.
 */
data class OAuthProvider(
    val id: String,
    val displayName: String,
    /** rclone backend type, e.g. "drive", "onedrive", "dropbox", "box". */
    val type: String,
    val authEndpoint: String,
    val tokenEndpoint: String,
    val scopes: List<String>,
    /** True when the token exchange must send a client_secret (Box). */
    val requiresClientSecret: Boolean = false,
)
```

Add the Box provider after `Dropbox` (before `All`):

```kotlin
    val Box = OAuthProvider(
        id = "box",
        displayName = "Box",
        type = "box",
        authEndpoint = "https://account.box.com/api/oauth2/authorize",
        tokenEndpoint = "https://api.box.com/oauth2/token",
        // Box derives the granted scope from the app's configuration; it does not
        // require a scope parameter on the authorize URL.
        scopes = emptyList(),
        requiresClientSecret = true,
    )
```

Update `All`:

```kotlin
    val All = listOf(GoogleDrive, OneDrive, Dropbox, Box)
```

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*OAuthProvidersTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/OAuthProvider.kt \
        core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/oauth/OAuthProvidersTest.kt
git commit -m "Add Box as a fourth bundled OAuth provider (needs client_secret)"
```

---

## Task 5: `clientSecrets` in `OAuthConfig`

**Files:**
- Modify: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/OAuthConfig.kt`
- Test: create `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/oauth/OAuthConfigTest.kt`

- [ ] **Step 1: Write the failing test**

Create `OAuthConfigTest.kt`:

```kotlin
package app.lusk.virga.core.rclone.oauth

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OAuthConfigTest {
    @Test fun `clientSecret returns the configured secret or null`() {
        val config = OAuthConfig(
            defaultRedirectUri = "https://x/cb",
            clientIds = mapOf("box" to "id"),
            clientSecrets = mapOf("box" to "secret"),
        )
        assertThat(config.clientSecret("box")).isEqualTo("secret")
        assertThat(config.clientSecret("gdrive")).isNull()
    }
}
```

- [ ] **Step 2: Run it, verify failure**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*OAuthConfigTest"`
Expected: compile error — no `clientSecrets` constructor parameter.

- [ ] **Step 3: Add the field and accessor**

In `OAuthConfig.kt`, add the parameter and method:

```kotlin
data class OAuthConfig(
    val defaultRedirectUri: String,
    val clientIds: Map<String, String>,
    /** Per-provider override; if absent, [defaultRedirectUri] is used. */
    val redirectUris: Map<String, String> = emptyMap(),
    /** Per-provider client secret; only providers that need one (Box) appear here. */
    val clientSecrets: Map<String, String> = emptyMap(),
) {
    fun clientId(providerId: String): String = clientIds[providerId].orEmpty()
    fun redirectUri(providerId: String): String =
        redirectUris[providerId] ?: defaultRedirectUri

    /** Configured client secret for [providerId], or null when it is a public client
     *  or the secret is unset (blank treated as unset). */
    fun clientSecret(providerId: String): String? =
        clientSecrets[providerId]?.ifBlank { null }
```

(The existing `companion object` with `googleAndroidRedirect` stays unchanged below this.)

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*OAuthConfigTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/oauth/OAuthConfig.kt \
        core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/oauth/OAuthConfigTest.kt
git commit -m "Add clientSecrets map to OAuthConfig"
```

---

## Task 6: Carry the Box client secret through `RemotesViewModel.startOAuth`

**Why:** `startOAuth` builds the `PendingAuth`. For Box it must attach the configured secret so `exchange` sends it.

**Files:**
- Modify: `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt:355-385`
- Test: `feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `RemotesViewModelTest`. Match the existing VM-test construction (the `setUp`/`@BeforeEach` already builds a `RemotesViewModel` with mocked collaborators; reuse those mocks — `oauthConfig`, `oauthStore`, `tokenExchanger`, `oauthKeyStore`). The test drives `startOAuth(Box, "boxremote")` and verifies the pending auth captured by `oauthStore.startPending` carries the secret.

```kotlin
@Test fun `startOAuth attaches the client secret for Box`() = runTest {
    every { oauthKeyStore.clientId("box") } returns null
    every { oauthConfig.clientId("box") } returns "box-id"
    every { oauthConfig.redirectUri("box") } returns "https://lusk.app/virga/oauth/callback"
    every { oauthConfig.clientSecret("box") } returns "box-secret"
    every { tokenExchanger.authorizeUrl(any()) } returns "https://account.box.com/api/oauth2/authorize?x=1"
    val pending = slot<OAuthTokenExchanger.PendingAuth>()
    coEvery { oauthStore.startPending(capture(pending)) } returns Unit

    viewModel.startOAuth(OAuthProviders.Box, "boxremote")
    advanceUntilIdle()

    assertThat(pending.captured.clientSecret).isEqualTo("box-secret")
}
```

(`oauthKeyStore.clientId` is a `suspend` function in `startOAuth`; if the existing tests stub it with `coEvery`, use `coEvery` here too. Match the existing stubs' style.)

- [ ] **Step 2: Run it, verify failure**

Run: `./gradlew :feature:remotes:testDebugUnitTest --tests "*RemotesViewModelTest"`
Expected: FAIL — `pending.captured.clientSecret` is null (not yet wired) or `oauthConfig.clientSecret` is unstubbed.

- [ ] **Step 3: Wire the secret in `startOAuth`**

In `RemotesViewModel.kt`, inside `startOAuth`, after `redirectUri` is computed and before building `PendingAuth`, add:

```kotlin
            // Box needs its client secret in the token exchange; the PKCE three return null.
            val clientSecret = oauthConfig.clientSecret(provider.id)
```

Then add `clientSecret = clientSecret,` to the `PendingAuth(...)` constructor call (alongside `redirectUri` and `remoteName`).

- [ ] **Step 4: Run tests, verify pass**

Run: `./gradlew :feature:remotes:testDebugUnitTest --tests "*RemotesViewModelTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt \
        feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt
git commit -m "Carry the Box client secret through startOAuth"
```

---

## Task 7: Wire Box id + secret into BuildConfig and DI

**Why:** The CI `production` environment already holds `VIRGA_OAUTH_CLIENT_ID_BOX` and `VIRGA_OAUTH_CLIENT_SECRET_BOX`. Surface them as BuildConfig fields and feed them into `OAuthConfig`. No unit test — this is build wiring; verified by a release-variant compile.

**Files:**
- Modify: `app/build.gradle.kts:20-22,86-92`
- Modify: `app/src/main/kotlin/app/lusk/virga/di/OAuthConfigModule.kt`
- Modify: `.github/workflows/release.yml:121-135`

- [ ] **Step 1: Add a secret helper and Box BuildConfig fields**

In `app/build.gradle.kts`, next to the existing `oauthClientId` helper (lines 20-22), add a secret helper:

```kotlin
fun oauthClientSecret(provider: String): String =
    System.getenv("VIRGA_OAUTH_CLIENT_SECRET_${provider.uppercase()}")
        ?: localProps.getProperty("oauthClientSecret.$provider")
        ?: ""
```

In the `buildConfigField` block (after the Dropbox line ~91), add:

```kotlin
        buildConfigField("String", "OAUTH_CLIENT_ID_BOX", "\"${oauthClientId("box")}\"")
        buildConfigField("String", "OAUTH_CLIENT_SECRET_BOX", "\"${oauthClientSecret("box")}\"")
```

- [ ] **Step 2: Wire them into `OAuthConfigModule`**

In `OAuthConfigModule.kt`, add Box to `clientIds` and a `clientSecrets` map:

```kotlin
        clientIds = mapOf(
            OAuthProviders.GoogleDrive.id to BuildConfig.OAUTH_CLIENT_ID_GDRIVE,
            OAuthProviders.OneDrive.id to BuildConfig.OAUTH_CLIENT_ID_ONEDRIVE,
            OAuthProviders.Dropbox.id to BuildConfig.OAUTH_CLIENT_ID_DROPBOX,
            OAuthProviders.Box.id to BuildConfig.OAUTH_CLIENT_ID_BOX,
        ),
        clientSecrets = mapOf(
            OAuthProviders.Box.id to BuildConfig.OAUTH_CLIENT_SECRET_BOX,
        ),
        redirectUris = mapOf(
            OAuthProviders.GoogleDrive.id to
                OAuthConfig.googleAndroidRedirect(BuildConfig.OAUTH_CLIENT_ID_GDRIVE, REDIRECT_URI),
        ),
```

Box uses the default HTTPS App Link redirect (`REDIRECT_URI`), so it needs no `redirectUris` override. Register `https://lusk.app/virga/oauth/callback` as a redirect URI in the Box developer console (verify-on-your-side note for the release step).

- [ ] **Step 3: Pass the Box env into the release build step**

In `.github/workflows/release.yml`, in the "Build FOSS release APKs" step `env:` block (after the Dropbox line ~128), add:

```yaml
          VIRGA_OAUTH_CLIENT_ID_BOX: ${{ secrets.OAUTH_CLIENT_ID_BOX }}
          VIRGA_OAUTH_CLIENT_SECRET_BOX: ${{ secrets.OAUTH_CLIENT_SECRET_BOX }}
```

- [ ] **Step 4: Verify the release variant compiles**

Run: `./gradlew :app:assembleFossRelease -x lint` (or `compileFossReleaseKotlin` for speed)
Expected: BUILD SUCCESSFUL. BuildConfig fields resolve; `OAuthConfigModule` compiles with the new maps.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts \
        app/src/main/kotlin/app/lusk/virga/di/OAuthConfigModule.kt \
        .github/workflows/release.yml
git commit -m "Wire Box OAuth client id + secret into BuildConfig, DI, and release CI"
```

---

## Task 8: Full-options accessor so the credential form shows advanced options

**Why:** `RemotesViewModel.optionsForBackend` filters `!it.advanced`, but `TypedOptionFields` expects the full list — it partitions into normal/advanced itself and only renders the "Show advanced options" expander when the list contains advanced entries. Feeding it the pre-filtered list silently drops every advanced option and hides the expander. Add a full-list accessor and use it in the dialog. Keep the filtered accessor for any other caller.

**Files:**
- Modify: `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt:104-115`
- Modify: `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/AddRemoteDialog.kt:66,128`
- Modify: `feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/AddRemoteScreen.kt:69` and `RemotesScreen.kt:235` (callers that pass `optionsForBackend`)
- Test: `feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `RemotesViewModelTest`. It seeds the provider schema with one normal and one advanced option, then asserts the new accessor returns both. Match how the existing tests seed `providers` — `repository.providers()` is mocked, and `ensureProvidersLoaded()` populates the StateFlow. Use the existing `repository` mock.

```kotlin
@Test fun `allOptionsForBackend returns advanced options too`() = runTest {
    coEvery { repository.providers() } returns listOf(
        RemoteProvider(
            name = "sftp",
            description = "SFTP",
            options = listOf(
                RemoteOption("host", "", "string", true, false, null, emptyList(), advanced = false),
                RemoteOption("ciphers", "", "string", false, false, null, emptyList(), advanced = true),
            ),
        ),
    )
    viewModel.ensureProvidersLoaded()
    advanceUntilIdle()

    val all = viewModel.allOptionsForBackend("sftp")
    assertThat(all?.map { it.name }).containsExactly("host", "ciphers")

    val filtered = viewModel.optionsForBackend("sftp")
    assertThat(filtered?.map { it.name }).containsExactly("host")
}
```

- [ ] **Step 2: Run it, verify failure**

Run: `./gradlew :feature:remotes:testDebugUnitTest --tests "*RemotesViewModelTest"`
Expected: compile error — `allOptionsForBackend` does not exist.

- [ ] **Step 3: Add the accessor**

In `RemotesViewModel.kt`, after `optionsForBackend` (line 115), add:

```kotlin
    /**
     * Returns the FULL [RemoteOption] list (basic + advanced) for [backendType], or
     * null when the schema is not loaded or the type is unknown. Use this for the
     * credential form — [TypedOptionFields] partitions basic/advanced itself and
     * needs the advanced options to render the "Show advanced options" expander.
     */
    fun allOptionsForBackend(backendType: String): List<RemoteOption>? {
        val loaded = _providers.value ?: return null
        if (loaded.isEmpty()) return null
        return loaded.firstOrNull { it.name.equals(backendType, ignoreCase = true) }?.options
    }
```

- [ ] **Step 4: Use it in the dialog**

In `AddRemoteDialog.kt`:
- Change the parameter (line 66) from `optionsForBackend: (String) -> List<RemoteOption>?,` to `allOptionsForBackend: (String) -> List<RemoteOption>?,`.
- Change the `schemaOptions` line (128) to call `allOptionsForBackend(type.trim())`.

In both callers — `AddRemoteScreen.kt:69` and `RemotesScreen.kt:235` — change
`optionsForBackend = viewModel::optionsForBackend,` to
`allOptionsForBackend = viewModel::allOptionsForBackend,`.

- [ ] **Step 5: Run tests, verify pass**

Run: `./gradlew :feature:remotes:testDebugUnitTest --tests "*RemotesViewModelTest"`
Expected: PASS.

- [ ] **Step 6: Verify the feature module compiles (Compose wiring)**

Run: `./gradlew :feature:remotes:compileFossDebugKotlin` (or `:feature:remotes:assembleFossDebug`)
Expected: BUILD SUCCESSFUL — the renamed parameter resolves at both call sites.

- [ ] **Step 7: Commit**

```bash
git add feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesViewModel.kt \
        feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/AddRemoteDialog.kt \
        feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/AddRemoteScreen.kt \
        feature/remotes/src/main/kotlin/app/lusk/virga/feature/remotes/RemotesScreen.kt \
        feature/remotes/src/test/kotlin/app/lusk/virga/feature/remotes/RemotesViewModelTest.kt
git commit -m "Feed the full option list to the credential form so advanced options show"
```

---

## Task 9: `ProviderCatalog` classification layer

**Why:** Phases 2-4 need one place that turns the raw `providers()` schema into "how do I set this up" — credential form, OAuth (bundled vs BYOK), or wrapper — plus picker ordering and the `local` exclusion. This task builds the pure, fully-tested classifier with nothing depending on it yet.

**Files:**
- Create: `core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/ProviderCatalog.kt`
- Create: `core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/ProviderCatalogTest.kt`
- Create: `core/rclone/src/test/resources/config-providers-sample.json`

- [ ] **Step 1: Add the fixture**

Create `core/rclone/src/test/resources/config-providers-sample.json` — a trimmed but real-shaped `config/providers` payload. Include enough backends to exercise every `SetupKind` and the override map.

```json
{
  "providers": [
    { "Name": "drive", "Description": "Google Drive",
      "Options": [
        { "Name": "client_id", "Help": "", "Type": "string", "Advanced": false },
        { "Name": "token", "Help": "", "Type": "string", "Advanced": true } ] },
    { "Name": "box", "Description": "Box",
      "Options": [
        { "Name": "client_id", "Help": "", "Type": "string", "Advanced": false },
        { "Name": "client_secret", "Help": "", "Type": "string", "Advanced": false },
        { "Name": "token", "Help": "", "Type": "string", "Advanced": true } ] },
    { "Name": "pcloud", "Description": "pCloud",
      "Options": [
        { "Name": "client_id", "Help": "", "Type": "string", "Advanced": false },
        { "Name": "token", "Help": "", "Type": "string", "Advanced": true } ] },
    { "Name": "s3", "Description": "Amazon S3 and compatibles",
      "Options": [
        { "Name": "access_key_id", "Help": "", "Type": "string", "Advanced": false },
        { "Name": "secret_access_key", "Help": "", "Type": "string", "IsPassword": true, "Advanced": false } ] },
    { "Name": "sftp", "Description": "SSH/SFTP",
      "Options": [
        { "Name": "host", "Help": "", "Type": "string", "Required": true, "Advanced": false },
        { "Name": "pass", "Help": "", "Type": "string", "IsPassword": true, "Advanced": false } ] },
    { "Name": "crypt", "Description": "Encrypt/Decrypt a remote",
      "Options": [
        { "Name": "remote", "Help": "", "Type": "string", "Required": true, "Advanced": false },
        { "Name": "password", "Help": "", "Type": "string", "IsPassword": true, "Advanced": false } ] },
    { "Name": "union", "Description": "Union merges several remotes",
      "Options": [
        { "Name": "upstreams", "Help": "", "Type": "string", "Required": true, "Advanced": false } ] },
    { "Name": "local", "Description": "Local Disk",
      "Options": [
        { "Name": "nounc", "Help": "", "Type": "bool", "Advanced": true } ] }
  ]
}
```

- [ ] **Step 2: Write the failing tests**

Create `ProviderCatalogTest.kt`. It loads the fixture through the existing `parseProviders` mapper, then asserts classification, ordering, and the `local` exclusion.

```kotlin
package app.lusk.virga.core.rclone

import app.lusk.virga.core.common.model.RemoteProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

class ProviderCatalogTest {

    private fun loadProviders(): List<RemoteProvider> {
        val text = checkNotNull(javaClass.getResourceAsStream("/config-providers-sample.json"))
            .bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(text) as JsonObject
        return parseProviders(root)
    }

    private val catalog by lazy { ProviderCatalog(loadProviders()) }

    @Test fun `credential backends with a password classify as Credential`() {
        assertThat(catalog.setupKind("s3")).isEqualTo(SetupKind.Credential)
        assertThat(catalog.setupKind("sftp")).isEqualTo(SetupKind.Credential)
    }

    @Test fun `OAuth backends are detected by token plus client_id`() {
        assertThat(catalog.setupKind("pcloud")).isEqualTo(SetupKind.OAuth(bundled = false))
    }

    @Test fun `the four allowlisted OAuth backends are bundled`() {
        assertThat(catalog.setupKind("drive")).isEqualTo(SetupKind.OAuth(bundled = true))
        assertThat(catalog.setupKind("box")).isEqualTo(SetupKind.OAuth(bundled = true))
    }

    @Test fun `wrapper backends classify as Wrapper`() {
        assertThat(catalog.setupKind("crypt")).isEqualTo(SetupKind.Wrapper)
        assertThat(catalog.setupKind("union")).isEqualTo(SetupKind.Wrapper)
    }

    @Test fun `local is excluded from the picker`() {
        assertThat(catalog.pickerEntries().map { it.type }).doesNotContain("local")
    }

    @Test fun `popular providers are pinned to the top of the picker in order`() {
        val top = catalog.pickerEntries().take(4).map { it.type }
        assertThat(top).containsExactly("drive", "dropbox", "onedrive", "box").inOrder()
        // (dropbox/onedrive are pinned even though this fixture omits them; absent
        //  pinned entries are simply skipped — see the next test.)
    }

    @Test fun `pinned entries absent from the schema are skipped, not invented`() {
        // The fixture has no dropbox/onedrive backend, so the picker can't list them.
        assertThat(catalog.pickerEntries().map { it.type }).containsNoneOf("dropbox", "onedrive")
    }

    @Test fun `unknown backend classifies as Credential by default`() {
        assertThat(catalog.setupKind("a-future-backend")).isEqualTo(SetupKind.Credential)
    }
}
```

Note the deliberate tension between the two "pinned" tests: the pin list defines a *preferred order*, but only backends actually present in the schema appear. Fix the first test's expectation in Step 4 once the real ordering is implemented (it should read `containsExactly("drive", "box").inOrder()` for this fixture, since dropbox/onedrive are absent). The two tests together lock the "pin order, but never invent absent providers" behavior.

- [ ] **Step 3: Run the tests, verify they fail**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*ProviderCatalogTest"`
Expected: compile error — `ProviderCatalog`, `SetupKind` do not exist.

- [ ] **Step 4: Implement `ProviderCatalog`**

Create `ProviderCatalog.kt`:

```kotlin
package app.lusk.virga.core.rclone

import app.lusk.virga.core.common.model.RemoteProvider

/** How a backend is configured in the Add-remote UI. */
sealed interface SetupKind {
    /** Schema-driven credential form. */
    data object Credential : SetupKind
    /** OAuth backend. [bundled] = Virga ships a first-party client (Custom Tabs + PKCE,
     *  plus client_secret for Box); otherwise BYOK. */
    data class OAuth(val bundled: Boolean) : SetupKind
    /** References other configured remotes rather than credentials. */
    data object Wrapper : SetupKind
}

/** One row in the provider picker. */
data class PickerEntry(val type: String, val description: String)

/**
 * Classifies rclone backends from a loaded `config/providers` schema and orders
 * them for the picker. Pure and synchronous — construct it from
 * [RcloneEngine.providers]. New rclone backends classify automatically; only the
 * two curated lists below ([BUNDLED_OAUTH], [PINNED]) and the fixed [WRAPPERS] set
 * are hand-maintained, and they stay small.
 */
class ProviderCatalog(private val providers: List<RemoteProvider>) {

    private val byType: Map<String, RemoteProvider> =
        providers.associateBy { it.name.lowercase() }

    fun setupKind(type: String): SetupKind {
        val provider = byType[type.lowercase()] ?: return SetupKind.Credential
        val optionNames = provider.options.map { it.name }.toSet()
        return when {
            type.lowercase() in WRAPPERS -> SetupKind.Wrapper
            isOAuth(optionNames) -> SetupKind.OAuth(bundled = type.lowercase() in BUNDLED_OAUTH)
            else -> SetupKind.Credential
        }
    }

    /** Picker rows: pinned providers first (in PINNED order, skipping any the schema
     *  lacks), then every remaining backend except HIDDEN, alphabetically. */
    fun pickerEntries(): List<PickerEntry> {
        val visible = providers
            .filter { it.name.lowercase() !in HIDDEN }
            .associateBy { it.name.lowercase() }
        val pinned = PINNED.mapNotNull { visible[it] }
        val pinnedTypes = pinned.map { it.name.lowercase() }.toSet()
        val rest = visible.values
            .filter { it.name.lowercase() !in pinnedTypes }
            .sortedBy { it.name.lowercase() }
        return (pinned + rest).map { PickerEntry(it.name, it.description) }
    }

    /** An OAuth backend exposes a `token` option together with a `client_id`. */
    private fun isOAuth(optionNames: Set<String>): Boolean =
        "token" in optionNames && "client_id" in optionNames

    companion object {
        /** Backends Virga ships a bundled client for. Allowlist, not a heuristic. */
        val BUNDLED_OAUTH = setOf("drive", "dropbox", "onedrive", "box")

        /** Fixed rclone meta/wrapper backends that reference other remotes. */
        val WRAPPERS = setOf(
            "crypt", "union", "alias", "combine", "chunker", "compress", "cache", "hasher",
        )

        /** Excluded from the picker entirely. */
        val HIDDEN = setOf("local")

        /** Preferred picker order for the most common providers; absent ones are skipped. */
        val PINNED = listOf("drive", "dropbox", "onedrive", "box", "s3", "b2")
    }
}
```

- [ ] **Step 5: Correct the first pinned-order assertion**

The fixture has no `dropbox`/`onedrive` backend, so for this fixture the pinned top is `drive`, `box` (in PINNED order), then `s3`. Update the `popular providers are pinned` test's expectation to:

```kotlin
    @Test fun `popular providers are pinned to the top of the picker in order`() {
        val top = catalog.pickerEntries().map { it.type }
        // PINNED order, skipping dropbox/onedrive/b2 which this fixture omits.
        assertThat(top.take(3)).containsExactly("drive", "box", "s3").inOrder()
    }
```

- [ ] **Step 6: Run the tests, verify they pass**

Run: `./gradlew :core:rclone:testDebugUnitTest --tests "*ProviderCatalogTest"`
Expected: PASS (all classification, ordering, and exclusion tests green).

- [ ] **Step 7: Commit**

```bash
git add core/rclone/src/main/kotlin/app/lusk/virga/core/rclone/ProviderCatalog.kt \
        core/rclone/src/test/kotlin/app/lusk/virga/core/rclone/ProviderCatalogTest.kt \
        core/rclone/src/test/resources/config-providers-sample.json
git commit -m "Add ProviderCatalog classification layer over config/providers"
```

---

## Task 10: Full-suite gate + lint

- [ ] **Step 1: Run the whole unit-test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL — every module's unit tests pass (this is the same gate `release.yml` runs).

- [ ] **Step 2: Run lint on the foss debug variant**

Run: `./gradlew lintFossDebug`
Expected: no new errors. (Pre-existing baseline warnings are fine; a new error must be fixed before proceeding.)

- [ ] **Step 3: Final commit if lint auto-touched anything**

```bash
git add -A
git commit -m "Phase 1: pass full unit-test suite and lint" || echo "nothing to commit"
```

---

## Self-review (completed during planning)

**Spec coverage for Phase 1's slice:**
- "Box added to the bundled set; needs a client_secret" → Tasks 3-7.
- "createRemote obscures only flagged sensitive keys, never the OAuth token" → Tasks 1-2.
- "optionsForBackend filters advanced; the form needs the full list" → Task 8.
- "ProviderCatalog classification (Credential / OAuth bundled-vs-BYOK / Wrapper), pinned ordering, local excluded, allowlist not heuristic" → Task 9.
- Picker UI, credential form rendering, wrapper sub-flow, BYOK rclone-delegated OAuth, connectivity test → **deferred to Phases 2-4** (called out in the Scope note; not regressions).

**Type/signature consistency:** `createRemote(name, type, params, sensitiveKeys = emptySet())` is used identically in the interface (Task 1), impl (Task 1), repository (Task 2), and the repository test matcher (Task 2). `OAuthProvider.requiresClientSecret`, `PendingAuth.clientSecret`, and `OAuthConfig.clientSecret(id)` are introduced before any task consumes them (Tasks 3-5 before Task 6). `allOptionsForBackend` is defined (Task 8) and consumed in the same task. `SetupKind`/`ProviderCatalog` are self-contained in Task 9.

**Placeholder scan:** no TBD/TODO; every code step shows full code. The one intentional mid-task correction (Task 9 Steps 2/5) is explicit about why and what the final assertion is.

---

## Verify-on-your-side (out of band, not code)

- The release-signing SHA-1 must be registered on the Box app's OAuth config, and `https://lusk.app/virga/oauth/callback` must be an allowed Box redirect URI, before bundled Box OAuth works on a release build.
- Confirm `OAUTH_CLIENT_ID_BOX` / `OAUTH_CLIENT_SECRET_BOX` exist in the GitHub `production` environment (they were added earlier in the session).
