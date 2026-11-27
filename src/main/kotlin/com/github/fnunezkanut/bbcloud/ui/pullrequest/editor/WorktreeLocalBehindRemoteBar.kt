@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.github.fnunezkanut.bbcloud.ui.pullrequest.editor

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.review.LocalBehindRemoteBarUiState
import com.github.fnunezkanut.bbcloud.ui.components.DiffyPluginIcons
import com.github.fnunezkanut.bbcloud.ui.components.labelForeground
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip

private val SEGMENT_GAP = 6.dp

/**
 * The red bar under the blue bar that warns that a local pull request branch is behind Bitbucket.
 *
 * It is one line: the "local is behind remote" icon, the message with an ellipsis and a tooltip with the details, and
 * the sync link at the end. It renders nothing when the pull request is not proven open or there is no [state].
 *
 * @param state what the bar shows, or null when no local branch is behind.
 * @param isPullRequestOpen whether the pull request is proven open.
 * @param onSync fetches and fast-forwards the local branches that are behind, then reloads the page.
 * @param modifier the modifier of the bar.
 */
@Composable
internal fun WorktreeLocalBehindRemoteBar(state: LocalBehindRemoteBarUiState?, isPullRequestOpen: Boolean, onSync: () -> Unit, modifier: Modifier = Modifier) {
    if (!isPullRequestOpen || state == null) {
        return
    }
    val iconTooltip = DiffyBundle.message("worktree.localBehindBar.iconTooltip")
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .topAndBottomBorder(width = BORDER_WIDTH, color = WorktreeLocalBehindRemoteBarColors.border())
                .background(color = WorktreeLocalBehindRemoteBarColors.background())
                .padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = ROW_VERTICAL_PADDING)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP)
            ) {
                Tooltip(tooltip = { Text(text = iconTooltip) }) {
                    Icon(key = DiffyPluginIcons.localBehindRemote, contentDescription = iconTooltip)
                }
                Tooltip(tooltip = { Text(text = state.tooltip) }, modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.message,
                        color = labelForeground(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val linkText = if (state.updateInProgress) DiffyBundle.message("worktree.localBehindBar.syncing") else state.linkText
                Link(text = linkText, onClick = onSync, enabled = !state.updateInProgress)
            }
        }
        Spacer(modifier = Modifier.height(BANNER_BOTTOM_GAP))
    }
}