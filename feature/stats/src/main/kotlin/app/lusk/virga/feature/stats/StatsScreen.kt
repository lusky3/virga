package app.lusk.virga.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lusk.virga.core.common.model.LifetimeStats
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.designsystem.component.EmptyState
import app.lusk.virga.core.designsystem.component.VirgaCard
import app.lusk.virga.core.designsystem.theme.VirgaGradients
import app.lusk.virga.core.designsystem.theme.VirgaSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        ResetConfirmDialog(
            onConfirm = {
                viewModel.resetStats()
                showResetDialog = false
            },
            onDismiss = { showResetDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.stats_cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.totalRuns == 0L) {
            EmptyState(
                title = stringResource(R.string.stats_empty_title),
                body = stringResource(R.string.stats_empty_body),
                icon = Icons.Outlined.BarChart,
                modifier = Modifier.padding(padding),
            )
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = VirgaSpacing.md)
                    .padding(bottom = VirgaSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(VirgaSpacing.md),
            ) {
                Spacer(Modifier.height(VirgaSpacing.sm))
                HeroCard(state)
                SectionLabel(stringResource(R.string.stats_section_transfer))
                TransferDirectionRow(state)
                SectionLabel(stringResource(R.string.stats_section_activity))
                TasksCard(state)
                FilesCard(state)
                TimeCard(state)
                SpeedCard(state)
                FunCard(state)
                TextButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        stringResource(R.string.stats_btn_reset),
                        // Destructive action → error track per §13 (was warning).
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Hero
// ---------------------------------------------------------------------------

@Composable
private fun HeroCard(state: LifetimeStats) {
    val gradient = VirgaGradients.hero()
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(gradient)
            // Scrim over the gradient so text clears WCAG-AA even on the lighter
            // teal end (white is only ~2.9:1 on bare teal). BRAND §4.5/§4.7.
            .background(VirgaGradients.heroScrim)
            .padding(VirgaSpacing.lg),
    ) {
        Column {
            Text(
                text = formatFileSize(state.totalBytesTransferred),
                style = MaterialTheme.typography.displaySmall,
                color = VirgaGradients.onHero,
            )
            Text(
                text = stringResource(R.string.stats_hero_label),
                style = MaterialTheme.typography.bodyMedium,
                color = VirgaGradients.onHero,
            )
            val sinceText = state.firstSyncEpochMs?.let { epochMs ->
                val month = Instant.ofEpochMilli(epochMs)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                stringResource(R.string.stats_hero_since, month)
            }
            if (sinceText != null) {
                Spacer(Modifier.height(VirgaSpacing.xs))
                Text(
                    text = sinceText,
                    style = MaterialTheme.typography.labelMedium,
                    color = VirgaGradients.onHero,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Transfer direction row
// ---------------------------------------------------------------------------

@Composable
private fun TransferDirectionRow(state: LifetimeStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(VirgaSpacing.sm),
    ) {
        // titleLarge (not headlineMedium) so "11.8 GB" fits one line in a narrow
        // 3-up column on a 360dp phone.
        val compact = MaterialTheme.typography.titleLarge
        StatCard(
            number = formatFileSize(state.bytesUploaded),
            label = stringResource(R.string.stats_uploaded),
            modifier = Modifier.weight(1f),
            numberStyle = compact,
        )
        StatCard(
            number = formatFileSize(state.bytesDownloaded),
            label = stringResource(R.string.stats_downloaded),
            modifier = Modifier.weight(1f),
            numberStyle = compact,
        )
        StatCard(
            number = formatFileSize(state.bytesTwoWay),
            label = stringResource(R.string.stats_two_way),
            modifier = Modifier.weight(1f),
            numberStyle = compact,
        )
    }
}

// ---------------------------------------------------------------------------
// Activity cards
// ---------------------------------------------------------------------------

@Composable
private fun TasksCard(state: LifetimeStats) {
    val successRate = if (state.totalRuns > 0) {
        (state.successfulRuns * 100L / state.totalRuns).toInt()
    } else {
        0
    }
    VirgaCard {
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(
                text = state.totalRuns.toString(),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.stats_tasks_run),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(VirgaSpacing.xs))
            Text(
                text = stringResource(
                    R.string.stats_tasks_detail,
                    state.successfulRuns.toInt(),
                    state.failedRuns.toInt(),
                    successRate,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilesCard(state: LifetimeStats) {
    StatCard(
        number = state.totalFilesTransferred.toString(),
        label = stringResource(R.string.stats_files_synced),
    )
}

@Composable
private fun TimeCard(state: LifetimeStats) {
    StatCard(
        number = formatDuration(state.totalSyncMillis),
        label = stringResource(R.string.stats_time_syncing),
    )
}

@Composable
private fun SpeedCard(state: LifetimeStats) {
    val avgSpeed = if (state.totalSyncMillis > 0) {
        val seconds = state.totalSyncMillis / 1000L
        if (seconds > 0) formatFileSize(state.totalBytesTransferred / seconds) + "/s" else "—"
    } else {
        "—"
    }
    StatCard(
        number = avgSpeed,
        label = stringResource(R.string.stats_avg_speed),
    )
}

// ---------------------------------------------------------------------------
// Fun strip
// ---------------------------------------------------------------------------

private const val PHOTO_BYTES = 3_500_000L          // ≈ 3.5 MB
private const val MUSIC_HOUR_BYTES = 60_000_000L    // ≈ 60 MB/hour
private const val BLURAY_BYTES = 25_000_000_000L    // ≈ 25 GB

@Composable
private fun FunCard(state: LifetimeStats) {
    val total = state.totalBytesTransferred
    VirgaCard {
        Text(
            text = stringResource(R.string.stats_fun_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(VirgaSpacing.sm))

        val photos = total / PHOTO_BYTES
        val musicHours = total / MUSIC_HOUR_BYTES
        val blurays = total / BLURAY_BYTES
        if (photos >= 1) {
            Text(
                text = stringResource(R.string.stats_fun_photos, photos),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (musicHours >= 1) {
            Text(
                text = stringResource(R.string.stats_fun_music, musicHours),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (blurays >= 1) {
            Text(
                text = stringResource(R.string.stats_fun_blurays, blurays),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(VirgaSpacing.sm))
        // Only show records once there's a non-zero value (a first run of 0 bytes
        // would otherwise read "Biggest single backup: 0 B").
        if (state.largestRunBytes > 0) {
            Text(
                text = stringResource(
                    R.string.stats_fun_biggest,
                    formatFileSize(state.largestRunBytes),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.longestRunMillis > 0) {
            Text(
                text = stringResource(
                    R.string.stats_fun_longest,
                    formatDuration(state.longestRunMillis),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (state.currentStreakDays > 0) {
            Spacer(Modifier.height(VirgaSpacing.xs))
            Text(
                text = stringResource(
                    R.string.stats_fun_streak,
                    state.currentStreakDays,
                    state.longestStreakDays,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Reusable primitives
// ---------------------------------------------------------------------------

@Composable
private fun StatCard(
    number: String,
    label: String,
    modifier: Modifier = Modifier,
    numberStyle: TextStyle = MaterialTheme.typography.headlineMedium,
) {
    VirgaCard(modifier = modifier) {
        // Merge so TalkBack reads "<number>, <label>" as one item, not two.
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(
                text = number,
                style = numberStyle,
                maxLines = 1,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = VirgaSpacing.xs)
            .semantics { heading() },
    )
}

// ---------------------------------------------------------------------------
// Reset dialog
// ---------------------------------------------------------------------------

@Composable
private fun ResetConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stats_reset_title)) },
        text = { Text(stringResource(R.string.stats_reset_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.stats_reset_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.stats_reset_cancel))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Duration formatter
// ---------------------------------------------------------------------------

/** Humanizes a millisecond duration as "3h 42m", "58m", or "< 1m". */
internal fun formatDuration(millis: Long): String {
    if (millis <= 0) return "—"
    val totalMinutes = millis / 60_000L
    if (totalMinutes < 1) return "< 1m"
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}
