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
import com.github.fnunezkanut.bbcloud.git.LocalBranchState
import com.github.fnunezkanut.bbcloud.git.LocalFileStatus
import com.github.fnunezkanut.bbcloud.ui.components.labelForeground
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

private val SEGMENT_GAP = 6.dp

/**
 * The orange bar under the blue bar that marks a file with changes that are not in the pull request on Bitbucket yet:
 * unpushed commits, uncommitted edits, or both.
 *
 * Renders nothing when the pull request is not proven open, when there is no [state] or [relativePath], or when the file
 * has no such changes. A branch that is both ahead of and behind its remote still shows the bar and does not mention
 * "behind".
 *
 * @param state the shared local branch state.
 * @param relativePath the normalized project-relative path of the file the bar belongs to.
 * @param isPullRequestOpen whether the pull request is proven open.
 * @param showCommit whether the Commit link may be offered (the platform action exists).
 * @param showPush whether the Push link may be offered (the platform action exists).
 * @param onCommit opens the commit view.
 * @param onPush opens the push dialog.
 */
@Composable
internal fun WorktreeUnpushedChangesBar(
    state: LocalBranchState?,
    relativePath: String?,
    isPullRequestOpen: Boolean,
    showCommit: Boolean,
    showPush: Boolean,
    onCommit: () -> Unit,
    onPush: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isPullRequestOpen || state == null || relativePath == null) {
        return
    }
    val status = state.fileStatus(relativePath = relativePath)
    if (status == LocalFileStatus.InPullRequest) {
        return
    }
    val message = messageFor(state = state, status = status)
    val hasUncommitted = status == LocalFileStatus.Uncommitted || status == LocalFileStatus.UnpushedAndUncommitted
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .topAndBottomBorder(width = BORDER_WIDTH, color = WorktreeUnpushedChangesBarColors.border())
                .background(color = WorktreeUnpushedChangesBarColors.background())
                .padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = ROW_VERTICAL_PADDING)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SEGMENT_GAP)
            ) {
                Icon(key = AllIconsKeys.General.Warning, contentDescription = null)
                Tooltip(tooltip = { Text(text = message) }) {
                    Text(
                        text = message,
                        color = labelForeground(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (hasUncommitted && showCommit) {
                    LinkWithTooltip(text = DiffyBundle.message("worktree.unpushedBar.commit"), onClick = onCommit)
                }
                if (state.commitsAheadOfRemote > 0 && showPush) {
                    LinkWithTooltip(text = DiffyBundle.message("worktree.unpushedBar.push"), onClick = onPush)
                }
            }
        }
        Spacer(modifier = Modifier.height(BANNER_BOTTOM_GAP))
    }
}

@Composable
private fun LinkWithTooltip(text: String, onClick: () -> Unit) {
    Tooltip(tooltip = { Text(text = DiffyBundle.message("worktree.unpushedBar.tooltip")) }) {
        Link(text = text, onClick = onClick)
    }
}

private fun messageFor(state: LocalBranchState, status: LocalFileStatus): String {
    return when (status) {
        LocalFileStatus.Unpushed -> DiffyBundle.message(
            "worktree.unpushedBar.unpushed",
            state.sourceBranch,
            state.commitsAheadOfRemote,
            state.remoteName
        )
        LocalFileStatus.UnpushedAndUncommitted -> DiffyBundle.message(
            "worktree.unpushedBar.both",
            state.sourceBranch,
            state.commitsAheadOfRemote,
            state.remoteName
        )
        else -> DiffyBundle.message("worktree.unpushedBar.uncommitted")
    }
}