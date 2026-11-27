package com.github.fnunezkanut.bbcloud.ui.main

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.github.fnunezkanut.bbcloud.DiffyBundle
import com.github.fnunezkanut.bbcloud.bitbucket.MergeChecksFailure
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestAction
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestDetails
import com.intellij.openapi.diagnostic.logger

/** What the mergeability checks say about the Merge action. Every state except [NotBlocked] disables Merge. */
sealed interface MergeChecksState {
    /** Merge checks have not loaded yet, so Merge is disabled while they are being checked. */
    data object Unknown : MergeChecksState

    /** No merge check blocks merging. */
    data object NotBlocked : MergeChecksState

    /** At least one merge check blocks merging. [reasons] are the localized plain-text labels of the blocking checks. */
    data class Blocked(val reasons: List<String>) : MergeChecksState

    /** The current user may not merge into [branch]: no write access, a branch restriction, or the source repository is gone. */
    data class PermissionDenied(val branch: String) : MergeChecksState

    /** The destination branch requires the pull request to go through a merge queue. */
    data object QueueRequired : MergeChecksState

    /** The checks could not be read and no earlier result exists, so Merge stays disabled. */
    data class Unavailable(val failure: MergeChecksFailure) : MergeChecksState
}

/**
 * The immutable state of the pull-request detail view: the action buttons and the refresh button.
 *
 * An action is effectively enabled only when [actionsEnabled] is true and it is not in [disabledActions].
 */
data class PullRequestDetailUiState(
    val actions: List<PullRequestAction> = emptyList(),
    val actionsEnabled: Boolean = true,
    val disabledActions: Set<PullRequestAction> = emptySet(),
    val actionTooltips: Map<PullRequestAction, String> = emptyMap(),
    val refreshEnabled: Boolean = true
)

/**
 * Owns the state of the pull-request detail view: the action buttons and their enabled state, and the refresh button.
 *
 * The id of the pull request the view currently reflects is tracked so stale responses for a different pull request
 * are ignored.
 */
class PullRequestDetailViewModel(private val onActionRequested: (PullRequestAction) -> Unit, private val onRefreshRequested: () -> Unit) {
    val state: MutableState<PullRequestDetailUiState> = mutableStateOf(PullRequestDetailUiState())

    private var displayedPullRequestId: Long? = null
    private var mergeChecksState: MergeChecksState = MergeChecksState.Unknown
    private var displayedDetails: PullRequestDetails? = null

    /** Records which pull request the view reflects so [showActions] can reject stale responses. */
    fun bind(pullRequestId: Long) {
        val pullRequestChanged = pullRequestId != displayedPullRequestId
        if (pullRequestChanged) {
            // Preserve the last merge-check signal during an in-place refresh of the same PR to avoid button flicker;
            // this follows the in-place editor refresh behavior.
            mergeChecksState = MergeChecksState.Unknown
            displayedDetails = null
        }
        displayedPullRequestId = pullRequestId
        state.value = state.value.copy(
            actions = emptyList(),
            actionsEnabled = true,
            disabledActions = emptySet(),
            actionTooltips = emptyMap()
        )
    }

    /** Shows the actions offered by a pull request, ignoring the ones of a pull request that is no longer displayed. */
    fun showActions(details: PullRequestDetails) {
        if (details.summary.id != displayedPullRequestId) {
            LOG.debug("Ignoring actions for pull request #${details.summary.id}; the view shows #$displayedPullRequestId")
            return
        }
        displayedDetails = details
        recomputeActions()
    }

    /** Applies the merge-check result only when it belongs to the pull request currently displayed. */
    fun setMergeChecksState(pullRequestId: Long, state: MergeChecksState) {
        if (pullRequestId != displayedPullRequestId) {
            LOG.debug("Ignoring merge checks for pull request #$pullRequestId; the view shows #$displayedPullRequestId")
            return
        }
        if (mergeChecksState != state) {
            logTransition(pullRequestId = pullRequestId, state = state)
        }
        mergeChecksState = state
        recomputeActions()
    }

    /** One `info` line per transition; reasons are counted rather than logged because they can carry user-authored check names. */
    private fun logTransition(pullRequestId: Long, state: MergeChecksState) {
        val description = when (state) {
            MergeChecksState.Unknown -> "are still loading"
            MergeChecksState.NotBlocked -> "no longer block merging"
            is MergeChecksState.Blocked -> "block merging (${state.reasons.size} blocking)"
            is MergeChecksState.PermissionDenied -> "deny the current user permission to merge"
            MergeChecksState.QueueRequired -> "require a merge queue"
            is MergeChecksState.Unavailable -> "are unavailable (${state.failure})"
        }
        LOG.info("Merge checks $description on pull request #$pullRequestId")
    }

    private fun mergeTooltipFor(state: MergeChecksState): String? {
        return when (state) {
            MergeChecksState.NotBlocked -> null
            MergeChecksState.Unknown -> DiffyBundle.message("main.branch.action.merge.checking")
            is MergeChecksState.Blocked -> blockedTooltip(state.reasons)
            is MergeChecksState.PermissionDenied -> DiffyBundle.message("main.branch.action.merge.noPermission", state.branch)
            MergeChecksState.QueueRequired -> DiffyBundle.message("main.branch.action.merge.queueRequired")
            is MergeChecksState.Unavailable -> unavailableTooltip(state.failure)
        }
    }

    private fun blockedTooltip(reasons: List<String>): String {
        if (reasons.isEmpty()) {
            return DiffyBundle.message("main.branch.action.merge.blocked")
        }
        val shown = reasons.take(MAX_TOOLTIP_REASONS).joinToString(separator = ", ")
        val hidden = reasons.size - MAX_TOOLTIP_REASONS
        return if (hidden > 0) {
            DiffyBundle.message("main.branch.action.merge.blockedByMore", shown, hidden)
        } else {
            DiffyBundle.message("main.branch.action.merge.blockedBy", shown)
        }
    }

    private fun unavailableTooltip(failure: MergeChecksFailure): String {
        val key = when (failure) {
            MergeChecksFailure.CustomChecksUnavailable -> "main.branch.action.merge.customChecksUnavailable"
            MergeChecksFailure.RateLimited -> "main.branch.action.merge.rateLimited"
            MergeChecksFailure.Other -> "main.branch.action.merge.checksUnavailable"
        }
        return DiffyBundle.message(key)
    }

    private fun recomputeActions() {
        val details = displayedDetails ?: return
        val actions = PullRequestActionVisibility.visibleActions(
            state = details.summary.state,
            isDraft = details.summary.isDraft,
            isApprovedByCurrentUser = details.isApprovedByCurrentUser,
            isChangesRequestedByCurrentUser = details.isChangesRequestedByCurrentUser
        )
        val mergeTooltip = if (PullRequestAction.Merge in actions) mergeTooltipFor(mergeChecksState) else null
        val changesRequestActions = actions.filter { action ->
            action == PullRequestAction.RequestChanges || action == PullRequestAction.RemoveChangesRequest
        }
        val disabledActions = buildSet {
            if (mergeTooltip != null) {
                add(PullRequestAction.Merge)
            }
            if (details.isAuthoredByCurrentUser) {
                addAll(changesRequestActions)
            }
        }
        val actionTooltips = buildMap {
            if (mergeTooltip != null) {
                put(PullRequestAction.Merge, mergeTooltip)
            }
            if (details.isAuthoredByCurrentUser) {
                changesRequestActions.forEach { action ->
                    put(action, DiffyBundle.message("main.branch.action.requestChanges.ownPullRequest"))
                }
            }
        }
        state.value = state.value.copy(
            actions = actions,
            actionsEnabled = true,
            disabledActions = disabledActions,
            actionTooltips = actionTooltips
        )
    }

    fun setActionsEnabled(enabled: Boolean) {
        state.value = state.value.copy(actionsEnabled = enabled)
    }

    fun setRefreshEnabled(enabled: Boolean) {
        state.value = state.value.copy(refreshEnabled = enabled)
    }

    /** Reloads the pull request. */
    fun onRefreshClicked() {
        LOG.info("User clicked refresh on the pull request page")
        onRefreshRequested()
    }

    /** Runs an action and disables the action buttons until the action finishes. */
    fun onActionClicked(action: PullRequestAction) {
        if (action in state.value.disabledActions) {
            LOG.debug("Ignoring click on disabled $action for pull request #$displayedPullRequestId")
            return
        }
        LOG.info("User clicked the $action button on the action row of pull request #$displayedPullRequestId")
        state.value = state.value.copy(actionsEnabled = false)
        onActionRequested(action)
    }

    companion object {
        private val LOG = logger<PullRequestDetailViewModel>()

        private const val MAX_TOOLTIP_REASONS = 3
    }
}