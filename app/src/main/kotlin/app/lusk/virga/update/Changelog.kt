package app.lusk.virga.update

import android.content.res.Resources
import app.lusk.virga.R

/** A single release's version name and human-readable notes. */
data class ReleaseNotes(val versionName: String, val notes: List<String>)

/**
 * Builds the in-app changelog from translatable string resources. Newest first.
 * Add a new [VersionEntry] here and the matching strings/arrays in strings.xml
 * when shipping a release — no other Kotlin changes needed.
 */
fun releaseNotes(resources: Resources): List<ReleaseNotes> =
    VERSION_ENTRIES.map { entry ->
        ReleaseNotes(
            versionName = resources.getString(entry.versionNameRes),
            notes = resources.getStringArray(entry.notesArrayRes).toList(),
        )
    }

/** Returns the [ReleaseNotes] for [versionName], or null if not found. */
fun releaseNotesFor(versionName: String, resources: Resources): ReleaseNotes? =
    releaseNotes(resources).firstOrNull { it.versionName == versionName }

/** Pairs a version-name string resource with its notes string-array resource. */
private data class VersionEntry(val versionNameRes: Int, val notesArrayRes: Int)

/** Newest-first registry. Add entries here when a new release ships. */
private val VERSION_ENTRIES: List<VersionEntry> = listOf(
    VersionEntry(R.string.release_version_0_3_0, R.array.release_notes_0_3_0),
    VersionEntry(R.string.release_version_0_2_0, R.array.release_notes_0_2_0),
    VersionEntry(R.string.release_version_0_1_0, R.array.release_notes_0_1_0),
)
