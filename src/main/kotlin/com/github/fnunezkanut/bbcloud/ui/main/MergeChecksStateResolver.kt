package com.github.fnunezkanut.bbcloud.ui.main

import com.github.fnunezkanut.bbcloud.bitbucket.MergeChecksFailure
import com.github.fnunezkanut.bbcloud.bitbucket.MergeChecksResult
import com.github.fnunezkanut.bbcloud.bitbucket.PullRequestMergeCheck

/** Derives the [MergeChecksState] that gates the Merge action from the mergeability checks result. */
object MergeChecksStateResolver {
    /**
     * A failed read keeps the last successful result ([previous]) when one exists, except HTTP 500: a real merge would fail
     * the same way, so it always disables Merge. A [MergeChecksResult.Loaded] result is resolved in this order: permission,
     * required merge queue, any other blocking check.
     */
    fun resolve(result: MergeChecksResult?, previous: MergeChecksResult.Loaded?, destinationBranch: String): MergeChecksState {
        return when (result) {
            null -> MergeChecksState.Unknown
            is MergeChecksResult.Loaded -> resolveLoaded(checks = result.checks, destinationBranch = destinationBranch)
            is MergeChecksResult.Unavailable -> resolveUnavailable(failure = result.reason, previous = previous, destinationBranch = destinationBranch)
        }
    }

    private fun resolveUnavailable(failure: MergeChecksFailure, previous: MergeChecksResult.Loaded?, destinationBranch: String): MergeChecksState {
        if (failure == MergeChecksFailure.CustomChecksUnavailable || previous == null) {
            return MergeChecksState.Unavailable(failure)
        }
        return resolveLoaded(checks = previous.checks, destinationBranch = destinationBranch)
    }

    private fun resolveLoaded(checks: List<PullRequestMergeCheck>, destinationBranch: String): MergeChecksState {
        val blocking = checks.filter { check -> check.isBlocking }
        return when {
            blocking.any { check -> check is PullRequestMergeCheck.PermissionCheck } -> {
                MergeChecksState.PermissionDenied(branch = destinationBranch)
            }
            blocking.any { check -> check is PullRequestMergeCheck.QueueCheck && check.isRequired && !check.isQueued } -> {
                MergeChecksState.QueueRequired
            }
            blocking.isNotEmpty() -> {
                MergeChecksState.Blocked(reasons = blocking.map { check -> MergeCheckLabels.toPlainText(MergeCheckLabels.labelFor(check)) })
            }
            else -> {
                MergeChecksState.NotBlocked
            }
        }
    }
}