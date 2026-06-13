package app.lusk.virga.core.rclone.oauth

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * UI-M3: tests the single-use, TTL-bounded, constant-time-compared OAuth pending
 * store. Pure JVM (AtomicReference + MessageDigest) — no Android runtime needed.
 */
class OAuthStoreTest {

    private fun pending(state: String) = OAuthTokenExchanger.PendingAuth(
        provider = OAuthProviders.GoogleDrive,
        state = state,
        verifier = "verifier",
        clientId = "client",
        redirectUri = "https://lusk.app/virga/oauth/callback",
        remoteName = "remote",
    )

    @Test
    fun `consume returns the pending auth for a matching state`() {
        val store = OAuthStore()
        store.startPending(pending("state-1"))
        assertThat(store.consume("state-1")).isNotNull()
    }

    @Test
    fun `consume is single-use - second consume returns null`() {
        val store = OAuthStore()
        store.startPending(pending("state-1"))
        assertThat(store.consume("state-1")).isNotNull()
        // CAS clear means the second consume finds nothing.
        assertThat(store.consume("state-1")).isNull()
    }

    @Test
    fun `consume rejects a mismatched state without clearing the genuine pending auth`() {
        val store = OAuthStore()
        store.startPending(pending("state-1"))
        assertThat(store.consume("wrong-state")).isNull()
        // The real pending auth survives the mismatched attempt.
        assertThat(store.consume("state-1")).isNotNull()
    }

    @Test
    fun `a freshly registered auth is within the TTL and consumable`() {
        // OAuthStore reads System.currentTimeMillis() directly (no injectable clock),
        // so true TTL expiry can't be exercised here without a clock seam. We assert
        // the within-window branch: a just-registered auth is not treated as expired.
        // (The expiry branch itself is exercised by the daemon/integration tests.)
        val store = OAuthStore()
        store.startPending(pending("fresh"))
        assertThat(store.consume("fresh")).isNotNull()
    }

    @Test
    fun `consume on an empty store returns null`() {
        val store = OAuthStore()
        assertThat(store.consume("anything")).isNull()
    }

    @Test
    fun `clear removes the pending auth`() {
        val store = OAuthStore()
        store.startPending(pending("state-1"))
        store.clear()
        assertThat(store.consume("state-1")).isNull()
    }

    @Test
    fun `startPending replaces a prior pending auth`() {
        val store = OAuthStore()
        store.startPending(pending("old"))
        store.startPending(pending("new"))
        // The old state is no longer the live pending auth.
        assertThat(store.consume("old")).isNull()
        assertThat(store.consume("new")).isNotNull()
    }

    @Test
    fun `emit then clearResult round-trips the result flow`() {
        val store = OAuthStore()
        assertThat(store.results.value).isNull()
        store.emit(OAuthResult.Success(state = "s", code = "c"))
        assertThat(store.results.value).isInstanceOf(OAuthResult.Success::class.java)
        store.clearResult()
        assertThat(store.results.value).isNull()
    }
}
