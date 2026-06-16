package app.lusk.virga.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Canvas sparkline for a 30-day bytes trend. [data] must be non-empty. */
@Composable
fun SyncSparkline(
    data: List<Long>,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("sparkline"),
    ) {
        if (data.isNotEmpty() && data.any { it != 0L }) {
            val max = data.max().toFloat().coerceAtLeast(1f)
            val n = data.size
            val step = size.width / (n - 1).coerceAtLeast(1)
            val path = Path()
            data.forEachIndexed { i, v ->
                val x = i * step
                val y = size.height - (v.toFloat() / max) * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}
