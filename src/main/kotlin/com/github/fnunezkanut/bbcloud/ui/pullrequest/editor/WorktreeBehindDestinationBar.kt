@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.review.BehindDestinationBarUiState
import com.github.fnunezkanut.bbcloud.ui.components.DiffyPluginIcons
import com.github.fnunezkanut.bbcloud.ui.components.labelForeground
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

private val SEGMENT_GAP = 6.dp
private val LINK_ICON_GAP = 4.dp

/**
 * The yellow bar under the red bar that warns that the pull request is behind its destination branch.
 *
 * It is one line: the "behind destination" icon and the message with an ellipsis and a tooltip with the details on the
 * left, and the "Rebase onto latest with AI" link at the end. It renders nothing when the pull request is not proven
 * open or there is no [state].
 *
 * @param state what the bar shows, or null when the pull request is not behind.
 * @param isPullRequestOpen whether the pull request is proven open.
 * @param showRebaseLink whether the link is shown: the Air plugin is available and the red bar is not visible.
 * @param onRebaseWithAi puts the rebase prompt into a new Air chat.
 * @param modifier the modifier of the bar.
 */
@Composable
internal fun WorktreeBehindDestinationBar(
    state: BehindDestinationBarUiState?,
    isPullRequestOpen: Boolean,
    showRebaseLink: Boolean,
    onRebaseWithAi: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isPullRequestOpen || state == null) {
        return
    }
    val iconTooltip = DiffyBundle.message("worktree.behindDestinationBar.iconTooltip")
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .topAndBottomBorder(width = BORDER_WIDTH, color = WorktreeBehindDestinationBarColors.border())
                .background(color = WorktreeBehindDestinationBarColors.background())
                .padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = ROW_VERTICAL_PADDING)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP)
            ) {
                Tooltip(tooltip = { Text(text = iconTooltip) }) {
                    Icon(key = DiffyPluginIcons.behindDestination, contentDescription = iconTooltip)
                }
                // The weight sits on this Box, not on the Text: Tooltip wraps its content in its own container, so a weight on
                // the Text would not be a direct child of the Row and the link would follow the message instead of the edge.
                Box(modifier = Modifier.weight(1f)) {
                    Tooltip(tooltip = { Text(text = state.tooltip) }) {
                        Text(
                            text = state.message,
                            color = labelForeground(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (showRebaseLink) {
                    RebaseWithAiLink(onClick = onRebaseWithAi)
                }
            }
        }
        Spacer(modifier = Modifier.height(BANNER_BOTTOM_GAP))
    }
}

@Composable
private fun RebaseWithAiLink(onClick: () -> Unit) {
    Tooltip(tooltip = { Text(text = DiffyBundle.message("worktree.behindDestinationBar.rebaseWithAi.tooltip")) }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LINK_ICON_GAP)
        ) {
            Icon(
                key = DiffyPluginIcons.sendToAir,
                contentDescription = null,
                modifier = Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
            )
            Link(text = DiffyBundle.message("worktree.behindDestinationBar.rebaseWithAi"), onClick = onClick)
        }
    }
}