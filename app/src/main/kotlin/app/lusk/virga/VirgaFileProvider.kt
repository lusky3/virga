package app.lusk.virga

import androidx.core.content.FileProvider

/**
 * App-specific [FileProvider] subclass so the manifest `<provider>` carries a
 * class name unique to Virga.
 *
 * The manifest merger matches `<provider>` entries by `android:name`; a bare
 * `androidx.core.content.FileProvider` would conflict with any dependency that
 * declares its own provider under that same class name but a different
 * `android:authorities`. Subclassing sidesteps that collision entirely while
 * inheriting all behaviour from the platform FileProvider (paths still come from
 * the `android.support.FILE_PROVIDER_PATHS` meta-data).
 */
class VirgaFileProvider : FileProvider()
