package app.lusk.virga.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.lusk.virga.core.designsystem.theme.LocalVirgaColors
import app.lusk.virga.core.designsystem.theme.VirgaSpacing

/** Visual state of a [VirgaCard] (BRAND §11). */
enum class VirgaCardState {
    /** Resting list card. */
    Default,

    /** Multi-select chosen — `secondaryContainer` tint. */
    Selected,

    /** A sync is actively running — `running`-tinted leading strip. */
    Active,
}

/**
 * The one card for the whole app (BRAND §11). Replaces the previous inconsistent
 * mix (`Surface` in `SyncTaskCard`, `ElevatedCard` in `RemoteCard`, raw `Card`
 * elsewhere). Uses tonal elevation (`surfaceContainer*`), the brand `medium`
 * shape, and [VirgaSpacing.md] inner padding.
 *
 * - [VirgaCardState.Default] → `surfaceContainerLow`.
 * - [VirgaCardState.Selected] → `secondaryContainer` tint (pair with a check
 *   affordance in the content).
 * - [VirgaCardState.Active] → a `running`-tinted leading strip (live transfer).
 *
 * Tap/long-press use [combinedClickable] so callers keep both gestures; pass a
 * `Modifier.semantics`/content-description via [modifier] or content as needed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VirgaCard(
    modifier: Modifier = Modifier,
    state: VirgaCardState = VirgaCardState.Default,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(VirgaSpacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container = when (state) {
        VirgaCardState.Default -> scheme.surfaceContainerLow
        VirgaCardState.Active -> scheme.surfaceContainerLow
        VirgaCardState.Selected -> scheme.secondaryContainer
    }
    val shape = MaterialTheme.shapes.medium
    val clickModifier = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
    } else {
        Modifier
    }

    Surface(
        color = container,
        shape = shape,
        modifier = modifier.fillMaxWidth().clip(shape).then(clickModifier),
    ) {
        Row(Modifier.fillMaxWidth()) {
            if (state == VirgaCardState.Active) {
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(LocalVirgaColors.current.running),
                )
            }
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}
