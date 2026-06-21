package app.lusk.virga.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores per-provider, user-supplied OAuth **client IDs and client secrets**
 * ("bring your own keys").
 *
 * The built-in client IDs are shared across every Virga install, so they share a
 * single OAuth client's rate-limit/quota and (for Google) the unverified-app user
 * cap. Power users avoid that by registering their own OAuth client and entering
 * its client ID here; it takes precedence over the built-in default.
 *
 * **Client IDs** are stored for all BYO providers (prefix [ID_PREFIX]). Most
 * providers use PKCE public clients, so no client secret is needed — the user
 * just creates an "Android"/installed-app OAuth client.
 *
 * **Client secrets** (prefix [SECRET_PREFIX]) are the user's OWN secret for
 * a client they registered with a provider that requires one (e.g. Google when
 * routing through the rclone daemon flow). Stored app-private in this DataStore.
 *
 * SECURITY POSTURE (BYO-secret power users only): the secret is app-private and is
 * **excluded from all backup/transfer** — `files/datastore/` is excluded in both
 * data_extraction_rules.xml and backup_rules.xml — so it never leaves the device via
 * cloud backup, device transfer, or ADB backup. The only exposure path is a rooted
 * device with filesystem access (outside the standard threat model), and the secret
 * alone cannot mint tokens (PKCE + redirect are still required). At-rest encryption
 * (a Keystore-backed value cipher over this DataStore) is tracked post-release
 * hardening — deferred because androidx.security-crypto is deprecated, so the
 * replacement should be a deliberate direct-Keystore design, not a rushed swap.
 * Never log, toast, or echo secrets.
 */
@Singleton
class OAuthKeyStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    // Built from the same PREFIX the read path (clientIds) strips, so the write and
    // read sides can't drift if the prefix is ever renamed.
    private fun idKey(providerId: String) = stringPreferencesKey(ID_PREFIX + providerId)
    private fun secretKey(providerId: String) = stringPreferencesKey(SECRET_PREFIX + providerId)

    // Degrade a corrupt/unreadable prefs file to empty instead of throwing — mirrors
    // PreferencesRepository, so a bad DataStore can't crash the OAuth flow.
    private val data: Flow<Preferences> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    /** Map of providerId → user client ID, for every provider that has one set. */
    val clientIds: Flow<Map<String, String>> = data.map { prefs ->
        prefs.asMap().entries
            .mapNotNull { (k, v) ->
                val name = k.name
                if (name.startsWith(ID_PREFIX) && v is String && v.isNotBlank()) {
                    name.removePrefix(ID_PREFIX) to v
                } else null
            }
            .toMap()
    }

    /** Map of providerId → user client secret, for every provider that has one set. */
    val clientSecrets: Flow<Map<String, String>> = data.map { prefs ->
        prefs.asMap().entries
            .mapNotNull { (k, v) ->
                val name = k.name
                if (name.startsWith(SECRET_PREFIX) && v is String && v.isNotBlank()) {
                    name.removePrefix(SECRET_PREFIX) to v
                } else null
            }
            .toMap()
    }

    /** The user-supplied client ID for [providerId], or null if none/blank. */
    suspend fun clientId(providerId: String): String? =
        data.first()[idKey(providerId)]?.takeIf { it.isNotBlank() }

    /** The user-supplied client secret for [providerId], or null if none/blank. */
    suspend fun clientSecret(providerId: String): String? =
        data.first()[secretKey(providerId)]?.takeIf { it.isNotBlank() }

    suspend fun setClientId(providerId: String, clientId: String) {
        val trimmed = clientId.trim()
        dataStore.edit { prefs ->
            if (trimmed.isEmpty()) prefs.remove(idKey(providerId)) else prefs[idKey(providerId)] = trimmed
        }
    }

    /** Stores (or clears, when [secret] is blank after trimming) the user's own client secret. */
    suspend fun setClientSecret(providerId: String, secret: String) {
        val trimmed = secret.trim()
        dataStore.edit { prefs ->
            if (trimmed.isEmpty()) prefs.remove(secretKey(providerId)) else prefs[secretKey(providerId)] = trimmed
        }
    }

    suspend fun clearClientId(providerId: String) {
        dataStore.edit { it.remove(idKey(providerId)) }
    }

    suspend fun clearClientSecret(providerId: String) {
        dataStore.edit { it.remove(secretKey(providerId)) }
    }

    private companion object {
        const val ID_PREFIX = "oauth_client_id_"
        const val SECRET_PREFIX = "oauth_client_secret_"
    }
}
