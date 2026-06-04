package app.lusk.virga.core.rclone

import app.lusk.virga.core.common.model.RemoteProvider

/** How a backend is configured in the Add-remote UI. */
sealed interface SetupKind {
    data object Credential : SetupKind
    data class OAuth(val bundled: Boolean) : SetupKind
    data object Wrapper : SetupKind
}

data class PickerEntry(val type: String, val description: String)

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

    private fun isOAuth(optionNames: Set<String>): Boolean =
        "token" in optionNames && "client_id" in optionNames

    companion object {
        val BUNDLED_OAUTH = setOf("drive", "dropbox", "onedrive", "box")
        val WRAPPERS = setOf(
            "crypt", "union", "alias", "combine", "chunker", "compress", "cache", "hasher",
        )
        val HIDDEN = setOf("local")
        val PINNED = listOf("drive", "dropbox", "onedrive", "box", "s3", "b2")
    }
}
