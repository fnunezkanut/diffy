package com.github.fnunezkanut.bbcloud.ui.otherprojects

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.github.fnunezkanut.bbcloud.ui.pullrequests.PullRequestsCard
import com.intellij.openapi.diagnostic.logger

/**
 * The immutable state of the workspace header of the Pull Requests: the workspace indicator, the open-PR count pill, the
 * Create new PR action, and the refresh action.
 *
 * A null [workspaceUrl] hides the green tick icon and the workspace link, and a null [openCountText] hides the count
 * pill. [workspaceName] is the link text shown after the tick (the workspace slug); it falls back to the last path
 * segment of [workspaceUrl] when null.
 */
data class WorkspaceHeaderState(
    val workspaceUrl: String? = null,
    val workspaceName: String? = null,
    val openCountText: String? = null,
    val isRefreshVisible: Boolean = false,
    val isRefreshEnabled: Boolean = true,
    /** Whether the "Create new PR" button is shown; true once the current project is a verified Bitbucket repository. */
    val isCreatePullRequestVisible: Boolean = false
)

/**
 * The open count the Pull Requests tab title shows for a counts callback, or null for no counter. Hidden and Error
 * show no counter, like the header pill. Loading returns [previous], so the title does not flicker while a refresh runs.
 */
internal fun tabOpenCount(openCount: Int, card: PullRequestsCard, previous: Int?): Int? {
    return when (card) {
        PullRequestsCard.Hidden, PullRequestsCard.Error -> null
        PullRequestsCard.Loading -> previous
        else -> openCount
    }
}

/**
 * Owns the workspace header of the Pull Requests: the green tick indicator, the open-PR count pill, the Create new PR
 * button, and the refresh button. One instance exists in [DiffyOtherProjectsPanel]. The pill counts the visible open
 * rows across all sources, after the Exclude filters and both dropdowns.
 */
class WorkspaceHeaderViewModel(private val onRefreshRequested: () -> Unit, private val onCreatePullRequestRequested: () -> Unit = {}) {
    val state: MutableState<WorkspaceHeaderState> = mutableStateOf(WorkspaceHeaderState())

    fun setWorkspaceUrl(url: String?, name: String? = null) {
        state.value = state.value.copy(workspaceUrl = url, workspaceName = name)
    }

    /** Sets the open pull request count shown in the header pill, or hides it when not [visible]. */
    fun setOpenCount(text: String?, visible: Boolean) {
        state.value = state.value.copy(openCountText = if (visible) text else null)
    }

    /**
     * Updates the open-count pill from a pull request list's counts callback. [openCount] is the number of visible open
     * rows after the Exclude filters, so toggling an Exclude checkbox that hides open rows updates the pill. The pill is
     * hidden on the Hidden, Loading, and Error cards, 
     */
    fun setOpenCount(openCount: Int, card: PullRequestsCard) {
        setOpenCount(
            text = openCount.toString(),
            visible = card != PullRequestsCard.Hidden &&
                card != PullRequestsCard.Loading &&
                card != PullRequestsCard.Error
        )
    }

    /** Shows the Create new PR button only when the current project is a verified Bitbucket repository. */
    fun showCurrentProject(lookup: CurrentProjectLookup?) {
        state.value = state.value.copy(isCreatePullRequestVisible = lookup is CurrentProjectLookup.Found)
    }

    fun setRefreshVisible(visible: Boolean) {
        state.value = state.value.copy(isRefreshVisible = visible)
    }

    fun setRefreshEnabled(enabled: Boolean) {
        state.value = state.value.copy(isRefreshEnabled = enabled)
    }

    /** Runs the refresh action the header offers. */
    fun onRefreshClicked() {
        LOG.info("User requested a reload of the Pull Requests")
        onRefreshRequested()
    }

    /** Runs the Create new PR action the header offers. */
    fun onCreatePullRequestClicked() {
        LOG.info("User clicked Create new PR in the Pull Requests footer")
        onCreatePullRequestRequested()
    }

    companion object {
        private val LOG = logger<WorkspaceHeaderViewModel>()
    }
}