package com.github.fnunezkanut.bbcloud.review

import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.bitbucket.ItemCount
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestState

/**
 * What the yellow "pull request is behind its destination branch" bar shows.
 *
 * @param pullRequestId the pull request the count belongs to.
 * @param message the sentence, for example: This pull request is 2 commits behind "main".
 * @param tooltip the longer explanation shown on hover over the message.
 * @param sourceBranch the source branch of the pull request.
 * @param destinationBranch the destination branch of the pull request.
 */
data class BehindDestinationBarUiState(
    val pullRequestId: Long,
    val message: String,
    val tooltip: String,
    val sourceBranch: String,
    val destinationBranch: String
)

/**
 * Builds the state of the yellow bar, or null when nothing is shown: the pull request is not open, the number of
 * commits behind is unknown, or the pull request is up to date with its destination branch.
 */
internal fun behindDestinationBarStateOf(
    pullRequestId: Long,
    state: PullRequestState,
    commitsBehind: ItemCount?,
    sourceBranch: String,
    destinationBranch: String
): BehindDestinationBarUiState? {
    if (state != PullRequestState.Open || commitsBehind == null || commitsBehind.value == 0) {
        return null
    }
    val countText = if (commitsBehind.isTruncated) {
        DiffyBundle.message("main.branch.count.truncated", commitsBehind.value)
    } else {
        commitsBehind.value.toString()
    }
    return BehindDestinationBarUiState(
        pullRequestId = pullRequestId,
        message = DiffyBundle.message("main.branch.overview.behind", countText, commitsBehind.value, destinationBranch),
        tooltip = DiffyBundle.message("worktree.behindDestinationBar.tooltip", destinationBranch, sourceBranch),
        sourceBranch = sourceBranch,
        destinationBranch = destinationBranch
    )
}

/**
 * Whether the "Rebase onto latest with AI" link is shown: the Air plugin is available and the red "local branches are
 * behind Bitbucket" bar is not visible, because a force push would then discard commits the local branch lacks.
 */
internal fun shouldShowRebaseLink(isAirAvailable: Boolean, isRedBarVisible: Boolean): Boolean {
    return isAirAvailable && !isRedBarVisible
}