package app.lusk.virga.feature.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/** Tier-1 throughput presets → rclone {transfers, checkers}. Tuned for mobile. */
private enum class PerfPreset(val transfers: Int, val checkers: Int, val labelRes: Int) {
    CONSERVATIVE(2, 4, R.string.sync_edit_perf_conservative),
    BALANCED(4, 8, R.string.sync_edit_perf_balanced),
    AGGRESSIVE(16, 32, R.string.sync_edit_perf_aggressive),
}

private fun presetFor(transfers: Int, checkers: Int): PerfPreset? =
    PerfPreset.entries.firstOrNull { it.transfers == transfers && it.checkers == checkers }

@Composable
internal fun PerformancePresetRow(form: SyncTaskForm, viewModel: SyncTaskEditViewModel) {
    val current = presetFor(form.transfers, form.checkers)
    val entries = PerfPreset.entries
    Column(verticalArrangement = Arrangement.spacedBy(VirgaSpacing.xs)) {
        Text(stringResource(R.string.sync_edit_perf_label), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            entries.forEachIndexed { index, p ->
                SegmentedButton(
                    selected = current == p,
                    onClick = {
                        viewModel.update { f -> f.copy(transfers = p.transfers, checkers = p.checkers) }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = entries.size),
                    label = { Text(stringResource(p.labelRes)) },
                )
            }
        }
        Text(
            text = stringResource(R.string.sync_edit_perf_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
