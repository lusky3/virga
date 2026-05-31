package app.lusk.virga.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores per-provider, user-supplied OAuth **client IDs** ("bring your own keys").
 *
 * The built-in client IDs are shared across every Virga install, so they share a
 * single OAuth client's rate-limit/quota and (for Google) the unverified-app user
 * cap. Power users avoid that by registering their own OAuth client and entering
 * its client ID here; it takes precedence over the built-in default.
 *
 * Only the client ID is stored. Virga authenticates with PKCE (public clients),
 * so no client secret is required for the supported providers — the user just
 * creates an "Android"/installed-app OAuth client.
 */
@Singleton
class OAuthKeyStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private fun key(providerId: String) = stringPreferencesKey("oauth_client_id_$providerId")

    /** Map of providerId → user client ID, for every provider that has one set. */
    val clientIds: Flow<Map<String, String>> = dataStore.data.map { prefs ->
        prefs.asMap().entries
            .mapNotNull { (k, v) ->
                val name = k.name
                if (name.startsWith(PREFIX) && v is String && v.isNotBlank()) {
                    name.removePrefix(PREFIX) to v
                } else null
            }
            .toMap()
    }

    /** The user-supplied client ID for [providerId], or null if none/blank. */
    suspend fun clientId(providerId: String): String? =
        dataStore.data.first()[key(providerId)]?.takeIf { it.isNotBlank() }

    suspend fun setClientId(providerId: String, clientId: String) {
        val trimmed = clientId.trim()
        dataStore.edit { prefs ->
            if (trimmed.isEmpty()) prefs.remove(key(providerId)) else prefs[key(providerId)] = trimmed
        }
    }

    suspend fun clearClientId(providerId: String) {
        dataStore.edit { it.remove(key(providerId)) }
    }

    private companion object {
        const val PREFIX = "oauth_client_id_"
    }
}
