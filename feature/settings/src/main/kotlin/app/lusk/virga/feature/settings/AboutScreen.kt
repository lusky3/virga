package app.lusk.virga.feature.settings

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.lusk.virga.core.designsystem.component.SettingsLinkRow
import app.lusk.virga.core.designsystem.theme.VirgaSpacing
import kotlinx.coroutines.launch

/**
 * Standalone About screen. Holds the app identity, the changelog + acknowledgements
 * entry points, the external reference links, and a quiet build-details block.
 *
 * [distribution] and [rcloneVersion] come from the app module's BuildConfig (passed
 * in by the nav host) so this feature module stays BuildConfig-free.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onViewChangelog: () -> Unit,
    distribution: String,
    rcloneVersion: String,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noBrowserMsg = stringResource(R.string.settings_snack_no_browser)

    var showHowItWorksDialog by remember { mutableStateOf(false) }
    var showLicensesSheet by remember { mutableStateOf(false) }

    // Opens an external URL, falling back to a snackbar when no browser is present.
    val openUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }.onFailure {
            scope.launch { snackbarHostState.showSnackbar(noBrowserMsg) }
        }
    }

    val (versionName, versionCode) = rememberAppVersion()

    if (showHowItWorksDialog) {
        HowVirgaWorksDialog(onDismiss = { showHowItWorksDialog = false })
    }
    if (showLicensesSheet) {
        AcknowledgementsSheet(onOpenUrl = openUrl, onDismiss = { showLicensesSheet = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.about_cd_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = VirgaSpacing.md)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(VirgaSpacing.md),
        ) {
            AboutIdentity(versionName = versionName, versionCode = versionCode)
            HorizontalDivider()
            AboutInfoLinks(
                onViewChangelog = onViewChangelog,
                onHowItWorks = { showHowItWorksDialog = true },
                onLicenses = { showLicensesSheet = true },
            )
            HorizontalDivider()
            AboutReferenceLinks(openUrl = openUrl)
            HorizontalDivider()
            AboutBuildDetails(
                distribution = distribution,
                versionName = versionName,
                versionCode = versionCode,
                rcloneVersion = rcloneVersion,
            )
            // Bottom spacing so content clears the nav bar.
            Spacer(Modifier.height(VirgaSpacing.lg))
        }
    }
}

/** App version name + code, read once from the package manager. */
@Composable
private fun rememberAppVersion(): Pair<String, Long> {
    val context = LocalContext.current
    val packageInfo: PackageInfo? = remember {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        }.getOrNull()
    }
    val name = packageInfo?.versionName ?: "—"
    val code = packageInfo?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else {
            @Suppress("DEPRECATION") it.versionCode.toLong()
        }
    } ?: 0L
    return name to code
}

/** Centered identity block: mark, name, version, tagline. */
@Composable
private fun AboutIdentity(versionName: String, versionCode: Long) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = VirgaSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(VirgaSpacing.xs),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_virga_mark),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
        )
        Text(
            stringResource(R.string.about_app_name),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.about_version, versionName, versionCode),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Changelog / how-it-works / licenses entry points. */
@Composable
private fun AboutInfoLinks(
    onViewChangelog: () -> Unit,
    onHowItWorks: () -> Unit,
    onLicenses: () -> Unit,
) {
    SettingsLinkRow(
        label = stringResource(R.string.settings_item_whats_new),
        onClick = onViewChangelog,
        leadingIcon = Icons.Outlined.NewReleases,
    )
    SettingsLinkRow(
        label = stringResource(R.string.settings_item_how_virga_works),
        onClick = onHowItWorks,
        leadingIcon = Icons.Outlined.Lightbulb,
    )
    SettingsLinkRow(
        label = stringResource(R.string.settings_item_licenses),
        onClick = onLicenses,
        leadingIcon = Icons.Outlined.Gavel,
    )
}

/** External references: Virga-owned first (site → policy → project → issues), then rclone docs. */
@Composable
private fun AboutReferenceLinks(openUrl: (String) -> Unit) {
    val opensExternally = stringResource(R.string.settings_opens_externally)
    SettingsLinkRow(
        label = stringResource(R.string.settings_item_website),
        onClick = { openUrl("https://virga.lusk.app") },
        leadingIcon = Icons.Outlined.Public,
        opensExternally = true,
        externalLinkDescription = opensExternally,
    )
    SettingsLinkRow(
        label = stringResource(R.string.settings_item_privacy_policy),
        onClick = { openUrl("https://virga.lusk.app/privacy") },
        leadingIcon = Icons.Outlined.Shield,
        opensExternally = true,
        externalLinkDescription = opensExternally,
    )
    SettingsLinkRow(
        label = stringResource(R.string.settings_item_source_code),
        onClick = { openUrl("https://github.com/lusky3/virga") },
        leadingIcon = Icons.Outlined.Code,
        opensExternally = true,
        externalLinkDescription = opensExternally,
    )
    SettingsLinkRow(
        label = stringResource(R.string.settings_item_report_bug),
        onClick = { openUrl("https://github.com/lusky3/virga/issues") },
        leadingIcon = Icons.Outlined.BugReport,
        opensExternally = true,
        externalLinkDescription = opensExternally,
    )
    SettingsLinkRow(
        label = stringResource(R.string.settings_item_rclone_docs),
        onClick = { openUrl("https://rclone.org/docs/") },
        leadingIcon = Icons.Outlined.Description,
        opensExternally = true,
        externalLinkDescription = opensExternally,
    )
}

/** Quiet build-details block: distribution, version, rclone version. */
@Composable
private fun AboutBuildDetails(
    distribution: String,
    versionName: String,
    versionCode: Long,
    rcloneVersion: String,
) {
    SectionTitle(stringResource(R.string.about_section_build_details))
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.xs)) {
        val muted = MaterialTheme.typography.bodySmall
        val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
        Text(stringResource(R.string.about_build_distribution, distribution), style = muted, color = mutedColor)
        Text(stringResource(R.string.about_build_version, versionName, versionCode), style = muted, color = mutedColor)
        Text(stringResource(R.string.about_build_rclone, rcloneVersion), style = muted, color = mutedColor)
    }
}

@Composable
private fun HowVirgaWorksDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_how_virga_works_title)) },
        text = { Text(stringResource(R.string.settings_how_virga_works_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_dialog_ok))
            }
        },
    )
}

/** A bundled or linked open-source dependency, shown in the acknowledgements modal. */
private data class OssLibrary(val name: String, val license: String, val url: String)

/**
 * Curated from Virga's actual *runtime* dependencies (test/build-only tooling is
 * intentionally excluded). Keep in sync with the version catalog when shipping
 * dependencies change. Names/licenses are proper nouns, so they live in code
 * rather than strings.xml.
 */
private const val APACHE_2 = "Apache License 2.0"

private val AcknowledgedLibraries: List<OssLibrary> = listOf(
    OssLibrary("rclone", "MIT License", "https://rclone.org/"),
    OssLibrary("Manrope (display font)", "SIL Open Font License 1.1", "https://fonts.google.com/specimen/Manrope"),
    OssLibrary("AndroidX & Jetpack libraries", APACHE_2, "https://developer.android.com/jetpack"),
    OssLibrary("Jetpack Compose", APACHE_2, "https://developer.android.com/jetpack/compose"),
    OssLibrary("Material Components for Android", APACHE_2, "https://github.com/material-components/material-components-android"),
    OssLibrary("Dagger & Hilt", APACHE_2, "https://dagger.dev/hilt/"),
    OssLibrary("Kotlin", APACHE_2, "https://kotlinlang.org/"),
    OssLibrary("Kotlin Coroutines", APACHE_2, "https://github.com/Kotlin/kotlinx.coroutines"),
    OssLibrary("kotlinx.serialization", APACHE_2, "https://github.com/Kotlin/kotlinx.serialization"),
    OssLibrary("OkHttp", APACHE_2, "https://square.github.io/okhttp/"),
    OssLibrary("Bcrypt (favre)", APACHE_2, "https://github.com/patrickfav/bcrypt"),
)

/**
 * Open-source acknowledgements. A [ModalBottomSheet] (not an AlertDialog) because
 * BRAND §11 reserves dialogs for short blocking confirms and routes longer,
 * scrolling content to a sheet. Each library row is a 48dp tappable link that
 * opens its homepage, with an explicit accessibility description and an
 * external-link glyph (BRAND §6, §14).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AcknowledgementsSheet(onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    val opensExternally = stringResource(R.string.settings_opens_externally)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = VirgaSpacing.lg)
                .padding(bottom = VirgaSpacing.lg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
        ) {
            Text(
                stringResource(R.string.settings_licenses_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .semantics { heading() }
                    .padding(bottom = VirgaSpacing.xs),
            )
            Text(
                stringResource(R.string.settings_licenses_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AcknowledgedLibraries.forEach { lib ->
                OssLibraryRow(lib = lib, opensExternally = opensExternally, onOpenUrl = onOpenUrl)
            }
        }
    }
}

/** A single tappable acknowledgements row: name + license, opens the library homepage. */
@Composable
private fun OssLibraryRow(lib: OssLibrary, opensExternally: String, onOpenUrl: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onOpenUrl(lib.url) }
            .defaultMinSize(minHeight = 48.dp)
            .semantics { contentDescription = "${lib.name}, ${lib.license}. $opensExternally" }
            .padding(vertical = VirgaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(lib.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                lib.license,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null, // the row carries the description
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
