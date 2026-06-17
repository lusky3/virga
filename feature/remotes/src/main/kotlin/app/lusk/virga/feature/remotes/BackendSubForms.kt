package app.lusk.virga.feature.remotes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

// ── rclone option keys ──────────────────────────────────────────────────────
private const val KEY_PROVIDER = "provider"
private const val KEY_VENDOR = "vendor"
private const val KEY_PEM = "key_pem"

// ── S3 provider tokens (rclone canonical values) ────────────────────────────
// NOTE: Backblaze B2 is a separate rclone backend (type=b2), NOT an S3 provider.
// It is intentionally absent — writing provider=Backblaze on an s3 remote is invalid.
private const val S3_AWS = "AWS"
private const val S3_WASABI = "Wasabi"
private const val S3_CLOUDFLARE = "Cloudflare"
private const val S3_MINIO = "Minio"
private const val S3_OTHER = "Other"

// ── WebDAV vendor tokens (rclone canonical values) ──────────────────────────
private const val WEBDAV_NEXTCLOUD = "nextcloud"
private const val WEBDAV_OWNCLOUD = "owncloud"
private const val WEBDAV_SHAREPOINT = "sharepoint"
private const val WEBDAV_OTHER = "other"

/**
 * Identifies which backend-specific sub-form to render, or signals that none
 * is defined. Pure value — no Compose dependency.
 */
internal enum class BackendSubFormKind { S3, SFTP, WEBDAV }

/**
 * Returns the [BackendSubFormKind] for a given rclone backend [type], or null
 * when no sub-form is registered. Comparison is case-insensitive.
 */
internal fun backendSubFormType(type: String): BackendSubFormKind? = when (type.lowercase()) {
    "s3" -> BackendSubFormKind.S3
    "sftp" -> BackendSubFormKind.SFTP
    "webdav" -> BackendSubFormKind.WEBDAV
    else -> null
}

/**
 * Renders the backend-specific sub-form section above the generic option list,
 * or nothing if no sub-form is registered for [type].
 *
 * Each sub-form writes its selections directly into [values] (the same map the
 * generic fields use), so there is no separate state to reconcile.
 */
@Composable
internal fun BackendSubForm(
    type: String,
    values: MutableMap<String, String>,
) {
    val kind = backendSubFormType(type) ?: return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
    ) {
        Text(
            stringResource(R.string.remotes_subform_section_header),
            style = MaterialTheme.typography.labelLarge,
        )
        when (kind) {
            BackendSubFormKind.S3 -> S3SubForm(values)
            BackendSubFormKind.SFTP -> SftpSubForm(values)
            BackendSubFormKind.WEBDAV -> WebDavSubForm(values)
        }
        HorizontalDivider()
    }
}

// ── S3 sub-form ─────────────────────────────────────────────────────────────

private data class S3ProviderEntry(val token: String, val labelRes: Int)

private val S3_PROVIDERS = listOf(
    S3ProviderEntry(S3_AWS, R.string.remotes_s3_provider_aws),
    S3ProviderEntry(S3_WASABI, R.string.remotes_s3_provider_wasabi),
    S3ProviderEntry(S3_CLOUDFLARE, R.string.remotes_s3_provider_cloudflare),
    S3ProviderEntry(S3_MINIO, R.string.remotes_s3_provider_minio),
    S3ProviderEntry(S3_OTHER, R.string.remotes_s3_provider_other),
)

/**
 * Quick-pick S3 provider preset. Writes [KEY_PROVIDER] into [values] as a
 * pre-fill convenience; the generic schema field for provider still renders
 * below and can override the selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun S3SubForm(values: MutableMap<String, String>) {
    var expanded by remember { mutableStateOf(false) }
    val selected = values[KEY_PROVIDER].orEmpty()
    val selectedLabel = S3_PROVIDERS.firstOrNull { it.token == selected }
        ?.let { stringResource(it.labelRes) }
        ?: selected.ifEmpty { stringResource(R.string.remotes_s3_provider_prompt) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        androidx.compose.material3.OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.remotes_s3_provider_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            S3_PROVIDERS.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(stringResource(entry.labelRes)) },
                    onClick = {
                        values[KEY_PROVIDER] = entry.token
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

// ── SFTP sub-form ────────────────────────────────────────────────────────────

/**
 * Parameters for [SftpSubForm] — groups fields to stay within the 6-param limit.
 */
private data class SftpSubFormState(
    val keyStatus: String,
    val keyError: String?,
)

/**
 * SFTP "Import private key from file" button. Reads the picked file inline as
 * UTF-8 text and stores it in [KEY_PEM]. A content:// path is NOT usable by
 * rclone — the inline PEM is the correct approach.
 *
 * The key content is NEVER displayed, logged, or stored in rememberSaveable.
 */
@Composable
private fun SftpSubForm(values: MutableMap<String, String>) {
    val context = LocalContext.current
    var keyStatus by remember { mutableStateOf("") }
    var keyError by remember { mutableStateOf<String?>(null) }

    val keyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?.trim()
                ?: error("null stream")
        }
        result.fold(
            onSuccess = { pem ->
                values[KEY_PEM] = pem
                keyError = null
                keyStatus = context.getString(R.string.remotes_sftp_key_loaded, pem.length)
            },
            onFailure = {
                keyError = context.getString(R.string.remotes_sftp_key_error)
                keyStatus = ""
            },
        )
    }

    Button(
        onClick = { keyLauncher.launch(arrayOf("*/*")) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.remotes_sftp_import_key))
    }

    val status = SftpSubFormState(keyStatus, keyError)
    SftpKeyStatusText(status)
}

@Composable
private fun SftpKeyStatusText(state: SftpSubFormState) {
    when {
        state.keyError != null -> Text(
            state.keyError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        state.keyStatus.isNotEmpty() -> Text(
            state.keyStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── WebDAV sub-form ──────────────────────────────────────────────────────────

private data class WebDavVendorEntry(val token: String, val labelRes: Int)

private val WEBDAV_VENDORS = listOf(
    WebDavVendorEntry(WEBDAV_NEXTCLOUD, R.string.remotes_webdav_vendor_nextcloud),
    WebDavVendorEntry(WEBDAV_OWNCLOUD, R.string.remotes_webdav_vendor_owncloud),
    WebDavVendorEntry(WEBDAV_SHAREPOINT, R.string.remotes_webdav_vendor_sharepoint),
    WebDavVendorEntry(WEBDAV_OTHER, R.string.remotes_webdav_vendor_other),
)

/**
 * WebDAV vendor preset. Writes [KEY_VENDOR] into [values] as a pre-fill
 * convenience. The generic schema field for vendor still renders below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebDavSubForm(values: MutableMap<String, String>) {
    var expanded by remember { mutableStateOf(false) }
    val selected = values[KEY_VENDOR].orEmpty()
    val selectedLabel = WEBDAV_VENDORS.firstOrNull { it.token == selected }
        ?.let { stringResource(it.labelRes) }
        ?: selected.ifEmpty { stringResource(R.string.remotes_webdav_vendor_prompt) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        androidx.compose.material3.OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.remotes_webdav_vendor_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            WEBDAV_VENDORS.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(stringResource(entry.labelRes)) },
                    onClick = {
                        values[KEY_VENDOR] = entry.token
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
