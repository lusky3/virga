package app.lusk.virga.feature.sync

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.common.util.formatFileSize
import app.lusk.virga.core.designsystem.theme.LocalVirgaColors
import app.lusk.virga.core.designsystem.theme.VirgaGradients
import app.lusk.virga.core.designsystem.theme.VirgaSpacing
import app.lusk.virga.core.designsystem.theme.rememberReduceMotion

/**
 * Home header displayed at the top of the Sync task list (BRAND §4.5, §10, §11).
 *
 * Part 1 — Status hero: a §4.5 gradient surface (shape = large, padding = lg)
 * showing the overall sync state as color + glyph + text (BRAND §10 vocabulary).
 * The whole block is one merged semantics node with a heading role and a live
 * region so TalkBack announces state changes automatically.
 *
 * Part 2 — Stat-glance row: compact tappable summary ("X moved · N runs") that
 * navigates to the Stats screen. Hidden until [lifetimeRuns] > 0.
 *
 * Only rendered when tasks exist and the screen is not in selection mode — both
 * conditions are enforced at the [SyncTasksScreen] call site.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeHeader(
    homeStatus: HomeStatus,
    lifetimeBytes: Long,
    lifetimeRuns: Long,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    Column(modifier = modifier.fillMaxWidth()) {
        StatusHero(homeStatus = homeStatus, reduceMotion = reduceMotion)
        if (lifetimeRuns > 0L) {
            Spacer(Modifier.height(VirgaSpacing.sm))
            StatGlanceRow(
                lifetimeBytes = lifetimeBytes,
                lifetimeRuns = lifetimeRuns,
                onOpenStats = onOpenStats,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusHero(
    homeStatus: HomeStatus,
    reduceMotion: Boolean,
) {
    val gradient = VirgaGradients.hero()
    val shape = MaterialTheme.shapes.large
    val statusSentence = heroStatusSentence(homeStatus)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(gradient)
            // Scrim so on-hero text clears WCAG-AA on the lighter teal end (§4.5/§4.7).
            .background(VirgaGradients.heroScrim)
            .padding(VirgaSpacing.lg)
            // Merged semantics node: heading + live region so TalkBack announces
            // the status sentence whenever homeStatus changes (BRAND §14, a11y).
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = statusSentence
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        when (homeStatus) {
            is HomeStatus.Running -> RunningHeroContent(reduceMotion = reduceMotion)
            is HomeStatus.NeedsAttention -> NeedsAttentionHeroContent(count = homeStatus.count)
            is HomeStatus.UpToDate -> UpToDateHeroContent(lastBackupEpochMs = homeStatus.lastBackupEpochMs)
            HomeStatus.Idle -> IdleHeroContent()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RunningHeroContent(reduceMotion: Boolean) {
    val running = LocalVirgaColors.current.running
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Sync,
                contentDescription = null,
                tint = running,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(VirgaSpacing.xs))
            Text(
                text = stringResource(R.string.home_hero_running),
                style = MaterialTheme.typography.headlineMedium,
                color = VirgaGradients.onHero,
            )
        }
        Spacer(Modifier.height(VirgaSpacing.sm))
        // Precipitation progress (BRAND §12): wavy under normal motion;
        // static indeterminate bar under reduce-motion.
        if (reduceMotion) {
            LinearProgressIndicator(
                color = running,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearWavyProgressIndicator(
                color = running,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NeedsAttentionHeroContent(count: Int) {
    val warning = LocalVirgaColors.current.warning
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = warning,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(VirgaSpacing.xs))
        Text(
            text = pluralStringResource(R.plurals.home_hero_needs_attention, count, count),
            style = MaterialTheme.typography.headlineMedium,
            color = VirgaGradients.onHero,
        )
    }
}

@Composable
private fun UpToDateHeroContent(lastBackupEpochMs: Long?) {
    val success = LocalVirgaColors.current.success
    val relativeTime = remember(lastBackupEpochMs) {
        lastBackupEpochMs?.let {
            DateUtils.getRelativeTimeSpanString(
                it,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
        }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = success,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(VirgaSpacing.xs))
            Text(
                text = stringResource(R.string.home_hero_up_to_date),
                style = MaterialTheme.typography.headlineMedium,
                color = VirgaGradients.onHero,
            )
        }
        if (relativeTime != null) {
            Spacer(Modifier.height(VirgaSpacing.xs))
            Text(
                text = stringResource(R.string.home_hero_last_backup, relativeTime),
                style = MaterialTheme.typography.bodyMedium,
                color = VirgaGradients.onHero.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun IdleHeroContent() {
    Text(
        text = stringResource(R.string.home_hero_idle),
        style = MaterialTheme.typography.headlineMedium,
        color = VirgaGradients.onHero,
    )
}

@Composable
private fun StatGlanceRow(
    lifetimeBytes: Long,
    lifetimeRuns: Long,
    onOpenStats: () -> Unit,
) {
    val glanceCd = stringResource(
        R.string.home_glance_cd,
        formatFileSize(lifetimeBytes),
        lifetimeRuns,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onOpenStats)
            .padding(horizontal = VirgaSpacing.md, vertical = VirgaSpacing.sm)
            .semantics {
                role = Role.Button
                contentDescription = glanceCd
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                R.string.home_glance_label,
                formatFileSize(lifetimeBytes),
                lifetimeRuns,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Builds the TalkBack content description sentence for the hero. */
@Composable
private fun heroStatusSentence(homeStatus: HomeStatus): String = when (homeStatus) {
    is HomeStatus.Running -> stringResource(R.string.home_hero_running)
    is HomeStatus.NeedsAttention ->
        pluralStringResource(R.plurals.home_hero_needs_attention, homeStatus.count, homeStatus.count)
    is HomeStatus.UpToDate -> stringResource(R.string.home_hero_up_to_date)
    HomeStatus.Idle -> stringResource(R.string.home_hero_idle)
}
